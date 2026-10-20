/* SPDX-License-Identifier: GPL-3.0-or-later */
import type { AdmissionContext } from "./admission.ts";
import { counter, sameHead } from "./protocol.ts";
import { CatalogService } from "./service.ts";
import { RECOVERY_LIMITS as L, RecoveryError, archiveId, bytesDigest, readRecoveryManifest,
  recoveryDigest, recoveryFail as fail, recoveryObject, verifyCloudArchivePart, verifyZipEnd, verifyZipStart } from "./recovery-format.ts";
import type { RecoveryManifest, CloudArchiveVerification } from "./recovery-format.ts";
import { REPLACE_GRANT, REPLACEMENT_COOLDOWN_MS } from "./replacement-state.ts";
import type { ReplacementApproval } from "./replacement-state.ts";

type Control = { revision: string; next_ordinal: string; service_generation: string };
type Slot = { library_id: string; slot: number; archive_id: string; object_key: string; owner_device: string;
  source_service: string; abort_fenced: number; manifest: string; digest: string; ordinal: string; created_at: number; state: string; upload_id: string | null;
  object_etag: string | null; object_version: string | null; next_part: number; parts_json: string;
  busy_token: string | null; busy_until: number; verify_part: number; verify_json: string | null;
  pin_id: string | null; pin_device: string | null; pin_until: number; error: string | null };
type Operation = { library_id: string; device_id: string; sequence: string; approval_id: string;
  archive_id: string; slot: number; manifest: string; digest: string; state: string;
  evict_id: string | null; created_at: number; error: string | null };
type Approval = { approval_id: string; manifest: string; digest: string; revision: string; evict_id: string | null; expires_at: number };
export type RecoveryReference = { sequence: string; archiveId: string };
const AUTH = "d.library_id=? AND d.device_id=? AND d.token_hash=? AND d.auth_generation=CAST(? AS INTEGER) AND d.service_generation=? AND d.revoked=0";
const args = (c: AdmissionContext) => [c.library, c.device, c.tokenHash, c.authGeneration, c.serviceGeneration];
const active = "state NOT IN('DONE','CANCELED')";
const manifest = (s: { manifest: string }) => readRecoveryManifest(JSON.parse(s.manifest));
const asError = (e: unknown): never => {
  if (e instanceof RecoveryError) throw e;
  if (String(e).toLowerCase().includes("constraint failed")) fail("RECOVERY_STATE_CHANGED");
  return fail("RECOVERY_OUTCOME_UNKNOWN", 503);
};
export function readRecoveryReference(raw: unknown): RecoveryReference {
  const o = recoveryObject(raw, ["sequence", "archiveId"]);
  if (typeof o.sequence !== "string" || typeof o.archiveId !== "string" || !archiveId.test(o.archiveId)) fail("INVALID_RECOVERY_REFERENCE", 400);
  try { if (counter(o.sequence) === 0n) fail("INVALID_RECOVERY_SEQUENCE", 400); } catch { fail("INVALID_RECOVERY_SEQUENCE", 400); }
  return { sequence: o.sequence, archiveId: o.archiveId };
}
function uuid(raw: unknown): string {
  if (typeof raw !== "string" || !archiveId.test(raw)) fail("INVALID_RECOVERY_REFERENCE", 400);
  return raw;
}

/** Three physical slots, one active attempt, and no expiry-based reclamation of uncertain writes. */
export class RecoveryService {
  private readonly db: D1Database;
  private readonly bucket: R2Bucket;
  private readonly context: AdmissionContext;
  private readonly maximum: number;
  constructor(db: D1Database, bucket: R2Bucket, context: AdmissionContext, maximum = L.bytes) {
    this.db = db; this.bucket = bucket; this.context = context; this.maximum = maximum;
  }
  private prefix() { return "recovery/" + this.context.library + "/"; }
  private key(slot: number, id: string) { return this.prefix() + slot + "/" + id; }
  private async control(): Promise<Control> {
    const row = await this.db.prepare("SELECT CAST(b.revision AS TEXT) AS revision,CAST(b.next_ordinal AS TEXT) AS next_ordinal,b.service_generation FROM cloud_backup_control b JOIN cloud_devices d ON d.library_id=b.library_id WHERE " + AUTH)
      .bind(...args(this.context)).first<Control>();
    if (!row || row.service_generation !== this.context.serviceGeneration) fail("RECOVERY_INDEX_RECONCILIATION_REQUIRED", 503);
    return row;
  }
  private async slots(): Promise<Slot[]> {
    return (await this.db.prepare("SELECT s.*,CAST(s.ordinal AS TEXT) AS ordinal FROM cloud_backup_slots s WHERE library_id=? ORDER BY ordinal")
      .bind(this.context.library).all<Slot>()).results;
  }
  private async ownOperation(): Promise<Operation | null> {
    return this.db.prepare("SELECT o.*,CAST(o.sequence AS TEXT) AS sequence FROM cloud_backup_operations o WHERE library_id=? AND device_id=?")
      .bind(this.context.library, this.context.device).first<Operation>();
  }
  private async operation(ref: RecoveryReference): Promise<Operation> {
    await this.control();
    const op = await this.ownOperation();
    if (!op || op.sequence !== ref.sequence) fail("RECOVERY_RESULT_EXPIRED", 410);
    if (op.archive_id !== ref.archiveId) fail("RECOVERY_REPLAY_MISMATCH");
    return op;
  }
  private async slot(op: Operation): Promise<Slot> {
    const row = await this.db.prepare("SELECT s.*,CAST(s.ordinal AS TEXT) AS ordinal FROM cloud_backup_slots s WHERE library_id=? AND slot=? AND archive_id=?")
      .bind(this.context.library, op.slot, op.state === "ROTATING" ? op.evict_id : op.archive_id).first<Slot>();
    if (!row) fail("RECOVERY_SLOT_UNAVAILABLE"); return row;
  }
  private async batch(condition: string, values: unknown[], mutations: D1PreparedStatement[], writable = true): Promise<void> {
    if (mutations.length > 16) fail("RECOVERY_STATEMENT_LIMIT", 413);
    const c = this.context;
    const guard = this.db.prepare("INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,(SELECT COUNT(*) FROM cloud_devices d JOIN cloud_backup_control b ON b.library_id=d.library_id WHERE " +
      AUTH + (writable ? " AND d.can_write=1" : "") +
      " AND b.service_generation=? AND b.revision<9223372036854775807 AND b.next_ordinal<9223372036854775807 AND (" + condition + ")))")
      .bind(...args(c), c.serviceGeneration, ...values);
    await this.db.batch([guard, ...mutations, this.bump(), this.db.prepare("DELETE FROM cloud_commit_assertion WHERE slot=1")]);
  }
  private bump() { return this.db.prepare("UPDATE cloud_backup_control SET revision=revision+1 WHERE library_id=?").bind(this.context.library); }
  private opCondition(op: Operation) { return { sql: "EXISTS(SELECT 1 FROM cloud_backup_operations o WHERE o.library_id=b.library_id AND o.device_id=d.device_id AND o.sequence=CAST(? AS INTEGER) AND o.archive_id=? AND o.state=?)",
    values: [op.sequence, op.archive_id, op.state] }; }
  private setOp(op: Operation, state: string, error: string | null = null) {
    return this.db.prepare("UPDATE cloud_backup_operations SET state=?,error=? WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER) AND archive_id=?")
      .bind(state, error, this.context.library, this.context.device, op.sequence, op.archive_id);
  }
  private setSlot(s: Slot, sql: string, values: unknown[] = []) {
    return this.db.prepare("UPDATE cloud_backup_slots SET " + sql + " WHERE library_id=? AND slot=? AND archive_id=?")
      .bind(...values, this.context.library, s.slot, s.archive_id);
  }
  private metadata(s: Slot): Record<string, string> {
    const m = manifest(s);
    const value = JSON.stringify({ format: 1, archive: s.archive_id, slot: s.slot, kind: m.kind, bytes: m.bytes,
      digest: s.digest, source: m.source, native: m.native, library: s.library_id, service: s.source_service,
      device: s.owner_device, created: String(s.created_at), checksum: "sha256-chunk-manifest-v1",
      producerValidation: m.producerValidation });
    if (new TextEncoder().encode(value).length > 1800) fail("RECOVERY_METADATA_LIMIT", 413);
    return { recovery: value };
  }
  private matches(s: Slot, object: R2Object | null): object is R2Object {
    return object !== null && object.key === s.object_key && object.size === manifest(s).bytes &&
      object.customMetadata?.recovery === this.metadata(s).recovery &&
      (s.object_etag === null || s.object_etag === object.etag) &&
      (s.object_version === null || s.object_version === object.version);
  }
  private async extant(s: Slot): Promise<R2Object> {
    const object = await this.bucket.head(s.object_key);
    if (!this.matches(s, object)) fail("RECOVERY_OBJECT_MISSING_OR_CHANGED");
    return object;
  }
  private async checkNamespace(slots: Slot[]): Promise<void> {
    const listed = await this.bucket.list({ prefix: this.prefix(), limit: 4 });
    const known = new Set(slots.map(s => s.object_key));
    if (listed.truncated || listed.objects.length > L.slots || listed.objects.some(o => !known.has(o.key))) fail("RECOVERY_UNINDEXED_OBJECTS", 503);
  }
  private async operationView(op: Operation) {
    const s = ["DONE", "CANCELED"].includes(op.state) ? null : await this.slot(op);
    return { sequence: op.sequence, approvalId: op.approval_id, archiveId: op.archive_id, state: op.state,
      manifest: manifest(op), digest: op.digest, evictArchiveId: op.evict_id,
      nextPart: s?.next_part ?? null, verifyPart: s?.verify_part ?? null, error: op.error ?? s?.error ?? null };
  }
  async status() {
    const c = await this.control(), slots = await this.slots(), own = await this.ownOperation();
    const archives = [];
    for (const s of slots) {
      const m = manifest(s);
      let usable = false;
      if (s.state === "READY") usable = this.matches(s, await this.bucket.head(s.object_key));
      archives.push({ archiveId: s.archive_id, slot: s.slot, kind: m.kind, sourceDevice: s.owner_device,
        createdAt: String(s.created_at), bytes: m.bytes, state: s.state, usable,
        protectedUntil: String(s.pin_until), checksum: "sha256-chunk-manifest-v1", digest: s.digest,
        validation: m.kind === "device-zip" ? "producer-native-validation-and-verified-transfer" : "verified-exact-cloud-pages",
        error: s.error ?? (s.state === "READY" && !usable ? "RECOVERY_OBJECT_MISSING_OR_CHANGED" : null) });
    }
    return { revision: c.revision, archives, occupiedSlots: slots.length,
      reservedBytes: slots.reduce((n, s) => n + manifest(s).bytes, 0),
      maxBytes: this.maximum, maxSlots: L.slots, partBytes: L.partBytes,
      lastSequence: own?.sequence ?? "0", operation: own && !["DONE", "CANCELED"].includes(own.state) ? await this.operationView(own) : null };
  }
  // undefined = ordinary backup, null = first replacement, ID = explicitly approved retry.
  private replacementFence(retry: string | null | undefined) {
    if (retry === undefined) return { sql: "NOT EXISTS(SELECT 1 FROM cloud_replace_attempts WHERE library_id=b.library_id AND state='ACTIVE') AND (NOT EXISTS(SELECT 1 FROM cloud_replace_attempts WHERE library_id=b.library_id AND state='STALE') OR (SELECT COUNT(*) FROM cloud_backup_slots WHERE library_id=b.library_id)<3)", values: [] as unknown[] };
    if (retry === null) return { sql: "NOT EXISTS(SELECT 1 FROM cloud_replace_attempts WHERE library_id=b.library_id AND state IN('ACTIVE','STALE'))", values: [] as unknown[] };
    return { sql: "EXISTS(SELECT 1 FROM cloud_replace_attempts WHERE library_id=b.library_id AND device_id=d.device_id AND state='STALE' AND archive_id=?)", values: [retry] as unknown[] };
  }
  private async requireReplacementFence(retry: string | null | undefined) {
    const f = this.replacementFence(retry);
    const row = await this.db.prepare("SELECT 1 FROM cloud_backup_control b JOIN cloud_devices d ON d.library_id=b.library_id WHERE " + AUTH + " AND " + f.sql + (retry !== undefined ? " AND " + REPLACE_GRANT : ""))
      .bind(...args(this.context), ...f.values).first();
    if (!row) fail("REPLACEMENT_PENDING_OR_NOT_AUTHORIZED");
    return f;
  }
  async verifyReplacementArchive(id: string) {
    await this.control();
    const s = (await this.slots()).find(x => x.archive_id === id);
    if (!s || s.state !== "READY" || !manifest(s).source) fail("REPLACEMENT_RECOVERY_NOT_READY");
    const object = await this.extant(s);
    return { manifest: manifest(s), digest: s.digest, objectEtag: object.etag, objectVersion: object.version };
  }
  async preview(raw: unknown, now = Date.now(), replacementRetry?: string | null) {
    const value = recoveryObject(raw, ["manifest"]), m = readRecoveryManifest(value.manifest, this.maximum);
    const control = await this.control(), slots = await this.slots();
    const replacementFence = await this.requireReplacementFence(replacementRetry);
    const activeOp = await this.db.prepare("SELECT 1 AS n FROM cloud_backup_operations WHERE library_id=? AND " + active).bind(this.context.library).first();
    if (activeOp) fail("RECOVERY_UPLOAD_PENDING");
    if (slots.some(s => s.state !== "READY" || s.pin_until > now)) fail("RECOVERY_SLOT_PROTECTED");
    await this.checkNamespace(slots);
    for (const s of slots) await this.extant(s);
    if (m.source) {
      const source = await new CatalogService(this.db, this.context).status();
      if (!source.initialized || !sameHead(source.head, m.source.head) || source.manifest !== m.source.manifest ||
        source.pages !== m.source.pages || source.bytes !== m.source.bytes) fail("CLOUD_ARCHIVE_SOURCE_CHANGED");
    }
    const evict = typeof replacementRetry === "string" ? slots.find(s => s.archive_id === replacementRetry) : slots.length === L.slots ? slots[0]! : null;
    if (typeof replacementRetry === "string" && (!evict || slots.length < 2)) fail("REPLACEMENT_RETRY_REQUIRES_SURVIVOR");
    if (replacementRetry == null && evict && slots.length - 1 !== 2) fail("RECOVERY_TWO_SURVIVORS_REQUIRED");
    const digest = await recoveryDigest(m), approval = crypto.randomUUID(), encoded = JSON.stringify(m);
    const headCheck = m.source ? " AND EXISTS(SELECT 1 FROM cloud_head h WHERE h.library_id=b.library_id AND h.epoch=? AND h.revision=CAST(? AS INTEGER) AND h.head_counter=CAST(? AS INTEGER) AND h.fingerprint=?)" : "";
    const headValues = m.source ? [m.source.head.epoch, m.source.head.revision, m.source.head.headCounter, m.source.head.fingerprint] : [];
    // Approval changes don't advance the rotation revision: they allocate no payload or pin.
    const guard = this.db.prepare("INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,(SELECT COUNT(*) FROM cloud_devices d JOIN cloud_backup_control b ON b.library_id=d.library_id WHERE " +
      AUTH + " AND d.can_write=1 AND b.service_generation=? AND b.revision=CAST(? AS INTEGER)" + headCheck + " AND " + replacementFence.sql + (replacementRetry !== undefined ? " AND " + REPLACE_GRANT : "") + "))")
      .bind(...args(this.context), this.context.serviceGeneration, control.revision, ...headValues, ...replacementFence.values);
    try {
      await this.db.batch([guard,
        this.db.prepare("INSERT INTO cloud_backup_approvals VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(library_id,device_id) DO UPDATE SET approval_id=excluded.approval_id,manifest=excluded.manifest,digest=excluded.digest,revision=excluded.revision,evict_id=excluded.evict_id,expires_at=excluded.expires_at")
          .bind(this.context.library, this.context.device, approval, encoded, digest, control.revision, evict?.archive_id ?? null, now + L.approvalMs),
        this.db.prepare("DELETE FROM cloud_commit_assertion WHERE slot=1")]);
    } catch (e) { asError(e); }
    return { approvalId: approval, manifest: m, digest, revision: control.revision,
      evictArchiveId: evict?.archive_id ?? null, expiresAt: String(now + L.approvalMs),
      occupiedSlots: slots.length, remainingArchives: slots.filter(s => s !== evict).map(s => s.archive_id) };
  }
  async reserve(raw: unknown, now = Date.now(), forReplacement = false) {
    const r = recoveryObject(raw, ["approvalId", "sequence"]), approvalId = uuid(r.approvalId);
    if (typeof r.sequence !== "string") fail("INVALID_RECOVERY_SEQUENCE", 400);
    try { if (counter(r.sequence) === 0n) fail("INVALID_RECOVERY_SEQUENCE", 400); } catch { fail("INVALID_RECOVERY_SEQUENCE", 400); }
    const sequence = r.sequence, control = await this.control(), previous = await this.ownOperation();
    if (previous?.sequence === sequence) {
      if (previous.approval_id !== approvalId) fail("RECOVERY_REPLAY_MISMATCH");
      return this.operationView(previous);
    }
    if (counter(sequence) !== counter(previous?.sequence ?? "0") + 1n) fail("RECOVERY_SEQUENCE_GAP");
    const a = await this.db.prepare("SELECT * FROM cloud_backup_approvals WHERE library_id=? AND device_id=? AND approval_id=?")
      .bind(this.context.library, this.context.device, approvalId).first<Approval>();
    if (!a || a.expires_at <= now) fail("RECOVERY_APPROVAL_EXPIRED", 410);
    if (a.revision !== control.revision) fail("RECOVERY_APPROVAL_CHANGED");
    const ra = await this.db.prepare("SELECT * FROM cloud_replace_approvals WHERE library_id=? AND device_id=? AND approval_id=?")
      .bind(this.context.library, this.context.device, approvalId).first<ReplacementApproval>();
    if (forReplacement ? !ra || ra.expires_at <= now : ra !== null) fail("REPLACEMENT_APPROVAL_REQUIRED");
    const retry = forReplacement ? ra!.retry_archive_id : undefined;
    const replacementFence = await this.requireReplacementFence(retry);
    const slots = await this.slots();
    if (slots.some(s => s.state !== "READY" || s.pin_until > now)) fail("RECOVERY_SLOT_PROTECTED");
    await this.checkNamespace(slots); for (const s of slots) await this.extant(s);
    const evict = typeof retry === "string" ? slots.find(s => s.archive_id === retry) : slots.length === L.slots ? slots[0]! : null;
    if (typeof retry === "string" && (!evict || slots.length < 2)) fail("REPLACEMENT_RETRY_REQUIRES_SURVIVOR");
    if ((evict?.archive_id ?? null) !== a.evict_id) fail("RECOVERY_APPROVAL_CHANGED");
    const free = evict?.slot ?? [0, 1, 2].find(n => !slots.some(s => s.slot === n));
    if (free === undefined) fail("RECOVERY_SLOT_LIMIT");
    const m = manifest(a), id = crypto.randomUUID(), state = evict ? "ROTATING" : "RESERVED", c = this.context;
    const headCheck = m.source ? " AND EXISTS(SELECT 1 FROM cloud_head h WHERE h.library_id=b.library_id AND h.epoch=? AND h.revision=CAST(? AS INTEGER) AND h.head_counter=CAST(? AS INTEGER) AND h.fingerprint=?)" : "";
    const hv = m.source ? [m.source.head.epoch, m.source.head.revision, m.source.head.headCounter, m.source.head.fingerprint] : [];
    const statements = [
      this.db.prepare("INSERT INTO cloud_backup_operations(library_id,device_id,sequence,approval_id,archive_id,slot,manifest,digest,state,evict_id,created_at) VALUES(?,?,CAST(? AS INTEGER),?,?,?,?,?,?,?,?) ON CONFLICT(library_id,device_id) DO UPDATE SET sequence=excluded.sequence,approval_id=excluded.approval_id,archive_id=excluded.archive_id,slot=excluded.slot,manifest=excluded.manifest,digest=excluded.digest,state=excluded.state,evict_id=excluded.evict_id,created_at=excluded.created_at,error=NULL")
        .bind(c.library, c.device, sequence, approvalId, id, free, a.manifest, a.digest, state, a.evict_id, now)
    ];
    if (evict) statements.push(this.setSlot(evict, "state='DELETING',error=NULL"));
    else {
      statements.push(this.insertSlot(id, free, a.manifest, a.digest, now));
      statements.push(this.db.prepare("UPDATE cloud_backup_control SET next_ordinal=next_ordinal+1 WHERE library_id=?").bind(c.library));
    }
    let replacementCheck = " AND " + replacementFence.sql;
    const replacementValues = [...replacementFence.values];
    if (forReplacement) {
      const approved = ra!;
      replacementCheck += " AND " + REPLACE_GRANT + " AND (b.last_replace_at=0 OR b.last_replace_at<=?) AND EXISTS(SELECT 1 FROM cloud_replace_approvals WHERE library_id=b.library_id AND device_id=d.device_id AND approval_id=? AND header=? AND next_epoch=? AND retry_archive_id IS ? AND retry_attempt_id IS ? AND expires_at>?)";
      replacementValues.push(now - REPLACEMENT_COOLDOWN_MS, approvalId, approved.header, approved.next_epoch, approved.retry_archive_id, approved.retry_attempt_id, now);
      replacementCheck += " AND d.resolved_sequence=CAST(json_extract(?,'$.sequence') AS INTEGER)-1 AND NOT EXISTS(SELECT 1 FROM cloud_uploads WHERE library_id=d.library_id AND device_id=d.device_id AND status IN('UPLOADING','SEALED'))";
      replacementValues.push(approved.header);
      if (approved.retry_attempt_id !== null) {
        replacementCheck += " AND EXISTS(SELECT 1 FROM cloud_replace_attempts WHERE library_id=b.library_id AND device_id=d.device_id AND attempt_id=? AND state='STALE')";
        replacementValues.push(approved.retry_attempt_id);
      }
      statements.push(this.db.prepare("INSERT INTO cloud_replace_attempts(library_id,device_id,attempt_id,header,next_epoch,recovery_sequence,archive_id,slot,retry_archive_id,expires_at,state) VALUES(?,?,?,?,?,CAST(? AS INTEGER),?,?,?,?,'ACTIVE') ON CONFLICT(library_id,device_id) DO UPDATE SET attempt_id=excluded.attempt_id,header=excluded.header,next_epoch=excluded.next_epoch,recovery_sequence=excluded.recovery_sequence,archive_id=excluded.archive_id,slot=excluded.slot,retry_archive_id=excluded.retry_archive_id,expires_at=excluded.expires_at,state='ACTIVE'")
        .bind(c.library, c.device, approvalId, approved.header, approved.next_epoch, sequence, id, free, approved.retry_archive_id, approved.expires_at));
    } else {
      replacementCheck += " AND NOT EXISTS(SELECT 1 FROM cloud_replace_approvals WHERE library_id=b.library_id AND device_id=d.device_id AND approval_id=?)";
      replacementValues.push(approvalId);
    }
    try {
      await this.batch("b.revision=CAST(? AS INTEGER) AND NOT EXISTS(SELECT 1 FROM cloud_backup_operations WHERE library_id=b.library_id AND " + active + ") AND COALESCE((SELECT sequence FROM cloud_backup_operations WHERE library_id=b.library_id AND device_id=d.device_id),0)=CAST(? AS INTEGER)-1 AND EXISTS(SELECT 1 FROM cloud_backup_approvals WHERE library_id=b.library_id AND device_id=d.device_id AND approval_id=? AND expires_at>?)" + headCheck + replacementCheck,
        [control.revision, sequence, approvalId, now, ...hv, ...replacementValues], statements);
    } catch (e) {
      const winner = await this.ownOperation();
      if (!winner || winner.sequence !== sequence || winner.approval_id !== approvalId) return asError(e);
      return this.operationView(winner);
    }
    return this.operationView((await this.ownOperation())!);
  }
  async abandon(raw: unknown) {
    const r = recoveryObject(raw, ["approvalId", "sequence"]), approvalId = uuid(r.approvalId);
    if (typeof r.sequence !== "string") fail("INVALID_RECOVERY_SEQUENCE", 400);
    try { if (counter(r.sequence) === 0n) fail("INVALID_RECOVERY_SEQUENCE", 400); } catch { fail("INVALID_RECOVERY_SEQUENCE", 400); }
    await this.control();
    const old = await this.ownOperation();
    if (old?.sequence === r.sequence) {
      if (old.approval_id !== approvalId) fail("RECOVERY_REPLAY_MISMATCH");
      return ["DONE", "CANCELED"].includes(old.state) ? this.operationView(old) : this.abort({ sequence: old.sequence, archiveId: old.archive_id });
    }
    if (counter(r.sequence) !== counter(old?.sequence ?? "0") + 1n) fail("RECOVERY_SEQUENCE_GAP");
    const a = await this.db.prepare("SELECT * FROM cloud_backup_approvals WHERE library_id=? AND device_id=? AND approval_id=?")
      .bind(this.context.library, this.context.device, approvalId).first<Approval>();
    if (!a) fail("RECOVERY_APPROVAL_UNAVAILABLE");
    // A terminal sequence fences a delayed reserve request, including one whose reply was lost.
    try { await this.batch("COALESCE((SELECT sequence FROM cloud_backup_operations WHERE library_id=b.library_id AND device_id=d.device_id),0)=CAST(? AS INTEGER)-1 AND NOT EXISTS(SELECT 1 FROM cloud_backup_operations WHERE library_id=b.library_id AND device_id=d.device_id AND " + active + ") AND EXISTS(SELECT 1 FROM cloud_backup_approvals WHERE library_id=b.library_id AND device_id=d.device_id AND approval_id=?)",
      [r.sequence, approvalId], [
        this.db.prepare("INSERT INTO cloud_backup_operations(library_id,device_id,sequence,approval_id,archive_id,slot,manifest,digest,state,created_at) VALUES(?,?,CAST(? AS INTEGER),?,?,0,?,?,'CANCELED',?) ON CONFLICT(library_id,device_id) DO UPDATE SET sequence=excluded.sequence,approval_id=excluded.approval_id,archive_id=excluded.archive_id,slot=0,manifest=excluded.manifest,digest=excluded.digest,state='CANCELED',evict_id=NULL,created_at=excluded.created_at,error=NULL")
          .bind(this.context.library, this.context.device, r.sequence, approvalId, crypto.randomUUID(), a.manifest, a.digest, Date.now()),
        this.db.prepare("DELETE FROM cloud_backup_approvals WHERE library_id=? AND device_id=? AND approval_id=?")
          .bind(this.context.library, this.context.device, approvalId)
      ]); } catch (e) { return asError(e); }
    return this.operationView((await this.ownOperation())!);
  }
  private insertSlot(id: string, slot: number, encoded: string, digest: string, created: number) {
    return this.db.prepare("INSERT INTO cloud_backup_slots(library_id,slot,archive_id,object_key,owner_device,source_service,manifest,digest,ordinal,created_at,state) SELECT ?,?,?,?,?,?,?,?,next_ordinal,?,'RESERVED' FROM cloud_backup_control WHERE library_id=?")
      .bind(this.context.library, slot, id, this.key(slot, id), this.context.device, this.context.serviceGeneration, encoded, digest, created, this.context.library);
  }
  private async quarantine(op: Operation, s: Slot, code: string, ticket?: string) {
    const p = this.opCondition(op);
    const claim = ticket ? " AND EXISTS(SELECT 1 FROM cloud_backup_slots WHERE library_id=b.library_id AND archive_id=? AND verify_part=? AND busy_token=?)" : "";
    const values = ticket ? [...p.values, s.archive_id, s.verify_part, ticket] : p.values;
    try { await this.batch(p.sql + claim, values, [this.setOp(op, "QUARANTINED", code), this.setSlot(s, "state='QUARANTINED',error=?,busy_token=NULL,busy_until=0", [code])]); }
    catch { /* A failed index write never frees the reservation. */ }
  }
  async advance(ref: RecoveryReference, now = Date.now()) {
    let op = await this.operation(ref);
    if (["DONE", "CANCELED"].includes(op.state)) return this.operationView(op);
    let s = await this.slot(op);
    if (op.state === "ROTATING") {
      const all = await this.slots(), control = await this.control();
      const survivors = all.filter(x => x.archive_id !== s.archive_id);
      const retry = await this.db.prepare("SELECT 1 FROM cloud_replace_attempts WHERE library_id=? AND device_id=? AND state='ACTIVE' AND archive_id=? AND recovery_sequence=CAST(? AS INTEGER) AND retry_archive_id=?")
        .bind(this.context.library, this.context.device, op.archive_id, op.sequence, s.archive_id).first();
      if (s.state !== "DELETING" || (retry ? survivors.length < 1 : survivors.length !== 2) || survivors.some(x => x.state !== "READY" || (!retry && counter(x.ordinal) <= counter(s.ordinal)) || x.pin_until > now)) fail(retry ? "REPLACEMENT_RETRY_REQUIRES_SURVIVOR" : "RECOVERY_TWO_SURVIVORS_REQUIRED");
      for (const survivor of survivors) await this.extant(survivor);
      const old = await this.bucket.head(s.object_key);
      if (old && !this.matches(s, old)) fail("RECOVERY_OBJECT_MISSING_OR_CHANGED");
      // Deleting the immutable old key is idempotent. Never recreate a missing old object.
      try { await this.bucket.delete(s.object_key); if (await this.bucket.head(s.object_key)) fail("RECOVERY_DELETE_UNCONFIRMED", 503); }
      catch (e) { if (e instanceof RecoveryError) throw e; fail("RECOVERY_DELETE_UNCONFIRMED", 503); }
      const p = this.opCondition(op);
      try {
        await this.batch(p.sql + " AND b.revision=CAST(? AS INTEGER)", [...p.values, control.revision], [
          this.db.prepare("DELETE FROM cloud_backup_slots WHERE library_id=? AND slot=? AND archive_id=? AND state='DELETING'").bind(this.context.library, s.slot, s.archive_id),
          this.insertSlot(op.archive_id, op.slot, op.manifest, op.digest, op.created_at),
          this.setOp(op, "RESERVED"),
          this.db.prepare("UPDATE cloud_backup_control SET next_ordinal=next_ordinal+1 WHERE library_id=?").bind(this.context.library)]);
      } catch (e) { asError(e); }
      return this.operationView((await this.operation(ref)));
    }
    if (op.state === "RESERVED") {
      const p = this.opCondition(op);
      try { await this.batch(p.sql, p.values, [this.setOp(op, "CREATING"), this.setSlot(s, "state='CREATING'")]); }
      catch (e) { asError(e); } // No R2 side effect unless the creation claim definitely succeeded.
      op = await this.operation(ref); s = await this.slot(op);
      try {
        const upload = await this.bucket.createMultipartUpload(s.object_key, {
          customMetadata: this.metadata(s), httpMetadata: { contentType: manifest(s).kind === "device-zip" ? "application/zip" : "application/x-newpipe-catalog", cacheControl: "private, no-store" }
        });
        const p2 = this.opCondition(op);
        await this.batch(p2.sql, p2.values, [this.setOp(op, "UPLOADING"), this.setSlot(s, "state='UPLOADING',upload_id=?", [upload.uploadId])]);
      } catch {
        // createMultipartUpload is not idempotent. Missing upload identity is never retried/freed.
        fail("RECOVERY_CREATION_OUTCOME_UNKNOWN", 503);
      }
      return this.operationView(await this.operation(ref));
    }
    if (op.state === "CREATING") fail("RECOVERY_CREATION_OUTCOME_UNKNOWN", 503);
    if (op.state === "QUARANTINED") fail(op.error ?? "RECOVERY_QUARANTINED", 503);
    if (op.state === "COMPLETING") {
      let object = await this.bucket.head(s.object_key);
      if (!object) {
        if (!s.upload_id) fail("RECOVERY_UPLOAD_ID_MISSING", 503);
        try { await this.bucket.resumeMultipartUpload(s.object_key, s.upload_id).complete(JSON.parse(s.parts_json) as R2UploadedPart[]); }
        catch { /* A lost completion response is resolved by the immutable stored object. */ }
        // Real R2 completion responses need not include all stored custom metadata.
        // Always read the committed object's authoritative metadata before verification.
        object = await this.bucket.head(s.object_key);
        if (!object) fail("RECOVERY_COMPLETION_OUTCOME_UNKNOWN", 503);
      }
      if (!this.matches(s, object)) { await this.quarantine(op, s, "RECOVERY_OBJECT_MISSING_OR_CHANGED"); fail("RECOVERY_OBJECT_MISSING_OR_CHANGED"); }
      const p = this.opCondition(op);
      try { await this.batch(p.sql, p.values, [this.setOp(op, "VERIFYING"),
        this.setSlot(s, "state='VERIFYING',object_etag=?,object_version=?,busy_token=NULL,busy_until=0", [object.etag, object.version])]); }
      catch (e) { asError(e); }
      return this.operationView(await this.operation(ref));
    }
    if (op.state === "VERIFYING") return this.verify(op, s, now);
    return this.operationView(op);
  }
  async uploadPart(ref: RecoveryReference, number: number, body: ReadableStream<Uint8Array> | null, declared: string | null, now = Date.now()) {
    const op = await this.operation(ref), s = await this.slot(op), m = manifest(s);
    if (!Number.isInteger(number) || number < 1 || number > m.parts.length) fail("INVALID_RECOVERY_PART", 400);
    if (op.state !== "UPLOADING" || s.state !== "UPLOADING" || !s.upload_id) fail("RECOVERY_NOT_UPLOADING");
    if (number < s.next_part) return this.operationView(op);
    const expected = m.parts[number - 1]!;
    if (!body || declared !== String(expected.bytes)) fail("RECOVERY_PART_LENGTH_REQUIRED", 400);
    const ticket = crypto.randomUUID(), p = this.opCondition(op);
    const slotCondition = " AND EXISTS(SELECT 1 FROM cloud_backup_slots s WHERE s.library_id=b.library_id AND s.archive_id=? AND s.state='UPLOADING' AND s.next_part=? AND (s.busy_token IS NULL OR s.busy_until<=?))";
    try { await this.batch(p.sql + slotCondition, [...p.values, s.archive_id, number, now],
      [this.setSlot(s, "busy_token=?,busy_until=?,error=NULL", [ticket, now + L.partLeaseMs])]); }
    catch (e) { asError(e); }
    const data = new Uint8Array(expected.bytes), reader = body.getReader(); let length = 0;
    try {
      while (true) {
        const r = await reader.read(); if (r.done) break;
        if (length + r.value.length > expected.bytes) { await reader.cancel(); fail("RECOVERY_PART_SIZE_MISMATCH", 413); }
        data.set(r.value, length); length += r.value.length;
      }
      if (length !== expected.bytes || await bytesDigest(data) !== expected.sha256) fail("RECOVERY_PART_CHECKSUM_MISMATCH", 422);
    } catch (e) {
      try { await this.batch(p.sql + " AND EXISTS(SELECT 1 FROM cloud_backup_slots WHERE library_id=b.library_id AND archive_id=? AND busy_token=?)",
        [...p.values, s.archive_id, ticket], [this.setSlot(s, "busy_token=NULL,busy_until=0")]); } catch { /* reservation retained */ }
      throw e;
    } finally { reader.releaseLock(); }
    let part: R2UploadedPart;
    try { part = await this.bucket.resumeMultipartUpload(s.object_key, s.upload_id).uploadPart(number, data); }
    catch { fail("RECOVERY_PART_OUTCOME_UNKNOWN", 503); }
    const parts = JSON.parse(s.parts_json) as R2UploadedPart[];
    if (parts.length !== number - 1 || part.partNumber !== number) fail("RECOVERY_PART_STATE_INVALID", 503);
    parts.push(part);
    try { await this.batch(p.sql + " AND EXISTS(SELECT 1 FROM cloud_backup_slots WHERE library_id=b.library_id AND archive_id=? AND busy_token=? AND next_part=?)",
      [...p.values, s.archive_id, ticket, number], [this.setSlot(s, "parts_json=?,next_part=next_part+1,busy_token=NULL,busy_until=0", [JSON.stringify(parts)])]); }
    catch (e) { asError(e); }
    return this.operationView(await this.operation(ref));
  }
  async complete(ref: RecoveryReference) {
    const op = await this.operation(ref);
    if (op.state !== "UPLOADING") return this.advance(ref);
    const s = await this.slot(op), m = manifest(s);
    if (s.next_part !== m.parts.length + 1 || s.busy_token) fail("RECOVERY_UPLOAD_INCOMPLETE");
    const p = this.opCondition(op);
    try { await this.batch(p.sql + " AND EXISTS(SELECT 1 FROM cloud_backup_slots WHERE library_id=b.library_id AND archive_id=? AND state='UPLOADING' AND next_part=? AND busy_token IS NULL)",
      [...p.values, s.archive_id, m.parts.length + 1], [this.setOp(op, "COMPLETING"), this.setSlot(s, "state='COMPLETING'")]); }
    catch (e) { asError(e); }
    return this.advance(ref);
  }
  private async getRange(s: Slot, offset: number, length: number): Promise<Uint8Array> {
    const result = await this.bucket.get(s.object_key, { onlyIf: { etagMatches: s.object_etag! }, range: { offset, length } });
    if (!this.matches(s, result) || !result || !("body" in result)) fail("RECOVERY_OBJECT_MISSING_OR_CHANGED");
    const bytes = new Uint8Array(await (result as R2ObjectBody).arrayBuffer());
    if (bytes.length !== length) fail("RECOVERY_OBJECT_SIZE_MISMATCH", 422); return bytes;
  }
  private async verify(op: Operation, s: Slot, now: number) {
    const m = manifest(s), number = s.verify_part, expected = m.parts[number - 1];
    if (!expected) fail("RECOVERY_VERIFICATION_STATE_INVALID", 503);
    const ticket = crypto.randomUUID(), p = this.opCondition(op);
    try { await this.batch(p.sql + " AND EXISTS(SELECT 1 FROM cloud_backup_slots WHERE library_id=b.library_id AND archive_id=? AND verify_part=? AND (busy_token IS NULL OR busy_until<=?))",
      [...p.values, s.archive_id, number, now], [this.setSlot(s, "busy_token=?,busy_until=?", [ticket, now + L.partLeaseMs])]); }
    catch (e) { asError(e); }
    let state = s.verify_json;
    const last = number === m.parts.length;
    try {
      const bytes = await this.getRange(s, (number - 1) * L.partBytes, expected.bytes);
      if (await bytesDigest(bytes) !== expected.sha256) fail("RECOVERY_OBJECT_CHECKSUM_MISMATCH", 422);
      if (m.kind === "cloud-catalog") state = JSON.stringify(await verifyCloudArchivePart(state ? JSON.parse(state) as CloudArchiveVerification : null, bytes, m.source!, last));
      else {
        if (number === 1) verifyZipStart(bytes);
        if (last) {
          const length = Math.min(m.bytes, 65557);
          const tail = m.bytes <= expected.bytes ? bytes.subarray(bytes.length - length) : await this.getRange(s, m.bytes - length, length);
          verifyZipEnd(tail, m.bytes);
        }
      }
    } catch (e) {
      const code = e instanceof RecoveryError ? e.code : "RECOVERY_VERIFICATION_FAILED";
      await this.quarantine(op, s, code, ticket); if (e instanceof RecoveryError) throw e; fail(code, 503);
    }
    try { await this.batch(p.sql + " AND EXISTS(SELECT 1 FROM cloud_backup_slots WHERE library_id=b.library_id AND archive_id=? AND verify_part=? AND busy_token=?)",
      [...p.values, s.archive_id, number, ticket], [
        this.setSlot(s, "state=?,verify_part=verify_part+1,verify_json=?,busy_token=NULL,busy_until=0,error=NULL", [last ? "READY" : "VERIFYING", last ? null : state]),
        this.setOp(op, last ? "DONE" : "VERIFYING")]); }
    catch (e) { asError(e); }
    return this.operationView((await this.ownOperation())!);
  }
  async abort(ref: RecoveryReference) {
    let op = await this.operation(ref);
    if (op.state === "CANCELED") return this.operationView(op);
    if (op.state === "DONE") fail("RECOVERY_ALREADY_COMMITTED");
    if (op.state === "ROTATING") {
      await this.advance(ref); op = await this.operation(ref);
      // A concurrent resumer may have completed the new archive while rotation returned.
      if (op.state === "CANCELED") return this.operationView(op);
      if (op.state === "DONE") fail("RECOVERY_ALREADY_COMMITTED");
    }
    const abortable = "'RESERVED','CREATING','UPLOADING','COMPLETING','VERIFYING','ABORTING','QUARANTINED'";
    let s = await this.slot(op), original = op.state;
    if (original === "CREATING" && !s.upload_id) {
      await this.quarantine(op, s, "RECOVERY_CREATION_OUTCOME_UNKNOWN"); fail("RECOVERY_OPERATOR_CLEANUP_REQUIRED", 503);
    }
    if (original === "QUARANTINED" && !s.upload_id) fail("RECOVERY_OPERATOR_CLEANUP_REQUIRED", 503);
    if (op.state !== "ABORTING") {
      const p = this.opCondition(op);
      try { await this.batch(p.sql + " AND EXISTS(SELECT 1 FROM cloud_backup_slots s WHERE s.library_id=b.library_id AND s.archive_id=? AND s.state=? AND s.state IN(" + abortable + "))",
        [...p.values, s.archive_id, original], [this.setOp(op, "ABORTING"), this.setSlot(s, "state='ABORTING',busy_token=NULL,busy_until=0,abort_fenced=?", [original === "RESERVED" ? 1 : s.abort_fenced])]); }
      catch (e) { asError(e); }
      op = await this.operation(ref);
      if (op.state === "CANCELED") return this.operationView(op);
      if (op.state !== "ABORTING") fail("RECOVERY_STATE_CHANGED");
      s = await this.slot(op);
    }
    if (op.state !== "ABORTING" || s.state !== "ABORTING") fail("RECOVERY_STATE_CHANGED");
    // RESERVED has never admitted a create; all other known identities are fenced by abort.
    let fenced = s.abort_fenced === 1 || s.object_etag !== null;
    if (s.upload_id) {
      try { await this.bucket.resumeMultipartUpload(s.object_key, s.upload_id).abort(); fenced = true; }
      catch { /* Could already be completed; only a matching extant object proves consumption. */ }
    }
    const object = await this.bucket.head(s.object_key);
    if (object) {
      if (!this.matches(s, object)) { await this.quarantine(op, s, "RECOVERY_OBJECT_MISSING_OR_CHANGED"); fail("RECOVERY_OPERATOR_CLEANUP_REQUIRED", 503); }
      fenced = true;
      try { await this.bucket.delete(s.object_key); } catch { fail("RECOVERY_DELETE_UNCONFIRMED", 503); }
    }
    if (!fenced || await this.bucket.head(s.object_key)) {
      await this.quarantine(op, s, "RECOVERY_ABORT_OUTCOME_UNKNOWN"); fail("RECOVERY_ABORT_OUTCOME_UNKNOWN", 503);
    }
    const p = this.opCondition(op);
    // CANCELED and removal of this exact ABORTING slot must commit together. A competing
    // quarantine invalidates both assertions; it cannot leave a hidden occupied slot.
    try { await this.batch(p.sql + " AND EXISTS(SELECT 1 FROM cloud_backup_slots WHERE library_id=b.library_id AND archive_id=? AND state='ABORTING')", [...p.values, s.archive_id], [
      this.db.prepare("DELETE FROM cloud_backup_slots WHERE library_id=? AND archive_id=? AND state='ABORTING'").bind(this.context.library, s.archive_id),
      this.setOp(op, "CANCELED")]); }
    catch (e) { asError(e); }
    return this.operationView((await this.ownOperation())!);
  }
  async pin(raw: unknown, now = Date.now()) {
    const r = recoveryObject(raw, ["archiveId"]), id = uuid(r.archiveId);
    await this.control();
    const s = (await this.slots()).find(x => x.archive_id === id);
    if (!s || s.state !== "READY") fail("RECOVERY_NOT_AVAILABLE", 404);
    await this.extant(s);
    if (s.pin_until > now) {
      if (s.pin_device !== this.context.device) fail("RECOVERY_SLOT_PROTECTED");
      return { archiveId: id, pinId: s.pin_id!, expiresAt: String(s.pin_until), manifest: manifest(s), digest: s.digest };
    }
    const pin = crypto.randomUUID();
    try { await this.batch("NOT EXISTS(SELECT 1 FROM cloud_backup_operations WHERE library_id=b.library_id AND " + active + ") AND EXISTS(SELECT 1 FROM cloud_backup_slots WHERE library_id=b.library_id AND archive_id=? AND state='READY' AND pin_until<=?)",
      [id, now], [this.setSlot(s, "pin_id=?,pin_device=?,pin_until=?", [pin, this.context.device, now + L.pinMs])], false); }
    catch (e) { asError(e); }
    return { archiveId: id, pinId: pin, expiresAt: String(now + L.pinMs), manifest: manifest(s), digest: s.digest };
  }
  async release(raw: unknown) {
    const r = recoveryObject(raw, ["archiveId", "pinId"]), id = uuid(r.archiveId), pin = uuid(r.pinId);
    await this.control();
    const s = (await this.slots()).find(x => x.archive_id === id);
    if (!s || s.pin_id === null) return { released: true };
    if (s.pin_id !== pin || s.pin_device !== this.context.device) fail("RECOVERY_PIN_CHANGED");
    try { await this.batch("EXISTS(SELECT 1 FROM cloud_backup_slots WHERE library_id=b.library_id AND archive_id=? AND pin_id=? AND pin_device=d.device_id)",
      [id, pin], [this.setSlot(s, "pin_id=NULL,pin_device=NULL,pin_until=0")], false); }
    catch (e) { asError(e); }
    return { released: true };
  }
  async download(raw: unknown, now = Date.now()): Promise<Response> {
    const r = recoveryObject(raw, ["archiveId", "pinId", "part"]), id = uuid(r.archiveId), pin = uuid(r.pinId);
    await this.control();
    const s = (await this.slots()).find(x => x.archive_id === id);
    if (!s || s.state !== "READY" || s.pin_id !== pin || s.pin_device !== this.context.device || s.pin_until <= now) fail("RECOVERY_PIN_EXPIRED", 410);
    const m = manifest(s);
    if (typeof r.part !== "number" || !Number.isInteger(r.part) || r.part < 1 || r.part > m.parts.length) fail("INVALID_RECOVERY_PART", 400);
    const part = m.parts[r.part - 1]!, result = await this.bucket.get(s.object_key, {
      onlyIf: { etagMatches: s.object_etag! }, range: { offset: (r.part - 1) * L.partBytes, length: part.bytes }
    });
    if (!this.matches(s, result) || !result || !("body" in result)) fail("RECOVERY_OBJECT_MISSING_OR_CHANGED");
    return new Response((result as R2ObjectBody).body, { headers: { "Content-Type": "application/octet-stream",
      "Content-Length": String(part.bytes), "Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff",
      "X-Recovery-Sha256": part.sha256, "Referrer-Policy": "no-referrer" } });
  }
}
