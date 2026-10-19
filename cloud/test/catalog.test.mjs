// SPDX-License-Identifier: GPL-3.0-or-later
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { readCatalog, canonicalBytes, fingerprint, diffCatalog, storedNameKey, CATALOG_LIMITS } from '../src/catalog.ts';
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
const catalogs=new Map(fixture.catalogs.map(r=>[r.name,r.catalog]));
for (const r of fixture.catalogs) test(`independent canonical oracle: ${r.name}`,async()=>{
 const c=readCatalog(r.catalog),bytes=canonicalBytes(c);
 assert.equal(bytes.length,r.bytes);assert.equal(await fingerprint(c),r.sha256);
 assert.equal(createHash('sha256').update(bytes).digest('hex'),r.sha256);
});
for(const r of fixture.invalid) test(`rejects invalid catalog: ${r.name}`,()=>assert.throws(()=>readCatalog(r.catalog),/INVALID_CATALOG/));
for(const [name,key] of fixture.normalization) test(`native stored name key ${JSON.stringify(name)}`,()=>assert.equal(storedNameKey(name),key));
for(const r of fixture.diffs) test(`exact difference: ${r.before} -> ${r.after}`,()=>{
 const d=diffCatalog(catalogs.get(r.before),catalogs.get(r.after));
 for(const k of ['added','removed','playlists','assignments'])assert.equal(d[k].length,r[k],k);
 assert.equal(d.playlists.reduce((n,p)=>n+p.added.length,0),r.occurrencesAdded);
 assert.equal(d.playlists.reduce((n,p)=>n+p.removed.length,0),r.occurrencesRemoved);
 assert.equal(d.tags.added.length,r.tagsAdded);assert.equal(d.tags.removed.length,r.tagsRemoved);assert.equal(d.categories.removed.length,r.categoriesRemoved);
});
test('global removal lists exact duplicates while retaining tag definitions',()=>{
 const c=catalogs.get('base'),d=diffCatalog(c,catalogs.get('global-remove'));
 assert.deepEqual(d.removed,[c.library[0]]);assert.deepEqual(d.playlists[0].removed,[c.library[0],c.library[0]]);assert.equal(d.tags.removed.length,0);
});
test('rename retains identity; reordering is not a membership deletion',()=>{
 const c=catalogs.get('base');assert.equal(diffCatalog(c,catalogs.get('rename')).playlists[0].id,c.playlists[0].id);
 const d=diffCatalog(c,catalogs.get('reorder'));assert.equal(d.librarySequenceChanged,true);assert.equal(d.playlists[0].sequenceChanged,true);assert.deepEqual(d.playlists[0].removed,[]);
});
test('validated candidate cannot be mutated by its caller',async()=>{
 const input=structuredClone(catalogs.get('base')),c=readCatalog(input),hash=await fingerprint(c);
 input.library.length=0;input.playlists[0].items.reverse();assert.equal(await fingerprint(c),hash);
 assert.throws(()=>c.library.pop(),TypeError);assert.throws(()=>c.playlists[0].items.reverse(),TypeError);
});
test('limits reject oversized text and collection before encoding',()=>{
 const c=structuredClone(catalogs.get('base'));c.library[0].url='x'.repeat(CATALOG_LIMITS.textUnits+1);assert.throws(()=>readCatalog(c),/text/);
 c.library=Array(CATALOG_LIMITS.streams+1).fill({service:0,url:'x'});assert.throws(()=>readCatalog(c),/limit/);
});
test('20000 streams / 100000 ordinary memberships retain exact complete diff',async()=>{
 const start=performance.now(),keys=Array.from({length:20000},(_,i)=>({service:0,url:`https://example.test/scale/${i}`}));
 const tag={id:'20000000-0000-0000-0000-000000000001',name:'Scale',nameKey:'scale',category:null};
 const c={version:1,serviceMap:1,library:keys,unavailable:[],categories:[],tags:[tag],assignments:keys.map(key=>({key,tags:[tag.id]})),
  playlists:Array.from({length:5},(_,i)=>({id:`30000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`,name:`Synthetic ${i+1}`,artwork:null,items:keys}))};
 const bytes=canonicalBytes(c);assert.equal((await fingerprint(c)).length,64);
 const next={...c,library:keys.slice(1),playlists:c.playlists.map(p=>({...p,items:keys.slice(1)})),assignments:c.assignments.slice(1)};
 const d=diffCatalog(c,next);assert.deepEqual(d.removed,[keys[0]]);assert.equal(d.playlists.length,5);assert.equal(d.playlists.reduce((n,p)=>n+p.removed.length,0),5);assert.deepEqual(d.assignments[0].removed,[tag.id]);
 console.log(`CLOUD_SCALE streams=20000 ordinary_memberships=100000 assignments=20000 canonical_bytes=${bytes.length} duration_ms=${Math.round(performance.now()-start)}`);
});
