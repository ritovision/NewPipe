/* SPDX-License-Identifier: GPL-3.0-or-later */
import { checkedHead, counter, MAX_COUNTER } from "./protocol.ts";
import type { Head } from "./protocol.ts";

/** Internal authenticated context. serviceGeneration comes from deployment config, never request JSON. */
export type AdmissionContext = Readonly<{
  library: string; device: string; tokenHash: string; authGeneration: string; serviceGeneration: string;
}>;
export type Operation = Readonly<{ sequence: string; digest: string; before: Head; nextFingerprint: string }>;
export type Receipt = Readonly<{ sequence: string; digest: string; outcome: "COMMITTED" | "RETIRED"; head: Head }>;
type RawReceipt = { sequence: string; digest: string; outcome: "COMMITTED" | "RETIRED";
  epoch: string; revision: string; head_counter: string; fingerprint: string };
const identity = /^[A-Za-z0-9_-]{1,128}$/;
const hash = /^[0-9a-f]{64}$/;
const MAX_MUTATIONS = 16; // Guard/receipt/auth/result/error lookups stay below the 32-statement proof budget.
const AUTH = `d.library_id=? AND d.device_id=? AND d.token_hash=?
 AND d.auth_generation=CAST(? AS INTEGER) AND d.service_generation=?
 AND d.revoked=0 AND d.can_write=1`;
const SELECT_RECEIPT = `SELECT CAST(r.sequence AS TEXT) AS sequence,r.digest,r.outcome,r.epoch,
 CAST(r.revision AS TEXT) AS revision,CAST(r.head_counter AS TEXT) AS head_counter,r.fingerprint
 FROM cloud_receipts r JOIN cloud_devices d ON d.library_id=r.library_id AND d.device_id=r.device_id
 WHERE ${AUTH} AND r.sequence=CAST(? AS INTEGER)`;
export class AdmissionError extends Error {
  constructor(code: string) { super(code); this.name = "AdmissionError"; }
}
function fail(code: string): never { throw new AdmissionError(code); }
function parameters(c: AdmissionContext): string[] {
  if (!identity.test(c.library) || !identity.test(c.device) || !identity.test(c.serviceGeneration) || !hash.test(c.tokenHash)) fail("INVALID_AUTH_CONTEXT");
  counter(c.authGeneration);
  return [c.library, c.device, c.tokenHash, c.authGeneration, c.serviceGeneration];
}
function operationSequence(seq: string): void {
  if (counter(seq) === 0n) fail("INVALID_SEQUENCE");
}
async function authenticate(db: D1Database, c: AdmissionContext): Promise<string> {
  const row = await db.prepare(`SELECT CAST(d.resolved_sequence AS TEXT) AS sequence FROM cloud_devices d WHERE ${AUTH}`)
    .bind(...parameters(c)).first<{ sequence: string }>();
  if (!row) fail("AUTH_REJECTED");
  counter(row.sequence); return row.sequence;
}
function receipt(c: AdmissionContext, row: RawReceipt): Receipt {
  return Object.freeze({ sequence: row.sequence, digest: row.digest, outcome: row.outcome,
    head: checkedHead({ serviceGeneration: c.serviceGeneration, library: c.library, epoch: row.epoch,
      revision: row.revision, headCounter: row.head_counter, fingerprint: row.fingerprint }) });
}
async function findReceipt(db: D1Database, c: AdmissionContext, seq: string): Promise<Receipt | null> {
  // Authentication predicates are repeated IN this query, not assumed from an earlier SELECT.
  const row = await db.prepare(SELECT_RECEIPT).bind(...parameters(c), seq).first<RawReceipt>();
  return row ? receipt(c, row) : null;
}
function requireDigest(r: Receipt, digest: string): Receipt {
  if (r.digest !== digest) fail("REPLAY_MISMATCH"); return r;
}
async function beforeAttempt(db: D1Database, c: AdmissionContext, seq: string, digest: string): Promise<Receipt | null> {
  operationSequence(seq); if (!hash.test(digest)) fail("INVALID_DIGEST");
  const resolved = await authenticate(db, c);
  const existing = await findReceipt(db, c, seq);
  if (existing) return requireDigest(existing, digest);
  if (counter(seq) <= counter(resolved)) fail("RESULT_EXPIRED");
  if (counter(seq) !== counter(resolved) + 1n) fail("SEQUENCE_GAP");
  return null;
}
async function afterFailure(db: D1Database, c: AdmissionContext, seq: string, digest: string, error: unknown): Promise<Receipt> {
  try {
    await authenticate(db, c);
    const r = await findReceipt(db, c, seq);
    if (r) return requireDigest(r, digest);
  } catch (lookupError) {
    if (lookupError instanceof AdmissionError) throw lookupError;
    fail("OUTCOME_UNKNOWN");
  }
  // A reported constraint failure rolls back the D1 batch. A timeout/network failure
  // without a receipt is NOT permission to skip or re-identify an operation.
  if (String(error).includes("constraint failed")) fail("PRECONDITION_FAILED");
  fail("OUTCOME_UNKNOWN");
}
function committed(r: Receipt): Receipt {
  if (r.outcome === "RETIRED") fail("OPERATION_RETIRED"); return r;
}

/**
 * Local M1 control-plane proof. Mutations must be prepared by a trusted typed domain
 * compiler after validating the exact fenced base; this is NOT a SQL-over-HTTP API.
 * No production endpoint calls this until the remaining M1/M2 gates are complete.
 */
export async function guardedCommit(db: D1Database, c: AdmissionContext, o: Operation,
  mutations: readonly D1PreparedStatement[]): Promise<Receipt> {
  checkedHead(o.before);
  if (o.before.serviceGeneration !== c.serviceGeneration || o.before.library !== c.library || !hash.test(o.nextFingerprint)) fail("INVALID_OPERATION");
  if (mutations.length > MAX_MUTATIONS) fail("STATEMENT_BUDGET_EXCEEDED");
  const existing = await beforeAttempt(db, c, o.sequence, o.digest);
  if (existing) return committed(existing);
  // COUNT always emits one non-NULL scalar. No INSERT...SELECT WHERE that can silently insert zero rows.
  const guard = db.prepare(`INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,
    (SELECT COUNT(*) FROM cloud_head h JOIN cloud_devices d ON d.library_id=h.library_id
     WHERE ${AUTH} AND d.resolved_sequence=CAST(? AS INTEGER)-1
     AND h.epoch=? AND h.revision=CAST(? AS INTEGER) AND h.head_counter=CAST(? AS INTEGER) AND h.fingerprint=?
     AND h.revision<9223372036854775807 AND h.head_counter<9223372036854775807))`)
    .bind(...parameters(c), o.sequence, o.before.epoch, o.before.revision, o.before.headCounter, o.before.fingerprint);
  const insertReceipt = db.prepare(`INSERT INTO cloud_receipts
    (library_id,device_id,sequence,digest,outcome,epoch,revision,head_counter,fingerprint)
    SELECT library_id,?,CAST(? AS INTEGER),?,'COMMITTED',epoch,revision+1,head_counter+1,?
    FROM cloud_head WHERE library_id=?`).bind(c.device, o.sequence, o.digest, o.nextFingerprint, c.library);
  const batch = [guard, ...mutations, insertReceipt,
    db.prepare("UPDATE cloud_head SET revision=revision+1,head_counter=head_counter+1,fingerprint=? WHERE library_id=?")
      .bind(o.nextFingerprint, c.library),
    db.prepare("UPDATE cloud_devices SET resolved_sequence=CAST(? AS INTEGER) WHERE library_id=? AND device_id=?")
      .bind(o.sequence, c.library, c.device),
    db.prepare("DELETE FROM cloud_commit_assertion WHERE slot=1"),
    db.prepare(SELECT_RECEIPT).bind(...parameters(c), o.sequence)];
  try {
    const results = await db.batch(batch);
    const rows = results[results.length - 1]!.results as unknown as RawReceipt[];
    if (rows.length !== 1) fail("OUTCOME_UNKNOWN");
    return committed(requireDigest(receipt(c, rows[0]!), o.digest));
  } catch (error) {
    return committed(await afterFailure(db, c, o.sequence, o.digest, error));
  }
}

/** Fences delayed submissions. Resolves a commit OR retires exactly the next device sequence. */
export async function resolveOrRetire(db: D1Database, c: AdmissionContext, sequence: string, digest: string,
  domainAssertions: readonly D1PreparedStatement[] = []): Promise<Receipt> {
  if (domainAssertions.length > 4) fail("STATEMENT_BUDGET_EXCEEDED");
  const existing = await beforeAttempt(db, c, sequence, digest);
  if (existing) return existing;
  const guard = db.prepare(`INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,
    (SELECT COUNT(*) FROM cloud_head h JOIN cloud_devices d ON d.library_id=h.library_id
     WHERE ${AUTH} AND d.resolved_sequence=CAST(? AS INTEGER)-1))`).bind(...parameters(c), sequence);
  const statements = [guard, ...domainAssertions,
    db.prepare(`INSERT INTO cloud_receipts(library_id,device_id,sequence,digest,outcome,epoch,revision,head_counter,fingerprint)
      SELECT library_id,?,CAST(? AS INTEGER),?,'RETIRED',epoch,revision,head_counter,fingerprint
      FROM cloud_head WHERE library_id=?`).bind(c.device, sequence, digest, c.library),
    db.prepare("UPDATE cloud_devices SET resolved_sequence=CAST(? AS INTEGER) WHERE library_id=? AND device_id=?")
      .bind(sequence, c.library, c.device),
    db.prepare("DELETE FROM cloud_commit_assertion WHERE slot=1"),
    db.prepare(SELECT_RECEIPT).bind(...parameters(c), sequence)];
  try {
    const results = await db.batch(statements);
    const rows = results[results.length - 1]!.results as unknown as RawReceipt[];
    if (rows.length !== 1) fail("OUTCOME_UNKNOWN");
    return requireDigest(receipt(c, rows[0]!), digest);
  } catch (error) {
    return afterFailure(db, c, sequence, digest, error);
  }
}

/** Validate in memory before any SQL casts; values are bound as decimal text, never Number. */
export function nextCounter(value: string): string {
  const n = counter(value); if (n === MAX_COUNTER) fail("COUNTER_EXHAUSTED"); return String(n + 1n);
}


/** Authenticated receipt resolution before an expensive transfer/publish; never accepts caller auth fields over HTTP. */
export async function inspectOperation(db: D1Database, context: AdmissionContext, sequence: string, digest: string): Promise<Receipt | null> {
  return beforeAttempt(db, context, sequence, digest);
}
