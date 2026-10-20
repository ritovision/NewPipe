// SPDX-License-Identifier: GPL-3.0-or-later
import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {stripTypeScriptTypes} from 'node:module';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
const H=s=>createHash('sha256').update(s).digest('hex');
const base={serviceGeneration:'service-a',library:'library-a',epoch:'epoch-a',revision:'10',headCounter:'10',fingerprint:H('before')};
const context={library:'library-a',device:'device-a',tokenHash:H('test-token-a'),authGeneration:'1',serviceGeneration:'service-a'};
const contextB={...context,device:'device-b',tokenHash:H('test-token-b')};
const operation={sequence:'1',digest:H('operation-a'),before:base,nextFingerprint:H('after')};
let mf;
const sqlStatement=(sql,args=[])=>({sql,args});
const insert=(name='one')=>sqlStatement('INSERT INTO proof_values(k,v) VALUES(?,?)',[name,'valid']);
async function rpc(data){const r=await mf.dispatchFetch('http://cloud-proof.local/',{method:'POST',body:JSON.stringify(data),headers:{'Content-Type':'application/json'}});return r.json();}
async function sql(...statements){const r=await rpc({kind:'sql',statements:statements.map(s=>typeof s==='string'?sqlStatement(s):s)});assert.ok(Array.isArray(r),JSON.stringify(r));return r;}
const commit=(c=context,o=operation,mutations=[insert()],inject)=>rpc({kind:'commit',context:c,operation:o,mutations,inject});
const retire=(c=context,o=operation)=>rpc({kind:'retire',context:c,operation:o});
async function count(table){return (await sql(`SELECT COUNT(*) AS n FROM ${table}`))[0].results[0].n;}
async function state(){const r=await sql('SELECT epoch,CAST(revision AS TEXT) AS revision,CAST(head_counter AS TEXT) AS headCounter,fingerprint FROM cloud_head', 'SELECT CAST(resolved_sequence AS TEXT) AS seq FROM cloud_devices WHERE device_id=\'device-a\'');return {head:r[0].results[0],seq:r[1].results[0].seq};}
function error(r,code){assert.equal(r.ok,false,JSON.stringify(r));assert.equal(r.code,code);}
before(async()=>{
 const modules=[];
 for(const file of ['test/runtime-worker.mjs','src/admission.ts','src/protocol.ts']){
  const source=await readFile(new URL('../'+file,import.meta.url),'utf8');
  modules.push({type:'ESModule',path:resolve(file),contents:file.endsWith('.ts')?stripTypeScriptTypes(source,{mode:'strip'}):source});
 }
 mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules,compatibilityDate:'2026-08-25',d1Databases:{DB:'cloud-m1-local-admission'}}));await mf.ready;
 const schema=(await readFile(new URL('../protocol/control-plane.sql',import.meta.url),'utf8')).replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
 await sql(...schema,"CREATE TABLE proof_values(k TEXT PRIMARY KEY,v TEXT NOT NULL CHECK(v!='bad'))");
});
after(async()=>{if(mf)await mf.dispose();});
beforeEach(async()=>{
 await sql('DELETE FROM proof_values','DELETE FROM cloud_commit_assertion','DELETE FROM cloud_receipts','DELETE FROM cloud_devices','DELETE FROM cloud_head');
 await sql(sqlStatement('INSERT INTO cloud_head VALUES(?,?,CAST(? AS INTEGER),CAST(? AS INTEGER),?)',[base.library,base.epoch,base.revision,base.headCounter,base.fingerprint]),
  ...[context,contextB].map(c=>sqlStatement('INSERT INTO cloud_devices VALUES(?,?,?,?,CAST(? AS INTEGER),0,1,0)',[c.library,c.device,c.tokenHash,c.serviceGeneration,c.authGeneration])));
});
test('guarded domain changes, receipt and head commit together',async()=>{
 const r=await commit();assert.equal(r.ok,true,JSON.stringify(r));assert.equal(r.result.outcome,'COMMITTED');assert.equal(r.result.head.revision,'11');
 assert.equal(await count('proof_values'),1);assert.equal(await count('cloud_receipts'),1);assert.equal(await count('cloud_commit_assertion'),0);
 assert.equal((await state()).seq,'1');
});
test('two devices against one base cannot both commit',async()=>{
 const results=await Promise.all([commit(),commit(contextB,{...operation,digest:H('b')},[insert('two')])]);
 assert.equal(results.filter(r=>r.ok).length,1,JSON.stringify(results));assert.equal(await count('proof_values'),1);assert.equal(await count('cloud_receipts'),1);
});
test('concurrent duplicate returns original receipt rather than false conflict',async()=>{
 const results=await Promise.all(Array.from({length:5},()=>commit()));
 assert.ok(results.every(r=>r.ok),JSON.stringify(results));results.forEach(r=>assert.deepEqual(r.result,results[0].result));assert.equal(await count('proof_values'),1);
});
test('same sequence with changed digest cannot replay',async()=>{
 assert.equal((await commit()).ok,true);error(await commit(context,{...operation,digest:H('other')}),'REPLAY_MISMATCH');assert.equal(await count('proof_values'),1);
});
for(const [label,before] of [['revision',{...base,revision:'9'}],['counter',{...base,headCounter:'9'}],['epoch',{...base,epoch:'old'}],['fingerprint',{...base,fingerprint:H('wrong')}]] )
 test(`stale ${label} aborts all effects`,async()=>{error(await commit(context,{...operation,before}),'PRECONDITION_FAILED');assert.equal(await count('proof_values'),0);assert.equal((await state()).seq,'0');assert.equal(await count('cloud_commit_assertion'),0);});
for(const [label,c]of [['missing device',{...context,device:'missing'}],['wrong token',{...context,tokenHash:H('wrong')}],['wrong auth generation',{...context,authGeneration:'2'}],['wrong service generation',{...context,serviceGeneration:'old'}]])
 test(`reject ${label}`,async()=>{const before={...base,serviceGeneration:c.serviceGeneration};error(await commit(c,{...operation,before}),'AUTH_REJECTED');assert.equal(await count('proof_values'),0);});
test('revoked credential cannot retrieve a previous receipt',async()=>{await commit();await sql("UPDATE cloud_devices SET revoked=1 WHERE device_id='device-a'");error(await commit(),'AUTH_REJECTED');});
test('missing write grant rejects before writes',async()=>{await sql("UPDATE cloud_devices SET can_write=0 WHERE device_id='device-a'");error(await commit(),'AUTH_REJECTED');});
test('revoke after preflight is caught INSIDE the batch',async()=>{error(await commit(context,operation,[insert()],'revokeBeforeBatch'),'AUTH_REJECTED');assert.equal(await count('proof_values'),0);assert.equal(await count('cloud_receipts'),0);});
test('missing head COUNT and NULL assertion fail closed',async()=>{
 for(const stmt of ["INSERT INTO cloud_commit_assertion VALUES(1,(SELECT COUNT(*) FROM cloud_head WHERE library_id='missing'))",'INSERT INTO cloud_commit_assertion VALUES(1,NULL)']){
  const r=await rpc({kind:'sql',statements:[sqlStatement(stmt),insert()]});assert.equal(r.ok,false);assert.match(r.code,/constraint/i);
  assert.equal(await count('proof_values'),0);assert.equal(await count('cloud_commit_assertion'),0);
 }
});
test('NULL auth generation cannot be stored',async()=>{const r=await rpc({kind:'sql',statements:[sqlStatement("UPDATE cloud_devices SET auth_generation=NULL WHERE device_id='device-a'")]});assert.equal(r.ok,false);assert.match(r.code,/NOT NULL/);});
test('late domain CHECK failure rolls back earlier inserts and guard',async()=>{
 error(await commit(context,operation,[insert(),sqlStatement("INSERT INTO proof_values VALUES('two','bad')")]),'PRECONDITION_FAILED');
 assert.equal(await count('proof_values'),0);assert.equal(await count('cloud_receipts'),0);assert.equal(await count('cloud_commit_assertion'),0);assert.equal((await state()).seq,'0');assert.equal((await state()).head.revision,'10');
});
test('commit followed by lost reply resolves exact receipt',async()=>{const r=await commit(context,operation,[insert()],'lostAfterCommit');assert.equal(r.ok,true,JSON.stringify(r));assert.equal(await count('proof_values'),1);});
test('unknown transport outcome is not mislabeled a definite conflict',async()=>{error(await commit(context,operation,[insert()],'networkBeforeCommit'),'OUTCOME_UNKNOWN');assert.equal((await state()).seq,'0');assert.equal(await count('proof_values'),0);});
test('retirement permanently fences the abandoned delayed commit',async()=>{
 const r=await retire();assert.equal(r.ok,true,JSON.stringify(r));assert.equal(r.result.outcome,'RETIRED');
 error(await commit(),'OPERATION_RETIRED');assert.equal(await count('proof_values'),0);assert.equal((await state()).head.revision,'10');assert.equal((await state()).seq,'1');
 assert.deepEqual((await retire()).result,r.result);
 const next={...operation,sequence:'2',digest:H('two')};assert.equal((await commit(context,next)).ok,true);
});
test('resolve-or-retire returns already committed outcome',async()=>{const a=await commit();const b=await retire();assert.deepEqual(a.result,b.result);assert.equal(await count('proof_values'),1);});
test('commit vs retirement race has one durable outcome',async()=>{
 const results=await Promise.all([commit(),retire()]);const r=await retire();assert.equal(r.ok,true,JSON.stringify(results));
 assert.equal(await count('cloud_receipts'),1);assert.equal((await state()).seq,'1');
 assert.equal(await count('proof_values'),r.result.outcome==='COMMITTED'?1:0);
});
test('pruned receipt never permits a previously accepted sequence again',async()=>{await commit();await sql('DELETE FROM cloud_receipts');error(await commit(),'RESULT_EXPIRED');error(await retire(),'RESULT_EXPIRED');assert.equal(await count('proof_values'),1);});
test('sequence gaps never masquerade as retired work',async()=>{error(await commit(context,{...operation,sequence:'2'}),'SEQUENCE_GAP');error(await retire(context,{...operation,sequence:'2'}),'SEQUENCE_GAP');assert.equal((await state()).seq,'0');});
test('statement budget rejects before changing any state',async()=>{error(await commit(context,operation,Array.from({length:17},(_,i)=>insert(String(i)))),'STATEMENT_BUDGET_EXCEEDED');assert.equal(await count('proof_values'),0);});
test('64-bit values above JS safe range remain exact',async()=>{
 const n='9007199254740992';await sql(sqlStatement('UPDATE cloud_head SET revision=CAST(? AS INTEGER),head_counter=CAST(? AS INTEGER)',[n,n]),sqlStatement("UPDATE cloud_devices SET resolved_sequence=CAST(? AS INTEGER) WHERE device_id='device-a'",[n]));
 const o={...operation,sequence:'9007199254740993',before:{...base,revision:n,headCounter:n}};const r=await commit(context,o);
 assert.equal(r.ok,true,JSON.stringify(r));assert.equal(r.result.sequence,'9007199254740993');assert.equal(r.result.head.headCounter,'9007199254740993');assert.equal((await state()).seq,'9007199254740993');
});
test('counter exhaustion cannot become floating point',async()=>{
 const n='9223372036854775807';await sql(sqlStatement('UPDATE cloud_head SET revision=CAST(? AS INTEGER),head_counter=CAST(? AS INTEGER)',[n,n]));
 error(await commit(context,{...operation,before:{...base,revision:n,headCounter:n}}),'PRECONDITION_FAILED');assert.equal(await count('proof_values'),0);assert.equal((await state()).head.revision,n);
});

// Replacement transaction primitive only: end-to-end approval/R2 proof is a separate service gate.
const replacementEpoch='9239176e-0bde-4c82-950c-5a4bb3c825bb';
const replacement=(c=context,o=operation,options={})=>rpc({kind:'replace',context:c,operation:o,nextEpoch:replacementEpoch,
 assertions:[sqlStatement('UPDATE cloud_commit_assertion SET ok=1 WHERE slot=1')],mutations:[insert()],...options});
test('replacement commits new epoch and revision one while the global counter advances',async()=>{
 const r=await replacement();assert.equal(r.ok,true,JSON.stringify(r));
 assert.deepEqual(r.result.head,{...base,epoch:replacementEpoch,revision:'1',headCounter:'11',fingerprint:operation.nextFingerprint});
 assert.equal((await state()).head.epoch,replacementEpoch);assert.equal((await state()).head.revision,'1');assert.equal((await state()).seq,'1');
 assert.equal(await count('proof_values'),1);assert.equal(await count('cloud_receipts'),1);
});
test('replacement assertion failure rolls back head, domain and receipt together',async()=>{
 error(await replacement(context,operation,{assertions:[sqlStatement('UPDATE cloud_commit_assertion SET ok=0 WHERE slot=1')]}),'PRECONDITION_FAILED');
 assert.equal((await state()).head.epoch,base.epoch);assert.equal((await state()).head.revision,'10');assert.equal((await state()).seq,'0');
 assert.equal(await count('proof_values'),0);assert.equal(await count('cloud_receipts'),0);
});
test('replacement cannot omit its domain assertion or reuse an epoch',async()=>{
 error(await replacement(context,operation,{assertions:[]}),'REPLACEMENT_ASSERTIONS_REQUIRED');
 error(await replacement(context,{...operation,before:{...base,epoch:replacementEpoch}}),'INVALID_REPLACEMENT_EPOCH');
 error(await replacement(context,operation,{nextEpoch:'invalid'}),'INVALID_REPLACEMENT_EPOCH');assert.equal(await count('cloud_receipts'),0);
});
test('a normal publication racing replacement has exactly one winner',async()=>{
 const results=await Promise.all([replacement(),commit(contextB,{...operation,digest:H('competing normal')},[insert('normal')])]);
 assert.equal(results.filter(x=>x.ok).length,1,JSON.stringify(results));assert.equal(await count('proof_values'),1);assert.equal(await count('cloud_receipts'),1);assert.equal((await state()).head.headCounter,'11');
});
test('replacement with stale base cannot overwrite newer cloud or reset its epoch',async()=>{
 const published=await commit(contextB,{...operation,digest:H('newer')},[insert('newer')]);assert.equal(published.ok,true);
 error(await replacement(),'PRECONDITION_FAILED');assert.equal((await state()).head.epoch,base.epoch);assert.equal((await state()).head.revision,'11');assert.equal(await count('proof_values'),1);assert.equal((await state()).seq,'0');
});
test('replacement completion lost response and exact retry return the original epoch receipt',async()=>{
 const first=await replacement(context,operation,{inject:'lostAfterCommit'});assert.equal(first.ok,true,JSON.stringify(first));
 const again=await replacement();assert.deepEqual(again.result,first.result);assert.equal(await count('proof_values'),1);assert.equal((await state()).head.headCounter,'11');
});
test('retiring a replacement fences a delayed request without changing the cloud epoch',async()=>{
 assert.equal((await retire()).ok,true);error(await replacement(),'OPERATION_RETIRED');assert.equal((await state()).head.epoch,base.epoch);assert.equal(await count('proof_values'),0);
});
test('ordinary sync after replacement continues the new epoch and global counter',async()=>{
 const first=await replacement();assert.equal(first.ok,true);
 const next={...operation,sequence:'2',digest:H('after replacement'),before:first.result.head,nextFingerprint:H('next')};
 const normal=await commit(context,next,[insert('next')]);assert.equal(normal.ok,true,JSON.stringify(normal));
 assert.equal(normal.result.head.epoch,replacementEpoch);assert.equal(normal.result.head.revision,'2');assert.equal(normal.result.head.headCounter,'12');
});
