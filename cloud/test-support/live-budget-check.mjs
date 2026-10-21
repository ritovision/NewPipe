// SPDX-License-Identifier: GPL-3.0-or-later
// Explicit small fenced QA. The only administrative mutation changes a temporary
// read ceiling, guarded by its old value; spending/tickets are never reset.
import assert from 'node:assert/strict';
import {readFile,writeFile,stat} from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {cloudflareClient,operatorClient} from '../operator/cloudflare.mjs';
import {validateScope,seal} from '../operator/recovery.mjs';
import {makeUpload} from '../src/service.ts';
import {changedPages} from '../src/pages.ts';
const run=promisify(execFile),[enrollmentPath,wrangler,outputPath]=process.argv.slice(2);
assert.equal((await stat(enrollmentPath)).mode&0o077,0);
const {plan,credentials}=JSON.parse(await readFile(enrollmentPath,'utf8'));
assert.equal(plan.digest,seal(plan.body));const s=validateScope(plan.body.scope);
assert.equal(s.worker,'newpipe-fence-qa-20261020');assert.equal(s.account,'b35814b119cba4448e3e0ac59442cc25');
assert.equal(s.database,'7fdf0708-4d39-4efb-946c-567109a6422b');assert.equal(s.fence.control,'9e60001d-359c-481e-8b1d-eaf2058f2f42');
assert.equal(credentials.length,3);const device=credentials[2],origin='https://'+s.worker+'.ritorhymes.workers.dev';
const cf=cloudflareClient(s,process.env.NEWPIPE_CLOUDFLARE_API_TOKEN),authority=await cf.authority();
assert.equal(authority.vars.ENABLE_CATALOG_API,'true');assert.equal(authority.vars.ENABLE_RECOVERY_API,'false');assert.equal(authority.vars.ENABLE_REPLACEMENT_API,'false');
const op=operatorClient(authority.operator.origin,process.env.NEWPIPE_OPERATOR_TOKEN);
const evidence={start:new Date().toISOString(),scope:s,authority,requests:[],cleanup:[],status:'RUNNING'};
await writeFile(outputPath,JSON.stringify(evidence)+'\n',{flag:'wx',mode:0o600});
async function save(){await writeFile(outputPath,JSON.stringify(evidence,null,2)+'\n',{mode:0o600});}
async function sql(text){
 const {stdout}=await run(wrangler,['application-budget-open.jsonc','d1','execute','CONTROL','--remote','--command',text,'--json'],{timeout:60000,maxBuffer:1048576});
 const result=JSON.parse(stdout);assert.equal(result.length,1);assert.equal(result[0].success,true);
 return result[0];
}
const ledger=async()=>(await sql(`SELECT state,read_limit,write_limit,spent_reads,spent_writes,held_reads,held_writes,held_growth,data_size,(SELECT COUNT(*) FROM cloud_fence_tickets) pending,(SELECT COUNT(*) FROM cloud_budget_maintenance) operator_holds FROM cloud_budget WHERE resource_id='${s.fence.resource}'`)).results[0];
async function request(path,data={},token=device.token){
 const start=performance.now(),r=await fetch(origin+path,{method:'POST',redirect:'manual',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(data),signal:AbortSignal.timeout(30000)});
 const value=await r.json();evidence.requests.push({at:new Date().toISOString(),path,status:r.status,milliseconds:Math.round(performance.now()-start),...(value.error?{error:value.error}:{})});await save();return {status:r.status,value};
}
const accepted=r=>{assert.equal(r.status,200,JSON.stringify(r.value));return r.value;};
const cleanup=async()=>{const r=await op('/maintenance/housekeep',{scope:s,cloudflareToken:process.env.NEWPIPE_CLOUDFLARE_API_TOKEN});evidence.cleanup.push(r);await save();return r;};
let restoreLimit=null;
try{
 const f=await cf.fence();assert.equal(f.state,'OPEN');assert.equal(f.pending,0);assert.equal(f.running,0);
 evidence.before=await ledger();assert.equal(evidence.before.state,'ACTIVE');assert.equal(evidence.before.held_reads,0);assert.equal(evidence.before.operator_holds,0);
 assert.equal(evidence.before.read_limit,2000000);
 const head=accepted(await request('/v1/connect'));assert.equal(head.scope.service,s.generation);
 const invalid=await request('/v1/head',{},'device-missing.'+'0'.repeat(64));assert.equal(invalid.status,401);
 const pages=(await cf.db.query('SELECT page_id AS id,body FROM cloud_catalog_pages WHERE library_id=? ORDER BY page_id LIMIT 9',[s.library]));assert.ok(pages.length>0&&pages.length<=8);
 assert.equal(head.deviceState.upload,null);const sequence=String(BigInt(head.deviceState.resolvedSequence)+1n);
 const upload=await makeUpload('sync',sequence,head.head,head.head.fingerprint,changedPages([],pages));
 accepted(await request('/v1/uploads/start',upload.header));
 // Active work must survive cleanup; no arbitrary expiration or DATA admin writes.
 for(let i=0;i<3;i++)await cleanup();
 assert.equal((await cf.db.query('SELECT COUNT(*) n FROM cloud_uploads WHERE library_id=? AND device_id=? AND status=\'UPLOADING\'',[s.library,device.device]))[0].n,1);
 const retired=accepted(await request('/v1/uploads/retire',{sequence,digest:upload.header.digest}));assert.equal(retired.receipt.outcome,'RETIRED');
 for(let i=0;i<3;i++)await cleanup();
 assert.ok(evidence.cleanup.some(r=>r.terminalRemoved===1));
 assert.equal((await cf.db.query('SELECT COUNT(*) n FROM cloud_uploads WHERE library_id=? AND device_id=?',[s.library,device.device]))[0].n,0);
 assert.equal((await cf.db.query('SELECT CAST(resolved_sequence AS TEXT) n FROM cloud_devices WHERE library_id=? AND device_id=?',[s.library,device.device]))[0].n,sequence);
 for(let i=0;i<24;i++){assert.deepEqual(accepted(await request('/v1/head')).head,head.head);}
 const beforeLimit=await ledger();assert.equal(beforeLimit.held_reads,0);assert.equal(beforeLimit.operator_holds,0);assert.equal(beforeLimit.state,'ACTIVE');
 restoreLimit=beforeLimit.spent_reads;
 const lowered=await sql(`UPDATE cloud_budget SET read_limit=${restoreLimit} WHERE resource_id='${s.fence.resource}' AND state='ACTIVE' AND read_limit=2000000 AND held_reads=0 AND held_writes=0 RETURNING read_limit`);
 assert.equal(lowered.results.length,1);
 const next=await makeUpload('sync',String(BigInt(sequence)+1n),head.head,head.head.fingerprint,changedPages([],pages));
 const denied=await request('/v1/uploads/start',next.header);assert.equal(denied.status,503);assert.equal(denied.value.error,'BUDGET_OR_MAINTENANCE_UNAVAILABLE');
 evidence.exhaustion=await ledger();assert.equal(evidence.exhaustion.pending,0);assert.equal(evidence.exhaustion.held_reads,0);
 assert.ok(evidence.exhaustion.spent_reads>=beforeLimit.spent_reads+16);
 assert.equal((await cf.db.query('SELECT COUNT(*) n FROM cloud_uploads WHERE library_id=? AND device_id=?',[s.library,device.device]))[0].n,0);
 const restored=await sql(`UPDATE cloud_budget SET read_limit=2000000 WHERE resource_id='${s.fence.resource}' AND read_limit=${restoreLimit} AND state='ACTIVE' RETURNING read_limit`);assert.equal(restored.results.length,1);restoreLimit=null;
 assert.deepEqual(accepted(await request('/v1/head')).head,head.head);
 const archives=await cf.db.query("SELECT slot,archive_id,digest,object_etag FROM cloud_backup_slots WHERE library_id=? AND state='READY' ORDER BY slot",[s.library]);
 const previous=plan.body.snapshot.find(x=>x.table==='cloud_backup_slots');
 const expected=JSON.parse(previous.state).map(row=>Object.fromEntries(previous.columns.map((c,i)=>[c,row[i]]))).map(r=>({slot:Number(r.slot),archive_id:r.archive_id,digest:r.digest,object_etag:r.object_etag}));
 assert.deepEqual(archives,expected);evidence.archivesPreserved=archives.length;
 evidence.after=await ledger();assert.equal(evidence.after.held_reads,0);assert.equal(evidence.after.held_writes,0);assert.equal(evidence.after.pending,0);assert.equal(evidence.after.operator_holds,0);assert.equal(evidence.after.state,'ACTIVE');
 evidence.fence=await cf.fence();assert.equal(evidence.fence.pending,0);assert.equal(evidence.fence.running,0);
 evidence.status='PASS';evidence.end=new Date().toISOString();await save();
 console.log(JSON.stringify({passed:true,requests:evidence.requests.length,activeStagePreserved:true,terminalStageRemoved:true,exhaustionRefused:true,archivesPreserved:archives.length,before:evidence.before,after:evidence.after}));
}catch(e){evidence.status='FAIL';evidence.error=e instanceof Error?e.message:'unknown';await save();throw e;}
finally{
 if(restoreLimit!==null){
  const restored=await sql(`UPDATE cloud_budget SET read_limit=2000000 WHERE resource_id='${s.fence.resource}' AND read_limit=${restoreLimit} AND state='ACTIVE' RETURNING read_limit`);
  evidence.emergencyLimitRestore=restored.results.length;await save();
 }
}
