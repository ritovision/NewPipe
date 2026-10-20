// SPDX-License-Identifier: GPL-3.0-or-later
// Exercise the production adapter in workerd, not Node's more permissive Fetch.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {stripTypeScriptTypes} from 'node:module';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
test('operator Cloudflare adapter works in workerd and rejects redirects without forwarding credentials',async()=>{
 const scope={account:'a'.repeat(32),worker:'newpipe-runtime-test',database:'11111111-1111-4111-8111-111111111111',
  bucket:'newpipe-runtime-test',library:'library',generation:'22222222-2222-4222-8222-222222222222'};
 const bindings=[{name:'DB',type:'d1',id:scope.database},{name:'RECOVERY',type:'r2_bucket',bucket_name:scope.bucket},
  ...Object.entries({ENABLE_CATALOG_API:'false',ENABLE_RECOVERY_API:'false',ENABLE_REPLACEMENT_API:'false',LIBRARY_ID:scope.library,SERVICE_GENERATION:scope.generation})
   .map(([name,text])=>({name,type:'plain_text',text}))];
 const modules=[{type:'ESModule',path:resolve('test/runtime-operator-adapter.mjs'),contents:
  "import {cloudflareClient} from '../operator/cloudflare.mjs';export default {async fetch(request){try{return Response.json(await cloudflareClient("+JSON.stringify(scope)+",'synthetic-token').authority());}catch(e){return Response.json({error:e.code??e.message},{status:503});}}}"}];
 for(const f of ['operator/cloudflare.mjs','operator/recovery.mjs',...(await readdir(new URL('../src/',import.meta.url))).filter(f=>f.endsWith('.ts')).map(f=>'src/'+f)]){
  const source=await readFile(new URL('../'+f,import.meta.url),'utf8');
  modules.push({type:'ESModule',path:resolve(f),contents:f.endsWith('.ts')?stripTypeScriptTypes(source,{mode:'strip'}):source});
 }
 let redirect=false,foreignRequests=0;
 const mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules,compatibilityDate:'2026-08-25',compatibilityFlags:['nodejs_compat'],
  outboundService:async request=>{
   const url=new URL(request.url);
   if(url.hostname!=='api.cloudflare.com'){foreignRequests++;return new Response('unexpected');}
   assert.equal(request.headers.get('authorization'),'Bearer synthetic-token');
   if(redirect)return new Response(null,{status:302,headers:{Location:'https://wrong-account.test/'}});
   const p=url.pathname;
   const result=p.endsWith('/deployments')?{deployments:[{id:'deployment',versions:[{version_id:'version',percentage:100}]}]}:
    p.includes('/versions/')?{id:'version',resources:{bindings}}:{bindings};
   return Response.json({success:true,result});
  }}));
 try{
  const good=await mf.dispatchFetch('https://operator.test');assert.equal(good.status,200,await good.clone().text());
  assert.equal((await good.json()).database,scope.database);
  redirect=true;
  const bad=await mf.dispatchFetch('https://operator.test');assert.equal(bad.status,503);
  assert.equal((await bad.json()).error,'CLOUDFLARE_HTTP_302');assert.equal(foreignRequests,0);
 }finally{await mf.dispose();}
});
