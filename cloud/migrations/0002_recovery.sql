-- SPDX-License-Identifier: GPL-3.0-or-later
-- Provision control explicitly after checking the private bucket, including multipart uploads.
-- Never automatically recreate a missing/rewound index from an HTTP request.
CREATE TABLE cloud_backup_control (
 library_id TEXT PRIMARY KEY NOT NULL REFERENCES cloud_head(library_id),
 service_generation TEXT NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0 CHECK(typeof(revision)='integer' AND revision>=0),
 next_ordinal INTEGER NOT NULL DEFAULT 1 CHECK(typeof(next_ordinal)='integer' AND next_ordinal>0),
 last_replace_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE cloud_backup_approvals (
 library_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 approval_id TEXT NOT NULL,
 manifest TEXT NOT NULL,
 digest TEXT NOT NULL,
 revision TEXT NOT NULL,
 evict_id TEXT,
 expires_at INTEGER NOT NULL,
 PRIMARY KEY(library_id,device_id),
 FOREIGN KEY(library_id,device_id) REFERENCES cloud_devices(library_id,device_id)
);
CREATE TABLE cloud_backup_operations (
 library_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 sequence INTEGER NOT NULL CHECK(typeof(sequence)='integer' AND sequence>0),
 approval_id TEXT NOT NULL,
 archive_id TEXT NOT NULL,
 slot INTEGER NOT NULL CHECK(slot BETWEEN 0 AND 2),
 manifest TEXT NOT NULL,
 digest TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('ROTATING','RESERVED','CREATING','UPLOADING','COMPLETING','VERIFYING','DONE','ABORTING','CANCELED','QUARANTINED')),
 evict_id TEXT,
 created_at INTEGER NOT NULL,
 error TEXT,
 PRIMARY KEY(library_id,device_id),
 UNIQUE(library_id,archive_id),
 FOREIGN KEY(library_id,device_id) REFERENCES cloud_devices(library_id,device_id)
);
CREATE UNIQUE INDEX cloud_one_backup_operation ON cloud_backup_operations(library_id)
 WHERE state NOT IN('DONE','CANCELED');
CREATE TABLE cloud_backup_slots (
 library_id TEXT NOT NULL REFERENCES cloud_backup_control(library_id),
 slot INTEGER NOT NULL CHECK(slot BETWEEN 0 AND 2),
 archive_id TEXT NOT NULL,
 object_key TEXT NOT NULL UNIQUE,
 owner_device TEXT NOT NULL,
 source_service TEXT NOT NULL,
 abort_fenced INTEGER NOT NULL DEFAULT 0 CHECK(abort_fenced IN(0,1)),
 manifest TEXT NOT NULL,
 digest TEXT NOT NULL,
 ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>0),
 created_at INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN('RESERVED','CREATING','UPLOADING','COMPLETING','VERIFYING','READY','DELETING','ABORTING','QUARANTINED')),
 upload_id TEXT,
 object_etag TEXT,
 object_version TEXT,
 next_part INTEGER NOT NULL DEFAULT 1 CHECK(next_part BETWEEN 1 AND 33),
 parts_json TEXT NOT NULL DEFAULT '[]',
 busy_token TEXT,
 busy_until INTEGER NOT NULL DEFAULT 0,
 verify_part INTEGER NOT NULL DEFAULT 1 CHECK(verify_part BETWEEN 1 AND 33),
 verify_json TEXT,
 pin_id TEXT,
 pin_device TEXT,
 pin_until INTEGER NOT NULL DEFAULT 0,
 error TEXT,
 PRIMARY KEY(library_id,slot),
 UNIQUE(library_id,archive_id)
);
