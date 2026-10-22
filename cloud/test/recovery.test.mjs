// SPDX-License-Identifier: GPL-3.0-or-later
import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {seedBudget} from './budget-fixture.mjs';
import {readFile,readdir,mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,join} from 'node:path';
import {stripTypeScriptTypes} from 'node:module';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {encodePages,changedPages,pageManifest,sha256,byteLength} from '../src/pages.ts';
import {makeUpload} from '../src/service.ts';
import {fingerprint} from '../src/catalog.ts';
import {RECOVERY_LIMITS as L,bytesDigest,recoveryDigest,cloudArchiveHeader,cloudArchivePage,readRecoveryManifest,verifyCloudArchivePart} from '../src/recovery-format.ts';
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
const catalog=name=>structuredClone(fixture.catalogs.find(c=>c.name===name).catalog);
const tokens={a:'device-a.'+'1'.repeat(64),b:'device-b.'+'2'.repeat(64),r:'device-r.'+'3'.repeat(64)};
const EMPTY_FP=await fingerprint(catalog('empty')),EMPTY_MANIFEST=await pageManifest([]);
let mf,persistence;
async function call(path,data={},token=tokens.a){
 const r=await mf.dispatchFetch('https://recovery.test'+path,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify(data)});
 return {status:r.status,data:await r.json(),headers:r.headers};
}
function ok(r){assert.equal(r.status,200,JSON.stringify(r.data));return r.data;}
function failure(r,code){assert.notEqual(r.status,200,JSON.stringify(r.data));if(code)assert.equal(r.data.error,code);return r;}
// Client-equivalent continuation for tests asking for completed cancellation.
// Raw per-request tests below use call() and assert each progress step/SQL cap.
async function recovery(path,data={},token=tokens.a){
 for(let step=0;step<4;step++){
  const r=await call('/v1/recovery/'+path,data,token);
  if(!['abort','abandon'].includes(path)||r.status!==200||!r.data.cleanupPending)return r;
 }
 assert.fail('ordinary cleanup exceeded four explicit continuation requests');
}
const control=data=>call('/__test/control',data).then(ok);
async function sql(...statements){return ok(await call('/__test/sql',statements.map(s=>typeof s==='string'?{sql:s}:s)));}
async function rows(table){return (await sql('SELECT * FROM '+table))[0].results;}
async function state(token=tokens.a){return ok(await recovery('status',{},token));}
function zip(payloadBytes=16){
 const name=Buffer.from('newpipe.db'),payload=Buffer.alloc(payloadBytes,65);let crc=0xffffffff;
 for(const b of payload){crc^=b;for(let bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}
 crc=(crc^0xffffffff)>>>0;
 const local=Buffer.alloc(30),central=Buffer.alloc(46),end=Buffer.alloc(22);
 local.writeUInt32LE(0x04034b50);local.writeUInt16LE(20,4);local.writeUInt32LE(crc,14);local.writeUInt32LE(payload.length,18);local.writeUInt32LE(payload.length,22);local.writeUInt16LE(name.length,26);
 central.writeUInt32LE(0x02014b50);central.writeUInt16LE(20,4);central.writeUInt16LE(20,6);central.writeUInt32LE(crc,16);central.writeUInt32LE(payload.length,20);central.writeUInt32LE(payload.length,24);central.writeUInt16LE(name.length,28);
 end.writeUInt32LE(0x06054b50);end.writeUInt16LE(1,8);end.writeUInt16LE(1,10);end.writeUInt32LE(central.length+name.length,12);end.writeUInt32LE(local.length+name.length+payload.length,16);
 return Buffer.concat([local,name,payload,central,name,end]);
}
async function manifest(bytes,source=null){
 const parts=[];for(let start=0;start<bytes.length;start+=L.partBytes){const part=bytes.subarray(start,start+L.partBytes);parts.push({bytes:part.length,sha256:await bytesDigest(part)});}
 return {version:1,kind:source?'cloud-catalog':'device-zip',producerValidation:source?'catalog-pages-v1':'native-restore-v1',bytes:bytes.length,source,native:source?null:{database:1,tags:1,uncompressedBytes:bytes.length},parts};
}
const ref=op=>({sequence:op.sequence,archiveId:op.archiveId});
async function reserve(bytes=zip(),{token=tokens.a,source=null,preview}={}){
 const m=await manifest(bytes,source),p=preview??ok(await recovery('preview',{manifest:m},token));
 const sequence=(BigInt((await state(token)).lastSequence)+1n).toString();
 return {...ok(await recovery('reserve',{approvalId:p.approvalId,sequence},token)),bytes,preview:p};
}
async function part(op,number=1,bytes=op.bytes,token=tokens.a){
 const data=bytes.subarray((number-1)*L.partBytes,number*L.partBytes);
 const r=await mf.dispatchFetch('https://recovery.test/v1/recovery/part',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/octet-stream','Content-Length':String(data.length),'X-Recovery-Sequence':op.sequence,'X-Recovery-Archive':op.archiveId,'X-Recovery-Part':String(number)},body:data});
 return {status:r.status,data:await r.json()};
}
async function finish(original,token=tokens.a){
 let op=original;
 for(let n=0;n<80;n++){
  if(op.state==='DONE')return {...op,bytes:original.bytes};
  if(op.state==='UPLOADING'){
   if(op.nextPart<=op.manifest.parts.length)op=ok(await part({...op,bytes:original.bytes},op.nextPart,original.bytes,token));
   else op=ok(await recovery('complete',ref(op),token));
  }else op=ok(await recovery('advance',ref(op),token));
 }
 assert.fail('recovery did not finish within bounded steps');
}
async function backup(bytes=zip(),options={}){return finish(await reserve(bytes,options),options.token??tokens.a);}
async function publishCatalog(name,token=tokens.a,previous=[]){
 const h=ok(await call('/v1/head',{},token)),pages=await encodePages(catalog(name),previous);
 const u=await makeUpload(h.initialized?'sync':'initialize',(BigInt(h.deviceState.resolvedSequence)+1n).toString(),h.head,await fingerprint(catalog(name)),changedPages(previous,pages));
 ok(await call('/v1/uploads/start',u.header,token));
 for(let index=0;index<u.chunks.length;index++)ok(await call('/v1/uploads/chunk',{sequence:u.header.sequence,index,changes:u.chunks[index]},token));
 const r={sequence:u.header.sequence,digest:u.header.digest};ok(await call('/v1/uploads/seal',r,token));ok(await call('/v1/uploads/publish',r,token));
 const status=ok(await call('/v1/head',{},token)),source={head:status.head,pages:status.pages,bytes:status.bytes,manifest:status.manifest};
 return {pages,source,bytes:Buffer.from(cloudArchiveHeader(source)+pages.map(cloudArchivePage).join(''))};
}
async function waitBlocked(point){
 for(let i=0;i<100;i++){if((await control({})).blocked===point)return;await new Promise(r=>setTimeout(r,5));}
 assert.fail('test hook did not block: '+point);
}
before(async()=>{
 const modules=[];persistence=await mkdtemp(join(tmpdir(),'newpipe-recovery-test-'));
 for(const f of ['test/runtime-recovery-worker.mjs',...(await readdir(new URL('../src/',import.meta.url))).filter(f=>f.endsWith('.ts')).map(f=>'src/'+f)]){
  const source=await readFile(new URL('../'+f,import.meta.url),'utf8');modules.push({type:'ESModule',path:resolve(f),contents:f.endsWith('.ts')?stripTypeScriptTypes(source,{mode:'strip'}):source});
 }
 mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules,compatibilityDate:'2026-08-25',d1Databases:{DB:'recovery-local',CONTROL:'recovery-control'},r2Buckets:{RECOVERY:'recovery-local'},resourcePersistencePath:persistence}));await mf.ready;
 const controlDb=await mf.getD1Database('CONTROL');
 const fenceSchema=(await Promise.all(['0001_fence.sql','0002_reopen.sql','0003_budgets.sql'].map(f=>readFile(new URL('../control-migrations/'+f,import.meta.url),'utf8')))).join('\n').replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
 await controlDb.batch(fenceSchema.map(s=>controlDb.prepare(s)));
 await controlDb.prepare("INSERT INTO cloud_fence(resource_id,database_id,bucket_name,library_id,service_generation,provenance,state,created_at) VALUES('test-resource','test-db','test-bucket','library-a','service-a','fresh-resources-v1','OPEN',0)").run();

 for(const file of ['0001_catalog_service.sql','0002_recovery.sql','0003_replacement.sql','0004_operator_receipts.sql','0005_reopen_receipts.sql','0006_housekeeping.sql']){
  const statements=(await readFile(new URL('../migrations/'+file,import.meta.url),'utf8')).replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);await sql(...statements);
 }
});
after(async()=>{if(mf)await mf.dispose();if(persistence)await rm(persistence,{recursive:true,force:true});});
beforeEach(async()=>{
 await seedBudget(await mf.getD1Database('CONTROL'),'test-resource');
 ok(await call('/__test/reset'));
 await sql(...['cloud_replace_attempts','cloud_replace_approvals','cloud_replace_grants','cloud_backup_slots','cloud_backup_operations','cloud_backup_approvals','cloud_backup_control','cloud_upload_changes','cloud_upload_chunks','cloud_uploads','cloud_catalog_pages','cloud_receipts','cloud_catalog_info','cloud_devices','cloud_head','cloud_commit_assertion'].map(t=>'DELETE FROM '+t));
 await sql({sql:'INSERT INTO cloud_head VALUES(?,?,0,0,?)',args:['library-a','epoch-a',EMPTY_FP]},
  {sql:'INSERT INTO cloud_catalog_info VALUES(?,0,0,0,?)',args:['library-a',EMPTY_MANIFEST]},
  ...await Promise.all(Object.entries(tokens).map(async([key,token])=>({sql:'INSERT INTO cloud_devices VALUES(?,?,?,?,1,0,?,0)',args:['library-a',token.split('.')[0],await sha256(token.split('.')[1]),'service-a',Number(key!=='r')]}))),
  {sql:'INSERT INTO cloud_backup_control(library_id,service_generation) VALUES(?,?)',args:['library-a','service-a']});
});

test('recovery status batches its three scoped DATA reads while still HEAD-verifying READY archives',async()=>{
 await control({resetStatements:true});
 const initial=ok(await recovery('status',{},tokens.a));
 assert.equal(initial.occupiedSlots,0);assert.deepEqual(initial.archives,[]);
 const first=await control({});
 assert.equal(first.statements,4,'Token authentication plus control, slots and own-operation reads');
 assert.equal(first.batches,1,'Only one D1 round trip for all three status selects');
 const archived=await backup();
 const before=await control({resetStatements:true}),headCalls=before.calls.head??0;
 const ready=ok(await recovery('status',{},tokens.r));
 assert.equal(ready.occupiedSlots,1);
 assert.equal(ready.archives[0].archiveId,archived.archiveId);
 assert.equal(ready.archives[0].usable,true,'Must still verify stored R2 bytes and metadata');
 const measured=await control({});
 assert.equal(measured.statements,4);
 assert.equal(measured.batches,1);
 assert.equal((measured.calls.head??0)-headCalls,1,'Exactly one R2 HEAD for the existing READY archive');
});
test('valid native ZIP uses multipart, verifies bytes, pins and downloads exactly',async()=>{
 const saved=await backup(),s=await state();assert.equal(s.occupiedSlots,1);assert.equal(s.archives[0].usable,true);assert.equal(s.archives[0].validation,'producer-native-validation-and-verified-transfer');
 assert.equal(saved.digest,await recoveryDigest(saved.manifest));
 const pin=ok(await recovery('pin',{archiveId:saved.archiveId}));
 const r=await mf.dispatchFetch('https://recovery.test/v1/recovery/download',{method:'POST',headers:{Authorization:'Bearer '+tokens.a,'Content-Type':'application/json'},body:JSON.stringify({archiveId:saved.archiveId,pinId:pin.pinId,part:1})});
 assert.equal(r.status,200);assert.equal(r.headers.get('x-recovery-sha256'),saved.manifest.parts[0].sha256);assert.deepEqual(Buffer.from(await r.arrayBuffer()),saved.bytes);
 ok(await recovery('release',{archiveId:saved.archiveId,pinId:pin.pinId}));assert.equal((await control({})).calls.create,1);
});
test('three full slots rotate only the named oldest after confirming two newer usable survivors',async()=>{
 const a=await backup(zip(10)),b=await backup(zip(11)),c=await backup(zip(12)),d=await reserve(zip(13));
 assert.equal(d.state,'ROTATING');assert.equal(d.preview.evictArchiveId,a.archiveId);assert.deepEqual(d.preview.remainingArchives,[b.archiveId,c.archiveId]);assert.equal((await control({})).objects,3);
 await finish(d);assert.deepEqual((await state()).archives.map(x=>x.archiveId),[b.archiveId,c.archiveId,d.archiveId]);
 assert.equal((await control({})).maxPayloads,3);
});
test('replayed approval/sequence never allocates another object even after old history rotated',async()=>{
 const a=await backup();for(let i=0;i<3;i++)await backup(zip(20+i),{token:tokens.b});
 const replay=ok(await recovery('reserve',{approvalId:a.approvalId,sequence:a.sequence}));assert.equal(replay.archiveId,a.archiveId);assert.equal(replay.state,'DONE');assert.equal((await control({})).calls.create,4);
 failure(await recovery('reserve',{approvalId:crypto.randomUUID(),sequence:a.sequence}),'RECOVERY_REPLAY_MISMATCH');
});
test('one active upload spans devices and refuses a fourth reservation',async()=>{
 const a=await reserve();failure(await recovery('preview',{manifest:a.manifest},tokens.b),'RECOVERY_UPLOAD_PENDING');assert.equal((await state()).occupiedSlots,1);
 ok(await recovery('abort',ref(a)));assert.equal((await state()).occupiedSlots,0);assert.equal((await control({})).calls.create,undefined);
});
test('concurrent reservations from two previews create exactly one operation',async()=>{
 const m=await manifest(zip()),a=ok(await recovery('preview',{manifest:m})),b=ok(await recovery('preview',{manifest:m},tokens.b));
 const out=await Promise.all([recovery('reserve',{approvalId:a.approvalId,sequence:'1'}),recovery('reserve',{approvalId:b.approvalId,sequence:'1'},tokens.b)]);
 assert.equal(out.filter(x=>x.status===200).length,1,JSON.stringify(out));assert.equal((await state()).occupiedSlots,1);
});
test('duplicate advance cannot invoke non-idempotent multipart creation twice',async()=>{
 const op=await reserve();await Promise.all([recovery('advance',ref(op)),recovery('advance',ref(op)),recovery('advance',ref(op))]);
 assert.equal((await control({})).calls.create,1);await finish({...ok(await recovery('advance',ref(op))),bytes:op.bytes});
});
for(const fault of ['claimCreate.after','create.before','create.after','recordCreate.before']){
 test(fault+' leaves an occupied reservation and never retries ambiguous creation',async()=>{
  const op=await reserve();await control({fault});failure(await recovery('advance',ref(op)));
  const creates=(await control({})).calls.create??0;
  failure(await recovery('advance',ref(op)),'RECOVERY_CREATION_OUTCOME_UNKNOWN');
  failure(await recovery('abort',ref(op)),'RECOVERY_OPERATOR_CLEANUP_REQUIRED');
  failure(await recovery('preview',{manifest:op.manifest},tokens.b),'RECOVERY_UPLOAD_PENDING');
  assert.equal((await state()).occupiedSlots,1);assert.equal((await control({})).calls.create??0,creates);
 });
}
test('creation identity committed despite lost response resumes the same multipart upload',async()=>{
 const op=await reserve();await control({fault:'recordCreate.after'});failure(await recovery('advance',ref(op)));
 await finish({...ok(await recovery('advance',ref(op))),bytes:op.bytes});assert.equal((await control({})).calls.create,1);
});
test('late creator cannot free a quarantined slot or create an uncounted fourth payload',async()=>{
 const op=await reserve();await control({block:'create.before'});const pending=recovery('advance',ref(op));await waitBlocked('create.before');
 failure(await recovery('abort',ref(op)),'RECOVERY_OPERATOR_CLEANUP_REQUIRED');await control({release:true});failure(await pending);
 assert.equal((await state()).occupiedSlots,1);assert.equal((await control({})).uploads,1);failure(await recovery('preview',{manifest:op.manifest},tokens.b));
});
for(const fault of ['part.after','recordPart.before']){
 test(fault+' retries only the same immutable part after lease expiry',async()=>{
  const op=await reserve();ok(await recovery('advance',ref(op)));await control({fault});failure(await part(op));
  failure(await part(op));await sql('UPDATE cloud_backup_slots SET busy_until=0');ok(await part(op));
  await finish({...ok(await recovery('advance',ref(op))),bytes:op.bytes});assert.equal((await control({})).calls.create,1);
 });
}
test('altered part checksum is rejected before R2 write and a correct retry succeeds',async()=>{
 const op=await reserve();ok(await recovery('advance',ref(op)));const altered=Buffer.from(op.bytes);altered[0]^=1;
 failure(await part(op,1,altered),'RECOVERY_PART_CHECKSUM_MISMATCH');assert.equal((await control({})).calls.part,undefined);await finish({...ok(await recovery('advance',ref(op))),bytes:op.bytes});
});
test('two-part ZIP verifies incrementally with fixed 8 MiB first part',async()=>{
 const op=await backup(zip(L.partBytes+512));assert.equal(op.manifest.parts.length,2);assert.equal((await control({})).calls.part,2);assert.equal((await state()).archives[0].usable,true);
});
for(const fault of ['complete.after','recordComplete.before','recordComplete.after','recordVerify.after']){
 test(fault+' resolves a completed object without recreating it',async()=>{
  const op=await reserve();ok(await recovery('advance',ref(op)));ok(await part(op));await control({fault});
  await recovery('complete',ref(op));await recovery('advance',ref(op));await finish({...ok(await recovery('advance',ref(op))),bytes:op.bytes});
  assert.equal((await control({})).calls.create,1);assert.equal((await control({})).calls.complete,1);assert.equal((await state()).occupiedSlots,1);
 });
}
test('complete before all parts is refused',async()=>{
 const op=await reserve();ok(await recovery('advance',ref(op)));failure(await recovery('complete',ref(op)),'RECOVERY_UPLOAD_INCOMPLETE');ok(await recovery('abort',ref(op)));assert.equal((await state()).occupiedSlots,0);
});
test('invalid ZIP remains quarantined, occupies capacity, and known upload cleanup is explicit',async()=>{
 const bad=zip();bad[bad.length-22]=0;const op=await reserve(bad);ok(await recovery('advance',ref(op)));ok(await part(op));ok(await recovery('complete',ref(op)));
 failure(await recovery('advance',ref(op)),'TRUNCATED_NATIVE_ZIP');assert.equal((await state()).archives[0].state,'QUARANTINED');
 ok(await recovery('abort',ref(op)));assert.equal((await state()).occupiedSlots,0);assert.equal((await control({})).objects,0);
});
test('failed deletion retains all three slots and creates no fourth upload',async()=>{
 for(let i=0;i<3;i++)await backup(zip(20+i));const op=await reserve();await control({fault:'delete.before'});
 failure(await recovery('advance',ref(op)),'RECOVERY_DELETE_UNCONFIRMED');assert.equal((await state()).occupiedSlots,3);assert.equal((await control({})).calls.create,3);
 await finish({...ok(await recovery('advance',ref(op))),bytes:op.bytes});assert.equal((await control({})).maxPayloads,3);
});
test('lost delete response retries the same immutable key and preserves both survivors',async()=>{
 for(let i=0;i<3;i++)await backup(zip(20+i));const op=await reserve();await control({fault:'delete.after'});failure(await recovery('advance',ref(op)));
 await finish({...ok(await recovery('advance',ref(op))),bytes:op.bytes});assert.equal((await state()).occupiedSlots,3);assert.equal((await control({})).maxPayloads,3);
});
test('missing survivor blocks rotation before any oldest deletion',async()=>{
 for(let i=0;i<3;i++)await backup(zip(20+i));const op=await reserve(),slots=await rows('cloud_backup_slots');
 ok(await call('/__test/object',{remove:true,key:slots.find(s=>s.archive_id!==op.evictArchiveId).object_key}));
 failure(await recovery('advance',ref(op)),'RECOVERY_OBJECT_MISSING_OR_CHANGED');assert.equal((await control({})).calls.delete,undefined);assert.equal((await state()).occupiedSlots,3);
});
test('pin blocks rotation, expires without renewal, and invalidates older approval',async()=>{
 const a=await backup(),m=await manifest(zip()),p=ok(await recovery('preview',{manifest:m}));
 const pin=ok(await recovery('pin',{archiveId:a.archiveId}));assert.deepEqual(ok(await recovery('pin',{archiveId:a.archiveId})),pin);
 failure(await recovery('preview',{manifest:m}),'RECOVERY_SLOT_PROTECTED');failure(await recovery('reserve',{approvalId:p.approvalId,sequence:'2'}),'RECOVERY_APPROVAL_CHANGED');
 ok(await recovery('release',{archiveId:a.archiveId,pinId:pin.pinId}));await backup();
});
test('read-only device may pin/download but cannot reserve or resume writes',async()=>{
 const a=await backup();assert.ok(ok(await call('/v1/head',{},tokens.r)).capabilities.includes('backup.read'));
 ok(await recovery('pin',{archiveId:a.archiveId},tokens.r));failure(await recovery('preview',{manifest:a.manifest},tokens.r),'AUTH_REJECTED');
 await sql("UPDATE cloud_devices SET can_write=0 WHERE device_id='device-a'");failure(await recovery('abort',ref(a)),'AUTH_REJECTED');
});
test('revoked token and rotated service generation reject recovery access',async()=>{
 await backup();await sql("UPDATE cloud_devices SET revoked=1 WHERE device_id='device-a'");assert.equal((await recovery('status')).status,401);
 await control({options:{SERVICE_GENERATION:'service-new'}});assert.equal((await recovery('status',{},tokens.b)).status,503);
});
test('missing or wrong-generation index is never automatically initialized',async()=>{
 await sql('DELETE FROM cloud_backup_control');failure(await recovery('status'),'RECOVERY_INDEX_RECONCILIATION_REQUIRED');assert.equal((await rows('cloud_backup_control')).length,0);
 await sql("INSERT INTO cloud_backup_control(library_id,service_generation) VALUES('library-a','old-service')");failure(await recovery('status'),'RECOVERY_INDEX_RECONCILIATION_REQUIRED');
});
test('unindexed R2 object blocks reservation rather than creating a hidden extra backup',async()=>{
 ok(await call('/__test/object',{key:'recovery/library-a/orphan',data:zip().toString('base64'),metadata:{}}));
 failure(await recovery('preview',{manifest:await manifest(zip())}),'RECOVERY_UNINDEXED_OBJECTS');assert.equal((await state()).occupiedSlots,0);
});
test('deployment gate and operator byte cap fail closed',async()=>{
 await control({options:{ENABLE_RECOVERY_API:'false'}});failure(await recovery('status'),'RECOVERY_API_DISABLED');assert.ok(!ok(await call('/v1/head')).capabilities.includes('backup.r2'));
 await control({options:{MAX_RECOVERY_BYTES:'128'}});failure(await recovery('preview',{manifest:await manifest(zip(100))}),'INVALID_RECOVERY_SIZE');
});
test('hard 256 MiB, 32-part and fixed-size shape limits apply before storage',async()=>{
 const m=await manifest(zip());for(const bad of [{...m,bytes:L.bytes+1},{...m,parts:[]},{...m,parts:[{...m.parts[0],sha256:'x'.repeat(64)}]},{...m,producerValidation:'none'}])
  failure(await recovery('preview',{manifest:bad}));
 const maximum={...m,bytes:L.bytes,parts:Array.from({length:32},()=>({bytes:L.partBytes,sha256:'1'.repeat(64)}))};assert.equal(readRecoveryManifest(maximum).parts.length,32);
 assert.equal((await state()).occupiedSlots,0);
});
test('exact cloud archive is bound to the trusted outgoing head and page manifest',async()=>{
 const source=await publishCatalog('base'),saved=await backup(source.bytes,{source:source.source});
 assert.equal((await state()).archives[0].validation,'verified-exact-cloud-pages');assert.equal(saved.manifest.source.manifest,source.source.manifest);
 const b=await publishCatalog('rename',tokens.b,source.pages);failure(await recovery('preview',{manifest:await manifest(source.bytes,source.source)}),'CLOUD_ARCHIVE_SOURCE_CHANGED');
 assert.notEqual(b.source.head.fingerprint,source.source.head.fingerprint);
});
test('incoming local pages cannot masquerade as an outgoing cloud recovery archive',async()=>{
 const a=await publishCatalog('base'),other=await encodePages(catalog('rename'));
 const forged=Buffer.from(cloudArchiveHeader(a.source)+other.map(cloudArchivePage).join('')),op=await reserve(forged,{source:a.source});
 ok(await recovery('advance',ref(op)));ok(await part(op));ok(await recovery('complete',ref(op)));
 failure(await recovery('advance',ref(op)));assert.equal((await state()).archives[0].state,'QUARANTINED');
});
test('cloud head change after preview invalidates approval before any archive allocation',async()=>{
 const a=await publishCatalog('base'),p=ok(await recovery('preview',{manifest:await manifest(a.bytes,a.source)}));
 await publishCatalog('rename',tokens.b,a.pages);failure(await recovery('reserve',{approvalId:p.approvalId,sequence:'1'}));assert.equal((await state()).occupiedSlots,0);
});
test('streamed cloud archive verification preserves split UTF-8 and rejects truncation/extra pages',async()=>{
 const a=await publishCatalog('base');let s=null;
 for(let i=0;i<a.bytes.length;i+=17)s=await verifyCloudArchivePart(s,a.bytes.subarray(i,i+17),a.source,i+17>=a.bytes.length);
 assert.equal(s.leaves.length,a.source.pages);assert.equal(s.carry,'');
 await assert.rejects(verifyCloudArchivePart(null,a.bytes.subarray(0,-1),a.source,true));
 await assert.rejects(verifyCloudArchivePart(null,Buffer.concat([a.bytes,Buffer.from(cloudArchivePage(a.pages[0]))]),a.source,true));
});

test('abandon before reserve fences a delayed request without allocating a slot',async()=>{
 const m=await manifest(zip()),p=ok(await recovery('preview',{manifest:m})),r={approvalId:p.approvalId,sequence:'1'};
 assert.equal(ok(await recovery('abandon',r)).state,'CANCELED');
 assert.equal(ok(await recovery('reserve',r)).state,'CANCELED');
 assert.equal((await state()).occupiedSlots,0);assert.equal((await control({})).calls.create,undefined);
});
test('abandon after lost reserve reply resolves and frees only the reserved attempt',async()=>{
 const op=await reserve();assert.equal(ok(await recovery('abandon',{approvalId:op.approvalId,sequence:op.sequence})).state,'CANCELED');
 assert.equal((await state()).occupiedSlots,0);assert.equal(ok(await recovery('reserve',{approvalId:op.approvalId,sequence:op.sequence})).state,'CANCELED');
});
test('cancel raced with reservation never leaves an unrecorded allocated payload',async()=>{
 const m=await manifest(zip()),p=ok(await recovery('preview',{manifest:m})),r={approvalId:p.approvalId,sequence:'1'};
 await Promise.all([recovery('reserve',r),recovery('abandon',r)]);
 assert.equal(ok(await recovery('abandon',r)).state,'CANCELED');assert.equal((await state()).occupiedSlots,0);
});
test('abandon preserves a completed backup rather than deleting verified history',async()=>{
 const op=await backup();assert.equal(ok(await recovery('abandon',{approvalId:op.approvalId,sequence:op.sequence})).state,'DONE');
 assert.equal((await state()).archives[0].usable,true);
});

test('stale failed verifier cannot quarantine a successor that advanced to the next part',async()=>{
 const op=await reserve(zip(L.partBytes+512));ok(await recovery('advance',ref(op)));ok(await part(op,1));ok(await part(op,2));ok(await recovery('complete',ref(op)));
 await control({block:'get.before'});const stale=recovery('advance',ref(op));await waitBlocked('get.before');
 await sql('UPDATE cloud_backup_slots SET busy_until=0');
 const successor=ok(await recovery('advance',ref(op)));assert.equal(successor.verifyPart,2);
 await control({fault:'get.after',release:true});failure(await stale);
 assert.equal((await state()).operation.state,'VERIFYING');assert.equal((await state()).operation.verifyPart,2);
 await finish({...successor,bytes:op.bytes});assert.equal((await state()).archives[0].state,'READY');
});

test('completion with sparse response metadata uses authoritative head before accepting bytes',async()=>{
 await control({options:{sparseComplete:true}});await backup();assert.equal((await state()).archives[0].usable,true);
});


test('abort paused after rotation preserves an archive completed by a concurrent resumer',async()=>{
 await backup();await backup();await backup();const op=await reserve();assert.equal(op.state,'ROTATING');
 await control({block:'removeSlot.after'});const aborting=recovery('abort',ref(op));await waitBlocked('removeSlot.after');
 const saved=await finish(op);assert.equal(saved.state,'DONE');await control({release:true});
 failure(await aborting,'RECOVERY_ALREADY_COMMITTED');const status=await state();
 assert.equal(status.operation,null);assert.equal(status.occupiedSlots,3);assert.equal(status.archives.find(a=>a.archiveId===op.archiveId).usable,true);
 assert.equal((await control({})).objects,3);
});
test('concurrent uncertain abort cannot hide a quarantined slot behind a canceled operation',async()=>{
 const op=await reserve();ok(await recovery('advance',ref(op)));
 await control({block:'claimAbort.after'});const first=recovery('abort',ref(op));await waitBlocked('claimAbort.after');
 await control({fault:'abort.before'});failure(await recovery('abort',ref(op)),'RECOVERY_ABORT_OUTCOME_UNKNOWN');
 assert.equal((await state()).operation.state,'QUARANTINED');await control({release:true});failure(await first,'RECOVERY_STATE_CHANGED');
 const status=await state();assert.equal(status.occupiedSlots,1);assert.equal(status.operation.state,'QUARANTINED');assert.equal(status.archives[0].state,'QUARANTINED');
 ok(await recovery('abort',ref(op)));assert.equal((await state()).occupiedSlots,0);assert.equal((await control({})).uploads,0);
});

// Protected replacement tests share the real local D1/R2 HTTP harness above.
const replace=(path,data={},token=tokens.a)=>call('/v1/replacement/'+path,data,token);
async function enableReplacement(){
 await control({options:{ENABLE_REPLACEMENT_API:'true'}});
 await sql("INSERT OR REPLACE INTO cloud_replace_grants SELECT library_id,device_id,service_generation,auth_generation,1 FROM cloud_devices WHERE can_write=1");
}
async function replacementPreview(source,{name='rename',token=tokens.a,retryAttemptId=null}={}){
 const h=ok(await call('/v1/head',{},token)),target=catalog(name),pages=await encodePages(target,source.pages);
 const upload=await makeUpload('replace',String(BigInt(h.deviceState.resolvedSequence)+1n),h.head,await fingerprint(target),changedPages(source.pages,pages));
 const preview=ok(await replace('preview',{incoming:upload.header,manifest:await manifest(source.bytes,source.source),retryAttemptId},token));
 return {preview,upload,bytes:source.bytes,token};
}
async function reserveReplacement(p){
 const recoverySequence=String(BigInt((await state(p.token)).lastSequence)+1n);
 const reserved=ok(await replace('reserve',{approvalId:p.preview.approvalId,recoverySequence},p.token));
 return {...p,...reserved,operation:{...reserved.operation,bytes:p.bytes}};
}
async function sealReplacement(p){
 ok(await call('/v1/uploads/start',p.upload.header,p.token));
 for(let index=0;index<p.upload.chunks.length;index++)ok(await call('/v1/uploads/chunk',{sequence:p.upload.header.sequence,index,changes:p.upload.chunks[index]},p.token));
 ok(await call('/v1/uploads/seal',{sequence:p.upload.header.sequence,digest:p.upload.header.digest},p.token));
}
const publishReplacement=p=>replace('publish',{attemptId:p.attempt.attemptId},p.token);
const settleReplacement=(p,keepForRetry)=>replace('settle',{attemptId:p.attempt.attemptId,keepForRetry},p.token);

test('replacement-enabled connect/head folds an exact authenticated grant into one indexed snapshot',async()=>{
 await enableReplacement();
 await control({resetStatements:true});
 const granted=ok(await call('/v1/head'));
 assert.ok(granted.capabilities.includes('catalog.replace'));
 assert.ok(!granted.unavailableCapabilities.includes('catalog.replace'));
 assert.equal(granted.replacementAllowed,undefined,'Internal grant column is never a protocol field');
 assert.equal((await control({})).statements,2,'Only token auth and the single current-state/grant snapshot read DATA');
 await control({resetStatements:true});
 const readOnly=ok(await call('/v1/connect',{},tokens.r));
 assert.ok(!readOnly.capabilities.includes('catalog.replace'));
 assert.ok(readOnly.unavailableCapabilities.includes('catalog.replace'));
 assert.equal((await control({})).statements,2);
 await sql("UPDATE cloud_replace_grants SET can_replace=0 WHERE device_id='device-a'");
 await control({resetStatements:true});
 const grantOff=ok(await call('/v1/head'));
 assert.ok(!grantOff.capabilities.includes('catalog.replace'));
 assert.equal((await control({})).statements,2);
 await sql("UPDATE cloud_replace_grants SET can_replace=1 WHERE device_id='device-a'", "UPDATE cloud_devices SET can_write=0 WHERE device_id='device-a'");
 await control({resetStatements:true});
 const nonWriter=ok(await call('/v1/head'));
 assert.ok(!nonWriter.capabilities.includes('catalog.replace'));
 assert.ok(!nonWriter.capabilities.includes('catalog.sync'));
 assert.equal((await control({})).statements,2);
 await sql("UPDATE cloud_devices SET can_write=1 WHERE device_id='device-a'");
 await control({options:{ENABLE_REPLACEMENT_API:'false'},resetStatements:true});
 const disabled=ok(await call('/v1/head'));
 assert.ok(!disabled.capabilities.includes('catalog.replace'));
 assert.equal((await control({})).statements,2,'Disabled replacement must not require any grant query');
});
test('replacement: exact outgoing backup precedes atomic new epoch, receipt and cooldown',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 assert.deepEqual(ok(await call('/v1/head')).deviceState.replacement,{attemptId:p.attempt.attemptId,state:'ACTIVE'});assert.equal(ok(await call('/v1/head',{},tokens.b)).deviceState.replacement,null);
 await finish(p.operation);await sealReplacement(p);
 failure(await call('/v1/uploads/publish',{sequence:p.upload.header.sequence,digest:p.upload.header.digest}),'REPLACEMENT_PROOF_REQUIRED');
 const result=ok(await publishReplacement(p)),h=ok(await call('/v1/head'));
 assert.equal(result.receipt.head.epoch,p.preview.nextEpoch);assert.equal(h.head.epoch,p.preview.nextEpoch);assert.equal(h.head.revision,'1');
 assert.equal(h.head.headCounter,String(BigInt(source.source.head.headCounter)+1n));assert.equal(h.head.fingerprint,await fingerprint(catalog('rename')));
 assert.equal(result.attempt.state,'DONE');assert.equal(h.deviceState.replacement,null);assert.deepEqual(ok(await publishReplacement(p)).receipt,result.receipt);
 const pin=ok(await recovery('pin',{archiveId:p.attempt.archiveId}));
 const download=await mf.dispatchFetch('https://recovery.test/v1/recovery/download',{method:'POST',headers:{Authorization:'Bearer '+tokens.a,'Content-Type':'application/json'},body:JSON.stringify({archiveId:p.attempt.archiveId,pinId:pin.pinId,part:1})});
 assert.deepEqual(Buffer.from(await download.arrayBuffer()),source.bytes);ok(await recovery('release',{archiveId:p.attempt.archiveId,pinId:pin.pinId}));
 assert.equal((await rows('cloud_backup_control'))[0].last_replace_at>0,true);assert.equal((await control({})).calls.create,1);
 const renamedPages=await encodePages(catalog('rename'),source.pages),nextSource={pages:renamedPages,source:{head:h.head,pages:h.pages,bytes:h.bytes,manifest:h.manifest}};
 nextSource.bytes=Buffer.from(cloudArchiveHeader(nextSource.source)+renamedPages.map(cloudArchivePage).join(''));
 await assert.rejects(()=>replacementPreview(nextSource,{name:'base'}),/PRECONDITION_FAILED/);
});
test('replacement: disabled API and absent or stale explicit grant cannot allocate',async()=>{
 const source=await publishCatalog('base');failure(await replace('status'),'REPLACEMENT_API_DISABLED');
 await control({options:{ENABLE_REPLACEMENT_API:'true'}});
 await assert.rejects(()=>replacementPreview(source),/REPLACEMENT_NOT_AUTHORIZED/);
 await enableReplacement();await sql("UPDATE cloud_devices SET auth_generation=auth_generation+1 WHERE device_id='device-a'");
 await assert.rejects(()=>replacementPreview(source),/REPLACEMENT_NOT_AUTHORIZED/);
 assert.equal((await state()).occupiedSlots,0);assert.equal((await control({})).calls.create??0,0);
});
test('replacement: changed head invalidates reservation without evicting any history',async()=>{
 await enableReplacement();for(let i=0;i<3;i++)await backup(zip(16+i));
 const source=await publishCatalog('base'),p=await replacementPreview(source),before=(await state()).archives.map(a=>a.archiveId);
 await publishCatalog('rename',tokens.b,source.pages);
 failure(await replace('reserve',{approvalId:p.preview.approvalId,recoverySequence:'4'}));
 assert.deepEqual((await state()).archives.map(a=>a.archiveId),before);assert.equal((await rows('cloud_replace_attempts')).length,0);
});
test('replacement: unverified outgoing bytes and ordinary reservation cannot authorize publication',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),preview=await replacementPreview(source);
 failure(await recovery('reserve',{approvalId:preview.preview.approvalId,sequence:'1'}),'REPLACEMENT_APPROVAL_REQUIRED');
 const p=await reserveReplacement(preview);await sealReplacement(p);
 failure(await publishReplacement(p),'REPLACEMENT_RECOVERY_NOT_READY');
 assert.deepEqual(ok(await call('/v1/head')).head,source.source.head);
});
test('replacement: revoked grant at final transaction preserves cloud and verified archive',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 await finish(p.operation);await sealReplacement(p);await control({block:'publishReplacement.before'});
 const publishing=publishReplacement(p);await waitBlocked('publishReplacement.before');
 await sql("UPDATE cloud_replace_grants SET can_replace=0 WHERE device_id='device-a'");await control({release:true});failure(await publishing);
 assert.deepEqual(ok(await call('/v1/head')).head,source.source.head);assert.equal((await state()).archives[0].usable,true);
 ok(await settleReplacement(p,false));
});
test('replacement: stale CAS retry reuses its own slot without a second historical eviction',async()=>{
 await enableReplacement();const original=[];for(let i=0;i<3;i++)original.push((await backup(zip(20+i))).archiveId);
 const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));await finish(p.operation);await sealReplacement(p);
 const changed=await publishCatalog('rename',tokens.b,source.pages);failure(await publishReplacement(p),'PRECONDITION_FAILED');
 assert.equal(ok(await settleReplacement(p,true)).attempt.state,'STALE');
 const retry=await reserveReplacement(await replacementPreview(changed,{name:'empty',retryAttemptId:p.attempt.attemptId}));
 assert.equal(retry.attempt.slot,p.attempt.slot);assert.equal(retry.preview.evictArchiveId,p.attempt.archiveId);
 await finish(retry.operation);await sealReplacement(retry);ok(await publishReplacement(retry));
 const retained=(await state()).archives.map(a=>a.archiveId);
 assert.deepEqual(new Set(retained),new Set([original[1],original[2],retry.attempt.archiveId]));assert.equal((await control({})).maxPayloads,3);
 failure(await publishReplacement(p),'REPLACEMENT_RESULT_EXPIRED');
});
test('replacement: retry never deletes its last verified copy and permits a separate spare-slot backup',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 await finish(p.operation);await sealReplacement(p);const changed=await publishCatalog('rename',tokens.b,source.pages);
 failure(await publishReplacement(p));ok(await settleReplacement(p,true));
 await assert.rejects(()=>replacementPreview(changed,{name:'empty',retryAttemptId:p.attempt.attemptId}),/REPLACEMENT_RETRY_REQUIRES_SURVIVOR/);
 assert.equal((await state()).archives[0].archiveId,p.attempt.archiveId);
 const safety=await backup(zip(40));const retry=await reserveReplacement(await replacementPreview(changed,{name:'empty',retryAttemptId:p.attempt.attemptId}));
 await finish(retry.operation);await sealReplacement(retry);ok(await publishReplacement(retry));
 assert.deepEqual(new Set((await state()).archives.map(a=>a.archiveId)),new Set([safety.archiveId,retry.attempt.archiveId]));
});
test('replacement: two device confirmations have one active winner',async()=>{
 await enableReplacement();const source=await publishCatalog('base');
 const a=await replacementPreview(source),b=await replacementPreview(source,{token:tokens.b});
 const results=await Promise.all([replace('reserve',{approvalId:a.preview.approvalId,recoverySequence:'1'}),replace('reserve',{approvalId:b.preview.approvalId,recoverySequence:'1'},tokens.b)]);
 assert.equal(results.filter(r=>r.status===200).length,1);assert.equal((await rows('cloud_replace_attempts')).length,1);assert.equal((await state()).occupiedSlots,1);
});
test('replacement: cancel before reserve fences delayed reservation and incoming stage',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await replacementPreview(source),r={approvalId:p.preview.approvalId,recoverySequence:'1'};
 assert.equal(ok(await replace('abandon',r)).receipt.outcome,'RETIRED');
 failure(await replace('reserve',r));const late=ok(await call('/v1/uploads/start',p.upload.header));assert.equal(late.receipt.outcome,'RETIRED');
 assert.equal((await state()).occupiedSlots,0);assert.equal((await rows('cloud_replace_attempts')).length,0);assert.equal((await control({})).calls.create??0,0);
});
test('replacement: exact reservation replay and lost publication reply never duplicate rotation or epoch',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 const again=ok(await replace('reserve',{approvalId:p.preview.approvalId,recoverySequence:p.attempt.recoverySequence}));
 assert.equal(again.attempt.archiveId,p.attempt.archiveId);await finish(p.operation);await sealReplacement(p);
 await control({fault:'publishReplacement.after'});const first=ok(await publishReplacement(p)),againPublish=ok(await publishReplacement(p));
 assert.deepEqual(first.receipt,againPublish.receipt);assert.equal((await control({})).calls.create,1);
});
test('replacement: cancellation wins against a delayed final publication',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 await finish(p.operation);await sealReplacement(p);await control({block:'publishReplacement.before'});
 const publishing=publishReplacement(p);await waitBlocked('publishReplacement.before');
 assert.equal(ok(await settleReplacement(p,false)).attempt.state,'CANCELED');await control({release:true});
 failure(await publishing,'OPERATION_RETIRED');assert.deepEqual(ok(await call('/v1/head')).head,source.source.head);assert.equal((await state()).archives[0].usable,true);
});
test('replacement: active incoming sequence cannot be substituted or retired under another digest',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 const changedMode={...p.upload.header,mode:'sync'};
 failure(await call('/v1/uploads/start',changedMode));failure(await call('/v1/uploads/retire',{sequence:p.upload.header.sequence,digest:'e'.repeat(64)}));
 await finish(p.operation);await sealReplacement(p);ok(await publishReplacement(p));
});
test('replacement: approval expiry keeps the verified recovery point and requires explicit resolution',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 await finish(p.operation);await sealReplacement(p);
 await sql("UPDATE cloud_replace_attempts SET expires_at=1");failure(await publishReplacement(p),'REPLACEMENT_APPROVAL_EXPIRED');
 assert.equal((await state()).archives[0].usable,true);assert.equal(ok(await settleReplacement(p,true)).attempt.state,'STALE');
});
test('replacement: an unknown multipart creation keeps its attempt and occupied slot visible',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 await control({fault:'create.after'});failure(await recovery('advance',ref(p.operation)),'RECOVERY_CREATION_OUTCOME_UNKNOWN');
 failure(await settleReplacement(p,false),'RECOVERY_OPERATOR_CLEANUP_REQUIRED');
 assert.equal(ok(await replace('status')).attempt.state,'ACTIVE');assert.equal((await state()).occupiedSlots,1);
 failure(await recovery('preview',{manifest:await manifest(zip())}),'REPLACEMENT_PENDING_OR_NOT_AUTHORIZED');
});

test('replacement review: next-sequence ordinary abandonment cannot overwrite its required recovery receipt',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 await finish(p.operation);await sealReplacement(p);
 failure(await recovery('abandon',{approvalId:p.preview.approvalId,sequence:String(BigInt(p.attempt.recoverySequence)+1n)}));
 assert.equal((await rows('cloud_backup_operations'))[0].sequence,Number(p.attempt.recoverySequence));
 ok(await publishReplacement(p));assert.equal(ok(await replace('status')).attempt.state,'DONE');
});
test('replacement review: publication uses database time after a delayed request entry',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 await finish(p.operation);await sealReplacement(p);const before=Date.now();
 ok(await call('/__test/slow-replacement',{attemptId:p.attempt.attemptId}));
 const control=(await rows('cloud_backup_control'))[0];assert.ok(control.last_replace_at>=before-2,JSON.stringify({before,recorded:control.last_replace_at}));
 assert.equal(ok(await replace('status')).cooldownUntil,String(control.last_replace_at+60000));
});

test('replacement review: database clock rejects approval expiry despite an earlier request timestamp',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 await finish(p.operation);await sealReplacement(p);
 await sql({sql:'UPDATE cloud_replace_attempts SET expires_at=?',args:[Date.now()-1000]});
 failure(await call('/__test/slow-replacement',{attemptId:p.attempt.attemptId}));
 assert.deepEqual(ok(await call('/v1/head')).head,source.source.head);assert.equal((await state()).archives[0].usable,true);
});

test('replacement Android review: superseded recovery preview does not prevent cancellation',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await replacementPreview(source);
 const later=ok(await recovery('preview',{manifest:await manifest(zip())}));
 failure(await replace('reserve',{approvalId:p.preview.approvalId,recoverySequence:'1'}),'RECOVERY_APPROVAL_EXPIRED');
 const stopped=ok(await replace('abandon',{approvalId:p.preview.approvalId,recoverySequence:'1'}));
 assert.equal(stopped.receipt.outcome,'RETIRED');assert.equal(stopped.attempt,null);
 assert.deepEqual(ok(await call('/v1/head')).head,source.source.head);assert.equal((await state()).occupiedSlots,0);
 failure(await replace('reserve',{approvalId:p.preview.approvalId,recoverySequence:'1'}));
 const ordinary=ok(await recovery('reserve',{approvalId:later.approvalId,sequence:'1'}));
 assert.equal(ordinary.approvalId,later.approvalId);
});
test('replacement Android review: an unadmitted retry preserves the older stale authority and recovery receipt',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
 await finish(p.operation);await sealReplacement(p);const changed=await publishCatalog('rename',tokens.b,source.pages);
 failure(await publishReplacement(p));ok(await settleReplacement(p,true));await backup(zip(40),{token:tokens.b});
 const retry=await replacementPreview(changed,{name:'empty',retryAttemptId:p.attempt.attemptId});
 const before=await rows('cloud_backup_operations'),archives=(await state()).archives.map(a=>a.archiveId);
 const stop=ok(await replace('abandon',{approvalId:retry.preview.approvalId,recoverySequence:'2'}));
 assert.equal(stop.receipt.outcome,'RETIRED');assert.equal(ok(await replace('status')).attempt.attemptId,p.attempt.attemptId);
 assert.equal(ok(await replace('status')).attempt.state,'STALE');assert.deepEqual(await rows('cloud_backup_operations'),before);
 assert.deepEqual((await state()).archives.map(a=>a.archiveId),archives);
 failure(await replace('reserve',{approvalId:retry.preview.approvalId,recoverySequence:'2'}));
 ok(await settleReplacement(p,false));assert.equal(ok(await call('/v1/head')).deviceState.replacement,null);
});
test('replacement Android review: retiring an unadmitted intent fences a delayed reservation',async()=>{
 await enableReplacement();const source=await publishCatalog('base'),p=await replacementPreview(source),request={approvalId:p.preview.approvalId,recoverySequence:'1'};
 await control({block:'reserveReplacement.before'});const late=replace('reserve',request);await waitBlocked('reserveReplacement.before');
 assert.equal(ok(await replace('abandon',request)).receipt.outcome,'RETIRED');await control({release:true});failure(await late);
 assert.equal((await state()).occupiedSlots,0);assert.equal(ok(await replace('status')).attempt,null);assert.equal((await control({})).calls.create??0,0);
});

for (const phase of ['RESERVED','UPLOADING','READY','ROTATING']) for(const route of ['abandon','settle']) {
 test('Astra 005: bounded replacement '+route+' progresses truthfully from '+phase,async()=>{
  await enableReplacement();
  if(phase==='ROTATING')for(let i=0;i<3;i++)await backup(zip(30+i));
  const source=await publishCatalog('base'),p=await reserveReplacement(await replacementPreview(source));
  if(phase==='UPLOADING')assert.equal(ok(await recovery('advance',ref(p.operation))).state,'UPLOADING');
  if(phase==='READY')await finish(p.operation);
  const protectedArchives=(await state()).archives.filter(a=>a.state==='READY'&&a.archiveId!==p.operation.archiveId);
  const cancel=()=>route==='abandon'?replace('abandon',{approvalId:p.preview.approvalId,recoverySequence:p.attempt.recoverySequence}):replace('settle',{attemptId:p.attempt.attemptId,keepForRetry:false});
  let response,passes=0;
  do{
   await control({resetStatements:true});
   response=ok(await cancel());
   const count=(await control({})).statements;
   assert.ok(count+5<=32,JSON.stringify({phase,count}));
   if(response.cleanupPending){assert.equal(response.receipt.outcome,'RETIRED');assert.ok(++passes<4);}
  }while(response.cleanupPending);
  assert.equal(response.attempt.state,'CANCELED');
  const again=ok(await cancel());
  assert.equal(again.attempt.state,'CANCELED');
  assert.deepEqual(ok(await call('/v1/head')).head,source.source.head);
  const after=(await state()).archives;
  // Rotation is explicitly approved to evict only the oldest; every other copy survives.
  for(const a of protectedArchives.filter(a=>phase!=='ROTATING'||a.archiveId!==p.preview.evictArchiveId)) {
   assert.ok(after.some(b=>b.archiveId===a.archiveId&&b.digest===a.digest));
  }
  if(phase==='ROTATING')assert.equal(after.length,2);
  if(phase==='READY')assert.ok(after.some(a=>a.archiveId===p.operation.archiveId&&a.usable));
  const controlDb=await mf.getD1Database('CONTROL');
  assert.equal((await controlDb.prepare('SELECT COUNT(*) n FROM cloud_fence_tickets').first()).n,0);
  const budget=await controlDb.prepare('SELECT state,held_reads,held_writes FROM cloud_budget').first();
  assert.deepEqual(budget,{state:'ACTIVE',held_reads:0,held_writes:0});
 });
}

for(const route of ['abort','abandon'])test('Claude 007: ordinary '+route+' from ROTATING reports bounded cleanup and preserves both survivors',async()=>{
 for(let i=0;i<3;i++)await backup(zip(40+i));
 const before=(await state()).archives;
 const op=await reserve(zip(90));assert.equal(op.state,'ROTATING');
 const body=route==='abort'?ref(op):{approvalId:op.approvalId,sequence:op.sequence};
 let result,passes=0;
 do{
  await control({resetStatements:true});
  result=ok(await call('/v1/recovery/'+route,body));
  assert.ok((await control({})).statements+5<=32);
  assert.ok(++passes<=4);
 }while(result.cleanupPending);
 assert.equal(result.state,'CANCELED');
 const survivors=(await state()).archives;
 assert.equal(survivors.length,2);
 for(const a of before.filter(a=>a.archiveId!==op.preview.evictArchiveId))assert.ok(survivors.some(b=>b.archiveId===a.archiveId&&b.digest===a.digest&&b.usable));
 const controlDb=await mf.getD1Database('CONTROL');
 assert.equal((await controlDb.prepare('SELECT COUNT(*) n FROM cloud_fence_tickets').first()).n,0);
});

test('Claude 006: a provider refusing repeated multipart abort never sees a duplicate successful abort during bounded cancellation',async()=>{
 await enableReplacement();await publishCatalog('base');
 const p=await reserveReplacement(await replacementPreview(await publishCatalog('reorder')));
 assert.equal(ok(await recovery('advance',ref(p.operation))).state,'UPLOADING');
 await control({options:{ENABLE_REPLACEMENT_API:'true',rejectRepeatedAbort:true}});
 let result,passes=0;
 do{
  await control({resetStatements:true});
  result=ok(await replace('abandon',{approvalId:p.preview.approvalId,recoverySequence:p.attempt.recoverySequence}));
  assert.ok((await control({})).statements+5<=32);
  assert.ok(++passes<=4);
 }while(result.cleanupPending);
 assert.equal(result.attempt.state,'CANCELED');
 assert.equal((await control({})).calls.abort,1);
 assert.equal((await state()).archives.length,0);
 const controlDb=await mf.getD1Database('CONTROL');
 assert.equal((await controlDb.prepare('SELECT COUNT(*) n FROM cloud_fence_tickets').first()).n,0);
 assert.deepEqual(await controlDb.prepare('SELECT state,held_reads,held_writes FROM cloud_budget').first(),{state:'ACTIVE',held_reads:0,held_writes:0});
});
