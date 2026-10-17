import {describe, expect, test} from 'vitest';
import {createServer} from 'node:net';
import {randomUUID, randomBytes, sign} from 'node:crypto';
import WebSocket from 'ws';
import {harness, ownerKey} from './helpers.mjs';

// This test explicitly uses a REAL TCP loopback WebSocket, not Miniflare's in-memory
// WebSocket pair, to exercise the timing of the replaced device's close event.
// No public listener, real phone, production Cloudflare account, or saved catalog.
async function loopbackPort() {
 const server=createServer();
 await new Promise((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',resolve);
 });
 const port=server.address().port;
 await new Promise(resolve=>server.close(resolve));
 return port;
}
const timeout=(label,ms=5000)=>new Promise((_,reject)=>setTimeout(()=>reject(new Error('Timed out: '+label)),ms));
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function connectPhone(h,device,onCommand) {
 const clientNonce=randomBytes(24).toString('base64url'), now=Date.now();
 const challengeProof=sign('sha256',Buffer.from(`challenge\n${h.origin}\n${device.id}\n${clientNonce}\n${now}`),device.privateKey).toString('base64url');
 const challengeResponse=await h.post('/device/challenge',{device_id:device.id,client_nonce:clientNonce,timestamp_ms:now,signature:challengeProof});
 expect(challengeResponse.status).toBe(200);
 const challenge=(await challengeResponse.json()).nonce;
 const proof=sign('sha256',Buffer.from(`connect\n${h.origin}\n${device.id}\n${challenge}\n${clientNonce}`),device.privateKey).toString('base64url');
 const ws=new WebSocket(h.origin.replace(/^http/,'ws')+'/device/connect',{
  headers:{'X-NewPipe-Device':device.id,'X-NewPipe-Nonce':challenge,'X-NewPipe-Signature':proof},
 });
 let connectionId;
 const ready=new Promise((resolve,reject)=>{
  ws.on('error',reject);
  ws.on('message',data=>{
   let frame;
   try{frame=JSON.parse(data.toString());}catch(e){reject(e);return;}
   if(frame.type==='connected'){
    connectionId=frame.connection_id;
    const hello={version:1,device_id:device.id,catalog_epoch:randomUUID(),playback_epoch:randomUUID(),
     capabilities:['create_playlist','get_catalog_status'],permissions:['library.read','playlist.write','library.write']};
    ws.send(JSON.stringify({type:'hello',connection_id:connectionId,hello}));
   }else if(frame.type==='ready'){
    resolve();
   }else if(frame.type==='command'){
    onCommand?.(frame);
    ws.send(JSON.stringify({type:'result',connection_id:frame.connection_id,client_id:frame.client_id,
     result:{request_id:frame.request.request_id,device_id:device.id,outcome:'COMMITTED',code:'OK',data:{playlist_id:17}}}));
   }
  });
 });
 await Promise.race([ready,timeout('real loopback signed phone handshake')]);
 return {ws,connectionId};
}

describe('network-level reliability acceptance: real TCP loopback',()=>{
 test('old-device late WebSocket close cannot mark the replacement offline or receive its commands',async()=>{
  const port=await loopbackPort(),base='http://127.0.0.1:'+port;
  const h=await harness({},{origin:base,directSocketPort:port});
  let oldPhone,newPhone;
  try{
   const client=await h.oauth(),device=await h.pairedDevice();
   let oldEffects=0,newEffects=0,newReady=false,oldClosedAfterReady=false;
   oldPhone=await connectPhone(h,device,()=>{oldEffects++;});
   const oldClosed=new Promise(resolve=>oldPhone.ws.once('close',(code,reason)=>{
    oldClosedAfterReady=newReady;
    resolve({code,reason:reason.toString()});
   }));
   // Delay receiving the old server-initiated close until the replacement is
   // fully authenticated and has sent hello, to maximize the stale-close race.
   oldPhone.ws.pause();
   newPhone=await connectPhone(h,device,()=>{newEffects++;});
   newReady=true;
   oldPhone.ws.resume();
   const closed=await Promise.race([oldClosed,timeout('old socket real close event')]);
   expect(oldClosedAfterReady).toBe(true);
   expect(closed.code).toBe(1000);
   expect(closed.reason).toBe('Replaced');
   expect(newPhone.ws.readyState).toBe(WebSocket.OPEN);
   // Poll repeatedly AFTER receiving the old close event: this catches a
   // late server close callback mistakenly invalidating the live replacement.
   for(let i=0;i<8;i++){
    const status=await h.call(client.access_token,'get_device_status',{device_id:device.id});
    expect(status.data.online).toBe(true);
    await sleep(35);
   }
   const args={device_id:device.id,request_id:randomUUID(),issued_at_ms:Date.now(),name:'Only the new connection'};
   const command=await h.call(client.access_token,'create_playlist',args);
   expect(command.data.outcome).toBe('COMMITTED');
   expect(oldEffects).toBe(0);
   expect(newEffects).toBe(1);
  }finally{
   for(const phone of [oldPhone,newPhone]){try{phone?.ws.terminate();}catch{}}
   await h.mf.dispose();
  }
 },20000);

 test('revoking a paired device closes real socket and prevents reconnect with old signed identity',async()=>{
  const port=await loopbackPort(),base='http://127.0.0.1:'+port;
  const h=await harness({},{origin:base,directSocketPort:port});
  let connected;
  try{
   const device=await h.pairedDevice();
   connected=await connectPhone(h,device);
   const closed=new Promise(resolve=>connected.ws.once('close',(code,reason)=>resolve({code,reason:reason.toString()})));
   const revoked=await h.post('/admin/revoke-device',{device_id:device.id},{Authorization:'Bearer '+ownerKey});
   expect(revoked.status).toBe(200);
   const result=await Promise.race([closed,timeout('revoked socket close event')]);
   expect(result.code).toBe(1008);
   expect(result.reason).toBe('Revoked');
   // Revoked identity must fail even with a *valid* original device signature.
   const clientNonce=randomBytes(24).toString('base64url'),timestamp=Date.now();
   const signature=sign('sha256',Buffer.from(`challenge\n${h.origin}\n${device.id}\n${clientNonce}\n${timestamp}`),device.privateKey).toString('base64url');
   const challenge=await h.post('/device/challenge',{device_id:device.id,client_nonce:clientNonce,timestamp_ms:timestamp,signature});
   expect(challenge.status).toBe(401);
  }finally{
   try{connected?.ws.terminate();}catch{}
   await h.mf.dispose();
  }
 },20000);
});
