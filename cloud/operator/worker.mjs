// SPDX-License-Identifier: GPL-3.0-or-later
// Separate authenticated operator deployment. Never a public app endpoint.
import {timingSafeEqual} from 'node:crypto';
import {canonical,seal,fail,OperatorError,validateScope,authorityBlockers,fenceBlockers,verifyPlan,rebuildBatch} from './recovery.mjs';
import {recoveryStore} from './adapters.mjs';
import {executeReopen,finishReopen} from './reopen.mjs';
import {cloudflareClient} from './cloudflare.mjs';
import {BudgetError,requestEstimate} from '../src/budget.ts';
import {admit,admitMaintenanceBudget,admitControlBudget,FenceError} from '../src/fence.ts';
import {housekeep} from '../src/housekeeping.ts';
import {fenceState,closeFence,requireHeld,claimAction,finishAction} from '../src/maintenance.ts';
import {RecoveryError} from '../src/recovery-format.ts';
const response=(value,status=200)=>Response.json(value,{status,headers:{'Cache-Control':'no-store, private','X-Content-Type-Options':'nosniff'}});
const bookmarkPattern=/^[0-9a-f]{8}-[0-9a-f]{8}-[0-9a-f]{8}-[0-9a-f]{32}$/;
async function body(request){
 const reader=request.body?.getReader();if(!reader)fail('BODY_REQUIRED');
 const chunks=[];let n=0;
 try{while(true){const r=await reader.read();if(r.done)break;n+=r.value.length;if(n>2*1024*1024){await reader.cancel();fail('OPERATOR_BODY_LIMIT');}chunks.push(r.value);}}
 finally{reader.releaseLock();}
 return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
const query=db=>({query:async(sql,params)=>(await db.prepare(sql).bind(...params).all()).results});
const receipt=async(db,digest)=>db.prepare('SELECT plan_digest,maintenance_id,library_id,service_generation FROM cloud_operator_receipts WHERE plan_digest=?').bind(digest).first();
export async function existing(env,scope,maintenance,id,kind){
 const a=await env.CONTROL.prepare('SELECT a.kind,a.state,a.result,f.state AS fence_state,f.maintenance_id AS active_maintenance,NOT EXISTS(SELECT 1 FROM cloud_fence_actions later WHERE later.resource_id=a.resource_id AND later.rowid>a.rowid) AS current FROM cloud_fence_actions a JOIN cloud_fence f ON f.resource_id=a.resource_id WHERE a.resource_id=? AND a.maintenance_id=? AND a.action_id=?').bind(scope.fence.resource,maintenance,id).first();
 if(!a)return null;if(a.kind!==kind)fail('ACTION_ID_CONFLICT');
 if(a.state==='DONE'){
  const current=a.current&&(kind==='reopen'?a.fence_state==='OPEN':a.active_maintenance===maintenance&&a.fence_state==='HELD');
  return current?{state:'DONE',result:JSON.parse(a.result)}:{state:'SUPERSEDED',action:id};
 }
 if(kind==='reopen'){const done=await finishReopen(env,scope,maintenance,id);if(done)return done;}
 if(kind==='apply'){
  const r=await receipt(env.DB,id);
  if(r&&r.maintenance_id===maintenance){
   const result={digest:id,committed:true};await finishAction(env.CONTROL,maintenance,id,result);
   return {state:'DONE',result};
  }
 }
 return {state:'RUNNING',error:'OPERATOR_OUTCOME_UNKNOWN'};
}
export async function applyPlan(env,plan,actualAuthority,store){
 if(!plan?.body||plan.digest!==seal(plan.body))fail('PLAN_SEAL_MISMATCH');
 const scope=validateScope(plan.body.scope);
 if(!scope.fence||plan.body.blockers.length||authorityBlockers(actualAuthority,scope).length)fail('PLAN_BLOCKED');
 if(canonical(plan.body.authority)!==canonical(actualAuthority))fail('PLAN_STALE');
 const f=await fenceState(env.CONTROL,scope.fence.resource);
 const done=await existing(env,scope,plan.body.fence?.maintenance_id,plan.digest,'apply');if(done)return done;
 if(fenceBlockers(f,scope).length||canonical(f)!==canonical(plan.body.fence))fail('PLAN_STALE');
 // Reinspect every object, multipart upload and SQL table under the same fence.
 // The regenerated plan also prevents a resealed file from supplying arbitrary SQL.
 if(!store)fail('SCOPED_R2_CREDENTIALS_REQUIRED');
 try {
  await verifyPlan({store,db:query(env.DB),authority:async()=>actualAuthority,
   fence:()=>fenceState(env.CONTROL,scope.fence.resource)},plan);
 } catch(e) {
  if(e instanceof OperatorError||e instanceof RecoveryError||e instanceof FenceError||e instanceof BudgetError)throw e;
  // Only runtime frame locations, never exception text, plan data or credentials.
  console.error(JSON.stringify({phase:'reinspect',type:['TypeError','ReferenceError','TimeoutError','AccessDenied','SignatureDoesNotMatch'].includes(e?.name)?e.name:'StorageError',
   frames:typeof e?.stack==='string'?e.stack.split('\n').slice(1,4).filter(line=>/^\s+at /.test(line)):[]}));
  fail('OPERATOR_REINSPECTION_UNAVAILABLE');
 }
 const batch=rebuildBatch(plan);
 batch.push({sql:"INSERT INTO cloud_operator_receipts(plan_digest,maintenance_id,library_id,service_generation,completed_at) VALUES(?,?,?,?,CAST(unixepoch('subsec')*1000 AS INTEGER))",
  params:[plan.digest,f.maintenance_id,scope.library,scope.generation]});
 await claimAction(env.CONTROL,f,plan.digest,'apply');
 // Any error leaves RUNNING. A later status can prove a commit from the receipt,
 // which is in this same transaction. Never automatically submit the batch twice.
 await env.DB.batch(batch.map(s=>env.DB.prepare(s.sql).bind(...s.params)));
 const result={digest:plan.digest,committed:true};
 await finishAction(env.CONTROL,f.maintenance_id,plan.digest,result);
 return {state:'DONE',result};
}
export default {async fetch(request,env){
 let phase='authenticate',budget;
 try{
  const url=new URL(request.url),auth=request.headers.get('authorization')??'',expected='Bearer '+(env.OPERATOR_TOKEN??'');
  if(!env.OPERATOR_TOKEN||auth.length!==expected.length||!timingSafeEqual(Buffer.from(auth),Buffer.from(expected)))return response({error:'AUTH_REJECTED'},403);
  if(request.method!=='POST'||url.origin!==env.PUBLIC_ORIGIN||url.search||request.headers.has('origin')||
    request.headers.get('content-type')!=='application/json')fail('OPERATOR_REQUEST_REJECTED');
  const input=await body(request),scope=validateScope(input.scope??input.plan?.body?.scope),configured=validateScope(JSON.parse(env.SCOPE));
  if(!scope.fence||canonical(scope)!==canonical(configured)||env.FENCE_RESOURCE!==scope.fence.resource)fail('OPERATOR_SCOPE_MISMATCH');
  if(url.pathname==='/maintenance/state'||url.pathname==='/maintenance/close'){
   budget=await admitControlBudget(env.CONTROL,{resource:scope.fence.resource,library:scope.library,generation:scope.generation});
   const control=budget.database(env.CONTROL,false);
   return response(url.pathname==='/maintenance/state'?await fenceState(control,scope.fence.resource):
    await closeFence(control,scope.fence.resource,input.maintenance,scope.generation));
  }
  phase='authority';
  const client=cloudflareClient(scope,input.cloudflareToken);
  const authority=await client.authority();
  if(authorityBlockers(authority,scope,url.pathname!=='/maintenance/housekeep').length)fail('LIVE_AUTHORITY_BLOCKED');
  if(url.pathname==='/maintenance/housekeep'){
   phase='housekeeping';
   const ticket=await admit(env.CONTROL,{resource:scope.fence.resource,library:scope.library,generation:scope.generation},requestEstimate(url.pathname));
   try{return response(await housekeep(ticket.database(env.DB),scope.library,scope.generation,{...input.limits,devices:Math.min(input.limits?.devices??2,2)}));}
   finally{await ticket.finish();}
  }
  budget=await admitMaintenanceBudget(env.CONTROL,{resource:scope.fence.resource,library:scope.library,generation:scope.generation});
  env={...env,DB:budget.database(env.DB),CONTROL:budget.database(env.CONTROL,false),RECOVERY:budget.bucket(env.RECOVERY)};
  if(url.pathname==='/recovery/apply'){
   phase='s3-client';const store=recoveryStore(scope,input.r2Credentials);
   phase='apply';return response(await applyPlan(env,input.plan,authority,store));
  }
  if(url.pathname==='/recovery/reopen'){
   if(!input.plan?.body||input.plan.digest!==seal(input.plan.body))fail('PLAN_SEAL_MISMATCH');
   const done=await existing(env,scope,input.plan.body.fence?.maintenance_id,input.plan.digest,'reopen');
   return response(done??await executeReopen(env,input.plan,authority,recoveryStore(scope,input.r2Credentials)));
  }
  if(url.pathname==='/recovery/status'){
   if(!/^[0-9a-f]{64}$/.test(input.action))fail('INVALID_ACTION_ID');
   const f=await fenceState(env.CONTROL,scope.fence.resource);
   return response(await existing(env,scope,input.maintenance??f.maintenance_id,input.action,input.kind)??{state:'ABSENT'});
  }
  const f=await fenceState(env.CONTROL,scope.fence.resource);
  if(fenceBlockers(f,scope).length && f.running===0)fail('MAINTENANCE_NOT_EXCLUSIVE');
  if(url.pathname==='/recovery/restore'){
   if(typeof input.bookmark!=='string'||!bookmarkPattern.test(input.bookmark))fail('EXACT_BOOKMARK_REQUIRED');
   const id=seal({kind:'restore',maintenance:input.maintenance,revision:input.revision,bookmark:input.bookmark});
   const prior=await existing(env,scope,f.maintenance_id,id,'restore');if(prior)return response({...prior,action:id});
   if(input.maintenance!==f.maintenance_id||input.revision!==f.revision)fail('STALE_MAINTENANCE_INTENT');
   requireHeld(f);await claimAction(env.CONTROL,f,id,'restore');
   const result=await budget.effect(()=>client.restore(input.bookmark));
   if(!bookmarkPattern.test(result.bookmark)||!bookmarkPattern.test(result.previous_bookmark))fail('RESTORE_OUTCOME_UNKNOWN');
   await finishAction(env.CONTROL,f.maintenance_id,id,result);return response({state:'DONE',action:id,result});
  }
  if(url.pathname==='/recovery/abort'){
   const prefix='recovery/'+scope.library+'/';
   if(typeof input.key!=='string'||!input.key.startsWith(prefix)||!/^([0-2])\/[0-9a-f-]{36}$/.test(input.key.slice(prefix.length))||
    typeof input.uploadId!=='string'||!input.uploadId||input.uploadId.length>1024)fail('EXACT_UPLOAD_REQUIRED');
   const id=seal({kind:'abort',maintenance:input.maintenance,revision:input.revision,key:input.key,uploadId:input.uploadId});
   const prior=await existing(env,scope,f.maintenance_id,id,'abort');if(prior)return response({...prior,action:id});
   if(input.maintenance!==f.maintenance_id||input.revision!==f.revision)fail('STALE_MAINTENANCE_INTENT');
   requireHeld(f);await claimAction(env.CONTROL,f,id,'abort');
   await env.RECOVERY.resumeMultipartUpload(input.key,input.uploadId).abort();
   const result={key:input.key,uploadId:input.uploadId,aborted:true};
   await finishAction(env.CONTROL,f.maintenance_id,id,result);return response({state:'DONE',action:id,result});
  }
  fail('NOT_FOUND');
 }catch(e){
  if(!(e instanceof OperatorError||e instanceof RecoveryError||e instanceof FenceError||e instanceof BudgetError))console.error(JSON.stringify({phase,
   type:['TypeError','ReferenceError','TimeoutError'].includes(e?.name)?e.name:'StorageError',
   frames:typeof e?.stack==='string'?e.stack.split('\n').slice(1,4).filter(line=>/^\s+at /.test(line)):[]}));
  return response({error:e instanceof OperatorError||e instanceof RecoveryError||e instanceof FenceError||e instanceof BudgetError?e.code:'OPERATOR_OUTCOME_UNKNOWN'},503);
 }finally{await budget?.finish();}
}};
