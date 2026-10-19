# NewPipe Cloud — catalog service and native apply

**Implemented:** typed Worker/D1 catalog publication, packed-page transfer, native Room/tag catalog capture, journaled native apply and recovery, stable identity mapping, exact diffs and sparse ordering.

**Not yet an operational end-user sync feature:** Android network/settings integration, automatic background scheduling, the visual preview/recovery controls, forced cloud replacement and three-slot R2 backups remain separate work. The checked-in Worker configuration is API-disabled and restricted to small synthetic deployment tests. Existing Music/AI infrastructure is not part of this service.

## Backend

`src/index.ts` is the typed Worker entry point. Authenticated device tokens derive authority from the configured library and service generation; request bodies cannot select another database/account or submit SQL. `src/service.ts` stages immutable chunks, validates/seals the actual complete target, and publishes pages, catalog metadata, head revision and operation receipt atomically. Retirement fences both sequence and staged digest before deleting payloads. `src/pages.ts` packs ordered semantic records without one D1 write for every occurrence.

`src/admission.ts` supplies fail-closed atomic guards/receipts; its prepared SQL arguments are internal compiler seams, never an HTTP interface. `test/runtime-worker.mjs` and `test/runtime-catalog-worker.mjs` intentionally expose synthetic SQL/fault injection **only inside local tests**; never deploy them.

See [service contract and deployment gates](protocol/service-v1.md), [semantic codec](protocol/codec-v1.md), and [sparse sequence contract](protocol/sequence-v1.md).

Run on Node24:

```sh
cd cloud
npm ci --no-audit --no-fund
npm run check
npm test
```

The D1 tests execute real local workerd bindings via Miniflare, not a hand-written database mock. The 20k-stream / 100k-membership scale fixture is local-only; it is not deployed Free-tier CPU, quota or production latency acceptance. Fixtures use synthetic example.test metadata.

## Native capture, apply and recovery

`CloudNativeCatalog.enroll(scope)` explicitly establishes local identity mappings. `capture(scope, requiredIncarnation)` cannot silently enroll after restore. It validates the persisted binding lifetime marker and playlist-sequence high-water, then projects real Room/tag records behind the shared writer barrier. Cached stream metadata and remote bookmarks are not semantic catalog content; orphan tag associations remain local and are separately accounted for.

`CloudNativeApply.apply(...)` is the internal exact-candidate write adapter. It revalidates the native snapshot and caller admission before modification, compiles portable IDs to preassigned native IDs, and persists the typed chunked plan before the Room transaction changes data. The same Room transaction commits a receipt proving those changes. On restart, the guarded Room helper resolves any native file-swap journal **before tags open**. A pending cloud operation without a matching commit witness is discarded only after proving the before-state; a matching witness permits idempotent tag/map/baseline completion, never replay of main deletions.

The adapter does not grant cloud authentication or replace the future preview/connection-generation/local-safety-backup UI. Its internal caller must supply that admission. It preserves existing stream metadata, subscriptions, history, progress, protected role IDs and remote bookmarks. Only new stream rows use cloud hints. Permanent-artwork-only streams are protected from native orphan cleanup without silently saving them to Library.

The complete resulting native assignment count—retained orphans plus target active assignments—must fit the receiver limit before journaling. Conflicting orphan reactivation/deletion blocks for explicit metadata reconciliation. A damaged journal raises a distinct blocked-recovery error: a user-selected validated **complete** native backup may replace both stores; a stock backup retaining live tags may not bypass that failure.

Main schema is **NP9/RV2 9002**, adding only `rv_cloud_apply_receipt`; explicit migrations retain9001 and upstream9 input. Tag schema **5** adds a single pending plan, bounded chunks and baseline to schema4 identities. Standard backup/export sanitizes runtime receipts, pending payloads, baselines, identity mappings and binding markers from staged copies. Older applications are not supported downgrade readers for these newer schemas.

Build with JDK21:

```sh
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

`test-support/CloudProtocolAssertions.kt` and the new native fixtures are test-only. The independent Python codec oracle is regenerated explicitly, never during test execution. Full-catalog native capture/apply can hold the writer barrier for seconds on large emulated fixtures; this is not a per-edit/UI-latency guarantee. Coalesced/incremental scheduling and real-device performance remain rollout requirements.

## Reproduce process death, not just exception injection

The committed driver requires an explicit emulator serial, exact AVD, Android user, installed isolated QA package, source commit and a fresh output directory. It never chooses a physical phone, switches users, clears app data or stops an unrelated app. It kills only the QA process **after** an fsynced marker proves the selected transaction boundary is reached, then verifies recovery in a fresh process.

```sh
python3 cloud/test-support/run-identity-process-death.py \
  --adb /path/to/adb --serial emulator-5576 --avd Pixel_9_API_35 --user 0 \
  --package org.schabi.newpipe.debug.featurecloudsyncv120261019 \
  --suite apply-after-room --source-commit <full-tested-commit> \
  --output /path/to/new-evidence-directory
```

Suites: `apply-before-room`, `apply-after-room`, `apply-before-tags`, plus prior `identity` and `enrollment`. The first three exercise the actual native apply coordinator. They are not proof of cloud transport recovery or sudden power-loss durability. Without the explicit host driver, the probe test methods skip rather than fabricate coverage.

## Deployment and live synthetic test

`cloud/scripts/wrangler.sh` selects the isolated Ritorhymes OAuth profile and pins the dedicated account, clearing conflicting credential environment overrides. It does not change the default Matthew login. `wrangler.jsonc` has no secrets and targets only `newpipe-cloud-sync-v1` / D1 `bde936ac-caa7-42bb-a931-208118a8cd6a`.

The default API gate is off; bounds are64KiB/eight pages with a per-location120/minute limiter. After review, a small synthetic live activation can verify the actual Worker/D1 path. `scripts/live-smoke.mjs` requires a private600-mode QA credential file, refuses an already initialized catalog, and uses two separately authenticated HTTP clients. It does not represent two Android devices or a complete end-user sync test. The coordinator must subsequently revoke those credentials, remove verified test-only state, and leave the default API gate disabled unless a later release explicitly enables it.

No R2 binding is used for everyday synchronization. The previously provisioned private recovery bucket remains empty until backup implementation. Do not add archive-per-sync behavior.

## Remaining release gates

Production housekeeping for revoked/abandoned stages and receipt retention; durable daily-account resource budgets; actual deployed large-catalog CPU/latency admission; Android transport and persistent credential/settings integration; automatic sync orchestration and comprehensive write-origin capture; exact visual preview and local safety backups; approved forced-replace flow with minimal R2 rotation; actual multi-device end-to-end acceptance; independent source review and release authorization. Historical validation checkpoints under `doc/validation/` apply only to their exact commits and stated emulator page sizes.
