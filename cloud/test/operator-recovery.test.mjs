// SPDX-License-Identifier: GPL-3.0-or-later
import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {inventory,verifyArchive,makePlan,verifyPlan,rebuildBatch,seal,refuseLiveApply,snapshot,authorityBlockers} from '../operator/recovery.mjs';
import {s3Store,cloudflareClient} from '../operator/adapters.mjs';
import {bytesDigest,recoveryDigest,cloudArchiveHeader,cloudArchivePage} from '../src/recovery-format.ts';
import {encodePages,pageManifest,byteLength,sha256} from '../src/pages.ts';
import {fingerprint} from '../src/catalog.ts';
import {authenticateToken} from '../src/service.ts';

const scope={account:'a'.repeat(32),worker:'newpipe-test',database:'11111111-1111-4111-8111-111111111111',
  bucket:'newpipe-test',library:'library-a',generation:'22222222-2222-4222-8222-222222222222'};
const authority=()=>({deployment:'deployment-new',version:'version-new',database:scope.database,bucket:scope.bucket,
  vars:{ENABLE_CATALOG_API:'false',ENABLE_RECOVERY_API:'false',ENABLE_REPLACEMENT_API:'false',LIBRARY_ID:scope.library,SERVICE_GENERATION:scope.generation}});
let mf,persistence,db,r2,store,objects,uploads;
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
const catalog=structuredClone(fixture.catalogs.find(c=>c.name==='empty').catalog);
function zip(){
  const local=Buffer.alloc(30),central=Buffer.alloc(46),end=Buffer.alloc(22);
  local.writeUInt32LE(0x04034b50);central.writeUInt32LE(0x02014b50);
  end.writeUInt32LE(0x06054b50);end.writeUInt16LE(1,8);end.writeUInt16LE(1,10);
  end.writeUInt32LE(central.length,12);end.writeUInt32LE(local.length,16);
  return Buffer.concat([local,central,end]);
}
async function seed(slot,cloud=false){
  const archive='00000000-0000-4000-8000-'+String(slot+1).padStart(12,'0');
  const key='recovery/library-a/'+slot+'/'+archive;
  let source=null,data=zip();
  if(cloud){
    const pages=await encodePages(catalog);
    source={head:{serviceGeneration:'old-service',library:scope.library,epoch:'epoch-a',revision:'1',headCounter:'1',fingerprint:await fingerprint(catalog)},
      pages:pages.length,bytes:pages.reduce((n,p)=>n+byteLength(p.body),0),manifest:await pageManifest(pages)};
    data=Buffer.from(cloudArchiveHeader(source)+pages.map(cloudArchivePage).join(''));
  }
  const manifest={version:1,kind:cloud?'cloud-catalog':'device-zip',bytes:data.length,source,
    native:cloud?null:{database:9002,tags:5,uncompressedBytes:8192},
    producerValidation:cloud?'catalog-pages-v1':'native-restore-v1',
    parts:[{bytes:data.length,sha256:await bytesDigest(data)}]};
  const metadata=JSON.stringify({format:1,archive,slot,kind:manifest.kind,bytes:data.length,digest:await recoveryDigest(manifest),
    source,native:manifest.native,library:scope.library,service:'old-service',device:'device-a',created:String(1000+slot),
    checksum:'sha256-chunk-manifest-v1',producerValidation:manifest.producerValidation});
  await r2.put(key,data,{customMetadata:{recovery:metadata}});
  objects.push({key,data,metadata});
}
const adapters=()=>({db:{query:async(sql,params)=>(await db.prepare(sql).bind(...params).all()).results},store,authority:async()=>authority()});
const runBatch=plan=>db.batch(rebuildBatch(plan).map(s=>db.prepare(s.sql).bind(...s.params)));
before(async()=>{
  persistence=await mkdtemp(join(tmpdir(),'newpipe-operator-'));
  mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules:true,script:'export default {fetch(){return new Response("operator local tests")}}',
    compatibilityDate:'2026-08-25',d1Databases:{DB:'operator-local'},r2Buckets:{RECOVERY:'operator-local'},resourcePersistencePath:persistence}));
  await mf.ready;db=await mf.getD1Database('DB');r2=await mf.getR2Bucket('RECOVERY');
  for(const file of ['0001_catalog_service.sql','0002_recovery.sql','0003_replacement.sql']){
    const parts=(await readFile(new URL('../migrations/'+file,import.meta.url),'utf8')).replace(/^--.*$/gm,'').split(';').map(x=>x.trim()).filter(Boolean);
    await db.batch(parts.map(s=>db.prepare(s)));
  }
});
after(async()=>{await mf?.dispose();if(persistence)await rm(persistence,{recursive:true,force:true});});
beforeEach(async()=>{
  for(const o of (await r2.list()).objects)await r2.delete(o.key);
  for(const t of ['cloud_replace_attempts','cloud_replace_approvals','cloud_replace_grants','cloud_backup_slots','cloud_backup_operations','cloud_backup_approvals','cloud_backup_control','cloud_upload_changes','cloud_upload_chunks','cloud_uploads','cloud_catalog_pages','cloud_receipts','cloud_catalog_info','cloud_devices','cloud_head','cloud_commit_assertion'])await db.prepare('DELETE FROM '+t).run();
  await db.batch([
    db.prepare("INSERT INTO cloud_head VALUES('library-a','epoch-a',7,9,?)").bind('a'.repeat(64)),
    db.prepare("INSERT INTO cloud_head VALUES('unrelated','other-epoch',2,3,?)").bind('b'.repeat(64)),
    db.prepare("INSERT INTO cloud_catalog_info VALUES('library-a',1,1,5,?)").bind('c'.repeat(64)),
    db.prepare("INSERT INTO cloud_catalog_pages VALUES('library-a','page','accepted-catalog')"),
    db.prepare("INSERT INTO cloud_devices VALUES('library-a','device-a',?,'old-service',1,0,1,6)").bind(await sha256('1'.repeat(64))),
    db.prepare("INSERT INTO cloud_devices VALUES('library-a','device-revoked',?,'old-service',1,1,1,6)").bind(await sha256('2'.repeat(64))),
    db.prepare("INSERT INTO cloud_devices VALUES('unrelated','other-device',?,'other-service',1,0,1,0)").bind(await sha256('3'.repeat(64))),
    db.prepare("INSERT INTO cloud_replace_grants VALUES('library-a','device-a','old-service',1,1)")
  ]);
  objects=[];uploads=[];
  store={
    list:async(kind,prefix,cursor)=>{
      if(kind==='uploads')return {items:uploads.filter(x=>x.key.startsWith(prefix)),truncated:false,next:null};
      const all=(await r2.list({prefix})).objects,offset=cursor??0;
      return {items:all.slice(offset,offset+1).map(o=>({key:o.key,size:o.size,etag:o.etag,modified:o.uploaded.toISOString()})),
        truncated:offset+1<all.length,next:offset+1<all.length?offset+1:null};
    },
    head:async key=>{const o=await r2.head(key);return o?{key,size:o.size,etag:o.etag,modified:o.uploaded.toISOString(),metadata:o.customMetadata.recovery}:null;},
    read:async(key,offset,length,etag)=>{const o=await r2.get(key,{range:{offset,length},onlyIf:{etagMatches:etag}});return new Uint8Array(await o.arrayBuffer());}
  };
  await seed(0);await seed(1);await seed(2,true);
});
test('reconstructs all three actual R2 archives while a missing D1 index remains read-only',async()=>{
  const before=await snapshot(adapters().db,scope.library),plan=await makePlan(adapters(),scope);
  assert.equal(plan.body.archives.length,3);assert.deepEqual(plan.body.blockers,['STALE_WORKER_FENCE_UNAVAILABLE']);
  for(const a of plan.body.archives)assert.equal(a.digest,await recoveryDigest(a.manifest));
  assert.deepEqual(await snapshot(adapters().db,scope.library),before);
  await verifyPlan(adapters(),plan);
});
test('real local D1 rebuild is atomic, preserves catalog/unrelated state and revokes resurrected devices',async()=>{
  // Local runtime is exclusively owned by this test: no admitted public requests exist.
  await db.prepare("UPDATE cloud_devices SET revoked=0 WHERE device_id='device-revoked'").run();
  const other=await snapshot(adapters().db,'unrelated'),plan=await makePlan(adapters(),scope);
  const before=await Promise.all(objects.map(o=>r2.get(o.key).then(r=>r.arrayBuffer())));
  await runBatch(plan);
  assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_backup_slots').first()).n,3);
  assert.equal((await db.prepare('SELECT service_generation FROM cloud_backup_control').first()).service_generation,scope.generation);
  assert.deepEqual(await snapshot(adapters().db,'unrelated'),other);
  assert.equal((await db.prepare("SELECT body FROM cloud_catalog_pages WHERE library_id='library-a'").first()).body,'accepted-catalog');
  assert.equal((await db.prepare("SELECT SUM(revoked) n FROM cloud_devices WHERE library_id='library-a'").first()).n,2);
  assert.equal((await db.prepare('SELECT can_replace FROM cloud_replace_grants').first()).can_replace,0);
  await assert.rejects(authenticateToken(db,'device-a.'+'1'.repeat(64),scope.library,scope.generation));
  await assert.rejects(authenticateToken(db,'device-revoked.'+'2'.repeat(64),scope.library,scope.generation));
  const after=await Promise.all(objects.map(o=>r2.get(o.key).then(r=>r.arrayBuffer())));
  assert.deepEqual(after,before);
  // A lost response/replay never produces a fourth archive or applies twice.
  await assert.rejects(runBatch(plan));assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_backup_slots').first()).n,3);
});
test('optimistic concurrency failure rolls back every mutation and competing batches have one winner',async()=>{
  const plan=await makePlan(adapters(),scope);
  await db.prepare("UPDATE cloud_head SET revision=8 WHERE library_id='library-a'").run();
  const before=await snapshot(adapters().db,scope.library);
  await assert.rejects(runBatch(plan));assert.deepEqual(await snapshot(adapters().db,scope.library),before);
  const current=await makePlan(adapters(),scope);
  const outcomes=await Promise.allSettled([runBatch(current),runBatch(current)]);
  assert.equal(outcomes.filter(x=>x.status==='fulfilled').length,1);
});
test('rewound index is reconstructed without modifying physical payloads or completed history',async()=>{
  await db.prepare("INSERT INTO cloud_backup_control VALUES('library-a','old-service',4,20,0)").run();
  await db.prepare("INSERT INTO cloud_backup_slots(library_id,slot,archive_id,object_key,owner_device,source_service,manifest,digest,ordinal,created_at,state) VALUES('library-a',0,'missing','recovery/library-a/0/missing','device-a','old-service','{}','old',19,1,'QUARANTINED')").run();
  const plan=await makePlan(adapters(),scope);await runBatch(plan);
  assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_backup_slots WHERE state=\'READY\'').first()).n,3);
  assert.deepEqual((await r2.list()).objects.map(o=>o.key).sort(),objects.map(o=>o.key).sort());
});
test('unresolved real multipart upload blocks the compiled rebuild',async()=>{
  const key='recovery/library-a/0/99999999-9999-4999-8999-999999999999';
  const u=await r2.createMultipartUpload(key);uploads.push({key,uploadId:u.uploadId,initiated:new Date().toISOString()});
  const plan=await makePlan(adapters(),scope);
  assert.ok(plan.body.blockers.includes('INCOMPLETE_MULTIPART_UPLOADS'));
  assert.throws(()=>rebuildBatch(plan),/PLAN_BLOCKED/);await u.abort();
});
test('corrupt bytes, malformed metadata, changed identity and duplicate slot all refuse',async()=>{
  const original=store.read;
  store.read=async(...args)=>{const b=await original(...args);b[10]^=1;return b;};
  await assert.rejects(makePlan(adapters(),scope),/ARCHIVE_DIGEST_MISMATCH/);store.read=original;
  await r2.put(objects[0].key,objects[0].data,{customMetadata:{recovery:'{}'}});
  await assert.rejects(makePlan(adapters(),scope),/INVALID_RECOVERY_METADATA/);
  const physical=await inventory(store,scope);
  const listed=physical.objects.find(x=>x.key===objects[1].key);
  await assert.rejects(verifyArchive(store,scope,{...listed,size:1}),/OBJECT_CHANGED_DURING_INVENTORY/);
  await r2.delete(objects[0].key);
  await r2.put('recovery/library-a/1/99999999-9999-4999-8999-999999999999',objects[1].data,{customMetadata:{recovery:objects[1].metadata}});
  await assert.rejects(inventory(store,scope),/DUPLICATE_PHYSICAL_SLOT/);
});
test('S3 list milliseconds and HTTP head seconds represent the same object',async()=>{
  const physical=await inventory(store,scope),listed=physical.objects[0];
  const original=store.head;
  store.head=async key=>{const o=await original(key);return {...o,modified:new Date(Math.floor(Date.parse(o.modified)/1000)*1000).toISOString()};};
  const archive=await verifyArchive(store,scope,listed);
  assert.equal(archive.listedModified,listed.modified);
  assert.equal(archive.digest,await recoveryDigest(archive.manifest));
});
test('seal, stale state, gates, generation and bindings fail closed',async()=>{
  const a=adapters(),plan=await makePlan(a,scope);
  const bad=structuredClone(plan);bad.body.archives[0].slot=2;
  await assert.rejects(verifyPlan(a,bad),/PLAN_SEAL_MISMATCH/);
  await db.prepare("UPDATE cloud_head SET head_counter=10 WHERE library_id='library-a'").run();
  await assert.rejects(verifyPlan(a,plan),/PLAN_STALE/);
  a.authority=async()=>({...authority(),bucket:'wrong'});
  assert.ok((await makePlan(a,scope)).body.blockers.includes('WORKER_BINDING_MISMATCH'));
  a.authority=async()=>({...authority(),vars:{...authority().vars,ENABLE_CATALOG_API:'true'}});
  assert.ok((await makePlan(a,scope)).body.blockers.includes('WORKER_SETTING_ENABLE_CATALOG_API'));
  await db.prepare("UPDATE cloud_devices SET service_generation=? WHERE library_id='library-a'").bind(scope.generation).run();
  assert.ok((await makePlan(adapters(),scope)).body.blockers.includes('FRESH_EXTERNAL_GENERATION_REQUIRED'));
  assert.throws(refuseLiveApply,/STALE_WORKER_FENCE_UNAVAILABLE/);
});
test('S3 adapter paginates completed objects and multipart uploads independently and sends exact range precondition',async()=>{
  const calls=[];
  const client={send:async command=>{
    calls.push({name:command.constructor.name,input:command.input});
    if(command.constructor.name==='ListObjectsV2Command')return command.input.ContinuationToken
      ?{Contents:[],IsTruncated:false}:{Contents:[],IsTruncated:true,NextContinuationToken:'object-next'};
    if(command.constructor.name==='ListMultipartUploadsCommand'){
      return command.input.KeyMarker?{Uploads:[],IsTruncated:false}:{Uploads:[],IsTruncated:true,NextKeyMarker:'key-marker',NextUploadIdMarker:'upload-marker'};
    }
    if(command.constructor.name==='GetObjectCommand')return {ContentLength:3,ContentRange:'bytes 2-4/8',ETag:'"etag"',
      Body:(async function*(){yield Uint8Array.of(1,2,3);})()};
  }};
  const a=s3Store(client,scope.bucket);await inventory(a,scope);
  assert.equal(calls.filter(c=>c.name==='ListObjectsV2Command').length,2);
  assert.equal(calls.filter(c=>c.name==='ListObjectsV2Command')[1].input.ContinuationToken,'object-next');
  assert.equal(calls.filter(c=>c.name==='ListMultipartUploadsCommand').length,2);
  assert.equal(calls.at(-1).input.KeyMarker,'key-marker');assert.equal(calls.at(-1).input.UploadIdMarker,'upload-marker');
  assert.deepEqual(await a.read('key',2,3,'etag'),Uint8Array.of(1,2,3));
  assert.equal(calls.at(-1).input.IfMatch,'"etag"');assert.equal(calls.at(-1).input.Range,'bytes=2-4');
});
test('partial, repeated, out-of-prefix and overflowing inventories refuse',async()=>{
  for(const list of [
    async()=>({items:[],truncated:true,next:null}),
    async()=>({items:[],truncated:true,next:'loop'}),
    async()=>({items:[{key:'recovery/other/0/x'}],truncated:false,next:null}),
    async()=>({items:Array.from({length:4},(_,i)=>({key:'recovery/library-a/'+(i%3)+'/00000000-0000-4000-8000-'+String(i).padStart(12,'0')})),truncated:false,next:null})
  ])await assert.rejects(inventory({list},scope));
});
test('Cloudflare adapter verifies deployed version, current settings and refuses split deployments',async()=>{
  const bindings=[{name:'DB',type:'d1',id:scope.database},{name:'RECOVERY',type:'r2_bucket',bucket_name:scope.bucket},
    ...Object.entries(authority().vars).map(([name,text])=>({name,type:'plain_text',text}))];
  let split=false,changed=false;
  const fetcher=async(url,opts)=>{
    assert.equal(opts.redirect,'error');
    let result=url.endsWith('/deployments')?{deployments:[{id:'d',versions:split?[]:[{version_id:'v',percentage:100}]}]}:
      url.endsWith('/versions/v')?{id:'v',resources:{bindings}}:{bindings:changed?bindings.filter(b=>b.name!=='DB'):bindings};
    return new Response(JSON.stringify({success:true,result}));
  };
  const a=cloudflareClient(scope,'private-test-token',fetcher);
  assert.deepEqual(authorityBlockers(await a.authority(),scope),[]);
  split=true;await assert.rejects(a.authority(),/SPLIT_OR_MISSING_DEPLOYMENT/);
  split=false;changed=true;await assert.rejects(a.authority(),/WORKER_BINDING_MISSING_OR_DUPLICATE/);
  await assert.rejects(a.db.query('DELETE FROM cloud_head',[]),/READ_ONLY_ADAPTER/);
});
