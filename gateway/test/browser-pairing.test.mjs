import {beforeAll,afterAll,beforeEach,describe,test,expect} from 'vitest';
import {generateKeyPairSync,randomBytes,randomUUID,sign} from 'node:crypto';
import {harness,origin,owner,ownerKey,digest,field,cookies,mergeCookies} from './helpers.mjs';
let h,store,deviceNamespace;
beforeAll(async()=>{
 h=await harness({}, {scriptPath:'test/fixtures/pairing-state-worker.mjs'});
 const ns=await h.mf.getDurableObjectNamespace('ACCOUNT');store=ns.get(ns.idFromName(owner));
 deviceNamespace=await h.mf.getDurableObjectNamespace('DEVICES');
},30000);
afterAll(async()=>{await h?.mf.dispose();});
beforeEach(async()=>{await store.fetch('https://internal/__test/reset',{method:'POST'});});
const internal=async(path,value)=>(await store.fetch('https://internal'+path,value===undefined?{}:{method:'PUT',body:JSON.stringify(value)})).json();
const devices=()=>internal('/__test/devices').then(v=>v??[]);
async function ok(response,status=200) {expect(response.status,await response.clone().text()).toBe(status);return response.json();}
function phone(extra={}) {
 const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'});
 return {...keys,details:{pairing_version:1,device_id:randomUUID(),public_key:keys.publicKey.export({type:'spki',format:'der'}).toString('base64url'),
  client_nonce:randomBytes(32).toString('base64url'),issued_at_ms:Date.now(),name:'Synthetic pairing phone',permissions:['library.read','playback.control'],...extra}};
}
function message(operation,d,id='',target=origin) {
 return ['newpipe-pairing-v1',operation,target,d.device_id,d.public_key,d.client_nonce,String(d.issued_at_ms),d.name,d.permissions.join(','),id].join('\n');
}
function payload(p,operation,extra={},target=origin) {
 const d={...p.details,...extra},id=digest(message('begin',d));
 return {...d,...(operation==='begin'?{}:{request_id:id}),signature:sign('sha256',Buffer.from(message(operation,d,operation==='begin'?'':id,target)),p.privateKey).toString('base64url')};
}
async function send(p,operation,extra={}) {
 if(operation==='begin' && !p.nonceFetched) {
  p.details.client_nonce=(await ok(await h.get('/device/pairing/nonce'))).client_nonce;
  p.nonceFetched=true;
 }
 return h.post('/device/pairing/'+operation,payload(p,operation,extra));
}
async function begin(p=phone()) {const value=await ok(await send(p,'begin'));p.result=value;return p;}
async function view(p,browser) {
 const r=await h.get('/pair/device?request='+p.result.request_id,{headers:{Cookie:browser.cookie}});
 const html=await r.text();return {status:r.status,html,fields:Object.fromEntries(['request_id','proof','csrf'].map(k=>[k,field(html,k)]))};
}
async function decide(browser,v,extra={},headers={}) {
 return h.get('/pair/device',{method:'POST',headers:{Cookie:browser.cookie,Origin:origin,'Content-Type':'application/x-www-form-urlencoded',...headers},
  body:new URLSearchParams({...v.fields,decision:'allow',comparison_confirmed:'1',...extra}).toString()});
}
async function approve(p,browser=undefined) {
 browser??=await h.ownerLogin();const v=await view(p,browser);expect(v.status,v.html).toBe(200);
 const r=await decide(browser,v);expect(r.status,await r.clone().text()).toBe(200);return {browser,v};
}
const logout=browser=>h.post('/admin/logout',{csrf:browser.csrf},{Cookie:browser.cookie,Origin:origin});

describe('device-key-bound browser pairing',()=>{
 test('begin does not enroll a device and identical retries do not extend its deadline',async()=>{
  const p=await begin();expect(p.result).toMatchObject({status:'pending',paired:false,pairing_version:1,device_id:p.details.device_id,owner_id:owner});
  expect(p.result.request_id).toBe(digest(message('begin',p.details)));
  expect(p.result.comparison_code).toMatch(/^[0-9A-F]{4}-[0-9A-F]{4}$/);
  const again=await ok(await send(p,'begin'));expect(again).toEqual(p.result);expect(await devices()).toEqual([]);
  expect(JSON.stringify(p.result)).not.toContain('pairing_code');
 });
 test('unapproved finish cannot create a DeviceRoom identity',async()=>{
  const p=await begin();expect((await send(p,'finish')).status).toBe(401);expect(await devices()).toEqual([]);
 });
 test('status and finish require the same key, transcript, operation and origin',async()=>{
  const p=await begin(),stolen=payload(p,'status');
  const attacker=phone();stolen.signature=sign('sha256',Buffer.from(message('status',p.details,p.result.request_id)),attacker.privateKey).toString('base64url');
  expect((await h.post('/device/pairing/status',stolen)).status).toBe(401);
  expect((await h.post('/device/pairing/finish',payload(p,'status'))).status).toBe(401);
  expect((await h.post('/device/pairing/status',payload(p,'status',{},'https://evil.test'))).status).toBe(401);
  expect((await h.post('/device/pairing/status',{...payload(p,'status'),request_id:'a'.repeat(64)})).status).toBe(401);
  expect((await h.post('/device/pairing/status',{...payload(p,'status'),name:'Different phone'})).status).toBe(401);
  expect((await send(p,'status')).status).toBe(200);
 });
 test('rejects scope ambiguity, hidden controls, malformed keys and future begin timestamps',async()=>{
  for(const extra of [{permissions:['library.read','library.read']},{permissions:['playback.control','library.read']},
   {permissions:['run.shell']},{name:'name\nattack'},{name:'name\u202eattack'},{issued_at_ms:Date.now()+120000}]) {
   expect((await send(phone(extra),'begin')).status).toBe(400);
  }
  const p=phone();expect((await h.post('/device/pairing/begin',{...payload(p,'begin'),public_key:'A'.repeat(120)})).status).toBe(401);
 });
 test('anonymous approval links show sign-in, not a grant or an approval button',async()=>{
  const p=await begin(),r=await h.get('/pair/device?request='+p.result.request_id),html=await r.text();
  expect(r.status).toBe(200);expect(html).toContain('Sign in with passkey');expect(html).not.toContain('Approve this device</button>');
  expect(html).toContain('data-pairing="'+p.result.request_id+'"');expect(await devices()).toEqual([]);
 });
 test('emergency recovery returns only to the exact pairing page and does not approve',async()=>{
  const p=await begin(),r=await h.get('/pair/device?request='+p.result.request_id),html=await r.text(),cookie=cookies(r);
  const logged=await h.get('/setup',{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/x-www-form-urlencoded'},
   body:new URLSearchParams({owner_key:ownerKey,browser_csrf:field(html,'browser_csrf'),pairing:p.result.request_id})});
  expect(logged.status).toBe(303);expect(logged.headers.get('location')).toBe('/pair/device?request='+p.result.request_id);
  expect((await ok(await send(p,'status'))).status).toBe('pending');
  const bad=await h.get('/setup',{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/x-www-form-urlencoded'},
   body:new URLSearchParams({owner_key:ownerKey,browser_csrf:field(html,'browser_csrf'),pairing:'https://evil.test'})});
  expect(bad.status).toBe(400);
 });
 test('approval requires an exact Origin, session CSRF, immutable form proof and explicit comparison',async()=>{
  const p=await begin(),browser=await h.ownerLogin(),v=await view(p,browser);
  expect((await decide(browser,v,{}, {Origin:'https://evil.test'})).status).toBe(403);
  expect((await decide(browser,v,{}, {Origin:''})).status).toBe(403);
  expect((await decide(browser,v,{csrf:'wrong'})).status).toBe(401);
  const wrongProof = await decide(browser,v,{proof:'wrong'});
  expect(wrongProof.status, await wrongProof.clone().text()).toBe(403);
  expect((await decide(browser,v,{decision:''})).status).toBe(400);
  expect((await decide(browser,v,{comparison_confirmed:''})).status).toBe(400);
  expect((await ok(await send(p,'status'))).status).toBe('pending');expect(await devices()).toEqual([]);
 });
 test('names are escaped and the displayed comparison code matches the signed transaction',async()=>{
  const p=await begin(phone({name:'<script>alert(1)</script>'})),browser=await h.ownerLogin(),v=await view(p,browser);
  expect(v.html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');expect(v.html).not.toContain('<script>alert');
  expect(v.html).toContain(p.result.comparison_code);expect(v.html).toContain('library.read');
 });
 test('owner approval alone does not enroll; signed finish creates one identity with idempotent retries',async()=>{
  const p=await begin();await approve(p);expect(await devices()).toEqual([]);
  expect((await ok(await send(p,'status'))).status).toBe('approved');
  const result=await ok(await send(p,'finish'));expect(result).toMatchObject({paired:true,status:'complete',owner_id:owner,device_id:p.details.device_id});
  expect(await devices()).toHaveLength(1);expect((await devices())[0].pending).toBe(false);
  expect(await ok(await send(p,'finish'))).toEqual(result);expect(await devices()).toHaveLength(1);
 });
 test('an approval is single-use and cannot be silently widened',async()=>{
  const p=await begin(),{browser,v}=await approve(p);
  expect((await decide(browser,v)).status).toBe(409);
  const changed=payload(p,'finish',{permissions:['library.delete','library.read']});
  expect((await h.post('/device/pairing/finish',{...changed,request_id:p.result.request_id})).status).toBe(401);
  expect(await devices()).toEqual([]);
 });
 test('deny is available without comparison and cannot later be changed to allow',async()=>{
  const p=await begin(),browser=await h.ownerLogin(),v=await view(p,browser);
  expect((await decide(browser,v,{decision:'deny',comparison_confirmed:''})).status).toBe(200);
  expect((await send(p,'finish')).status).toBe(401);expect((await decide(browser,v)).status).toBe(409);
  expect((await ok(await send(p,'begin'))).status).toBe('denied');expect(await devices()).toEqual([]);
 });
 test.each(['pending','approved'])('signed cancellation in %s state preserves all existing registrations',async stage=>{
  const existing=await h.pairedDevice(),before=await devices(),p=await begin();
  if(stage==='approved') await approve(p);
  expect((await ok(await send(p,'cancel'))).status).toBe('cancelled');
  expect((await send(p,'finish')).status).toBe(401);expect(await devices()).toEqual(before);
  expect((await devices())[0].deviceId).toBe(existing.id);
 });
 test('logout after viewing invalidates both the form and a later re-login of that browser',async()=>{
  const p=await begin(),browser=await h.ownerLogin(),v=await view(p,browser);await logout(browser);
  expect((await decide(browser,v)).status).toBe(401);
  const replacement=await h.ownerLogin();expect((await view(p,replacement)).status).toBe(401);
  expect(await devices()).toEqual([]);
 });
 test('logout after approval prevents a not-yet-committed finish',async()=>{
  const p=await begin(),{browser}=await approve(p);await logout(browser);
  expect((await send(p,'finish')).status).toBe(401);expect(await devices()).toEqual([]);
 });
 test.each(['expiry','epoch'])('owner %s is rechecked at enrollment commit',async kind=>{
  const p=await begin();await approve(p);const state=await internal('/__test/owner');
  if(kind==='expiry') state.sessions.forEach(s=>s.expiresAt=Date.now()-1);else state.epoch++;
  await internal('/__test/owner',state);expect((await send(p,'finish')).status).toBe(401);expect(await devices()).toEqual([]);
 });
 test('expired pending records cannot be approved or completed and stale begin does not create new work',async()=>{
  const p=await begin(),{browser,v}=await approve(p),pairs=await internal('/__test/pairs');
  pairs[0].expiresAt=Date.now()-1;pairs[0].retainUntil=Date.now()-1;await internal('/__test/pairs',pairs);
  expect((await send(p,'finish')).status).toBe(410);expect((await decide(browser,v)).status).toBe(410);
  expect((await send(phone({issued_at_ms:Date.now()-360000}),'begin')).status).toBe(400);expect(await devices()).toEqual([]);
 });
 test('simultaneous finishes converge on one registration',async()=>{
  const p=await begin();await approve(p);const results=await Promise.all([send(p,'finish'),send(p,'finish')]);
  for(const r of results) expect((await ok(r)).status).toBe('complete');expect(await devices()).toHaveLength(1);
 });
 test('finish versus cancel has one durable order and cancellation never claims to undo committed enrollment',async()=>{
  const p=await begin();await approve(p);
  const [finished,cancelled]=await Promise.all([send(p,'finish'),send(p,'cancel')]);
  const cancellation=await ok(cancelled),current=await devices();
  if(cancellation.status==='cancelled') {expect(finished.status).toBe(401);expect(current).toHaveLength(0);}
  else {expect(['committed','complete']).toContain(cancellation.status);expect(finished.status).toBe(200);expect(current).toHaveLength(1);}
 });
 test('failed cross-object registration is recoverable without renewing or re-authorizing the enrollment',async()=>{
  const p=await begin(),{browser}=await approve(p),room=deviceNamespace.get(deviceNamespace.idFromName(p.details.device_id));
  await room.fetch('https://internal/__test/fail-register',{method:'POST'});
  expect((await send(p,'finish')).status).toBe(503);expect((await devices())[0].pending).toBe(true);
  expect((await ok(await send(p,'status'))).status).toBe('committed');
  await logout(browser);expect((await ok(await send(p,'finish'))).status).toBe('complete');
  expect(await devices()).toHaveLength(1);expect((await devices())[0].pending).toBe(false);
 });
 test('revocation after a partial commit blocks recovery and cannot be undone by replay',async()=>{
  const p=await begin();await approve(p);const room=deviceNamespace.get(deviceNamespace.idFromName(p.details.device_id));
  await room.fetch('https://internal/__test/fail-register',{method:'POST'});expect((await send(p,'finish')).status).toBe(503);
  await ok(await h.post('/admin/revoke-device',{device_id:p.details.device_id},{Authorization:'Bearer '+ownerKey}));
  expect((await send(p,'finish')).status).toBe(401);expect((await devices())[0].revoked).toBe(true);
 });
 test('completed cancellation reports completion; completed retry cannot re-enroll a revoked device',async()=>{
  const p=await begin();await approve(p);await ok(await send(p,'finish'));
  expect((await ok(await send(p,'cancel'))).status).toBe('complete');
  await ok(await h.post('/admin/revoke-device',{device_id:p.details.device_id},{Authorization:'Bearer '+ownerKey}));
  expect((await send(p,'finish')).status).toBe(401);expect((await send(p,'begin')).status).toBe(401);
 });
 test('a matching existing identity is not renamed or reset by browser enrollment',async()=>{
  const old=await h.pairedDevice(),before=await devices();
  const p=phone({device_id:old.id,name:'Different pending name',public_key:old.registration.public_key});p.privateKey=old.privateKey;
  await begin(p);await approve(p);expect((await ok(await send(p,'finish'))).paired).toBe(true);expect(await devices()).toEqual(before);
  expect((await send(phone({device_id:old.id}),'begin')).status).toBe(401);
 });
 test('anonymous request churn cannot evict already-viewed or approved owner requests',async()=>{
  const p=await begin(),browser=await h.ownerLogin();await view(p,browser);
  const approved=await begin();await approve(approved,browser);
  for(let i=0;i<35;i++) await begin();
  const pairs=await internal('/__test/pairs');expect(pairs).toHaveLength(34);
  expect(pairs.some(x=>x.id===p.result.request_id)).toBe(true);expect(pairs.some(x=>x.id===approved.result.request_id)).toBe(true);
 });
 test('anonymous cancellation churn remains bounded and cannot reserve all owner slots',async()=>{
  const protectedPhone=await begin(),browser=await h.ownerLogin();await view(protectedPhone,browser);
  const cancelled=[];
  for(let i=0;i<70;i++) {const p=await begin();await ok(await send(p,'cancel'));cancelled.push(p);}
  const pairs=await internal('/__test/pairs');expect(pairs).toHaveLength(33);
  expect(pairs.some(p=>p.id===protectedPhone.result.request_id)).toBe(true);
  const fresh=await begin();expect(fresh.result.status).toBe('pending');
  const replay=await send(cancelled[0],'begin');expect(replay.status).toBe(409);
  expect((await replay.json()).error).toBe('PAIRING_RESTART_REQUIRED');
 });
 test('evicted pending begin cannot be recreated with a renewed deadline',async()=>{
  const original=await begin();
  for(let i=0;i<33;i++) await begin();
  expect((await send(original,'status')).status).toBe(410);
  const retried=await send(original,'begin');expect(retried.status).toBe(409);
  expect((await retried.json()).error).toBe('PAIRING_RESTART_REQUIRED');
  expect((await internal('/__test/pairs')).some(p=>p.id===original.result.request_id)).toBe(false);
 });
 test('nonce generation rotation does not invalidate a retained owner-viewed request',async()=>{
  const retained=await begin(),browser=await h.ownerLogin(),v=await view(retained,browser);
  for(let i=0;i<35;i++) await begin();
  expect(await ok(await send(retained,'begin'))).toEqual(retained.result);
  expect((await decide(browser,v)).status).toBe(200);
  expect((await ok(await send(retained,'finish'))).paired).toBe(true);
 });
 test('device registration persisted but lost response recovers exactly once after logout',async()=>{
  const p=await begin(),{browser}=await approve(p),room=deviceNamespace.get(deviceNamespace.idFromName(p.details.device_id));
  await room.fetch('https://internal/__test/register-mode',{method:'POST',body:JSON.stringify({mode:'fail-after'})});
  expect((await send(p,'finish')).status).toBe(503);
  const roomState=await (await room.fetch('https://internal/__test/register-state')).json();
  expect(roomState.registration.deviceId).toBe(p.details.device_id);expect(roomState.revoked).toBe(false);
  expect((await devices())[0].pending).toBe(true);
  await logout(browser);
  expect((await ok(await send(p,'finish'))).status).toBe('complete');
  expect(await devices()).toHaveLength(1);expect((await devices())[0].pending).toBe(false);
 });
 test.each(['hold-before','hold-after'])('device revoke wins across registration %s schedule',async mode=>{
  const p=await begin();await approve(p);const room=deviceNamespace.get(deviceNamespace.idFromName(p.details.device_id));
  await room.fetch('https://internal/__test/register-mode',{method:'POST',body:JSON.stringify({mode})});
  const finishing=send(p,'finish');
  try {
   let waiting=false;
   for(let n=0;n<100;n++) {
    waiting=(await (await room.fetch('https://internal/__test/register-state')).json()).waiting;
    if(waiting)break;await new Promise(resolve=>setTimeout(resolve,10));
   }
   expect(waiting).toBe(true);
   await ok(await h.post('/admin/revoke-device',{device_id:p.details.device_id},{Authorization:'Bearer '+ownerKey}));
  } finally {await room.fetch('https://internal/__test/release-register',{method:'POST'});}
  expect((await finishing).status).toBe(mode==='hold-before'?503:401);
  expect((await send(p,'finish')).status).toBe(401);
  const state=await (await room.fetch('https://internal/__test/register-state')).json();
  expect(state.revoked).toBe(true);expect((await devices())[0].revoked).toBe(true);
 });
 test('native input cannot inject owner authority to approve a request',async()=>{
  const p=await begin();expect((await h.post('/device/pairing/decide',{request_id:p.result.request_id,ownerAuthority:{},decision:'allow'})).status).toBe(404);
  expect((await h.get('/device/pairing/begin')).status).toBe(405);
  expect((await h.get('/pair/device?request=https://evil.test')).status).toBe(400);expect(await devices()).toEqual([]);
 });
});
