/* SPDX-License-Identifier: GPL-3.0-or-later */
import type { AdmissionContext } from "./admission.ts";
import { inspectOperation } from "./admission.ts";
import { counter, sameHead } from "./protocol.ts";
import { CatalogService, readStageHeader } from "./service.ts";
import type { ServiceLimits } from "./service.ts";
import { RecoveryService } from "./recovery.ts";
import { archiveId, readRecoveryManifest, recoveryObject, recoveryFail as fail } from "./recovery-format.ts";
import { REPLACE_GRANT, REPLACEMENT_COOLDOWN_MS, REPLACEMENT_DB_NOW } from "./replacement-state.ts";
import type { ReplacementAttempt } from "./replacement-state.ts";

const AUTH = "d.library_id=? AND d.device_id=? AND d.token_hash=? AND d.auth_generation=CAST(? AS INTEGER) AND d.service_generation=? AND d.revoked=0 AND d.can_write=1";
const args = (c: AdmissionContext) => [c.library, c.device, c.tokenHash, c.authGeneration, c.serviceGeneration];
function uuid(value: unknown): string {
  if (typeof value !== "string" || !archiveId.test(value)) fail("INVALID_REPLACEMENT_REFERENCE", 400);
  return value;
}
export async function canReplace(db: D1Database, c: AdmissionContext): Promise<boolean> {
  return !!await db.prepare("SELECT 1 FROM cloud_devices d WHERE " + AUTH + " AND " + REPLACE_GRANT).bind(...args(c)).first();
}

/** Explicit replacement authority; never called by normal sync or background work. */
export class ReplacementService {
  private readonly db: D1Database;
  private readonly context: AdmissionContext;
  private readonly catalog: CatalogService;
  private readonly recovery: RecoveryService;
  constructor(db: D1Database, bucket: R2Bucket, context: AdmissionContext, limits: ServiceLimits, maximum: number) {
    this.db = db; this.context = context;
    this.catalog = new CatalogService(db, context, limits);
    this.recovery = new RecoveryService(db, bucket, context, maximum);
  }
  private async own(): Promise<ReplacementAttempt | null> {
    return this.db.prepare("SELECT r.*,CAST(r.recovery_sequence AS TEXT) AS recovery_sequence FROM cloud_replace_attempts r WHERE library_id=? AND device_id=?")
      .bind(this.context.library, this.context.device).first<ReplacementAttempt>();
  }
  private async attempt(raw: unknown) {
    const id = uuid(raw);
    // Authenticate the attempt in one indexed snapshot; the final publication
    // transaction independently rechecks the grant, head, expiry and archive.
    const a = await this.db.prepare("SELECT r.*,CAST(r.recovery_sequence AS TEXT) AS recovery_sequence FROM cloud_replace_attempts r JOIN cloud_devices d ON d.library_id=r.library_id AND d.device_id=r.device_id JOIN cloud_head h ON h.library_id=d.library_id WHERE " + AUTH)
      .bind(...args(this.context)).first<ReplacementAttempt>();
    if (!a || a.attempt_id !== id) fail("REPLACEMENT_RESULT_EXPIRED", 410);
    return a;
  }
  private view(a: ReplacementAttempt) {
    return { attemptId: a.attempt_id, incoming: readStageHeader(JSON.parse(a.header)), nextEpoch: a.next_epoch,
      recoverySequence: a.recovery_sequence, archiveId: a.archive_id, slot: a.slot,
      retryArchiveId: a.retry_archive_id, expiresAt: String(a.expires_at), state: a.state };
  }
  async status() {
    await this.catalog.status();
    const a = await this.own();
    const control = await this.db.prepare("SELECT last_replace_at FROM cloud_backup_control WHERE library_id=? AND service_generation=?")
      .bind(this.context.library, this.context.serviceGeneration).first<{ last_replace_at: number }>();
    if (!control) fail("RECOVERY_INDEX_RECONCILIATION_REQUIRED", 503);
    return { attempt: a ? this.view(a) : null, cooldownUntil: String(control.last_replace_at ? control.last_replace_at + REPLACEMENT_COOLDOWN_MS : 0) };
  }
  async preview(raw: unknown, now = Date.now()) {
    const r = recoveryObject(raw, ["incoming", "manifest", "retryAttemptId"]);
    const incoming = readStageHeader(r.incoming), m = readRecoveryManifest(r.manifest);
    if (incoming.mode !== "replace" || !m.source || !sameHead(m.source.head, incoming.before)) fail("REPLACEMENT_SOURCE_MISMATCH");
    if (!await canReplace(this.db, this.context)) fail("REPLACEMENT_NOT_AUTHORIZED", 403);
    if (await inspectOperation(this.db, this.context, incoming.sequence, incoming.digest)) fail("REPLACEMENT_SEQUENCE_RESOLVED");
    const state = await this.catalog.deviceState();
    if (state.upload) fail("REPLACEMENT_UPLOAD_PENDING");
    const prior = r.retryAttemptId === null ? null : await this.attempt(r.retryAttemptId);
    if (prior && prior.state !== "STALE") fail("REPLACEMENT_RETRY_NOT_READY");
    const p = await this.recovery.preview({ manifest: m }, now, prior?.archive_id ?? null);
    const c = this.context, nextEpoch = crypto.randomUUID(), encoded = JSON.stringify(incoming);
    const guard = this.db.prepare("INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,(SELECT COUNT(*) FROM cloud_devices d JOIN cloud_backup_control b ON b.library_id=d.library_id JOIN cloud_head h ON h.library_id=d.library_id WHERE " +
      AUTH + " AND " + REPLACE_GRANT + " AND b.service_generation=? AND (b.last_replace_at=0 OR b.last_replace_at<=" + REPLACEMENT_DB_NOW + "-" + REPLACEMENT_COOLDOWN_MS + ")" +
      " AND h.epoch=? AND h.revision=CAST(? AS INTEGER) AND h.head_counter=CAST(? AS INTEGER) AND h.fingerprint=?" +
      " AND EXISTS(SELECT 1 FROM cloud_backup_approvals a WHERE a.library_id=d.library_id AND a.device_id=d.device_id AND a.approval_id=? AND a.digest=? AND a.expires_at>" + REPLACEMENT_DB_NOW + ")))")
      .bind(...args(c), c.serviceGeneration,
        incoming.before.epoch, incoming.before.revision, incoming.before.headCounter, incoming.before.fingerprint, p.approvalId, p.digest);
    await this.db.batch([guard,
      this.db.prepare("INSERT INTO cloud_replace_approvals VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(library_id,device_id) DO UPDATE SET approval_id=excluded.approval_id,header=excluded.header,next_epoch=excluded.next_epoch,retry_archive_id=excluded.retry_archive_id,retry_attempt_id=excluded.retry_attempt_id,expires_at=excluded.expires_at")
        .bind(c.library, c.device, p.approvalId, encoded, nextEpoch, prior?.archive_id ?? null, prior?.attempt_id ?? null, Number(p.expiresAt)),
      this.db.prepare("DELETE FROM cloud_commit_assertion WHERE slot=1")]);
    return { ...p, incoming, nextEpoch, retryAttemptId: prior?.attempt_id ?? null, retryArchiveId: prior?.archive_id ?? null };
  }
  async reserve(raw: unknown, now = Date.now()) {
    const r = recoveryObject(raw, ["approvalId", "recoverySequence"]);
    const id = uuid(r.approvalId);
    const op = await this.recovery.reserve({ approvalId: id, sequence: r.recoverySequence }, now, true);
    const a = await this.attempt(id);
    if (a.recovery_sequence !== op.sequence || a.archive_id !== op.archiveId) fail("REPLACEMENT_IDENTITY_CHANGED");
    return { attempt: this.view(a), operation: op };
  }
  async publish(raw: unknown, now = Date.now()) {
    const r = recoveryObject(raw, ["attemptId"]), a = await this.attempt(r.attemptId);
    const header = readStageHeader(JSON.parse(a.header));
    const existing = await inspectOperation(this.db, this.context, header.sequence, header.digest);
    if (existing) {
      if (existing.outcome !== "COMMITTED" || a.state !== "DONE") fail("OPERATION_RETIRED");
      return { receipt: existing, attempt: this.view(a) };
    }
    if (a.state !== "ACTIVE" || a.expires_at <= now) fail("REPLACEMENT_APPROVAL_EXPIRED", 410);
    if (!await canReplace(this.db, this.context)) fail("REPLACEMENT_NOT_AUTHORIZED", 403);
    const proof = await this.recovery.verifyReplacementArchive(a.archive_id);
    if (!proof.manifest.source || !sameHead(proof.manifest.source.head, header.before)) fail("REPLACEMENT_SOURCE_MISMATCH");
    const result = await this.catalog.publishReplacement(header.sequence, header.digest, { ...proof, attempt: a }, now);
    return { ...result, attempt: this.view((await this.own())!) };
  }
  async abandon(raw: unknown) {
    const r = recoveryObject(raw, ["approvalId", "recoverySequence"]), id = uuid(r.approvalId);
    if (typeof r.recoverySequence !== "string") fail("INVALID_RECOVERY_SEQUENCE", 400);
    try { if (counter(r.recoverySequence) === 0n) fail("INVALID_RECOVERY_SEQUENCE", 400); } catch { fail("INVALID_RECOVERY_SEQUENCE", 400); }
    await this.catalog.status();
    let current = await this.own();
    if (current?.attempt_id === id) return this.settle({ attemptId: id, keepForRetry: false });
    const approval = await this.db.prepare("SELECT header FROM cloud_replace_approvals WHERE library_id=? AND device_id=? AND approval_id=?")
      .bind(this.context.library, this.context.device, id).first<{ header: string }>();
    if (!approval) fail("REPLACEMENT_APPROVAL_UNAVAILABLE");
    const h = readStageHeader(JSON.parse(approval.header));
    // Retirement prevents a not-yet-admitted reserve as well as delayed publication.
    const result = await this.catalog.retire(h.sequence, h.digest);
    // The catalog sequence also fences recovery reservation. An unadmitted replacement
    // owns no recovery operation: do not consume a sequence or require an obsolete preview.
    // A reserve that won before retirement has an exact attempt and is settled below.
    current = await this.own();
    if (current?.attempt_id === id) return this.settle({ attemptId: id, keepForRetry: false });
    if (result.receipt.outcome !== "RETIRED") fail("REPLACEMENT_IDENTITY_CHANGED");
    return { ...result, attempt: null };
  }
  /** Resolve publication or fence it before releasing an attempt or issuing a fresh approval. */
  async settle(raw: unknown) {
    const r = recoveryObject(raw, ["attemptId", "keepForRetry"]), a = await this.attempt(r.attemptId);
    if (typeof r.keepForRetry !== "boolean") fail("INVALID_REPLACEMENT_REFERENCE", 400);
    const header = readStageHeader(JSON.parse(a.header));
    if (a.state === "CANCELED") return { attempt: this.view(a) };
    if (a.state === "STALE" && r.keepForRetry) return { attempt: this.view(a) };
    if (r.keepForRetry && a.state === "ACTIVE") await this.recovery.verifyReplacementArchive(a.archive_id);
    const result = await this.catalog.retire(header.sequence, header.digest);
    if (result.receipt.outcome === "COMMITTED") {
      const current = await this.attempt(a.attempt_id);
      if (current.state !== "DONE") fail("REPLACEMENT_IDENTITY_CHANGED");
      return { ...result, attempt: this.view(current) };
    }
    if (a.state === "ACTIVE") {
      const op = await this.recovery.abandon({ approvalId: a.attempt_id, sequence: a.recovery_sequence });
      if (r.keepForRetry && op.state !== "DONE") fail("REPLACEMENT_RECOVERY_NOT_READY");
    }
    const c = this.context, target = r.keepForRetry ? "STALE" : "CANCELED";
    const slotCheck = r.keepForRetry ? " AND EXISTS(SELECT 1 FROM cloud_backup_slots s WHERE s.library_id=d.library_id AND s.archive_id=? AND s.state='READY')" : "";
    const guard = this.db.prepare("INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,(SELECT COUNT(*) FROM cloud_devices d JOIN cloud_backup_control b ON b.library_id=d.library_id WHERE " +
      AUTH + " AND b.service_generation=? AND b.revision<9223372036854775807" +
      " AND EXISTS(SELECT 1 FROM cloud_replace_attempts WHERE library_id=d.library_id AND device_id=d.device_id AND attempt_id=? AND state IN('ACTIVE','STALE'))" +
      " AND EXISTS(SELECT 1 FROM cloud_receipts WHERE library_id=d.library_id AND device_id=d.device_id AND sequence=CAST(? AS INTEGER) AND digest=? AND outcome='RETIRED')" + slotCheck + "))")
      .bind(...args(c), c.serviceGeneration, a.attempt_id, header.sequence, header.digest, ...(r.keepForRetry ? [a.archive_id] : []));
    await this.db.batch([guard,
      this.db.prepare("UPDATE cloud_replace_attempts SET state=? WHERE library_id=? AND device_id=? AND attempt_id=?")
        .bind(target, c.library, c.device, a.attempt_id),
      this.db.prepare("UPDATE cloud_backup_control SET revision=revision+1 WHERE library_id=?").bind(c.library),
      this.db.prepare("DELETE FROM cloud_commit_assertion WHERE slot=1")]);
    return { ...result, attempt: this.view((await this.own())!) };
  }
}
