import type { OAuthHelpers } from '@cloudflare/workers-oauth-provider';
import type { ControlAccount } from './account';
import type { DeviceRoom } from './device';

export const SCOPES = ['mcp.connect', 'tags.read', 'tags.write', 'tags.delete', 'library.read', 'library.write', 'library.delete', 'playlist.write', 'playlist.delete', 'playback.control', 'offline_access'] as const;
export const DEVICE_SCOPES = SCOPES.filter(s => s !== 'mcp.connect' && s !== 'offline_access');
export interface Env {
  OAUTH_KV: KVNamespace;
  OAUTH_PROVIDER: OAuthHelpers;
  ACCOUNT: DurableObjectNamespace<ControlAccount>;
  DEVICES: DurableObjectNamespace<DeviceRoom>;
  PUBLIC_ORIGIN: string;
  OWNER_ID: string;
  OWNER_KEY_HASH: string;
  SESSION_SECRET: string;
  AUTH_LIMITER: { limit(input: {key: string}): Promise<{success: boolean}> };
  CONTROL_LIMITER: { limit(input: {key: string}): Promise<{success: boolean}> };
}
export interface Principal { ownerId: string; clientId: string; clientName: string; scopes: string[] }
export interface DeviceRegistration { deviceId: string; name: string; publicKey: string; ownerId: string; revoked: boolean; pairedAt: number }
export interface DeviceHello { version: number; device_id: string; catalog_epoch: string; playback_epoch: string; capabilities: string[]; permissions: string[] }
export interface DeviceReply { request_id: string; device_id: string; outcome: string; code: string; data: Record<string, unknown>; replayed?: boolean }
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const CLIENT = /^c_[0-9a-f]{64}$/;
export function assert(condition: unknown, code = 'INVALID_REQUEST'): asserts condition {
  if (!condition) throw new ControlError(code, 400);
}
export class ControlError extends Error { constructor(public code: string, public status = 400) { super(code); } }
export function response(value: unknown, status = 200): Response {
  return Response.json(value, { status, headers: {'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'} });
}
export function failure(error: unknown): Response {
  return error instanceof ControlError ? response({error: error.code}, error.status) : response({error: 'INTERNAL_ERROR'}, 500);
}
export function esc(value: unknown): string {
  return String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]!));
}
export async function boundedText(request: Request, maximum = 65_536): Promise<string> {
  if (Number(request.headers.get('Content-Length') ?? 0) > maximum) throw new ControlError('PAYLOAD_TOO_LARGE', 413);
  if (!request.body) return '';
  const reader = request.body.getReader(); const parts: Uint8Array[] = []; let size = 0;
  try {
    for (;;) { const {value, done} = await reader.read(); if (done) break;
      size += value.length; if (size > maximum) { await reader.cancel(); throw new ControlError('PAYLOAD_TOO_LARGE', 413); } parts.push(value); }
  } finally { reader.releaseLock(); }
  const all = new Uint8Array(size); let offset = 0; for (const part of parts) { all.set(part, offset); offset += part.length; }
  return new TextDecoder('utf-8', {fatal: true, ignoreBOM: false}).decode(all);
}
export function jsonDepth(text: string, maximum = 18): void {
  let depth = 0, quoted = false, escaped = false;
  for (const c of text) {
    if (quoted) { if (escaped) escaped = false; else if (c === '\\') escaped = true; else if (c === '"') quoted = false; }
    else if (c === '"') quoted = true;
    else if (c === '{' || c === '[') { if (++depth > maximum) throw new ControlError('INVALID_REQUEST'); }
    else if (c === '}' || c === ']') --depth;
  }
}
export async function body(request: Request, maximum = 65_536): Promise<Record<string, unknown>> {
  const text = await boundedText(request, maximum); jsonDepth(text);
  let data: unknown; try { data = JSON.parse(text); } catch { throw new ControlError('INVALID_JSON'); }
  assert(data && typeof data === 'object' && !Array.isArray(data)); return data as Record<string, unknown>;
}
export async function digest(value: string | Uint8Array): Promise<string> {
  const data = typeof value === 'string' ? new TextEncoder().encode(value) : Uint8Array.from(value);
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', data)), b => b.toString(16).padStart(2, '0')).join('');
}
export function b64(bytes: Uint8Array): string { return btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, ''); }
export function unb64(value: string, max = 2048): Uint8Array {
  assert(typeof value === 'string' && value.length <= max && /^[A-Za-z0-9_-]+$/.test(value));
  const bytes = atob(value.replaceAll('-', '+').replaceAll('_', '/')); return Uint8Array.from(bytes, c => c.charCodeAt(0));
}
export function randomToken(bytes = 32): string { return b64(crypto.getRandomValues(new Uint8Array(bytes))); }
export function constantTime(a: string, b: string): boolean { let diff = a.length ^ b.length; for (let i=0; i<Math.max(a.length,b.length); i++) diff |= (a.charCodeAt(i)||0) ^ (b.charCodeAt(i)||0); return diff === 0; }
export async function verifyOwner(key: string, env: Env): Promise<boolean> {
  if (!/^[0-9a-f]{64}$/.test(env.OWNER_KEY_HASH ?? '') || key.length < 32 || key.length > 200) return false;
  if(!(await env.AUTH_LIMITER.limit({key:'owner-signin-global'})).success) return false;
  return constantTime(await digest(key), env.OWNER_KEY_HASH);
}
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return '['+value.map(canonical).join(',')+']';
  if (value && typeof value === 'object') return '{'+Object.entries(value).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([k,v])=>JSON.stringify(k)+':'+canonical(v)).join(',')+'}';
  return JSON.stringify(value) ?? 'null';
}
export function device(env: Env, id: string) { assert(UUID.test(id), 'INVALID_DEVICE'); return env.DEVICES.get(env.DEVICES.idFromName(id)); }
export function account(env: Env) { assert(UUID.test(env.OWNER_ID), 'SERVER_NOT_CONFIGURED'); return env.ACCOUNT.get(env.ACCOUNT.idFromName(env.OWNER_ID)); }
export function sameOrigin(request: Request, env: Env): boolean { const origin=request.headers.get('Origin'); return origin === null || origin === env.PUBLIC_ORIGIN; }

/** Android's SHA256withECDSA returns DER; WebCrypto verifies fixed-width IEEE-P1363. */
export function derToRaw(der: Uint8Array): Uint8Array {
  assert(der.length >= 8 && der.length <= 72 && der[0] === 0x30 && der[1] === der.length-2, 'INVALID_SIGNATURE');
  let pos=2; const raw=new Uint8Array(64);
  for (let part=0; part<2; part++) {
    assert(der[pos++]===2, 'INVALID_SIGNATURE'); const size=der[pos++]!;
    assert(size>=1 && size<=33 && pos+size<=der.length && (der[pos]!&0x80)===0, 'INVALID_SIGNATURE');
    let n=der.slice(pos,pos+size); pos+=size;
    if (n.length>1 && n[0]===0) { assert((n[1]!&0x80)!==0, 'INVALID_SIGNATURE'); n=n.slice(1); }
    assert(n.length<=32, 'INVALID_SIGNATURE'); raw.set(n, part*32+32-n.length);
  }
  assert(pos===der.length, 'INVALID_SIGNATURE'); return raw;
}
export async function verifyDevice(publicKey: string, message: string, signature: string): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey('spki', unb64(publicKey, 256), {name:'ECDSA', namedCurve:'P-256'}, false, ['verify']);
    return await crypto.subtle.verify({name:'ECDSA', hash:'SHA-256'}, key, derToRaw(unb64(signature, 128)), new TextEncoder().encode(message));
  } catch { return false; }
}

export function cleanLabel(value:string):string { return value.replace(/[\p{Cf}\p{Cc}]/gu,'').trim().slice(0,80) || 'Unnamed'; }
