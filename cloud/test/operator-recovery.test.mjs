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
import operatorWorker,{applyPlan,existing} from '../operator/worker.mjs';
import {makeReopenPlan,executeReopen,latestAction} from '../operator/reopen.mjs';
import {admit} from '../src/fence.ts';
import {closeFence} from '../src/maintenance.ts';
import {fenceState,claimAction,finishAction} from '../src/maintenance.ts';
import {authenticateToken} from '../src/service.ts';

const scope={account:'a'.repeat(32),worker:'newpipe-test',database:'11111111-1111-4111-8111-111111111111',
  bucket:'newpipe-test',library:'library-a',generation:'22222222-2222-4222-8222-222222222222'};
const authority=()=>({deployment:'deployment-new',version:'version-new',database:scope.database,bucket:scope.bucket,
  vars:{ENABLE_CATALOG_API:'false',ENABLE_RECOVERY_API:'false',ENABLE_REPLACEMENT_API:'false',LIBRARY_ID:scope.library,SERVICE_GENERATION:scope.generation}});
let mf,persistence,db,control,r2,store,objects,uploads;
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
    compatibilityDate:'2026-08-25',d1Databases:{DB:'operator-local',CONTROL:'operator-control'},r2Buckets:{RECOVERY:'operator-local'},resourcePersistencePath:persistence}));
  await mf.ready;control=await mf.getD1Database('CONTROL');db=await mf.getD1Database('DB');r2=await mf.getR2Bucket('RECOVERY');
  for(const file of ['0001_catalog_service.sql','0002_recovery.sql','0003_replacement.sql','0004_operator_receipts.sql','0005_reopen_receipts.sql','0006_housekeeping.sql']){
    const parts=(await readFile(new URL('../migrations/'+file,import.meta.url),'utf8')).replace(/^--.*$/gm,'').split(';').map(x=>x.trim()).filter(Boolean);
    await db.batch(parts.map(s=>db.prepare(s)));
  }
 const schema=(await Promise.all(['0001_fence.sql','0002_reopen.sql'].map(f=>readFile(new URL('../control-migrations/'+f,import.meta.url),'utf8')))).join('\n').replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
 await control.batch(schema.map(s=>control.prepare(s)));

});
after(async()=>{await mf?.dispose();if(persistence)await rm(persistence,{recursive:true,force:true});});
beforeEach(async()=>{
 await db.prepare('DELETE FROM cloud_receipt_retention').run();
 await db.prepare('DELETE FROM cloud_housekeeping_cursor').run();
 for(const t of ['cloud_fence_actions','cloud_fence_tickets','cloud_fence','cloud_fence_assertion'])await control.prepare('DELETE FROM '+t).run();
 await db.prepare('DELETE FROM cloud_reopen_receipts').run();
 await db.prepare('DELETE FROM cloud_operator_receipts').run();
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
    assert.equal(opts.redirect,'manual');
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
const fencedScope={...scope,fence:{control:'33333333-3333-4333-8333-333333333333',
 resource:'44444444-4444-4444-8444-444444444444',operator:'newpipe-test-operator'}};
const maintenance='55555555-5555-4555-8555-555555555555';
const fencedAuthority=()=>({...authority(),control:fencedScope.fence.control,
 vars:{...authority().vars,FENCE_RESOURCE:fencedScope.fence.resource},
 operator:{database:scope.database,bucket:scope.bucket,control:fencedScope.fence.control,resource:fencedScope.fence.resource}});
async function fencedPlan(){
 await control.prepare("INSERT INTO cloud_fence(resource_id,database_id,bucket_name,library_id,service_generation,provenance,state,maintenance_id,next_generation,created_at,revision) VALUES(?,?,?,?,?,'fresh-resources-v1','HELD',?,?,0,2)")
  .bind(fencedScope.fence.resource,scope.database,scope.bucket,scope.library,'old-service',maintenance,scope.generation).run();
 return makePlan({...adapters(),authority:async()=>fencedAuthority(),fence:()=>fenceState(control,fencedScope.fence.resource)},fencedScope);
}
const apply=(p,database=db)=>applyPlan({DB:database,CONTROL:control,RECOVERY:r2},p,fencedAuthority(),store);
test('fenced apply independently verifies archives and records a transactional durable receipt',async()=>{
 const p=await fencedPlan();assert.deepEqual(p.body.blockers,[]);
 const original=await Promise.all(objects.map(o=>r2.get(o.key).then(r=>r.arrayBuffer())));
 const result=await apply(p);assert.equal(result.state,'DONE');assert.equal(result.result.digest,p.digest);
 assert.deepEqual(await apply(p),result);
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_operator_receipts').first()).n,1);
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_backup_slots').first()).n,3);
 assert.equal((await fenceState(control,fencedScope.fence.resource)).running,0);
 assert.deepEqual(await Promise.all(objects.map(o=>r2.get(o.key).then(r=>r.arrayBuffer()))),original);
});
test('lost rebuild reply resolves from its receipt without submitting another batch',async()=>{
 const p=await fencedPlan();let batches=0;
 const ambiguous={prepare:db.prepare.bind(db),batch:async statements=>{batches++;await db.batch(statements);throw new Error('lost reply');}};
 await assert.rejects(apply(p,ambiguous));
 assert.equal((await fenceState(control,fencedScope.fence.resource)).running,1);
 const resolved=await apply(p,ambiguous);assert.equal(resolved.state,'DONE');assert.equal(batches,1);
 assert.equal((await fenceState(control,fencedScope.fence.resource)).running,0);
});
test('unknown rebuild before response retains exclusivity and cannot be resubmitted or rewound',async()=>{
 const p=await fencedPlan();let batches=0;
 const ambiguous={prepare:db.prepare.bind(db),batch:async()=>{batches++;throw new Error('request outcome unknown');}};
 await assert.rejects(apply(p,ambiguous));
 assert.equal((await apply(p,ambiguous)).state,'RUNNING');assert.equal(batches,1);
 const f=await fenceState(control,fencedScope.fence.resource);
 await assert.rejects(claimAction(control,f,'c'.repeat(64),'restore'));
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_operator_receipts').first()).n,0);
});
test('resealed injected SQL, missing multipart blockers, and changed bytes fail before a live claim',async()=>{
 const p=await fencedPlan(),forged=structuredClone(p);
 forged.body.snapshot[0].sql="SELECT 'malicious' AS state";forged.digest=seal(forged.body);
 await assert.rejects(apply(forged),/PLAN_STALE/);
 const upload=await r2.createMultipartUpload('recovery/library-a/0/00000000-0000-4000-8000-000000000099');
 uploads.push({key:upload.key,uploadId:upload.uploadId,initiated:new Date(0).toISOString()});
 await assert.rejects(apply(p),/PLAN_STALE/);await upload.abort();uploads=[];
 await r2.put(objects[0].key,new Uint8Array([0,1,2]),{customMetadata:{recovery:objects[0].metadata}});
 await assert.rejects(apply(p));
 assert.equal((await control.prepare('SELECT COUNT(*) n FROM cloud_fence_actions').first()).n,0);
});
test('two simultaneous apply calls have one batch winner',async()=>{
 const p=await fencedPlan();
 const attempts=await Promise.allSettled([apply(p),apply(p)]);
 assert.ok(attempts.some(r=>r.status==='fulfilled'&&r.value.state==='DONE'));
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_operator_receipts').first()).n,1);
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_backup_slots').first()).n,3);
});

test('a completed rebuild receipt becomes superseded after a later restore action',async()=>{
 const p=await fencedPlan();await apply(p);
 const f=await fenceState(control,fencedScope.fence.resource),id='d'.repeat(64);
 await claimAction(control,f,id,'restore');
 await db.prepare('DELETE FROM cloud_reopen_receipts').run();
 await db.prepare('DELETE FROM cloud_operator_receipts').run();
 await finishAction(control,maintenance,id,{restored:true});
 assert.equal((await apply(p)).state,'SUPERSEDED');
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_operator_receipts').first()).n,0);
});
async function operatorHttp(path,input,envOverrides={},providerRestore){
 const secret='a'.repeat(64),origin='https://newpipe-test-operator.qa.workers.dev';
 const publicBindings=[{name:'DB',type:'d1',id:scope.database},{name:'CONTROL',type:'d1',id:fencedScope.fence.control},
  {name:'RECOVERY',type:'r2_bucket',bucket_name:scope.bucket},
  ...Object.entries(fencedAuthority().vars).map(([name,text])=>({name,type:'plain_text',text}))];
 const operatorBindings=[...publicBindings.filter(b=>['DB','CONTROL','RECOVERY','FENCE_RESOURCE'].includes(b.name)),
  {name:'SCOPE',type:'plain_text',text:JSON.stringify(fencedScope)},{name:'PUBLIC_ORIGIN',type:'plain_text',text:origin}];
 const original=globalThis.fetch;
 globalThis.fetch=async url=>{
  const p=new URL(url).pathname,isOperator=p.includes('/'+fencedScope.fence.operator+'/'),bindings=isOperator?operatorBindings:publicBindings;
  let result;
  if(p.endsWith('/deployments'))result={deployments:[{id:'deployment',versions:[{version_id:'version',percentage:100}]}]};
  else if(p.includes('/versions/'))result={id:'version',resources:{bindings}};
  else if(p.endsWith('/settings'))result={bindings};
  else if(p.endsWith('/workers/subdomain'))result={subdomain:'qa'};
  else if(p.endsWith('/time_travel/restore'))return providerRestore();
  else throw new Error('unexpected provider path');
  return Response.json({success:true,result});
 };
 try{
  return await operatorWorker.fetch(new Request(origin+path,{method:'POST',headers:{Authorization:'Bearer '+secret,'Content-Type':'application/json'},
   body:JSON.stringify({scope:fencedScope,cloudflareToken:'synthetic-token',maintenance,revision:'2',...input})}),
   {DB:db,CONTROL:control,RECOVERY:r2,OPERATOR_TOKEN:secret,PUBLIC_ORIGIN:origin,SCOPE:JSON.stringify(fencedScope),FENCE_RESOURCE:fencedScope.fence.resource,...envOverrides});
 }finally{globalThis.fetch=original;}
}
test('operator HTTP abort fences an exact multipart upload, preserves completed bytes and is idempotent',async()=>{
 await fencedPlan();const key='recovery/library-a/0/00000000-0000-4000-8000-000000000088',upload=await r2.createMultipartUpload(key);
 const before=(await r2.list()).objects.map(o=>[o.key,o.etag]);
 const first=await operatorHttp('/recovery/abort',{key,uploadId:upload.uploadId});assert.equal(first.status,200);
 const done=await first.json();assert.equal(done.state,'DONE');
 const again=await operatorHttp('/recovery/abort',{key,uploadId:upload.uploadId});assert.deepEqual(await again.json(),done);
 assert.deepEqual((await r2.list()).objects.map(o=>[o.key,o.etag]),before);
});
test('operator HTTP rejects unauthenticated maintenance without touching control state',async()=>{
 await fencedPlan();
 const before=await fenceState(control,fencedScope.fence.resource);
 const r=await operatorHttp('/maintenance/close',{maintenance:'99999999-9999-4999-8999-999999999999'},{OPERATOR_TOKEN:'different-secret'});
 assert.equal(r.status,403);assert.deepEqual(await fenceState(control,fencedScope.fence.resource),before);
});
test('unknown provider restore keeps RUNNING and a repeated request never calls the provider twice',async()=>{
 await fencedPlan();let calls=0;
 const bookmark='00000001-00000002-00000003-'+'1'.repeat(32);
 const provider=()=>{calls++;throw new Error('lost provider response');};
 const first=await operatorHttp('/recovery/restore',{bookmark},{},provider);assert.equal(first.status,503);
 assert.equal((await fenceState(control,fencedScope.fence.resource)).running,1);
 const again=await operatorHttp('/recovery/restore',{bookmark},{},provider);
 assert.equal((await again.json()).state,'RUNNING');assert.equal(calls,1);
});

test('delayed unclaimed restore intent cannot overtake a later completed action',async()=>{
 await fencedPlan();let calls=0;
 const initial=await fenceState(control,fencedScope.fence.resource),id='e'.repeat(64);
 await claimAction(control,initial,id,'abort');await finishAction(control,maintenance,id,{aborted:true});
 const r=await operatorHttp('/recovery/restore',{bookmark:'00000001-00000002-00000003-'+'2'.repeat(32)},{},()=>{calls++;return Response.json({success:true,result:{}});});
 assert.equal(r.status,503);assert.equal((await r.json()).error,'STALE_MAINTENANCE_INTENT');assert.equal(calls,0);
 assert.equal((await fenceState(control,fencedScope.fence.resource)).running,0);
});

const freshDevices=[
 {device:'device-10000000-0000-4000-8000-000000000001',tokenHash:await sha256('4'.repeat(64)),writable:true},
 {device:'device-10000000-0000-4000-8000-000000000002',tokenHash:await sha256('5'.repeat(64)),writable:false}
];
const reopenAdapters=()=>({...adapters(),authority:async()=>fencedAuthority(),fence:()=>fenceState(control,fencedScope.fence.resource),
 latestAction:m=>latestAction(control,fencedScope,m)});
async function recoveredFixture(){
 const pages=await encodePages(catalog);
 await db.batch([
  db.prepare("UPDATE cloud_head SET fingerprint=? WHERE library_id=?").bind(await fingerprint(catalog),scope.library),
  db.prepare("UPDATE cloud_catalog_info SET initialized=1,page_count=?,payload_bytes=?,manifest=? WHERE library_id=?")
   .bind(pages.length,pages.reduce((n,p)=>n+byteLength(p.body),0),await pageManifest(pages),scope.library),
  db.prepare("DELETE FROM cloud_catalog_pages WHERE library_id=?").bind(scope.library),
  ...pages.map(p=>db.prepare('INSERT INTO cloud_catalog_pages VALUES(?,?,?)').bind(scope.library,p.id,p.body))
 ]);
 const recovery=await fencedPlan();await apply(recovery);
 return {recovery,plan:await makeReopenPlan(reopenAdapters(),fencedScope,freshDevices)};
}
const reopen=async(p,database=db,controlDb=control)=>{
 const env={DB:database,CONTROL:controlDb,RECOVERY:r2};
 return await existing(env,fencedScope,p.body.fence.maintenance_id,p.digest,'reopen')??
  executeReopen(env,p,fencedAuthority(),store);
};
test('reopen validates accepted catalog and archives, enrolls fresh tokens and atomically opens a new generation',async()=>{
 const {recovery,plan}=await recoveredFixture(),before=await Promise.all(objects.map(o=>r2.get(o.key).then(r=>r.arrayBuffer())));
 const result=await reopen(plan);assert.equal(result.state,'DONE');assert.equal(result.result.opened,true);
 assert.deepEqual(await reopen(plan),result);
 const f=await fenceState(control,fencedScope.fence.resource);
 assert.equal(f.state,'OPEN');assert.equal(f.service_generation,scope.generation);assert.equal(f.pending,0);assert.equal(f.running,0);
 assert.equal(f.maintenance_id,null);assert.equal(f.next_generation,null);
 await assert.rejects(admit(control,{resource:f.resource_id,library:scope.library,generation:'old-service'}));
 const ticket=await admit(control,{resource:f.resource_id,library:scope.library,generation:scope.generation});await ticket.finish();
 for(const [i,d] of freshDevices.entries()){
  const auth=await authenticateToken(db,d.device+'.'+String(i+4).repeat(64),scope.library,scope.generation);
  assert.equal(auth.writable,d.writable);
 }
 await assert.rejects(authenticateToken(db,'device-a.'+'1'.repeat(64),scope.library,scope.generation));
 assert.deepEqual(await Promise.all(objects.map(o=>r2.get(o.key).then(r=>r.arrayBuffer()))),before);
 assert.equal((await apply(recovery)).state,'SUPERSEDED');
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_reopen_receipts').first()).n,1);
});
test('lost enrollment reply resolves its receipt and never submits the data batch twice',async()=>{
 const {plan}=await recoveredFixture();let batches=0;
 const ambiguous={prepare:db.prepare.bind(db),batch:async s=>{batches++;await db.batch(s);throw new Error('lost data reply');}};
 await assert.rejects(reopen(plan,ambiguous));
 assert.equal((await fenceState(control,fencedScope.fence.resource)).state,'HELD');
 assert.equal((await fenceState(control,fencedScope.fence.resource)).running,1);
 assert.equal((await reopen(plan,ambiguous)).state,'DONE');assert.equal(batches,1);
 assert.equal((await fenceState(control,fencedScope.fence.resource)).state,'OPEN');
});
test('unresolved enrollment without a receipt keeps maintenance exclusive and blocks restore',async()=>{
 const {plan}=await recoveredFixture();let batches=0;
 const ambiguous={prepare:db.prepare.bind(db),batch:async()=>{batches++;throw new Error('unknown request');}};
 await assert.rejects(reopen(plan,ambiguous));assert.equal((await reopen(plan,ambiguous)).state,'RUNNING');assert.equal(batches,1);
 const f=await fenceState(control,fencedScope.fence.resource);assert.equal(f.state,'HELD');assert.equal(f.running,1);
 await assert.rejects(claimAction(control,f,'8'.repeat(64),'restore'));
 await assert.rejects(admit(control,{resource:f.resource_id,library:scope.library,generation:scope.generation}));
});
test('lost external open reply is resolved without repeating either transaction',async()=>{
 const {plan}=await recoveredFixture();let batches=0;
 const ambiguous={prepare:control.prepare.bind(control),batch:async s=>{
  batches++;const r=await control.batch(s);if(batches===2)throw new Error('lost open reply');return r;
 }};
 await assert.rejects(reopen(plan,db,ambiguous));
 assert.equal((await fenceState(control,fencedScope.fence.resource)).state,'OPEN');
 assert.equal((await reopen(plan,db,ambiguous)).state,'DONE');assert.equal(batches,2);
});
test('a committed enrollment cannot admit requests before the external open transaction',async()=>{
 const {plan}=await recoveredFixture();let batches=0;
 const delayed={prepare:control.prepare.bind(control),batch:async s=>{if(++batches===2)throw new Error('open not sent');return control.batch(s);}};
 await assert.rejects(reopen(plan,db,delayed));
 const f=await fenceState(control,fencedScope.fence.resource);assert.equal(f.state,'HELD');assert.equal(f.running,1);
 await assert.rejects(admit(control,{resource:f.resource_id,library:scope.library,generation:scope.generation}));
 assert.equal((await reopen(plan)).state,'DONE');
});
test('reopen refuses corrupt catalog, active old credentials, incorrect index and stale plans before claiming',async()=>{
 const {plan}=await recoveredFixture();
 await db.prepare('DROP TABLE cloud_reopen_receipts').run();
 await assert.rejects(reopen(plan),/REOPEN_SCHEMA_UPGRADE_REQUIRED/);
 assert.equal((await fenceState(control,fencedScope.fence.resource)).running,0);
 const receiptSchema=(await readFile(new URL('../migrations/0005_reopen_receipts.sql',import.meta.url),'utf8')).replace(/^--.*$/gm,'');
 await db.prepare(receiptSchema).run();
 await db.prepare("UPDATE cloud_head SET head_counter=head_counter+1 WHERE library_id=?").bind(scope.library).run();
 await assert.rejects(reopen(plan),/PLAN_STALE/);
 await db.prepare("UPDATE cloud_head SET head_counter=head_counter-1 WHERE library_id=?").bind(scope.library).run();
 await db.prepare("UPDATE cloud_head SET fingerprint=? WHERE library_id=?").bind('f'.repeat(64),scope.library).run();
 await assert.rejects(makeReopenPlan(reopenAdapters(),fencedScope,freshDevices),/RECOVERED_CATALOG_INVALID/);
 await db.prepare("UPDATE cloud_head SET fingerprint=? WHERE library_id=?").bind(await fingerprint(catalog),scope.library).run();
 await db.prepare("UPDATE cloud_devices SET revoked=0 WHERE library_id=? AND device_id='device-a'").bind(scope.library).run();
 await assert.rejects(reopen(plan),/RECOVERED_AUTHORITY_REQUIRED/);
 await db.prepare("UPDATE cloud_devices SET revoked=1 WHERE library_id=?").bind(scope.library).run();
 await db.prepare("UPDATE cloud_backup_slots SET object_etag='wrong' WHERE library_id=? AND slot=0").bind(scope.library).run();
 await assert.rejects(reopen(plan),/RECOVERED_INDEX_MISMATCH/);
 assert.equal((await fenceState(control,fencedScope.fence.resource)).running,0);
});
test('authenticated housekeeping uses ordinary admission and cannot mutate a HELD generation',async()=>{
 const {plan}=await recoveredFixture();
 const before=await snapshot(adapters().db,scope.library);
 const closed=await operatorHttp('/maintenance/housekeep',{});assert.equal(closed.status,503);assert.equal((await closed.json()).error,'MAINTENANCE_REQUIRED');
 assert.deepEqual(await snapshot(adapters().db,scope.library),before);
 await reopen(plan);
 const result=await operatorHttp('/maintenance/housekeep',{});assert.equal(result.status,200);assert.equal((await result.json()).inspected,4);
 const fence=await fenceState(control,fencedScope.fence.resource);assert.equal(fence.state,'OPEN');assert.equal(fence.pending,0);
});
test('only housekeeping may inspect enabled application authority',()=>{
 const enabled=fencedAuthority();enabled.vars.ENABLE_CATALOG_API='true';
 assert.ok(authorityBlockers(enabled,fencedScope).length>0);assert.deepEqual(authorityBlockers(enabled,fencedScope,false),[]);
 enabled.database='unrelated';assert.ok(authorityBlockers(enabled,fencedScope,false).includes('WORKER_BINDING_MISMATCH'));
});

test('competing reopen plans have one winner and later maintenance cannot reuse the old cycle',async()=>{
 const {plan}=await recoveredFixture();
 const other=await makeReopenPlan(reopenAdapters(),fencedScope,[{...freshDevices[0],device:'device-20000000-0000-4000-8000-000000000001',tokenHash:await sha256('6'.repeat(64))}]);
 const results=await Promise.allSettled([reopen(plan),reopen(other)]);
 assert.equal(results.filter(r=>r.status==='fulfilled'&&r.value.state==='DONE').length,1);
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_reopen_receipts').first()).n,1);
 const nextGeneration='99999999-9999-4999-8999-999999999999';
 await assert.rejects(closeFence(control,fencedScope.fence.resource,maintenance,nextGeneration),/MAINTENANCE_CONFLICT/);
 const next='88888888-8888-4888-8888-888888888888';
 assert.equal((await closeFence(control,fencedScope.fence.resource,next,nextGeneration)).state,'HELD');
 const winner=results[0].status==='fulfilled'?plan:other;
 assert.equal((await reopen(winner)).state,'SUPERSEDED');
});
