// SPDX-License-Identifier: GPL-3.0-or-later
import {fail,canonical,validateScope} from './recovery.mjs';
export function cloudflareClient(scope,token,fetcher=globalThis.fetch.bind(globalThis)) {
 validateScope(scope);
 if(typeof token!=='string'||!token)fail('CLOUDFLARE_TOKEN_REQUIRED');
 const base='https://api.cloudflare.com/client/v4/accounts/'+scope.account;
 const call=async(path,body)=>{
  const r=await fetcher(base+path,{method:body===undefined?'GET':'POST',redirect:'manual',
   headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},
   ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});
  if(!r.ok)fail('CLOUDFLARE_HTTP_'+r.status);
  const v=await r.json();if(v.success!==true||v.result==null)fail('CLOUDFLARE_RESPONSE_FAILED');return v.result;
 };
 async function deployment(worker,names){
  const script='/workers/scripts/'+encodeURIComponent(worker);
  const d=(await call(script+'/deployments'))?.deployments?.[0];
  if(!d||d.versions?.length!==1||d.versions[0].percentage!==100)fail('SPLIT_OR_MISSING_DEPLOYMENT');
  const v=await call(script+'/versions/'+encodeURIComponent(d.versions[0].version_id)),settings=await call(script+'/settings');
  const relevant=bindings=>{
   if(!Array.isArray(bindings))fail('WORKER_BINDINGS_UNAVAILABLE');
   const out={};
   for(const n of names){
    const found=bindings.filter(b=>b.name===n);if(found.length!==1)fail('WORKER_BINDING_MISSING_OR_DUPLICATE');
    const b=found[0];out[n]=(n==='DB'||n==='CONTROL')?(b.type==='d1'?b.id:null):
     n==='RECOVERY'?(b.type==='r2_bucket'?b.bucket_name:null):(b.type==='plain_text'?b.text:null);
   }
   return out;
  };
  const b=relevant(v.resources?.bindings);
  if(v.id!==d.versions[0].version_id||canonical(b)!==canonical(relevant(settings.bindings)))fail('WORKER_SETTINGS_VERSION_MISMATCH');
  if(canonical((await call(script+'/deployments'))?.deployments?.[0])!==canonical(d))fail('DEPLOYMENT_CHANGED');
  return {deployment:d.id,version:v.id,b};
 }
 async function authority(){
  const names=['DB','RECOVERY','ENABLE_CATALOG_API','ENABLE_RECOVERY_API','ENABLE_REPLACEMENT_API','LIBRARY_ID','SERVICE_GENERATION'];
  if(scope.fence)names.push('CONTROL','FENCE_RESOURCE');
  const a=await deployment(scope.worker,names),{DB,RECOVERY,CONTROL,...vars}=a.b;
  const result={deployment:a.deployment,version:a.version,database:DB,bucket:RECOVERY,vars};
  if(scope.fence){
   const op=await deployment(scope.fence.operator,['DB','RECOVERY','CONTROL','FENCE_RESOURCE','PUBLIC_ORIGIN','SCOPE']);
   const sub=(await call('/workers/subdomain'))?.subdomain;
   if(typeof sub!=='string'||!/^[a-z0-9-]+$/.test(sub))fail('WORKERS_SUBDOMAIN_UNVERIFIED');
   const origin='https://'+scope.fence.operator+'.'+sub+'.workers.dev';
   if(op.b.PUBLIC_ORIGIN!==origin||canonical(JSON.parse(op.b.SCOPE))!==canonical(scope))fail('OPERATOR_SCOPE_MISMATCH');
   result.control=CONTROL;
   result.operator={deployment:op.deployment,version:op.version,database:op.b.DB,bucket:op.b.RECOVERY,
    control:op.b.CONTROL,resource:op.b.FENCE_RESOURCE,origin};
  }
  return result;
 }
 const query=async(database,sql,params)=>{
  if(!sql.startsWith('SELECT ')&&!sql.startsWith('PRAGMA table_info('))fail('READ_ONLY_ADAPTER');
  const r=await call('/d1/database/'+database+'/query',{sql,params});
  if(!Array.isArray(r)||r.length!==1||r[0].success!==true||!Array.isArray(r[0].results))fail('INCOMPLETE_D1_RESPONSE');
  return r[0].results;
 };
 const db={query:(sql,params)=>query(scope.database,sql,params)};
 const fence=async()=>{
  if(!scope.fence)return null;
  const r=await query(scope.fence.control,"SELECT f.resource_id,f.database_id,f.bucket_name,f.library_id,f.service_generation,f.provenance,f.state,f.maintenance_id,f.next_generation,CAST(f.revision AS TEXT) AS revision,(SELECT COUNT(*) FROM cloud_fence_tickets t WHERE t.resource_id=f.resource_id) AS pending,(SELECT COUNT(*) FROM cloud_fence_actions a WHERE a.maintenance_id=f.maintenance_id AND a.state='RUNNING') AS running FROM cloud_fence f WHERE resource_id=?",[scope.fence.resource]);
  if(r.length!==1)fail('FENCE_RESOURCE_UNREGISTERED');return r[0];
 };
 return {db,authority,fence,
  latestAction:async maintenance=>(await query(scope.fence.control,'SELECT action_id,kind,state FROM cloud_fence_actions WHERE resource_id=? AND maintenance_id=? ORDER BY rowid DESC LIMIT 1',[scope.fence.resource,maintenance]))[0]??null,
  // Only the authenticated operator Worker uses this mutation; it claims a
  // durable exclusive action first. No retries after an uncertain response.
  restore:bookmark=>call('/d1/database/'+scope.database+'/time_travel/restore?bookmark='+encodeURIComponent(bookmark),{}),
  bookmark:()=>call('/d1/database/'+scope.database+'/time_travel/bookmark')
 };
}
export function operatorClient(origin,secret,fetcher=globalThis.fetch.bind(globalThis)){
 if(!/^https:\/\/newpipe-[a-z0-9-]+\.[a-z0-9-]+\.workers\.dev$/.test(origin)||!secret)fail('OPERATOR_CONNECTION_REQUIRED');
 return async(path,payload)=>{
  const r=await fetcher(origin+path,{method:'POST',redirect:'manual',headers:{'Content-Type':'application/json',Authorization:'Bearer '+secret},
   body:JSON.stringify(payload),signal:AbortSignal.timeout(60000)});
  const v=await r.json();
  if(!r.ok)fail(typeof v.error==='string'?v.error:'OPERATOR_REQUEST_FAILED');
  return v;
 };
}
