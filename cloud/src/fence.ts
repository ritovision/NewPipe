/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * A ticket is durable before any data effect starts. It is removed only once every
 * effect has a definite response. No timeout, lease expiry, or restarted process
 * may remove a ticket: an unknown write can still arrive at its storage service.
 */
import { BudgetReservation, reserveBudget, requestEstimate, type BudgetEstimate } from "./budget.ts";
export class FenceError extends Error {
  readonly code: string;
  constructor(code: string) { super(code); this.code = code; }
}
type Scope = { resource: string; library: string; generation: string };
const rejected = (): never => { throw new FenceError("MAINTENANCE_REQUIRED"); };
export async function admit(control: D1Database | undefined, scope: Scope, estimate: BudgetEstimate = requestEstimate("/v1/uploads/publish")): Promise<RequestFence> {
  if (!control || !scope.resource) return rejected();
  return new RequestFence(await reserveBudget(control, scope, estimate));
}
/** Authenticated operator budget only; does not claim or relax maintenance authority. */
export async function admitMaintenanceBudget(control: D1Database, scope: Scope): Promise<RequestFence> {
  return new RequestFence(await reserveBudget(control, scope, { reads: 200000, writes: 5000, growth: 2 * 1024 * 1024 }, "operator"));
}
/** Limited to operator status/close; policy is required even for emergency control. */
export async function admitControlBudget(control: D1Database, scope: Scope): Promise<RequestFence> {
  return new RequestFence(await reserveBudget(control, scope, { reads: 200000, writes: 128, growth: 0 }, "control"));
}
export class RequestFence {
  private closed = false;
  private uncertain = false;
  private pending = 0;
  private readonly budget: BudgetReservation;
  constructor(budget: BudgetReservation) { this.budget = budget; }
  private async call<T>(run: () => Promise<T>, mutation: boolean, transactional = false): Promise<T> {
    if (this.closed) return rejected();
    this.budget.ready();
    this.pending++;
    try { return await run(); }
    catch (e) {
      // Only this exact database assertion is a known transaction rollback.
      // Network errors, arbitrary constraints and R2 errors remain unresolved.
      const rolledBack = transactional && e instanceof Error &&
        /^D1_ERROR: CHECK constraint failed: ok = 1(?:: SQLITE_CONSTRAINT(?: \(extended: SQLITE_CONSTRAINT_CHECK\))?)?$/.test(e.message);
      if (mutation && !rolledBack) this.uncertain = true;
      throw e;
    } finally { this.pending--; }
  }
  private async sql<T>(run: () => Promise<T>, count: number, mutation: boolean, data: boolean): Promise<T> {
    if (this.closed) return rejected();
    this.budget.before(count);
    return this.call(async () => {
      let result: T;
      try { result = await run(); }
      catch (e) { this.budget.unmetered(); throw e; }
      try { this.budget.observe(Array.isArray(result) ? result : [result], data); }
      catch (e) { await this.budget.trip(); throw e; }
      return result;
    }, mutation, true);
  }

  database(db: D1Database, data = true): D1Database {
    const raw = new WeakMap<D1PreparedStatement, { statement: D1PreparedStatement; read: boolean }>();
    const wrap = (statement: D1PreparedStatement, read: boolean): D1PreparedStatement => {
      const result = new Proxy(statement, { get: (target, name) => {
        if (name === "bind") return (...args: unknown[]) => wrap(target.bind(...args), read);
        if (name === "first") return async (column?: string) => {
          const r = await this.sql(() => target.all(), 1, !read, data), row = r.results[0] ?? null;
          if (row && column !== undefined && !Object.hasOwn(row, column)) throw new Error("D1_ERROR: column not found");
          return column === undefined ? row : row?.[column] ?? null;
        };
        if (name === "all" || name === "run") return () => this.sql(() => target[name](), 1, !read, data);
        // raw/exec/session and future methods cannot bypass metadata or admission.
        return undefined;
      }});
      raw.set(result, { statement, read }); return result;
    };
    return new Proxy(db, { get: (target, name) => {
      if (name === "prepare") return (sql: string) => wrap(target.prepare(sql), /^\s*SELECT\s/i.test(sql));
      if (name === "batch") return (statements: D1PreparedStatement[]) => {
        const parts = statements.map(s => { const p = raw.get(s); if (!p) return rejected(); return p; });
        return this.sql(() => target.batch(parts.map(p => p.statement)), parts.length, parts.some(p => !p.read), data);
      };
      return undefined;
    }});
  }
  bucket(bucket: R2Bucket): R2Bucket {
    const multipart = (upload: R2MultipartUpload): R2MultipartUpload => new Proxy(upload, {
      get: (target, name) => {
        if (name === "key" || name === "uploadId") return target[name];
        if (["uploadPart", "complete", "abort"].includes(String(name))) return (...args: unknown[]) =>
          this.call(() => (target[name as keyof R2MultipartUpload] as Function).apply(target, args), true);
        return undefined;
      }
    });
    return new Proxy(bucket, { get: (target, name) => {
      if (name === "resumeMultipartUpload") return (key: string, id: string) => {
        if (this.closed) return rejected();
        return multipart(target.resumeMultipartUpload(key, id));
      };
      if (name === "createMultipartUpload") return (...args: unknown[]) =>
        this.call<R2MultipartUpload>(() => (target.createMultipartUpload as Function).apply(target, args), true).then(multipart);
      if (["put", "delete", "head", "get", "list"].includes(String(name))) return (...args: unknown[]) =>
        this.call(() => (target[name as keyof R2Bucket] as Function).apply(target, args), name === "put" || name === "delete");
      return undefined;
    }});
  }
  async finish(): Promise<void> {
    // A finish call closes all captured capabilities before inspecting effects.
    if (this.closed) return;
    this.closed = true;
    if (this.uncertain || this.pending !== 0) return;
    await this.budget.settle();
  }
  /** Provider writes such as Time Travel have no D1 result metadata. */
  async effect<T>(run: () => Promise<T>): Promise<T> {
    this.budget.unmetered();
    return this.call(run, true);
  }
  usage() { return this.budget.summary(); }
}
