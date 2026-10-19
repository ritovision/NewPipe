# NewPipe Cloud — M1 foundation

**Status:** Local protocol/admission foundations, sparse ordering, and native read-side snapshot/identity mapping are implemented. This is not a deployed sync service, whole M1 acceptance, a cloud settings feature, or a working multi-device synchronization engine.

Source is deliberately separate from `gateway/`, the existing AI relay. Nothing here changes its deployment or authentication. No Cloudflare account credentials or resource IDs are needed to run these tests, and there is no deploy script/Wrangler config.

## Implemented in this checkpoint

- Typed, bounded Kotlin/TypeScript semantic catalog model, canonical bytes and SHA-256 fingerprints.
- Exact Library/Unavailable/playlist membership differences, duplicate occurrences, order/rename/artwork changes, tag/category changes and assignments. UI search/paging/confirmation controls are **not yet implemented**.
- Pure disabled/enrollment/conflict/rollback/unknown-operation decisions and exact preview binding checks. There is **no persistent network scheduler or native apply coordinator yet**.
- Local D1 proof of the fail-closed atomic admission/receipt/revision batch and resolve-or-retire sequence fencing. The SQL file is a **proof control-plane schema**, not the production catalog schema or a migration to run remotely.
- Shared independent golden vectors and the same Kotlin assertions for JVM/Android, plus actual Worker-runtime D1 race tests.
- Native capture of real Room/tag records under the shared writer barrier, without changing native catalog content or silently repairing role identities.
- Persisted portable UUID mappings in tag schema 4, preserved across ordinary reopen and removed from staged native backup/import copies. Main Room stays 9001. This adds no token, sync toggle, network endpoint or remote-apply controller.
- Sparse 128-bit-text sequence labels with exact duplicate-aware edit planning, bounded rebalance and explicit write-budget checks; see [sequence contract](protocol/sequence-v1.md). Generic sealed D1 staging remains unimplemented.

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

Generic large sealed-delta publication, sparse ordering and measured physical schema; real D1 CPU/row budget and account verification; remote stable-ID adoption and write-side mapping; protected-role admission; durable Room/tag/map apply and process-death recovery; full writer coverage; secure credentials and settings; authenticated network transport; exact visual preview; minimal R2 recovery; true two-device convergence; independent implementation review and release authorization. See the milestone plan for boundaries.


## Native capture and compatibility

`CloudNativeCatalog.capture(scope, requiredIncarnation)` never enrolls or resets identities; `enroll(scope)` is a separate explicit local-identity action. Capture requires off-main execution, the current registered repository, no unresolved native deletion intents, and verified persisted Library/Unavailable roles. It opens the guarded Room helper before binding reads, captures within Room/tag transactions under TagRuntime, then commits validated runtime identity maps only after verifying the atomic binding lifetime anchor and playlist-sequence high-water. These are runtime-only metadata; native content is unchanged. Ordinary playlist duplicate occurrences and display rank are preserved; remote bookmarks, automatic artwork/cache refreshes and orphan assignments do not become portable semantic content. Orphan associations are retained and reported separately for later reconciliation.

This is a read-side adapter. It does not apply a cloud library, recover a half-completed cross-store cloud apply, enable sync, or enroll a device. Proper native backup/restore invalidates runtime mappings; arbitrary out-of-band database replacement is not supported or claimed tamper-detectable. New tag-schema-4 databases/backups require this newer fork version; the older schema-3 application is not a supported downgrade reader. No physical owner installation has been upgraded.

Capture owns a copy of exact canonical bytes; the caller must still bind any future preview to the documented live-state checks. The large native fixture measures an off-main full-catalog capture with writer locking; it is not proof of cheap per-edit sync or a user-interaction latency budget. The scheduler/delta pipeline must coalesce and measure production workloads before rollout.


## Reproduce host-induced process death

After installing the exact Cloud QA app/test build on an explicitly selected primary-host emulator, run the committed driver. This never selects a physical phone, changes Android users, clears app data, or touches another package:

```sh
python3 cloud/test-support/run-identity-process-death.py \
  --adb /path/to/adb --serial emulator-5590 \
  --avd MusicYtdlp_16K_Temp_API35 --user 11 \
  --package org.schabi.newpipe.debug.featurecloudsyncv120261019 \
  --suite identity --source-commit <full-40-character-tested-commit> \
  --output /path/to/new-identity-evidence-directory
```

Run `--suite enrollment` with a separate new output directory to stop the QA process after the binding anchor is durable but before tag enrollment commits. The driver requires the fsynced test marker before force-stop and runs verification in a fresh process. Without this explicit driver, probe tests skip rather than claim process-death coverage. The identity suite proves single-tag-transaction rollback; enrollment proves fail-closed marker/map recovery and explicit re-enrollment, not a complete cross-database remote-apply coordinator.

Compatibility: old schema-3 builds cannot import schema-4 tag archives. Older unanchored schema-4 preview contexts cannot be trusted automatically after this correction; capture fails closed until explicit recovery/reset. This is not a data migration for the real phone, which remains untouched.
