-- SPDX-License-Identifier: GPL-3.0-or-later
-- Committed with the rebuild. The external fence prevents rewinding this receipt
-- while a rebuild's outcome remains unresolved.
CREATE TABLE cloud_operator_receipts (
 plan_digest TEXT PRIMARY KEY NOT NULL,
 maintenance_id TEXT NOT NULL,
 library_id TEXT NOT NULL,
 service_generation TEXT NOT NULL,
 completed_at INTEGER NOT NULL
);
