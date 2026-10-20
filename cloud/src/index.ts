/* SPDX-License-Identifier: GPL-3.0-or-later */
import { AdmissionError } from "./admission.ts";
import { counter } from "./protocol.ts";
import { CatalogError, CatalogService, authenticateToken, DEFAULT_SERVICE_LIMITS, TRANSPORT_LIMITS, serviceLimits } from "./service.ts";
import type { Head } from "./protocol.ts";
import { RecoveryService, readRecoveryReference } from "./recovery.ts";
import { RecoveryError, RECOVERY_LIMITS } from "./recovery-format.ts";
import { ReplacementService, canReplace } from "./replacement.ts";

export interface CloudEnvironment {
  DB: D1Database;
  PUBLIC_ORIGIN: string;
  LIBRARY_ID: string;
  SERVICE_GENERATION: string;
  ENABLE_CATALOG_API?: string;
  MAX_CATALOG_BYTES?: string;
  MAX_CATALOG_PAGES?: string;
  ENABLE_RECOVERY_API?: string;
  ENABLE_REPLACEMENT_API?: string;
  MAX_RECOVERY_BYTES?: string;
  RECOVERY?: R2Bucket;
  REQUEST_LIMITER: { limit(options: { key: string }): Promise<{ success: boolean }> };
}
const jsonHeaders = { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store, private",
  "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer" };
const response = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: jsonHeaders });
function fail(code: string, status: number): never { throw new CatalogError(code, status); }
function exact(value: unknown, fields: readonly string[]): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) fail("INVALID_REQUEST", 400);
  const o = value as Record<string, unknown>;
  if (Object.keys(o).length !== fields.length || !fields.every(k => Object.hasOwn(o, k))) fail("INVALID_REQUEST_FIELDS", 400);
  return o;
}
async function body(request: Request): Promise<unknown> {
  if (!/^application\/json(?:;\s*charset=utf-8)?$/i.test(request.headers.get("content-type") ?? "")) fail("JSON_REQUIRED", 415);
  const declared = request.headers.get("content-length");
  if (declared !== null && (!/^[0-9]+$/.test(declared) || Number(declared) > TRANSPORT_LIMITS.requestBytes)) fail("REQUEST_TOO_LARGE", 413);
  const reader = request.body?.getReader(); if (!reader) fail("BODY_REQUIRED", 400);
  const chunks: Uint8Array[] = []; let bytes = 0;
  try {
    while (true) {
      const part = await reader.read(); if (part.done) break;
      bytes += part.value.byteLength;
      if (bytes > TRANSPORT_LIMITS.requestBytes) { await reader.cancel(); fail("REQUEST_TOO_LARGE", 413); }
      chunks.push(part.value);
    }
  } finally { reader.releaseLock(); }
  const input = new Uint8Array(bytes); let offset = 0;
  for (const chunk of chunks) { input.set(chunk, offset); offset += chunk.length; }
  try { return JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(input)); }
  catch { return fail("INVALID_JSON", 400); }
}
function reference(raw: Record<string, unknown>): { sequence: string; digest: string } {
  if (typeof raw.sequence !== "string" || typeof raw.digest !== "string" || !/^[0-9a-f]{64}$/.test(raw.digest)) fail("INVALID_OPERATION_REFERENCE", 400);
  try { if (counter(raw.sequence) === 0n) fail("INVALID_SEQUENCE", 400); } catch { fail("INVALID_SEQUENCE", 400); }
  return { sequence: raw.sequence, digest: raw.digest };
}
function environmentNumber(value: string | undefined, fallback: number): number {
  if (value === undefined) return fallback;
  if (!/^[1-9][0-9]{0,8}$/.test(value)) fail("SERVICE_CONFIGURATION_INVALID", 503);
  return Number(value);
}
const paths = new Set(["/v1/connect", "/v1/head", "/v1/pages", "/v1/uploads/start", "/v1/uploads/chunk",
  "/v1/uploads/seal", "/v1/uploads/publish", "/v1/uploads/status", "/v1/uploads/retire",
  "/v1/recovery/status", "/v1/recovery/preview", "/v1/recovery/reserve", "/v1/recovery/advance",
  "/v1/recovery/part", "/v1/recovery/complete", "/v1/recovery/abort", "/v1/recovery/pin",
  "/v1/recovery/release", "/v1/recovery/download", "/v1/recovery/abandon",
  "/v1/replacement/status", "/v1/replacement/preview", "/v1/replacement/reserve",
  "/v1/replacement/publish", "/v1/replacement/settle", "/v1/replacement/abandon"]);

/** This is the deployable typed entry point. Test SQL/fault injection exists only in test/ harnesses. */
export async function handleRequest(request: Request, env: CloudEnvironment): Promise<Response> {
  try {
    if (env.ENABLE_CATALOG_API !== "true") fail("CLOUD_API_DISABLED", 503);
    let origin: URL; try { origin = new URL(env.PUBLIC_ORIGIN); } catch { return fail("SERVICE_CONFIGURATION_INVALID", 503); }
    if (origin.protocol !== "https:" || origin.username || origin.password || origin.pathname !== "/" || origin.search || origin.hash) fail("SERVICE_CONFIGURATION_INVALID", 503);
    const url = new URL(request.url);
    if (url.origin !== origin.origin || url.search || url.hash || request.headers.has("origin") && request.headers.get("origin") !== origin.origin) fail("ORIGIN_REJECTED", 403);
    if (!paths.has(url.pathname)) fail("NOT_FOUND", 404);
    if (request.method !== "POST") fail("METHOD_NOT_ALLOWED", 405);
    if (!env.REQUEST_LIMITER || !await env.REQUEST_LIMITER.limit({ key: "newpipe-cloud-sync-v1:" + (request.headers.get("cf-connecting-ip") ?? "unknown") }).then(r => r.success)) fail("REQUEST_LIMIT", 429);
    const authorization = request.headers.get("authorization") ?? "";
    if (!authorization.startsWith("Bearer ") || authorization.length > 204) fail("INVALID_CREDENTIAL", 401);
    const context = await authenticateToken(env.DB, authorization.slice(7), env.LIBRARY_ID, env.SERVICE_GENERATION);
    const limits = serviceLimits(environmentNumber(env.MAX_CATALOG_BYTES, DEFAULT_SERVICE_LIMITS.maxCatalogBytes),
      environmentNumber(env.MAX_CATALOG_PAGES, DEFAULT_SERVICE_LIMITS.maxPages));
    const recoveryEnabled = env.ENABLE_RECOVERY_API === "true" && env.RECOVERY !== undefined;
    const replacementEnabled = recoveryEnabled && env.ENABLE_REPLACEMENT_API === "true";
    if (url.pathname.startsWith("/v1/replacement/")) {
      if (!replacementEnabled) fail("REPLACEMENT_API_DISABLED", 503);
      if (!context.writable) fail("AUTH_REJECTED", 403);
      const maximum = environmentNumber(env.MAX_RECOVERY_BYTES, RECOVERY_LIMITS.bytes);
      if (maximum < 22 || maximum > RECOVERY_LIMITS.bytes) fail("SERVICE_CONFIGURATION_INVALID", 503);
      const replacement = new ReplacementService(env.DB, env.RECOVERY!, context, limits, maximum), data = await body(request);
      switch (url.pathname) {
        case "/v1/replacement/status": exact(data, []); return response(await replacement.status());
        case "/v1/replacement/preview": return response(await replacement.preview(data));
        case "/v1/replacement/reserve": return response(await replacement.reserve(data));
        case "/v1/replacement/publish": return response(await replacement.publish(data));
        case "/v1/replacement/settle": return response(await replacement.settle(data));
        case "/v1/replacement/abandon": return response(await replacement.abandon(data));
      }
    }
    if (url.pathname.startsWith("/v1/recovery/")) {
      if (!recoveryEnabled) fail("RECOVERY_API_DISABLED", 503);
      if (!context.writable && !["status", "pin", "release", "download"].some(name => url.pathname === "/v1/recovery/" + name)) fail("AUTH_REJECTED", 403);
      const maximum = environmentNumber(env.MAX_RECOVERY_BYTES, RECOVERY_LIMITS.bytes);
      if (maximum < 22 || maximum > RECOVERY_LIMITS.bytes) fail("SERVICE_CONFIGURATION_INVALID", 503);
      const recovery = new RecoveryService(env.DB, env.RECOVERY!, context, maximum);
      if (url.pathname === "/v1/recovery/part") {
        if (request.headers.get("content-type") !== "application/octet-stream") fail("BINARY_PART_REQUIRED", 415);
        const ref = readRecoveryReference({ sequence: request.headers.get("x-recovery-sequence"), archiveId: request.headers.get("x-recovery-archive") });
        const number = request.headers.get("x-recovery-part");
        if (number === null || !/^[1-9][0-9]?$/.test(number)) fail("INVALID_RECOVERY_PART", 400);
        return response(await recovery.uploadPart(ref, Number(number), request.body, request.headers.get("content-length")));
      }
      const data = await body(request);
      switch (url.pathname) {
        case "/v1/recovery/status": exact(data, []); return response(await recovery.status());
        case "/v1/recovery/preview": return response(await recovery.preview(data));
        case "/v1/recovery/reserve": return response(await recovery.reserve(data));
        case "/v1/recovery/abandon": return response(await recovery.abandon(data));
        case "/v1/recovery/advance": return response(await recovery.advance(readRecoveryReference(data)));
        case "/v1/recovery/complete": return response(await recovery.complete(readRecoveryReference(data)));
        case "/v1/recovery/abort": return response(await recovery.abort(readRecoveryReference(data)));
        case "/v1/recovery/pin": return response(await recovery.pin(data));
        case "/v1/recovery/release": return response(await recovery.release(data));
        case "/v1/recovery/download": return recovery.download(data);
      }
    }
    const service = new CatalogService(env.DB, context, limits), data = await body(request);
    switch (url.pathname) {
      case "/v1/connect":
      case "/v1/head": {
        exact(data, []);
        const replacementAllowed = replacementEnabled && await canReplace(env.DB, context);
        return response({ protocol: 1, device: context.device, scope: { service: context.serviceGeneration, library: context.library },
          ...await service.status(), deviceState: await service.deviceState(), limits: { ...limits, requestBytes: TRANSPORT_LIMITS.requestBytes, uploadLifetimeMs: TRANSPORT_LIMITS.stageLifetimeMs },
          capabilities: [...(context.writable ? ["catalog.read", "catalog.initialize", "catalog.sync", "operation.retire"] : ["catalog.read"]),
            ...(recoveryEnabled ? (context.writable ? ["backup.r2", "backup.read"] : ["backup.read"]) : []),
            ...(replacementAllowed ? ["catalog.replace"] : [])],
          unavailableCapabilities: [...(replacementAllowed ? [] : ["catalog.replace"]), ...(recoveryEnabled ? [] : ["backup.r2"])] });
      }
      case "/v1/pages": {
        const p = exact(data, ["head", "cursor"]);
        if (p.cursor !== null && typeof p.cursor !== "string") fail("INVALID_CURSOR", 400);
        return response(await service.download(p.head as Head, p.cursor as string | null));
      }
      case "/v1/uploads/start":
        if ((data as { mode?: unknown })?.mode === "replace" && !replacementEnabled) fail("REPLACEMENT_API_DISABLED", 503);
        return response(await service.start(data));
      case "/v1/uploads/chunk": {
        const p = exact(data, ["sequence", "index", "changes"]);
        if (typeof p.sequence !== "string" || typeof p.index !== "number") fail("INVALID_CHUNK", 400);
        try { if (counter(p.sequence) === 0n) fail("INVALID_SEQUENCE", 400); } catch { fail("INVALID_SEQUENCE", 400); }
        return response(await service.chunk(p.sequence, p.index, p.changes));
      }
      default: {
        const p = reference(exact(data, ["sequence", "digest"]));
        if (url.pathname === "/v1/uploads/seal") return response(await service.seal(p.sequence, p.digest));
        if (url.pathname === "/v1/uploads/publish") return response(await service.publish(p.sequence, p.digest));
        if (url.pathname === "/v1/uploads/retire") return response(await service.retire(p.sequence, p.digest));
        return response(await service.uploadStatus(p.sequence, p.digest));
      }
    }
  } catch (error) {
    if (error instanceof RecoveryError) return response({ error: error.code, ...(error.status >= 500 ? { outcome: "unknown" } : {}) }, error.status);
    if (error instanceof CatalogError) return response({ error: error.code }, error.status);
    if (error instanceof AdmissionError) {
      if (error.outcomeUnknown || error.message === "OUTCOME_UNKNOWN") return response({ error: error.message, outcome: "unknown" }, 503);
      return response({ error: error.message }, error.message === "AUTH_REJECTED" ? 403 : 409);
    }
    // Never return SQL, credential fragments, uploaded titles/URLs or internal exception stacks.
    if (String(error).toLowerCase().includes("constraint failed")) return response({ error: "PRECONDITION_FAILED" }, 409);
    return response({ error: "STORAGE_OR_SERVICE_UNAVAILABLE", outcome: "unknown" }, 503);
  }
}
export default { fetch: handleRequest } satisfies ExportedHandler<CloudEnvironment>;
