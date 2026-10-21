/* SPDX-License-Identifier: GPL-3.0-or-later */
/** CONTROL survives DATA rollback. Outstanding reservations never expire.
 * Bounds are application safety estimates, not provider billing guarantees.
 */
export class BudgetError extends Error {
  readonly code: string;
  constructor(code: string) { super(code); this.name = "BudgetError"; this.code = code; }
}
const fail = (code: string): never => { throw new BudgetError(code); };
export const BUDGET_POLICY = Object.freeze({ version: 1, reads: 2_000_000, writes: 50_000,
  bytes: 250_000_000, statements: 32 });
// Includes admission, settlement, indexes/FKs, rejection recording and one trip.
// Measured assertions test this allowance against real D1, including CONTROL.
export const ACCOUNTING = Object.freeze({ reads: 128, writes: 32, statements: 5 });
const DENIED = Object.freeze({ reads: 16, writes: 4 });
const DAY = "CAST(unixepoch()/86400 AS INTEGER)";
const current = (kind: "reads" | "writes") => `CASE WHEN day=${DAY} THEN spent_${kind} ELSE held_${kind} END`;
const policy = "policy_version=1 AND read_limit BETWEEN 1 AND 2000000 AND write_limit BETWEEN 1 AND 50000 AND size_limit BETWEEN 1 AND 250000000 AND statement_limit BETWEEN 6 AND 32";
export type BudgetEstimate = Readonly<{ reads: number; writes: number; growth: number }>;
export type BudgetScope = Readonly<{ resource: string; library: string; generation: string }>;
function integer(n: unknown, min: number, max = Number.MAX_SAFE_INTEGER): n is number {
  return typeof n === "number" && Number.isSafeInteger(n) && n >= min && n <= max;
}
export function validateEstimate(e: BudgetEstimate): void {
  if (!e || Object.keys(e).sort().join(",") !== "growth,reads,writes" ||
    !integer(e.reads, ACCOUNTING.reads, BUDGET_POLICY.reads) ||
    !integer(e.writes, ACCOUNTING.writes, BUDGET_POLICY.writes) ||
    !integer(e.growth, 0, BUDGET_POLICY.bytes)) fail("BUDGET_ESTIMATE_INVALID");
}
/** Static route envelopes cover bounded indexed SQL, cascades and staging.
 * Growth includes bounded row/key/index storage even for many tiny chunks.
 * Large local proof limits must not silently become deployed admission.
 */
export function requestEstimate(path: string, pages = 128, bytes = 512 * 1024): BudgetEstimate {
  if (!integer(pages, 1, 2048) || !integer(bytes, 16 * 1024, 32 * 1024 * 1024)) fail("BUDGET_ESTIMATE_INVALID");
  let e: BudgetEstimate;
  if (path === "/maintenance/housekeep") e = { reads: 16384, writes: 4096, growth: 128 * 1024 };
  else if (["/v1/head", "/v1/connect", "/v1/pages", "/v1/uploads/status", "/v1/recovery/status",
    "/v1/recovery/download", "/v1/replacement/status"].includes(path)) e = { reads: 512, writes: ACCOUNTING.writes, growth: 0 };
  else if (/^\/v1\/(recovery|replacement)\//.test(path)) e = { reads: 1024 + 32 * pages, writes: 256 + 12 * pages, growth: 256 * 1024 + 3 * bytes + 8192 * pages };
  else if (/^\/v1\/uploads\/(start|chunk|seal|publish|retire)$/.test(path)) e = { reads: 1024 + 32 * pages, writes: 256 + 12 * pages, growth: 128 * 1024 + 3 * bytes + 8192 * pages };
  else return fail("BUDGET_ROUTE_UNAVAILABLE");
  validateEstimate(e); return Object.freeze(e);
}
type Meta = { rows_read: number; rows_written: number; size_after: number };
function metadata(result: unknown): Meta {
  const r = result as { success?: boolean; meta?: Meta };
  if (r?.success !== true || !r.meta || !integer(r.meta.rows_read, 0) ||
    !integer(r.meta.rows_written, 0) || !integer(r.meta.size_after, 0)) return fail("BUDGET_METER_UNAVAILABLE");
  return r.meta;
}
export async function reserveBudget(control: D1Database, scope: BudgetScope, estimate: BudgetEstimate, mode: "public" | "operator" | "control" = "public"): Promise<BudgetReservation> {
  validateEstimate(estimate);
  estimate = Object.freeze({ ...estimate });
  const id = crypto.randomUUID();
  const maintenance = mode !== "public";
  const table = maintenance ? "cloud_budget_maintenance" : "cloud_fence_tickets";
  // Authenticated CONTROL-only status/close must still work at the daily cap.
  // They are charged, can exceed the target, and never grant a DATA capability.
  const allowance = mode === "control" ? "1" : `b.state='ACTIVE'
   AND (${current("reads")})+${estimate.reads}<=read_limit
   AND (${current("writes")})+${estimate.writes}<=write_limit
   AND data_size+held_growth+${estimate.growth}<=size_limit`;
  const authority = maintenance ? "f.resource_id=? AND f.library_id=?" : "f.resource_id=? AND f.library_id=? AND f.service_generation=? AND f.state='OPEN'";
  const scopeValues = maintenance ? [scope.resource, scope.library] : [scope.resource, scope.library, scope.generation];
  const ticket = control.prepare(`INSERT INTO ${table}(ticket_id,resource_id,created_at,budget_reads,budget_writes,budget_growth)
   SELECT ?,f.resource_id,CAST(unixepoch('subsec')*1000 AS INTEGER),?,?,? FROM cloud_fence f
   JOIN cloud_budget b ON b.resource_id=f.resource_id WHERE ${authority}
   AND f.provenance='fresh-resources-v1' AND ${policy} AND day<=${DAY}
   AND (${allowance}) RETURNING ticket_id`)
    .bind(id, estimate.reads, estimate.writes, estimate.growth, ...scopeValues);
  // Both statements are one transaction. A lost response keeps the inserted
  // ticket and its capacity. Rejected traffic has a bounded accounting debit;
  // unlimited rejected requests can still exceed the project target.
  const amount = (name: string, fallback: number) => `COALESCE((SELECT budget_${name} FROM ${table} WHERE ticket_id=?),${fallback})`;
  const update = control.prepare(`UPDATE cloud_budget SET
   spent_reads=(${current("reads")})+${amount("reads", DENIED.reads)},
   spent_writes=(${current("writes")})+${amount("writes", DENIED.writes)},
   held_reads=held_reads+${amount("reads", 0)},held_writes=held_writes+${amount("writes", 0)},
   held_growth=held_growth+${amount("growth", 0)},day=${DAY}
   WHERE resource_id=? AND ${policy} AND day<=${DAY}
   RETURNING size_limit,statement_limit,data_size`).bind(id, id, id, id, id, scope.resource);
  let results: D1Result[];
  try { results = await control.batch([ticket, update]); }
  catch { return fail("BUDGET_ADMISSION_UNAVAILABLE"); }
  const accepted = results[0]?.results[0] as { ticket_id?: string } | undefined;
  if (accepted?.ticket_id !== id) return fail("BUDGET_OR_MAINTENANCE_UNAVAILABLE");
  const row = results[1]?.results[0] as { size_limit: number; statement_limit: number; data_size: number } | undefined;
  const reservation = new BudgetReservation(control, id, scope.resource, estimate,
    row?.size_limit ?? 0, mode === "operator" ? 48 : row?.statement_limit ?? 0, row?.data_size ?? 0, table);
  try {
    const metas = results.map(metadata);
    if (!row || metas.reduce((n, m) => n + m.rows_read, 0) > ACCOUNTING.reads / 2 ||
      metas.reduce((n, m) => n + m.rows_written, 0) > ACCOUNTING.writes / 2) fail("BUDGET_ACCOUNTING_BOUND_EXCEEDED");
  } catch (e) { await reservation.trip(); throw e; }
  return reservation;
}
export class BudgetReservation {
  private reads = ACCOUNTING.reads;
  private writes = ACCOUNTING.writes;
  private statements = 0;
  private size: number;
  private unknown = false;
  private chargeFull = false;
  private blocked = false;
  private settled = false;
  private readonly control: D1Database;
  readonly id: string;
  private readonly resource: string;
  readonly estimate: BudgetEstimate;
  private readonly sizeLimit: number;
  private readonly statementLimit: number;
  private readonly table: "cloud_fence_tickets" | "cloud_budget_maintenance";
  constructor(control: D1Database, id: string, resource: string, estimate: BudgetEstimate,
    sizeLimit: number, statementLimit: number, size: number, table: "cloud_fence_tickets" | "cloud_budget_maintenance") {
    this.control = control; this.id = id; this.resource = resource; this.estimate = estimate;
    this.sizeLimit = sizeLimit; this.statementLimit = statementLimit; this.size = size; this.table = table;
  }

  ready(): void {
    if (this.blocked || this.settled || this.unknown) fail("BUDGET_RESERVATION_BLOCKED");
  }
  before(count: number): void {
    this.ready();
    if (!integer(count, 1) || this.statements + count + ACCOUNTING.statements > this.statementLimit) fail("REQUEST_STATEMENT_BUDGET_EXCEEDED");
    this.statements += count;
  }
  observe(results: unknown[], data = true): void {
    try {
      for (const result of results) {
        const m = metadata(result); this.reads += m.rows_read; this.writes += m.rows_written; if (data) this.size = Math.max(this.size, m.size_after);
      }
    } catch (e) { this.unknown = true; throw e; }
    if (this.reads > this.estimate.reads || this.writes > this.estimate.writes || this.size > this.sizeLimit) {
      this.blocked = true; fail("BUDGET_ESTIMATE_EXCEEDED");
    }
  }
  // A definitive rollback still consumed unreported SQL work. Retain the full
  // estimate as that request's daily charge, but it does not leave a write open.
  unmetered(): void { this.chargeFull = true; }
  async trip(): Promise<void> {
    this.blocked = true;
    try { await this.control.prepare("UPDATE cloud_budget SET state='BLOCKED' WHERE resource_id=?").bind(this.resource).run(); }
    catch { /* Existing reservation remains durable even if the trip reply is lost. */ }
  }
  async settle(): Promise<void> {
    if (this.settled) return;
    this.settled = true;
    if (this.blocked || this.unknown) { await this.trip(); return; }
    const amount = (name: string) => `(SELECT budget_${name} FROM ${this.table} WHERE ticket_id=?)`;
    try {
      const results = await this.control.batch([
        this.control.prepare(`UPDATE cloud_budget SET
         spent_reads=(${current("reads")})-${amount("reads")}+?,
         spent_writes=(${current("writes")})-${amount("writes")}+?,
         held_reads=held_reads-${amount("reads")},held_writes=held_writes-${amount("writes")},
         held_growth=held_growth-${amount("growth")},data_size=MAX(data_size,?),day=${DAY},last_settled_ticket=?
         WHERE resource_id=? AND ${policy} AND day<=${DAY}
         AND held_reads>=${amount("reads")} AND held_writes>=${amount("writes")} AND held_growth>=${amount("growth")}
         RETURNING resource_id`)
          .bind(this.id, this.chargeFull ? this.estimate.reads : this.reads, this.id, this.chargeFull ? this.estimate.writes : this.writes, this.id, this.id, this.id, this.size, this.id,
            this.resource, this.id, this.id, this.id),
        this.control.prepare(`DELETE FROM ${this.table} WHERE ticket_id=? AND EXISTS(SELECT 1 FROM cloud_budget WHERE resource_id=? AND last_settled_ticket=?)`)
          .bind(this.id, this.resource, this.id)
      ]);
      const metas = results.map(metadata);
      if (metas.reduce((n, m) => n + m.rows_read, 0) > ACCOUNTING.reads / 2 ||
        metas.reduce((n, m) => n + m.rows_written, 0) > ACCOUNTING.writes / 2) await this.trip();
    } catch { /* Never retry an ambiguous settlement; it either settled or still reserves capacity. */ }
  }
  summary() { return { statements: this.statements + 4, rowsRead: this.chargeFull ? this.estimate.reads : this.reads, rowsWritten: this.chargeFull ? this.estimate.writes : this.writes, dataSize: this.size }; }
}
