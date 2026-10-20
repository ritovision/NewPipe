# NewPipe Cloud — catalog service and native apply

**Implemented:** typed Worker/D1 catalog publication, packed-page transfer, native Room/tag catalog capture, journaled native apply and recovery, stable identity mapping, exact diffs and sparse ordering. Android includes encrypted credentials, connection/settings controls, exact-candidate previews, verified local safety backups, durable upload recovery and foreground/WorkManager scheduling.

**Not release-ready:** forced cloud replacement and operator disaster-recovery tooling remain unfinished. Explicit three-slot R2 backup, rotation and Android restore now exist; see [executed recovery evidence](../doc/validation/cloud-recovery-20261020/README.md). Integration, failure, UI and scale acceptance must be tied to the specific tested candidate. The checked-in Worker configuration is API-disabled and restricted to small synthetic deployment tests. Existing Music/AI infrastructure is not part of this service.

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

The adapter does not grant cloud authentication. `CloudSyncController` supplies connection-generation admission, exact preview validation and a verified local safety backup before an explicit pull replaces local catalog content. The adapter preserves existing stream metadata, subscriptions, history, progress, protected role IDs and remote bookmarks. Only new stream rows use cloud hints. Permanent-artwork-only streams are protected from native orphan cleanup without silently saving them to Library.

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

`cloud/scripts/wrangler.sh` selects the named `newpipe-cloud` OAuth profile and pins the dedicated Ritorhymes account, clearing conflicting credential environment overrides. A named profile is necessary on hosts where legacy `~/.wrangler` takes precedence over `XDG_CONFIG_HOME`; the wrapper does not use or replace another project's default login. `wrangler.jsonc` has no secrets and targets only `newpipe-cloud-sync-v1` / D1 `bde936ac-caa7-42bb-a931-208118a8cd6a`. Set up that profile through Wrangler's supported authentication flow and verify the account before remote writes; do not copy credentials into this repository.

The default API gate is off; bounds are64KiB/eight pages with a per-location120/minute limiter. After review, a small synthetic live activation can verify the actual Worker/D1 path. `scripts/live-smoke.mjs` requires a private600-mode QA credential file, refuses an already initialized catalog, and uses two separately authenticated HTTP clients. It does not represent two Android devices or a complete end-user sync test. The coordinator must subsequently revoke those credentials, remove verified test-only state, and leave the default API gate disabled unless a later release explicitly enables it.

### Two isolated Android installations

On `feature/cloud-sync-v1-20261019`, the default debug package is
`org.schabi.newpipe.debug.featurecloudsyncv120261019`. Building with
`-PcloudQaPeer=b` produces a separate `.peerb` app and test package. Copy and
verify each pair's `output-metadata.json` before building the other variant,
because both use the debug output directory. This property is restricted to the
feature branch and does not alter release identifiers.

`test-support/run-live-android.py` drives the real controller, private Android
stores, Keystore and HTTPS client. It accepts only the two named QA packages and
the dedicated synthetic origin. Its credential JSON must be mode0600 and contain
`syntheticOnly: true`, `origin`, and `tokens: {a, b}`; provision tokens outside
Git. The driver does not provision, deploy, clear data or revoke credentials.

```sh
python3 cloud/test-support/run-live-android.py \
  --adb /path/to/adb --serial <explicit-serial> \
  --credentials /private/qa-credentials.json --output /new/evidence-directory
```

Physical-device use additionally requires `--allow-phone` and prior owner
authorization. The initial scenario refuses previously connected/nonempty
installations. It verifies initial publication, duplicate playlist entries,
Unavailable and tags, automatic push/pull, disabled local divergence, and
one-time restore remaining disabled. After that successful run, use
`--scenario conflict` with a different output directory: it resumes both
installations, commits a local edit behind the writer barrier, fsyncs a marker,
actually stops that QA process, publishes the other installation's edit, and
checks that conflict detection preserves both branches until explicit
reconciliation. It does not claim an OS network-outage or two-physical-device test.

Both scenarios end by disabling synchronization and removing the plaintext QA
credential files on the devices. On any failure the driver force-stops only the
named QA packages; the operator must still revoke credentials, inspect retained
state and disable the Worker. The coordinator must do the same server cleanup
after success. Never run the initial scenario against retained acceptance data
by clearing it merely to make the test pass.

Everyday synchronization performs no R2 operations. Explicit recovery uses the
private `RECOVERY` binding with a separate default-off API gate. Do not add
archive-per-sync behavior.

## Remaining release gates

Production housekeeping for revoked/abandoned stages and receipt retention; durable daily-account resource budgets; actual deployed large-catalog CPU/latency admission; broader scheduling/offline/write-origin acceptance; broader UI/theme/rotation acceptance; approved forced-replace flow bound to a verified outgoing R2 archive; actual multi-device end-to-end acceptance; independent whole-feature review and release authorization. Historical validation checkpoints under `doc/validation/` apply only to their exact commits and stated device/page sizes.

## Explicit recovery acceptance

`ENABLE_RECOVERY_API` defaults false alongside `ENABLE_CATALOG_API`. Migration `0002_recovery.sql` supplies the three-slot index; provisioning/reconciliation of its control row is an operator action after physical object and multipart inventory. Never automatically rebuild that row from an ordinary HTTP request. `MAX_RECOVERY_BYTES` is deliberately only 1 MiB for the dedicated small live QA; the protocol hard limit does not admit larger production loads.

The native controller exports/validates device ZIPs or the exact downloaded D1 catalog, records the approved payload before reservation, and resolves the same operation after interruption. Restores stay local and leave sync off. See [protocol](protocol/recovery-v1.md) for verification and cleanup rules.

The explicit `test-support/run-live-recovery.py` driver uses two known isolated QA packages and a private synthetic credential file. It requires `--allow-phone` for a physical device. Provision/enable and subsequent revoke/disable/known-data cleanup remain explicit operator steps; the driver never clears app data or deploys by itself.
