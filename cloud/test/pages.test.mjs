// SPDX-License-Identifier: GPL-3.0-or-later
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {encodePages,readPages,changedPages,packEntries,parsePage,pageManifest,sha256,PAGE_LIMITS} from '../src/pages.ts';
import {fingerprint} from '../src/catalog.ts';
import {makeUpload,uploadDigest} from '../src/service.ts';
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
const catalogs=new Map(fixture.catalogs.map(r=>[r.name,r.catalog]));
for(const g of fixture.catalogs)test(`packed catalog round trip: ${g.name}`,async()=>{
 const pages=await encodePages(g.catalog);const result=await readPages(pages);
 assert.equal(await fingerprint(result.catalog),g.sha256);assert.equal(new Set(pages.map(p=>p.id)).size,pages.length);
 assert.ok(pages.every(p=>new TextEncoder().encode(p.body).length<=PAGE_LIMITS.pageBytes));
 assert.deepEqual(changedPages(pages,await encodePages(result.catalog,pages)),[]);
});
test('semantic ordering of definitions does not churn packed pages',async()=>{
 const before=await encodePages(catalogs.get('base')),after=await encodePages(catalogs.get('semantic-order'),before);
 assert.deepEqual(changedPages(before,after),[]);
});
test('hints transfer exact duration/type without changing semantic fingerprint',async()=>{
 const base=catalogs.get('base'),hint={key:base.library[0],title:'Exact title',uploader:'Uploader',duration:'9007199254740993',streamType:'VIDEO_STREAM',thumbnail:null};
 const pages=await encodePages(base,[],[hint]),decoded=await readPages(pages);
 assert.deepEqual(decoded.hints,[hint]);assert.equal(await fingerprint(decoded.catalog),await fingerprint(base));
 await assert.rejects(()=>encodePages(base,[],[{...hint,key:{service:0,url:'https://example.test/not-in-library'}}]),/ORPHAN_MEDIA_HINT/);
});
test('partial/mixed/duplicate/corrupt pages are not valid catalogs',async()=>{
 const pages=await encodePages(catalogs.get('base'));
 await assert.rejects(()=>readPages([]),/MISSING_CATALOG_VERSION/);
 await assert.rejects(()=>readPages([...pages,pages[0]]),/DUPLICATE_PAGE/);
 await assert.rejects(()=>readPages([{...pages[0],id:'a'.repeat(64)}]),/PAGE_ID_MISMATCH/);
 await assert.rejects(()=>readPages([{...pages[0],body:pages[0].body+' '}]),/NONCANONICAL_PAGE/);
 const entries=pages.flatMap(parsePage);entries.push(['unknown',true]);
 await assert.rejects(async()=>readPages(await packEntries(entries)),/UNKNOWN_CATALOG_ENTRY/);
});
test('hidden orphan sequence and forged assignment keys fail semantic reconstruction',async()=>{
 const pages=await encodePages(catalogs.get('base')),entries=pages.flatMap(parsePage);
 const rank='80000000000000000000000000000000';
 await assert.rejects(async()=>readPages(await packEntries([...entries,['s/44444444-0000-0000-0000-000000000001/'+rank,[0,'https://example.test/A']]])),/ORPHAN_SEQUENCE/);
 const assignment=entries.find(e=>e[0].startsWith('a/'));
 const forged=entries.filter(e=>e!==assignment);forged.push([assignment[0]+'bad',assignment[1]]);
 await assert.rejects(async()=>readPages(await packEntries(forged)),/INVALID_ASSIGNMENT_KEY/);
});
test('same-count reorder or duplicate removal changes exact fingerprint and page payload',async()=>{
 const before=await encodePages(catalogs.get('base'));
 for(const name of ['reorder','global-remove','rename']){
  const target=catalogs.get(name),next=await encodePages(target,before);
  assert.notEqual(await pageManifest(before),await pageManifest(next));assert.ok(changedPages(before,next).length>0);
  assert.equal(await fingerprint((await readPages(next)).catalog),await fingerprint(target));
 }
});
test('page manifest is order-independent but binds every byte and page id',async()=>{
 const keys=Array.from({length:500},(_,i)=>({service:0,url:'https://example.test/'+i}));
 const pages=await encodePages({...catalogs.get('empty'),library:keys});assert.ok(pages.length>1);
 assert.equal(await pageManifest(pages),await pageManifest([...pages].reverse()));
 assert.notEqual(await pageManifest(pages),await pageManifest(pages.slice(1)));
});
test('an entry too large for the record cap fails before transport',async()=>{
 const huge=['h/x',{payload:'x'.repeat(PAGE_LIMITS.pageBytes)}];await assert.rejects(()=>packEntries([huge]),/ENTRY_BYTE_LIMIT/);
 assert.throws(()=>parsePage({id:'a'.repeat(64),body:'[]'}),/PAGE_ENTRY_LIMIT/);
});
test('incremental insertion does not create a new tiny page on each edit',async()=>{
 const base={...catalogs.get('empty'),library:Array.from({length:1000},(_,i)=>({service:0,url:'https://example.test/'+i}))};
 let pages=await encodePages(base);const initial=pages.length;let maxChanged=0;
 for(let i=0;i<160;i++){
  base.library.splice(200,0,{service:0,url:'https://example.test/new-'+i});
  const next=await encodePages(base,pages);maxChanged=Math.max(maxChanged,changedPages(pages,next).length);pages=next;
 }
 assert.ok(pages.length<initial+8,`unbounded fragmentation: ${initial} -> ${pages.length}`);assert.ok(maxChanged<12,`churn=${maxChanged}`);
 console.log(`CLOUD_PACKED_REPEATED base=1000 edits=160 initial_pages=${initial} final_pages=${pages.length} max_changed_pages=${maxChanged}`);
});
test('upload digest binds exact base, target, sequence and actual changes',async()=>{
 const empty=catalogs.get('empty'),base=catalogs.get('base'),pages=await encodePages(base),changes=changedPages([],pages);
 const head={serviceGeneration:'service',library:'library',epoch:'epoch',revision:'0',headCounter:'0',fingerprint:await fingerprint(empty)};
 const upload=await makeUpload('initialize','1',head,await fingerprint(base),changes);
 assert.equal(await uploadDigest(upload.header,changes),upload.header.digest);
 assert.notEqual(await uploadDigest({...upload.header,sequence:'2'},changes),upload.header.digest);
 assert.notEqual(await uploadDigest({...upload.header,before:{...head,revision:'1'}},changes),upload.header.digest);
 assert.ok(upload.chunks.every((changes,index)=>Buffer.byteLength(JSON.stringify({sequence:'1',index,changes}))<=65536));
});
