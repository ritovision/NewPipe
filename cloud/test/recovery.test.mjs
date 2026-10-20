// SPDX-License-Identifier: GPL-3.0-or-later
import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
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
const recovery=(path,data={},token=tokens.a)=>call('/v1/recovery/'+path,data,token);
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
 mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules,compatibilityDate:'2026-08-25',d1Databases:{DB:'recovery-local'},r2Buckets:{RECOVERY:'recovery-local'},resourcePersistencePath:persistence}));await mf.ready;
 for(const file of ['0001_catalog_service.sql','0002_recovery.sql']){
  const statements=(await readFile(new URL('../migrations/'+file,import.meta.url),'utf8')).replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);await sql(...statements);
 }
});
after(async()=>{if(mf)await mf.dispose();if(persistence)await rm(persistence,{recursive:true,force:true});});
beforeEach(async()=>{
 ok(await call('/__test/reset'));
 await sql(...['cloud_backup_slots','cloud_backup_operations','cloud_backup_approvals','cloud_backup_control','cloud_upload_changes','cloud_upload_chunks','cloud_uploads','cloud_catalog_pages','cloud_receipts','cloud_catalog_info','cloud_devices','cloud_head','cloud_commit_assertion'].map(t=>'DELETE FROM '+t));
 await sql({sql:'INSERT INTO cloud_head VALUES(?,?,0,0,?)',args:['library-a','epoch-a',EMPTY_FP]},
  {sql:'INSERT INTO cloud_catalog_info VALUES(?,0,0,0,?)',args:['library-a',EMPTY_MANIFEST]},
  ...await Promise.all(Object.entries(tokens).map(async([key,token])=>({sql:'INSERT INTO cloud_devices VALUES(?,?,?,?,1,0,?,0)',args:['library-a',token.split('.')[0],await sha256(token.split('.')[1]),'service-a',Number(key!=='r')]}))),
  {sql:'INSERT INTO cloud_backup_control(library_id,service_generation) VALUES(?,?)',args:['library-a','service-a']});
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
 await control({options:{SERVICE_GENERATION:'service-new'}});assert.equal((await recovery('status',{},tokens.b)).status,401);
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
