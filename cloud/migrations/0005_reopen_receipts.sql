-- SPDX-License-Identifier: GPL-3.0-or-later
-- The external RUNNING action fences this receipt until OPEN commits externally.
CREATE TABLE cloud_reopen_receipts (
 action_id TEXT PRIMARY KEY NOT NULL,
 maintenance_id TEXT NOT NULL,
 library_id TEXT NOT NULL,
 service_generation TEXT NOT NULL,
 recovery_digest TEXT NOT NULL,
 devices TEXT NOT NULL,
 completed_at INTEGER NOT NULL
);
