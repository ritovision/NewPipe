// SPDX-License-Identifier: GPL-3.0-or-later
// TEST HARNESS ONLY: accepts synthetic SQL. Never a deployment entry point.
import {guardedCommit,resolveOrRetire} from '../src/admission.ts';
export default {
 async fetch(request,env) {
  try {
   const data=await request.json(),db=env.DB;
   if(data.kind==='sql')return Response.json(await db.batch(data.statements.map(s=>db.prepare(s.sql).bind(...(s.args??[])))));
   let database=db, first=true;
   if(data.inject)database={prepare:db.prepare.bind(db),batch:async statements=>{
    if(!first)return db.batch(statements);first=false;
    if(data.inject==='networkBeforeCommit')throw new Error('synthetic network uncertainty');
    if(data.inject==='revokeBeforeBatch')await db.prepare('UPDATE cloud_devices SET revoked=1 WHERE library_id=? AND device_id=?').bind(data.context.library,data.context.device).run();
    const result=await db.batch(statements);
    if(data.inject==='lostAfterCommit')throw new Error('synthetic lost response');
    return result;
   }};
   const result=data.kind==='retire'
    ?await resolveOrRetire(database,data.context,data.operation.sequence,data.operation.digest)
    :await guardedCommit(database,data.context,data.operation,(data.mutations??[]).map(s=>db.prepare(s.sql).bind(...(s.args??[]))));
   return Response.json({ok:true,result});
  }catch(error){return Response.json({ok:false,code:error.message},{status:409});}
 }
};
