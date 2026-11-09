// Isolated Miniflare test entry. This module is not imported by production.
import worker from '../../dist/index.js';
export {ControlAccount,DeviceRoom} from '../../dist/index.js';
export default {
 async fetch(request,env,ctx) {
  const grantFault=request.headers.get('X-Test-Fail-Grant-Write')==='yes';
  if(!grantFault && request.headers.get('X-Test-Fail-Access-Write')!=='yes') return worker.fetch(request,env,ctx);
  let failed=false;
  const kv=new Proxy(env.OAUTH_KV,{
   get(target,property) {
    if(property==='put') return async (key,value,options)=>{
     if(!failed && key.startsWith(grantFault?'grant:':'token:')) {failed=true;throw new Error('Synthetic OAuth storage failure');}
     return target.put(key,value,options);
    };
    const value=Reflect.get(target,property);return typeof value==='function'?value.bind(target):value;
   }
  });
  // A reused Miniflare env may already cache OAuthHelpers with the original KV.
  // Force only this injected request to create helpers over the failing binding.
  return worker.fetch(request,{...env,OAUTH_KV:kv,OAUTH_PROVIDER:undefined},ctx);
 }
};
