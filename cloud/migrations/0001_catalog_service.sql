-- SPDX-License-Identifier: GPL-3.0-or-later
-- Catalog service control plane. Apply only to the dedicated new cloud-sync database.
CREATE TABLE cloud_head (
  library_id TEXT PRIMARY KEY NOT NULL,
  epoch TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK(typeof(revision) = 'integer' AND revision >= 0),
  head_counter INTEGER NOT NULL CHECK(typeof(head_counter) = 'integer' AND head_counter >= 0),
  fingerprint TEXT NOT NULL
);
CREATE TABLE cloud_devices (
  library_id TEXT NOT NULL REFERENCES cloud_head(library_id),
  device_id TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  service_generation TEXT NOT NULL,
  auth_generation INTEGER NOT NULL CHECK(typeof(auth_generation) = 'integer' AND auth_generation >= 0),
  revoked INTEGER NOT NULL CHECK(revoked IN (0,1)),
  can_write INTEGER NOT NULL CHECK(can_write IN (0,1)),
  resolved_sequence INTEGER NOT NULL CHECK(typeof(resolved_sequence) = 'integer' AND resolved_sequence >= 0),
  PRIMARY KEY(library_id, device_id)
);
CREATE TABLE cloud_receipts (
  library_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  sequence INTEGER NOT NULL CHECK(typeof(sequence) = 'integer' AND sequence > 0),
  digest TEXT NOT NULL,
  outcome TEXT NOT NULL CHECK(outcome IN ('COMMITTED','RETIRED')),
  epoch TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK(typeof(revision) = 'integer' AND revision >= 0),
  head_counter INTEGER NOT NULL CHECK(typeof(head_counter) = 'integer' AND head_counter >= 0),
  fingerprint TEXT NOT NULL,
  PRIMARY KEY(library_id, device_id, sequence),
  FOREIGN KEY(library_id,device_id) REFERENCES cloud_devices(library_id,device_id)
);
CREATE TABLE cloud_commit_assertion (
  slot INTEGER PRIMARY KEY CHECK(slot = 1),
  ok INTEGER NOT NULL CHECK(ok = 1)
);

CREATE UNIQUE INDEX cloud_device_token ON cloud_devices(token_hash);
CREATE TABLE cloud_catalog_info (
 library_id TEXT PRIMARY KEY NOT NULL REFERENCES cloud_head(library_id),
 initialized INTEGER NOT NULL DEFAULT 0 CHECK(initialized IN(0,1)),
 page_count INTEGER NOT NULL DEFAULT 0 CHECK(page_count>=0),
 payload_bytes INTEGER NOT NULL DEFAULT 0 CHECK(payload_bytes>=0),
 manifest TEXT NOT NULL
);
CREATE TABLE cloud_catalog_pages (
 library_id TEXT NOT NULL REFERENCES cloud_head(library_id),
 page_id TEXT NOT NULL,
 body TEXT NOT NULL,
 PRIMARY KEY(library_id,page_id)
);
CREATE TABLE cloud_uploads (
 library_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 sequence INTEGER NOT NULL CHECK(typeof(sequence)='integer' AND sequence>0),
 digest TEXT NOT NULL,
 header TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN('UPLOADING','SEALED','COMMITTED','RETIRED')),
 expires_at INTEGER NOT NULL,
 expected_chunks INTEGER NOT NULL CHECK(expected_chunks>0),
 expected_changes INTEGER NOT NULL CHECK(expected_changes>0),
 expected_bytes INTEGER NOT NULL CHECK(expected_bytes>=0),
 received_chunks INTEGER NOT NULL DEFAULT 0 CHECK(received_chunks>=0),
 received_changes INTEGER NOT NULL DEFAULT 0 CHECK(received_changes>=0),
 received_bytes INTEGER NOT NULL DEFAULT 0 CHECK(received_bytes>=0),
 verified_pages INTEGER,
 verified_bytes INTEGER,
 verified_manifest TEXT,
 PRIMARY KEY(library_id,device_id),
 UNIQUE(library_id,device_id,sequence),
 FOREIGN KEY(library_id,device_id) REFERENCES cloud_devices(library_id,device_id)
);
CREATE TABLE cloud_upload_chunks (
 library_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 sequence INTEGER NOT NULL,
 chunk_index INTEGER NOT NULL CHECK(chunk_index>=0),
 digest TEXT NOT NULL,
 PRIMARY KEY(library_id,device_id,sequence,chunk_index),
 FOREIGN KEY(library_id,device_id,sequence) REFERENCES cloud_uploads(library_id,device_id,sequence) ON DELETE CASCADE
);
CREATE TABLE cloud_upload_changes (
 library_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 sequence INTEGER NOT NULL,
 page_id TEXT NOT NULL,
 body TEXT,
 PRIMARY KEY(library_id,device_id,sequence,page_id),
 FOREIGN KEY(library_id,device_id,sequence) REFERENCES cloud_uploads(library_id,device_id,sequence) ON DELETE CASCADE
);
