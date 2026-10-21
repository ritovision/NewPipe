# Independent malformed-input continuation probes

Executed locally on 2026-10-21 against source `2b5c85b801481923de9af88aaa082868db40cd92`. Result: **2/2 tests passed**, with zero failures, cancellations or skips. See [independent.log](independent.log).

These are additional reviewer-written synthetic probes. They use the existing service-test fixture and the production Worker/SQL in local Miniflare. No service, library, credential, D1 database or R2 bucket in a live Cloudflare account was accessed. The fixture's own synthetic identities are reused; none are copied into this report.

## What the probes establish

- Ten malformed upload-start requests return client errors, leave no stage or CONTROL ticket/held capacity, preserve the initial head, and permit a later valid initialization.
- Twelve malformed chunks return client errors, preserve the exact accepted rows/counters and release CONTROL capacity. Replaying the accepted chunk is idempotent; the valid continuation seals and publishes the expected catalog. Maintenance subsequently reaches HELD with zero pending effects.

The malformed variants are assertions within two tests, not 22 separate tests. This is bounded adversarial coverage, not an exhaustive fuzzer or proof that every malformed payload is safe. It complements the retained cross-chunk uniqueness regression and the selected backend run.

## Fixture and reproduction

The executed temporary harness reused lines 1–81 of the pinned [service.test.mjs fixture](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/test/service.test.mjs#L1-L81), ending after its `beforeEach` setup, then appended the JavaScript below. Relative module imports were resolved to the original checkout, `miniflare` to the installed package, and `import.meta.url` to the original service-test URL. Those adaptations allow the probe to live outside the repository without changing the production source or test fixtures.

The following recipe, run from a checkout of the reviewed source with its existing dependencies and Node v24.19.0, reconstructs an equivalent temporary harness. It does not install dependencies or edit tracked files. Its local D1 fixture intentionally resets only the databases it creates in its own temporary directory.

```python
import json
import pathlib
import re
import subprocess
import tempfile

repo = pathlib.Path.cwd().resolve()
fixture = repo / 'cloud/test/service.test.mjs'
header = ''.join(fixture.read_text().splitlines(keepends=True)[:81])
header = re.sub(
    r"(from\s+)(['\"])(\.\.?/[^'\"]+)\2",
    lambda m: m[1] + json.dumps((fixture.parent / m[3]).resolve().as_uri()),
    header,
)
miniflare = (repo / 'cloud/node_modules/miniflare/dist/src/index.js').as_uri()
header = header.replace("from 'miniflare'", 'from ' + json.dumps(miniflare))
header = header.replace('import.meta.url', 'REVIEW_ORIGINAL_URL')
appendix = repo / 'doc/reviews/cloud-sync-v1-astra-final-20261021/evidence/independent-probes.md'
probe = appendix.read_text().split('```javascript\n', 1)[1].split('\n```', 1)[0]
with tempfile.TemporaryDirectory(prefix='newpipe-astra-final-probe-') as temporary:
    harness = pathlib.Path(temporary) / 'independent-probes.mjs'
    harness.write_text(
        'const REVIEW_ORIGINAL_URL = ' + json.dumps(fixture.as_uri()) + ';\n'
        + header + '\n' + probe + '\n'
    )
    subprocess.run(
        ['node', '--test', '--test-concurrency=1', str(harness)],
        cwd=repo / 'cloud', check=True,
    )
```

## Appended probe source

```javascript
async function finalAssertReleased() {
 const db=await mf.getD1Database('CONTROL');
 assert.equal((await db.prepare('SELECT COUNT(*) n FROM cloud_fence_tickets').first()).n,0);
 assert.deepEqual(await db.prepare('SELECT state,held_reads,held_writes,held_growth FROM cloud_budget').first(),
  {state:'ACTIVE',held_reads:0,held_writes:0,held_growth:0});
}
test('Astra final: ten malformed start headers release CONTROL and preserve fresh enrollment',async()=>{
 const target=catalog('base'),head=await status(),pages=await encodePages(target);
 const u=await makeUpload('initialize','1',head.head,await fingerprint(target),changedPages([],pages));
 const invalid=[
  {...u.header,mode:'historical-local'},
  {...u.header,sequence:'01'},
  {...u.header,sequence:'9223372036854775808'},
  {...u.header,chunkCount:0},
  {...u.header,chunkCount:1.5},
  {...u.header,changeCount:0},
  {...u.header,payloadBytes:-1},
  {...u.header,digest:'A'.repeat(64)},
  {...u.header,library:'other-library'},
  {...u.header,before:{...u.header.before,headCounter:'01'}}
 ];
 for(const [i,input] of invalid.entries()){
  const result=await call('/v1/uploads/start',input);
  assert.ok(result.status>=400&&result.status<500,JSON.stringify({i,result}));
  await finalAssertReleased();assert.equal(await rows('cloud_uploads'),0);
  assert.deepEqual((await status()).head,head.head);
 }
 const valid=await stage(target);await publish(valid);
 assert.equal(await fingerprint((await download()).catalog),await fingerprint(target));
 await finalAssertReleased();
});
test('Astra final: twelve malformed chunks preserve exact stage and a valid continuation commits',async()=>{
 const target={...catalog('empty'),library:Array.from({length:300},(_,i)=>({service:0,url:'https://example.test/review-saved/'+i}))};
 const head=await status(),pages=await encodePages(target),changes=changedPages([],pages);
 assert.ok(changes.length>=2);
 const u=await makeUpload('initialize','1',head.head,await fingerprint(target),changes);
 const header={...u.header,chunkCount:2};header.digest=await uploadDigest(header,changes);
 assert.equal((await call('/v1/uploads/start',header)).status,200);
 const first={sequence:'1',index:0,changes:[changes[0]]};
 assert.equal((await call('/v1/uploads/chunk',first)).status,200);
 const snapshot=async()=> (await sql('SELECT * FROM cloud_upload_changes ORDER BY page_id',
  'SELECT * FROM cloud_upload_chunks ORDER BY chunk_index',
  'SELECT received_chunks,received_changes,received_bytes FROM cloud_uploads')).map(x=>x.results);
 const original=await snapshot();
 const good={sequence:'1',index:1,changes:changes.slice(1)};
 const invalid=[
  {...good,authority:'other-device'}, {...good,index:-1}, {...good,index:1.5},
  {...good,index:3}, {...good,changes:[]},
  {...good,changes:[changes[0],changes[0]]},
  {...good,changes:[changes[0]]},
  {...good,changes:[{id:'0'.repeat(64),body:null}]},
  {...good,changes:[{id:changes[1].id,body:17}]},
  {...good,changes:[{id:'not-a-page',body:null}]},
  {...good,changes:[{...changes[1],authority:'other-device'}]},
  {...good,sequence:'01'}
 ];
 for(const [i,input] of invalid.entries()){
  const result=await call('/v1/uploads/chunk',input);
  assert.ok(result.status>=400&&result.status<500,JSON.stringify({i,result}));
  await finalAssertReleased();assert.deepEqual(await snapshot(),original);
 }
 assert.equal((await call('/v1/uploads/chunk',first)).status,200);
 assert.deepEqual(await snapshot(),original);
 assert.equal((await call('/v1/uploads/chunk',good)).status,200);
 const ref={sequence:'1',digest:header.digest};
 assert.equal((await call('/v1/uploads/seal',ref)).status,200);
 await publish({ref});
 assert.equal(await fingerprint((await download()).catalog),await fingerprint(target));
 await finalAssertReleased();
 const held=await closeFence(await mf.getD1Database('CONTROL'),'test-resource',
  '71111111-1111-4111-8111-111111111111','72222222-2222-4222-8222-222222222222');
 assert.equal(held.state,'HELD');assert.equal(held.pending,0);
});
```

Only this documentation recipe is committed. The executed `.mjs` harness was created outside the repository. The selected production source, existing test sources and dependency files were not modified.
