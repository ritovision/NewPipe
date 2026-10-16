import { OAuthProvider } from '@cloudflare/workers-oauth-provider';
import { administration, authorize } from './auth';
import { mcpFetch } from './mcp';
import { account, body, boundedText, ControlError, device, Env, failure, response, SCOPES, UUID } from './shared';
export { ControlAccount } from './account';
export { DeviceRoom } from './device';

async function publicRoutes(request:Request,env:Env):Promise<Response> {
  const url=new URL(request.url);
  if(url.pathname==='/authorize') return authorize(request,env);
  if(url.pathname==='/setup' || url.pathname.startsWith('/admin/')) return administration(request,env);
  if(url.pathname==='/device/pair' && request.method==='POST') {
    return account(env).fetch(new Request('https://internal/pair',{method:'POST',body:await boundedText(request,4096)}));
  }
  if(url.pathname==='/device/challenge' && request.method==='POST') {
    const input=await body(request,2048);
    if(typeof input.device_id!=='string' || !UUID.test(input.device_id)) throw new ControlError('NOT_AUTHORIZED',401);
    return device(env,input.device_id).fetch(new Request('https://internal/challenge',{method:'POST',body:JSON.stringify(input)}));
  }
  if(url.pathname==='/device/connect' && request.method==='GET') {
    const id=request.headers.get('X-NewPipe-Device')??'';
    if(!UUID.test(id)) throw new ControlError('NOT_AUTHORIZED',401);
    return device(env,id).fetch(new Request('https://internal/connect',{method:'GET',headers:request.headers}));
  }
  if(url.pathname==='/' || url.pathname==='/health') return response({service:'NewPipe AI Control',protocol_version:1,authenticated_control:true});
  throw new ControlError('NOT_FOUND',404);
}
// OAuth clients use the protected-resource scope hints to request permissions. Exposing only
// mcp.connect caused ChatGPT to receive a token that could discover a device but could not use
// the Library, playlist or playback tools. Advertise all supported DEVICE action scopes, while
// keeping the actual grant subject to owner-key consent and unchecked destructive checkboxes.
// offline_access belongs to the authorization server, not the MCP protected resource.
const MCP_RESOURCE_SCOPES = SCOPES.filter(scope => scope !== 'offline_access');
function provider(origin:string):OAuthProvider<Env> {
  return new OAuthProvider<Env>({
    apiRoute:'/mcp', apiHandler:{fetch:mcpFetch}, defaultHandler:{fetch:publicRoutes},
    authorizeEndpoint:'/authorize', tokenEndpoint:'/oauth/token', clientRegistrationEndpoint:'/oauth/register',
    accessTokenTTL:600, refreshTokenTTL:604800, clientRegistrationTTL:31_536_000,
    scopesSupported:[...SCOPES], requiredScopes:[...MCP_RESOURCE_SCOPES], allowTokenExchangeGrant:false,
    clientIdMetadataDocumentEnabled:true, cookiePrefix:'__Host-newpipe-oauth-',
    resourceMetadata:{resource:origin+'/mcp',authorization_servers:[origin]},
  });
}
export default {
  async fetch(request:Request,env:Env,ctx:ExecutionContext):Promise<Response> {
    try {
      const url=new URL(request.url);
      if(!UUID.test(env.OWNER_ID??'') || !env.PUBLIC_ORIGIN || url.origin!==env.PUBLIC_ORIGIN ||
          !/^[0-9a-f]{64}$/.test(env.OWNER_KEY_HASH??'') || (env.SESSION_SECRET??'').length<32) return response({error:'SERVER_NOT_CONFIGURED'},503);
      const source=request.headers.get('Origin');
      const allowed=new Set([env.PUBLIC_ORIGIN,...(url.pathname==='/mcp'?['https://chatgpt.com','https://chat.openai.com']:[])]);
      if(source!==null && !allowed.has(source)) return response({error:'INVALID_ORIGIN'},403);
      if(request.method==='OPTIONS') {
        if(url.pathname!=='/mcp' || !source || !allowed.has(source)) throw new ControlError('METHOD_NOT_ALLOWED',405);
        return new Response(null,{status:204,headers:{'Access-Control-Allow-Origin':source,'Access-Control-Allow-Methods':'POST, GET, OPTIONS','Access-Control-Allow-Headers':'Authorization, Content-Type, Accept, MCP-Protocol-Version, MCP-Session-Id','Vary':'Origin'}});
      }
      if(url.pathname!=='/health') {
        const key=(url.pathname==='/oauth/register'?'registration:':'requests:')+(request.headers.get('CF-Connecting-IP')??'local');
        if(!(await env.AUTH_LIMITER.limit({key})).success) return response({error:'RATE_LIMITED'},429);
      }
      // Bound every public request before any library/parser is allowed to consume it.
      if(request.method==='POST') {
        const text=await boundedText(request,url.pathname==='/mcp'?65_536:16_384);
        if(url.pathname==='/mcp') {
          const rpc=JSON.parse(text);
          if(!rpc || typeof rpc!=='object' || Array.isArray(rpc)) return response({error:'BATCH_NOT_SUPPORTED'},400);
        }
        request=new Request(request,{body:text});
      }
      return await provider(env.PUBLIC_ORIGIN).fetch(request,env,ctx);
    } catch(error) { return failure(error); }
  }
} satisfies ExportedHandler<Env>;
