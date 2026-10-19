#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Explicit synthetic HTTP integration test. No administrative API or credentials are bundled.
import {parseArgs} from 'node:util';
import {readFile,writeFile,stat,mkdir,realpath} from 'node:fs/promises';
import {resolve,relative,isAbsolute} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {encodePages,readPages,changedPages,pageManifest,byteLength} from '../src/pages.ts';
import {makeUpload} from '../src/service.ts';
import {fingerprint} from '../src/catalog.ts';

const {values}=parseArgs({options:{credentials:{type:'string'},output:{type:'string'}}});
if(!values.credentials||!values.output)throw new Error('Use --credentials <private QA JSON> --output <new result JSON>');
const credentialsPath=resolve(values.credentials),output=resolve(values.output);
const repo=await realpath(fileURLToPath(new URL('../../',import.meta.url)));
const credentialRelative=relative(repo,await realpath(credentialsPath));
if(!credentialRelative.startsWith('..'+(process.platform==='win32'?'\\':'/'))&&!isAbsolute(credentialRelative))throw new Error('QA credentials must be outside the repository');
const mode=(await stat(credentialsPath)).mode&0o777;
if(mode&0o077)throw new Error('QA credential file must not be readable by group/others');
const config=JSON.parse(await readFile(credentialsPath,'utf8'));
const origin=new URL(config.origin);
assert.equal(origin.href,'https://newpipe-cloud-sync-v1.ritorhymes.workers.dev/');
assert.equal(config.syntheticOnly,true);
assert.equal(config.library,'newpipe-library-v1');
assert.match(config.serviceGeneration,/^[0-9a-f-]{36}$/);
for(const token of [config.tokens.a,config.tokens.b])assert.match(token,/^qa-[a-z0-9-]+\.[0-9a-f]{64}$/);
await assert.rejects(stat(output),error=>error.code==='ENOENT');
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
const catalog=name=>structuredClone(fixture.catalogs.find(c=>c.name===name).catalog);
const checks=[];let requests=0;
async function call(path,body={},token=config.tokens.a){
 assert.ok(path.startsWith('/v1/'));
 const response=await fetch(new URL(path,origin),{method:'POST',redirect:'error',signal:AbortSignal.timeout(20000),
  headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify(body)});
 const bytes=await response.arrayBuffer();assert.ok(bytes.byteLength<=1024*1024,'response bound');requests++;
 return {status:response.status,data:JSON.parse(new TextDecoder().decode(bytes)),cache:response.headers.get('cache-control')};
}
function success(r){assert.equal(r.status,200,JSON.stringify(r.data));return r.data;}
async function status(token=config.tokens.a){
 const r=await call('/v1/head',{},token),s=success(r);
 assert.equal(s.scope.library,config.library);assert.equal(s.scope.service,config.serviceGeneration);
 assert.match(r.cache,/no-store/);return s;
}
async function download(token){
 const current=await status(token);let cursor=null;const pages=[];
 do{
  const data=success(await call('/v1/pages',{head:current.head,cursor},token));
  assert.deepEqual(data.head,current.head);pages.push(...data.data);cursor=data.nextCursor;
  assert.ok(pages.length<=8,'small deployment page admission');
 }while(cursor!==null);
 assert.equal(pages.length,current.pages);assert.equal(pages.reduce((n,p)=>n+byteLength(p.body),0),current.bytes);
 assert.equal(await pageManifest(pages),current.manifest);
 const decoded=await readPages(pages);assert.equal(await fingerprint(decoded.catalog),current.head.fingerprint);
 return {...decoded,pages,status:current};
}
async function publish(target,token,sequence,prior,requiredMode){
 const before=await status(token),pages=await encodePages(target,prior),changes=changedPages(prior,pages);
 const upload=await makeUpload(requiredMode??(before.initialized?'sync':'initialize'),sequence,before.head,await fingerprint(target),changes);
 assert.ok(upload.header.payloadBytes<=65536 && pages.length<=8);
 success(await call('/v1/uploads/start',upload.header,token));
 for(let i=0;i<upload.chunks.length;i++)success(await call('/v1/uploads/chunk',{sequence,index:i,changes:upload.chunks[i]},token));
 const ref={sequence,digest:upload.header.digest};success(await call('/v1/uploads/seal',ref,token));
 const result=success(await call('/v1/uploads/publish',ref,token));
 assert.equal(result.receipt.head.fingerprint,await fingerprint(target));
 return {pages,ref,result,before};
}
const started=new Date().toISOString();
const initial=await status();assert.equal(initial.initialized,false,'Refusing to replace an already initialized live catalog');assert.equal(initial.pages,0);
assert.equal((await call('/v1/connect',{},'qa-invalid.'+'0'.repeat(64))).status,401);checks.push('invalid credentials rejected without catalog');
const a=await publish(catalog('base'),config.tokens.a,'1',[],'initialize');checks.push('device A initializes exact synthetic catalog');
const bBefore=await download(config.tokens.b);assert.equal(await fingerprint(bBefore.catalog),await fingerprint(catalog('base')));checks.push('device B downloads manifest-verified exact A state');
const b=await publish(catalog('reorder'),config.tokens.b,'1',bBefore.pages);checks.push('device B publishes real reorder against current head');
const aAfter=await download(config.tokens.a);assert.equal(await fingerprint(aAfter.catalog),await fingerprint(catalog('reorder')));checks.push('device A downloads B revision and exact order');
const stalePages=await encodePages(catalog('rename'),a.pages);
const stale=await makeUpload('sync','2',a.result.receipt.head,await fingerprint(catalog('rename')),changedPages(a.pages,stalePages));
const rejected=await call('/v1/uploads/start',stale.header,config.tokens.a);assert.equal(rejected.status,409);assert.equal(rejected.data.error,'CLOUD_CHANGED');checks.push('stale device edit cannot overwrite newer cloud state');
assert.deepEqual(success(await call('/v1/uploads/publish',b.ref,config.tokens.b)),b.result);checks.push('exact publication retry returns the same receipt');
const cleared=await publish(catalog('empty'),config.tokens.a,'2',aAfter.pages);const empty=await download(config.tokens.b);
assert.equal(empty.catalog.library.length,0);assert.equal(await fingerprint(empty.catalog),await fingerprint(catalog('empty')));checks.push('ordinary global removal produces verified empty catalog');
assert.equal(empty.status.head.revision,'3');
const result={status:'PASS',started,finished:new Date().toISOString(),origin:origin.origin,library:config.library,
 serviceGeneration:config.serviceGeneration,requests,checks,finalHead:empty.status.head,finalManifest:empty.status.manifest,
 finalPages:empty.pages.length,finalBytes:empty.status.bytes,syntheticOnly:true,clients:'two separately authenticated HTTP clients; not Android devices',
 deployedCpuAcceptance:false,fullSyncFeatureAcceptance:false,qaCredentialsMustBeRevokedByCoordinator:true};
await writeFile(output,JSON.stringify(result,null,2)+'\n',{flag:'wx',mode:0o600});
console.log(JSON.stringify({...result,checks:checks.length}));
