-- SPDX-License-Identifier: GPL-3.0-or-later
-- EXTERNAL CONTROL ONLY. Apply transactionally while all registered fences are HELD.
-- Policy/initial DATA size must be provisioned explicitly after inspection.
INSERT INTO cloud_fence_assertion(slot,ok) VALUES(1,CASE WHEN
 NOT EXISTS(SELECT 1 FROM cloud_fence WHERE state!='HELD') AND
 NOT EXISTS(SELECT 1 FROM cloud_fence_tickets) AND
 NOT EXISTS(SELECT 1 FROM cloud_fence_actions WHERE state='RUNNING')
 THEN 1 ELSE 0 END);
DELETE FROM cloud_fence_assertion WHERE slot=1;
ALTER TABLE cloud_fence_tickets ADD COLUMN budget_reads INTEGER NOT NULL DEFAULT 0 CHECK(budget_reads>=0);
ALTER TABLE cloud_fence_tickets ADD COLUMN budget_writes INTEGER NOT NULL DEFAULT 0 CHECK(budget_writes>=0);
ALTER TABLE cloud_fence_tickets ADD COLUMN budget_growth INTEGER NOT NULL DEFAULT 0 CHECK(budget_growth>=0);
CREATE TABLE cloud_budget (
 resource_id TEXT PRIMARY KEY NOT NULL REFERENCES cloud_fence(resource_id),
 policy_version INTEGER NOT NULL CHECK(policy_version=1),
 state TEXT NOT NULL CHECK(state IN('ACTIVE','BLOCKED')),
 read_limit INTEGER NOT NULL CHECK(read_limit BETWEEN 1 AND 2000000),
 write_limit INTEGER NOT NULL CHECK(write_limit BETWEEN 1 AND 50000),
 size_limit INTEGER NOT NULL CHECK(size_limit BETWEEN 1 AND 250000000),
 statement_limit INTEGER NOT NULL CHECK(statement_limit BETWEEN 6 AND 32),
 day INTEGER NOT NULL CHECK(day>=0),
 spent_reads INTEGER NOT NULL DEFAULT 0 CHECK(typeof(spent_reads)='integer' AND spent_reads>=0),
 spent_writes INTEGER NOT NULL DEFAULT 0 CHECK(typeof(spent_writes)='integer' AND spent_writes>=0),
 held_reads INTEGER NOT NULL DEFAULT 0 CHECK(typeof(held_reads)='integer' AND held_reads>=0),
 held_writes INTEGER NOT NULL DEFAULT 0 CHECK(typeof(held_writes)='integer' AND held_writes>=0),
 held_growth INTEGER NOT NULL DEFAULT 0 CHECK(typeof(held_growth)='integer' AND held_growth>=0),
 data_size INTEGER NOT NULL CHECK(typeof(data_size)='integer' AND data_size>=0),
 last_settled_ticket TEXT,
 CHECK(spent_reads>=held_reads AND spent_writes>=held_writes)
);
-- Budget-only holds for authenticated operator work. Maintenance authority still
-- comes exclusively from cloud_fence_actions; these cannot grant public access.
CREATE TABLE cloud_budget_maintenance (
 ticket_id TEXT PRIMARY KEY NOT NULL,
 resource_id TEXT NOT NULL REFERENCES cloud_fence(resource_id),
 created_at INTEGER NOT NULL,
 budget_reads INTEGER NOT NULL CHECK(budget_reads>=0),
 budget_writes INTEGER NOT NULL CHECK(budget_writes>=0),
 budget_growth INTEGER NOT NULL CHECK(budget_growth>=0)
);
CREATE INDEX cloud_budget_maintenance_resource ON cloud_budget_maintenance(resource_id);
