// SPDX-License-Identifier: GPL-3.0-or-later
// LOCAL TEST HARNESS ONLY. The production index.ts contains no SQL or fault-injection endpoint.
import {handleRequest} from '../src/index.ts';
let options={},fault=null,stats={statements:0,batches:0,maxBatch:0,rowsRead:0,rowsWritten:0};
const sqlByStatement=new WeakMap();
function measured(db){
 const add=r=>{if(r?.meta){stats.rowsRead+=r.meta.rows_read??0;stats.rowsWritten+=r.meta.rows_written??0;}};
 const wrap=(s,sql)=>{
  const p={bind:(...args)=>wrap(s.bind(...args),sql),
   all:async(...args)=>{stats.statements++;const r=await s.all(...args);add(r);return r;},
   first:async(column)=>{stats.statements++;const r=await s.all();add(r);const row=r.results[0]??null;return column===undefined?row:row?.[column]??null;},
   run:async()=>{stats.statements++;const r=await s.run();add(r);return r;},raw:s.raw.bind(s)};
  sqlByStatement.set(p,{s,sql});return p;
 };
 return {prepare:sql=>wrap(db.prepare(sql),sql),batch:async statements=>{
  const raw=statements.map(s=>sqlByStatement.get(s)??{s,sql:''});
  const publishes=raw.some(r=>r.sql.startsWith('UPDATE cloud_head SET'));
  const retiring=raw.some(r=>r.sql.includes("SET status='RETIRED'"));
  const current=(publishes||retiring)?fault:null;if(publishes||retiring)fault=null;
  if(current==='beforeCommit')throw new Error('simulated unknown network result');
  if(current==='revokeBeforeCommit')await db.prepare("UPDATE cloud_devices SET revoked=1 WHERE device_id='device-a'").run();
  stats.statements+=raw.length;stats.batches++;stats.maxBatch=Math.max(stats.maxBatch,raw.length);
  const payload=raw.map(x=>x.s);
  if(retiring&&current==='failRetirement')payload.push(db.prepare('INSERT INTO cloud_commit_assertion VALUES(1,0)'));
  const r=await db.batch(payload);r.forEach(add);
  if(current==='afterCommit')throw new Error('simulated lost committed response');
  return r;
 }};
}
export default {async fetch(request,env){
 const path=new URL(request.url).pathname;
 if(path==='/__test/sql'){
  const input=await request.json();
  try{return Response.json(await env.DB.batch(input.map(s=>env.DB.prepare(s.sql).bind(...(s.args??[])))));}catch(e){return Response.json({error:String(e)},{status:409});}
 }
 if(path==='/__test/control'){
  const input=await request.json();options=input.options??options;if(Object.hasOwn(input,'fault'))fault=input.fault;
  if(input.resetStats)stats={statements:0,batches:0,maxBatch:0,rowsRead:0,rowsWritten:0};return Response.json(stats);
 }
 if(path==='/__test/stats')return Response.json(stats);
 return handleRequest(request,{...env,DB:measured(env.DB),PUBLIC_ORIGIN:'https://catalog.test',LIBRARY_ID:'library-a',
  SERVICE_GENERATION:'service-a',ENABLE_CATALOG_API:'true',MAX_CATALOG_BYTES:String(32*1024*1024),MAX_CATALOG_PAGES:'2048',
  REQUEST_LIMITER:{limit:async()=>({success:options.rateAllowed!==false})},...options});
}};
