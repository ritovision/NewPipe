import {beforeAll,afterAll,beforeEach,describe,test,expect} from 'vitest';
import {createHash,randomBytes} from 'node:crypto';
import {harness,origin,owner,ownerKey,cookies,mergeCookies,field} from './helpers.mjs';
import {authenticator} from './webauthn-fixtures.mjs';
let h,store;
beforeAll(async()=>{
 h=await harness({}, {scriptPath:'test/fixtures/auth-state-worker.mjs'});
 const ns=await h.mf.getDurableObjectNamespace('ACCOUNT');store=ns.get(ns.idFromName(owner));
},30000);
afterAll(async()=>{await h?.mf.dispose();});
beforeEach(async()=>{await store.fetch('https://internal/__test/owner-state',{method:'DELETE'});});
const readState=async()=> (await store.fetch('https://internal/__test/owner-state')).json();
const writeState=async state=>store.fetch('https://internal/__test/owner-state',{method:'PUT',body:JSON.stringify(state)});
const authPost=(browser,path,data={},extra={})=>h.post('/auth'+path,{csrf:browser.csrf,browser_csrf:browser.browser_csrf,...data},{Cookie:browser.cookie,Origin:origin,...extra});
async function ok(response,status=200) {expect(response.status,await response.clone().text()).toBe(status);return response.json();}
async function page(browser) {
 const result=await h.get('/setup',{headers:{Cookie:browser.cookie}});expect(result.status).toBe(200);
 const html=await result.text();browser.html=html;browser.csrf=field(html,'csrf');return html;
}
async function add(browser,key=authenticator(),label='Synthetic passkey',permit) {
 const request=await ok(await authPost(browser,'/register/options',{label,permit}));
 expect(request.options.authenticatorSelection.userVerification).toBe('required');
 expect(request.options.authenticatorSelection.residentKey).toBe('required');expect(request.options.rp.id).toBe('newpipe.test');
 const response=await authPost(browser,'/register/finish',{challengeId:request.challengeId,response:key.register(request.options)});
 await ok(response);return key;
}
async function seeded(key=authenticator()) {const browser=await h.ownerLogin();await add(browser,key);return {browser,key};}
async function login(key,browser,overrides={}) {
 browser??=await h.newBrowser();
 const request=await ok(await authPost(browser,'/login/options'));
 expect(request.options.userVerification).toBe('required');expect(request.options.allowCredentials??[]).toEqual([]);
 const result=await authPost(browser,'/login/finish',{challengeId:request.challengeId,response:key.assert(request.options,overrides)});
 await ok(result);expect(result.headers.get('set-cookie')).toContain('HttpOnly');
 browser.cookie=mergeCookies(browser.cookie,cookies(result));await page(browser);return browser;
}
async function fresh(browser,key,action,target) {
 const request=await ok(await authPost(browser,'/stepup/options',{action,target}));
 return (await ok(await authPost(browser,'/stepup/finish',{challengeId:request.challengeId,response:key.assert(request.options)}))).permit;
}
const recoveryPermit=async(browser,action,target)=>(await ok(await authPost(browser,'/recovery-stepup',{owner_key:ownerKey,action,target}))).permit;
async function begin(browser,scopes=['mcp.connect','library.read','library.delete']) {
 const client=await ok(await h.post('/oauth/register',{client_name:'Passkey QA',redirect_uris:['https://example.com/callback'],grant_types:['authorization_code','refresh_token'],response_types:['code'],token_endpoint_auth_method:'none'}),201);
 const verifier=randomBytes(32).toString('base64url');
 const query=new URLSearchParams({response_type:'code',client_id:client.client_id,redirect_uri:'https://example.com/callback',scope:scopes.join(' '),state:'passkey-state',code_challenge:createHash('sha256').update(verifier).digest('base64url'),code_challenge_method:'S256',resource:origin+'/mcp'});
 const result=await h.get('/authorize?'+query,{headers:{Cookie:browser.cookie}});expect(result.status,await result.clone().text()).toBe(200);
 browser.cookie=mergeCookies(browser.cookie,cookies(result));const html=await result.text();
 const parse=html=>Object.fromEntries(['flow','handle','scope_proof','csrf'].map(name=>[name,field(html,name)??'']));
 return {browser,client,verifier,html,values:parse(html),parse,csp:result.headers.get('content-security-policy')};
}
async function consent(info,extra={},scopes=['library.read'],headers={}) {
 const values=new URLSearchParams({...info.values,decision:'allow',...extra});scopes.forEach(s=>values.append('scope',s));
 return h.get('/authorize',{method:'POST',headers:{Cookie:info.browser.cookie,Origin:origin,'Content-Type':'application/x-www-form-urlencoded',...headers},body:values.toString()});
}
const logout=browser=>h.post('/admin/logout',{csrf:browser.csrf},{Cookie:browser.cookie,Origin:origin});

describe('passkey ownership with real WebAuthn cryptography',()=>{
 test('bootstrap enrolls a discoverable verified credential and stores only public verification data',async()=>{
  const browser=await h.ownerLogin(),key=await add(browser);
  const state=await readState();expect(state.credentials).toHaveLength(1);
  expect(state.credentials[0]).toMatchObject({id:key.id,counter:0,deviceType:'multiDevice',backedUp:true});
  expect(JSON.stringify(state)).not.toContain(ownerKey);
  expect(JSON.stringify(state)).not.toContain(key.privateKey.export({type:'pkcs8',format:'pem'}));
  expect(state.sessions[0].expiresAt-state.sessions[0].createdAt).toBe(3600000);
  expect(await page(browser)).toContain('Synthetic passkey');
 });
 test('a returning owner signs in without an owner key and no OAuth grant is created by login',async()=>{
  const {key}=await seeded(),kv=await h.mf.getKVNamespace('OAUTH_KV');
  const before=(await kv.list({prefix:'grant:'})).keys;
  const browser=await login(key);expect(browser.csrf).toBeTruthy();expect(browser.html).toContain('Add passkey');
  expect((await kv.list({prefix:'grant:'})).keys).toEqual(before);
  const state=await readState();expect(state.sessions.at(-1).credentialId).toBe(key.id);
 });
 test('RS256 credentials complete real registration and signature verification',async()=>{
  const {key}=await seeded(authenticator({algorithm:'RS256'}));
  const browser=await login(key);expect(browser.csrf).toBeTruthy();
  expect((await readState()).credentials[0].id).toBe(key.id);
 });
 test('synced passkeys with a zero counter work repeatedly but each challenge is single-use',async()=>{
  const {key}=await seeded(),browser=await h.newBrowser();
  const request=await ok(await authPost(browser,'/login/options'));
  const payload={challengeId:request.challengeId,response:key.assert(request.options)};
  expect((await authPost(browser,'/login/finish',payload)).status).toBe(200);
  expect((await authPost(browser,'/login/finish',payload)).status).toBe(401);
  await login(key);expect((await readState()).credentials[0].counter).toBe(0);
 });
 test('simultaneous reuse of a valid assertion succeeds exactly once',async()=>{
  const {key}=await seeded(),browser=await h.newBrowser(),request=await ok(await authPost(browser,'/login/options'));
  const payload={challengeId:request.challengeId,response:key.assert(request.options)};
  const results=await Promise.all([authPost(browser,'/login/finish',payload),authPost(browser,'/login/finish',payload)]);
  expect(results.map(r=>r.status).sort()).toEqual([200,401]);
 });
 test.each([
  ['missing user verification',{flags:0x19}],['missing user presence',{flags:0x1c}],
  ['wrong RP hash',{rp:'evil.example'}],['wrong origin',{clientData:{origin:'https://evil.example'}}],
  ['wrong challenge',{clientData:{challenge:'invalid-challenge'}}],['wrong response type',{clientData:{type:'webauthn.create'}}],
  ['iframe without top origin',{clientData:{crossOrigin:true}}],['unexpected top origin',{clientData:{topOrigin:origin}}],
  ['wrong user handle',{userHandle:Buffer.from('different-owner').toString('base64url')}],
 ])('rejects authentication with %s and consumes the challenge',async(_name,overrides)=>{
  const {key}=await seeded(),browser=await h.newBrowser(),request=await ok(await authPost(browser,'/login/options'));
  const result=await authPost(browser,'/login/finish',{challengeId:request.challengeId,response:key.assert(request.options,overrides)});
  expect([400,401]).toContain(result.status);
  expect((await authPost(browser,'/login/finish',{challengeId:request.challengeId,response:key.assert(request.options)})).status).toBe(401);
 });
 test('rejects a signature from another key even when credential ID and all other fields match',async()=>{
  const {key}=await seeded(),browser=await h.newBrowser(),request=await ok(await authPost(browser,'/login/options'));
  expect((await authPost(browser,'/login/finish',{challengeId:request.challengeId,response:key.assert(request.options,{privateKey:authenticator().privateKey})})).status).toBe(401);
 });
 test('single-device counters must advance and backup eligibility cannot change',async()=>{
  const {key}=await seeded(authenticator({counter:1,synced:false}));
  await login(key,undefined,{counter:2});
  const browser=await h.newBrowser(),request=await ok(await authPost(browser,'/login/options'));
  expect((await authPost(browser,'/login/finish',{challengeId:request.challengeId,response:key.assert(request.options,{counter:2})})).status).toBe(401);
  const next=await ok(await authPost(browser,'/login/options'));
  expect((await authPost(browser,'/login/finish',{challengeId:next.challengeId,response:key.assert(next.options,{counter:3,flags:0x1d})})).status).toBe(401);
 });
 test.each([
  ['no verification',{flags:0x59}],['no presence',{flags:0x5c}],['wrong RP',{rp:'other.example'}],
  ['wrong origin',{clientData:{origin:'https://other.example'}}],['iframe',{clientData:{crossOrigin:true}}],
 ])('rejects registration with %s',async(_name,overrides)=>{
  const browser=await h.ownerLogin(),key=authenticator(),request=await ok(await authPost(browser,'/register/options',{label:'Invalid'}));
  const result=await authPost(browser,'/register/finish',{challengeId:request.challengeId,response:key.register(request.options,overrides)});
  expect([400,401]).toContain(result.status);expect((await readState()).credentials).toHaveLength(0);
 });
 test('requires exact Origin, bound CSRF and owner authority, not user-supplied internal flags',async()=>{
  const browser=await h.newBrowser();
  expect((await authPost(browser,'/register/options',{label:'Injected',recoveryVerified:true})).status).toBe(401);
  const logged=await h.ownerLogin();
  for(const Origin of [undefined,'null','https://evil.example']) {
   const headers={Cookie:logged.cookie};if(Origin!==undefined)headers.Origin=Origin;
   expect((await h.post('/auth/register/options',{csrf:logged.csrf,label:'Rejected'},headers)).status).toBe(403);
  }
  expect((await authPost(logged,'/register/options',{csrf:'wrong',label:'Rejected'})).status).toBe(401);
  expect((await authPost(logged,'/recovery-stepup',{owner_key:'wrong',recoveryVerified:true,action:'register',target:'Rejected'})).status).toBe(401);
 });
 test('a different browser cannot use a challenge, and cannot invalidate the original browser request',async()=>{
  const {key}=await seeded(),a=await h.newBrowser(),b=await h.newBrowser(),request=await ok(await authPost(a,'/login/options'));
  const payload={challengeId:request.challengeId,response:key.assert(request.options)};
  expect((await authPost(b,'/login/finish',payload)).status).toBe(401);
  expect((await authPost(a,'/login/finish',payload)).status).toBe(200);
 });
 test('expired challenges and expired browser sessions are rejected without changing credentials',async()=>{
  const {browser,key}=await seeded(),anon=await h.newBrowser(),request=await ok(await authPost(anon,'/login/options'));
  let state=await readState();state.challenges.find(c=>c.id===request.challengeId).expiresAt=Date.now()-1;await writeState(state);
  expect((await authPost(anon,'/login/finish',{challengeId:request.challengeId,response:key.assert(request.options)})).status).toBe(401);
  state=await readState();state.sessions.forEach(s=>s.expiresAt=Date.now()-1);await writeState(state);
  expect(await page(browser)).toContain('Sign in with passkey');expect((await readState()).credentials).toHaveLength(1);
 });
 test('second enrollment requires a fresh action-bound verification; a permit cannot be reused or retargeted',async()=>{
  const {browser,key}=await seeded();
  expect((await authPost(browser,'/register/options',{label:'Second'})).status).toBe(400);
  const permit=await fresh(browser,key,'register','Second');
  expect((await authPost(browser,'/register/options',{label:'Different',permit})).status).toBe(401);
  await add(browser,authenticator(),'Second',permit);
  expect((await authPost(browser,'/register/options',{label:'Second',permit})).status).toBe(401);
  expect((await readState()).credentials).toHaveLength(2);
 });
 test('revocation invalidates credential-backed sessions and pending authorization for adding a passkey',async()=>{
  const {browser,key}=await seeded();
  const second=await add(browser,authenticator(),'Second',await recoveryPermit(browser,'register','Second'));
  const live=await login(key),permit=await fresh(live,key,'register','Pending');
  const pending=await ok(await authPost(live,'/register/options',{label:'Pending',permit}));
  const remove=await fresh(browser,second,'remove',key.id);
  await ok(await authPost(browser,'/credentials/remove',{credentialId:key.id,permit:remove}));
  expect(await page(live)).toContain('Sign in with passkey');
  expect((await authPost(live,'/register/finish',{challengeId:pending.challengeId,response:authenticator().register(pending.options)})).status).toBe(401);
  expect((await readState()).credentials.map(c=>c.id)).toEqual([second.id]);
 });
 test('revoking a fresh authorizer invalidates enrollment even when its owner session uses a different live passkey',async()=>{
  const {browser,key}=await seeded();const second=await add(browser,authenticator(),'Second',await recoveryPermit(browser,'register','Second'));
  const live=await login(key),permit=await fresh(live,second,'register','Pending');
  const pending=await ok(await authPost(live,'/register/options',{label:'Pending',permit}));
  await ok(await authPost(browser,'/credentials/remove',{credentialId:second.id,permit:await recoveryPermit(browser,'remove',second.id)}));
  expect(await page(live)).toContain('Add passkey');
  expect((await authPost(live,'/register/finish',{challengeId:pending.challengeId,response:authenticator().register(pending.options)})).status).toBe(401);
  expect((await readState()).credentials.map(c=>c.id)).toEqual([key.id]);
 });
 test('concurrent registration and authorizer revocation have a single serialized authority order',async()=>{
  const {browser,key}=await seeded();const second=await add(browser,authenticator(),'Second',await recoveryPermit(browser,'register','Second'));
  const live=await login(key),permit=await fresh(live,second,'register','Racing');
  const pending=await ok(await authPost(live,'/register/options',{label:'Racing',permit}));
  const remove=await recoveryPermit(browser,'remove',second.id),newKey=authenticator(),auditBefore=(await readState()).audit.length;
  const [finished,removed]=await Promise.all([
   authPost(live,'/register/finish',{challengeId:pending.challengeId,response:newKey.register(pending.options)}),
   authPost(browser,'/credentials/remove',{credentialId:second.id,permit:remove})]);
  expect(removed.status).toBe(200);expect([200,401]).toContain(finished.status);
  const state=await readState(),events=state.audit.slice(auditBefore).map(e=>e.event);
  expect(state.credentials.some(c=>c.id===second.id)).toBe(false);
  expect(state.credentials.some(c=>c.id===newKey.id)).toBe(finished.status===200);
  if(finished.status===200)expect(events.indexOf('passkey-added')).toBeLessThan(events.indexOf('passkey-removed'));
 });
 test('last-key removal requires fresh recovery proof and explicit confirmation; recovery still works afterward',async()=>{
  const {browser,key}=await seeded();
  let permit=await fresh(browser,key,'remove',key.id);
  expect((await authPost(browser,'/credentials/remove',{credentialId:key.id,permit,confirmLast:true})).status).toBe(409);
  permit=await recoveryPermit(browser,'remove',key.id);
  expect((await authPost(browser,'/credentials/remove',{credentialId:key.id,permit})).status).toBe(409);
  permit=await recoveryPermit(browser,'remove',key.id);
  await ok(await authPost(browser,'/credentials/remove',{credentialId:key.id,permit,confirmLast:true}));
  const recovered=await h.ownerLogin();await add(recovered);
  expect((await readState()).credentials).toHaveLength(1);
 });
 test('logout invalidates a copied session cookie and pending consent, without revoking an existing OAuth grant',async()=>{
  const {key}=await seeded(),live=await login(key),authorized=await h.oauth(['mcp.connect','library.read'],undefined,{browser:live});
  const pending=await begin(live);expect((await logout(live)).status).toBe(303);
  expect((await consent(pending)).status).toBe(401);expect(await page(live)).toContain('Sign in with passkey');
  expect((await h.rpc(authorized.access_token,'tools/list',{})).status).toBe(200);
  await login(key,live);expect((await consent(pending)).status).toBe(401);
 });
 test('recovery session reset invalidates old sessions/challenges/consents but preserves credentials and devices',async()=>{
  const {browser,key}=await seeded(),live=await login(key),pending=await begin(live),device=await h.pairedDevice();
  const before=await readState(),permit=await recoveryPermit(browser,'reset-sessions','');
  const result=await authPost(browser,'/sessions/reset',{permit,confirmReset:true});await ok(result);
  const after=await readState();expect(after.epoch).toBe(before.epoch+1);expect(after.credentials).toEqual(before.credentials);
  expect(after.consents).toHaveLength(0);expect(after.challenges).toHaveLength(0);expect(after.sessions).toHaveLength(1);
  expect((await consent(pending)).status).toBe(401);expect(await page(live)).toContain('Sign in with passkey');
  const list=await store.fetch('https://internal/list');expect((await list.json()).devices.some(d=>d.deviceId===device.id&&!d.revoked)).toBe(true);
 });
 test('bounded anonymous challenges do not grow without limit and expired entries are pruned',async()=>{
  await seeded();const browser=await h.newBrowser();
  for(let n=0;n<4;n++)await ok(await authPost(browser,'/login/options'));
  expect((await authPost(browser,'/login/options')).status).toBe(429);
  const state=await readState();expect(state.challenges).toHaveLength(4);state.challenges.forEach(c=>c.expiresAt=Date.now()-1);await writeState(state);
  await store.fetch('https://internal/__test/alarm');expect((await readState()).challenges).toHaveLength(0);
  await ok(await authPost(browser,'/login/options'));
 });
 test('cancelled bootstrap enrollment can retry without reentering recovery, but a submitted ceremony cannot be reopened',async()=>{
  const browser=await h.ownerLogin(),key=authenticator();
  const request=await ok(await authPost(browser,'/register/options',{label:'Cancelled'}));
  const cancelled=await ok(await authPost(browser,'/challenge/cancel',{challengeId:request.challengeId}));
  expect(cancelled).toEqual({cancelled:true,retryBootstrap:true});
  expect((await authPost(browser,'/register/finish',{challengeId:request.challengeId,response:key.register(request.options)})).status).toBe(401);
  await add(browser,key);
  const repeat=await ok(await authPost(browser,'/challenge/cancel',{challengeId:request.challengeId}));
  expect(repeat).toEqual({cancelled:false,retryBootstrap:false});
  expect((await authPost(browser,'/register/options',{label:'Unauthorized extra'})).status).toBe(400);
 });
 test('cancelled login frees its challenge slot and does not authenticate or let another browser cancel a pending request',async()=>{
  const {key}=await seeded(),browser=await h.newBrowser(),other=await h.newBrowser();
  for(let n=0;n<6;n++) {
   const request=await ok(await authPost(browser,'/login/options'));
   expect((await ok(await authPost(other,'/login/cancel',{challengeId:request.challengeId}))).cancelled).toBe(false);
   expect((await ok(await authPost(browser,'/login/cancel',{challengeId:request.challengeId}))).cancelled).toBe(true);
   expect((await authPost(browser,'/login/finish',{challengeId:request.challengeId,response:key.assert(request.options)})).status).toBe(401);
  }
  expect(await page(browser)).toContain('Sign in with passkey');expect((await readState()).challenges).toHaveLength(0);
 });
 test('registration cannot extend the deadline of its original action-bound verification',async()=>{
  const {browser,key}=await seeded(),permit=await fresh(browser,key,'register','Deadline');
  const state=await readState(),deadline=Date.now()+1000;state.permits[0].expiresAt=deadline;await writeState(state);
  const request=await ok(await authPost(browser,'/register/options',{label:'Deadline',permit}));
  expect((await readState()).challenges.find(c=>c.id===request.challengeId).expiresAt).toBe(deadline);
 });
 test('expired step-up permits cannot authorize a new credential',async()=>{
  const {browser,key}=await seeded(),permit=await fresh(browser,key,'register','Expired');
  const state=await readState();state.permits.forEach(p=>p.expiresAt=Date.now()-1);await writeState(state);
  expect((await authPost(browser,'/register/options',{label:'Expired',permit})).status).toBe(401);
  expect((await readState()).credentials.map(c=>c.id)).toEqual([key.id]);
 });
 test('duplicate enrollment is rejected, and successful registration cannot be replayed',async()=>{
  const {browser,key}=await seeded(),permit=await recoveryPermit(browser,'register','Duplicate');
  const request=await ok(await authPost(browser,'/register/options',{label:'Duplicate',permit}));
  const payload={challengeId:request.challengeId,response:key.register(request.options)};
  expect((await authPost(browser,'/register/finish',payload)).status).toBe(409);
  expect((await authPost(browser,'/register/finish',payload)).status).toBe(401);
  expect((await readState()).credentials).toHaveLength(1);
 });
 test('changing owner epoch or stored expected origin invalidates pending assertions',async()=>{
  const {key}=await seeded(),browser=await h.newBrowser(),request=await ok(await authPost(browser,'/login/options'));
  let state=await readState();state.challenges[0].origin='https://different.example';await writeState(state);
  expect((await authPost(browser,'/login/finish',{challengeId:request.challengeId,response:key.assert(request.options)})).status).toBe(401);
  const second=await ok(await authPost(browser,'/login/options'));
  state=await readState();state.epoch+=1;await writeState(state);
  expect((await authPost(browser,'/login/finish',{challengeId:second.challengeId,response:key.assert(second.options)})).status).toBe(401);
 });
 test('legacy anonymous saturation is reclaimed for new login requests and recovery remains available',async()=>{
  const {key}=await seeded(),browser=await h.newBrowser();await ok(await authPost(browser,'/login/options'));
  const state=await readState(),sample=state.challenges[0];
  state.challenges=Array.from({length:64},()=>({...sample,id:randomBytes(32).toString('base64url'),browserHash:randomBytes(32).toString('hex')}));
  await writeState(state);await ok(await authPost(browser,'/login/options'));
  expect((await readState()).challenges.filter(c=>!c.sessionHash)).toHaveLength(32);
  const recovered=await h.ownerLogin();expect(recovered.csrf).toBeTruthy();expect((await readState()).challenges).toHaveLength(32);
  const permit=await recoveryPermit(recovered,'reset-sessions','');await ok(await authPost(recovered,'/sessions/reset',{permit,confirmReset:true}));
  expect((await readState()).challenges).toHaveLength(0);await login(key);
 });
 test('anonymous login allocation through public endpoints cannot evict or block an owner registration',async()=>{
  const {browser,key}=await seeded();
  const permit=await fresh(browser,key,'register','Protected registration');
  const pending=await ok(await authPost(browser,'/register/options',{label:'Protected registration',permit}));
  for(let binding=0;binding<10;binding++) {
   const attacker=await h.newBrowser();
   for(let n=0;n<4;n++)await ok(await authPost(attacker,'/login/options'));
  }
  const state=await readState();expect(state.challenges.filter(c=>!c.sessionHash)).toHaveLength(32);
  expect(state.challenges.some(c=>c.id===pending.challengeId&&c.sessionHash)).toBe(true);
  await ok(await authPost(browser,'/register/finish',{challengeId:pending.challengeId,response:authenticator().register(pending.options)}));
  const next=await fresh(browser,key,'register','Still available');expect(next).toBeTruthy();
  const incoming=await h.newBrowser();await login(key,incoming);
 });
 test('credential capacity cannot be exceeded and verified recovery at session capacity evicts only the oldest session',async()=>{
  const {browser,key}=await seeded();let state=await readState(),credential=state.credentials[0],session=state.sessions[0];
  state.credentials=Array.from({length:16},(_,n)=>({...credential,id:n?randomBytes(32).toString('base64url'):key.id}));
  state.sessions=Array.from({length:32},(_,n)=>({...session,hash:n?randomBytes(32).toString('hex'):session.hash,
    browserHash:n?randomBytes(32).toString('hex'):session.browserHash,createdAt:session.createdAt+n}));
  await writeState(state);
  expect((await authPost(browser,'/register/options',{label:'Too many'})).status).toBe(409);
  const oldest=state.sessions[0].hash,recovered=await h.ownerLogin();state=await readState();
  expect(state.sessions).toHaveLength(32);expect(state.sessions.some(s=>s.hash===oldest)).toBe(false);expect(recovered.csrf).toBeTruthy();
  expect(state.credentials).toHaveLength(16);
 });
 test('malformed and oversized responses fail closed; scripts are local and pages forbid framing/foreign forms',async()=>{
  const {key}=await seeded(),browser=await h.newBrowser(),request=await ok(await authPost(browser,'/login/options'));
  expect((await authPost(browser,'/login/finish',{challengeId:request.challengeId,response:{}})).status).toBe(400);
  expect((await authPost(browser,'/login/finish',{challengeId:request.challengeId,response:'x'.repeat(70000)})).status).toBe(413);
  const result=await h.get('/setup');const csp=result.headers.get('content-security-policy');
  expect(csp).toContain("script-src 'self'");expect(csp).toContain("form-action 'self'");expect(csp).toContain("frame-ancestors 'none'");
  expect(result.headers.get('cache-control')).toBe('no-store');expect(await result.text()).toContain('src="/auth/passkeys.js"');
  const asset=await h.get('/auth/passkeys.js');expect(asset.status).toBe(200);expect(asset.headers.get('content-type')).toContain('javascript');
  expect(await asset.text()).not.toContain(ownerKey);await login(key);
 });
});

describe('passkey OAuth consent remains explicit, single-use and browser-bound',()=>{
 test('already signed-in owner sees scopes and consent without an owner-key field',async()=>{
  const {key}=await seeded(),browser=await login(key),info=await begin(browser);
  expect(info.html).not.toContain('name="owner_key"');expect(info.html).toContain('value="library.read"');
  expect(info.html.match(/<input type="checkbox"[^>]*value="library.delete"[^>]*>/)?.[0]).not.toContain('checked');
  expect((await consent(info,{decision:''})).status).toBe(400);
  expect((await consent(info,{csrf:'incorrect'})).status).toBe(401);
  const granted=await consent(info);expect(granted.status).toBe(302);expect(granted.headers.get('location')).toContain('code=');
  expect((await consent(info)).status).toBe(401);
 });
 test('consent CSP permits only self and the provider-validated callback origin',async()=>{
  const {key}=await seeded(),info=await begin(await login(key));
  expect(info.csp).toContain("form-action 'self' https://example.com;");
  expect(info.csp).not.toContain('*');
  expect((await h.get('/setup')).headers.get('content-security-policy')).toContain("form-action 'self';");
 });
 test.each(['https://*/callback','https://example.com;sandbox/callback',
  'https://example.com,evil/callback','https://*.example.com/callback'])('rejects a provider-registered callback with CSP syntax: %s',async(redirect)=>{
  const browser=await h.newBrowser();
  const client=await ok(await h.post('/oauth/register',{client_name:'Untrusted callback',redirect_uris:[redirect],
   grant_types:['authorization_code','refresh_token'],response_types:['code'],token_endpoint_auth_method:'none'}),201);
  const verifier=randomBytes(32).toString('base64url');
  const query=new URLSearchParams({response_type:'code',client_id:client.client_id,redirect_uri:redirect,scope:'mcp.connect',
   state:'rejected-csp',code_challenge:createHash('sha256').update(verifier).digest('base64url'),code_challenge_method:'S256',resource:origin+'/mcp'});
  const result=await h.get('/authorize?'+query,{headers:{Cookie:browser.cookie}});
  expect(result.status).toBe(400);expect(await result.text()).toContain('INVALID_REDIRECT_ORIGIN');
  expect(result.headers.get('content-security-policy')).toContain("form-action 'self';");
  expect(result.headers.get('location')).toBeNull();
  expect((await readState())?.consents??[]).toHaveLength(0);
 });
 test.each(['http://127.0.0.1:49152/callback','http://[::1]:49152/callback',
  'https://xn--bcher-kva.example:8443/callback','https://sub.example.com/callback'])('permits a validated literal/DNS callback without widening CSP: %s',async(redirect)=>{
  const browser=await h.newBrowser();
  const client=await ok(await h.post('/oauth/register',{client_name:'Valid callback',redirect_uris:[redirect],
   grant_types:['authorization_code','refresh_token'],response_types:['code'],token_endpoint_auth_method:'none'}),201);
  const verifier=randomBytes(32).toString('base64url');
  const query=new URLSearchParams({response_type:'code',client_id:client.client_id,redirect_uri:redirect,scope:'mcp.connect',
   state:'accepted-csp',code_challenge:createHash('sha256').update(verifier).digest('base64url'),code_challenge_method:'S256',resource:origin+'/mcp'});
  const result=await h.get('/authorize?'+query,{headers:{Cookie:browser.cookie}});
  expect(result.status,await result.clone().text()).toBe(200);
  expect(result.headers.get('content-security-policy')).toContain("form-action 'self' "+new URL(redirect).origin+';');
 });
 test('public anonymous consent saturation cannot evict or block verified owner consent',async()=>{
  const {key}=await seeded(),browser=await login(key),protectedFlow=await begin(browser);
  for(let binding=0;binding<5;binding++) {
   const attacker=await h.newBrowser();
   for(let n=0;n<8;n++)await begin(attacker,['mcp.connect']);
  }
  const state=await readState();expect(state.consents.filter(c=>!c.sessionHash)).toHaveLength(32);
  expect(state.consents.some(c=>c.id===protectedFlow.values.flow&&c.sessionHash)).toBe(true);
  const newer=await begin(browser);expect((await consent(newer)).status).toBe(302);
  expect((await consent(protectedFlow)).status).toBe(302);
 });
 test('login from an OAuth page returns to consent, retaining the original provider transaction and browser cookies',async()=>{
  const {key}=await seeded(),anon=await h.newBrowser(),info=await begin(anon);
  expect(info.html).toContain('Sign in with passkey');expect(info.html).not.toContain('value="allow"');
  anon.browser_csrf=field(info.html,'browser_csrf');await login(key,anon);
  const resumed=await h.get('/authorize/continue?flow='+info.values.flow,{headers:{Cookie:anon.cookie}});
  expect(resumed.status,await resumed.clone().text()).toBe(200);info.html=await resumed.text();info.values=info.parse(info.html);
  expect(info.html).toContain('value="allow"');expect(info.html).not.toContain('name="owner_key"');
  const approved=await consent(info);expect(approved.status).toBe(302);
  const redirect=new URL(approved.headers.get('location'));expect(redirect.searchParams.get('state')).toBe('passkey-state');
  const exchanged=await h.get('/oauth/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'authorization_code',client_id:info.client.client_id,redirect_uri:'https://example.com/callback',code_verifier:info.verifier,code:redirect.searchParams.get('code'),resource:origin+'/mcp'}).toString()});
  expect((await ok(exchanged)).scope).toBe('mcp.connect library.read');
 });
 test('logout before the continuation page prevents a later login from resurrecting the pending consent',async()=>{
  const {key}=await seeded(),anon=await h.newBrowser(),info=await begin(anon);
  await login(key,anon);expect((await logout(anon)).status).toBe(303);await login(key,anon);
  const result=await h.get('/authorize/continue?flow='+info.values.flow,{headers:{Cookie:anon.cookie}});
  expect(result.status).toBe(401);
 });
 test('simultaneous approval issues at most one authorization code',async()=>{
  const {key}=await seeded(),info=await begin(await login(key));
  const results=await Promise.all([consent(info),consent(info)]);
  expect(results.filter(r=>r.headers.get('location')?.includes('code=')).length).toBe(1);
 });
 test('denial works without login and cannot subsequently be approved',async()=>{
  const {key}=await seeded(),anon=await h.newBrowser(),info=await begin(anon);
  const result=await consent(info,{decision:'deny'});expect(result.status).toBe(302);expect(result.headers.get('location')).toContain('error=access_denied');
  await login(key,anon);expect((await consent(info)).status).toBe(401);
 });
 test('concurrent logout and approval either commit before logout or refuse the consent',async()=>{
  const {key}=await seeded(),browser=await login(key),info=await begin(browser),auditBefore=(await readState()).audit.length;
  const [approved,loggedOut]=await Promise.all([consent(info),logout(browser)]);
  expect(loggedOut.status).toBe(303);expect([302,401]).toContain(approved.status);
  const events=(await readState()).audit.slice(auditBefore).map(e=>e.event);
  if(approved.status===302) {
   expect(approved.headers.get('location')).toContain('code=');
   expect(events.indexOf('oauth-consent-committed')).toBeGreaterThanOrEqual(0);
   expect(events.indexOf('oauth-consent-committed')).toBeLessThan(events.indexOf('logout'));
  } else expect(events).not.toContain('oauth-consent-committed');
  expect((await consent(info)).status).toBe(401);
 });
 test('OAuth storage failure after consent commit leaves the flow spent and a new flow can recover',async()=>{
  const {key}=await seeded(),browser=await login(key),info=await begin(browser);
  const failed=await consent(info,{},['library.read'],{'X-Test-Fail-Grant-Write':'yes'});
  expect(failed.status).toBe(500);expect(failed.headers.get('location')??'').not.toContain('code=');
  expect((await readState()).consents.find(c=>c.id===info.values.flow).status).toBe('claimed');
  expect((await consent(info)).status).toBe(401);
  const next=await begin(browser);expect((await consent(next)).status).toBe(302);
 });
 test('a session plus CSRF does not replace the provider original-browser cookie',async()=>{
  const {key}=await seeded(),info=await begin(await login(key));
  info.browser.cookie=info.browser.cookie.split(';').map(v=>v.trim()).filter(v=>!v.startsWith('__Host-newpipe-oauth-')).join('; ');
  const result=await consent(info);expect(result.headers.get('location')??'').not.toContain('code=');
 });
});
