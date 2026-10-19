# NewPipe Cloud — M1 foundation

**Status:** Local protocol and admission foundations only. This is not a deployed sync service, whole M1 acceptance, a cloud settings feature, or a working multi-device synchronization engine.

Source is deliberately separate from `gateway/`, the existing AI relay. Nothing here changes its deployment or authentication. No Cloudflare account credentials or resource IDs are needed to run these tests, and there is no deploy script/Wrangler config.

## Implemented in this checkpoint

- Typed, bounded Kotlin/TypeScript semantic catalog model, canonical bytes and SHA-256 fingerprints.
- Exact Library/Unavailable/playlist membership differences, duplicate occurrences, order/rename/artwork changes, tag/category changes and assignments. UI search/paging/confirmation controls are **not yet implemented**.
- Pure disabled/enrollment/conflict/rollback/unknown-operation decisions and exact preview binding checks. There is **no persistent network scheduler or native apply coordinator yet**.
- Local D1 proof of the fail-closed atomic admission/receipt/revision batch and resolve-or-retire sequence fencing. The SQL file is a **proof control-plane schema**, not the production catalog schema or a migration to run remotely.
- Shared independent golden vectors and the same Kotlin assertions for JVM/Android, plus actual Worker-runtime D1 race tests.

## Run

Use Node 24 (tested 24.21.0) and the checked-in lockfile:

```sh
cd cloud
npm ci --no-audit --no-fund
npm run check
npm test
```

Tests run entirely locally. D1 tests execute the actual admission implementation **inside workerd through Miniflare**, not through a hand-written SQLite mock. The harness is ephemeral and does not use remote bindings. `test/runtime-worker.mjs` intentionally accepts test SQL and **must never be deployed or exposed**. It is not a mobile API. Source TS is stripped by Node's built-in test-time helper; `npm run check` independently type-checks it.

The SQL mutation list accepted by `guardedCommit` is an **internal seam for a future trusted typed operation compiler**. HTTP clients must never provide those statements or assert their own trusted request digest. Production use is blocked until the typed domain validator, exact canonical request digest, primary-read/base fencing, staging budgets, external-service-generation binding and network security boundary are implemented and tested. The test-only mutation table is not a catalog.

Android/JVM:

```sh
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

`cloud/test-support/CloudProtocolAssertions.kt` is test-only Kotlin source included in JVM and instrumentation source sets. The single fixture file lives at `app/src/test/resources/cloud/golden-v1.json`; Android tests package it as a test asset. No JUnit/test fixture enters production source.

## Format and limits

See [codec format](protocol/codec-v1.md), [product contract](../doc/specs/cloud-sync-v1.md), [architecture](../doc/architecture/cloud-sync-v1.md), and [adjudication](../doc/reviews/cloud-sync-design-adjudication-2026-10-19.md).

`protocol/generate-golden.py` is an independent Python encoder. Regeneration is explicit, never part of a test run; compare any changed expectations before committing. It has no provider/user-data inputs.

The 20,000-stream / 100,000-ordinary-membership / 20,000-assignment fixture measures local codec/diff behavior, not D1 row/CPU/storage acceptance. No such scale fixture is uploaded to Cloudflare. Format ceilings are not an admission promise for the Free tier.

## Remaining gates

Generic large sealed-delta publication, sparse ordering and measured physical schema; real D1 CPU/row budget and account verification; stable native ID projection; protected-role admission; durable Room/tag/map apply and process-death recovery; full writer coverage; secure credentials and settings; authenticated network transport; exact visual preview; minimal R2 recovery; true two-device convergence; independent implementation review and release authorization. See the milestone plan for boundaries.
