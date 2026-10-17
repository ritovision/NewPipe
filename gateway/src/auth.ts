import { AuthorizationError } from '@cloudflare/workers-oauth-provider';
import { account, assert, b64, body, boundedText, constantTime, ControlError, digest, Env, esc, failure, randomToken, response, sameOrigin, SCOPES, unb64, verifyOwner, cleanLabel } from './shared';

const adminCookie='__Host-newpipe-admin';
interface AdminSession { expires: number; nonce: string }
async function signingKey(env:Env):Promise<CryptoKey> {
  assert((env.SESSION_SECRET??'').length>=32,'SERVER_NOT_CONFIGURED');
  return crypto.subtle.importKey('raw',new TextEncoder().encode(env.SESSION_SECRET),{name:'HMAC',hash:'SHA-256'},false,['sign','verify']);
}
async function seal(value:unknown,env:Env,purpose='admin'):Promise<string> {
  const encoded=b64(new TextEncoder().encode(JSON.stringify(value)));
  const signature=new Uint8Array(await crypto.subtle.sign('HMAC',await signingKey(env),new TextEncoder().encode(purpose+'.'+encoded)));
  return encoded+'.'+b64(signature);
}
async function open(value:string,env:Env,purpose='admin'):Promise<AdminSession|null> {
  try {
    const parts=value.split('.'); if(parts.length!==2 || value.length>(purpose==='admin'?512:2048)) return null;
    if(!await crypto.subtle.verify('HMAC',await signingKey(env),unb64(parts[1]!),new TextEncoder().encode(purpose+'.'+parts[0]!))) return null;
    const data=JSON.parse(new TextDecoder().decode(unb64(parts[0]!))) as AdminSession;
    return Number.isSafeInteger(data.expires) && data.expires>Date.now() && /^[A-Za-z0-9_-]{32}$/.test(data.nonce) ? data:null;
  } catch { return null; }
}
function cookie(request:Request,name:string):string {
  return (request.headers.get('Cookie')??'').split(';').map(x=>x.trim()).find(x=>x.startsWith(name+'='))?.slice(name.length+1)??'';
}
function html(content:string,headers=new Headers(),status=200):Response {
  headers.set('Content-Type','text/html; charset=utf-8');headers.set('Cache-Control','no-store');headers.set('Referrer-Policy','same-origin');headers.set('X-Content-Type-Options','nosniff');
  headers.set('Content-Security-Policy',"default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");
  return new Response(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NewPipe AI Control</title><style>body{font:16px system-ui;max-width:660px;margin:48px auto;padding:0 20px;line-height:1.55}input,button{font:inherit;padding:10px;margin:6px 0;max-width:95%}input[type=password]{width:100%}button{cursor:pointer}code{overflow-wrap:anywhere}label{display:block}small{display:block}fieldset{margin:18px 0}</style>${content}</html>`,{status,headers});
}
async function form(request:Request):Promise<URLSearchParams> {
  if(!(request.headers.get('Content-Type')??'').includes('application/x-www-form-urlencoded')) throw new ControlError('INVALID_CONTENT_TYPE',415);
  return new URLSearchParams(await boundedText(request,8192));
}
export async function authorize(request:Request,env:Env):Promise<Response> {
  try {
    if(!sameOrigin(request,env)) throw new ControlError('INVALID_ORIGIN',403);
    if(request.method==='GET') {
      const parsed=await env.OAUTH_PROVIDER.parseAuthRequest(request);
      // Require S256 for every client, including confidential clients.
      if(parsed.codeChallengeMethod!=='S256' || !parsed.codeChallenge) throw new ControlError('PKCE_S256_REQUIRED');
      const info=await env.OAUTH_PROVIDER.describeConsent(parsed);
      const transaction=await env.OAUTH_PROVIDER.beginConsent(parsed);
      const scopeProof=await seal({expires:Date.now()+300_000,nonce:randomToken(24),scopes:parsed.scope,handleHash:await digest(transaction.handle)},env,'consent');
      return html(`<h1>Connect NewPipe AI Control</h1><p>Authorize <strong>${esc(info.clientName)}</strong>${info.clientDomain?' from <strong>'+esc(info.clientDomain)+'</strong>':''} to control your paired NewPipe devices.</p><p>Authorization returns to <strong>${esc(info.redirectHost)}</strong>.</p>${info.redirectIsLoopback?'<p><strong>This redirects to a local application. Any local process could be listening.</strong></p>':''}<form method="post" action="/authorize"><input type="hidden" name="handle" value="${esc(transaction.handle)}"><input type="hidden" name="scope_proof" value="${esc(scopeProof)}"><label>Owner setup key<input name="owner_key" type="password" required autocomplete="current-password"></label><fieldset><legend>Standing permissions</legend>${parsed.scope.filter(x=>x!=='mcp.connect' && SCOPES.includes(x as typeof SCOPES[number])).map(scope=>`<label><input type="checkbox" name="scope" value="${esc(scope)}" ${scope.endsWith('.delete')?'':'checked'}> ${esc(scope)}</label>`).join('')}</fieldset><p>Tag/category deletion removes metadata only, keeping saved videos and playlists. Library deletion removes saved items from every local playlist on the selected device. These permissions do not add per-action approval prompts. The phone's local permissions remain the limit.</p><button name="decision" value="allow">Authorize</button> <button name="decision" value="deny" formnovalidate>Deny</button></form>`,transaction.headers);
    }
    if(request.method!=='POST') throw new ControlError('METHOD_NOT_ALLOWED',405);
    const values=await form(request); const handle=values.get('handle')??'';
    if(values.get('decision')==='deny') {
      const denied=await env.OAUTH_PROVIDER.denyConsent(request,handle);
      return new Response(null,{status:302,headers:denied.headers});
    }
    if(!await verifyOwner(values.get('owner_key')??'',env)) throw new ControlError('NOT_AUTHORIZED',401);
    const scopes=['mcp.connect',...new Set(values.getAll('scope').filter(s=>SCOPES.includes(s as typeof SCOPES[number])))];
    const proof=await open(values.get('scope_proof')??'',env,'consent') as (AdminSession & {scopes?:unknown;handleHash?:unknown})|null;
    if(!proof || proof.handleHash!==await digest(handle) || !Array.isArray(proof.scopes) || !scopes.every(s=>(proof.scopes as unknown[]).includes(s))) throw new ControlError('INVALID_SCOPE',400);
    const approved=await env.OAUTH_PROVIDER.approveConsent(request,handle,{scope:scopes});
    if(approved.request.codeChallengeMethod!=='S256' || !approved.request.codeChallenge) throw new ControlError('PKCE_S256_REQUIRED');
    if(!scopes.every(scope=>approved.request.scope.includes(scope))) throw new ControlError('INVALID_SCOPE',400);
    const info=await env.OAUTH_PROVIDER.describeConsent(approved.request);
    const clientId='c_'+await digest(approved.request.clientId);
    const granted=await env.OAUTH_PROVIDER.completeAuthorization({request:approved.request,userId:env.OWNER_ID,scope:scopes,
      metadata:{clientName:cleanLabel(info.clientName)},props:{ownerId:env.OWNER_ID,clientId,clientName:cleanLabel(info.clientName)}});
    approved.headers.set('Location',granted.redirectTo);
    return new Response(null,{status:302,headers:approved.headers});
  } catch(error) {
    if(error instanceof AuthorizationError && error.redirectTo) {
      // Only the library's already-validated redirect is eligible for an OAuth error response.
      return Response.redirect(error.redirectTo,302);
    }
    return error instanceof ControlError ? html(`<h1>Connection not authorized</h1><p>${esc(error.code)}</p>`,new Headers(),error.status) : failure(error);
  }
}
async function ownerAuthorized(request:Request, env:Env, csrf?:unknown):Promise<boolean> {
  const bearer=request.headers.get('Authorization');
  if(bearer?.startsWith('Bearer ') && await verifyOwner(bearer.slice(7),env)) return true;
  const session=await open(cookie(request,adminCookie),env);
  return session!==null && sameOrigin(request,env) && typeof csrf==='string' && constantTime(csrf,session.nonce);
}
export async function administration(request:Request,env:Env):Promise<Response> {
  try {
    if(!sameOrigin(request,env)) throw new ControlError('INVALID_ORIGIN',403);
    const path=new URL(request.url).pathname;
    const session=await open(cookie(request,adminCookie),env);
    if(path==='/setup' && request.method==='GET') {
      if(!session) return html('<h1>NewPipe AI Control setup</h1><p>This private owner portal pairs devices and manages access. It is not required for individual catalog actions.</p><form method="post" action="/setup"><label>Owner setup key<input name="owner_key" type="password" required autocomplete="current-password"></label><button>Sign in</button></form>');
      const state=await (await account(env).fetch('https://internal/list')).json() as {devices:{deviceId:string;name:string;revoked:boolean}[];blocked_clients:string[]};
      const page=await env.OAUTH_PROVIDER.listUserGrants(env.OWNER_ID,{limit:100});
      const grantRows=await Promise.all(page.items.map(async grant=>{
        const client='c_'+await digest(grant.clientId);
        return `<p><strong>${esc(cleanLabel(String(grant.metadata?.clientName??grant.clientId)))}</strong><br>${esc(grant.scope.join(', '))}</p><form method="post" action="/admin/revoke-grant"><input type="hidden" name="csrf" value="${esc(session.nonce)}"><input type="hidden" name="grant_id" value="${esc(grant.id)}"><input type="hidden" name="client_id" value="${client}"><button>Revoke this authorization</button></form>`;
      }));
      const grantSection='<h2>Authorized clients</h2>'+grantRows.join('')+(page.cursor?'<p>More authorizations exist; use the authenticated grant-list endpoint to paginate.</p>':'')+
        '<h2>Blocked clients</h2>'+state.blocked_clients.map(client=>`<form method="post" action="/admin/unblock-client"><input type="hidden" name="csrf" value="${esc(session.nonce)}"><input type="hidden" name="client_id" value="${esc(client)}"><code>${esc(client)}</code> <button>Allow this client to authorize again</button></form>`).join('');
      return html(`<h1>NewPipe AI Control</h1><p>MCP endpoint: <code>${esc(env.PUBLIC_ORIGIN)}/mcp</code></p><form method="post" action="/admin/pairings"><input type="hidden" name="csrf" value="${esc(session.nonce)}"><button>Create a five-minute pairing code</button></form><h2>Paired devices</h2>${state.devices.map(d=>`<p><strong>${esc(d.name)}</strong> ${d.revoked?'(revoked)':''}<br><code>${esc(d.deviceId)}</code></p><form method="post" action="/admin/revoke-device"><input type="hidden" name="csrf" value="${esc(session.nonce)}"><input type="hidden" name="device_id" value="${esc(d.deviceId)}"><button>Revoke this device</button></form>`).join('')}${grantSection}<form method="post" action="/admin/logout"><input type="hidden" name="csrf" value="${esc(session.nonce)}"><button>Sign out</button></form>`);
    }
    if(path==='/setup' && request.method==='POST') {
      const values=await form(request);
      if(!await verifyOwner(values.get('owner_key')??'',env)) throw new ControlError('NOT_AUTHORIZED',401);
      const next={expires:Date.now()+3_600_000,nonce:randomToken(24)};
      return new Response(null,{status:303,headers:{Location:'/setup','Set-Cookie':`${adminCookie}=${await seal(next,env)}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=3600`,'Cache-Control':'no-store'}});
    }
    if(request.method!=='POST') throw new ControlError('METHOD_NOT_ALLOWED',405);
    const isForm=(request.headers.get('Content-Type')??'').includes('application/x-www-form-urlencoded');
    const values=isForm ? Object.fromEntries(await form(request)) : await body(request,8192);
    if(!await ownerAuthorized(request,env,values.csrf)) throw new ControlError('NOT_AUTHORIZED',401);
    if(path==='/admin/logout') return new Response(null,{status:303,headers:{Location:'/setup','Set-Cookie':`${adminCookie}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0`}});
    if(path==='/admin/grants') {
      const cursor=typeof values.cursor==='string' && values.cursor.length<=2048?values.cursor:undefined;
      return response(await env.OAUTH_PROVIDER.listUserGrants(env.OWNER_ID,{limit:100,cursor}));
    }
    if(path==='/admin/revoke-grant') {
      assert(typeof values.grant_id==='string' && values.grant_id.length<=200 && typeof values.client_id==='string');
      let cursor:string|undefined; let found=false;
      for(let pages=0;pages<10;pages++) {
        const grants=await env.OAUTH_PROVIDER.listUserGrants(env.OWNER_ID,{limit:100,cursor});
        const grant=grants.items.find(g=>g.id===values.grant_id);
        if(grant) { if('c_'+await digest(grant.clientId)!==values.client_id) throw new ControlError('NOT_AUTHORIZED',403); found=true; break; }
        cursor=grants.cursor; if(!cursor) break;
      }
      if(!found) throw new ControlError('GRANT_NOT_FOUND',404);
      const block=await account(env).fetch(new Request('https://internal/block-client',{method:'POST',body:JSON.stringify({client_id:values.client_id})}));
      if(!block.ok) return block;
      await env.OAUTH_PROVIDER.revokeGrant(values.grant_id,env.OWNER_ID);
      return isForm?new Response(null,{status:303,headers:{Location:'/setup'}}):response({revoked:true});
    }
    const routes:Record<string,string>={'/admin/pairings':'/pairing','/admin/revoke-device':'/revoke-device','/admin/revoke-client':'/block-client','/admin/unblock-client':'/unblock-client'};
    const route=routes[path]; if(!route) throw new ControlError('NOT_FOUND',404);
    const r=await account(env).fetch(new Request('https://internal'+route,{method:'POST',body:JSON.stringify(values)}));
    if(!isForm || !r.ok) return r;
    const data=await r.json() as {pairing_code?:string};
    return data.pairing_code ? html(`<h1>Pair your NewPipe installation</h1><p>Open AI Control in NewPipe, enter gateway <code>${esc(env.PUBLIC_ORIGIN)}</code> and this one-time code:</p><p><code>${esc(data.pairing_code)}</code></p><p>The code expires in five minutes. Pairing grants only the permissions you enable in NewPipe.</p><a href="/setup">Back to devices</a>`) : new Response(null,{status:303,headers:{Location:'/setup'}});
  } catch(error) { return failure(error); }
}
