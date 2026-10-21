// SPDX-License-Identifier: GPL-3.0-or-later
import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {seedBudget} from './budget-fixture.mjs';
import {readFile} from 'node:fs/promises';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {admit} from '../src/fence.ts';
import {closeFence,fenceState,requireHeld,claimAction,finishAction} from '../src/maintenance.ts';
let mf,control,db,r2;
const scope={resource:'resource-a',library:'library-a',generation:'old-generation'};
const maintenance='11111111-1111-4111-8111-111111111111',generation='22222222-2222-4222-8222-222222222222';
const close=()=>closeFence(control,scope.resource,maintenance,generation);
const gate=()=>{let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};};
before(async()=>{
 mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules:true,script:'export default {fetch(){return new Response("fence tests")}}',
  compatibilityDate:'2026-08-25',d1Databases:{DB:'data',CONTROL:'control'},r2Buckets:{RECOVERY:'fence-bucket'}}));
 await mf.ready;db=await mf.getD1Database('DB');control=await mf.getD1Database('CONTROL');r2=await mf.getR2Bucket('RECOVERY');
 const schema=(await Promise.all(['0001_fence.sql','0002_reopen.sql','0003_budgets.sql'].map(f=>readFile(new URL('../control-migrations/'+f,import.meta.url),'utf8')))).join('\n').replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
 await control.batch(schema.map(s=>control.prepare(s)));
 await db.batch([db.prepare('CREATE TABLE sample(value INTEGER)'),db.prepare('CREATE TABLE cloud_commit_assertion(slot INTEGER PRIMARY KEY,ok INTEGER CHECK(ok = 1))')]);
});
after(async()=>{await mf?.dispose();});
beforeEach(async()=>{
 for(const table of ['cloud_fence_actions','cloud_fence_tickets','cloud_budget_maintenance','cloud_budget','cloud_fence','cloud_fence_assertion'])await control.prepare('DELETE FROM '+table).run();
 await control.prepare("INSERT INTO cloud_fence(resource_id,database_id,bucket_name,library_id,service_generation,provenance,state,created_at) VALUES('resource-a','data','bucket','library-a','old-generation','fresh-resources-v1','OPEN',0)").run();
 await seedBudget(control,'resource-a');
 await db.prepare('DELETE FROM sample').run();
 for(const o of (await r2.list()).objects)await r2.delete(o.key);
});
test('missing or mismatched authority fails closed before storage admission',async()=>{
 await assert.rejects(admit(undefined,scope));
 await assert.rejects(admit(control,{...scope,resource:'legacy'}));
 await assert.rejects(admit(control,{...scope,generation:'resurrected'}));
 assert.equal((await fenceState(control,scope.resource)).pending,0);
});
test('maintenance waits for an admitted catalog batch, then denies stale capabilities across rollback',async()=>{
 const lease=await admit(control,scope),waiting=gate(),started=gate();
 const delayed={prepare:db.prepare.bind(db),batch:async statements=>{started.resolve();await waiting.promise;return db.batch(statements);}};
 const wrapped=lease.database(delayed);
 const write=wrapped.batch([wrapped.prepare('INSERT INTO sample VALUES(7)')]);
 await started.promise;assert.equal((await close()).state,'DRAINING');
 await assert.rejects(admit(control,scope));
 assert.throws(()=>requireHeld({state:'DRAINING'}),/MAINTENANCE_NOT_EXCLUSIVE/);
 waiting.resolve();await write;await lease.finish();requireHeld(await close());
 // Simulate a data-only rewind. The external record and closed handle survive.
 await db.prepare('DELETE FROM sample').run();
 await assert.rejects(wrapped.prepare('INSERT INTO sample VALUES(9)').run());
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM sample').first()).n,0);
 assert.equal((await fenceState(control,scope.resource)).state,'HELD');
});
for(const operation of ['create','part','complete','abort','delete']){
 test('paused R2 '+operation+' cannot be overtaken by maintenance',async()=>{
  const lease=await admit(control,scope),waiting=gate(),started=gate();
  const existing=await r2.createMultipartUpload('payload');
  const part=await existing.uploadPart(1,new Uint8Array([1,2,3]));
  if(operation==='delete')await existing.complete([part]);
  const original=operation==='create'?()=>r2.createMultipartUpload('new-payload'):
   operation==='part'?()=>existing.uploadPart(1,new Uint8Array([4,5,6])):
   operation==='complete'?()=>existing.complete([part]):operation==='abort'?()=>existing.abort():()=>r2.delete('payload');
  const delayed=async()=>{started.resolve();await waiting.promise;return original();};
  const bucket=lease.bucket({createMultipartUpload:delayed,delete:delayed,
   resumeMultipartUpload:()=>({key:existing.key,uploadId:existing.uploadId,uploadPart:delayed,complete:delayed,abort:delayed})});
  const u=bucket.resumeMultipartUpload('payload',existing.uploadId);
  const effect=operation==='create'?bucket.createMultipartUpload('new-payload'):operation==='delete'?bucket.delete('payload'):
   operation==='part'?u.uploadPart(1,new Uint8Array([4,5,6])):operation==='complete'?u.complete([part]):u.abort();
  await started.promise;const draining=await close();assert.equal(draining.pending,1);assert.equal(draining.state,'DRAINING');
  assert.throws(()=>requireHeld(draining),/MAINTENANCE_NOT_EXCLUSIVE/);
  waiting.resolve();const result=await effect;await lease.finish();requireHeld(await close());
  await assert.rejects(u.abort(),/MAINTENANCE_REQUIRED/);
  if(operation==='create')await r2.resumeMultipartUpload(result.key,result.uploadId).abort();
  if(operation==='part')await existing.abort();
 });
}
test('ambiguous storage result permanently retains its ticket, even after data rewind',async()=>{
 const lease=await admit(control,scope),bucket=lease.bucket({delete:async()=>{await r2.put('late',new Uint8Array([1]));throw new Error('lost response');}});
 await assert.rejects(bucket.delete('late'));await lease.finish();
 await db.prepare('DELETE FROM sample').run();
 // Artificially ancient tickets cannot expire into a false proof.
 await control.prepare('UPDATE cloud_fence_tickets SET created_at=0').run();
 const f=await close();assert.equal(f.state,'DRAINING');assert.equal(f.pending,1);
 assert.throws(()=>requireHeld(f));assert.ok(await r2.head('late'));
});
test('detached storage work retains the ticket and cannot start another effect after finish',async()=>{
 const lease=await admit(control,scope),waiting=gate(),started=gate();
 const bucket=lease.bucket({delete:async()=>{started.resolve();await waiting.promise;}});
 const pending=bucket.delete('key');await started.promise;await lease.finish();
 assert.equal((await close()).state,'DRAINING');waiting.resolve();await pending;
 assert.equal((await close()).state,'DRAINING');await assert.rejects(bucket.delete('later'));
});
test('confirmed assertion rollback releases safely; arbitrary errors do not',async()=>{
 const lease=await admit(control,scope),wrapped=lease.database(db);
 await assert.rejects(wrapped.batch([wrapped.prepare('INSERT INTO sample VALUES(1)'),wrapped.prepare('INSERT INTO cloud_commit_assertion VALUES(1,0)')]));
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM sample').first()).n,0);
 await lease.finish();requireHeld(await close());
});
test('concurrent admission and maintenance always either reject or retain a durable ticket',async()=>{
 const outcomes=await Promise.allSettled([admit(control,scope),close()]);
 if(outcomes[0].status==='fulfilled'){
  assert.equal((await fenceState(control,scope.resource)).pending,1);
  assert.notEqual((await fenceState(control,scope.resource)).state,'HELD');
  await outcomes[0].value.finish();requireHeld(await close());
 }else requireHeld(await close());
});
test('only one operator effect can claim a fence revision; unknown actions stay exclusive',async()=>{
 const f=await close(),id='a'.repeat(64);
 const attempts=await Promise.allSettled([claimAction(control,f,id,'apply'),claimAction(control,f,'b'.repeat(64),'restore')]);
 assert.equal(attempts.filter(x=>x.status==='fulfilled').length,1);
 const now=await fenceState(control,scope.resource);assert.equal(now.running,1);assert.throws(()=>requireHeld(now));
 const action=await control.prepare('SELECT action_id FROM cloud_fence_actions').first();
 await finishAction(control,maintenance,action.action_id,{verified:true});requireHeld(await fenceState(control,scope.resource));
 await assert.rejects(claimAction(control,f,'c'.repeat(64),'restore'));
});
