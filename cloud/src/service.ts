/* SPDX-License-Identifier: GPL-3.0-or-later */
import { checkedHead, counter, sameHead } from "./protocol.ts";
import type { Head } from "./protocol.ts";
import { guardedCommit, guardedReplacement, inspectOperation, resolveOrRetire } from "./admission.ts";
import type { AdmissionContext, Receipt } from "./admission.ts";
import { byteLength, PAGE_LIMITS, parsePage, validPageId, sha256, readPages, pageManifest } from "./pages.ts";
import type { CatalogPage, PageChange } from "./pages.ts";
import { fingerprint } from "./catalog.ts";
import { REPLACE_GRANT, REPLACEMENT_COOLDOWN_MS, REPLACEMENT_DB_NOW } from "./replacement-state.ts";
import type { ReplacementProof } from "./replacement-state.ts";

export class CatalogError extends Error {
  readonly code: string;
  readonly status: number;
  constructor(code: string, status = 409) { super(code); this.name = "CatalogError"; this.code = code; this.status = status; }
}
function fail(code: string, status = 409): never { throw new CatalogError(code, status); }
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;
const hashPattern = /^[0-9a-f]{64}$/;
export const TRANSPORT_LIMITS = Object.freeze({ requestBytes: 64 * 1024, pageResponse: 8, chunks: 4_096,
  changes: 4_096, stageLifetimeMs: 60 * 60 * 1000, pendingDevices: 4 });
export type ServiceLimits = Readonly<{ maxCatalogBytes: number; maxPages: number }>;
// Conservative local/deployment default. Larger limits require actual environment admission, not a plan upgrade.
export const DEFAULT_SERVICE_LIMITS: ServiceLimits = Object.freeze({ maxCatalogBytes: 512 * 1024, maxPages: 128 });
export function serviceLimits(bytes: number, pages: number): ServiceLimits {
  if (!Number.isSafeInteger(bytes) || bytes < PAGE_LIMITS.pageBytes || bytes > PAGE_LIMITS.totalBytes ||
      !Number.isSafeInteger(pages) || pages < 1 || pages > PAGE_LIMITS.pages) fail("INVALID_SERVICE_LIMITS", 503);
  return Object.freeze({ maxCatalogBytes: bytes, maxPages: pages });
}
function object(value: unknown, fields: readonly string[]): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) fail("INVALID_REQUEST", 400);
  const o = value as Record<string, unknown>;
  if (Object.keys(o).length !== fields.length || !fields.every(k => Object.hasOwn(o, k))) fail("INVALID_REQUEST_FIELDS", 400);
  return o;
}
function count(value: unknown, maximum: number, minimum = 0): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < minimum || value > maximum) fail("INVALID_COUNT", 400);
  return value;
}
export type StageHeader = Readonly<{ mode: "initialize" | "sync" | "replace"; sequence: string; before: Head;
  targetFingerprint: string; chunkCount: number; changeCount: number; payloadBytes: number; digest: string }>;
function exactHead(value: unknown): Head {
  const h = object(value, ["serviceGeneration", "library", "epoch", "revision", "headCounter", "fingerprint"]);
  try { return checkedHead(h as Head); } catch { return fail("INVALID_HEAD", 400); }
}
export function readStageHeader(value: unknown): StageHeader {
  const h = object(value, ["mode", "sequence", "before", "targetFingerprint", "chunkCount", "changeCount", "payloadBytes", "digest"]);
  if (h.mode !== "initialize" && h.mode !== "sync" && h.mode !== "replace") fail("UNSUPPORTED_OPERATION", 400);
  if (typeof h.sequence !== "string") fail("INVALID_SEQUENCE", 400);
  try { if (counter(h.sequence) === 0n) fail("INVALID_SEQUENCE", 400); } catch { fail("INVALID_SEQUENCE", 400); }
  if (typeof h.targetFingerprint !== "string" || !hashPattern.test(h.targetFingerprint) || typeof h.digest !== "string" || !hashPattern.test(h.digest)) fail("INVALID_DIGEST", 400);
  return Object.freeze({ mode: h.mode, sequence: h.sequence, before: exactHead(h.before), targetFingerprint: h.targetFingerprint,
    chunkCount: count(h.chunkCount, TRANSPORT_LIMITS.chunks, 1), changeCount: count(h.changeCount, TRANSPORT_LIMITS.changes, 1),
    payloadBytes: count(h.payloadBytes, PAGE_LIMITS.totalBytes), digest: h.digest });
}
export function readChanges(value: unknown): readonly PageChange[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > TRANSPORT_LIMITS.changes) fail("INVALID_CHANGES", 400);
  let last: string | null = null;
  return Object.freeze(value.map(raw => {
    const p = object(raw, ["id", "body"]);
    if (!validPageId(p.id) || (last !== null && last >= p.id)) fail("INVALID_CHANGE_ID_OR_ORDER", 400);
    last = p.id;
    if (p.body !== null) {
      if (typeof p.body !== "string") fail("INVALID_PAGE_BODY", 400);
      try { parsePage({ id: p.id, body: p.body }); } catch { fail("INVALID_PAGE_BODY", 400); }
    }
    return Object.freeze({ id: p.id, body: p.body as string | null });
  }));
}
/** Exact content-bound operation identity independent of HTTP field order and chunk retransmission. */
export async function uploadDigest(header: Omit<StageHeader, "digest">, changes: readonly PageChange[]): Promise<string> {
  const leafs: unknown[] = [];
  for (const p of [...changes].sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0)) leafs.push([p.id, p.body === null ? null : await sha256(p.body)]);
  const h = header.before;
  return sha256(JSON.stringify(["rv-upload/1", header.mode, header.sequence,
    [h.serviceGeneration, h.library, h.epoch, h.revision, h.headCounter, h.fingerprint], header.targetFingerprint,
    header.chunkCount, header.changeCount, header.payloadBytes, leafs]));
}
export async function makeUpload(mode: "initialize" | "sync" | "replace", sequence: string, before: Head,
  targetFingerprint: string, changes: readonly PageChange[]): Promise<{ header: StageHeader; chunks: readonly (readonly PageChange[])[] }> {
  readChanges(changes);
  const chunks: PageChange[][] = []; let current: PageChange[] = [];
  for (const p of changes) {
    if (byteLength(JSON.stringify({ sequence, index: chunks.length, changes: [p] })) > TRANSPORT_LIMITS.requestBytes) fail("CHUNK_BYTE_LIMIT", 400);
    if (byteLength(JSON.stringify({ sequence, index: chunks.length, changes: [...current, p] })) > TRANSPORT_LIMITS.requestBytes) {
      if (!current.length) fail("CHUNK_BYTE_LIMIT", 400); chunks.push(current); current = [];
    }
    current.push(p);
  }
  if (current.length) chunks.push(current);
  const raw = { mode, sequence, before, targetFingerprint, chunkCount: chunks.length, changeCount: changes.length,
    payloadBytes: changes.reduce((n, p) => n + (p.body === null ? 0 : byteLength(p.body)), 0) };
  const header = readStageHeader({ ...raw, digest: await uploadDigest(raw, changes) });
  return { header, chunks };
}

type CatalogRow = { epoch: string; revision: string; head_counter: string; fingerprint: string;
  initialized: number; page_count: number; payload_bytes: number; manifest: string };
export type CatalogStatus = Readonly<{ head: Head; initialized: boolean; pages: number; bytes: number; manifest: string }>;
type Upload = { sequence: string; digest: string; header: string; status: "UPLOADING" | "SEALED" | "COMMITTED" | "RETIRED";
  expires_at: number; expected_chunks: number; expected_changes: number; expected_bytes: number;
  received_chunks: number; received_changes: number; received_bytes: number; verified_pages: number | null;
  verified_bytes: number | null; verified_manifest: string | null };
const AUTH = `d.library_id=? AND d.device_id=? AND d.token_hash=? AND d.auth_generation=CAST(? AS INTEGER)
 AND d.service_generation=? AND d.revoked=0`;
const STATUS = `SELECT h.epoch,CAST(h.revision AS TEXT) AS revision,CAST(h.head_counter AS TEXT) AS head_counter,h.fingerprint,
 i.initialized,i.page_count,i.payload_bytes,i.manifest FROM cloud_head h JOIN cloud_catalog_info i ON i.library_id=h.library_id
 JOIN cloud_devices d ON d.library_id=h.library_id WHERE ${AUTH}`;
const args = (c: AdmissionContext): string[] => [c.library, c.device, c.tokenHash, c.authGeneration, c.serviceGeneration];
const UPLOAD_SELECT = `SELECT CAST(u.sequence AS TEXT) AS sequence,u.digest,u.header,u.status,u.expires_at,u.expected_chunks,
 u.expected_changes,u.expected_bytes,u.received_chunks,u.received_changes,u.received_bytes,u.verified_pages,u.verified_bytes,u.verified_manifest
 FROM cloud_uploads u JOIN cloud_devices d ON d.library_id=u.library_id AND d.device_id=u.device_id WHERE ${AUTH}`;

/** Real typed catalog service. SQL statements are compiled here; no remote API accepts SQL/auth contexts. */
export class CatalogService {
  private readonly db: D1Database;
  private readonly context: AdmissionContext;
  private readonly limits: ServiceLimits;
  constructor(db: D1Database, context: AdmissionContext, limits: ServiceLimits = DEFAULT_SERVICE_LIMITS) {
    this.db = db; this.context = context; this.limits = limits;
  }
  private statusRow(row: CatalogRow): CatalogStatus {
    return Object.freeze({ head: checkedHead({ serviceGeneration: this.context.serviceGeneration, library: this.context.library,
      epoch: row.epoch, revision: row.revision, headCounter: row.head_counter, fingerprint: row.fingerprint }),
    initialized: row.initialized === 1, pages: row.page_count, bytes: row.payload_bytes, manifest: row.manifest });
  }
  async status(): Promise<CatalogStatus> {
    // D1 without Sessions routes all queries to the primary; do not substitute unconstrained replicas.
    const row = await this.db.prepare(STATUS).bind(...args(this.context)).first<CatalogRow>();
    if (!row) fail("AUTH_OR_LIBRARY_UNAVAILABLE", 401); return this.statusRow(row);
  }
  private async getUpload(): Promise<Upload | null> {
    return this.db.prepare(UPLOAD_SELECT).bind(...args(this.context)).first<Upload>();
  }
  private async requireUpload(sequence: string, digest?: string): Promise<Upload> {
    await this.status();
    const u = await this.getUpload();
    if (!u || u.sequence !== sequence) fail("UPLOAD_NOT_FOUND", 404);
    if (digest !== undefined && u.digest !== digest) fail("REPLAY_MISMATCH"); return u;
  }
  private async writeBatch(condition: string, values: unknown[], mutations: D1PreparedStatement[]): Promise<void> {
    if (mutations.length > 20) fail("STATEMENT_BUDGET_EXCEEDED", 413);
    const guard = this.db.prepare(`INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,
      (SELECT COUNT(*) FROM cloud_devices d JOIN cloud_head h ON h.library_id=d.library_id
      WHERE ${AUTH} AND d.can_write=1 AND (${condition})))`).bind(...args(this.context), ...values);
    await this.db.batch([guard, ...mutations, this.db.prepare("DELETE FROM cloud_commit_assertion WHERE slot=1")]);
  }
  private async resolve(sequence: string, digest: string): Promise<Receipt | null> { return inspectOperation(this.db, this.context, sequence, digest); }
  private view(u: Upload) {
    return { sequence: u.sequence, digest: u.digest, status: u.status, nextChunk: u.received_chunks,
      expectedChunks: u.expected_chunks, expiresAt: String(u.expires_at), verifiedPages: u.verified_pages, verifiedBytes: u.verified_bytes };
  }
  async deviceState(): Promise<{ resolvedSequence: string; upload: { sequence: string; digest: string; status: string } | null; replacement: { attemptId: string; state: string } | null }> {
    const row = await this.db.prepare(`SELECT CAST(d.resolved_sequence AS TEXT) AS sequence FROM cloud_devices d WHERE ${AUTH}`)
      .bind(...args(this.context)).first<{ sequence: string }>();
    if (!row) fail("AUTH_OR_LIBRARY_UNAVAILABLE", 401);
    const upload = await this.getUpload();
    const replacement = await this.db.prepare("SELECT attempt_id AS attemptId,state FROM cloud_replace_attempts WHERE library_id=? AND device_id=? AND state IN('ACTIVE','STALE')")
      .bind(this.context.library, this.context.device).first<{ attemptId: string; state: string }>();
    return { resolvedSequence: row.sequence, upload: upload && (upload.status === "UPLOADING" || upload.status === "SEALED")
      ? { sequence: upload.sequence, digest: upload.digest, status: upload.status } : null, replacement };
  }
  async uploadStatus(sequence: string, digest: string) {
    const r = await this.resolve(sequence, digest); if (r) return { receipt: r };
    return this.view(await this.requireUpload(sequence, digest));
  }
  async start(value: unknown, now = Date.now()) {
    const header = readStageHeader(value), c = this.context;
    if (header.payloadBytes > this.limits.maxCatalogBytes || header.changeCount > this.limits.maxPages * 2) fail("TRANSFER_STORAGE_LIMIT", 413);
    const receipt = await this.resolve(header.sequence, header.digest); if (receipt) return { receipt };
    const status = await this.status();
    if (!sameHead(status.head, header.before)) fail("CLOUD_CHANGED");
    if (header.mode === "initialize" ? status.initialized : !status.initialized) fail("INITIALIZATION_STATE_CHANGED");
    const encoded = JSON.stringify(header), existing = await this.getUpload();
    if (existing?.sequence === header.sequence) {
      if (existing.header !== encoded) fail("REPLAY_MISMATCH");
      if (existing.expires_at <= now) fail("UPLOAD_EXPIRED", 410);
      return this.view(existing);
    }
    const replacementCheck = header.mode === "replace" ? " AND " + REPLACE_GRANT + " AND EXISTS(SELECT 1 FROM cloud_replace_attempts WHERE library_id=d.library_id AND device_id=d.device_id AND state='ACTIVE' AND header=? AND expires_at>?)" : " AND NOT EXISTS(SELECT 1 FROM cloud_replace_attempts WHERE library_id=d.library_id AND device_id=d.device_id AND state='ACTIVE')";
    const condition = `d.resolved_sequence=CAST(? AS INTEGER)-1 AND h.epoch=? AND h.revision=CAST(? AS INTEGER)
      AND h.head_counter=CAST(? AS INTEGER) AND h.fingerprint=?
      AND EXISTS(SELECT 1 FROM cloud_catalog_info i WHERE i.library_id=h.library_id AND i.initialized=?)
      AND NOT EXISTS(SELECT 1 FROM cloud_uploads u WHERE u.library_id=d.library_id AND u.device_id=d.device_id AND u.status IN('UPLOADING','SEALED'))
      AND (SELECT COUNT(*) FROM cloud_uploads u WHERE u.library_id=d.library_id AND u.status IN('UPLOADING','SEALED'))<?
      AND COALESCE((SELECT SUM(u.expected_bytes) FROM cloud_uploads u WHERE u.library_id=d.library_id AND u.status IN('UPLOADING','SEALED')),0)+?<=?`;
    try {
      await this.writeBatch(condition + replacementCheck, [header.sequence, header.before.epoch, header.before.revision, header.before.headCounter,
      header.before.fingerprint, Number(header.mode !== "initialize"), TRANSPORT_LIMITS.pendingDevices,
      header.payloadBytes, this.limits.maxCatalogBytes * TRANSPORT_LIMITS.pendingDevices, ...(header.mode === "replace" ? [encoded, now] : [])], [
      this.db.prepare("DELETE FROM cloud_uploads WHERE library_id=? AND device_id=?").bind(c.library, c.device),
      this.db.prepare(`INSERT INTO cloud_uploads(library_id,device_id,sequence,digest,header,status,expires_at,expected_chunks,expected_changes,expected_bytes)
       VALUES(?,?,CAST(? AS INTEGER),?,?,'UPLOADING',?,?,?,?)`).bind(c.library, c.device, header.sequence, header.digest, encoded,
        now + TRANSPORT_LIMITS.stageLifetimeMs, header.chunkCount, header.changeCount, header.payloadBytes)]);
    } catch (error) {
      const replay = await this.resolve(header.sequence, header.digest);
      if (replay) return { receipt: replay };
      const winner = await this.getUpload();
      if (!winner || winner.header !== encoded || winner.expires_at <= now) throw error;
    }
    return this.view(await this.requireUpload(header.sequence, header.digest));
  }
  async chunk(sequence: string, index: number, value: unknown, now = Date.now()) {
    const changes = readChanges(value); count(index, TRANSPORT_LIMITS.chunks - 1);
    if (byteLength(JSON.stringify({ sequence, index, changes })) > TRANSPORT_LIMITS.requestBytes) fail("CHUNK_BYTE_LIMIT", 413);
    const u = await this.requireUpload(sequence), c = this.context;
    const digest = await sha256(JSON.stringify(changes));
    if (u.expires_at <= now) fail("UPLOAD_EXPIRED", 410);
    if (index < u.received_chunks) {
      const previous = await this.db.prepare(`SELECT digest FROM cloud_upload_chunks WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER) AND chunk_index=?`)
        .bind(c.library, c.device, sequence, index).first<{ digest: string }>();
      if (!previous || previous.digest !== digest) fail("CHUNK_REPLAY_MISMATCH"); return this.view(u);
    }
    if (u.status !== "UPLOADING" || index !== u.received_chunks || index >= u.expected_chunks) fail("UPLOAD_STATE_CHANGED");
    const bytes = changes.reduce((n, p) => n + (p.body === null ? 0 : byteLength(p.body)), 0);
    if (u.received_changes + changes.length > u.expected_changes || u.received_bytes + bytes > u.expected_bytes) fail("DECLARED_TRANSFER_LIMIT", 413);
    const condition = `d.resolved_sequence=CAST(? AS INTEGER)-1 AND EXISTS(SELECT 1 FROM cloud_uploads u
      WHERE u.library_id=d.library_id AND u.device_id=d.device_id AND u.sequence=CAST(? AS INTEGER) AND u.status='UPLOADING'
       AND u.digest=? AND u.expires_at>? AND u.received_chunks=? AND u.received_changes+?<=u.expected_changes AND u.received_bytes+?<=u.expected_bytes)`;
    try {
      await this.writeBatch(condition, [sequence, sequence, u.digest, now, index, changes.length, bytes], [
        this.db.prepare(`INSERT INTO cloud_upload_changes(library_id,device_id,sequence,page_id,body)
          SELECT ?,?,CAST(? AS INTEGER),json_extract(value,'$.id'),json_extract(value,'$.body') FROM json_each(?)`)
          .bind(c.library, c.device, sequence, JSON.stringify(changes)),
        this.db.prepare("INSERT INTO cloud_upload_chunks VALUES(?,?,CAST(? AS INTEGER),?,?)").bind(c.library, c.device, sequence, index, digest),
        this.db.prepare(`UPDATE cloud_uploads SET received_chunks=received_chunks+1,received_changes=received_changes+?,received_bytes=received_bytes+?
          WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER)`).bind(changes.length, bytes, c.library, c.device, sequence)]);
    } catch (error) {
      // A raced exact retransmission is success only when its recorded chunk bytes agree.
      await this.status();
      const recorded = await this.db.prepare("SELECT digest FROM cloud_upload_chunks WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER) AND chunk_index=?")
        .bind(c.library, c.device, sequence, index).first<{ digest: string }>();
      if (!recorded || recorded.digest !== digest) throw error;
    }
    return this.view(await this.requireUpload(sequence, u.digest));
  }
  async seal(sequence: string, digest: string, now = Date.now()) {
    const r = await this.resolve(sequence, digest); if (r) return { receipt: r };
    const u = await this.requireUpload(sequence, digest), c = this.context;
    if (u.expires_at <= now) fail("UPLOAD_EXPIRED", 410);
    if (u.status === "SEALED") return this.view(u);
    if (u.status !== "UPLOADING" || u.received_chunks !== u.expected_chunks || u.received_changes !== u.expected_changes || u.received_bytes !== u.expected_bytes) fail("UPLOAD_INCOMPLETE");
    const header = readStageHeader(JSON.parse(u.header));
    const result = await this.db.batch([
      this.db.prepare(STATUS).bind(...args(c)),
      this.db.prepare("SELECT page_id AS id,body FROM cloud_catalog_pages WHERE library_id=? ORDER BY page_id LIMIT ?").bind(c.library, this.limits.maxPages + 1),
      this.db.prepare("SELECT page_id AS id,body FROM cloud_upload_changes WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER) ORDER BY page_id LIMIT ?")
        .bind(c.library, c.device, sequence, TRANSPORT_LIMITS.changes + 1)]);
    const statusRaw = result[0]!.results[0] as CatalogRow | undefined;
    if (!statusRaw) fail("AUTH_OR_LIBRARY_UNAVAILABLE", 401);
    const status = this.statusRow(statusRaw);
    if (!sameHead(status.head, header.before)) fail("CLOUD_CHANGED");
    const previous = result[1]!.results as unknown as CatalogPage[];
    if (previous.length > this.limits.maxPages) fail("CATALOG_STORAGE_LIMIT", 413);
    const changes = result[2]!.results as unknown as PageChange[];
    if (changes.length !== header.changeCount || await uploadDigest(header, changes) !== digest) fail("UPLOAD_DIGEST_MISMATCH");
    const pages = new Map(previous.map(p => [p.id, p]));
    for (const p of changes) {
      if (p.body === null) { if (!pages.delete(p.id)) fail("DELETE_PAGE_NOT_IN_BASE"); }
      else pages.set(p.id, { id: p.id, body: p.body });
    }
    const target = [...pages.values()], bytes = target.reduce((n, p) => n + byteLength(p.body), 0);
    if (target.length > this.limits.maxPages || bytes > this.limits.maxCatalogBytes) fail("CATALOG_STORAGE_LIMIT", 413);
    let computed: string;
    try { computed = await fingerprint((await readPages(target)).catalog); } catch (error) {
      if (error instanceof CatalogError) throw error;
      fail("INVALID_TARGET_CATALOG", 422);
    }
    if (computed !== header.targetFingerprint) fail("TARGET_FINGERPRINT_MISMATCH", 422);
    const manifest = await pageManifest(target);
    try {
      await this.writeBatch(`h.epoch=? AND h.revision=CAST(? AS INTEGER) AND h.head_counter=CAST(? AS INTEGER) AND h.fingerprint=?
       AND d.resolved_sequence=CAST(? AS INTEGER)-1 AND EXISTS(SELECT 1 FROM cloud_uploads u WHERE u.library_id=d.library_id AND u.device_id=d.device_id
        AND u.sequence=CAST(? AS INTEGER) AND u.digest=? AND u.status='UPLOADING' AND u.expires_at>?
        AND u.received_chunks=u.expected_chunks AND u.received_changes=u.expected_changes AND u.received_bytes=u.expected_bytes)`,
      [header.before.epoch, header.before.revision, header.before.headCounter, header.before.fingerprint, sequence, sequence, digest, now], [
        this.db.prepare(`UPDATE cloud_uploads SET status='SEALED',verified_pages=?,verified_bytes=?,verified_manifest=?
          WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER)`).bind(target.length, bytes, manifest, c.library, c.device, sequence)]);
    } catch (error) {
      const current = await this.requireUpload(sequence, digest);
      if (current.status !== "SEALED" || current.verified_manifest !== manifest) throw error;
    }
    return this.view(await this.requireUpload(sequence, digest));
  }
  async publish(sequence: string, digest: string, now = Date.now()): Promise<{ receipt: Receipt }> {
    return this.publishSealed(sequence, digest, now, null);
  }
  /** Called only after the recovery service checked the immutable outgoing object. */
  async publishReplacement(sequence: string, digest: string, proof: ReplacementProof, now = Date.now()): Promise<{ receipt: Receipt }> {
    return this.publishSealed(sequence, digest, now, proof);
  }
  private async publishSealed(sequence: string, digest: string, now: number, proof: ReplacementProof | null): Promise<{ receipt: Receipt }> {
    const existing = await this.resolve(sequence, digest);
    if (existing) { if (existing.outcome === "RETIRED") fail("OPERATION_RETIRED"); return { receipt: existing }; }
    const u = await this.getUpload(), c = this.context;
    if (!u || u.sequence !== sequence || u.status !== "SEALED") {
      const replay = await this.resolve(sequence, digest);
      if (replay) { if (replay.outcome === "RETIRED") fail("OPERATION_RETIRED"); return { receipt: replay }; }
      fail("UPLOAD_NOT_SEALED_OR_EXPIRED");
    }
    if (u.digest !== digest) fail("REPLAY_MISMATCH");
    if (u.expires_at <= now) fail("UPLOAD_NOT_SEALED_OR_EXPIRED");
    const h = readStageHeader(JSON.parse(u.header));
    if (h.mode === "replace" ? !proof || proof.attempt.header !== u.header : proof !== null) fail("REPLACEMENT_PROOF_REQUIRED");
    const predicate = `EXISTS(SELECT 1 FROM cloud_uploads u JOIN cloud_catalog_info i ON i.library_id=u.library_id
      WHERE u.library_id=? AND u.device_id=? AND u.sequence=CAST(? AS INTEGER) AND u.digest=? AND u.status='SEALED'
      AND u.expires_at>? AND u.verified_pages IS NOT NULL AND u.verified_bytes IS NOT NULL AND u.verified_manifest IS NOT NULL
      AND i.initialized=?)`;
    const guard = this.db.prepare(`UPDATE cloud_commit_assertion SET ok=(SELECT CASE WHEN ${predicate} THEN 1 ELSE 0 END) WHERE slot=1`)
      .bind(c.library, c.device, sequence, digest, now, Number(h.mode !== "initialize"));
    const mutations = [guard,
      this.db.prepare(`DELETE FROM cloud_catalog_pages WHERE library_id=? AND page_id IN
        (SELECT page_id FROM cloud_upload_changes WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER) AND body IS NULL)`)
        .bind(c.library, c.library, c.device, sequence),
      this.db.prepare(`INSERT INTO cloud_catalog_pages(library_id,page_id,body)
        SELECT library_id,page_id,body FROM cloud_upload_changes WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER) AND body IS NOT NULL
        ON CONFLICT(library_id,page_id) DO UPDATE SET body=excluded.body`).bind(c.library, c.device, sequence),
      this.db.prepare(`UPDATE cloud_catalog_info SET initialized=1,
        page_count=(SELECT verified_pages FROM cloud_uploads WHERE library_id=? AND device_id=?),
        payload_bytes=(SELECT verified_bytes FROM cloud_uploads WHERE library_id=? AND device_id=?),
        manifest=(SELECT verified_manifest FROM cloud_uploads WHERE library_id=? AND device_id=?) WHERE library_id=?`)
        .bind(c.library, c.device, c.library, c.device, c.library, c.device, c.library),
      this.db.prepare("UPDATE cloud_uploads SET status='COMMITTED' WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER)").bind(c.library, c.device, sequence),
      this.db.prepare("DELETE FROM cloud_upload_changes WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER)").bind(c.library, c.device, sequence),
      this.db.prepare("DELETE FROM cloud_upload_chunks WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER)").bind(c.library, c.device, sequence)];
    const operation = { sequence, digest, before: h.before, nextFingerprint: h.targetFingerprint };
    if (!proof) return { receipt: await guardedCommit(this.db, c, operation, mutations) };
    const a = proof.attempt;
    const replacementGuard = this.db.prepare(`UPDATE cloud_commit_assertion SET ok=(SELECT COUNT(*) FROM cloud_replace_attempts r
      JOIN cloud_devices d ON d.library_id=r.library_id AND d.device_id=r.device_id
      JOIN cloud_backup_control b ON b.library_id=r.library_id
      JOIN cloud_backup_slots s ON s.library_id=r.library_id AND s.archive_id=r.archive_id AND s.slot=r.slot
      JOIN cloud_backup_operations o ON o.library_id=r.library_id AND o.device_id=r.device_id AND o.archive_id=r.archive_id AND o.sequence=r.recovery_sequence
      JOIN cloud_uploads u ON u.library_id=r.library_id AND u.device_id=r.device_id
      WHERE ${AUTH} AND ${REPLACE_GRANT} AND b.service_generation=? AND b.revision<9223372036854775807 AND (b.last_replace_at=0 OR b.last_replace_at<=${REPLACEMENT_DB_NOW}-${REPLACEMENT_COOLDOWN_MS})
      AND r.attempt_id=? AND r.state='ACTIVE' AND r.header=? AND r.next_epoch=? AND r.expires_at>${REPLACEMENT_DB_NOW} AND u.expires_at>${REPLACEMENT_DB_NOW}
      AND u.sequence=CAST(? AS INTEGER) AND u.digest=? AND u.header=r.header
      AND s.archive_id=? AND s.state='READY' AND s.digest=? AND s.object_etag=? AND s.object_version=? AND o.state='DONE'
      AND json_extract(s.manifest,'$.kind')='cloud-catalog'
      AND json_extract(s.manifest,'$.source.head.serviceGeneration')=? AND json_extract(s.manifest,'$.source.head.library')=?
      AND json_extract(s.manifest,'$.source.head.epoch')=? AND json_extract(s.manifest,'$.source.head.revision')=?
      AND json_extract(s.manifest,'$.source.head.headCounter')=? AND json_extract(s.manifest,'$.source.head.fingerprint')=?
      AND json_extract(s.manifest,'$.source.manifest')=(SELECT manifest FROM cloud_catalog_info WHERE library_id=r.library_id)) WHERE slot=1`)
      .bind(...args(c), c.serviceGeneration, a.attempt_id, a.header, a.next_epoch,
        sequence, digest, a.archive_id, proof.digest, proof.objectEtag, proof.objectVersion,
        h.before.serviceGeneration, h.before.library, h.before.epoch, h.before.revision, h.before.headCounter, h.before.fingerprint);
    mutations.push(this.db.prepare("UPDATE cloud_replace_attempts SET state='DONE' WHERE library_id=? AND device_id=? AND attempt_id=?")
      .bind(c.library, c.device, a.attempt_id));
    mutations.push(this.db.prepare(`UPDATE cloud_backup_control SET last_replace_at=${REPLACEMENT_DB_NOW},revision=revision+1 WHERE library_id=? AND revision<9223372036854775807`)
      .bind(c.library));
    return { receipt: await guardedReplacement(this.db, c, operation, a.next_epoch, [replacementGuard], mutations) };
  }
  async retire(sequence: string, digest: string): Promise<{ receipt: Receipt }> {
    // Resolve sequence, terminalize the exact upload and delete its reserved payload
    // in the SAME transaction. A lost reply cannot leave a retired active slot.
    const c = this.context;
    const stagedIdentity = this.db.prepare(`UPDATE cloud_commit_assertion SET ok=CASE WHEN EXISTS(
      SELECT 1 FROM cloud_uploads WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER) AND digest!=?
      ) OR EXISTS(SELECT 1 FROM cloud_replace_attempts WHERE library_id=? AND device_id=? AND state='ACTIVE'
        AND json_extract(header,'$.sequence')=? AND json_extract(header,'$.digest')!=?)
      THEN 0 ELSE 1 END WHERE slot=1`).bind(c.library, c.device, sequence, digest, c.library, c.device, sequence, digest);
    const mutations = [stagedIdentity,
      this.db.prepare("UPDATE cloud_uploads SET status='RETIRED' WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER) AND digest=?")
        .bind(c.library, c.device, sequence, digest),
      this.db.prepare("DELETE FROM cloud_upload_changes WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER)").bind(c.library, c.device, sequence),
      this.db.prepare("DELETE FROM cloud_upload_chunks WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER)").bind(c.library, c.device, sequence)];
    const receipt = await resolveOrRetire(this.db, c, sequence, digest, mutations);
    return { receipt };
  }
  async download(before: Head, cursor: string | null) {
    const expected = exactHead(before), c = this.context;
    if (cursor !== null && !validPageId(cursor)) fail("INVALID_CURSOR", 400);
    const result = await this.db.batch([this.db.prepare(STATUS).bind(...args(c)),
      this.db.prepare(`SELECT p.page_id AS id,p.body FROM cloud_catalog_pages p WHERE p.library_id=? AND p.page_id>?
        AND EXISTS(SELECT 1 FROM cloud_devices d WHERE ${AUTH}) ORDER BY p.page_id LIMIT ?`)
        .bind(c.library, cursor ?? "", ...args(c), TRANSPORT_LIMITS.pageResponse + 1)]);
    const row = result[0]!.results[0] as CatalogRow | undefined;
    if (!row) fail("AUTH_OR_LIBRARY_UNAVAILABLE", 401);
    const status = this.statusRow(row); if (!sameHead(status.head, expected)) fail("CLOUD_CHANGED");
    if (!status.initialized) fail("CATALOG_NOT_INITIALIZED");
    const pages = result[1]!.results as unknown as CatalogPage[], more = pages.length > TRANSPORT_LIMITS.pageResponse;
    const output = pages.slice(0, TRANSPORT_LIMITS.pageResponse);
    return { ...status, data: output, nextCursor: more ? output[output.length - 1]!.id : null };
  }
}

/** Maps a device token to server-side authority. A client cannot choose its library/auth generation. */
export async function authenticateToken(db: D1Database, token: string, library: string, serviceGeneration: string): Promise<AdmissionContext & { writable: boolean }> {
  if (!idPattern.test(library) || !idPattern.test(serviceGeneration)) fail("SERVICE_CONFIGURATION_INVALID", 503);
  const match = /^([A-Za-z0-9_-]{1,128})\.([0-9a-f]{64})$/.exec(token);
  if (!match) fail("INVALID_CREDENTIAL", 401);
  const tokenHash = await sha256(match[2]!);
  const row = await db.prepare(`SELECT device_id,CAST(auth_generation AS TEXT) AS auth_generation,can_write FROM cloud_devices
    WHERE library_id=? AND device_id=? AND token_hash=? AND service_generation=? AND revoked=0`)
    .bind(library, match[1], tokenHash, serviceGeneration).first<{ device_id: string; auth_generation: string; can_write: number }>();
  if (!row) fail("INVALID_CREDENTIAL", 401);
  return Object.freeze({ library, device: row.device_id, tokenHash, authGeneration: row.auth_generation, serviceGeneration, writable: row.can_write === 1 });
}
