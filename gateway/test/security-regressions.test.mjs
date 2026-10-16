import {beforeAll, afterAll, describe, test, expect} from 'vitest';
import {createHash, generateKeyPairSync, randomBytes, randomUUID, sign} from 'node:crypto';
import {harness, origin, ownerKey, digest} from './helpers.mjs';
let h;
beforeAll(async()=>{h=await harness();},30000);
afterAll(async()=>{await h?.mf.dispose();});
const cookies=r=>(r.headers.getSetCookie?.()??[r.headers.get('set-cookie')]).filter(Boolean).map(c=>c.split(';')[0]).join('; ');

async function consent(scopes, method='S256') {
 const registered=await h.post('/oauth/register',{client_name:'Untrusted test label',redirect_uris:['https://example.com/callback'],grant_types:['authorization_code','refresh_token'],response_types:['code'],token_endpoint_auth_method:'none'});
 const client=await registered.json(),verifier=randomBytes(32).toString('base64url');
 const query=new URLSearchParams({response_type:'code',client_id:client.client_id,redirect_uri:'https://example.com/callback',scope:scopes.join(' '),state:'state',code_challenge:createHash('sha256').update(verifier).digest('base64url'),code_challenge_method:method,resource:origin+'/mcp'});
 const page=await h.get('/authorize?'+query);
 const cookie=cookies(page),text=await page.text(),handle=text.match(/name="handle" value="([^"]+)"/)?.[1];
 return {page,text,cookie,handle,client,scopeProof:text.match(/name="scope_proof" value="([^"]+)"/)?.[1]};
}
async function approve(info,cookie,scopes) {
 const form=new URLSearchParams({handle:info.handle??'',scope_proof:info.scopeProof??'',owner_key:ownerKey,decision:'allow'});
 scopes.forEach(s=>form.append('scope',s));
 return h.get('/authorize',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',Cookie:cookie,Origin:origin},body:form.toString()});
}

describe('review-driven security regressions',()=>{
 test('self-signed bogus pairing does not reset the account or drop a live device',async()=>{
  const credentials=await h.oauth(),d=await h.pairedDevice();let effects=0;
  const ws=await h.connectDevice(d,()=>{effects++;});
  try {
   const {privateKey,publicKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),pub=publicKey.export({type:'spki',format:'der'}).toString('base64url');
   const id=randomUUID(),code=randomBytes(18).toString('base64url'),name='Invalid pairing';
   const message=`pair\n${origin}\n${id}\n${code}\n${pub}\n${name}`;
   const invalid=await h.post('/device/pair',{device_id:id,name,public_key:pub,pairing_code:code,signature:sign('sha256',Buffer.from(message),privateKey).toString('base64url')});
   expect(invalid.status).toBe(401);
   const result=await h.call(credentials.access_token,'create_playlist',{device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Still online'});
   expect(result.data.outcome).toBe('COMMITTED');expect(effects).toBe(1);
  } finally {ws.close();}
 },15000);
 test('bogus connection nonce cannot terminate an existing connection',async()=>{
  const credentials=await h.oauth(),d=await h.pairedDevice();const ws=await h.connectDevice(d);
  try {
   const bad=await h.get('/device/connect',{headers:{Upgrade:'websocket','X-NewPipe-Device':d.id,'X-NewPipe-Nonce':randomBytes(24).toString('base64url'),'X-NewPipe-Signature':'bad'}});
   expect(bad.status).toBe(401);
   const current=await h.call(credentials.access_token,'get_device_status',{device_id:d.id});
   expect(current.data.online).toBe(true);
   expect(current.data.connection_id).toBeUndefined();
  } finally {ws.close();}
 },15000);
 test('replaying a pairing after revocation never re-enrolls the device',async()=>{
  const d=await h.pairedDevice();
  expect((await h.post('/admin/revoke-device',{device_id:d.id},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
  expect((await h.post('/device/pair',d.registration)).status).toBe(401);
  expect((await h.post('/admin/revoke-device',{device_id:d.id},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
 });
 test('consent shows only requested permissions and deletion is opt-in',async()=>{
  const limited=await consent(['mcp.connect','library.read']);
  expect(limited.page.status).toBe(200);
  expect(limited.text).toContain('value="library.read"');
  expect(limited.text).not.toContain('value="library.delete"');
  const broad=await consent(['mcp.connect','library.read','library.delete','playlist.delete']);
  const checks=[...broad.text.matchAll(/<input type="checkbox"[^>]+>/g)].map(m=>m[0]);
  expect(checks.find(x=>x.includes('value="library.delete"'))).not.toContain('checked');
  expect(checks.find(x=>x.includes('value="playlist.delete"'))).not.toContain('checked');
 });
 test('consent without its original browser cookie cannot authorize',async()=>{
  const info=await consent(['mcp.connect','library.read']);
  const result=await approve(info,'',['library.read']);
  expect(result.headers.get('location')??'').not.toMatch(/[?&]code=/);
 });
 test('another browsers consent cookie cannot authorize this handle',async()=>{
  const a=await consent(['mcp.connect','library.read']),b=await consent(['mcp.connect','library.read']);
  const result=await approve(a,b.cookie,['library.read']);
  expect(result.headers.get('location')??'').not.toMatch(/[?&]code=/);
 });
 test('submitted scopes cannot exceed the requested grant',async()=>{
  const a=await consent(['mcp.connect','library.read']);
  const result=await approve(a,a.cookie,['library.read','library.delete']);
  expect(result.headers.get('location')??'').not.toMatch(/[?&]code=/);
 });
 test('public consent proof cannot be reused as an owner session cookie',async()=>{
  const info=await consent(['mcp.connect','library.read']);
  const forged='__Host-newpipe-admin='+info.scopeProof;
  const response=await h.get('/setup',{headers:{Cookie:forged}});
  expect(await response.text()).toContain('name="owner_key"');
  const result=await h.post('/admin/pairings',{csrf:'forged'},{Cookie:forged});
  expect(result.status).toBe(401);
 });
 test('plain PKCE is refused',async()=>{
  const a=await consent(['mcp.connect','library.read'],'plain');
  expect(a.text).not.toContain('name="owner_key"');
  expect(a.page.status===400 || a.page.status===302).toBe(true);
 });
 test('first-seen stale command is not executed and cannot replay after renewal',async()=>{
  const credentials=await h.oauth(),d=await h.pairedDevice();let effects=0;const ws=await h.connectDevice(d,()=>{effects++;});
  try {
   const args={device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now()-301_000,name:'Expired'};
   const result=await h.call(credentials.access_token,'create_playlist',args);
   expect(result.data.outcome).toBe('NOT_EXECUTED');expect(result.data.code).toBe('EXPIRED');
   expect((await h.call(credentials.access_token,'create_playlist',args)).data.outcome).toBe('NOT_EXECUTED');expect(effects).toBe(0);
   const later=await h.call(credentials.access_token,'create_playlist',{...args,issued_at_ms:Date.now()});
   expect(later.data.code).toBe('REQUEST_ID_REUSED');expect(effects).toBe(0);
  } finally {ws.close();}
 },15000);
 test('accepted execution deadline is not consumed by normal AI reasoning delay',async()=>{
  const credentials=await h.oauth(),d=await h.pairedDevice();let observed;
  const ws=await h.connectDevice(d,frame=>{observed=frame.request;});
  try {
   const r=await h.call(credentials.access_token,'create_playlist',{device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now()-60_000,name:'Current'});
   expect(r.data.outcome).toBe('COMMITTED');expect(observed.expires_at_ms-Date.now()).toBeGreaterThan(25_000);
  } finally {ws.close();}
 },15000);
 test('result lookup is isolated to the original client and original scopes',async()=>{
  const a=await h.oauth(),b=await h.oauth(['mcp.connect']),d=await h.pairedDevice();const ws=await h.connectDevice(d);
  try {
   const id=randomUUID();await h.call(a.access_token,'create_playlist',{device_id:d.id,request_id:id,issued_at_ms:Date.now(),name:'Private result'});
   const own=await h.call(a.access_token,'get_command_result',{device_id:d.id,original_request_id:id});
   expect(own.data.data.original_outcome).toBe('COMMITTED');
   const other=await h.call(b.access_token,'get_command_result',{device_id:d.id,original_request_id:id});
   expect(other.data.code).toBe('RESULT_NOT_RETAINED');expect(other.data.data.playlist_id).toBeUndefined();
  } finally {ws.close();}
 },15000);
 test('batch requests and nonpublic URLs are refused before routing',async()=>{
  const credentials=await h.oauth();
  const batch=await h.post('/mcp',[{jsonrpc:'2.0',id:1,method:'tools/list'}],{Authorization:'Bearer '+credentials.access_token});
  expect(batch.status).toBe(400);
  const result=await h.rpc(credentials.access_token,'tools/call',{name:'save_urls',arguments:{device_id:randomUUID(),request_id:randomUUID(),issued_at_ms:Date.now(),urls:['http://127.0.0.1/']}});
  expect(result.value.result?.isError===true || result.value.error!==undefined).toBe(true);
 });
 test('administrative block can be reversed without silently granting new scopes',async()=>{
  const credentials=await h.oauth(['mcp.connect','library.read']),id='c_'+digest(credentials.client_id);
  expect((await h.post('/admin/revoke-client',{client_id:id},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
  expect((await h.call(credentials.access_token,'list_devices',{})).data.code).toBe('CLIENT_REVOKED');
  expect((await h.post('/admin/unblock-client',{client_id:id},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
  expect(Array.isArray((await h.call(credentials.access_token,'list_devices',{})).data.devices)).toBe(true);
 });
 test('token exchange grant is disabled',async()=>{
  const r=await h.get('/oauth/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:token-exchange',subject_token:'invalid',subject_token_type:'urn:ietf:params:oauth:token-type:access_token'}).toString()});
  expect(r.status).toBeGreaterThanOrEqual(400);
 });
 test('out-of-order client policy delivery cannot reapply an older block',async()=>{
  const credentials=await h.oauth(),d=await h.pairedDevice();
  const ns=await h.mf.getDurableObjectNamespace('DEVICES');
  const stub=ns.get(ns.idFromName(d.id)),client='c_'+digest(credentials.client_id);
  const policy=(revision,blocked)=>stub.fetch('https://internal/client-policy',{method:'POST',body:JSON.stringify({client_id:client,revision,blocked})});
  expect((await policy(2,false)).status).toBe(200);
  expect((await policy(1,true)).status).toBe(200);
  const ws=await h.connectDevice(d);
  try {
   const r=await h.call(credentials.access_token,'create_playlist',{device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Latest policy wins'});
   expect(r.data.outcome).toBe('COMMITTED');
  } finally {ws.close();}
 },15000);
 test('actual grant revocation kills the old token even after emergency unblock',async()=>{
  const credentials=await h.oauth(),client='c_'+digest(credentials.client_id);
  const listed=await h.post('/admin/grants',{}, {Authorization:'Bearer '+ownerKey});
  expect(listed.status).toBe(200);
  const grant=(await listed.json()).items.find(g=>g.clientId===credentials.client_id);
  expect(grant).toBeDefined();
  expect((await h.post('/admin/revoke-grant',{grant_id:grant.id,client_id:client},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
  expect((await h.post('/admin/unblock-client',{client_id:client},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
  const attempt=await h.rpc(credentials.access_token,'tools/list',{});
  expect(attempt.status).toBe(401);
 });
 test('a fresh owner code can re-pair the same nonrevoked device without changing its identity',async()=>{
  const d=await h.pairedDevice();
  const code=(await (await h.post('/admin/pairings',{}, {Authorization:'Bearer '+ownerKey})).json()).pairing_code;
  const message=`pair\n${origin}\n${d.id}\n${code}\n${d.registration.public_key}\n${d.name}`;
  const registration={...d.registration,pairing_code:code,signature:sign('sha256',Buffer.from(message),d.privateKey).toString('base64url')};
  expect((await h.post('/device/pair',registration)).status).toBe(200);
  expect((await h.post('/device/pair',registration)).status).toBe(200);
  expect((await h.post('/admin/revoke-device',{device_id:d.id},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
  expect((await h.post('/device/pair',registration)).status).toBe(401);
 });

 test('a deployment without authentication secrets remains completely closed',async()=>{
  const closed=await harness({OWNER_KEY_HASH:'',SESSION_SECRET:''});
  try {
   for(const path of ['/health','/setup','/.well-known/oauth-protected-resource','/mcp']) {
    const r=await closed.get(path);expect(r.status).toBe(503);expect(await r.json()).toEqual({error:'SERVER_NOT_CONFIGURED'});
   }
  } finally {await closed.mf.dispose();}
 },15000);

});
