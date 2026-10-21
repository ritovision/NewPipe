/* SPDX-License-Identifier: GPL-3.0-or-later */
import { admit } from "./fence.ts";
import { requestEstimate } from "./budget.ts";
import { housekeep } from "./housekeeping.ts";
type Environment = { DB: D1Database; CONTROL?: D1Database; FENCE_RESOURCE?: string;
  LIBRARY_ID: string; SERVICE_GENERATION: string; ENABLE_HOUSEKEEPING?: string };
/** A deployment must explicitly enable both its cron trigger and this flag.
 * The scheduled path has no R2 capability and shares ordinary admission/budgets.
 */
export async function scheduledHousekeeping(env: Environment) {
  if (env.ENABLE_HOUSEKEEPING !== "true") return { enabled: false };
  const ticket = await admit(env.CONTROL, { resource: env.FENCE_RESOURCE ?? "", library: env.LIBRARY_ID,
    generation: env.SERVICE_GENERATION }, requestEstimate("/maintenance/housekeep"));
  try { return { enabled: true, ...await housekeep(ticket.database(env.DB), env.LIBRARY_ID, env.SERVICE_GENERATION, { devices: 2 }) }; }
  finally { await ticket.finish(); }
}
