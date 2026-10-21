-- SPDX-License-Identifier: GPL-3.0-or-later
-- Retention begins when housekeeping first observes an eligible sequence prefix.
-- No timestamp is guessed for old receipts and no publication trigger is required.
CREATE TABLE cloud_receipt_retention (
 library_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 through_sequence INTEGER NOT NULL CHECK(typeof(through_sequence)='integer' AND through_sequence>=0),
 first_seen INTEGER NOT NULL CHECK(typeof(first_seen)='integer' AND first_seen>=0),
 PRIMARY KEY(library_id,device_id),
 FOREIGN KEY(library_id,device_id) REFERENCES cloud_devices(library_id,device_id)
);
CREATE TABLE cloud_housekeeping_cursor (
 library_id TEXT PRIMARY KEY NOT NULL REFERENCES cloud_head(library_id),
 device_cursor TEXT NOT NULL
);
CREATE INDEX cloud_upload_active ON cloud_uploads(library_id,expires_at)
 WHERE status IN('UPLOADING','SEALED');
