import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
export const origin='https://newpipe.test';
export const owner='90000000-0000-0000-0000-000000000001';
export const ownerKey='test-only-'+randomBytes(32).toString('base64url');
export const digest=s=>createHash('sha256').update(s).digest('hex');
export async function harness(bindingOverrides={}) {
 const mf=new Miniflare(convertV4MiniflareOptions({modules:true,scriptPath:resolve('dist/index.js'),compatibilityDate:'2026-08-25',compatibilityFlags:['nodejs_compat','global_fetch_strictly_public'],
   kvNamespaces:['OAUTH_KV'],durableObjects:{ACCOUNT:{className:'ControlAccount',useSQLite:true},DEVICES:{className:'DeviceRoom',useSQLite:true}},
   bindings:{PUBLIC_ORIGIN:origin,OWNER_ID:owner,OWNER_KEY_HASH:digest(ownerKey),SESSION_SECRET:randomBytes(32).toString('base64url'),...bindingOverrides},
   ratelimits:{AUTH_LIMITER:{namespace_id:'1',simple:{limit:1000,period:60}},CONTROL_LIMITER:{namespace_id:'2',simple:{limit:1000,period:60}}}
 }));
 await mf.ready;
 const get=(path,init={})=>mf.dispatchFetch(origin+path,{redirect:'manual',...init});
 const post=(path,value,headers={})=>get(path,{method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(value)});
 const cookies=r=>(r.headers.getSetCookie?.()??[r.headers.get('set-cookie')]).filter(Boolean).map(c=>c.split(';')[0]).join('; ');
 async function oauth(scopes=['mcp.connect','library.read','library.write','playlist.write','library.delete','playlist.delete','playback.control']) {
  const registered=await post('/oauth/register',{client_name:'Synthetic QA client',redirect_uris:['https://example.com/callback'],grant_types:['authorization_code','refresh_token'],response_types:['code'],token_endpoint_auth_method:'none'});
  assert.equal(registered.status,201,await registered.clone().text());const client=await registered.json();
  const verifier=randomBytes(32).toString('base64url'),challenge=createHash('sha256').update(verifier).digest('base64url');
  const query=new URLSearchParams({response_type:'code',client_id:client.client_id,redirect_uri:'https://example.com/callback',scope:scopes.join(' '),state:'synthetic-state',code_challenge:challenge,code_challenge_method:'S256',resource:origin+'/mcp'});
  const page=await get('/authorize?'+query);assert.equal(page.status,200,await page.clone().text());const text=await page.text();
  const handle=text.match(/name="handle" value="([^"]+)"/)?.[1];assert.ok(handle);
  const scopeProof=text.match(/name="scope_proof" value="([^"]+)"/)?.[1];assert.ok(scopeProof);
  const values=new URLSearchParams({handle,scope_proof:scopeProof,owner_key:ownerKey,decision:'allow'});for(const scope of scopes) if(scope!=='mcp.connect') values.append('scope',scope);
  const approved=await get('/authorize',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',Cookie:cookies(page),Origin:origin},body:values.toString()});
  assert.equal(approved.status,302,await approved.clone().text());const redirect=new URL(approved.headers.get('location'));assert.equal(redirect.searchParams.get('state'),'synthetic-state');
  const code=redirect.searchParams.get('code');assert.ok(code);
  const exchanged=await get('/oauth/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'authorization_code',client_id:client.client_id,redirect_uri:'https://example.com/callback',code_verifier:verifier,code,resource:origin+'/mcp'}).toString()});
  assert.equal(exchanged.status,200,await exchanged.clone().text());return {...await exchanged.json(),client_id:client.client_id,consentHandle:handle,consentCookie:cookies(page)};
 }
 async function rpc(token,method,params,id=1) {
  const r=await post('/mcp',{jsonrpc:'2.0',id,method,params},{Authorization:'Bearer '+token,Accept:'application/json, text/event-stream','MCP-Protocol-Version':'2025-11-25'});
  const text=await r.text();let value;try{value=JSON.parse(text);}catch{throw new Error(`HTTP ${r.status}: ${text.slice(0,300)}`);}return {status:r.status,value};
 }
 async function call(token,name,args) {
  const {status,value}=await rpc(token,'tools/call',{name,arguments:args});assert.equal(status,200,JSON.stringify(value));assert.equal(value.error,undefined,JSON.stringify(value));
  return {raw:value.result,data:value.result.structuredContent??JSON.parse(value.result.content[0].text)};
 }
 async function pairedDevice() {
  const {privateKey,publicKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),publicWire=publicKey.export({type:'spki',format:'der'}).toString('base64url');
  const id=randomUUID(),name='Synthetic test phone';
  const r=await post('/admin/pairings',{}, {Authorization:'Bearer '+ownerKey});assert.equal(r.status,200,await r.clone().text());const code=(await r.json()).pairing_code;
  const message=`pair\n${origin}\n${id}\n${code}\n${publicWire}\n${name}`;
  const registration={device_id:id,name,public_key:publicWire,pairing_code:code,signature:sign('sha256',Buffer.from(message),privateKey).toString('base64url')};
  const paired=await post('/device/pair',registration);assert.equal(paired.status,200,await paired.clone().text());return {id,name,privateKey,registration};
 }
 async function connectDevice(d,listener) {
  const clientNonce=randomBytes(24).toString('base64url'),timestamp=Date.now();
  const proof=`challenge\n${origin}\n${d.id}\n${clientNonce}\n${timestamp}`;
  const r=await post('/device/challenge',{device_id:d.id,client_nonce:clientNonce,timestamp_ms:timestamp,signature:sign('sha256',Buffer.from(proof),d.privateKey).toString('base64url')});
  assert.equal(r.status,200,await r.clone().text());const challenge=await r.json();
  const proof2=`connect\n${origin}\n${d.id}\n${challenge.nonce}\n${clientNonce}`;
  const upgraded=await get('/device/connect',{headers:{Upgrade:'websocket','X-NewPipe-Device':d.id,'X-NewPipe-Nonce':challenge.nonce,'X-NewPipe-Signature':sign('sha256',Buffer.from(proof2),d.privateKey).toString('base64url')}});
  assert.equal(upgraded.status,101);const ws=upgraded.webSocket;assert.ok(ws);ws.accept();
  await new Promise((resolveReady,reject)=>{
   const timer=setTimeout(()=>reject(new Error('WebSocket handshake timeout')),5000);
   ws.addEventListener('message',event=>{
    const frame=JSON.parse(event.data);
    if(frame.type==='connected') ws.send(JSON.stringify({type:'hello',connection_id:frame.connection_id,hello:{version:1,device_id:d.id,catalog_epoch:randomUUID(),playback_epoch:randomUUID(),capabilities:['get_catalog_status','create_playlist','delete_playlist','remove_library_items','get_now_playing'],permissions:['library.read','library.write','library.delete','playlist.write','playlist.delete','playback.control']}}));
    if(frame.type==='ready') {clearTimeout(timer);resolveReady();}
    if(frame.type==='command') {
     if(listener?.(frame,ws)===false) return;
     ws.send(JSON.stringify({type:'result',connection_id:frame.connection_id,client_id:frame.client_id,result:{request_id:frame.request.request_id,device_id:d.id,outcome:frame.request.command.type==='get_catalog_status'?'COMPLETED':'COMMITTED',code:'OK',data:{playlist_id:17,affected_memberships:0}}}));
    }
   });
  });return ws;
 }
 return {mf,get,post,oauth,rpc,call,pairedDevice,connectDevice};
}
