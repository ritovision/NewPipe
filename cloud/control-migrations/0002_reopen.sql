-- SPDX-License-Identifier: GPL-3.0-or-later
-- Apply the entire file in one transaction while existing resources are HELD.
-- Preserve action ordering and resource identity after a maintenance cycle closes.
INSERT INTO cloud_fence_assertion(slot,ok) VALUES(1,CASE WHEN
 NOT EXISTS(SELECT 1 FROM cloud_fence WHERE state!='HELD') AND
 NOT EXISTS(SELECT 1 FROM cloud_fence_tickets) AND
 NOT EXISTS(SELECT 1 FROM cloud_fence_actions WHERE state='RUNNING')
 THEN 1 ELSE 0 END);
DELETE FROM cloud_fence_assertion WHERE slot=1;
CREATE TABLE cloud_fence_actions_v2 (
 resource_id TEXT NOT NULL REFERENCES cloud_fence(resource_id),
 maintenance_id TEXT NOT NULL,
 action_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN('restore','apply','abort','reopen')),
 state TEXT NOT NULL CHECK(state IN('RUNNING','DONE')),
 result TEXT,
 PRIMARY KEY(maintenance_id,action_id)
);
INSERT INTO cloud_fence_actions_v2(rowid,resource_id,maintenance_id,action_id,kind,state,result)
 SELECT a.rowid,f.resource_id,a.maintenance_id,a.action_id,a.kind,a.state,a.result
 FROM cloud_fence_actions a JOIN cloud_fence f ON f.maintenance_id=a.maintenance_id;
INSERT INTO cloud_fence_assertion(slot,ok) VALUES(1,CASE WHEN (SELECT COUNT(*) FROM cloud_fence_actions_v2)=(SELECT COUNT(*) FROM cloud_fence_actions) THEN 1 ELSE 0 END);
DROP TABLE cloud_fence_actions;
ALTER TABLE cloud_fence_actions_v2 RENAME TO cloud_fence_actions;
CREATE UNIQUE INDEX cloud_fence_one_action ON cloud_fence_actions(resource_id) WHERE state='RUNNING';
CREATE INDEX cloud_fence_history ON cloud_fence_actions(resource_id,maintenance_id);
DELETE FROM cloud_fence_assertion WHERE slot=1;
