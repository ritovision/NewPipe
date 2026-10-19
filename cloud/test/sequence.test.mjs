// SPDX-License-Identifier: GPL-3.0-or-later
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {planSequence,requireSequenceBudget} from '../src/sequence.ts';
const key=(url,service=0)=>({service,url:`https://example.test/${url}`});
const A=key('A'), B=key('B'), C=key('C'), X=key('X');
const seeded=keys=>planSequence([],keys).rows;
const apply=(before,p)=>{
 const state=new Map(before.map(row=>[row.rank,row]));
 for(const rank of p.remove)assert.equal(state.delete(rank),true,'delete must refer to existing base row');
 for(const row of p.put){assert.equal(state.has(row.rank),false,'must not overwrite a retained row');state.set(row.rank,row);}
 return [...state.values()].sort((a,b)=>a.rank.localeCompare(b.rank,'en')).map(row=>row.key);
};
test('empty sequence is a no-op',()=>{assert.deepEqual(planSequence([],[]),{rows:[],remove:[],put:[],rebalanced:false});});
test('initial labels have independent fixed-width 128-bit expected values',()=>{
 const p=planSequence([],[A,B,C]);
 const step=((1n<<128n)-1n)/4n;
 assert.deepEqual(p.rows.map(r=>r.rank),[1n,2n,3n].map(n=>(n*step).toString(16).padStart(32,'0')));
 assert.deepEqual(apply([],p),[A,B,C]);
});
test('deletion preserves all surviving ranks with one deleted row',()=>{
 const before=seeded([A,B,C]);const p=planSequence(before,[A,C]);
 assert.deepEqual(p.remove,[before[1].rank]);assert.deepEqual(p.put,[]);assert.equal(p.rebalanced,false);assert.deepEqual(apply(before,p),[A,C]);
});
test('middle insertion writes one occurrence, not the following sequence',()=>{
 const before=seeded([A,B,C]),p=planSequence(before,[A,X,B,C]);
 assert.equal(p.remove.length,0);assert.equal(p.put.length,1);assert.equal(p.rows[2].rank,before[1].rank);assert.deepEqual(apply(before,p),[A,X,B,C]);
});
test('moving last item to front removes/inserts only that occurrence',()=>{
 const before=seeded([A,B,C]),p=planSequence(before,[C,A,B]);
 assert.equal(p.remove.length,1);assert.equal(p.put.length,1);assert.deepEqual(apply(before,p),[C,A,B]);
});
test('duplicate occurrences remain separate and deterministic',()=>{
 const before=seeded([A,A,B,A]),p=planSequence(before,[A,B,A,A]);
 assert.deepEqual(apply(before,p),[A,B,A,A]);assert.deepEqual(planSequence(before,[A,B,A,A]),p);
 assert.equal(new Set(p.rows.map(r=>r.rank)).size,4);
});
test('same URL in different services never matches the wrong occurrence',()=>{
 const other=key('A',1),before=seeded([A,other]);const p=planSequence(before,[other]);
 assert.deepEqual(p.remove,[before[0].rank]);assert.deepEqual(p.put,[]);assert.deepEqual(apply(before,p),[other]);
});
test('clear sequence deletes occurrences including duplicates',()=>{
 const before=seeded([A,A,B]),p=planSequence(before,[]);assert.equal(p.remove.length,3);assert.equal(p.put.length,0);assert.deepEqual(apply(before,p),[]);
});
test('exhausted gap explicitly rebalances only the bounded sequence',()=>{
 const before=[{rank:'00000000000000000000000000000001',key:A},{rank:'00000000000000000000000000000002',key:B}];
 const p=planSequence(before,[A,X,B]);assert.equal(p.rebalanced,true);assert.deepEqual(apply(before,p),[A,X,B]);
 assert.throws(()=>requireSequenceBudget(p,1),/WRITE_BUDGET/);requireSequenceBudget(p,p.put.length+p.remove.length);
});
for(const rank of ['1','0'.repeat(32),'f'.repeat(32),'A'.repeat(32),'g'.repeat(32),'1'.repeat(33)])
 test(`reject malformed/reserved rank ${rank}`,()=>assert.throws(()=>planSequence([{rank,key:A}],[A]),/INVALID_SEQUENCE_RANK/));
test('non-increasing or duplicate ranks rejected',()=>{
 const rows=seeded([A,B]);assert.throws(()=>planSequence([...rows].reverse(),[A]),/STRICTLY_ORDERED/);
 assert.throws(()=>planSequence([rows[0],rows[0]],[A]),/STRICTLY_ORDERED/);
});
test('invalid key and write-budget shapes rejected',()=>{
 assert.throws(()=>planSequence([],[{service:-1,url:'x'}]),/INVALID_CATALOG/);
 assert.throws(()=>planSequence([],[{service:0,url:'x',nativeId:9}]),/INVALID_CATALOG/);
 for(const budget of [-1,0.5,NaN,Infinity])assert.throws(()=>requireSequenceBudget(planSequence([],[]),budget),/WRITE_BUDGET/);
});
test('mutating input cannot change planned content or ranks',()=>{
 const before=structuredClone(seeded([A,B])),desired=[structuredClone(C),structuredClone(A)],p=planSequence(before,desired);
 const actual=structuredClone(p);before[0].key.url='wrong';desired[0].url='wrong';assert.deepEqual(p,actual);assert.throws(()=>p.rows.pop(),TypeError);
});
test('seeded randomized edits always round-trip exact desired occurrences',()=>{
 let seed=1729;const random=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};
 const all=[A,B,C,X,key('Unicode-β'),key('A',1)];let rows=[];
 for(let i=0;i<500;i++){
  const desired=rows.map(r=>r.key);const op=random(4);
  if(op===0||desired.length===0)desired.splice(random(desired.length+1),0,all[random(all.length)]);
  else if(op===1)desired.splice(random(desired.length),1);
  else if(op===2){const source=random(desired.length),[item]=desired.splice(source,1);desired.splice(random(desired.length+1),0,item);}
  else desired.reverse();
  const p=planSequence(rows,desired);assert.deepEqual(apply(rows,p),desired,`iteration ${i}`);
  assert.deepEqual(p.rows.map(r=>r.key),desired);assert.equal(new Set(p.rows.map(r=>r.rank)).size,desired.length);rows=p.rows;
 }
});
test('100000 identical occurrences do not use quadratic array shifting',()=>{
 const started=performance.now(),before=seeded(Array(100000).fill(A));const p=planSequence(before,Array(99999).fill(A));
 assert.equal(p.remove.length,1);assert.equal(p.put.length,0);assert.equal(p.rows.length,99999);
 console.log(`CLOUD_SPARSE_DUPLICATES occurrences=100000 removed=1 inserted=0 duration_ms=${Math.round(performance.now()-started)}`);
});
test('100000 unique occurrences retain unaffected rows after global removal',()=>{
 const started=performance.now(),keys=Array.from({length:100000},(_,i)=>key(String(i))),before=seeded(keys),p=planSequence(before,keys.filter((_,i)=>i!==50000));
 assert.equal(p.remove.length,1);assert.equal(p.put.length,0);assert.equal(p.rows[50000].rank,before[50001].rank);
 console.log(`CLOUD_SPARSE_UNIQUE occurrences=100000 removed=1 inserted=0 duration_ms=${Math.round(performance.now()-started)}`);
});

test('actual local D1 TEXT order and atomic patch match exact duplicate sequence',async()=>{
 // Ephemeral test worker only. No production endpoint, remote binding or arbitrary URL fetch.
 const script=`export default {async fetch(r,e){const p=await r.json(),db=e.DB;
 try {if(p.create){await db.prepare("CREATE TABLE items(rank TEXT PRIMARY KEY,key_json TEXT NOT NULL)").run();}
 const batch=[db.prepare("DELETE FROM items WHERE rank IN (SELECT value FROM json_each(?))").bind(JSON.stringify(p.remove)),
 db.prepare("INSERT INTO items(rank,key_json) SELECT json_extract(value,'$.rank'),json_extract(value,'$.key') FROM json_each(?)").bind(JSON.stringify(p.put))];
 const results=await db.batch(batch);const rows=await db.prepare("SELECT rank,key_json FROM items ORDER BY rank COLLATE BINARY").all();
 return Response.json({results,rows:rows.results});}catch(error){return Response.json({error:String(error)},{status:409});}}};`;
 const mf=new Miniflare(convertV4MiniflareOptions({cf:false,modules:true,script,compatibilityDate:'2026-08-25',d1Databases:{DB:'cloud-sparse-local'}}));
 const patch=async p=>{const response=await mf.dispatchFetch('http://sparse.local/',{method:'POST',body:JSON.stringify(p)});return {status:response.status,data:await response.json()};};
 try{
  await mf.ready;let before=[];
  for(const [i,desired]of [[A,A,B,C],[C,A,B,A],[C,B,A],[C,B,A,X]].entries()){
   const p=planSequence(before,desired);const r=await patch({...p,create:i===0});assert.equal(r.status,200,JSON.stringify(r.data));
   assert.deepEqual(r.data.rows.map(row=>JSON.parse(row.key_json)),desired);assert.deepEqual(r.data.rows.map(row=>row.rank),p.rows.map(row=>row.rank));before=p.rows;
  }
  const bad=await patch({remove:[before[0].rank],put:[before[1]]});assert.equal(bad.status,409);
  const state=await patch({remove:[],put:[]});assert.deepEqual(state.data.rows.map(row=>row.rank),before.map(row=>row.rank));
 }finally{await mf.dispose();}
});
