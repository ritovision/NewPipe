// SPDX-License-Identifier: GPL-3.0-or-later
// Synthetic local fixtures only. Never import this reset helper in a deployment.
export async function seedBudget(control, resource) {
 await control.prepare("DELETE FROM cloud_fence_tickets WHERE resource_id=?").bind(resource).run();
 await control.prepare("DELETE FROM cloud_budget_maintenance WHERE resource_id=?").bind(resource).run();
 await control.prepare("DELETE FROM cloud_budget WHERE resource_id=?").bind(resource).run();
 await control.prepare(`INSERT INTO cloud_budget(resource_id,policy_version,state,read_limit,write_limit,size_limit,statement_limit,day,data_size)
 VALUES(?,1,'ACTIVE',2000000,50000,250000000,32,CAST(unixepoch()/86400 AS INTEGER),0)`).bind(resource).run();
}
