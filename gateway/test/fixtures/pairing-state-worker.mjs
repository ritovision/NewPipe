// Test-only storage/failure hooks. This entry is used only by ephemeral Miniflare.
import worker, {ControlAccount as Account, DeviceRoom as Room} from '../../dist/index.js';
export class ControlAccount extends Account {
 async fetch(request) {
  const path=new URL(request.url).pathname;
  if(path==='/__test/reset') {await this.ctx.storage.deleteAll();return Response.json({ok:true});}
  const key={'/__test/pairs':'browser-pairing:v1','/__test/owner':'owner-auth:v1','/__test/devices':'devices'}[path];
  if(key) {
   if(request.method==='PUT') {await this.ctx.storage.put(key,await request.json());return Response.json({ok:true});}
   return Response.json(await this.ctx.storage.get(key)??null);
  }
  return super.fetch(request);
 }
}
export class DeviceRoom extends Room {
 registerWaiting=false;
 releaseRegistration;
 async waitForRelease() {
  this.registerWaiting=true;
  await new Promise(resolve=>{this.releaseRegistration=resolve;});
  this.registerWaiting=false;this.releaseRegistration=undefined;
 }
 async fetch(request) {
  const path=new URL(request.url).pathname;
  if(path==='/__test/fail-register') {await this.ctx.storage.put('qa-register-mode','fail-before');return Response.json({ok:true});}
  if(path==='/__test/register-mode') {await this.ctx.storage.put('qa-register-mode',(await request.json()).mode);return Response.json({ok:true});}
  if(path==='/__test/register-state') return Response.json({waiting:this.registerWaiting,registration:await this.ctx.storage.get('registration')??null,revoked:await this.ctx.storage.get('revoked')??false});
  if(path==='/__test/release-register') {this.releaseRegistration?.();return Response.json({ok:true});}
  if(path==='/register') {
   const mode=await this.ctx.storage.get('qa-register-mode');
   if(mode) await this.ctx.storage.delete('qa-register-mode');
   if(mode==='fail-before') return Response.json({error:'QA_STORAGE_FAILURE'},{status:503});
   if(mode==='hold-before') await this.waitForRelease();
   const reply=await super.fetch(request);
   if(mode==='hold-after') await this.waitForRelease();
   if(mode==='fail-after') return Response.json({error:'QA_RESPONSE_LOST_AFTER_COMMIT'},{status:503});
   return reply;
  }
  return super.fetch(request);
 }
}
export default worker;
