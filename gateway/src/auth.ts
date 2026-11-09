import { AuthorizationError } from '@cloudflare/workers-oauth-provider';
import { account, assert, body, ControlError, digest, Env, esc, failure, response, sameOrigin, SCOPES, verifyOwner, cleanLabel } from './shared';
import { authority, browserContext, form, getSession, html, loginPanel, managementPanel, oauthRedirectOrigin, ownerCall, recover, sessionCookie, strictOrigin } from './owner-web';
import type {BrowserContext} from './owner-web';
import type {Consent, OwnerAuthority, OwnerSession} from './owner-state';

function consentPage(consent: Consent, session: OwnerSession | null, browser: BrowserContext): string {
  const details = `<h1>Connect NewPipe AI Control</h1><p>Authorize <strong>${esc(consent.clientName)}</strong>${consent.clientDomain ? ' from <strong>' + esc(consent.clientDomain) + '</strong>' : ''} to control your paired NewPipe devices.</p><p>Authorization returns to <strong>${esc(consent.redirectHost)}</strong>.</p>${consent.redirectIsLoopback ? '<p><strong>This redirects to a local application. Any local process could be listening.</strong></p>' : ''}`;
  const binding = `<input type="hidden" name="flow" value="${esc(consent.id)}"><input type="hidden" name="handle" value="${esc(consent.handle)}"><input type="hidden" name="scope_proof" value="${esc(consent.proof)}">`;
  if (!session) return details + loginPanel(browser, consent.id) + `<p>Signing in does not approve this connection. Review its permissions afterward.</p><form method="post" action="/authorize">${binding}<button name="decision" value="deny">Deny connection</button></form>`;
  const checks = consent.scopes.filter(s => s !== 'mcp.connect' && SCOPES.includes(s as typeof SCOPES[number]))
    .map(s => `<label><input type="checkbox" name="scope" value="${esc(s)}" ${s.endsWith('.delete') ? '' : 'checked'}> ${esc(s)}</label>`).join('');
  return details + `<form method="post" action="/authorize">${binding}<input type="hidden" name="csrf" value="${esc(session.csrf)}"><fieldset><legend>Standing permissions</legend>${checks}</fieldset><p>Tag/category deletion removes metadata only, keeping saved videos and playlists. Library deletion removes saved items from every local playlist on the selected device. These permissions do not add per-action approval prompts. The phone's local permissions remain the limit.</p><button name="decision" value="allow">Authorize</button> <button name="decision" value="deny" formnovalidate>Deny</button></form>`;
}

export async function authorize(request: Request, env: Env): Promise<Response> {
  try {
    if (!sameOrigin(request, env)) throw new ControlError('INVALID_ORIGIN', 403);
    const url = new URL(request.url);
    if (request.method === 'GET') {
      const session = await getSession(request, env);
      if (url.pathname === '/authorize/continue') {
        if (!session) throw new ControlError('CONSENT_SESSION_ENDED', 401);
        const browser = await browserContext(request, env);
        const consent = await ownerCall<Consent>(env, '/consent/resume', {browserHash: browser.hash,
          sessionHash: session.hash, flow: url.searchParams.get('flow')});
        return html(consentPage(consent, session, browser), new Headers(), 200, consent.redirectOrigin);
      }
      const parsed = await env.OAUTH_PROVIDER.parseAuthRequest(request);
      if (parsed.codeChallengeMethod !== 'S256' || !parsed.codeChallenge) throw new ControlError('PKCE_S256_REQUIRED');
      const info = await env.OAUTH_PROVIDER.describeConsent(parsed);
      const redirectOrigin = oauthRedirectOrigin(parsed.redirectUri);
      const transaction = await env.OAUTH_PROVIDER.beginConsent(parsed);
      const browser = await browserContext(request, env, transaction.headers);
      const consent = await ownerCall<Consent>(env, '/consent/begin', {browserHash: browser.hash,
        sessionHash: session?.hash, handle: transaction.handle, scopes: parsed.scope, clientName: info.clientName,
        clientDomain: info.clientDomain ?? '', redirectHost: info.redirectHost, redirectOrigin, redirectIsLoopback: info.redirectIsLoopback});
      return html(consentPage(consent, session, browser), transaction.headers, 200, consent.redirectOrigin);
    }
    if (request.method !== 'POST' || url.pathname !== '/authorize') throw new ControlError('METHOD_NOT_ALLOWED', 405);
    strictOrigin(request, env);
    const values = await form(request), handle = values.get('handle') ?? '';
    const decision = values.get('decision');
    assert(decision === 'allow' || decision === 'deny', 'EXPLICIT_CONSENT_REQUIRED');
    const browser = await browserContext(request, env);
    const session = await getSession(request, env);
    const binding = {browserHash: browser.hash, sessionHash: session?.hash, csrf: values.get('csrf'),
      flow: values.get('flow'), handle, proof: values.get('scope_proof')};
    if (decision === 'deny') {
      await ownerCall(env, '/consent/deny', binding);
      const denied = await env.OAUTH_PROVIDER.denyConsent(request, handle);
      return new Response(null, {status: 302, headers: denied.headers});
    }
    await authority(request, env, values.get('csrf'));
    const submitted = values.getAll('scope');
    assert(submitted.every(s => SCOPES.includes(s as typeof SCOPES[number])), 'INVALID_SCOPE');
    const scopes = [...new Set(['mcp.connect', ...submitted])];
    // This single-use durable claim is the authorization commit point. It rechecks
    // live owner/session/credential/epoch and exact scopes. Logout after this commit
    // does not retract an approved OAuth grant (grant revocation is separate).
    await ownerCall(env, '/consent/claim', {...binding, scopes});
    const approved = await env.OAUTH_PROVIDER.approveConsent(request, handle, {scope: scopes});
    if (approved.request.codeChallengeMethod !== 'S256' || !approved.request.codeChallenge) throw new ControlError('PKCE_S256_REQUIRED');
    assert(scopes.every(s => approved.request.scope.includes(s)), 'INVALID_SCOPE');
    const info = await env.OAUTH_PROVIDER.describeConsent(approved.request);
    const clientId = 'c_' + await digest(approved.request.clientId);
    const granted = await env.OAUTH_PROVIDER.completeAuthorization({request: approved.request,
      userId: env.OWNER_ID, scope: scopes, metadata: {clientName: cleanLabel(info.clientName)},
      props: {ownerId: env.OWNER_ID, clientId, clientName: cleanLabel(info.clientName)}});
    approved.headers.set('Location', granted.redirectTo);
    return new Response(null, {status: 302, headers: approved.headers});
  } catch (error) {
    if (error instanceof AuthorizationError && error.redirectTo) return Response.redirect(error.redirectTo, 302);
    return error instanceof ControlError ? html(`<h1>Connection not authorized</h1><p>${esc(error.code)}</p><p>Return to the connecting application and start a new authorization request.</p>`, new Headers(), error.status) : failure(error);
  }
}
async function ownerAuthorized(request: Request, env: Env, csrf?: unknown): Promise<OwnerAuthority | undefined> {
  const bearer = request.headers.get('Authorization');
  if (bearer?.startsWith('Bearer ') && await verifyOwner(bearer.slice(7), env)) return undefined;
  return authority(request, env, csrf);
}

export async function administration(request:Request,env:Env):Promise<Response> {
  try {
    if(!sameOrigin(request,env)) throw new ControlError('INVALID_ORIGIN',403);
    const path=new URL(request.url).pathname;
    const session=await getSession(request,env);
    if(path==='/setup' && request.method==='GET') {
      const headers=new Headers(), browser=await browserContext(request,env,headers);
      if(!session) return html('<h1>NewPipe AI Control setup</h1><p>This private owner portal pairs devices and manages access. It is not required for individual catalog actions.</p>'+loginPanel(browser),headers);
      const state=await (await account(env).fetch('https://internal/list')).json() as {devices:{deviceId:string;name:string;revoked:boolean}[];blocked_clients:string[]};
      const page=await env.OAUTH_PROVIDER.listUserGrants(env.OWNER_ID,{limit:100});
      const grantRows=await Promise.all(page.items.map(async grant=>{
        const client='c_'+await digest(grant.clientId);
        return `<p><strong>${esc(cleanLabel(String(grant.metadata?.clientName??grant.clientId)))}</strong><br>${esc(grant.scope.join(', '))}</p><form method="post" action="/admin/revoke-grant"><input type="hidden" name="csrf" value="${esc(session.csrf)}"><input type="hidden" name="grant_id" value="${esc(grant.id)}"><input type="hidden" name="client_id" value="${client}"><button>Revoke this authorization</button></form>`;
      }));
      const grantSection='<h2>Authorized clients</h2>'+grantRows.join('')+(page.cursor?'<p>More authorizations exist; use the authenticated grant-list endpoint to paginate.</p>':'')+
        '<h2>Blocked clients</h2>'+state.blocked_clients.map(client=>`<form method="post" action="/admin/unblock-client"><input type="hidden" name="csrf" value="${esc(session.csrf)}"><input type="hidden" name="client_id" value="${esc(client)}"><code>${esc(client)}</code> <button>Allow this client to authorize again</button></form>`).join('');
      return html(`<h1>NewPipe AI Control</h1>${await managementPanel(env,session)}<p>MCP endpoint: <code>${esc(env.PUBLIC_ORIGIN)}/mcp</code></p><form method="post" action="/admin/pairings"><input type="hidden" name="csrf" value="${esc(session.csrf)}"><button>Create a five-minute pairing code</button></form><h2>Paired devices</h2>${state.devices.map(d=>`<p><strong>${esc(d.name)}</strong> ${d.revoked?'(revoked)':''}<br><code>${esc(d.deviceId)}</code></p><form method="post" action="/admin/revoke-device"><input type="hidden" name="csrf" value="${esc(session.csrf)}"><input type="hidden" name="device_id" value="${esc(d.deviceId)}"><button>Revoke this device</button></form>`).join('')}${grantSection}<form method="post" action="/admin/logout"><input type="hidden" name="csrf" value="${esc(session.csrf)}"><button>Sign out</button></form>`);
    }
    if(path==='/setup' && request.method==='POST') {
      return recover(request,env,await form(request));
    }
    if(request.method!=='POST') throw new ControlError('METHOD_NOT_ALLOWED',405);
    const isForm=(request.headers.get('Content-Type')??'').includes('application/x-www-form-urlencoded');
    const values=isForm ? Object.fromEntries(await form(request)) : await body(request,8192);
    const ownerAuthority=await ownerAuthorized(request,env,values.csrf);
    if(path==='/admin/logout') {
      if(!ownerAuthority) throw new ControlError('NOT_AUTHORIZED',401);
      await ownerCall(env,'/logout',{...ownerAuthority});
      return new Response(null,{status:303,headers:{Location:'/setup','Set-Cookie':sessionCookie('',0),'Cache-Control':'no-store'}});
    }
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
      const block=await account(env).fetch(new Request('https://internal/block-client',{method:'POST',body:JSON.stringify({client_id:values.client_id,ownerAuthority})}));
      if(!block.ok) return block;
      await env.OAUTH_PROVIDER.revokeGrant(values.grant_id,env.OWNER_ID);
      return isForm?new Response(null,{status:303,headers:{Location:'/setup'}}):response({revoked:true});
    }
    const routes:Record<string,string>={'/admin/pairings':'/pairing','/admin/revoke-device':'/revoke-device','/admin/revoke-client':'/block-client','/admin/unblock-client':'/unblock-client'};
    const route=routes[path]; if(!route) throw new ControlError('NOT_FOUND',404);
    const r=await account(env).fetch(new Request('https://internal'+route,{method:'POST',body:JSON.stringify({...values,ownerAuthority})}));
    if(!isForm || !r.ok) return r;
    const data=await r.json() as {pairing_code?:string};
    return data.pairing_code ? html(`<h1>Pair your NewPipe installation</h1><p>Open AI Control in NewPipe, enter gateway <code>${esc(env.PUBLIC_ORIGIN)}</code> and this one-time code:</p><p><code>${esc(data.pairing_code)}</code></p><p>The code expires in five minutes. Pairing grants only the permissions you enable in NewPipe.</p><a href="/setup">Back to devices</a>`) : new Response(null,{status:303,headers:{Location:'/setup'}});
  } catch(error) { return failure(error); }
}
