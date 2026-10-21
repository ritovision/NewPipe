/* SPDX-License-Identifier: GPL-3.0-or-later */
import { FenceError } from "./fence.ts";
const NOW = "CAST(unixepoch('subsec')*1000 AS INTEGER)";
export const HOUSEKEEPING = Object.freeze({ devices: 4, receipts: 64, keepReceipts: 16, retentionMs: 30 * 86400000, stageRows: 128 });
const fail = (code: string): never => { throw new FenceError(code); };
const unpinned = `NOT EXISTS(SELECT 1 FROM cloud_replace_attempts a WHERE a.library_id=r.library_id AND a.device_id=r.device_id AND json_extract(a.header,'$.sequence')=CAST(r.sequence AS TEXT))
 AND NOT EXISTS(SELECT 1 FROM cloud_uploads u WHERE u.library_id=r.library_id AND u.device_id=r.device_id AND u.sequence=r.sequence)`;
const oldReceipt = `r.sequence<=d.resolved_sequence-${HOUSEKEEPING.keepReceipts} AND ${unpinned}`;
const safeStage = `NOT EXISTS(SELECT 1 FROM cloud_backup_operations b WHERE b.library_id=u.library_id AND b.state NOT IN('DONE','CANCELED'))
 AND NOT EXISTS(SELECT 1 FROM cloud_replace_attempts a WHERE a.library_id=u.library_id AND a.device_id=u.device_id AND a.state IN('ACTIVE','STALE'))
 AND (SELECT COUNT(*) FROM (SELECT 1 FROM cloud_upload_changes c WHERE c.library_id=u.library_id AND c.device_id=u.device_id AND c.sequence=u.sequence LIMIT ${HOUSEKEEPING.stageRows + 1}))<=${HOUSEKEEPING.stageRows}
 AND (SELECT COUNT(*) FROM (SELECT 1 FROM cloud_upload_chunks c WHERE c.library_id=u.library_id AND c.device_id=u.device_id AND c.sequence=u.sequence LIMIT ${HOUSEKEEPING.stageRows + 1}))<=${HOUSEKEEPING.stageRows}`;
const disposable = `((u.status IN('UPLOADING','SEALED') AND d.resolved_sequence=u.sequence-1 AND (u.expires_at<=${NOW} OR d.revoked=1 OR d.service_generation!=?))
 OR (u.status IN('COMMITTED','RETIRED') AND d.resolved_sequence>=u.sequence)) AND ${safeStage}`;
type Stage = { sequence: string; digest: string; status: string };
function knownRollback(error: unknown): boolean {
 return error instanceof Error && /^D1_ERROR: CHECK constraint failed: ok = 1(?:: SQLITE_CONSTRAINT(?: \(extended: SQLITE_CONSTRAINT_CHECK\))?)?$/.test(error.message);
}
/** The authenticated operator must wrap this database in an ordinary external request ticket.
 * Never receives an R2 capability. Old high-water marks and referenced receipts are retained.
 */
export async function housekeep(db: D1Database, library: string, generation: string, input: { devices?: number; receipts?: number } = {}) {
 if (!input || typeof input !== "object" || Array.isArray(input) || Object.keys(input).some(k => !["devices", "receipts"].includes(k))) fail("HOUSEKEEPING_LIMIT_INVALID");
 const devices = input.devices ?? HOUSEKEEPING.devices, receiptLimit = input.receipts ?? HOUSEKEEPING.receipts;
 if (!Number.isInteger(devices) || devices < 1 || devices > HOUSEKEEPING.devices ||
  !Number.isInteger(receiptLimit) || receiptLimit < 0 || receiptLimit > HOUSEKEEPING.receipts) fail("HOUSEKEEPING_LIMIT_INVALID");
 // Refuse an incomplete/restored schema before any cleanup effect.
 await db.prepare("SELECT through_sequence,first_seen FROM cloud_receipt_retention WHERE library_id=? LIMIT 0").bind(library).all();
 const oldCursor = await db.prepare("SELECT device_cursor FROM cloud_housekeeping_cursor WHERE library_id=?").bind(library).first<{device_cursor: string}>();
 const window = (await db.prepare("SELECT device_id FROM cloud_devices WHERE library_id=? AND device_id>? ORDER BY device_id LIMIT ?")
  .bind(library, oldCursor?.device_cursor ?? "", devices + 1).all<{device_id: string}>()).results;
 const selected = window.slice(0, devices);
 let retired = 0, terminalRemoved = 0, receiptsRemoved = 0, raced = 0, retainedStages = 0;
 for (const { device_id: device } of selected) {
  const stage = await db.prepare(`SELECT CAST(u.sequence AS TEXT) AS sequence,u.digest,u.status FROM cloud_uploads u JOIN cloud_devices d ON d.library_id=u.library_id AND d.device_id=u.device_id
   WHERE u.library_id=? AND u.device_id=? AND ${disposable}`).bind(library, device, generation).first<Stage>();
  if (stage) {
   const active = stage.status === "UPLOADING" || stage.status === "SEALED";
   const batch = [db.prepare(`INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,(SELECT COUNT(*) FROM cloud_uploads u JOIN cloud_devices d ON d.library_id=u.library_id AND d.device_id=u.device_id
    WHERE u.library_id=? AND u.device_id=? AND u.sequence=CAST(? AS INTEGER) AND u.digest=? AND u.status=? AND ${disposable}))`)
    .bind(library, device, stage.sequence, stage.digest, stage.status, generation)];
   if (active) batch.push(
    db.prepare("INSERT INTO cloud_receipts(library_id,device_id,sequence,digest,outcome,epoch,revision,head_counter,fingerprint) SELECT library_id,?,CAST(? AS INTEGER),?,'RETIRED',epoch,revision,head_counter,fingerprint FROM cloud_head WHERE library_id=?")
     .bind(device, stage.sequence, stage.digest, library),
    db.prepare("UPDATE cloud_devices SET resolved_sequence=CAST(? AS INTEGER) WHERE library_id=? AND device_id=?").bind(stage.sequence, library, device)
   );
   batch.push(db.prepare("DELETE FROM cloud_uploads WHERE library_id=? AND device_id=? AND sequence=CAST(? AS INTEGER) AND digest=?")
    .bind(library, device, stage.sequence, stage.digest), db.prepare("DELETE FROM cloud_commit_assertion WHERE slot=1"));
   try { await db.batch(batch); if (active) retired++; else terminalRemoved++; }
   catch (error) { if (!knownRollback(error)) throw error; raced++; }
  } else if (await db.prepare("SELECT 1 FROM cloud_uploads WHERE library_id=? AND device_id=?").bind(library, device).first()) retainedStages++;
  // Observe a fixed prefix once. Advancing high-water never ages newer receipts early.
  await db.prepare(`INSERT INTO cloud_receipt_retention(library_id,device_id,through_sequence,first_seen)
   SELECT d.library_id,d.device_id,d.resolved_sequence-${HOUSEKEEPING.keepReceipts},${NOW} FROM cloud_devices d
   WHERE d.library_id=? AND d.device_id=? AND EXISTS(SELECT 1 FROM cloud_receipts r WHERE r.library_id=d.library_id AND r.device_id=d.device_id AND ${oldReceipt})
   ON CONFLICT(library_id,device_id) DO NOTHING`).bind(library, device).run();
  if (receiptsRemoved < receiptLimit) {
   const removed = await db.prepare(`DELETE FROM cloud_receipts WHERE library_id=? AND device_id=? AND sequence IN(
    SELECT r.sequence FROM cloud_receipts r JOIN cloud_devices d ON d.library_id=r.library_id AND d.device_id=r.device_id
    JOIN cloud_receipt_retention t ON t.library_id=r.library_id AND t.device_id=r.device_id
    WHERE r.library_id=? AND r.device_id=? AND ${oldReceipt} AND r.sequence<=t.through_sequence
    AND t.first_seen<=${NOW}-${HOUSEKEEPING.retentionMs} ORDER BY r.sequence LIMIT ?) RETURNING CAST(sequence AS TEXT) AS sequence`)
    .bind(library, device, library, device, receiptLimit - receiptsRemoved).all<{sequence: string}>();
   receiptsRemoved += removed.results.length;
  }
  await db.prepare(`DELETE FROM cloud_receipt_retention WHERE library_id=? AND device_id=? AND NOT EXISTS(
   SELECT 1 FROM cloud_receipts r JOIN cloud_devices d ON d.library_id=r.library_id AND d.device_id=r.device_id
   WHERE r.library_id=cloud_receipt_retention.library_id AND r.device_id=cloud_receipt_retention.device_id
   AND r.sequence<=cloud_receipt_retention.through_sequence AND ${oldReceipt})`).bind(library, device).run();
 }
 const next = window.length > devices ? selected.at(-1)!.device_id : "";
 await db.prepare(`INSERT INTO cloud_housekeeping_cursor(library_id,device_cursor) SELECT library_id,? FROM cloud_head WHERE library_id=?
  ON CONFLICT(library_id) DO UPDATE SET device_cursor=excluded.device_cursor WHERE cloud_housekeeping_cursor.device_cursor=?`)
  .bind(next, library, oldCursor?.device_cursor ?? "").run();
 return { inspected: selected.length, retired, terminalRemoved, receiptsRemoved, raced, retainedStages, wrapped: next === "" };
}
