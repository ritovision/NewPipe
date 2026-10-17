import {afterAll, beforeAll, describe, expect, test} from 'vitest';
import {randomUUID} from 'node:crypto';
import {harness, ownerKey, digest, origin} from './helpers.mjs';

// All tests run against ephemeral Miniflare storage and synthetic signed phone sockets.
// Never send commands to the live Worker, an installed NewPipe app, or a real Library.
let h;
beforeAll(async()=>{h=await harness();},30000);
afterAll(async()=>{await h?.mf.dispose();});

const request=(device_id,name)=>({device_id,request_id:randomUUID(),issued_at_ms:Date.now(),name});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function eventually(fn, description, timeout=2000) {
 const start=Date.now();let result;
 do {
  result=await fn();
  if(result) return;
  await delay(35);
 } while(Date.now()-start<timeout);
 throw Error('Timed out: '+description);
}

describe('short reliability acceptance: ephemeral clients and Android relay',()=>{
 test('disconnect/STOP goes offline; offline tombstone never plays after fresh reconnect',async()=>{
  const c=await h.oauth(),d=await h.pairedDevice();
  let effects=0;
  const ws=await h.connectDevice(d,()=>{effects++;});
  const online=request(d.id,'Before disconnect');
  expect((await h.call(c.access_token,'create_playlist',online)).data.outcome).toBe('COMMITTED');
  expect(effects).toBe(1);
  ws.close(); // Simulates user pressing Stop / Android network loss at the relay boundary.
  await eventually(async()=>!(await h.call(c.access_token,'get_device_status',{device_id:d.id})).data.online,'device offline after close');
  const lost=request(d.id,'While offline');
  const offline=await h.call(c.access_token,'create_playlist',lost);
  expect(offline.data.outcome).toBe('NOT_EXECUTED');
  expect(offline.data.code).toBe('DEVICE_OFFLINE');
  expect(effects).toBe(1);
  const ws2=await h.connectDevice(d,()=>{effects++;});
  try {
   expect((await h.call(c.access_token,'create_playlist',lost)).data.code).toBe('DEVICE_OFFLINE');
   expect(effects).toBe(1);
   const after=request(d.id,'After reconnect');
   expect((await h.call(c.access_token,'create_playlist',after)).data.outcome).toBe('COMMITTED');
   expect(effects).toBe(2);
   const same=await h.call(c.access_token,'create_playlist',after);
   expect(same.data.replayed).toBe(true);
   expect(effects).toBe(2);
  }finally{ws2.close();}
 },20000);

 test('commit before lost ack remains unknown until device receipt lookup after reconnect',async()=>{
  const c=await h.oauth(),d=await h.pairedDevice();
  let mutations=0,lookups=0,queriedId=null;
  const args=request(d.id,'Interrupted after commit');
  const ws=await h.connectDevice(d,(_,socket)=>{
   // Simulate Android committing with a durable local receipt, then losing the
   // connection before acknowledgement reaches Cloudflare.
   mutations++;
   socket.close();
   return false;
  });
  const first=await h.call(c.access_token,'create_playlist',args);
  expect(first.data.outcome).toBe('OUTCOME_UNKNOWN');
  expect(first.data.code).toBe('CONNECTION_LOST');
  expect(mutations).toBe(1);
  const fresh=await h.connectDevice(d,(frame,socket)=>{
   if(frame.request.command.type==='get_command_result'){
    lookups++;
    queriedId=frame.request.command.original_request_id;
    socket.send(JSON.stringify({
     type:'result',connection_id:frame.connection_id,client_id:frame.client_id,
     result:{request_id:frame.request.request_id,device_id:d.id,outcome:'COMPLETED',code:'OK',
      data:{original_request_id:args.request_id,original_outcome:'COMMITTED',result:{playlist_id:17}}}
    }));
    return false;
   }
   mutations++; // A replay of create_playlist would be an invalid second effect.
  });
  try {
   const repeat=await h.call(c.access_token,'create_playlist',args);
   expect(repeat.data.outcome).toBe('OUTCOME_UNKNOWN');
   expect(repeat.data.code).toBe('CONNECTION_LOST');
   expect(mutations).toBe(1);
   const receipt=await h.call(c.access_token,'get_command_result',{device_id:d.id,original_request_id:args.request_id});
   expect(receipt.data.outcome).toBe('COMPLETED');
   expect(receipt.data.code).toBe('OK');
   expect(receipt.data.data.original_outcome).toBe('COMMITTED');
   expect(receipt.data.data.result.playlist_id).toBe(17);
   expect(queriedId).toBe(args.request_id);
   expect(lookups).toBe(1);
   expect(mutations).toBe(1);
  }finally{
   try { ws.close(); } catch { /* First socket was deliberately severed. */ }
   fresh.close();
  }
 },20000);

 test('replacing an active socket fences its late close and never misroutes to the old socket',async()=>{
  const c=await h.oauth(),d=await h.pairedDevice();
  let oldDispatch=0,newDispatch=0;
  const old=await h.connectDevice(d,()=>{oldDispatch++;});
  const newer=await h.connectDevice(d,()=>{newDispatch++;});
  try {
   // Miniflare holds server-initiated closures in CLOSING (2); it does not
   // necessarily finish the client close handshake in this in-memory harness.
   await eventually(()=>old.readyState!==1,'old WebSocket begins closing when replaced');
   // A stale close from the earlier connection must not mark the replacement offline.
   try { old.close(); } catch { /* Already closed by the gateway. */ }
   await delay(25);
   const present=await h.call(c.access_token,'get_device_status',{device_id:d.id});
   expect(present.data.online).toBe(true);
   expect([2,3]).toContain(old.readyState);
   expect((await h.call(c.access_token,'create_playlist',request(d.id,'New connection'))).data.outcome).toBe('COMMITTED');
   expect(oldDispatch).toBe(0);
   expect(newDispatch).toBe(1);
  }finally{
   try { old.close(); } catch { /* Deliberately replaced. */ }
   newer.close();
  }
 },20000);

 test('an identical mutation arriving during a pending ack has no second effect',async()=>{
  const c=await h.oauth(),d=await h.pairedDevice();
  let sends=0,signal;
  const arrived=new Promise(resolve=>{signal=resolve;});
  const ws=await h.connectDevice(d,(frame,socket)=>{
   sends++;signal();
   // Hold the ack to force the second RPC into an actual in-flight window.
   setTimeout(()=>{
    try {
     socket.send(JSON.stringify({type:'result',connection_id:frame.connection_id,client_id:frame.client_id,
      result:{request_id:frame.request.request_id,device_id:d.id,outcome:'COMMITTED',code:'OK',data:{playlist_id:17}}}));
    } catch { /* Test teardown may have closed the socket on an assertion failure. */ }
   },250);
   return false;
  });
  try {
   const args=request(d.id,'Concurrent idempotent');
   const first=h.call(c.access_token,'create_playlist',args);
   await arrived;
   const inProgress=await h.call(c.access_token,'create_playlist',args);
   expect(inProgress.data.outcome).toBe('OUTCOME_UNKNOWN');
   expect(inProgress.data.code).toBe('AWAITING_DEVICE_RECEIPT');
   expect(inProgress.data.replayed).toBe(true);
   const completed=await first;
   expect(completed.data.outcome).toBe('COMMITTED');
   expect(sends).toBe(1);
   const cached=await h.call(c.access_token,'create_playlist',args);
   expect(cached.data.outcome).toBe('COMMITTED');
   expect(cached.data.replayed).toBe(true);
   expect((await h.call(c.access_token,'create_playlist',{...args,name:'Changed payload'})).data.code).toBe('REQUEST_ID_REUSED');
   expect(sends).toBe(1);
  }finally{ws.close();}
 },20000);

 test('an OAuth grant revoked while the phone is online invalidates access and refresh',async()=>{
  const c=await h.oauth(),d=await h.pairedDevice();
  let sends=0;
  const ws=await h.connectDevice(d,()=>{sends++;});
  try {
   expect((await h.call(c.access_token,'get_device_status',{device_id:d.id})).data.online).toBe(true);
   const list=await h.post('/admin/grants',{}, {Authorization:'Bearer '+ownerKey});
   expect(list.status).toBe(200);
   const grant=(await list.json()).items.find(x=>x.clientId===c.client_id);
   expect(grant).toBeDefined();
   const client_id='c_'+digest(c.client_id);
   expect((await h.post('/admin/revoke-grant',{grant_id:grant.id,client_id},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
   expect((await h.rpc(c.access_token,'tools/list',{})).status).toBe(401);
   expect((await h.rpc(c.access_token,'tools/call',{name:'create_playlist',arguments:request(d.id,'Revoked grant')})).status).toBe(401);
   expect(sends).toBe(0);
   const refreshed=await h.get('/oauth/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:new URLSearchParams({grant_type:'refresh_token',client_id:c.client_id,refresh_token:c.refresh_token,resource:origin+'/mcp'}).toString()});
   expect(refreshed.status).toBe(400);
   const after=await refreshed.json();
   expect(after.error).toBe('invalid_grant');
   expect(after.access_token).toBeUndefined();
  }finally{ws.close();}
 },20000);

 test('revoking a device closes its socket and prevents redispatch from valid clients',async()=>{
  const c=await h.oauth(),d=await h.pairedDevice();
  let sends=0;
  const ws=await h.connectDevice(d,()=>{sends++;});
  try {
   expect((await h.call(c.access_token,'get_device_status',{device_id:d.id})).data.online).toBe(true);
   expect((await h.post('/admin/revoke-device',{device_id:d.id},{Authorization:'Bearer '+ownerKey})).status).toBe(200);
   await eventually(()=>ws.readyState!==1,'device socket enters closing state on revocation');
   expect([2,3]).toContain(ws.readyState);
   const discovery=await h.call(c.access_token,'list_devices',{});
   expect(discovery.data.devices.some(x=>x.device_id===d.id)).toBe(false);
   const denied=await h.call(c.access_token,'create_playlist',request(d.id,'Revoked device'));
   expect(denied.data.code).toBe('DEVICE_NOT_AUTHORIZED');
   expect(sends).toBe(0);
   const pairing=await h.post('/device/pair',d.registration);
   expect(pairing.status).toBe(401);
  }finally{try {ws.close();} catch { /* Revoked by the Worker. */ }}
 },20000);

 test('reduced OAuth grant stays restricted even when synthetic phone advertises all permissions',async()=>{
  const c=await h.oauth(['mcp.connect','library.read','playback.control']),d=await h.pairedDevice();
  let sends=0;
  const ws=await h.connectDevice(d,()=>{sends++;});
  try {
   expect((await h.call(c.access_token,'get_catalog_status',{device_id:d.id})).data.code).toBe('OK');
   const denied=await h.call(c.access_token,'create_playlist',request(d.id,'OAuth-restricted'));
   expect(denied.data.code).toBe('PERMISSION_DENIED');
   const deletion=await h.call(c.access_token,'delete_playlist',{device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now(),playlist_id:17});
   expect(deletion.data.code).toBe('PERMISSION_DENIED');
   expect(sends).toBe(1); // Only the permitted read reached the synthetic phone.
  }finally{ws.close();}
 },20000);

 test('rapid clock and offline errors refuse stale playback/catalog without scheduling late work',async()=>{
  const c=await h.oauth(),d=await h.pairedDevice();
  let sends=0;
  const ws=await h.connectDevice(d,()=>{sends++;});
  try {
   const stale=request(d.id,'Stale catalog');stale.issued_at_ms=Date.now()-301_000;
   const old=await h.call(c.access_token,'create_playlist',stale);
   expect(old.data.outcome).toBe('NOT_EXECUTED');expect(old.data.code).toBe('EXPIRED');
   const playbackStale={device_id:d.id,request_id:randomUUID(),issued_at_ms:Date.now()-61_000};
   const delayedPlayback=await h.call(c.access_token,'pause',playbackStale);
   expect(delayedPlayback.data.outcome).toBe('NOT_EXECUTED');
   expect(delayedPlayback.data.code).toBe('EXPIRED');
   const future=request(d.id,'Future');future.issued_at_ms=Date.now()+10_000;
   expect((await h.call(c.access_token,'create_playlist',future)).data.code).toBe('CLOCK_SKEW');
   expect(sends).toBe(0);
   expect((await h.call(c.access_token,'create_playlist',{...stale,issued_at_ms:Date.now()})).data.code).toBe('REQUEST_ID_REUSED');
   expect(sends).toBe(0);
  }finally{ws.close();}
 },20000);

 test('client receipts are isolated even when different authorized clients use the same UUID',async()=>{
  const a=await h.oauth(),b=await h.oauth(),d=await h.pairedDevice();
  const clients=new Set();let sends=0;
  const ws=await h.connectDevice(d,frame=>{sends++;clients.add(frame.client_id);});
  try {
   const first=request(d.id,'Client A');
   expect((await h.call(a.access_token,'create_playlist',first)).data.outcome).toBe('COMMITTED');
   const independent={...first,name:'Client B uses same UUID'};
   expect((await h.call(b.access_token,'create_playlist',independent)).data.outcome).toBe('COMMITTED');
   expect(sends).toBe(2);
   expect(clients.size).toBe(2);
   expect((await h.call(a.access_token,'create_playlist',first)).data.replayed).toBe(true);
   expect((await h.call(b.access_token,'create_playlist',independent)).data.replayed).toBe(true);
   expect(sends).toBe(2);
  }finally{ws.close();}
 },20000);

 test('two device identities route only to the expressly selected Android installation',async()=>{
  const c=await h.oauth(),a=await h.pairedDevice(),b=await h.pairedDevice();
  let countA=0,countB=0;
  const socketA=await h.connectDevice(a,()=>{countA++;});
  const socketB=await h.connectDevice(b,()=>{countB++;});
  try {
   expect((await h.call(c.access_token,'create_playlist',request(a.id,'Only A'))).data.outcome).toBe('COMMITTED');
   expect([countA,countB]).toEqual([1,0]);
   socketA.close();
   await eventually(async()=>!(await h.call(c.access_token,'get_device_status',{device_id:a.id})).data.online,'A offline');
   expect((await h.call(c.access_token,'create_playlist',request(a.id,'A while offline'))).data.code).toBe('DEVICE_OFFLINE');
   expect([countA,countB]).toEqual([1,0]);
   expect((await h.call(c.access_token,'create_playlist',request(b.id,'Only B'))).data.outcome).toBe('COMMITTED');
   expect([countA,countB]).toEqual([1,1]);
  }finally{
   try { socketA.close(); } catch { /* Deliberately closed for the offline routing test. */ }
   socketB.close();
  }
 },20000);
});
