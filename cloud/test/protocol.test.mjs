// SPDX-License-Identifier: GPL-3.0-or-later
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { counter, decide, previewStillMatches } from '../src/protocol.ts';
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
for(const value of fixture.validCounters)test(`exact decimal counter ${value}`,()=>assert.equal(counter(value).toString(),value));
for(const value of fixture.invalidCounters)test(`reject malformed counter ${JSON.stringify(value)}`,()=>assert.throws(()=>counter(value),/INVALID_COUNTER/));
for(const r of fixture.policies)test(`sync decision: ${r.name}`,()=>assert.equal(decide(r.input),r.expected));
test('preview binds exact local/cloud/candidate/scope/expiry and connection',()=>{
 const remote=fixture.policies.find(p=>p.name==='unchanged').input.remote;
 const p={action:'USE_CLOUD',connectionGeneration:'1',incarnation:'incarnation-a',localFingerprint:remote.fingerprint,remote,candidateDigest:remote.fingerprint,expiresAt:'1000'};
 assert.equal(previewStillMatches(p,structuredClone(p),'999'),true);assert.equal(previewStillMatches(p,p,'1000'),false);
 const cases=[{action:'REPLACE_CLOUD'},{connectionGeneration:'2'},{incarnation:'incarnation-b'},{localFingerprint:'a'.repeat(64)},
  {remote:{...remote,revision:'11',headCounter:'11'}},{candidateDigest:'b'.repeat(64)},{expiresAt:'1001'}];
 for(const changes of cases)assert.equal(previewStillMatches(p,{...p,...changes},'999'),false);
});
