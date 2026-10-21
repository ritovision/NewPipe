// SPDX-License-Identifier: GPL-3.0-or-later
import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {admit,admitMaintenanceBudget,admitControlBudget} from '../src/fence.ts';
import {scheduledHousekeeping} from '../src/housekeeping-schedule.ts';
import {requestEstimate,validateEstimate,ACCOUNTING} from '../src/budget.ts';
import {closeFence,fenceState} from '../src/maintenance.ts';
import {handleRequest} from '../src/index.ts';
import {seedBudget} from './budget-fixture.mjs';
let mf,db,control;
const scope={resource:'resource',library:'library',generation:'service'};
const small={reads:512,writes:64,growth:65536};
const schema=async f=>(await readFile(new URL('../control-migrations/'+f,import.meta.url),'utf8')).replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
const row=()=>control.prepare("SELECT * FROM cloud_budget WHERE resource_id='resource'").first();
const pending=async()=>(await control.prepare('SELECT COUNT(*) n FROM cloud_fence_tickets').first()).n;
const day=()=>control.prepare('SELECT CAST(unixepoch()/86400 AS INTEGER) n').first().then(r=>r.n);
function measured(database) {
 const stats={statements:0,reads:0,writes:0},raw=new WeakMap();
 const record=r=>{for(const x of Array.isArray(r)?r:[r]){stats.reads+=x.meta.rows_read;stats.writes+=x.meta.rows_written;}return r;};
 const wrap=s=>{const p={bind:(...v)=>wrap(s.bind(...v)),all:async()=>{stats.statements++;return record(await s.all());},run:async()=>{stats.statements++;return record(await s.run());}};raw.set(p,s);return p;};
 return {stats,db:{prepare:sql=>wrap(database.prepare(sql)),batch:async ss=>{stats.statements+=ss.length;return record(await database.batch(ss.map(s=>raw.get(s))));}}};
}
before(async()=>{
 mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules:true,script:'export default {fetch(){return new Response("budget")}}',compatibilityDate:'2026-08-25',d1Databases:{DB:'budget-data',CONTROL:'budget-control'}}));
 await mf.ready;db=await mf.getD1Database('DB');control=await mf.getD1Database('CONTROL');
 for(const f of ['0001_fence.sql','0002_reopen.sql','0003_budgets.sql'])await control.batch((await schema(f)).map(s=>control.prepare(s)));
 await db.batch([db.prepare('CREATE TABLE sample(id INTEGER PRIMARY KEY,value TEXT)'),db.prepare('CREATE INDEX sample_value ON sample(value)'),
  db.prepare('CREATE TABLE cloud_commit_assertion(slot INTEGER PRIMARY KEY,ok INTEGER CHECK(ok = 1))')]);
});
after(async()=>{await mf?.dispose();});
beforeEach(async()=>{
 for(const t of ['cloud_fence_tickets','cloud_budget_maintenance','cloud_budget','cloud_fence_actions','cloud_fence'])await control.prepare('DELETE FROM '+t).run();
 await control.prepare("INSERT INTO cloud_fence(resource_id,database_id,bucket_name,library_id,service_generation,provenance,state,created_at) VALUES('resource','data','bucket','library','service','fresh-resources-v1','OPEN',0)").run();
 await seedBudget(control,scope.resource);await db.prepare('DELETE FROM sample').run();
});
test('missing policy, missing schema and malformed estimates fail before DATA',async()=>{
 await control.prepare('DELETE FROM cloud_budget').run();
 await assert.rejects(admit(control,scope,small),/BUDGET/);assert.equal(await pending(),0);
 for(const e of [{...small,reads:0},{...small,writes:NaN},{...small,growth:-1},{...small,reads:2000001},{...small,writes:50001},{...small,extra:1}])assert.throws(()=>validateEstimate(e),/BUDGET_ESTIMATE_INVALID/);
 await control.prepare('ALTER TABLE cloud_budget RENAME TO hidden_budget').run();
 try{await assert.rejects(admit(control,scope,small),/BUDGET_ADMISSION_UNAVAILABLE/);}finally{await control.prepare('ALTER TABLE hidden_budget RENAME TO cloud_budget').run();}
 assert.equal(await pending(),0);
});
test('concurrent requests reserve atomically and a full budget starts no extra request',async()=>{
 await control.prepare('UPDATE cloud_budget SET read_limit=700,write_limit=100').run();
 const r=await Promise.allSettled([admit(control,scope,small),admit(control,scope,small)]);
 assert.equal(r.filter(x=>x.status==='fulfilled').length,1);assert.equal(await pending(),1);
 const held=await row();assert.equal(held.held_reads,small.reads);assert.equal(held.held_writes,small.writes);
 await r.find(x=>x.status==='fulfilled').value.finish();
 assert.equal(await pending(),0);assert.equal((await row()).held_reads,0);
});
test('all, first, run and batch meter DATA plus indexed writes and CONTROL overhead',async t=>{
 const ctl=measured(control),data=measured(db),ticket=await admit(ctl.db,scope,small),wrapped=ticket.database(data.db);
 await wrapped.prepare('INSERT INTO sample VALUES(1,?)').bind('one').run();
 assert.equal(await wrapped.prepare('SELECT value FROM sample WHERE id=1').first('value'),'one');
 assert.deepEqual(await wrapped.prepare('SELECT value FROM sample WHERE id=2').first(),null);
 await wrapped.batch([wrapped.prepare("INSERT INTO sample VALUES(2,'two')"),wrapped.prepare('SELECT * FROM sample ORDER BY id')]);
 await ticket.finish();const final=await row();
 assert.equal(final.spent_reads,data.stats.reads+ACCOUNTING.reads);assert.equal(final.spent_writes,data.stats.writes+ACCOUNTING.writes);
 assert.ok(data.stats.writes>2);assert.ok(ctl.stats.reads<=ACCOUNTING.reads);assert.ok(ctl.stats.writes<=ACCOUNTING.writes);
 assert.equal(final.held_growth,0);assert.ok(final.data_size>0);assert.equal(await pending(),0);
 t.diagnostic(JSON.stringify({control:ctl.stats,data:data.stats,charged:ticket.usage()}));
});
test('UTC rollover carries unresolved reservations and rejects competition until safe settlement',async()=>{
 const ticket=await admit(control,scope,small);await control.prepare('UPDATE cloud_budget SET day=day-1,read_limit=700,write_limit=100').run();
 await assert.rejects(admit(control,scope,small),/BUDGET/);
 const state=await row();assert.equal(state.day,await day());assert.equal(state.held_reads,small.reads);assert.ok(state.spent_reads>=small.reads);
 await ticket.database(db).prepare('SELECT * FROM sample LIMIT 1').all();await ticket.finish();
 assert.equal(await pending(),0);assert.equal((await row()).held_reads,0);
 const next=await admit(control,scope,small);await next.finish();
});
test('settlement itself can roll midnight before the next admission',async()=>{
 const ticket=await admit(control,scope,small);await ticket.database(db).prepare("INSERT INTO sample VALUES(1,'one')").run();
 await control.prepare('UPDATE cloud_budget SET day=day-1,spent_reads=1000,spent_writes=1000').run();
 await ticket.finish();const state=await row();assert.equal(state.day,await day());assert.equal(state.held_reads,0);
 assert.equal(state.spent_reads,ticket.usage().rowsRead);assert.equal(state.spent_writes,ticket.usage().rowsWritten);
});
test('lost DATA write keeps fence, reservation and size hold across multiple days and DATA rewind',async()=>{
 const ticket=await admit(control,scope,small),wrapped=ticket.database({prepare:db.prepare.bind(db),batch:async ss=>{await db.batch(ss);throw new Error('lost response');}});
 await assert.rejects(wrapped.batch([wrapped.prepare("INSERT INTO sample VALUES(1,'unknown')")]));
 await ticket.finish();await db.prepare('DELETE FROM sample').run();await control.prepare('UPDATE cloud_budget SET day=day-3').run();
 const other=await admit(control,scope,small);await other.finish();
 assert.equal(await pending(),1);const state=await row();assert.equal(state.held_reads,small.reads);assert.equal(state.held_growth,small.growth);
 const f=await closeFence(control,'resource','11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222');assert.equal(f.state,'DRAINING');
});
test('lost CONTROL admission reserves atomically without dispatching DATA',async()=>{
 const lost={prepare:control.prepare.bind(control),batch:async ss=>{await control.batch(ss);throw new Error('lost admission');}};
 await assert.rejects(admit(lost,scope,small));assert.equal(await pending(),1);assert.equal((await row()).held_reads,small.reads);
});
test('lost settlement reply is never retried and cannot refund twice',async()=>{
 let batches=0;const lost={prepare:control.prepare.bind(control),batch:async ss=>{const r=await control.batch(ss);if(++batches===2)throw new Error('lost settlement');return r;}};
 const ticket=await admit(lost,scope,small);await ticket.database(db).prepare('SELECT 1').first();await ticket.finish();const before=await row();
 await ticket.finish();assert.equal(batches,2);assert.deepEqual(await row(),before);assert.equal(await pending(),0);
});
test('statement ceiling rejects an entire oversized batch before any write',async()=>{
 const ticket=await admit(control,scope,small),wrapped=ticket.database(db);
 await assert.rejects(wrapped.batch(Array.from({length:28},(_,i)=>wrapped.prepare('INSERT INTO sample VALUES(?,?)').bind(i,'x'))),/REQUEST_STATEMENT_BUDGET_EXCEEDED/);
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM sample').first()).n,0);await ticket.finish();assert.equal(await pending(),0);
});
test('known rollback releases its fence but charges the full estimate including unreported work',async()=>{
 const ticket=await admit(control,scope,small),wrapped=ticket.database(db);
 await assert.rejects(wrapped.batch([wrapped.prepare("INSERT INTO sample VALUES(1,'x')"),wrapped.prepare('INSERT INTO cloud_commit_assertion VALUES(1,0)')]));
 await wrapped.prepare('SELECT 1').first();await ticket.finish();const state=await row();
 assert.equal(await pending(),0);assert.equal(state.spent_reads,small.reads);assert.equal(state.spent_writes,small.writes);
});
test('an underestimated result blocks future admissions and retains its reservation',async()=>{
 const ticket=await admit(control,scope,{reads:128,writes:32,growth:65536});
 await assert.rejects(ticket.database(db).prepare("INSERT INTO sample VALUES(1,'x')").run(),/BUDGET_ESTIMATE_EXCEEDED/);
 await ticket.finish();assert.equal((await row()).state,'BLOCKED');assert.equal(await pending(),1);
 await assert.rejects(admit(control,scope,small));assert.equal((await db.prepare('SELECT COUNT(*) n FROM sample').first()).n,1);
});
test('missing metadata fails closed and cannot release capacity',async()=>{
 const ticket=await admit(control,scope,small),bad={prepare:()=>({all:async()=>({success:true,results:[]})})};
 await assert.rejects(ticket.database(bad).prepare('SELECT 1').all(),/BUDGET_METER_UNAVAILABLE/);
 await ticket.finish();assert.equal(await pending(),1);assert.equal((await row()).state,'BLOCKED');
});
test('concurrent growth reservation and measured database size both enforce storage admission',async()=>{
 await control.prepare('UPDATE cloud_budget SET size_limit=100000,data_size=20000').run();
 const a=await admit(control,scope,small);await assert.rejects(admit(control,scope,small));await a.finish();
 await control.prepare('UPDATE cloud_budget SET size_limit=1,data_size=0').run();
 const b=await admit(control,scope,{reads:512,writes:64,growth:0});
 await assert.rejects(b.database(db).prepare('SELECT 1').all(),/BUDGET_ESTIMATE_EXCEEDED/);await b.finish();assert.equal((await row()).state,'BLOCKED');
});
test('public token lookup occurs only after reservation and its failures are metered',async()=>{
 let queries=0;
 const env={DB:{prepare:()=>{queries++;return {bind(){return this;},all:async()=>({success:true,meta:{rows_read:2,rows_written:0,size_after:0},results:[]})};}},
  CONTROL:control,FENCE_RESOURCE:'resource',PUBLIC_ORIGIN:'https://cloud.test',LIBRARY_ID:'library',SERVICE_GENERATION:'service',
  ENABLE_CATALOG_API:'true',REQUEST_LIMITER:{limit:async()=>({success:true})}};
 const request=()=>new Request('https://cloud.test/v1/head',{method:'POST',headers:{Authorization:'Bearer device.'+'1'.repeat(64),'Content-Type':'application/json'},body:'{}'});
 await control.prepare('DELETE FROM cloud_budget').run();assert.equal((await handleRequest(request(),env)).status,503);assert.equal(queries,0);
 await seedBudget(control,scope.resource);assert.equal((await handleRequest(request(),env)).status,401);assert.equal(queries,1);
 assert.equal((await row()).spent_reads,ACCOUNTING.reads+2);assert.equal(await pending(),0);
});
test('future ledger date refuses instead of manufacturing a reset, and no unmetered methods escape',async()=>{
 await control.prepare('UPDATE cloud_budget SET day=day+1').run();await assert.rejects(admit(control,scope,small));assert.equal(await pending(),0);
 await seedBudget(control,scope.resource);const t=await admit(control,scope,small),w=t.database(db);
 assert.equal(w.exec,undefined);assert.equal(w.withSession,undefined);assert.equal(w.prepare('SELECT 1').raw,undefined);await t.finish();
});
test('all published route estimates validate and unknown routes refuse',()=>{
 for(const path of ['/v1/head','/v1/uploads/chunk','/v1/recovery/advance','/v1/replacement/publish','/maintenance/housekeep'])validateEstimate(requestEstimate(path,8,65536));
 assert.throws(()=>requestEstimate('/arbitrary'),/BUDGET_ROUTE_UNAVAILABLE/);
 assert.ok(requestEstimate('/v1/uploads/chunk',2048,16384).growth>=2048*8192);
 validateEstimate(requestEstimate('/v1/uploads/publish',2048,32*1024*1024));
});

test('operator budget remains outside DATA, meters CONTROL without confusing its size and preserves unknown provider writes',async()=>{
 const t=await admitMaintenanceBudget(control,scope);
 await t.database(control,false).prepare('SELECT * FROM cloud_fence WHERE resource_id=?').bind(scope.resource).first();
 await t.database(db).prepare("INSERT INTO sample VALUES(1,'operator')").run();
 await t.finish();assert.equal(await pending(),0);assert.equal((await row()).held_reads,0);
 const lost=await admitMaintenanceBudget(control,scope);
 await assert.rejects(lost.effect(async()=>{throw new Error('provider outcome unknown');}));await lost.finish();
 assert.equal((await control.prepare('SELECT COUNT(*) n FROM cloud_budget_maintenance').first()).n,1);
 assert.equal((await row()).held_reads,200000);
});
test('disabled scheduled housekeeping makes no storage calls and enabled housekeeping still refuses missing policy',async()=>{
 assert.deepEqual(await scheduledHousekeeping({ENABLE_HOUSEKEEPING:'false'}),{enabled:false});
 await control.prepare('DELETE FROM cloud_budget').run();
 await assert.rejects(scheduledHousekeeping({ENABLE_HOUSEKEEPING:'true',DB:db,CONTROL:control,FENCE_RESOURCE:'resource',LIBRARY_ID:'library',SERVICE_GENERATION:'service'}),/BUDGET/);
});
test('denied accounting is bounded and is charged even when the daily threshold is already exhausted',async()=>{
 await control.prepare('UPDATE cloud_budget SET spent_reads=read_limit,spent_writes=write_limit').run();
 const c=measured(control);await assert.rejects(admit(c.db,scope,small));
 assert.ok(c.stats.reads<=16,JSON.stringify(c.stats));assert.ok(c.stats.writes<=4,JSON.stringify(c.stats));
 assert.equal((await row()).spent_reads,2000016);assert.equal((await row()).spent_writes,50004);assert.equal(await pending(),0);
});

test('authenticated CONTROL-only close remains metered when ordinary admission is blocked or exhausted',async()=>{
 await control.prepare("UPDATE cloud_budget SET state='BLOCKED',spent_reads=read_limit,spent_writes=write_limit").run();
 await assert.rejects(admit(control,scope,small));
 const t=await admitControlBudget(control,scope);
 const f=await closeFence(t.database(control,false),'resource','11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222');
 assert.equal(f.state,'HELD');await t.finish();
 const b=await row();assert.equal(b.state,'BLOCKED');assert.equal(b.held_reads,0);assert.ok(b.spent_reads>2000000);assert.ok(b.spent_writes>50000);
 await control.prepare('DELETE FROM cloud_budget').run();await assert.rejects(admitControlBudget(control,scope));
});
