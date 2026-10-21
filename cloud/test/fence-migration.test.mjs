// SPDX-License-Identifier: GPL-3.0-or-later
import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
let mf,db;
const statements=async file=>(await readFile(new URL('../control-migrations/'+file,import.meta.url),'utf8')).replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
const upgrade=async()=>db.batch((await statements('0002_reopen.sql')).map(s=>db.prepare(s)));
const seed=()=>db.prepare("INSERT INTO cloud_fence(resource_id,database_id,bucket_name,library_id,service_generation,provenance,state,maintenance_id,next_generation,created_at) VALUES('resource','data','bucket','library','old','fresh-resources-v1','HELD','maintenance','new',0)").run();
const legacy=async()=>{
 for(const t of ['cloud_fence_actions_v2','cloud_fence_actions','cloud_fence_tickets','cloud_fence','cloud_fence_assertion'])await db.prepare('DROP TABLE IF EXISTS '+t).run();
 await db.batch((await statements('0001_fence.sql')).map(s=>db.prepare(s)));
};
before(async()=>{
 mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules:true,script:'export default {fetch(){return new Response("migration tests")}}',compatibilityDate:'2026-08-25',d1Databases:{DB:'control-upgrade'}}));
 await mf.ready;db=await mf.getD1Database('DB');
});
after(async()=>{await mf?.dispose();});
beforeEach(legacy);
test('control upgrade preserves history ordering, results and per-resource exclusivity',async()=>{
 await seed();
 await db.prepare("INSERT INTO cloud_fence_actions(rowid,maintenance_id,action_id,kind,state,result) VALUES(7,'maintenance','older','restore','DONE','{}'),(19,'maintenance','newer','apply','DONE','{\"committed\":true}')").run();
 await upgrade();
 const rows=(await db.prepare('SELECT rowid,resource_id,action_id,result FROM cloud_fence_actions ORDER BY rowid').all()).results;
 assert.deepEqual(rows,[{rowid:7,resource_id:'resource',action_id:'older',result:'{}'},{rowid:19,resource_id:'resource',action_id:'newer',result:'{"committed":true}'}]);
 await db.prepare("INSERT INTO cloud_fence_actions VALUES('resource','maintenance','reopen','reopen','RUNNING',NULL)").run();
 await assert.rejects(db.prepare("INSERT INTO cloud_fence_actions VALUES('resource','another-cycle','conflict','apply','RUNNING',NULL)").run());
});
test('control upgrade rolls back for an open gate, pending request, unknown action or unmapped history',async()=>{
 for(const mutation of [
  "UPDATE cloud_fence SET state='OPEN',maintenance_id=NULL,next_generation=NULL",
  "INSERT INTO cloud_fence_tickets VALUES('request','resource',0)",
  "INSERT INTO cloud_fence_actions VALUES('maintenance','unknown','apply','RUNNING',NULL)",
  "INSERT INTO cloud_fence_actions VALUES('unmapped-cycle','old','apply','DONE','{}')"
 ]){
  await legacy();await seed();await db.prepare(mutation).run();
  const before=(await db.prepare('SELECT rowid,* FROM cloud_fence_actions').all()).results;
  await assert.rejects(upgrade(),/CHECK constraint failed/);
  assert.deepEqual((await db.prepare('SELECT rowid,* FROM cloud_fence_actions').all()).results,before);
  assert.equal((await db.prepare("SELECT COUNT(*) n FROM sqlite_master WHERE name='cloud_fence_actions_v2'").first()).n,0);
  assert.equal((await db.prepare("SELECT COUNT(*) n FROM pragma_table_info('cloud_fence_actions') WHERE name='resource_id'").first()).n,0);
 }
});
