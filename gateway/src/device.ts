import { DurableObject } from 'cloudflare:workers';
import { assert, body, canonical, CLIENT, ControlError, DeviceHello, DeviceRegistration, DeviceReply, digest, Env, failure, jsonDepth, Principal, randomToken, response, UUID, verifyDevice } from './shared';
import { PLAYBACK, TOOL_MAP } from './tools';

interface Attachment { connectionId: string; hello?: DeviceHello; seenAt: number }
interface Challenge { value: string; clientNonce: string; expiresAt: number }
interface Receipt { fingerprint: string; scopes: string[]; createdAt: number; connectionId?: string; reply?: DeviceReply }
interface Pending { memoryKey: string; resolve: (r: DeviceReply)=>void; timer: ReturnType<typeof setTimeout>; key?: string; connectionId: string; requestId: string; deviceId: string }
const RETAIN_MS=86_400_000, MAX_RECEIPTS=10_000;
const outcomes=new Set(['COMPLETED','COMMITTED','REJECTED','NOT_EXECUTED','OUTCOME_UNKNOWN']);
const result = (deviceId:string, requestId:string, outcome:string, code:string):DeviceReply => ({device_id:deviceId,request_id:requestId,outcome,code,data:{}});

export class DeviceRoom extends DurableObject<Env> {
  private pending=new Map<string,Pending>();
  constructor(ctx:DurableObjectState, env:Env) {
    super(ctx,env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping','pong'));
  }
  private async gate<T>(action:()=>Promise<T>):Promise<T> {
    const settled=await this.ctx.blockConcurrencyWhile(async()=>{
      try { return {ok:true as const,value:await action()}; }
      catch(error) { return {ok:false as const,error}; }
    });
    if(!settled.ok) throw settled.error;
    return settled.value;
  }
  private safeClose(ws:WebSocket,code=1000,reason='Closed'):void { try { ws.close(code,reason); } catch { /* Already closed. */ } }
  private socket():WebSocket|undefined {
    return this.ctx.getWebSockets('device').find(ws=>{
      if(ws.readyState!==WebSocket.OPEN) return false;
      const info=ws.deserializeAttachment() as Attachment|undefined;
      const last=Math.max(info?.seenAt??0,this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime()??0);
      if(Date.now()-last>90_000) { this.safeClose(ws,1000,'Presence expired'); return false; }
      return true;
    });
  }
  private async registration():Promise<DeviceRegistration> {
    const r=await this.ctx.storage.get<DeviceRegistration>('registration');
    if (!r || r.revoked || await this.ctx.storage.get<boolean>('revoked') || r.ownerId!==this.env.OWNER_ID) throw new ControlError('NOT_AUTHORIZED',401);
    return r;
  }
  async fetch(request:Request):Promise<Response> {
    try {
      const path=new URL(request.url).pathname;
      if(path==='/register') {
        const r=await body(request,2048) as unknown as DeviceRegistration;
        assert(UUID.test(r.deviceId) && r.ownerId===this.env.OWNER_ID && typeof r.publicKey==='string');
        await this.gate(async()=>{
          const old=await this.ctx.storage.get<DeviceRegistration>('registration');
          if(await this.ctx.storage.get<boolean>('revoked') || old?.revoked) throw new ControlError('DEVICE_REVOKED',401);
          if(old && old.publicKey!==r.publicKey) throw new ControlError('DEVICE_ID_CONFLICT',409);
          if(!old) await this.ctx.storage.put('registration',r);
        });
        return response({registered:true});
      }
      if(path==='/revoke') {
        await this.gate(async()=>{
          await this.ctx.storage.put('revoked',true);
          const r=await this.ctx.storage.get<DeviceRegistration>('registration');
          if(r) await this.ctx.storage.put('registration',{...r,revoked:true});
          for(const ws of this.ctx.getWebSockets()) this.safeClose(ws,1008,'Revoked');
          for(const p of [...this.pending.values()]) await this.finish(p,result(p.deviceId,p.requestId,'OUTCOME_UNKNOWN','DEVICE_REVOKED'));
        });
        return response({revoked:true});
      }
      if(path==='/client-policy') {
        const input=await body(request,2048);
        assert(typeof input.client_id==='string' && CLIENT.test(input.client_id) && typeof input.blocked==='boolean' && Number.isSafeInteger(input.revision) && (input.revision as number)>0);
        await this.gate(async()=>{
          const revisionKey='client_policy:'+input.client_id;
          const last=await this.ctx.storage.get<number>(revisionKey)??0;
          if((input.revision as number)<=last) return;
          const old=await this.ctx.storage.get<string[]>('blocked_clients')??[];
          const next=input.blocked ? [...new Set([...old,input.client_id as string])] : old.filter(c=>c!==input.client_id);
          if(next.length>128) throw new ControlError('CLIENT_LIMIT',409);
          await this.ctx.storage.transaction(async tx=>{
            await tx.put('blocked_clients',next);
            await tx.put(revisionKey,input.revision);
          });
          if(input.blocked) {
            for(const ws of this.ctx.getWebSockets()) this.safeClose(ws,1000,'Client access changed');
            for(const p of [...this.pending.values()]) await this.finish(p,result(p.deviceId,p.requestId,'OUTCOME_UNKNOWN','CLIENT_ACCESS_CHANGED'));
          }
        });
        return response({updated:true});
      }
      const registration=await this.registration();
      if(path==='/presence') {
        const ws=this.socket(); const info=ws?.deserializeAttachment() as Attachment|undefined;
        return response({device_id:registration.deviceId,name:registration.name,online:!!info?.hello,last_seen_ms:ws ? Math.max(info?.seenAt??0,this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime()??0) : null,capabilities:info?.hello?.capabilities??[]});
      }
      if(path==='/challenge') {
        const input=await body(request,2048);
        assert(input.device_id===registration.deviceId && typeof input.client_nonce==='string' && /^[A-Za-z0-9_-]{32,64}$/.test(input.client_nonce));
        assert(Number.isSafeInteger(input.timestamp_ms) && Math.abs(Date.now()-(input.timestamp_ms as number))<=60_000,'CLOCK_SKEW');
        assert(typeof input.signature==='string');
        if(!await verifyDevice(registration.publicKey,`challenge\n${this.env.PUBLIC_ORIGIN}\n${registration.deviceId}\n${input.client_nonce}\n${input.timestamp_ms}`,input.signature)) throw new ControlError('NOT_AUTHORIZED',401);
        const challenge=await this.gate(async()=>{
          const active=(await this.ctx.storage.get<Challenge[]>('challenges')??[]).filter(c=>c.expiresAt>Date.now());
          const old=active.find(c=>c.clientNonce===input.client_nonce); if(old) return old;
          if(active.length>=8) throw new ControlError('TOO_MANY_CHALLENGES',429);
          const next={value:randomToken(),clientNonce:input.client_nonce as string,expiresAt:Date.now()+30_000};
          await this.ctx.storage.put('challenges',[...active,next]); return next;
        });
        return response({nonce:challenge.value,expires_at_ms:challenge.expiresAt});
      }
      if(path==='/connect') return await this.connectDevice(request,registration);
      if(path==='/dispatch') return response(await this.dispatch(await body(request),registration));
      throw new ControlError('NOT_FOUND',404);
    } catch(error) { return failure(error); }
  }
  private async connectDevice(request:Request, registration:DeviceRegistration):Promise<Response> {
    if(request.headers.get('Upgrade')?.toLowerCase()!=='websocket') throw new ControlError('WEBSOCKET_REQUIRED',426);
    const nonce=request.headers.get('X-NewPipe-Nonce')??'', signature=request.headers.get('X-NewPipe-Signature')??'';
    assert(nonce.length<=64 && signature.length<=128);
    return this.gate(async()=>{
      const live=await this.registration();
      if(live.publicKey!==registration.publicKey) throw new ControlError('NOT_AUTHORIZED',401);
      const challenges=await this.ctx.storage.get<Challenge[]>('challenges')??[];
      const selected=challenges.find(c=>c.value===nonce && c.expiresAt>Date.now());
      if(!selected || !await verifyDevice(registration.publicKey,`connect\n${this.env.PUBLIC_ORIGIN}\n${registration.deviceId}\n${nonce}\n${selected.clientNonce}`,signature)) throw new ControlError('NOT_AUTHORIZED',401);
      await this.ctx.storage.put('challenges',challenges.filter(c=>c.value!==nonce && c.expiresAt>Date.now()));
      for(const old of this.ctx.getWebSockets()) this.safeClose(old,1000,'Replaced');
      for(const p of [...this.pending.values()]) await this.finish(p,result(p.deviceId,p.requestId,'OUTCOME_UNKNOWN','CONNECTION_REPLACED'));
      const pair=new WebSocketPair(); const client=pair[0], server=pair[1];
      const connectionId=crypto.randomUUID();
      this.ctx.acceptWebSocket(server,['device']);
      server.serializeAttachment({connectionId,seenAt:Date.now()} satisfies Attachment);
      server.send(JSON.stringify({type:'connected',connection_id:connectionId,owner_id:registration.ownerId,server_time_ms:Date.now()}));
      return new Response(null,{status:101,webSocket:client});
    });
  }
  private async dispatch(input:Record<string,unknown>, registration:DeviceRegistration):Promise<DeviceReply> {
    assert(typeof input.operation==='string'); const spec=TOOL_MAP.get(input.operation); assert(spec,'UNKNOWN_TOOL');
    const args=spec.schema.parse(input.arguments) as Record<string,unknown>;
    const principal=input.principal as Principal;
    assert(principal?.ownerId===registration.ownerId && CLIENT.test(principal.clientId) && Array.isArray(principal.scopes),'NOT_AUTHORIZED');
    assert(args.device_id===registration.deviceId,'WRONG_DEVICE');
    const scopes=spec.scopes(args); if(!scopes.every(s=>principal.scopes.includes(s))) throw new ControlError('PERMISSION_DENIED',403);
    const requestId=spec.mutation ? args.request_id as string : crypto.randomUUID();
    const key='r:'+principal.clientId+':'+requestId;
    const fingerprint=await digest(canonical({operation:spec.name,arguments:args}));
    const prepared=await this.gate(async()=>{
      await this.registration();
      const blocked=await this.ctx.storage.get<string[]>('blocked_clients')??[];
      if(blocked.includes(principal.clientId)) throw new ControlError('CLIENT_REVOKED',403);
      // Receipt admission and sending are serialized, but waiting for the device is NOT: socket
      // events must remain runnable. Persist "dispatched" before sending; never redeliver it.
      if(spec.name==='get_command_result') {
        const prior=await this.ctx.storage.get<Receipt>('r:'+principal.clientId+':'+args.original_request_id);
        if(!prior || prior.createdAt+RETAIN_MS<=Date.now()) return {reply:result(registration.deviceId,requestId,'OUTCOME_UNKNOWN','RESULT_NOT_RETAINED')};
        if(!prior.scopes.every(s=>principal.scopes.includes(s))) throw new ControlError('PERMISSION_DENIED',403);
        if(prior.reply && prior.reply.outcome!=='OUTCOME_UNKNOWN') return {reply:{...result(registration.deviceId,requestId,'COMPLETED','OK'),data:{original_request_id:args.original_request_id,original_outcome:prior.reply.outcome,result:prior.reply.data}}};
      }
      if(spec.mutation) {
        const existing=await this.ctx.storage.get<Receipt>(key);
        if(existing) {
          if(existing.fingerprint!==fingerprint) return {reply:result(registration.deviceId,requestId,'REJECTED','REQUEST_ID_REUSED')};
          if(!existing.scopes.every(s=>principal.scopes.includes(s))) throw new ControlError('PERMISSION_DENIED',403);
          if(existing.createdAt+RETAIN_MS<=Date.now()) return {reply:result(registration.deviceId,requestId,'OUTCOME_UNKNOWN','RESULT_NOT_RETAINED')};
          return {reply:{...(existing.reply??result(registration.deviceId,requestId,'OUTCOME_UNKNOWN','AWAITING_DEVICE_RECEIPT')),replayed:true}};
        }
        if(Date.now()-(args.issued_at_ms as number)>=RETAIN_MS-5_000) return {reply:result(registration.deviceId,requestId,'OUTCOME_UNKNOWN','EXPIRED_RESULT_NOT_RETAINED')};
        const count=await this.ctx.storage.get<number>('receipt_count')??0;
        if(count>=MAX_RECEIPTS) return {reply:result(registration.deviceId,requestId,'NOT_EXECUTED','RECEIPT_CAPACITY')};
      }
      const ws=this.socket(); const attachment=ws?.deserializeAttachment() as Attachment|undefined;
      const hello=attachment?.hello;
      let rejection:DeviceReply|undefined;
      if(spec.mutation && (args.issued_at_ms as number)>Date.now()+5_000) rejection=result(registration.deviceId,requestId,'NOT_EXECUTED','CLOCK_SKEW');
      else if(spec.mutation && (args.issued_at_ms as number)+(PLAYBACK.has(spec.name)?60_000:300_000)<=Date.now()) rejection=result(registration.deviceId,requestId,Date.now()-(args.issued_at_ms as number)>=RETAIN_MS-5_000?'OUTCOME_UNKNOWN':'NOT_EXECUTED','EXPIRED');
      else if(!ws || !hello) rejection=result(registration.deviceId,requestId,'NOT_EXECUTED','DEVICE_OFFLINE');
      else if(!hello.capabilities.includes(spec.name)) rejection=result(registration.deviceId,requestId,'NOT_EXECUTED','UNSUPPORTED_CAPABILITY');
      else if(!scopes.every(s=>hello.permissions.includes(s))) rejection=result(registration.deviceId,requestId,'NOT_EXECUTED','DEVICE_PERMISSION_DENIED');
      else if(this.pending.size>=8) rejection=result(registration.deviceId,requestId,'NOT_EXECUTED','DEVICE_BUSY');
      const record:Receipt={fingerprint,scopes,createdAt:Date.now(),connectionId:attachment?.connectionId,reply:rejection};
      if(spec.mutation) {
        await this.ctx.storage.transaction(async tx=>{
          await tx.put(key,record); await tx.put('receipt_count',(await tx.get<number>('receipt_count')??0)+1);
        });
        if(await this.ctx.storage.getAlarm()===null) await this.ctx.storage.setAlarm(Date.now()+3_600_000);
      }
      // Offline attempts are receipt tombstones, not queued commands: retrying their UUID later
      // still cannot execute after reconnection.
      if(rejection) return {reply:rejection};
      const {device_id:_device,request_id:_request,issued_at_ms:_issued,...commandArgs}=args;
      const now=Date.now(), ttl=spec.name==='save_urls'?90_000:PLAYBACK.has(spec.name)?20_000:30_000;
      const envelope={version:1,device_id:registration.deviceId,client_id:principal.clientId,catalog_epoch:hello!.catalog_epoch,request_id:requestId,issued_at_ms:now,expires_at_ms:now+ttl,command:{type:spec.name,...commandArgs},playback_epoch:PLAYBACK.has(spec.name)?hello!.playback_epoch:null};
      let resolve!:(r:DeviceReply)=>void;
      const promise=new Promise<DeviceReply>(r=>{resolve=r;});
      const p:Pending={memoryKey:principal.clientId+':'+requestId,resolve,key:spec.mutation?key:undefined,connectionId:attachment!.connectionId,requestId,deviceId:registration.deviceId,timer:setTimeout(()=>{},0)};
      clearTimeout(p.timer);
      p.timer=setTimeout(()=>{void this.finish(p,result(p.deviceId,p.requestId,'OUTCOME_UNKNOWN','DEVICE_TIMEOUT'));},ttl+1000);
      this.pending.set(p.memoryKey,p);
      try {
        ws!.send(JSON.stringify({type:'command',connection_id:p.connectionId,owner_id:registration.ownerId,client_id:principal.clientId,client_name:principal.clientName.slice(0,80),scopes:principal.scopes,request:envelope}));
      } catch { await this.finish(p,result(p.deviceId,p.requestId,'OUTCOME_UNKNOWN','CONNECTION_LOST')); }
      return {promise};
    });
    return prepared.reply ?? await prepared.promise!;
  }
  async webSocketMessage(ws:WebSocket, message:string|ArrayBuffer):Promise<void> {
    try {
      assert(typeof message==='string' && new TextEncoder().encode(message).length<=262_144,'PAYLOAD_TOO_LARGE');
      jsonDepth(message); const frame=JSON.parse(message) as Record<string,unknown>;
      const attachment=ws.deserializeAttachment() as Attachment;
      assert(frame.connection_id===attachment.connectionId && this.socket()?.deserializeAttachment()?.connectionId===attachment.connectionId,'STALE_CONNECTION');
      const registration=await this.registration();
      if(frame.type==='hello') {
        const hello=frame.hello as DeviceHello;
        assert(hello?.version===1 && hello.device_id===registration.deviceId && UUID.test(hello.catalog_epoch) && UUID.test(hello.playback_epoch));
        assert(Array.isArray(hello.capabilities) && hello.capabilities.length<=64 && hello.capabilities.every(x=>typeof x==='string' && x.length<=64));
        assert(Array.isArray(hello.permissions) && hello.permissions.length<=16 && hello.permissions.every(x=>typeof x==='string' && x.length<=64));
        const normalized:DeviceHello={version:1,device_id:hello.device_id,catalog_epoch:hello.catalog_epoch,playback_epoch:hello.playback_epoch,capabilities:[...new Set(hello.capabilities)],permissions:[...new Set(hello.permissions)]};
        attachment.hello=normalized; attachment.seenAt=Date.now();
        assert(new TextEncoder().encode(JSON.stringify(attachment)).length<=1900,'HELLO_TOO_LARGE');
        ws.serializeAttachment(attachment);
        ws.send(JSON.stringify({type:'ready',connection_id:attachment.connectionId})); return;
      }
      assert(frame.type==='result'); const reply=frame.result as DeviceReply;
      assert(reply && UUID.test(reply.request_id) && reply.device_id===registration.deviceId && outcomes.has(reply.outcome));
      assert(typeof reply.code==='string' && /^[A-Z0-9_]{1,64}$/.test(reply.code) && reply.data && typeof reply.data==='object' && !Array.isArray(reply.data));
      attachment.seenAt=Date.now(); ws.serializeAttachment(attachment);
      assert(typeof frame.client_id==='string' && CLIENT.test(frame.client_id));
      const p=this.pending.get(frame.client_id+':'+reply.request_id);
      if(p && p.connectionId===attachment.connectionId) await this.finish(p,reply);
      // Late replies are never forwarded as new commands. A caller can query the device's
      // durable catalog receipt after an uncertain reply instead of replaying the mutation.
    } catch { this.safeClose(ws,1008,'Invalid device message'); }
  }
  private async finish(p:Pending, reply:DeviceReply):Promise<void> {
    if(this.pending.get(p.memoryKey)!==p) return;
    this.pending.delete(p.memoryKey); clearTimeout(p.timer);
    try { if(p.key) {
      const record=await this.ctx.storage.get<Receipt>(p.key);
      if(record?.connectionId===p.connectionId) {
        // Persist only compact counts/identifiers, not titles, URLs, search results, or credentials.
        const safe:Record<string,unknown>={};
        for(const [k,v] of Object.entries(reply.data)) if(/^(playlist_id|library_id|tag_id|category_id|catalog_revision|deleted_tags|deleted_assignments|deleted_categories|added_items|affected_memberships|removed_library_items|removed_memberships|removed_playlists|added_memberships|source_revision|target_revision|revision)$/.test(k) && (typeof v==='number' || typeof v==='boolean' || (typeof v==='string' && /^[0-9a-f-]{1,64}$/.test(v)))) safe[k]=v;
        if(Array.isArray(reply.data.tag_ids) && reply.data.tag_ids.length<=500 && reply.data.tag_ids.every(v=>typeof v==='number' && Number.isSafeInteger(v) && v>0)) safe.tag_ids=reply.data.tag_ids;
        await this.ctx.storage.put(p.key,{...record,reply:{...reply,data:safe}});
      }
    } } catch { reply=result(p.deviceId,p.requestId,'OUTCOME_UNKNOWN','RELAY_RECEIPT_FAILURE'); }
    finally { p.resolve(reply); }
  }
  async webSocketClose(ws:WebSocket):Promise<void> {
    const attachment=ws.deserializeAttachment() as Attachment|undefined;
    for(const p of [...this.pending.values()]) if(p.connectionId===attachment?.connectionId) await this.finish(p,result(p.deviceId,p.requestId,'OUTCOME_UNKNOWN','CONNECTION_LOST'));
    this.safeClose(ws);
  }
  async webSocketError(ws:WebSocket):Promise<void> { await this.webSocketClose(ws); }
  async alarm():Promise<void> {
    try { await this.gate(async()=>{
      const all=await this.ctx.storage.list<Receipt>({prefix:'r:'});
      const expired=[...all].filter(([,v])=>v.createdAt+RETAIN_MS<=Date.now()).map(([k])=>k);
      for(let n=0;n<expired.length;n+=128) await this.ctx.storage.delete(expired.slice(n,n+128));
      await this.ctx.storage.put('receipt_count',all.size-expired.length);
      if(all.size>expired.length) await this.ctx.storage.setAlarm(Date.now()+3_600_000);
    }); } catch { await this.ctx.storage.setAlarm(Date.now()+60_000); }
  }
}
