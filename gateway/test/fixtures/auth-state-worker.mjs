// Test-only subclass: allows deterministic expiry/epoch/failure fixtures in ephemeral
// Miniflare storage. Neither this module nor its routes is bundled for deployment.
import worker, {ControlAccount as ActualAccount} from '../../dist/index.js';
export {DeviceRoom} from '../../dist/index.js';
export class ControlAccount extends ActualAccount {
 async fetch(request) {
  if(new URL(request.url).pathname==='/__test/owner-state') {
   if(request.method==='DELETE') {await this.ctx.storage.delete('owner-auth:v1');return Response.json({ok:true});}
   if(request.method==='PUT') {await this.ctx.storage.put('owner-auth:v1',await request.json());return Response.json({ok:true});}
   return Response.json(await this.ctx.storage.get('owner-auth:v1')??null);
  }
  if(new URL(request.url).pathname==='/__test/alarm') {await this.alarm();return Response.json({ok:true});}
  return super.fetch(request);
 }
}
export default worker;
