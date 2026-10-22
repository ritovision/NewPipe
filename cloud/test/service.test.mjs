// SPDX-License-Identifier: GPL-3.0-or-later
import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {seedBudget} from './budget-fixture.mjs';
import {closeFence} from '../src/maintenance.ts';
import {readFile,readdir,mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,join} from 'node:path';
import {stripTypeScriptTypes} from 'node:module';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {encodePages,readPages,changedPages,pageManifest,parsePage,packEntries,sha256,byteLength} from '../src/pages.ts';
import {makeUpload,uploadDigest,TRANSPORT_LIMITS} from '../src/service.ts';
import {fingerprint} from '../src/catalog.ts';
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
const catalog=name=>structuredClone(fixture.catalogs.find(c=>c.name===name).catalog);
const tokens={a:'device-a.'+'1'.repeat(64),b:'device-b.'+'2'.repeat(64),r:'device-r.'+'3'.repeat(64)};
const EMPTY_FP=await fingerprint(catalog('empty')),EMPTY_MANIFEST=await pageManifest([]);
let mf,modules,persistence;
async function runtime(){return new Miniflare(convertV4MiniflareOptions({cf:false,modules,compatibilityDate:'2026-08-25',d1Databases:{DB:'newpipe-catalog-service-local',CONTROL:'service-control'},resourcePersistencePath:persistence}));}
async function call(path,data={},token=tokens.a,extra={}){
 const r=await mf.dispatchFetch('https://catalog.test'+path,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token,...extra.headers},body:JSON.stringify(data),...extra});
 return {status:r.status,data:await r.json(),headers:r.headers};
}
async function sql(...statements){
 const r=await call('/__test/sql',statements.map(s=>typeof s==='string'?{sql:s}:s));
 assert.equal(r.status,200,JSON.stringify(r.data));return r.data;
}
async function controls(value){return (await call('/__test/control',value)).data;}
async function status(token=tokens.a){const r=await call('/v1/head',{},token);assert.equal(r.status,200,JSON.stringify(r.data));return r.data;}
async function stage(target,{token=tokens.a,previous=[],sequence='1',mode,seal=true,hints=[]}={}){
 const head=await status(token),pages=await encodePages(target,previous,hints),changes=changedPages(previous,pages);
 const u=await makeUpload(mode??(head.initialized?'sync':'initialize'),sequence,head.head,await fingerprint(target),changes);
 const start=await call('/v1/uploads/start',u.header,token);assert.equal(start.status,200,JSON.stringify(start.data));
 for(let i=0;i<u.chunks.length;i++){
  const r=await call('/v1/uploads/chunk',{sequence,index:i,changes:u.chunks[i]},token);assert.equal(r.status,200,JSON.stringify(r.data));
 }
 const ref={sequence,digest:u.header.digest};
 if(seal){const r=await call('/v1/uploads/seal',ref,token);assert.equal(r.status,200,JSON.stringify(r.data));}
 return {...u,ref,pages,changes};
}
async function publish(u,token=tokens.a){const r=await call('/v1/uploads/publish',u.ref,token);assert.equal(r.status,200,JSON.stringify(r.data));return r.data.receipt;}
async function download(token=tokens.a){
 const head=await status(token);let cursor=null,pages=[];
 do{
  const r=await call('/v1/pages',{head:head.head,cursor},token);assert.equal(r.status,200,JSON.stringify(r.data));
  assert.deepEqual(r.data.head,head.head);pages.push(...r.data.data);cursor=r.data.nextCursor;
 }while(cursor!==null);
 assert.equal(pages.length,head.pages);assert.equal(pages.reduce((n,p)=>n+byteLength(p.body),0),head.bytes);
 assert.equal(await pageManifest(pages),head.manifest);
 const result=await readPages(pages);assert.equal(await fingerprint(result.catalog),head.head.fingerprint);
 return {...result,pages,head};
}
async function rows(table){return (await sql(`SELECT COUNT(*) AS n FROM ${table}`))[0].results[0].n;}
function failure(r,code){assert.notEqual(r.status,200,JSON.stringify(r.data));if(code)assert.equal(r.data.error,code);}
before(async()=>{
 modules=[];persistence=await mkdtemp(join(tmpdir(),'newpipe-service-test-'));
 for(const f of ['test/runtime-catalog-worker.mjs',...(await readdir(new URL('../src/',import.meta.url))).filter(f=>f.endsWith('.ts')).map(f=>'src/'+f)]){
  const source=await readFile(new URL('../'+f,import.meta.url),'utf8');
  modules.push({type:'ESModule',path:resolve(f),contents:f.endsWith('.ts')?stripTypeScriptTypes(source,{mode:'strip'}):source});
 }
 mf=await runtime();await mf.ready;
 const controlDb=await mf.getD1Database('CONTROL');
 const fenceSchema=(await Promise.all(['0001_fence.sql','0002_reopen.sql','0003_budgets.sql'].map(f=>readFile(new URL('../control-migrations/'+f,import.meta.url),'utf8')))).join('\n').replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
 await controlDb.batch(fenceSchema.map(s=>controlDb.prepare(s)));
 await controlDb.prepare("INSERT INTO cloud_fence(resource_id,database_id,bucket_name,library_id,service_generation,provenance,state,created_at) VALUES('test-resource','test-db','test-bucket','library-a','service-a','fresh-resources-v1','OPEN',0)").run();

 const migration=(await Promise.all(['0001_catalog_service.sql','0002_recovery.sql','0003_replacement.sql','0004_operator_receipts.sql','0005_reopen_receipts.sql','0006_housekeeping.sql'].map(f=>readFile(new URL('../migrations/'+f,import.meta.url),'utf8')))).join('\n').replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
 await sql(...migration);
});
after(async()=>{if(mf)await mf.dispose();if(persistence)await rm(persistence,{recursive:true,force:true});});
beforeEach(async()=>{
 await seedBudget(await mf.getD1Database('CONTROL'),'test-resource');
 await controls({options:{},fault:null,resetStats:true});
 await sql('DELETE FROM cloud_replace_attempts','DELETE FROM cloud_replace_approvals','DELETE FROM cloud_replace_grants','DELETE FROM cloud_backup_control','DELETE FROM cloud_upload_changes','DELETE FROM cloud_upload_chunks','DELETE FROM cloud_uploads','DELETE FROM cloud_catalog_pages',
  'DELETE FROM cloud_receipts','DELETE FROM cloud_catalog_info','DELETE FROM cloud_devices','DELETE FROM cloud_head','DELETE FROM cloud_commit_assertion');
 await sql({sql:'INSERT INTO cloud_head VALUES(?,?,0,0,?)',args:['library-a','epoch-a',EMPTY_FP]},
  {sql:'INSERT INTO cloud_catalog_info VALUES(?,0,0,0,?)',args:['library-a',EMPTY_MANIFEST]},
  ...await Promise.all(Object.entries(tokens).map(async([key,token])=>({sql:'INSERT INTO cloud_devices VALUES(?,?,?,?,1,0,?,0)',args:['library-a',token.split('.')[0],await sha256(token.split('.')[1]),'service-a',Number(key!=='r')]}))));
 await controls({resetStats:true});
});

test('authenticated connect reports uninitialized separately from an empty initialized library',async()=>{
 const initial=await status();assert.equal(initial.initialized,false);assert.equal(initial.pages,0);
 failure(await call('/v1/pages',{head:initial.head,cursor:null}),'CATALOG_NOT_INITIALIZED');
 const u=await stage(catalog('empty'));await publish(u);const next=await status();
 assert.equal(next.initialized,true);assert.equal(next.head.fingerprint,EMPTY_FP);assert.ok(next.pages>0);assert.deepEqual((await download()).catalog,catalog('empty'));
 failure(await call('/v1/uploads/start',{...u.header,sequence:'2',before:next.head}),'INITIALIZATION_STATE_CHANGED');
});
test('device A initializes; device B downloads the exact catalog and can publish a later edit',async()=>{
 const a=await stage(catalog('base'));const first=await publish(a);assert.equal(first.head.revision,'1');
 const b=await download(tokens.b);assert.equal(await fingerprint(b.catalog),await fingerprint(catalog('base')));
 const update=await stage(catalog('reorder'),{token:tokens.b,previous:b.pages});await publish(update,tokens.b);
 const final=await download();assert.equal(await fingerprint(final.catalog),await fingerprint(catalog('reorder')));assert.equal(final.head.head.revision,'2');
});
test('new catalog publication uses one receipt preflight without weakening replay resolution',async()=>{
 const staged=await stage(catalog('base'));
 await controls({resetStats:true});
 const committed=await call('/v1/uploads/publish',staged.ref);
 assert.equal(committed.status,200,JSON.stringify(committed.data));
 assert.equal(committed.data.receipt.outcome,'COMMITTED');
 const measured=await controls({});
 assert.equal(measured.batches,1);
 assert.equal(measured.statements-measured.maxBatch,3,
  'DATA calls before the guarded batch are token auth, sealed-upload read, and authenticated high-water/receipt preflight');
 assert.equal(await rows('cloud_receipts'),1);
 const repeat=await call('/v1/uploads/publish',staged.ref);
 assert.equal(repeat.status,200);assert.deepEqual(repeat.data,committed.data);
 assert.equal(await rows('cloud_receipts'),1);
});
test('partial uploaded pages never appear in the live catalog',async()=>{
 const u=await stage(catalog('base'),{seal:false});assert.equal((await status()).initialized,false);assert.equal(await rows('cloud_catalog_pages'),0);
 assert.ok(await rows('cloud_upload_changes')>0);failure(await call('/v1/uploads/publish',u.ref),'UPLOAD_NOT_SEALED_OR_EXPIRED');
 const sealed=await call('/v1/uploads/seal',u.ref);assert.equal(sealed.status,200);assert.equal(await rows('cloud_catalog_pages'),0);
 await publish(u);assert.ok(await rows('cloud_catalog_pages')>0);assert.equal(await rows('cloud_upload_changes'),0);assert.equal(await rows('cloud_upload_chunks'),0);
});
test('exact repeated start/chunk/seal/publish returns one committed outcome',async()=>{
 const head=await status(),pages=await encodePages(catalog('base')),u=await makeUpload('initialize','1',head.head,await fingerprint(catalog('base')),changedPages([],pages));
 const starts=await Promise.all([call('/v1/uploads/start',u.header),call('/v1/uploads/start',u.header)]);assert.ok(starts.every(r=>r.status===200),JSON.stringify(starts));
 for(let i=0;i<u.chunks.length;i++){
  const data={sequence:'1',index:i,changes:u.chunks[i]};const results=await Promise.all([call('/v1/uploads/chunk',data),call('/v1/uploads/chunk',data)]);
  assert.ok(results.every(r=>r.status===200),JSON.stringify(results));
 }
 const ref={sequence:'1',digest:u.header.digest};const seals=await Promise.all([call('/v1/uploads/seal',ref),call('/v1/uploads/seal',ref)]);assert.ok(seals.every(r=>r.status===200),JSON.stringify(seals));
 const publishes=await Promise.all(Array.from({length:4},()=>call('/v1/uploads/publish',ref)));assert.ok(publishes.every(r=>r.status===200),JSON.stringify(publishes));
 publishes.forEach(r=>assert.deepEqual(r.data,publishes[0].data));assert.equal(await rows('cloud_receipts'),1);assert.equal((await status()).head.revision,'1');
});
test('changed chunk or start header under the same operation is rejected',async()=>{
 const u=await stage(catalog('base'),{seal:false});
 failure(await call('/v1/uploads/start',{...u.header,targetFingerprint:'f'.repeat(64)}),'REPLAY_MISMATCH');
 failure(await call('/v1/uploads/chunk',{sequence:'1',index:0,changes:[{id:u.chunks[0][0].id,body:null}]}),'CHUNK_REPLAY_MISMATCH');
 assert.equal((await status()).initialized,false);
});
test('a false target fingerprint never seals or publishes',async()=>{
 const head=await status(),pages=await encodePages(catalog('base'));const u=await makeUpload('initialize','1',head.head,'a'.repeat(64),changedPages([],pages));
 assert.equal((await call('/v1/uploads/start',u.header)).status,200);
 for(let i=0;i<u.chunks.length;i++)assert.equal((await call('/v1/uploads/chunk',{sequence:'1',index:i,changes:u.chunks[i]})).status,200);
 failure(await call('/v1/uploads/seal',{sequence:'1',digest:u.header.digest}),'TARGET_FINGERPRINT_MISMATCH');assert.equal(await rows('cloud_catalog_pages'),0);
});
test('claimed digest does not substitute for verifying received page bytes',async()=>{
 const head=await status(),pages=await encodePages(catalog('base'));const u=await makeUpload('initialize','1',head.head,await fingerprint(catalog('base')),changedPages([],pages));u.header={...u.header,digest:'d'.repeat(64)};
 assert.equal((await call('/v1/uploads/start',u.header)).status,200);
 for(let i=0;i<u.chunks.length;i++)assert.equal((await call('/v1/uploads/chunk',{sequence:'1',index:i,changes:u.chunks[i]})).status,200);
 failure(await call('/v1/uploads/seal',{sequence:'1',digest:u.header.digest}),'UPLOAD_DIGEST_MISMATCH');assert.equal(await rows('cloud_catalog_pages'),0);
});
test('well-formed JSON with broken Library invariants is rejected server-side',async()=>{
 const base=await encodePages(catalog('base')),entries=base.flatMap(parsePage);
 const damaged=entries.filter(e=>!e[0].startsWith('s/library/'));const pages=await packEntries(damaged);
 const head=await status(),u=await makeUpload('initialize','1',head.head,await fingerprint(catalog('base')),changedPages([],pages));
 await call('/v1/uploads/start',u.header);
 for(let i=0;i<u.chunks.length;i++)assert.equal((await call('/v1/uploads/chunk',{sequence:'1',index:i,changes:u.chunks[i]})).status,200);
 failure(await call('/v1/uploads/seal',{sequence:'1',digest:u.header.digest}),'INVALID_TARGET_CATALOG');assert.equal(await rows('cloud_catalog_pages'),0);
});
test('incomplete chunks, skipped indices and immutable sealed uploads are enforced',async()=>{
 const head=await status(),pages=await encodePages(catalog('base')),u=await makeUpload('initialize','1',head.head,await fingerprint(catalog('base')),changedPages([],pages));
 await call('/v1/uploads/start',u.header);const ref={sequence:'1',digest:u.header.digest};
 failure(await call('/v1/uploads/seal',ref),'UPLOAD_INCOMPLETE');failure(await call('/v1/uploads/chunk',{sequence:'1',index:1,changes:u.chunks[0]}),'UPLOAD_STATE_CHANGED');
 for(let i=0;i<u.chunks.length;i++)await call('/v1/uploads/chunk',{sequence:'1',index:i,changes:u.chunks[i]});
 assert.equal((await call('/v1/uploads/seal',ref)).status,200);
 failure(await call('/v1/uploads/chunk',{sequence:'1',index:u.chunks.length,changes:u.chunks[0]}),'UPLOAD_STATE_CHANGED');
});
test('expired upload can be retired and cleaned but cannot publish',async()=>{
 const u=await stage(catalog('base'));await sql("UPDATE cloud_uploads SET expires_at=0");
 failure(await call('/v1/uploads/publish',u.ref),'UPLOAD_NOT_SEALED_OR_EXPIRED');
 assert.equal((await call('/v1/uploads/retire',u.ref)).data.receipt.outcome,'RETIRED');assert.equal(await rows('cloud_upload_changes'),0);
 failure(await call('/v1/uploads/publish',u.ref),'OPERATION_RETIRED');assert.equal((await status()).initialized,false);
 const fresh=await stage(catalog('base'),{sequence:'2'});await publish(fresh);assert.equal((await status()).head.revision,'1');
});
test('retire races publication without accepting a late discarded upload',async()=>{
 const u=await stage(catalog('base'));const out=await Promise.all([call('/v1/uploads/publish',u.ref),call('/v1/uploads/retire',u.ref)]);
 const receipt=(await call('/v1/uploads/status',u.ref)).data.receipt;assert.ok(receipt,JSON.stringify(out));assert.equal(await rows('cloud_receipts'),1);
 const current=await status();assert.equal(current.initialized,receipt.outcome==='COMMITTED');assert.equal(await rows('cloud_upload_changes'),0);
});
test('one shared revision allows only one of two separately staged devices to commit',async()=>{
 const a=await stage(catalog('base')),b=await stage(catalog('rename'),{token:tokens.b});
 const out=await Promise.all([call('/v1/uploads/publish',a.ref),call('/v1/uploads/publish',b.ref,tokens.b)]);
 assert.equal(out.filter(r=>r.status===200).length,1,JSON.stringify(out));assert.equal((await status()).head.revision,'1');
 const latest=await download();const winning=out[0].status===200?'base':'rename';assert.equal(await fingerprint(latest.catalog),await fingerprint(catalog(winning)));
});
test('cloud change invalidates a staged preview before seal',async()=>{
 const a=await stage(catalog('base'),{seal:false}),b=await stage(catalog('rename'),{token:tokens.b});await publish(b,tokens.b);
 failure(await call('/v1/uploads/seal',a.ref),'CLOUD_CHANGED');assert.equal(await fingerprint((await download()).catalog),await fingerprint(catalog('rename')));
});
test('revoke between preflight and commit aborts all catalog publication',async()=>{
 const u=await stage(catalog('base'));await controls({fault:'revokeBeforeCommit'});
 failure(await call('/v1/uploads/publish',u.ref));assert.equal(await rows('cloud_catalog_pages'),0);assert.equal(await rows('cloud_receipts'),0);
 assert.equal((await status(tokens.b)).initialized,false);
});
test('lost committed HTTP response resolves the existing receipt without duplicate writes',async()=>{
 const u=await stage(catalog('base'));await controls({fault:'afterCommit'});const r=await call('/v1/uploads/publish',u.ref);
 assert.equal(r.status,200,JSON.stringify(r.data));assert.equal(await rows('cloud_receipts'),1);
 assert.deepEqual((await call('/v1/uploads/publish',u.ref)).data,r.data);
});
test('unknown pre-commit transport failure does not mark catalog synchronized',async()=>{
 const u=await stage(catalog('base'));await controls({fault:'beforeCommit'});failure(await call('/v1/uploads/publish',u.ref),'OUTCOME_UNKNOWN');
 assert.equal((await status()).initialized,false);assert.equal(await rows('cloud_receipts'),0);await publish(u);
});
test('read-only token downloads but cannot upload, retire or claim write capabilities',async()=>{
 const u=await stage(catalog('base'));await publish(u);
 const read=await download(tokens.r);assert.equal(read.catalog.library.length,2);assert.deepEqual((await status(tokens.r)).capabilities,['catalog.read']);
 const plan=await makeUpload('sync','1',read.head.head,await fingerprint(catalog('rename')),changedPages(read.pages,await encodePages(catalog('rename'),read.pages)));
 failure(await call('/v1/uploads/start',plan.header,tokens.r),'AUTH_REJECTED');failure(await call('/v1/uploads/retire',{sequence:'1',digest:plan.header.digest},tokens.r),'AUTH_REJECTED');
});
for(const [label,token]of [['missing',''],['invalid','not-a-token'],['wrong secret','device-a.'+'0'.repeat(64)],['wrong device','other.'+'1'.repeat(64)]])
 test(`reject ${label} credential without returning private data`,async()=>{const r=await call('/v1/connect',{},token);assert.equal(r.status,401);assert.equal(r.data.head,undefined);assert.equal(r.headers.get('cache-control'),'no-store, private');});
test('service generation reset invalidates all old device credentials',async()=>{
 await controls({options:{SERVICE_GENERATION:'service-new'}});assert.equal((await call('/v1/connect')).status,503);assert.equal(await rows('cloud_receipts'),0);
});
test('disabled service, origins, methods, SQL routes and browser cross-origin requests fail closed',async()=>{
 await controls({options:{ENABLE_CATALOG_API:'false'}});failure(await call('/v1/connect'),'CLOUD_API_DISABLED');await controls({options:{}});
 const cross=await call('/v1/connect',{},tokens.a,{headers:{'Content-Type':'application/json',Authorization:'Bearer '+tokens.a,Origin:'https://evil.test'}});assert.equal(cross.status,403);
 const get=await mf.dispatchFetch('https://catalog.test/v1/head',{method:'GET'});assert.equal(get.status,405);
 assert.equal((await call('/v1/sql',{sql:'DELETE FROM cloud_head'})).status,404);
 assert.equal((await call('/v1/connect?token=secret')).status,403);
 await controls({options:{rateAllowed:false}});assert.equal((await call('/v1/head')).status,429);
});
test('bounded JSON reader rejects oversized and malformed content before any stage write',async()=>{
 const r=await mf.dispatchFetch('https://catalog.test/v1/uploads/start',{method:'POST',headers:{Authorization:'Bearer '+tokens.a,'Content-Type':'application/json'},body:' '.repeat(65537)});assert.equal(r.status,413);
 const bad=await mf.dispatchFetch('https://catalog.test/v1/uploads/start',{method:'POST',headers:{Authorization:'Bearer '+tokens.a,'Content-Type':'application/json'},body:'{' });assert.equal(bad.status,400);
 assert.equal(await rows('cloud_uploads'),0);
});
test('unknown request authority fields cannot select a different database/library',async()=>{
 const r=await call('/v1/connect',{library:'other',tokenHash:'a'.repeat(64)});assert.equal(r.status,400);assert.equal(await rows('cloud_uploads'),0);
});
test('advertised operator caps reject a transfer before reserving oversized storage',async()=>{
 await controls({options:{MAX_CATALOG_BYTES:'32768',MAX_CATALOG_PAGES:'1'}});
 const c={...catalog('empty'),library:Array.from({length:1000},(_,i)=>({service:0,url:'https://example.test/'+i}))};
 const pages=await encodePages(c),head=await status(),plan=await makeUpload('initialize','1',head.head,await fingerprint(c),changedPages([],pages));
 failure(await call('/v1/uploads/start',plan.header),'TRANSFER_STORAGE_LIMIT');assert.equal(await rows('cloud_uploads'),0);
});
test('paging is fenced to one exact head and detects mid-download changes',async()=>{
 const c={...catalog('empty'),library:Array.from({length:1300},(_,i)=>({service:0,url:'https://example.test/'+i}))};
 const u=await stage(c);await publish(u);const head=await status();assert.ok(head.pages>8);
 const first=await call('/v1/pages',{head:head.head,cursor:null},tokens.b);assert.equal(first.status,200);assert.ok(first.data.nextCursor);
 const next={...c,library:c.library.slice(1)},edit=await stage(next,{previous:u.pages,sequence:'2'});await publish(edit);
 failure(await call('/v1/pages',{head:head.head,cursor:first.data.nextCursor},tokens.b),'CLOUD_CHANGED');
 assert.equal((await download(tokens.b)).catalog.library.length,1299);
});
test('one video deletion updates a few packed rows, not every playlist occurrence row',async()=>{
 await controls({options:{MAX_CATALOG_BYTES:String(32*1024*1024),MAX_CATALOG_PAGES:'2048'}});
 const keys=Array.from({length:1000},(_,i)=>({service:0,url:'https://example.test/'+i}));
 const base={...catalog('empty'),library:keys,playlists:Array.from({length:5},(_,i)=>({id:`30000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`,name:'P'+i,artwork:null,items:keys}))};
 const u=await stage(base);await publish(u);await controls({resetStats:true});
 const target={...base,library:keys.slice(1),playlists:base.playlists.map(p=>({...p,items:keys.slice(1)}))};
 const edit=await stage(target,{previous:u.pages,sequence:'2'});assert.ok(edit.changes.length<=14,`changed pages=${edit.changes.length}`);await publish(edit);
 const metrics=(await call('/__test/stats')).data;assert.ok(metrics.maxBatch<=32);assert.ok(metrics.rowsWritten<200,JSON.stringify(metrics));
 const final=await download(tokens.b);assert.equal(final.catalog.library.length,999);assert.ok(final.catalog.playlists.every(p=>p.items.length===999));
 console.log('CLOUD_PACKED_PUBLICATION '+JSON.stringify({saved:1000,memberships:5000,changedPages:edit.changes.length,...metrics}));
});

test('worker restart preserves partial upload, immutable seal and committed receipt',async()=>{
 const u=await stage(catalog('base'),{seal:false});
 await mf.dispose();mf=await runtime();await mf.ready;
 assert.equal((await call('/v1/uploads/status',u.ref)).data.nextChunk,u.chunks.length);assert.equal((await status()).initialized,false);
 assert.equal((await call('/v1/uploads/seal',u.ref)).status,200);
 await mf.dispose();mf=await runtime();await mf.ready;
 const receipt=await publish(u);
 await mf.dispose();mf=await runtime();await mf.ready;
 assert.deepEqual((await call('/v1/uploads/publish',u.ref)).data.receipt,receipt);
 assert.equal(await fingerprint((await download(tokens.b)).catalog),await fingerprint(catalog('base')));
});
test('publication constraint failure rolls back payload, metadata and receipt together',async()=>{
 const u=await stage(catalog('base'));
 // Test-only trigger injects a late storage constraint, not an application schema change.
 await sql("CREATE TRIGGER reject_catalog_publish BEFORE UPDATE OF initialized ON cloud_catalog_info BEGIN SELECT RAISE(ABORT,'synthetic constraint failed'); END");
 try{
  failure(await call('/v1/uploads/publish',u.ref),'PRECONDITION_FAILED');
  assert.equal(await rows('cloud_catalog_pages'),0);assert.equal(await rows('cloud_receipts'),0);assert.equal((await status()).initialized,false);
  assert.equal((await call('/v1/uploads/status',u.ref)).data.status,'SEALED');
 }finally{await sql('DROP TRIGGER reject_catalog_publish');}
 await publish(u);
});
test('large catalog initializes then globally removes all saved items without row-per-membership writes',async()=>{
 await controls({options:{MAX_CATALOG_BYTES:String(32*1024*1024),MAX_CATALOG_PAGES:'2048'}});
 const started=performance.now(),keys=Array.from({length:20000},(_,i)=>({service:0,url:'https://example.test/scale/'+i}));
 const tag={id:'20000000-0000-0000-0000-000000000001',name:'Scale',nameKey:'scale',category:null};
 const c={...catalog('empty'),library:keys,tags:[tag],assignments:keys.map(key=>({key,tags:[tag.id]})),
  playlists:Array.from({length:5},(_,i)=>({id:`30000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`,name:'P'+i,artwork:null,items:keys}))};
 const u=await stage(c);await publish(u);const initialized=await status();
 assert.ok(initialized.pages<1500,`page count=${initialized.pages}`);assert.equal(initialized.head.fingerprint,await fingerprint(c));
 const initialMetrics=(await call('/__test/stats')).data;assert.ok(initialMetrics.maxBatch<=32);assert.ok(initialMetrics.rowsWritten<16000,JSON.stringify(initialMetrics));
 const downloaded=await download(tokens.b);assert.equal(downloaded.catalog.library.length,20000);assert.equal(downloaded.catalog.playlists.reduce((n,p)=>n+p.items.length,0),100000);assert.equal(downloaded.catalog.assignments.length,20000);
 await controls({resetStats:true});
 const cleared={...catalog('empty'),tags:[tag],playlists:c.playlists.map(p=>({...p,items:[]}))};
 const clear=await stage(cleared,{previous:u.pages,sequence:'2'});await publish(clear);
 const metrics=(await call('/__test/stats')).data;assert.ok(metrics.maxBatch<=32);assert.ok(metrics.rowsWritten<16000,JSON.stringify(metrics));
 const final=await download(tokens.b);assert.equal(final.catalog.library.length,0);assert.equal(final.catalog.tags.length,1);assert.equal(final.catalog.playlists.length,5);assert.ok(final.catalog.playlists.every(p=>p.items.length===0));
 console.log('CLOUD_SERVICE_SCALE '+JSON.stringify({streams:20000,memberships:100000,assignments:20000,pages:initialized.pages,bytes:initialized.bytes,init:initialMetrics,globalRemove:metrics,durationMs:Math.round(performance.now()-started),deployedCpuAcceptance:false}));
});


test('retire with a different digest cannot destroy or strand the active upload',async()=>{
 const u=await stage(catalog('base'),{seal:false});const wrong='9'.repeat(64);
 const originalChunks=await rows('cloud_upload_changes');assert.ok(originalChunks>0);
 failure(await call('/v1/uploads/retire',{sequence:'1',digest:wrong}));
 assert.equal(await rows('cloud_upload_changes'),originalChunks);assert.equal(await rows('cloud_receipts'),0);
 const active=(await call('/v1/uploads/status',u.ref)).data;assert.equal(active.digest,u.ref.digest);assert.equal(active.status,'UPLOADING');
 const retired=await call('/v1/uploads/retire',u.ref);assert.equal(retired.status,200);assert.equal(retired.data.receipt.outcome,'RETIRED');
 const next=await stage(catalog('base'),{sequence:'2'});await publish(next);assert.equal((await status()).head.revision,'1');
});

test('staged start racing mismatched retirement has one valid winner and no stuck slot',async()=>{
 const before=await status(),pages=await encodePages(catalog('base'));
 const u=await makeUpload('initialize','1',before.head,await fingerprint(catalog('base')),changedPages([],pages));
 const wrong={sequence:'1',digest:'9'.repeat(64)};
 const [started,retired]=await Promise.all([call('/v1/uploads/start',u.header),call('/v1/uploads/retire',wrong)]);
 assert.notEqual(started.status===200&&retired.status===200,true,JSON.stringify([started,retired]));
 if(started.status===200){
  assert.notEqual(retired.status,200);assert.equal(await rows('cloud_receipts'),0);
  assert.equal((await call('/v1/uploads/retire',{sequence:'1',digest:u.header.digest})).status,200);
 }else{
  assert.equal(retired.status,200,JSON.stringify(retired));assert.equal(await rows('cloud_uploads'),0);
 }
 const next=await stage(catalog('base'),{sequence:'2'});await publish(next);
 assert.equal((await status()).head.revision,'1');
});


test('retirement lost reply is resolved by status; next upload never requires a second retirement',async()=>{
 const u=await stage(catalog('base'));
 await controls({fault:'afterCommit'});
 const result=await call('/v1/uploads/retire',u.ref);assert.equal(result.status,200,JSON.stringify(result.data));
 assert.equal(result.data.receipt.outcome,'RETIRED');
 const resolved=await call('/v1/uploads/status',u.ref);assert.deepEqual(resolved.data.receipt,result.data.receipt);
 const state=(await sql("SELECT status FROM cloud_uploads WHERE device_id='device-a'"))[0].results[0];assert.equal(state.status,'RETIRED');
 assert.equal(await rows('cloud_upload_chunks'),0);assert.equal(await rows('cloud_upload_changes'),0);
 await mf.dispose();mf=await runtime();await mf.ready;
 const next=await stage(catalog('base'),{sequence:'2'});await publish(next);assert.equal((await status()).head.revision,'1');
});

test('retirement failure rolls back sequence, terminal state and payload together',async()=>{
 const u=await stage(catalog('base'));
 const changes=await rows('cloud_upload_changes'),chunks=await rows('cloud_upload_chunks');
 await controls({fault:'failRetirement'});failure(await call('/v1/uploads/retire',u.ref),'PRECONDITION_FAILED');
 assert.equal(await rows('cloud_receipts'),0);
 assert.equal((await call('/v1/uploads/status',u.ref)).data.status,'SEALED');
 assert.equal(await rows('cloud_upload_changes'),changes);assert.equal(await rows('cloud_upload_chunks'),chunks);
 await publish(u);assert.equal((await status()).head.revision,'1');
});

test('repeated retirement of a committed upload preserves committed receipt and newer upload',async()=>{
 const first=await stage(catalog('base'));const committed=await publish(first);
 const second=await stage(catalog('rename'),{previous:first.pages,sequence:'2'});
 assert.deepEqual((await call('/v1/uploads/retire',first.ref)).data.receipt,committed);
 assert.equal((await call('/v1/uploads/status',second.ref)).data.status,'SEALED');await publish(second);
});


test('connection reports exact own sequence and active upload without leaking other device state',async()=>{
 const before=await status();assert.deepEqual(before.deviceState,{resolvedSequence:'0',upload:null,replacement:null});
 const u=await stage(catalog('base'),{seal:false});
 const active=await status();assert.deepEqual(active.deviceState.upload,{sequence:'1',digest:u.ref.digest,status:'UPLOADING'});
 assert.equal((await status(tokens.b)).deviceState.upload,null);
 await call('/v1/uploads/retire',u.ref);assert.deepEqual((await status()).deviceState,{resolvedSequence:'1',upload:null,replacement:null});
});


test('unknown publication outcome is retryable 503 and never a definitive rejection',async()=>{
 const u=await stage(catalog('base'));await controls({fault:'beforeCommit'});
 const r=await call('/v1/uploads/publish',u.ref);assert.equal(r.status,503);assert.equal(r.data.error,'OUTCOME_UNKNOWN');assert.equal(r.data.outcome,'unknown');
 const retired=await call('/v1/uploads/retire',u.ref);assert.equal(retired.data.receipt.outcome,'RETIRED');
});

test('revocation during post-batch resolution does not claim the request failed to commit',async()=>{
 const u=await stage(catalog('base'));await controls({fault:'revokeBeforeCommit'});
 const r=await call('/v1/uploads/publish',u.ref);assert.equal(r.status,503);assert.equal(r.data.error,'AUTH_REJECTED');assert.equal(r.data.outcome,'unknown');
 assert.equal(await rows('cloud_catalog_pages'),0);
});

test('oversized legacy sealed stages refuse publication and retirement before deleting child rows',async()=>{
 const u=await stage(catalog('base'));
 await sql("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<257) INSERT INTO cloud_upload_chunks(library_id,device_id,sequence,chunk_index,digest) SELECT 'library-a','device-a',1,10000+x,'legacy' FROM n");
 const count=await rows('cloud_upload_chunks'),before=(await status()).head;
 failure(await call('/v1/uploads/publish',u.ref));
 failure(await call('/v1/uploads/retire',u.ref));
 assert.equal(await rows('cloud_upload_chunks'),count);assert.equal(await rows('cloud_receipts'),0);
 assert.deepEqual((await status()).head,before);
});

test('polling authenticates and reads head plus own pending state in two DATA statements',async()=>{
 const u=await stage(catalog('base'),{seal:false});
 await controls({resetStats:true});
 const state=await status();
 assert.equal(state.deviceState.resolvedSequence,'0');assert.equal(state.deviceState.upload.sequence,'1');
 assert.equal(state.deviceState.upload.status,'UPLOADING');assert.equal(state.deviceState.replacement,null);
 const measured=await controls({});assert.equal(measured.statements,2,JSON.stringify(measured));
 assert.equal((await call('/v1/uploads/retire',u.ref)).status,200);
 await controls({resetStats:true});
 const retired=await status();assert.equal(retired.deviceState.resolvedSequence,'1');assert.equal(retired.deviceState.upload,null);
 assert.equal((await controls({})).statements,2);
});

for(const variant of ['duplicate','descending'])test('Claude 001: cross-chunk '+variant+' rejection settles its ticket and permits maintenance',async()=>{
 const control=await mf.getD1Database('CONTROL');
 const ids=['1'.repeat(64),'2'.repeat(64)];
 const first=variant==='duplicate'?ids[0]:ids[1],second=ids[0];
 const initial=await status();
 const header={mode:'initialize',sequence:'1',before:initial.head,targetFingerprint:EMPTY_FP,chunkCount:2,changeCount:2,payloadBytes:0,digest:'a'.repeat(64)};
 assert.equal((await call('/v1/uploads/start',header)).status,200);
 assert.equal((await call('/v1/uploads/chunk',{sequence:'1',index:0,changes:[{id:first,body:null}]})).status,200);
 const before=await sql('SELECT * FROM cloud_upload_changes','SELECT * FROM cloud_upload_chunks','SELECT received_chunks,received_changes FROM cloud_uploads');
 for(let n=0;n<3;n++){
  const refused=await call('/v1/uploads/chunk',{sequence:'1',index:1,changes:[{id:second,body:null}]});
  assert.ok(refused.status>=400&&refused.status<500,JSON.stringify(refused));
  assert.equal((await control.prepare('SELECT COUNT(*) n FROM cloud_fence_tickets').first()).n,0,'Known invalid chunk must not become permanent uncertainty');
  const b=await control.prepare('SELECT state,held_reads,held_writes,held_growth FROM cloud_budget').first();
  assert.deepEqual(b,{state:'ACTIVE',held_reads:0,held_writes:0,held_growth:0});
 }
 assert.deepEqual(await sql('SELECT * FROM cloud_upload_changes','SELECT * FROM cloud_upload_chunks','SELECT received_chunks,received_changes FROM cloud_uploads').then(r=>r.map(x=>x.results)),before.map(x=>x.results));
 assert.deepEqual((await status()).head,initial.head);
 const held=await closeFence(control,'test-resource','61111111-1111-4111-8111-111111111111','62222222-2222-4222-8222-222222222222');
 assert.equal(held.state,'HELD');assert.equal(held.pending,0);
 // Only this disposable local fixture is reset for subsequent tests.
 await control.prepare("UPDATE cloud_fence SET state='OPEN',maintenance_id=NULL,next_generation=NULL WHERE resource_id='test-resource'").run();
});
