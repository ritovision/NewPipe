-- SPDX-License-Identifier: GPL-3.0-or-later
-- No existing device receives replacement authority automatically.
CREATE TABLE cloud_replace_grants (
 library_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 service_generation TEXT NOT NULL,
 auth_generation INTEGER NOT NULL,
 can_replace INTEGER NOT NULL CHECK(can_replace IN(0,1)),
 PRIMARY KEY(library_id,device_id),
 FOREIGN KEY(library_id,device_id) REFERENCES cloud_devices(library_id,device_id)
);
CREATE TABLE cloud_replace_approvals (
 library_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 approval_id TEXT NOT NULL,
 header TEXT NOT NULL,
 next_epoch TEXT NOT NULL,
 retry_archive_id TEXT,
 retry_attempt_id TEXT,
 expires_at INTEGER NOT NULL,
 PRIMARY KEY(library_id,device_id),
 FOREIGN KEY(library_id,device_id) REFERENCES cloud_devices(library_id,device_id)
);
CREATE TABLE cloud_replace_attempts (
 library_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 attempt_id TEXT NOT NULL,
 header TEXT NOT NULL,
 next_epoch TEXT NOT NULL,
 recovery_sequence INTEGER NOT NULL CHECK(typeof(recovery_sequence)='integer' AND recovery_sequence>0),
 archive_id TEXT NOT NULL,
 slot INTEGER NOT NULL CHECK(slot BETWEEN 0 AND 2),
 retry_archive_id TEXT,
 expires_at INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN('ACTIVE','STALE','DONE','CANCELED')),
 PRIMARY KEY(library_id,device_id),
 UNIQUE(library_id,attempt_id),
 FOREIGN KEY(library_id,device_id) REFERENCES cloud_devices(library_id,device_id)
);
CREATE UNIQUE INDEX cloud_one_replacement ON cloud_replace_attempts(library_id)
 WHERE state IN('ACTIVE','STALE');
