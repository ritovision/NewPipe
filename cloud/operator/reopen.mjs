// SPDX-License-Identifier: GPL-3.0-or-later
import {canonical,seal,fail,inspect,validateScope} from './recovery.mjs';
import {readPages,pageManifest,byteLength} from '../src/pages.ts';
import {fingerprint} from '../src/catalog.ts';
import {fenceState,claimAction} from '../src/maintenance.ts';
const rows=(p,name)=>{const t=p.snapshot.find(t=>t.table===name);return JSON.parse(t.state).map(r=>Object.fromEntries(t.columns.map((c,i)=>[c,r[i]])));};
export function enrollments(value){
 if(!Array.isArray(value)||value.length<1||value.length>16)fail('ONE_TO_SIXTEEN_FRESH_DEVICES_REQUIRED');
 const ids=new Set(),hashes=new Set();
 for(const d of value){
  if(!d||Object.keys(d).sort().join(',')!=='device,tokenHash,writable'||
   typeof d.device!=='string'||!/^device-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(d.device)||
   typeof d.tokenHash!=='string'||!/^[0-9a-f]{64}$/.test(d.tokenHash)||typeof d.writable!=='boolean'||
   ids.has(d.device)||hashes.has(d.tokenHash))fail('INVALID_FRESH_DEVICE');
  ids.add(d.device);hashes.add(d.tokenHash);
 }
 return value;
}
export async function latestAction(control,scope,maintenance){
 return control.prepare('SELECT action_id,kind,state FROM cloud_fence_actions WHERE resource_id=? AND maintenance_id=? ORDER BY rowid DESC LIMIT 1')
  .bind(scope.fence.resource,maintenance).first();
}
export async function makeReopenPlan(adapters,scope,devices){
 validateScope(scope);enrollments(devices);
 // Time Travel can predate this data migration. Refuse before an action claim.
 const receiptColumns=await adapters.db.query("PRAGMA table_info('cloud_reopen_receipts')",[]);
 const receiptSchema=['action_id','maintenance_id','library_id','service_generation','recovery_digest','devices','completed_at'];
 if(receiptColumns.length!==receiptSchema.length||receiptColumns.some((c,i)=>c.name!==receiptSchema[i]||c.type!==(i===6?'INTEGER':'TEXT')||c.pk!==(i===0?1:0)))fail('REOPEN_SCHEMA_UPGRADE_REQUIRED');
 const body=await inspect(adapters,scope);
 if(!scope.fence||body.blockers.some(b=>b!=='FRESH_EXTERNAL_GENERATION_REQUIRED'))fail('REOPEN_BLOCKED');
 const oldDevices=rows(body,'cloud_devices'),control=rows(body,'cloud_backup_control'),slots=rows(body,'cloud_backup_slots');
 if(oldDevices.some(d=>d.revoked!=='1'||d.can_write!=='0')||
  devices.some(d=>oldDevices.some(o=>o.device_id===d.device||o.token_hash===d.tokenHash))||
  control.length!==1||control[0].service_generation!==scope.generation||
  rows(body,'cloud_uploads').length||rows(body,'cloud_backup_approvals').length||rows(body,'cloud_replace_approvals').length||
  rows(body,'cloud_replace_grants').some(g=>g.can_replace!=='0')||
  rows(body,'cloud_backup_operations').some(o=>!['DONE','CANCELED'].includes(o.state))||
  rows(body,'cloud_replace_attempts').some(o=>!['DONE','CANCELED'].includes(o.state)))fail('RECOVERED_AUTHORITY_REQUIRED');
 if(slots.length!==body.archives.length||slots.some(s=>{
  const a=body.archives.find(a=>a.slot===Number(s.slot));
  return !a||s.state!=='READY'||s.archive_id!==a.archive||s.object_key!==a.key||s.object_etag!==a.etag||
   s.digest!==a.digest||canonical(JSON.parse(s.manifest))!==canonical(a.manifest)||s.busy_token!==null||s.pin_id!==null;
 }))fail('RECOVERED_INDEX_MISMATCH');
 const heads=rows(body,'cloud_head'),infos=rows(body,'cloud_catalog_info');
 const pages=rows(body,'cloud_catalog_pages').map(p=>({id:p.page_id,body:p.body}));
 if(heads.length!==1||infos.length!==1||infos[0].initialized!=='1'||
  Number(infos[0].page_count)!==pages.length||Number(infos[0].payload_bytes)!==pages.reduce((n,p)=>n+byteLength(p.body),0)||
  await pageManifest(pages)!==infos[0].manifest||await fingerprint((await readPages(pages)).catalog)!==heads[0].fingerprint)fail('RECOVERED_CATALOG_INVALID');
 const latest=await adapters.latestAction(body.fence.maintenance_id);
 if(!latest||latest.kind!=='apply'||latest.state!=='DONE')fail('LATEST_RECOVERY_RECEIPT_REQUIRED');
 const receipts=await adapters.db.query('SELECT plan_digest FROM cloud_operator_receipts WHERE plan_digest=? AND maintenance_id=? AND library_id=? AND service_generation=?',
  [latest.action_id,body.fence.maintenance_id,scope.library,scope.generation]);
 if(receipts.length!==1)fail('LATEST_RECOVERY_RECEIPT_REQUIRED');
 if(canonical(await adapters.fence())!==canonical(body.fence))fail('MAINTENANCE_CHANGED');
 const result={...body,kind:'reopen',devices,recovery:latest.action_id,blockers:[]};
 return {body:result,digest:seal(result)};
}
export async function finishReopen(env,scope,maintenance,id){
 const r=await env.DB.prepare('SELECT maintenance_id,library_id,service_generation,devices FROM cloud_reopen_receipts WHERE action_id=?').bind(id).first();
 if(!r||r.maintenance_id!==maintenance||r.library_id!==scope.library||r.service_generation!==scope.generation)return null;
 const result={digest:id,opened:true,generation:scope.generation,devices:JSON.parse(r.devices).map(d=>d.device)};
 // OPEN and DONE are one external transaction. No remaining data effect follows it.
 await env.CONTROL.batch([
  env.CONTROL.prepare("INSERT INTO cloud_fence_assertion(slot,ok) VALUES(1,(SELECT COUNT(*) FROM cloud_fence f JOIN cloud_fence_actions a ON a.resource_id=f.resource_id AND a.maintenance_id=f.maintenance_id WHERE f.resource_id=? AND f.state='HELD' AND f.maintenance_id=? AND f.next_generation=? AND a.action_id=? AND a.kind='reopen' AND a.state='RUNNING' AND NOT EXISTS(SELECT 1 FROM cloud_fence_tickets t WHERE t.resource_id=f.resource_id)))").bind(scope.fence.resource,maintenance,scope.generation,id),
  env.CONTROL.prepare("UPDATE cloud_fence SET state='OPEN',service_generation=next_generation,maintenance_id=NULL,next_generation=NULL,revision=revision+1 WHERE resource_id=?").bind(scope.fence.resource),
  env.CONTROL.prepare("UPDATE cloud_fence_actions SET state='DONE',result=? WHERE resource_id=? AND maintenance_id=? AND action_id=? AND state='RUNNING'").bind(JSON.stringify(result),scope.fence.resource,maintenance,id),
  env.CONTROL.prepare('DELETE FROM cloud_fence_assertion WHERE slot=1')
 ]);
 return {state:'DONE',result};
}
export async function executeReopen(env,plan,authority,store){
 const scope=validateScope(plan?.body?.scope);
 if(plan.digest!==seal(plan.body)||plan.body.kind!=='reopen')fail('PLAN_SEAL_MISMATCH');
 const current=await makeReopenPlan({db:{query:async(sql,params)=>(await env.DB.prepare(sql).bind(...params).all()).results},
  store,authority:async()=>authority,fence:()=>fenceState(env.CONTROL,scope.fence.resource),
  latestAction:m=>latestAction(env.CONTROL,scope,m)},scope,plan.body.devices);
 if(current.digest!==plan.digest)fail('PLAN_STALE');
 const p=current.body,f=p.fence,statement=(sql,...params)=>env.DB.prepare(sql).bind(...params);
 const batch=[
  statement('INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,CASE WHEN '+p.snapshot.map(s=>'(('+s.sql+')=?)').join(' AND ')+' THEN 1 ELSE 0 END)',...p.snapshot.flatMap(s=>[scope.library,s.state])),
  statement('DELETE FROM cloud_commit_assertion WHERE slot=1'),
  statement('INSERT INTO cloud_devices(library_id,device_id,token_hash,service_generation,auth_generation,revoked,can_write,resolved_sequence) SELECT ?,json_extract(value,\'$.device\'),json_extract(value,\'$.tokenHash\'),?,1,0,json_extract(value,\'$.writable\'),0 FROM json_each(?)',scope.library,scope.generation,JSON.stringify(p.devices)),
  statement("INSERT INTO cloud_reopen_receipts VALUES(?,?,?,?,?,?,CAST(unixepoch('subsec')*1000 AS INTEGER))",plan.digest,f.maintenance_id,scope.library,scope.generation,p.recovery,JSON.stringify(p.devices))
 ];
 await claimAction(env.CONTROL,f,plan.digest,'reopen');
 // Ambiguity retains RUNNING. Only this transaction's receipt may open the gate.
 await env.DB.batch(batch);
 const done=await finishReopen(env,scope,f.maintenance_id,plan.digest);
 if(!done)fail('REOPEN_OUTCOME_UNKNOWN');return done;
}
