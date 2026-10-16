import { beforeAll, afterAll, describe, expect, test } from 'vitest';
import { randomBytes, randomUUID } from 'node:crypto';
import { harness, origin, ownerKey, digest } from './helpers.mjs';
let h;
beforeAll(async()=>{h=await harness();},30000);
afterAll(async()=>{await h?.mf.dispose();});

describe('private gateway boundaries',()=>{
 test('health is generic and anonymous MCP is rejected',async()=>{
  expect((await h.get('/health')).status).toBe(200);
  const r=await h.post('/mcp',{jsonrpc:'2.0',id:1,method:'tools/list'},{Accept:'application/json, text/event-stream'});
  expect(r.status).toBe(401);expect(r.headers.get('www-authenticate')).toContain('oauth-protected-resource');
 });
 test('raw internal routes and unauthenticated administration are unavailable',async()=>{
  expect((await h.post('/dispatch',{})).status).toBe(404);
  expect((await h.post('/register',{})).status).toBe(404);
  expect((await h.post('/admin/pairings',{})).status).toBe(401);
 });
 test('untrusted origins and oversized input fail before dispatch',async()=>{
  expect((await h.post('/admin/pairings',{}, {Origin:'https://evil.example',Authorization:'Bearer '+ownerKey})).status).toBe(403);
  expect((await h.get('/device/pair',{method:'POST',body:'x'.repeat(20000)})).status).toBe(413);
 });
 test('Chrome-compatible owner form login keeps strict same-origin protection',async()=>{
  // Referrer-Policy: no-referrer makes browsers send Origin: null for form POSTs.
  // Keep the origin check instead of whitelisting null; use same-origin on HTML pages.
  const page=await h.get('/setup');
  expect(page.status).toBe(200);
  expect(page.headers.get('referrer-policy')).toBe('same-origin');
  const headers={'Content-Type':'application/x-www-form-urlencoded',Origin:origin};
  const form=new URLSearchParams({owner_key:ownerKey}).toString();
  const accepted=await h.get('/setup',{method:'POST',headers,body:form,redirect:'manual'});
  expect(accepted.status).toBe(303);
  expect(accepted.headers.get('set-cookie')).toContain('__Host-newpipe-admin=');
  const cookie=accepted.headers.get('set-cookie')?.split(';')[0]??'';
  const ownerPage=await h.get('/setup',{headers:{Cookie:cookie}});
  expect(ownerPage.status).toBe(200);
  expect(await ownerPage.text()).toContain('Create a five-minute pairing code');
  for(const untrusted of ['null','https://evil.example']){
   const denied=await h.get('/setup',{method:'POST',headers:{...headers,Origin:untrusted},body:form});
   expect(denied.status).toBe(403);
   expect(await denied.text()).toContain('INVALID_ORIGIN');
  }
 });
 test('discovery identifies the protected MCP resource',async()=>{
  const challenge=await h.post('/mcp',{jsonrpc:'2.0',id:1,method:'tools/list'},{Accept:'application/json, text/event-stream'});
  const metadata=challenge.headers.get('www-authenticate')?.match(/resource_metadata="([^"]+)"/)?.[1];expect(metadata).toBeTruthy();
  const r=await h.get(new URL(metadata).pathname);expect(r.status).toBe(200);expect((await r.json()).resource).toBe(origin+'/mcp');
 });
 test('OAuth discovery requests all device tool scopes, but deletion needs explicit consent',async()=>{
  // Simulate an MCP client that discovers its requested scopes from the resource
  // challenge. Previously it saw only mcp.connect and got unusable credentials.
  const challenge=await h.post('/mcp',{jsonrpc:'2.0',id:1,method:'tools/list'},{Accept:'application/json, text/event-stream'});
  const header=challenge.headers.get('www-authenticate')??'';
  const hint=header.match(/(?:^|, )scope="([^"]+)"/)?.[1];
  expect(hint).toBeTruthy();
  const requested=hint.split(' ');
  const core=['mcp.connect','library.read','library.write','playlist.write','playback.control'];
  const destructive=['playlist.delete','library.delete'];
  expect(requested).toEqual(expect.arrayContaining([...core,...destructive]));
  expect(requested).not.toContain('offline_access');
  const metadata=header.match(/resource_metadata="([^"]+)"/)?.[1];
  expect(metadata).toBeTruthy();
  const document=await h.get(new URL(metadata).pathname);
  expect(document.status).toBe(200);
  expect((await document.json()).scopes_supported).toEqual(expect.arrayContaining([...core,...destructive]));
  // A valid owner deliberately chooses all core access and leaves deletes off.
  const grant=await h.oauth(requested,core);
  expect(grant.scope.split(' ').sort()).toEqual([...core].sort());
  for(const scope of destructive) expect(grant.scope.split(' ')).not.toContain(scope);
  const checkboxes=[...grant.consentHtml.matchAll(/<input type="checkbox"[^>]+>/g)].map(m=>m[0]);
  for(const scope of destructive){
   const option=checkboxes.find(x=>x.includes('value="'+scope+'"'));
   expect(option).toBeTruthy();
   expect(option).not.toContain('checked');
  }
  for(const scope of core.filter(x=>x!=='mcp.connect')){
   expect(checkboxes.find(x=>x.includes('value="'+scope+'"'))).toContain('checked');
  }
  const d=await h.pairedDevice();
  const ws=await h.connectDevice(d);
  try {
   const read=await h.call(grant.access_token,'get_catalog_status',{device_id:d.id});
   expect(read.data.code).toBe('OK');
   const playback=await h.call(grant.access_token,'get_now_playing',{device_id:d.id});
   expect(playback.data.code).toBe('OK');
   const write=await h.call(grant.access_token,'create_playlist',{device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Consent QA only'});
   expect(write.data.outcome).toBe('COMMITTED');
   // Populating the playlist requires library.write AND playlist.write, not just the latter.
   const example={stream_id:1,service:'YouTube',url:'https://www.youtube.com/watch?v=x'};
   const populated=await h.call(grant.access_token,'create_playlist',{device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Populated consent QA',items:[example]});
   expect(populated.data.outcome).toBe('COMMITTED');
   const deletion=await h.call(grant.access_token,'delete_playlist',{device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),playlist_id:17});
   expect(deletion.data.code).toBe('PERMISSION_DENIED');
   const remove=await h.call(grant.access_token,'remove_library_items',{device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),items:[{stream_id:1,service:'YouTube',url:'https://www.youtube.com/watch?v=x'}]});
   expect(remove.data.code).toBe('PERMISSION_DENIED');
  } finally {ws.close();}
  const withoutLibraryWrite=await h.oauth(requested,core.filter(s=>s!=='library.write'));
  const deniedWrite=await h.call(withoutLibraryWrite.access_token,'create_playlist',{
   device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Denied populated',
   items:[{stream_id:1,service:'YouTube',url:'https://www.youtube.com/watch?v=x'}]
  });
  expect(deniedWrite.data.code).toBe('PERMISSION_DENIED');
  const optedIn=await h.oauth(requested,[...core,'playlist.delete']);
  expect(optedIn.scope.split(' ').sort()).toEqual([...core,'playlist.delete'].sort());
 },20000);
 test('OAuth PKCE, browser-bound setup consent and authenticated tools work',async()=>{
  const credentials=await h.oauth();
  const listed=await h.rpc(credentials.access_token,'tools/list',{});expect(listed.status).toBe(200);expect(listed.value.result.tools.some(t=>t.name==='remove_library_items')).toBe(true);
  const devices=await h.call(credentials.access_token,'list_devices',{});expect(Array.isArray(devices.data.devices)).toBe(true);
  const reused=await h.get('/authorize',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',Cookie:credentials.consentCookie,Origin:origin},body:new URLSearchParams({handle:credentials.consentHandle,owner_key:ownerKey,decision:'allow'}).toString()});
  expect(reused.headers.get('location')??'').not.toMatch(/[?&]code=/);
 },15000);
 test('unpaired devices cannot get a connection challenge',async()=>{
  const r=await h.post('/device/challenge',{device_id:randomUUID(),client_nonce:randomBytes(24).toString('base64url'),timestamp_ms:Date.now(),signature:'not-a-signature'});expect(r.status).toBe(401);
 });
 test('device pairing requires private-key proof and allows only an identical retry',async()=>{
  const d=await h.pairedDevice();expect((await h.post('/device/pair',d.registration)).status).toBe(200);
  expect((await h.post('/device/pair',{...d.registration,signature:'invalid'})).status).toBe(401);
 });
 test('OAuth scopes are enforced independently of phone state',async()=>{
  const credentials=await h.oauth(['mcp.connect','library.read']),d=await h.pairedDevice();
  const r=await h.call(credentials.access_token,'create_playlist',{device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Denied'});expect(r.data.code).toBe('PERMISSION_DENIED');
 },15000);
 test('offline commands never replay on reconnect and live mutations have one effect',async()=>{
  const credentials=await h.oauth(),d=await h.pairedDevice();
  const args={device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Offline'};
  const offline=await h.call(credentials.access_token,'create_playlist',args);expect(offline.data.code).toBe('DEVICE_OFFLINE');
  let effects=0;const ws=await h.connectDevice(d,()=>{effects++;});
  try {
   expect((await h.call(credentials.access_token,'create_playlist',args)).data.code).toBe('DEVICE_OFFLINE');expect(effects).toBe(0);
   const online={device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Online'};
   expect((await h.call(credentials.access_token,'create_playlist',online)).data.outcome).toBe('COMMITTED');expect(effects).toBe(1);
   expect((await h.call(credentials.access_token,'create_playlist',online)).data.replayed).toBe(true);expect(effects).toBe(1);
   expect((await h.call(credentials.access_token,'create_playlist',{...online,name:'Changed'})).data.code).toBe('REQUEST_ID_REUSED');expect(effects).toBe(1);
  } finally {ws.close();}
 },15000);
 test('disconnect after dispatch reports unknown without resending',async()=>{
  const credentials=await h.oauth(),d=await h.pairedDevice();let count=0;
  const ws=await h.connectDevice(d,(_,socket)=>{count++;socket.close();return false;});
  const args={device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Uncertain'};
  const r=await h.call(credentials.access_token,'create_playlist',args);expect(r.data.outcome).toBe('OUTCOME_UNKNOWN');
  expect((await h.call(credentials.access_token,'create_playlist',args)).data.outcome).toBe('OUTCOME_UNKNOWN');expect(count).toBe(1);
 },15000);
 test('revoked clients lose access even with a previously issued token',async()=>{
  const credentials=await h.oauth(),client_id='c_'+digest(credentials.client_id);
  expect((await h.post('/admin/revoke-client',{client_id},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
  expect((await h.call(credentials.access_token,'list_devices',{})).data.code).toBe('CLIENT_REVOKED');
 },15000);
});
