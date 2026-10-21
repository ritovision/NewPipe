// SPDX-License-Identifier: GPL-3.0-or-later
import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {seedBudget} from './budget-fixture.mjs';
import {readFile} from 'node:fs/promises';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {scheduledHousekeeping} from '../src/housekeeping-schedule.ts';
import {housekeep,HOUSEKEEPING} from '../src/housekeeping.ts';
import {admit} from '../src/fence.ts';
import {closeFence,fenceState} from '../src/maintenance.ts';
import {CatalogService,makeUpload} from '../src/service.ts';
import {encodePages,changedPages,pageManifest,sha256} from '../src/pages.ts';
import {fingerprint} from '../src/catalog.ts';
const fixtures=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
const empty=fixtures.catalogs.find(c=>c.name==='empty').catalog,target=fixtures.catalogs.find(c=>c.name==='base').catalog;
const fp=await fingerprint(empty),tokenHash=await sha256('1'.repeat(64));
const ctx={library:'library',device:'device-a',tokenHash,authGeneration:'1',serviceGeneration:'service'};
let mf,db,control;
const ddl=async(path)=>(await readFile(new URL(path,import.meta.url),'utf8')).replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
before(async()=>{
 mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules:true,script:'export default {fetch(){return new Response("housekeeping")}}',compatibilityDate:'2026-08-25',d1Databases:{DB:'housekeeping',CONTROL:'housekeeping-control'}}));
 await mf.ready;db=await mf.getD1Database('DB');control=await mf.getD1Database('CONTROL');
 for(const file of ['0001_catalog_service.sql','0002_recovery.sql','0003_replacement.sql','0004_operator_receipts.sql','0005_reopen_receipts.sql','0006_housekeeping.sql'])await db.batch((await ddl('../migrations/'+file)).map(s=>db.prepare(s)));
 for(const file of ['0001_fence.sql','0002_reopen.sql','0003_budgets.sql'])await control.batch((await ddl('../control-migrations/'+file)).map(s=>control.prepare(s)));
});
after(async()=>{await mf?.dispose();});
beforeEach(async()=>{
 for(const t of ['cloud_fence_actions','cloud_fence_tickets','cloud_budget_maintenance','cloud_budget','cloud_fence','cloud_fence_assertion'])await control.prepare('DELETE FROM '+t).run();
 await control.prepare("INSERT INTO cloud_fence(resource_id,database_id,bucket_name,library_id,service_generation,provenance,state,created_at) VALUES('resource','db','bucket','library','service','fresh-resources-v1','OPEN',0)").run();
 await seedBudget(control,'resource');
 for(const t of ['cloud_receipt_retention','cloud_housekeeping_cursor','cloud_replace_attempts','cloud_replace_approvals','cloud_replace_grants','cloud_backup_slots','cloud_backup_operations','cloud_backup_approvals','cloud_backup_control','cloud_upload_changes','cloud_upload_chunks','cloud_uploads','cloud_receipts','cloud_catalog_pages','cloud_catalog_info','cloud_devices','cloud_head','cloud_commit_assertion'])await db.prepare('DELETE FROM '+t).run();
 await db.batch([
  db.prepare("INSERT INTO cloud_head VALUES('library','epoch',0,0,?)").bind(fp),
  db.prepare("INSERT INTO cloud_head VALUES('unrelated','other',0,0,?)").bind(fp),
  db.prepare("INSERT INTO cloud_catalog_info VALUES('library',0,0,0,?)").bind(await pageManifest([])),
  db.prepare("INSERT INTO cloud_devices VALUES('library','device-a',?,'service',1,0,1,0)").bind(tokenHash),
  db.prepare("INSERT INTO cloud_devices VALUES('unrelated','other-device',?,'other-service',1,0,1,0)").bind('2'.repeat(64)),
  db.prepare("INSERT INTO cloud_backup_control VALUES('library','service',0,1,0)")
 ]);
});
const run=(database=db,limits)=>housekeep(database,'library','service',limits);
const count=async table=>(await db.prepare('SELECT COUNT(*) n FROM '+table).first()).n;
async function staged(expired=true,sequence='1'){
 const service=new CatalogService(db,ctx),head=(await service.status()).head,pages=await encodePages(target);
 const u=await makeUpload('initialize',sequence,head,await fingerprint(target),changedPages([],pages));
 await service.start(u.header);
 for(let i=0;i<u.chunks.length;i++)await service.chunk(sequence,i,u.chunks[i]);
 await service.seal(sequence,u.header.digest);
 if(expired)await db.prepare("UPDATE cloud_uploads SET expires_at=0 WHERE library_id='library'").run();
 return {service,...u,ref:[sequence,u.header.digest]};
}
async function history(n=100,device='device-a',start='1'){
 const last=String(BigInt(start)+BigInt(n)-1n);
 await db.prepare("UPDATE cloud_devices SET resolved_sequence=CAST(? AS INTEGER) WHERE library_id='library' AND device_id=?").bind(last,device).run();
 await db.prepare("WITH RECURSIVE x(i) AS(SELECT 0 UNION ALL SELECT i+1 FROM x WHERE i+1<?) INSERT INTO cloud_receipts SELECT 'library',?,CAST(? AS INTEGER)+i,printf('%064x',i),'COMMITTED','epoch',1,1,? FROM x")
  .bind(n,device,start,fp).run();
}
async function pin(sequence='20',state='STALE'){
 await db.prepare("INSERT INTO cloud_replace_attempts VALUES('library','device-a','attempt',?,'new-epoch',1,'archive',0,NULL,1,?)").bind(JSON.stringify({sequence}),state).run();
}
const age=()=>db.prepare('UPDATE cloud_receipt_retention SET first_seen=0').run();
test('expired upload retires atomically, releases staged rows and fences delayed publication',async()=>{
 const u=await staged(),head=await db.prepare("SELECT * FROM cloud_head WHERE library_id='library'").first();
 const r=await run();assert.equal(r.retired,1);assert.equal(await count('cloud_uploads'),0);assert.equal(await count('cloud_upload_changes'),0);assert.equal(await count('cloud_upload_chunks'),0);
 const receipt=(await u.service.uploadStatus(...u.ref)).receipt;assert.equal(receipt.outcome,'RETIRED');
 await assert.rejects(u.service.publish(...u.ref),/OPERATION_RETIRED/);
 assert.deepEqual(await db.prepare("SELECT * FROM cloud_head WHERE library_id='library'").first(),head);
 assert.equal((await db.prepare("SELECT resolved_sequence FROM cloud_devices WHERE device_id='device-a'").first()).resolved_sequence,1);
 assert.equal((await db.prepare("SELECT resolved_sequence FROM cloud_devices WHERE library_id='unrelated'").first()).resolved_sequence,0);
 assert.equal((await run()).retired,0);
});
for(const change of ["revoked=1","service_generation='old-service'"])test('non-expired abandoned authority '+change+' is retired',async()=>{
 await staged(false);await db.prepare("UPDATE cloud_devices SET "+change+" WHERE library_id='library'").run();assert.equal((await run()).retired,1);
});
test('unexpired live uploads are kept',async()=>{await staged(false);assert.equal((await run()).retainedStages,1);assert.equal(await count('cloud_receipts'),0);});
for(const state of ['ACTIVE','STALE'])test('replacement '+state+' pins its incoming stage',async()=>{
 await staged();await pin('1',state);assert.equal((await run()).retainedStages,1);assert.equal(await count('cloud_uploads'),1);
});
test('unknown R2 operation blocks stage retirement without an archive capability',async()=>{
 await staged();await db.prepare("INSERT INTO cloud_backup_operations(library_id,device_id,sequence,approval_id,archive_id,slot,manifest,digest,state,created_at) VALUES('library','device-a',1,'approval','archive',0,'{}',?,'QUARANTINED',0)").bind('a'.repeat(64)).run();
 assert.equal((await run()).retainedStages,1);assert.equal(await count('cloud_uploads'),1);
});
test('oversized legacy stages are retained rather than producing unbounded deletes',async()=>{
 await staged();await db.prepare("WITH RECURSIVE x(i) AS(SELECT 10 UNION ALL SELECT i+1 FROM x WHERE i<140) INSERT INTO cloud_upload_chunks SELECT 'library','device-a',1,i,? FROM x").bind('a'.repeat(64)).run();
 assert.equal((await run()).retainedStages,1);assert.ok(await count('cloud_upload_chunks')>HOUSEKEEPING.stageRows);
});
test('receipt retention waits thirty days, keeps newest sixteen, pins replacement references and bounds each delete',async()=>{
 await history();await pin();assert.equal((await run()).receiptsRemoved,0);
 assert.equal((await db.prepare('SELECT through_sequence FROM cloud_receipt_retention').first()).through_sequence,84);
 await age();assert.equal((await run()).receiptsRemoved,64);assert.equal(await count('cloud_receipts'),36);
 assert.ok(await db.prepare("SELECT 1 FROM cloud_receipts WHERE sequence=20").first());
 assert.equal((await run()).receiptsRemoved,19);assert.equal(await count('cloud_receipts'),17);assert.equal(await count('cloud_receipt_retention'),0);
 assert.equal((await db.prepare("SELECT COUNT(*) n FROM cloud_receipts WHERE sequence>84").first()).n,16);
 await db.prepare('DELETE FROM cloud_replace_attempts').run();assert.equal((await run()).receiptsRemoved,0);
 assert.ok((await db.prepare('SELECT first_seen FROM cloud_receipt_retention').first()).first_seen>0);
});
test('newer sequences never inherit an old prefix retention age',async()=>{
 await history();await run();await age();await history(100,'device-a','101');
 await run();await run();assert.equal(await count('cloud_receipts'),116);
 assert.equal((await run()).receiptsRemoved,0);assert.equal((await db.prepare('SELECT through_sequence FROM cloud_receipt_retention').first()).through_sequence,184);
});
test('retention arithmetic remains exact at the signed 64-bit high-water limit',async()=>{
 await history(80,'device-a','9223372036854775728');await run();await age();assert.equal((await run()).receiptsRemoved,64);
 const r=await db.prepare("SELECT CAST(resolved_sequence AS TEXT) n FROM cloud_devices WHERE device_id='device-a'").first();assert.equal(r.n,'9223372036854775807');
 assert.equal(await count('cloud_receipts'),16);
});
test('device cursor rotates bounded windows and does not scan another library',async()=>{
 for(let i=0;i<6;i++)await db.prepare("INSERT INTO cloud_devices VALUES('library',?,?,'service',1,0,1,0)").bind('device-'+i,await sha256('extra'+i)).run();
 const a=await run();assert.equal(a.inspected,4);assert.equal(a.wrapped,false);
 const b=await run();assert.equal(b.inspected,3);assert.equal(b.wrapped,true);
 assert.equal((await run()).inspected,4);
});
test('a publication winning after inspection cannot be overwritten by housekeeping',async()=>{
 const u=await staged();let intercepted=false;
 const race={prepare:db.prepare.bind(db),batch:async statements=>{
  if(!intercepted){intercepted=true;await db.batch([
   db.prepare("UPDATE cloud_uploads SET status='COMMITTED'"),db.prepare("UPDATE cloud_devices SET resolved_sequence=1 WHERE library_id='library'"),
   db.prepare("INSERT INTO cloud_receipts VALUES('library','device-a',1,?,'COMMITTED','epoch',1,1,?)").bind(u.header.digest,fp)
  ]);}return db.batch(statements);
 }};
 const r=await run(race);assert.equal(r.raced,1);assert.equal(r.retired,0);
 assert.equal((await db.prepare('SELECT outcome FROM cloud_receipts').first()).outcome,'COMMITTED');
});
test('housekeeping winning against a paused publication returns the exact retired receipt',async()=>{
 const u=await staged();let intercepted=false;
 const delayed={prepare:db.prepare.bind(db),batch:async statements=>{if(!intercepted){intercepted=true;await run();}return db.batch(statements);}};
 // The publisher entered before expiry; cleanup uses database time at execution.
 await assert.rejects(new CatalogService(delayed,ctx).publish(...u.ref,-1),/OPERATION_RETIRED/);
 assert.equal((await db.prepare('SELECT outcome FROM cloud_receipts').first()).outcome,'RETIRED');
});
test('lost cleanup reply retains its request fence and never authorizes maintenance',async()=>{
 await staged();const ticket=await admit(control,{resource:'resource',library:'library',generation:'service'});
 const ambiguous={prepare:db.prepare.bind(db),batch:async statements=>{await db.batch(statements);throw new Error('lost cleanup reply');}};
 await assert.rejects(run(ticket.database(ambiguous)));await ticket.finish();
 assert.equal((await fenceState(control,'resource')).pending,1);
 const closed=await closeFence(control,'resource','11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222');assert.equal(closed.state,'DRAINING');
 assert.equal((await run()).retired,0);assert.equal(await count('cloud_receipts'),1);
});
test('invalid bounds and missing retention schema refuse before mutations',async()=>{
 await staged();await assert.rejects(run(db,{devices:5}),/HOUSEKEEPING_LIMIT_INVALID/);await assert.rejects(run(db,{receipts:65}),/HOUSEKEEPING_LIMIT_INVALID/);
 await db.prepare('DROP TABLE cloud_receipt_retention').run();await assert.rejects(run());assert.equal(await count('cloud_uploads'),1);assert.equal(await count('cloud_receipts'),0);
 const parts=await ddl('../migrations/0006_housekeeping.sql');await db.batch([db.prepare(parts[0])]);
});
test('large receipt history remains bounded by indexed prefix and deletion limits',async t=>{
 await history(10000);await run();await age();
 const stats={statements:0,rowsRead:0,rowsWritten:0},raw=new WeakMap();
 const add=r=>{stats.rowsRead+=r.meta.rows_read;stats.rowsWritten+=r.meta.rows_written;return r;};
 const wrap=s=>{
  const p={bind:(...a)=>wrap(s.bind(...a)),all:async()=>{stats.statements++;return add(await s.all());},run:async()=>{stats.statements++;return add(await s.run());},
   first:async column=>{stats.statements++;const r=add(await s.all()),row=r.results[0]??null;return column===undefined?row:row?.[column]??null;}};
  raw.set(p,s);return p;
 };
 const measured={prepare:s=>wrap(db.prepare(s)),batch:async ss=>{stats.statements+=ss.length;return (await db.batch(ss.map(s=>raw.get(s)))).map(add);}};
 assert.equal((await run(measured)).receiptsRemoved,64);
 assert.ok(stats.rowsRead>0&&stats.rowsRead<2048,JSON.stringify(stats));assert.ok(stats.rowsWritten<512,JSON.stringify(stats));
 t.diagnostic(JSON.stringify(stats));
});

test('enabled scheduler uses bounded ordinary admission, leaves archives alone and settles its daily reservation',async()=>{
 await staged();
 const result=await scheduledHousekeeping({ENABLE_HOUSEKEEPING:'true',DB:db,CONTROL:control,FENCE_RESOURCE:'resource',LIBRARY_ID:'library',SERVICE_GENERATION:'service'});
 assert.equal(result.retired,1);assert.ok(result.inspected<=2);
 const budget=await control.prepare('SELECT * FROM cloud_budget').first();assert.equal(budget.held_reads,0);assert.ok(budget.spent_writes>32);
});
