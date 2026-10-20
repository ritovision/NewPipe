-- SPDX-License-Identifier: GPL-3.0-or-later
-- EXTERNAL CONTROL DATABASE ONLY. Never apply to or restore alongside the data DB.
CREATE TABLE cloud_fence (
 resource_id TEXT PRIMARY KEY NOT NULL,
 database_id TEXT UNIQUE NOT NULL,
 bucket_name TEXT UNIQUE NOT NULL,
 library_id TEXT NOT NULL,
 service_generation TEXT NOT NULL,
 provenance TEXT NOT NULL CHECK(provenance='fresh-resources-v1'),
 state TEXT NOT NULL CHECK(state IN('OPEN','DRAINING','HELD')),
 maintenance_id TEXT UNIQUE,
 next_generation TEXT,
 created_at INTEGER NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0),
 CHECK((state='OPEN' AND maintenance_id IS NULL AND next_generation IS NULL)
    OR (state!='OPEN' AND maintenance_id IS NOT NULL AND next_generation IS NOT NULL))
);
CREATE TABLE cloud_fence_tickets (
 ticket_id TEXT PRIMARY KEY NOT NULL,
 resource_id TEXT NOT NULL REFERENCES cloud_fence(resource_id),
 created_at INTEGER NOT NULL
);
CREATE INDEX cloud_fence_pending ON cloud_fence_tickets(resource_id);
-- Operator effects are also non-expiring. Unknown results never release maintenance.
CREATE TABLE cloud_fence_actions (
 maintenance_id TEXT NOT NULL,
 action_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN('restore','apply','abort')),
 state TEXT NOT NULL CHECK(state IN('RUNNING','DONE')),
 result TEXT,
 PRIMARY KEY(maintenance_id,action_id)
);
CREATE UNIQUE INDEX cloud_fence_one_action ON cloud_fence_actions(maintenance_id) WHERE state='RUNNING';
CREATE TABLE cloud_fence_assertion (
 slot INTEGER PRIMARY KEY CHECK(slot=1),
 ok INTEGER NOT NULL CHECK(ok=1)
);
