// SPDX-License-Identifier: GPL-3.0-or-later
// Bundle the production S3 adapter exactly for a Worker (no browser DOMParser).
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {build} from 'esbuild';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {operatorAliases} from '../operator/build-aliases.mjs';
test('bundled operator S3 adapter parses XML and exact ranges in workerd without following redirects',async()=>{
 const scope={account:'a'.repeat(32),worker:'newpipe-runtime-test',database:'11111111-1111-4111-8111-111111111111',
  bucket:'newpipe-runtime-test',library:'library',generation:'22222222-2222-4222-8222-222222222222'};
 const script=`import {recoveryStore} from '../operator/adapters.mjs';
 export default {async fetch(){try{
  const store=recoveryStore(${JSON.stringify(scope)},{accessKeyId:'synthetic-access',secretAccessKey:'synthetic-secret'});
  return Response.json({objects:await store.list('objects','recovery/library/',null),
   uploads:await store.list('uploads','recovery/library/',null),
   head:await store.head('recovery/library/0/object'),
   bytes:Array.from(await store.read('recovery/library/0/object',2,3,'etag'))});
 }catch(e){return Response.json({error:e.name,message:e.message},{status:503});}}};`;
 const bundled=await build({stdin:{contents:script,resolveDir:resolve('test'),sourcefile:'runtime-s3.mjs'},
  bundle:true,write:false,format:'esm',platform:'browser',conditions:['workerd','worker','browser'],
  external:['node:*'],alias:operatorAliases,logLevel:'silent'});
 let redirect=false,foreignRequests=0;const operations=[];
 const xml=value=>new Response(value,{headers:{'Content-Type':'application/xml'}});
 const mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules:true,script:bundled.outputFiles[0].text,
  compatibilityDate:'2026-08-25',compatibilityFlags:['nodejs_compat'],outboundService:async request=>{
   const u=new URL(request.url);
   if(![scope.account+'.r2.cloudflarestorage.com',scope.bucket+'.'+scope.account+'.r2.cloudflarestorage.com'].includes(u.hostname)){foreignRequests++;return new Response('unexpected');}
   assert.match(request.headers.get('authorization'),/^AWS4-HMAC-SHA256 /);
   if(redirect)return new Response(null,{status:302,headers:{Location:'https://foreign.test/'}});
   if(u.searchParams.has('list-type')){
    operations.push('list');
    return xml('<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>false</IsTruncated><Contents><Key>recovery/library/0/object</Key><ETag>&quot;etag&quot;</ETag><Size>8</Size><LastModified>2026-10-20T00:00:00.000Z</LastModified></Contents></ListBucketResult>');
   }
   if(u.searchParams.has('uploads')){
    operations.push('uploads');
    return xml('<ListMultipartUploadsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>false</IsTruncated><Upload><Key>recovery/library/1/pending</Key><UploadId>upload-id</UploadId><Initiated>2026-10-20T00:00:00.000Z</Initiated></Upload></ListMultipartUploadsResult>');
   }
   if(request.method==='HEAD'){
    operations.push('head');
    return new Response(null,{headers:{'Content-Length':'8',ETag:'"etag"','Last-Modified':'Tue, 20 Oct 2026 00:00:00 GMT','x-amz-meta-recovery':'metadata'}});
   }
   operations.push('range');
   assert.equal(request.headers.get('range'),'bytes=2-4');assert.equal(request.headers.get('if-match'),'"etag"');
   return new Response(Uint8Array.of(1,2,3),{status:206,headers:{'Content-Length':'3','Content-Range':'bytes 2-4/8',ETag:'"etag"'}});
  }}));
 try{
  const good=await mf.dispatchFetch('https://operator.test');assert.equal(good.status,200,await good.clone().text());
  const r=await good.json();assert.deepEqual(operations,['list','uploads','head','range']);
  assert.deepEqual(r.objects,{items:[{key:'recovery/library/0/object',etag:'etag',size:8,modified:'2026-10-20T00:00:00.000Z'}],truncated:false,next:null});
  assert.deepEqual(r.uploads,{items:[{key:'recovery/library/1/pending',uploadId:'upload-id',initiated:'2026-10-20T00:00:00.000Z'}],truncated:false,next:null});
  assert.equal(r.head.metadata,'metadata');assert.deepEqual(r.bytes,[1,2,3]);
  redirect=true;const bad=await mf.dispatchFetch('https://operator.test');assert.equal(bad.status,503);assert.equal(foreignRequests,0);
 }finally{await mf.dispose();}
});
