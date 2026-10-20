/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * A ticket is durable before any data effect starts. It is removed only once every
 * effect has a definite response. No timeout, lease expiry, or restarted process
 * may remove a ticket: an unknown write can still arrive at its storage service.
 */
export class FenceError extends Error {
  readonly code: string;
  constructor(code: string) { super(code); this.code = code; }
}
type Scope = { resource: string; library: string; generation: string };
const rejected = (): never => { throw new FenceError("MAINTENANCE_REQUIRED"); };
export async function admit(control: D1Database | undefined, scope: Scope): Promise<RequestFence> {
  if (!control || !scope.resource) return rejected();
  const id = crypto.randomUUID();
  const r = await control.prepare("INSERT INTO cloud_fence_tickets(ticket_id,resource_id,created_at) SELECT ?,resource_id,CAST(unixepoch('subsec')*1000 AS INTEGER) FROM cloud_fence WHERE resource_id=? AND library_id=? AND service_generation=? AND state='OPEN' AND provenance='fresh-resources-v1' RETURNING ticket_id")
    .bind(id, scope.resource, scope.library, scope.generation).all<{ ticket_id: string }>();
  if (r.results.length !== 1 || r.results[0]?.ticket_id !== id) return rejected();
  return new RequestFence(control, id);
}
export class RequestFence {
  private closed = false;
  private uncertain = false;
  private pending = 0;
  private readonly control: D1Database;
  private readonly id: string;
  constructor(control: D1Database, id: string) { this.control = control; this.id = id; }
  private async call<T>(run: () => Promise<T>, mutation: boolean, transactional = false): Promise<T> {
    if (this.closed) return rejected();
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
  database(db: D1Database): D1Database {
    const raw = new WeakMap<D1PreparedStatement, { statement: D1PreparedStatement; read: boolean }>();
    const wrap = (statement: D1PreparedStatement, read: boolean): D1PreparedStatement => {
      const result = new Proxy(statement, { get: (target, name) => {
        if (name === "bind") return (...args: unknown[]) => wrap(target.bind(...args), read);
        if (["all", "first", "raw", "run"].includes(String(name))) return (...args: unknown[]) =>
          this.call(() => (target[name as keyof D1PreparedStatement] as Function).apply(target, args), !read, true);
        // Do not expose unwrapped capabilities when D1 grows a new method.
        return undefined;
      }});
      raw.set(result, { statement, read }); return result;
    };
    return new Proxy(db, { get: (target, name) => {
      if (name === "prepare") return (sql: string) => wrap(target.prepare(sql), /^\s*SELECT\s/i.test(sql));
      if (name === "batch") return (statements: D1PreparedStatement[]) => {
        const parts = statements.map(s => { const p = raw.get(s); if (!p) return rejected(); return p; });
        return this.call(() => target.batch(parts.map(p => p.statement)), parts.some(p => !p.read), true);
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
    // Disable captured capabilities before checking pending calls. Detached work
    // either remains covered by its ticket or cannot start another storage call.
    this.closed = true;
    if (this.uncertain || this.pending !== 0) return;
    try {
      await this.control.prepare("DELETE FROM cloud_fence_tickets WHERE ticket_id=?").bind(this.id).run();
    } catch { /* Safe leak: a missing acknowledgement never authorizes recovery. */ }
  }
}
