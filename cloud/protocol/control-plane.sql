-- SPDX-License-Identifier: GPL-3.0-or-later
-- Local M1 admission proof. Not a production catalog schema or deployment migration.
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
