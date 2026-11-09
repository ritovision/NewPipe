// Isolated Miniflare test entry. This module is not imported by production.
import worker from '../../dist/index.js';
export {ControlAccount,DeviceRoom} from '../../dist/index.js';
export default {
 async fetch(request,env,ctx) {
  if(request.headers.get('X-Test-Fail-Access-Write')!=='yes') return worker.fetch(request,env,ctx);
  let failed=false;
  const kv=new Proxy(env.OAUTH_KV,{
   get(target,property) {
    if(property==='put') return async (key,value,options)=>{
     if(!failed && key.startsWith('token:')) {failed=true;throw new Error('Synthetic access-token storage failure');}
     return target.put(key,value,options);
    };
    const value=Reflect.get(target,property);return typeof value==='function'?value.bind(target):value;
   }
  });
  return worker.fetch(request,{...env,OAUTH_KV:kv},ctx);
 }
};
