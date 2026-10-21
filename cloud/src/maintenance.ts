/* SPDX-License-Identifier: GPL-3.0-or-later */
// Used only by the authenticated operator Worker and tests, never the app router.
import { FenceError } from "./fence.ts";
export type FenceState = {
 resource_id: string; database_id: string; bucket_name: string; library_id: string;
 service_generation: string; provenance: string; state: string; maintenance_id: string | null;
 next_generation: string | null; revision: string; pending: number; running: number;
};
const deny = (code: string): never => { throw new FenceError(code); };
export async function fenceState(db: D1Database, resource: string): Promise<FenceState> {
 const row = await db.prepare("SELECT f.resource_id,f.database_id,f.bucket_name,f.library_id,f.service_generation,f.provenance,f.state,f.maintenance_id,f.next_generation,CAST(f.revision AS TEXT) AS revision,(SELECT COUNT(*) FROM cloud_fence_tickets t WHERE t.resource_id=f.resource_id) AS pending,(SELECT COUNT(*) FROM cloud_fence_actions a WHERE a.maintenance_id=f.maintenance_id AND a.state='RUNNING') AS running FROM cloud_fence f WHERE resource_id=?")
  .bind(resource).first<FenceState>();
 if (!row) return deny("FENCE_RESOURCE_UNREGISTERED"); return row;
}
export async function closeFence(db: D1Database, resource: string, maintenance: string, generation: string): Promise<FenceState> {
 if (!/^[0-9a-f-]{36}$/.test(maintenance) || !/^[0-9a-f-]{36}$/.test(generation)) return deny("INVALID_MAINTENANCE_ID");
 await db.prepare("UPDATE cloud_fence SET state='DRAINING',maintenance_id=?,next_generation=?,revision=revision+1 WHERE resource_id=? AND state='OPEN' AND service_generation!=? AND NOT EXISTS(SELECT 1 FROM cloud_fence_actions WHERE maintenance_id=?)")
  .bind(maintenance,generation,resource,generation,maintenance).run();
 const row = await fenceState(db,resource);
 if (row.maintenance_id!==maintenance || row.next_generation!==generation || row.state==="OPEN") return deny("MAINTENANCE_CONFLICT");
 // This atomic transition is the proof. Never substitute a quiet observation,
 // a process-local lock, expiry, deployment age, or the restored data database.
 await db.prepare("UPDATE cloud_fence SET state='HELD',revision=revision+1 WHERE resource_id=? AND maintenance_id=? AND state='DRAINING' AND NOT EXISTS(SELECT 1 FROM cloud_fence_tickets WHERE resource_id=?)")
  .bind(resource,maintenance,resource).run();
 return fenceState(db,resource);
}
export function requireHeld(f: FenceState): void {
 if (f.provenance!=="fresh-resources-v1" || f.state!=="HELD" || f.pending!==0 || f.running!==0 ||
   !f.maintenance_id || !f.next_generation) deny("MAINTENANCE_NOT_EXCLUSIVE");
}
export async function claimAction(db: D1Database, expected: FenceState, id: string, kind: "apply" | "restore" | "abort" | "reopen"): Promise<void> {
 requireHeld(expected);
 if (!/^[0-9a-f]{64}$/.test(id)) deny("INVALID_ACTION_ID");
 await db.batch([
  db.prepare("INSERT INTO cloud_fence_assertion(slot,ok) VALUES(1,(SELECT COUNT(*) FROM cloud_fence f WHERE resource_id=? AND maintenance_id=? AND revision=CAST(? AS INTEGER) AND state='HELD' AND NOT EXISTS(SELECT 1 FROM cloud_fence_tickets t WHERE t.resource_id=f.resource_id) AND NOT EXISTS(SELECT 1 FROM cloud_fence_actions a WHERE a.maintenance_id=f.maintenance_id AND a.state='RUNNING')))")
   .bind(expected.resource_id,expected.maintenance_id,expected.revision),
  db.prepare("INSERT INTO cloud_fence_actions(resource_id,maintenance_id,action_id,kind,state) VALUES(?,?,?,?,'RUNNING')")
   .bind(expected.resource_id,expected.maintenance_id,id,kind),
  db.prepare("UPDATE cloud_fence SET revision=revision+1 WHERE resource_id=?").bind(expected.resource_id),
  db.prepare("DELETE FROM cloud_fence_assertion WHERE slot=1")
 ]);
}
export async function finishAction(db: D1Database, maintenance: string, id: string, result: unknown): Promise<void> {
 await db.prepare("UPDATE cloud_fence_actions SET state='DONE',result=? WHERE maintenance_id=? AND action_id=? AND state='RUNNING'")
  .bind(JSON.stringify(result),maintenance,id).run();
}
