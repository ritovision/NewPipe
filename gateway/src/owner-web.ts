import {account, assert, b64, body, boundedText, constantTime, ControlError, digest, Env, esc, failure, randomToken, response, verifyOwner} from './shared';
import type {OwnerAuthority, OwnerSession} from './owner-state';
import browserScript from '../.generated/passkeys-browser';

export const ADMIN_COOKIE = '__Host-newpipe-admin';
const BROWSER_COOKIE = '__Host-newpipe-browser';
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
export interface BrowserContext {hash: string; csrf: string}
export function cookie(request: Request, name: string): string {
  const values = (request.headers.get('Cookie') ?? '').split(';').map(v => v.trim()).filter(v => v.startsWith(name + '='));
  return values.length === 1 ? values[0]!.slice(name.length + 1) : '';
}
export function strictOrigin(request: Request, env: Env): void {
  if (request.headers.get('Origin') !== env.PUBLIC_ORIGIN) throw new ControlError('INVALID_ORIGIN', 403);
}
export function sessionCookie(value: string, age = 3600): string {
  // Lax permits session reuse on a cross-site OAuth navigation. Every privileged
  // POST additionally requires exact Origin and a browser/session-bound CSRF proof.
  return `${ADMIN_COOKIE}=${value}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=${age}`;
}
export async function ownerCall<T>(env: Env, path: string, input: Record<string, unknown>): Promise<T> {
  const result = await account(env).fetch(new Request('https://internal/owner' + path, {method: 'POST', body: JSON.stringify(input)}));
  const value = await result.json() as {error?: string};
  if (!result.ok) throw new ControlError(value.error ?? 'AUTH_UNAVAILABLE', result.status);
  return value as T;
}
export async function browserContext(request: Request, env: Env, headers?: Headers): Promise<BrowserContext> {
  let value = cookie(request, BROWSER_COOKIE);
  if (!TOKEN.test(value)) {
    if (!headers) throw new ControlError('BROWSER_SESSION_REQUIRED', 401);
    value = randomToken();
    headers.append('Set-Cookie', `${BROWSER_COOKIE}=${value}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400`);
  }
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(env.SESSION_SECRET), {name: 'HMAC', hash: 'SHA-256'}, false, ['sign']);
  const signed = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode('owner-browser-csrf-v1\n' + env.PUBLIC_ORIGIN + '\n' + value));
  return {hash: await digest(value), csrf: b64(new Uint8Array(signed))};
}
export async function getSession(request: Request, env: Env): Promise<OwnerSession | null> {
  const raw = cookie(request, ADMIN_COOKIE);
  if (!TOKEN.test(raw) || !TOKEN.test(cookie(request, BROWSER_COOKIE))) return null;
  const browser = await browserContext(request, env);
  return (await ownerCall<{session: OwnerSession | null}>(env, '/session', {browserHash: browser.hash, sessionHash: await digest(raw)})).session;
}
export async function authority(request: Request, env: Env, csrf: unknown): Promise<OwnerAuthority> {
  const session = await getSession(request, env);
  if (!session || typeof csrf !== 'string' || !constantTime(session.csrf, csrf)) throw new ControlError('NOT_AUTHORIZED', 401);
  strictOrigin(request, env);
  return {sessionHash: session.hash, browserHash: session.browserHash, csrf};
}
export async function form(request: Request): Promise<URLSearchParams> {
  if (!(request.headers.get('Content-Type') ?? '').startsWith('application/x-www-form-urlencoded')) throw new ControlError('INVALID_CONTENT_TYPE', 415);
  return new URLSearchParams(await boundedText(request, 8192));
}
export function oauthRedirectOrigin(uri: string): string {
  let target: URL;
  try { target = new URL(uri); } catch { throw new ControlError('INVALID_REDIRECT_ORIGIN', 400); }
  // WHATWG URL accepts hosts such as '*' and 'example.com;sandbox'. A canonical
  // URL origin is therefore NOT necessarily a safe CSP source expression.
  // Limit interpolation to ordinary DNS/IPv4 names or URL-validated IPv6 literals.
  const dnsLabel = '[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?';
  const dns = new RegExp('^' + dnsLabel + '(?:\\.' + dnsLabel + ')*\\.?$', 'i');
  const ipv6 = /^\[[0-9a-f:]+\]$/i;
  if (!['https:', 'http:'].includes(target.protocol) ||
      !(dns.test(target.hostname) || ipv6.test(target.hostname)) ||
      /[\s;,'"*\\]/.test(target.origin)) throw new ControlError('INVALID_REDIRECT_ORIGIN', 400);
  return target.origin;
}
export function html(content: string, headers = new Headers(), status = 200, validatedRedirectOrigin?: string): Response {
  let formAction = "'self'";
  if (validatedRedirectOrigin) {
    // Only a canonical HTTP(S) origin from the OAuth provider's validated redirect
    // is admitted. No wildcard, client-supplied CSP fragment or arbitrary return URL.
    const source = oauthRedirectOrigin(validatedRedirectOrigin);
    assert(source === validatedRedirectOrigin, 'INVALID_REDIRECT_ORIGIN');
    formAction += ' ' + source;
  }
  headers.set('Content-Type', 'text/html; charset=utf-8');
  headers.set('Cache-Control', 'no-store'); headers.set('Referrer-Policy', 'same-origin');
  headers.set('X-Content-Type-Options', 'nosniff');
  headers.set('Content-Security-Policy', `default-src 'none'; script-src 'self'; connect-src 'self'; style-src 'unsafe-inline'; form-action ${formAction}; frame-ancestors 'none'; base-uri 'none'`);
  headers.set('Permissions-Policy', 'publickey-credentials-get=(self), publickey-credentials-create=(self)');
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NewPipe AI Control</title><script src="/auth/passkeys.js" defer></script><style>body{font:16px system-ui;max-width:700px;margin:40px auto;padding:0 20px;line-height:1.55;color-scheme:light dark}input,button{font:inherit;padding:10px;margin:6px 0;max-width:95%}input[type=password],input[type=text]{width:95%}button{cursor:pointer}code{overflow-wrap:anywhere}label{display:block}fieldset,section,details{margin:20px 0}#auth-status{white-space:pre-line}li{margin:12px 0}</style></head><body>${content}</body></html>`, {status, headers});
}
export function loginPanel(browser: BrowserContext, flow = '', pairing = ''): string {
  return `<section data-auth-root data-browser-csrf="${esc(browser.csrf)}" data-flow="${esc(flow)}" data-pairing="${esc(pairing)}"><h2>Sign in</h2><p>Use a passkey with your fingerprint, device PIN or supported device unlock. This signs you into your private gateway, not into ChatGPT.</p><button type="button" data-login>Sign in with passkey</button><p id="auth-status" role="status" aria-live="polite"></p><details><summary>First setup or emergency recovery</summary><p>Use your existing owner setup key once to register a passkey, or to recover access after losing your passkeys. Do not paste it into chat.</p><form method="post" action="/setup"><input type="hidden" name="browser_csrf" value="${esc(browser.csrf)}"><input type="hidden" name="flow" value="${esc(flow)}"><input type="hidden" name="pairing" value="${esc(pairing)}"><label>Owner setup key<input name="owner_key" type="password" required autocomplete="current-password" maxlength="200"></label><button>Recover access / first setup</button></form></details><noscript>Passkeys require JavaScript. Enable JavaScript for this gateway, or use emergency recovery.</noscript></section>`;
}
export async function managementPanel(env: Env, session: OwnerSession): Promise<string> {
  const {credentials} = await ownerCall<{credentials: {id: string; label: string; createdAt: number; lastUsedAt: number; backedUp: boolean}[]}>(env, '/credentials', {sessionHash: session.hash, browserHash: session.browserHash});
  const initial = session.recovery && session.bootstrapEnrollment && session.createdAt + 300_000 > Date.now();
  const rows = credentials.map(c => `<li><strong>${esc(c.label)}</strong><br>Added ${esc(new Date(c.createdAt).toISOString())}${c.lastUsedAt ? '<br>Last used ' + esc(new Date(c.lastUsedAt).toISOString()) : ''}<br><button type="button" data-remove="${esc(c.id)}" data-last="${credentials.length === 1}">Remove this passkey</button></li>`).join('');
  return `<section data-auth-root data-csrf="${esc(session.csrf)}" data-bootstrap="${initial}"><h2>Passkeys</h2><p>Passkeys authenticate the owner. Removing one signs out its browser sessions; existing AI-client grants and paired devices have separate revoke controls below.</p>${credentials.length ? '<ul>' + rows + '</ul>' : '<p>No passkeys registered yet.</p>'}<label>New passkey label<input type="text" id="passkey-label" value="My phone" maxlength="80"></label><button type="button" data-register>Add passkey</button><p>Keep another passkey or your protected emergency key as a recovery option.</p><p id="auth-status" role="status" aria-live="polite"></p><details><summary>Recovery and owner-session reset</summary><label>Recovery key for the next sensitive action<input id="recovery-key" type="password" autocomplete="off" maxlength="200"></label><p>Use this only when passkeys are unavailable, or to remove the last passkey. It is cleared after the request.</p><button type="button" data-reset>Revoke all owner browser sessions</button><p>This does not revoke existing ChatGPT grants or paired devices. Manage those separately below.</p></details></section>`;
}
export async function recover(request: Request, env: Env, values: URLSearchParams): Promise<Response> {
  strictOrigin(request, env);
  const browser = await browserContext(request, env);
  if (!constantTime(browser.csrf, values.get('browser_csrf') ?? '')) throw new ControlError('INVALID_CSRF', 403);
  if (!await verifyOwner(values.get('owner_key') ?? '', env)) throw new ControlError('NOT_AUTHORIZED', 401);
  const flow = values.get('flow') ?? '';
  assert(flow === '' || TOKEN.test(flow), 'INVALID_CONSENT');
  const pairing = values.get('pairing') ?? '';
  assert(pairing === '' || (flow === '' && /^[0-9a-f]{64}$/.test(pairing)), 'INVALID_PAIRING_REQUEST');
  const result = await ownerCall<{token: string}>(env, '/recover', {browserHash: browser.hash, recoveryVerified: true});
  return new Response(null, {status: 303, headers: {'Set-Cookie': sessionCookie(result.token),
    Location: flow ? '/authorize/continue?flow=' + encodeURIComponent(flow) : pairing ? '/pair/device?request=' + pairing : '/setup', 'Cache-Control': 'no-store'}});
}
export async function ownerRoutes(request: Request, env: Env): Promise<Response> {
  try {
    const path = new URL(request.url).pathname.slice('/auth'.length);
    if (path === '/passkeys.js' && request.method === 'GET') return new Response(browserScript, {
      headers: {'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'},
    });
    if (request.method !== 'POST') throw new ControlError('METHOD_NOT_ALLOWED', 405);
    strictOrigin(request, env);
    if (!(request.headers.get('Content-Type') ?? '').startsWith('application/json')) throw new ControlError('INVALID_CONTENT_TYPE', 415);
    if (!(await env.AUTH_LIMITER.limit({key: 'owner-passkeys-global'})).success) throw new ControlError('RATE_LIMITED', 429);
    const values = await body(request, 65_536);
    const browser = await browserContext(request, env);
    let auth: Record<string, unknown> = {browserHash: browser.hash};
    if (path === '/login/options' || path === '/login/finish' || path === '/login/cancel') {
      if (typeof values.browser_csrf !== 'string' || !constantTime(browser.csrf, values.browser_csrf)) throw new ControlError('INVALID_CSRF', 403);
    } else {
      auth = {...await authority(request, env, values.csrf)};
    }
    // Whitelist individual fields. The request must never supply recoveryVerified,
    // browserHash, sessionHash, epoch, or any internal authority field.
    const allowed = new Set(['/login/options','/login/finish','/login/cancel','/challenge/cancel','/stepup/options','/stepup/finish',
      '/recovery-stepup','/register/options','/register/finish','/credentials/remove','/sessions/reset']);
    if (!allowed.has(path)) throw new ControlError('NOT_FOUND', 404);
    const input: Record<string, unknown> = {...auth,
      challengeId: values.challengeId, response: values.response, action: values.action, target: values.target,
      permit: values.permit, label: values.label, credentialId: values.credentialId,
      confirmLast: values.confirmLast === true, confirmReset: values.confirmReset === true};
    if (path === '/recovery-stepup') {
      if (typeof values.owner_key !== 'string' || !await verifyOwner(values.owner_key, env)) throw new ControlError('NOT_AUTHORIZED', 401);
      input.recoveryVerified = true;
    }
    const result = await ownerCall<Record<string, unknown>>(env, path, input);
    if (path === '/login/finish' || path === '/sessions/reset') {
      assert(typeof result.token === 'string', 'AUTH_UNAVAILABLE');
      const out = response({authenticated: true}); out.headers.set('Set-Cookie', sessionCookie(result.token)); return out;
    }
    return response(result);
  } catch (error) { return failure(error); }
}
