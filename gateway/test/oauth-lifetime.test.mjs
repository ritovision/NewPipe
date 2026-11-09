import {beforeAll, afterAll, describe, test, expect} from 'vitest';
import {randomUUID} from 'node:crypto';
import {harness, origin, ownerKey, digest} from './helpers.mjs';

// Grant/token lifetime policy against the pinned @cloudflare/workers-oauth-provider, using
// ephemeral Miniflare KV only. Elapsed time is simulated by rewriting the provider's own
// TTL-derived fields (grant expiresAt, client registrationExpiresAt and the matching KV
// expiration, keeping KV metadata) in this isolated store. No clock travel, production
// state or custom token logic is involved.
const DAY=86_400, GRANT_TTL=90*DAY, ACCESS_TTL=600, REGISTRATION_TTL=31_536_000, LEGACY_TTL=7*DAY;
let h, kv;
beforeAll(async()=>{h=await harness();kv=await h.mf.getKVNamespace('OAUTH_KV');},30000);
afterAll(async()=>{await h?.mf.dispose();});

const now=()=>Math.floor(Date.now()/1000);
const near=(value,from,to,ttl)=>{expect(value).toBeGreaterThanOrEqual(from+ttl-1);expect(value).toBeLessThanOrEqual(to+ttl+1);};
const token=fields=>h.get('/oauth/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({resource:origin+'/mcp',...fields}).toString()});
const refresh=(c,refresh_token,extra={})=>token({grant_type:'refresh_token',client_id:c.client_id,refresh_token,...extra});
const grantKey=t=>{const [user,grant]=t.split(':');return `grant:${user}:${grant}`;};
const kvExpiration=async key=>(await kv.list({prefix:key})).keys.find(k=>k.name===key)?.expiration;
async function stored(key) {
 const {value:raw,metadata}=await kv.getWithMetadata(key,'text');
 return {record:raw===null?null:JSON.parse(raw),raw,metadata,expiration:await kvExpiration(key)};
}
async function relevantSnapshot(c) {
 const [user,id]=c.refresh_token.split(':'),keys=[grantKey(c.refresh_token),'client:'+c.client_id];
 keys.push(...(await kv.list({prefix:`token:${user}:${id}:`})).keys.map(k=>k.name));
 return Object.fromEntries(await Promise.all(keys.sort().map(async key=>[key,await stored(key)])));
}
async function simulate(key,changes,expiration) {
 const {value,metadata}=await kv.getWithMetadata(key,'json');
 await kv.put(key,JSON.stringify({...value,...changes}),{expiration,...(metadata?{metadata}:{})});
}
async function listed(clientId) {
 const r=await h.post('/admin/grants',{}, {Authorization:'Bearer '+ownerKey});expect(r.status).toBe(200);
 return (await r.json()).items.find(g=>g.clientId===clientId);
}
async function ok(r) {expect(r.status,await r.clone().text()).toBe(200);return r.json();}
async function rejected(r,error='invalid_grant') {expect(r.status).toBe(400);expect((await r.json()).error).toBe(error);}

describe('durable OAuth authorization: 10-minute access, 90-day sliding grant',()=>{
 test('a new grant lasts 90 days from code exchange and access tokens last 10 minutes',async()=>{
  const t0=now(),c=await h.oauth(['mcp.connect','library.read']),t1=now();
  expect(c.expires_in).toBe(ACCESS_TTL);
  const grant=await listed(c.client_id);near(grant.expiresAt,t0,t1,GRANT_TTL);
  expect(await kvExpiration(grantKey(c.refresh_token))).toBe(grant.expiresAt);
  const [user,id]=c.access_token.split(':'),tokens=(await kv.list({prefix:`token:${user}:${id}:`})).keys;
  expect(tokens).toHaveLength(1);
  const access=await kv.get(tokens[0].name,'json');expect(access.expiresAt-access.createdAt).toBe(ACCESS_TTL);
 },15000);

 test('each successful refresh moves expiry 90 days forward; ordinary MCP use does not',async()=>{
  const c=await h.oauth(['mcp.connect','library.read']),key=grantKey(c.refresh_token);
  const almostIdle=now()+3600; // one hour of the idle window left
  await simulate(key,{expiresAt:almostIdle},almostIdle);
  expect((await h.rpc(c.access_token,'tools/list',{})).status).toBe(200);
  expect(Array.isArray((await h.call(c.access_token,'list_devices',{})).data.devices)).toBe(true);
  expect((await stored(key)).record.expiresAt).toBe(almostIdle);
  const t0=now(),next=await ok(await refresh(c,c.refresh_token)),t1=now();
  expect(next.expires_in).toBe(ACCESS_TTL);expect(next.scope).toBe(c.scope);
  const slid=await stored(key);near(slid.record.expiresAt,t0,t1,GRANT_TTL);
  expect(slid.expiration).toBe(slid.record.expiresAt);
  expect((await listed(c.client_id)).expiresAt).toBe(slid.record.expiresAt);
  expect((await h.rpc(next.access_token,'tools/list',{})).status).toBe(200);
 },15000);

 test('an expired grant is rejected before writes to grant, token, client or metadata',async()=>{
  const c=await h.oauth(['mcp.connect','library.read']),key=grantKey(c.refresh_token);
  const current=(await ok(await refresh(c,c.refresh_token))).refresh_token; // c.refresh_token is now the retry-window token
  // Inside the provider's 60 s floor, then fully elapsed. The record is kept so the check is shown to be logical, not only KV eviction.
  for(const expiresAt of [now()+30,now()-1]) {
   await simulate(key,{expiresAt},now()+3600);
   const before=await relevantSnapshot(c);
   await rejected(await refresh(c,current));
   await rejected(await refresh(c,c.refresh_token));
   expect(await relevantSnapshot(c)).toEqual(before);
  }
  await kv.delete(key); // physical KV expiry
  await rejected(await refresh(c,current));
  expect(await listed(c.client_id)).toBeUndefined();
 },15000);

 test('previous-token retries repeatedly slide until a replacement is used, then are rejected without writes',async()=>{
  const c=await h.oauth(['mcp.connect','library.read']),key=grantKey(c.refresh_token),r0=c.refresh_token;
  let lost=await ok(await refresh(c,r0));
  for(let attempt=0;attempt<3;attempt++) {
   // Without aging, an unchanged expiry could accidentally satisfy the assertion.
   const aged=now()+3600;
   await simulate(key,{expiresAt:aged},aged);
   const t0=now(),retry=await ok(await refresh(c,r0)),t1=now();
   near((await stored(key)).record.expiresAt,t0,t1,GRANT_TTL);
   expect(retry.refresh_token).not.toBe(lost.refresh_token);
   const before=await relevantSnapshot(c);
   await rejected(await refresh(c,lost.refresh_token));
   expect(await relevantSnapshot(c)).toEqual(before);
   lost=retry;
  }
  const third=await ok(await refresh(c,lost.refresh_token));
  let before=await relevantSnapshot(c);
  await rejected(await refresh(c,r0));
  expect(await relevantSnapshot(c)).toEqual(before);
  const other=await h.oauth(['mcp.connect']);
  before=await relevantSnapshot(c); const otherBefore=await relevantSnapshot(other);
  await rejected(await refresh({client_id:other.client_id},third.refresh_token));
  expect(await relevantSnapshot(c)).toEqual(before);expect(await relevantSnapshot(other)).toEqual(otherBefore);
  expect((await ok(await refresh(c,third.refresh_token))).scope).toBe(c.scope);
 },15000);

 test('a grant older than a year keeps sliding when refreshed before its idle deadline',async()=>{
  const c=await h.oauth(['mcp.connect','library.read']),key=grantKey(c.refresh_token),createdAt=now()-400*DAY;
  let current=c.refresh_token;
  for(let round=0;round<3;round++) {
   const expiresAt=now()+3600;
   await simulate(key,{createdAt,expiresAt},expiresAt);
   const t0=now();current=(await ok(await refresh(c,current))).refresh_token;const t1=now();
   const result=await stored(key);near(result.record.expiresAt,t0,t1,GRANT_TTL);
   expect(result.record.createdAt).toBe(createdAt);
  }
 },15000);

 test('a failed access-token write can follow grant rotation; retrying the old token recovers',async()=>{
  // The fault injection exists ONLY in this separate test-entry Worker, never production.
  const f=await harness({}, {scriptPath:'test/fixtures/kv-fault-worker.mjs'});
  try {
   const store=await f.mf.getKVNamespace('OAUTH_KV'),c=await f.oauth(['mcp.connect','library.read']),key=grantKey(c.refresh_token);
   const before=await store.getWithMetadata(key,'json'),aged=now()+3600;
   await store.put(key,JSON.stringify({...before.value,expiresAt:aged}),{expiration:aged,metadata:before.metadata});
   const send=(token,headers={})=>f.get('/oauth/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',...headers},body:new URLSearchParams({grant_type:'refresh_token',client_id:c.client_id,refresh_token:token,resource:origin+'/mcp'}).toString()});
   const [user,id]=c.access_token.split(':'),prefix=`token:${user}:${id}:`;
   const tokensBefore=(await store.list({prefix})).keys;
   const t0=now(),failed=await send(c.refresh_token,{'X-Test-Fail-Access-Write':'yes'}),t1=now();
   expect(failed.status).toBe(500);
   const partial=await store.get(key,'json');near(partial.expiresAt,t0,t1,GRANT_TTL);
   expect(partial.refreshTokenId).not.toBe(before.value.refreshTokenId);
   expect(partial.previousRefreshTokenId).toBe(before.value.refreshTokenId);
   expect((await store.list({prefix})).keys).toEqual(tokensBefore);
   const recovered=await ok(await send(c.refresh_token));expect(recovered.expires_in).toBe(ACCESS_TTL);
   expect((await f.rpc(recovered.access_token,'tools/list',{})).status).toBe(200);
   await ok(await send(recovered.refresh_token));
   await rejected(await send(c.refresh_token));
  } finally {await f.mf.dispose();}
 },30000);

 test('refresh can narrow but never widen the consented scope',async()=>{
  const consented=['mcp.connect','library.read','playback.control'];
  const c=await h.oauth([...consented,'library.delete','playlist.delete'],consented),key=grantKey(c.refresh_token);
  const wide=await ok(await refresh(c,c.refresh_token,{scope:[...consented,'library.delete','playlist.delete','library.write'].join(' ')}));
  expect(wide.scope.split(' ').sort()).toEqual([...consented].sort());
  const deletion=await h.call(wide.access_token,'delete_playlist',{device_id:randomUUID(),request_id:randomUUID(),issued_at_ms:Date.now(),playlist_id:17});
  expect(deletion.data.code).toBe('PERMISSION_DENIED');
  const before=await stored(key);
  await rejected(await refresh(c,wide.refresh_token,{scope:'library.delete playlist.delete'}),'invalid_scope');
  expect(await stored(key)).toEqual(before);
  const narrow=await ok(await refresh(c,wide.refresh_token,{scope:'mcp.connect library.read'}));
  expect(narrow.scope).toBe('mcp.connect library.read');
  const denied=await h.call(narrow.access_token,'pause',{device_id:randomUUID(),request_id:randomUUID(),issued_at_ms:Date.now()});
  expect(denied.data.code).toBe('PERMISSION_DENIED');
  // The consented grant, not the last token, is the ceiling: a later plain refresh restores exactly it.
  expect((await ok(await refresh(c,narrow.refresh_token))).scope.split(' ').sort()).toEqual([...consented].sort());
  expect((await stored(key)).record.scope.sort()).toEqual([...consented].sort());
 },15000);

 test('an existing seven-day grant keeps its schedule until a successful refresh adopts the 90-day idle policy',async()=>{
  const c=await h.oauth(['mcp.connect','library.read']),key=grantKey(c.refresh_token);
  const r1=(await ok(await refresh(c,c.refresh_token))).refresh_token;
  // The record refreshTokenTTL=604800 without refreshTokenIdleTTL writes for a code exchange six days ago.
  const createdAt=now()-6*DAY,expiresAt=createdAt+LEGACY_TTL;
  await simulate(key,{createdAt,expiresAt},expiresAt);
  const legacy=await stored(key);
  expect(Array.isArray((await h.call(c.access_token,'list_devices',{})).data.devices)).toBe(true);
  expect(await stored(key)).toEqual(legacy);
  const t0=now(),adopted=await ok(await refresh(c,r1)),t1=now();
  const after=await stored(key);
  near(after.record.expiresAt,t0,t1,GRANT_TTL);expect(after.expiration).toBe(after.record.expiresAt);
  expect(adopted.scope).toBe(c.scope);expect(adopted.expires_in).toBe(ACCESS_TTL);
  for(const field of ['id','clientId','userId','scope','createdAt','encryptedProps','metadata','resource','redirectUri'])
   expect(after.record[field]).toEqual(legacy.record[field]);
  expect(Array.isArray((await h.call(adopted.access_token,'list_devices',{})).data.devices)).toBe(true);
 },15000);

 test('an existing seven-day grant that lapsed before any refresh is not revived',async()=>{
  const c=await h.oauth(['mcp.connect','library.read']),key=grantKey(c.refresh_token);
  const createdAt=now()-LEGACY_TTL-60;
  await simulate(key,{createdAt,expiresAt:createdAt+LEGACY_TTL},now()+3600);
  const before=await stored(key);
  await rejected(await refresh(c,c.refresh_token));
  expect(await stored(key)).toEqual(before);
 },15000);

 test('dynamic registrations keep a one-year TTL and renew only on successful use past half-life',async()=>{
  const c=await h.oauth(['mcp.connect','library.read']),key='client:'+c.client_id;
  const initial=await stored(key),registered=initial.record.registrationDate;
  expect(initial.record.registrationExpiresAt-registered).toBe(REGISTRATION_TTL);
  near(initial.expiration,registered,registered,REGISTRATION_TTL);
  // Renewal is due after half the TTL; a grant alive under the idle policy refreshed within the last 90 days.
  expect(REGISTRATION_TTL/2).toBeGreaterThan(GRANT_TTL);
  let current=(await ok(await refresh(c,c.refresh_token))).refresh_token;
  expect(await stored(key)).toEqual(initial);
  const aged=now()+100*DAY; // a 265-day-old registration
  await simulate(key,{registrationExpiresAt:aged},aged);
  const before=await stored(key);
  await rejected(await refresh(c,'not:a:token'));
  expect(await stored(key)).toEqual(before);
  const t0=now();current=(await ok(await refresh(c,current))).refresh_token;const t1=now();
  const renewed=await stored(key);
  near(renewed.record.registrationExpiresAt,t0,t1,REGISTRATION_TTL);near(renewed.expiration,t0,t1,REGISTRATION_TTL);
  expect(renewed.record.registrationDate).toBe(registered);
  await kv.delete(key); // a lapsed registration makes even a live grant unusable
  const lapsed=await refresh(c,current);expect(lapsed.status).toBe(401);expect((await lapsed.json()).error).toBe('invalid_client');
 },15000);

 test('revocation still ends a slid grant, including its retry-window refresh token',async()=>{
  const admin=await h.oauth(['mcp.connect','library.read']),next=await ok(await refresh(admin,admin.refresh_token));
  const grant=await listed(admin.client_id);
  expect((await h.post('/admin/revoke-grant',{grant_id:grant.id,client_id:'c_'+digest(admin.client_id)},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
  expect((await h.rpc(next.access_token,'tools/list',{})).status).toBe(401);
  await rejected(await refresh(admin,next.refresh_token));await rejected(await refresh(admin,admin.refresh_token));
  // Client-initiated RFC 7009 revocation of a refresh token revokes the whole grant.
  const self=await h.oauth(['mcp.connect','library.read']),rotated=await ok(await refresh(self,self.refresh_token));
  expect((await token({client_id:self.client_id,token:rotated.refresh_token,token_type_hint:'refresh_token'})).status).toBe(200);
  expect((await h.rpc(rotated.access_token,'tools/list',{})).status).toBe(401);
  await rejected(await refresh(self,rotated.refresh_token));await rejected(await refresh(self,self.refresh_token));
  expect(await listed(self.client_id)).toBeUndefined();
 },15000);
});
