// SPDX-License-Identifier: GPL-3.0-or-later
// LOCAL TEST HARNESS ONLY. Never import this module from the deployment entry point.
import {handleRequest} from '../src/index.ts';
import {ReplacementService} from '../src/replacement.ts';
import {authenticateToken,DEFAULT_SERVICE_LIMITS} from '../src/service.ts';
let options={},fault=null,block=null,blockedRequest=false;
let stats={calls:{},maxPayloads:0,blocked:null},uploads=new Map(),objects=new Set();
const measured=new WeakMap();
async function hook(point){
 if(block===point&&!blockedRequest){
  blockedRequest=true;stats.blocked=point;
  const deadline=Date.now()+10000;
  while(block===point&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,5));
  stats.blocked=null;blockedRequest=false;
  if(block===point)throw new Error('test gate timeout');
 }
 if(fault===point){fault=null;throw new Error('injected ambiguous result at '+point);}
}
function count(){stats.maxPayloads=Math.max(stats.maxPayloads,uploads.size+objects.size);}
function database(db){
 const wrap=(s,sql)=>{const p={bind:(...args)=>wrap(s.bind(...args),sql),first:s.first.bind(s),all:s.all.bind(s),run:s.run.bind(s),raw:s.raw.bind(s)};measured.set(p,{s,sql});return p;};
 return {prepare:sql=>wrap(db.prepare(sql),sql),batch:async statements=>{
  const raw=statements.map(s=>measured.get(s)??{s,sql:''}),sql=raw.map(x=>x.sql).join('\n');
  const point=sql.includes("UPDATE cloud_replace_attempts SET state='DONE'")?'publishReplacement':sql.includes('INSERT INTO cloud_replace_attempts')?'reserveReplacement':sql.includes("state='ABORTING',busy_token=")?'claimAbort':sql.includes("state='UPLOADING',upload_id=")?'recordCreate':sql.includes("state='CREATING'")?'claimCreate':
   sql.includes('parts_json=?,next_part')?'recordPart':sql.includes("state='VERIFYING',object_etag=")?'recordComplete':
   sql.includes('state=?,verify_part=')?'recordVerify':sql.includes('DELETE FROM cloud_backup_slots')?'removeSlot':'batch';
  await hook(point+'.before');const r=await db.batch(raw.map(x=>x.s));await hook(point+'.after');return r;
 }};
}
function bucket(r2){
 const call=async(name,run,effect=()=>{})=>{stats.calls[name]=(stats.calls[name]??0)+1;await hook(name+'.before');const r=await run();effect(r);count();await hook(name+'.after');return r;};
 const multipart=u=>({key:u.key,uploadId:u.uploadId,
  uploadPart:(...args)=>call('part',()=>u.uploadPart(...args)),
  complete:(...args)=>call('complete',()=>u.complete(...args),o=>{uploads.delete(u.uploadId);objects.add(o.key);})
   .then(o=>options.sparseComplete?{key:o.key,size:o.size,etag:o.etag,version:o.version}:o),
  abort:()=>call('abort',()=>u.abort(),()=>uploads.delete(u.uploadId))});
 return {
  createMultipartUpload:(...args)=>call('create',()=>r2.createMultipartUpload(...args),u=>uploads.set(u.uploadId,u.key)).then(multipart),
  resumeMultipartUpload:(...args)=>multipart(r2.resumeMultipartUpload(...args)),
  head:(...args)=>call('head',()=>r2.head(...args)),
  get:(...args)=>call('get',()=>r2.get(...args)),
  list:(...args)=>call('list',()=>r2.list(...args)),
  delete:key=>call('delete',()=>r2.delete(key),()=>objects.delete(key))
 };
}
export default {async fetch(request,env){
 const path=new URL(request.url).pathname;
 if(path==='/__test/slow-replacement'){
  try{
   const context=await authenticateToken(env.DB,request.headers.get('authorization').slice(7),'library-a','service-a');
   const result=await new ReplacementService(database(env.DB),bucket(env.RECOVERY),context,DEFAULT_SERVICE_LIMITS,1024*1024).publish(await request.json(),Date.now()-120000);
   return Response.json(result);
  }catch(e){return Response.json({error:e.message},{status:409});}
 }
 if(path==='/__test/sql'){try{return Response.json(await env.DB.batch((await request.json()).map(s=>env.DB.prepare(s.sql).bind(...(s.args??[])))));}catch(e){return Response.json({error:String(e)},{status:409});}}
 if(path==='/__test/control'){
  const input=await request.json();options=input.options??options;if(Object.hasOwn(input,'fault'))fault=input.fault;if(Object.hasOwn(input,'block'))block=input.block;
  if(input.release)block=null;return Response.json({...stats,uploads:uploads.size,objects:objects.size});
 }
 if(path==='/__test/reset'){
  fault=null;block=null;options={};
  for(const [id,key]of uploads)try{await env.RECOVERY.resumeMultipartUpload(key,id).abort();}catch{}
  for(const o of (await env.RECOVERY.list({limit:1000})).objects)await env.RECOVERY.delete(o.key);
  uploads=new Map();objects=new Set();stats={calls:{},maxPayloads:0,blocked:null};return Response.json({ok:true});
 }
 if(path==='/__test/object'){
  const p=await request.json();if(p.remove){await env.RECOVERY.delete(p.key);objects.delete(p.key);}
  else{await env.RECOVERY.put(p.key,Uint8Array.from(atob(p.data),x=>x.charCodeAt(0)),{customMetadata:p.metadata});objects.add(p.key);count();}
  return Response.json({ok:true});
 }
 return handleRequest(request,{...env,FENCE_RESOURCE:'test-resource',DB:database(env.DB),RECOVERY:bucket(env.RECOVERY),
  PUBLIC_ORIGIN:'https://recovery.test',LIBRARY_ID:'library-a',SERVICE_GENERATION:'service-a',
  ENABLE_CATALOG_API:'true',ENABLE_RECOVERY_API:'true',MAX_CATALOG_BYTES:String(32*1024*1024),MAX_CATALOG_PAGES:'2048',
  REQUEST_LIMITER:{limit:async()=>({success:true})},...options});
}};
