# NewPipe Cloud — catalog service and native apply

**Implemented:** typed Worker/D1 catalog publication, packed-page transfer, native Room/tag catalog capture, journaled native apply and recovery, stable identity mapping, exact diffs and sparse ordering. Android includes encrypted credentials, connection/settings controls, exact-candidate previews, verified local safety backups, durable upload recovery and foreground/WorkManager scheduling.

**Not release-ready:** protected cloud replacement and operator disaster recovery have passed small synthetic live checkpoints. The [operator and recovery runbook](../doc/architecture/cloud-operator-recovery.md) uses a separate, non-expiring maintenance barrier, authenticated execution and transactional receipts. [Fenced recovery evidence](../doc/validation/cloud-fence-20261020/README.md) covers real index loss, D1 Time Travel and rebuilding all three archives on newly provisioned isolated resources. Legacy resource pairs remain inspection-only. [Guarded reopening](../doc/validation/cloud-reopen-20261021/README.md) now verifies recovery and issues fresh device credentials before opening the external fence; Android recovery reconnection has passed 34 executed controller tests and five real process-kill/restart checks; see [runtime evidence](../doc/validation/cloud-reconnect-process-death-20261021/README.md). Explicit three-slot R2 backup, rotation and Android restore now exist; see [executed recovery evidence](../doc/validation/cloud-recovery-20261020/README.md). Integration, failure, UI and scale acceptance must be tied to the specific tested candidate. The checked-in Worker configuration is API-disabled and restricted to small synthetic deployment tests. Existing Music/AI infrastructure is not part of this service.

## Tested candidate checkpoint

[2026-10-21 candidate evidence](../doc/validation/cloud-v1-candidate-20261021/README.md) records 359 backend tests, 351 JVM tests, 157 executed native Android tests, three real theme/rotation/credential UI scenarios, the 16-step two-installation HTTPS/offline/conflict matrix, and live budget/cleanup acceptance. The minified small public sample measured p95 CPU 6.950 ms across 30 requests with zero Worker errors; earlier failed CPU samples are preserved. This admits neither larger catalogs nor production rollout. Final isolated QA is HELD revision 15 with all APIs off, six revoked credentials, zero holds, and three unchanged archives totaling 4,995 bytes.

## Independent-review corrections

All eight accepted Astra findings have implementations and source-linked verification in the [correction checkpoint](../doc/validation/cloud-astra-fixes-20261021/README.md): native Forget/staging integrity, cross-generation historical provenance, explicit one-time OFF, generation-conditional terminal-auth stopping, bounded truthful cancellation and exact preview/filter behavior. The local correction suites passed 363 backend, 353 JVM and 191 Android tests, plus six Forget and three historical actual process-kill scenarios. This is not a new live deployment or broad release approval. The [completed independent whole-feature Claude review](../doc/reviews/cloud-sync-v1-claude-20261021/README.md) confirms the core corrections but identifies nine further issues, including three normal-use release blockers. Production remains on HOLD. Claude wrote no implementation code and ran no runtime tests; its source review is not a substitute for integrated live/background gates.

## Claude correction checkpoint

[All nine Claude findings have implementation/local verification](../doc/reviews/cloud-sync-v1-claude-20261021/correction-resolution.md). The [evidence checkpoint](../doc/validation/cloud-claude-fixes-20261021/README.md) records 372 backend passes, 353 JVM passes, a 213-test native run, a 52-test final controller follow-up and five actual host process-kill scenarios. Counts overlap where stated; source/APK identities are retained. No live QA was reopened or deployment performed. Integrated live R2/native recovery/cancellation, representative CPU, separate-device/background acceptance and independent changed-code verification remain release gates.

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

## Daily budgets and housekeeping

[Budget and cleanup design/runbook](../doc/architecture/cloud-budgets-v1.md) documents external CONTROL reservations, UTC rollover, indexed row metering, statement and size admission, operator accounting, and the bounded cleanup cursor. Public token lookup is admitted and metered. Unknown writes retain their request fences and reservations without expiry. Missing budget schema or policy refuses requests.

The scheduled cleanup handler requires both an explicit cron trigger and `ENABLE_HOUSEKEEPING=true`; generated configurations keep it disabled. `node operator/cli.mjs housekeep --scope /private/current-scope.json` runs one authenticated, budgeted pass only while the ordinary fence is OPEN. It never receives R2 authority. Existing QA requires transactional DATA 0006 / CONTROL 0003 upgrades, followed by explicit policy initialization; do not run the complete migration directories against raw-provisioned resources.

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

Suites: `apply-before-room`, `apply-after-room`, `apply-before-tags`, `identity`, `enrollment`, and `reconnect-journal`, `reconnect-archived-outbox`, `reconnect-witness`, `reconnect-metadata`, `reconnect-connection`. The reconnection suites terminate and resume the encrypted transition at each durable boundary. The first three exercise the actual native apply coordinator. They are not proof of cloud transport recovery or sudden power-loss durability. Without the explicit host driver, the probe test methods skip rather than fabricate coverage.

## Deployment and live synthetic test

`cloud/scripts/wrangler.sh` selects the named `newpipe-cloud` OAuth profile and pins the dedicated Ritorhymes account, clearing conflicting credential environment overrides. A named profile is necessary on hosts where legacy `~/.wrangler` takes precedence over `XDG_CONFIG_HOME`; the wrapper does not use or replace another project's default login. `wrangler.jsonc` has no secrets and targets only `newpipe-cloud-sync-v1` / D1 `bde936ac-caa7-42bb-a931-208118a8cd6a`. Set up that profile through Wrangler's supported authentication flow and verify the account before remote writes; do not copy credentials into this repository.

The checked-in legacy pair and its archives are inspection-only: do not enable that configuration or use `scripts/live-smoke.mjs` to mutate it. Current validation uses an explicit private configuration for `newpipe-fence-qa-20261020`, its separate CONTROL database and authenticated operator. Follow the [operator runbook](../doc/architecture/cloud-operator-recovery.md), [budget upgrade runbook](../doc/architecture/cloud-budgets-v1.md), and exact [closed candidate state](../doc/validation/cloud-v1-candidate-20261021/README.md). The tested public profile remains 64 KiB/eight pages. Never clear an initialized catalog or native app data merely to rerun an initial-enrollment fixture.

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

The newer `test-support/run-live-continuation.py` joins a retained accepted catalog and drives two independent installations through actual emulator network loss, restart, automatic convergence, conflict resolution and one-time restore. It requires the exact emulator/AVD/source commit, private scoped credentials and `--allow-emulator-network-outage`. It restores original networking and removes transient credentials, but the operator still must close/revoke QA afterward. The [16-step evidence](../doc/validation/cloud-live-budget-android-20261021/README.md) is not a two-hardware-device or Doze proof. The original initial scenario still requires pristine, unconnected QA installations and must not be forced by clearing data.

Everyday synchronization performs no R2 operations. Explicit recovery uses the
private `RECOVERY` binding with a separate default-off API gate. Do not add
archive-per-sync behavior.

## Remaining release gates

Independent whole-feature review; actual separate-device and longer background/Doze/OEM acceptance; measured admission beyond the eight-page / 64 KiB deployed catalog; applicable full live backup/restore/replacement checks on the selected integrated rollout candidate; and explicit release authorization. Daily budgets/cleanup, real reconnect restarts, the current two-installation offline/conflict matrix, and the three-theme rotation/credential scenarios have executed and are not still compile-only gates. Historical validation checkpoints under `doc/validation/` apply only to their exact commits and stated device/page sizes.

## Explicit recovery acceptance

`ENABLE_RECOVERY_API` defaults false alongside `ENABLE_CATALOG_API`. Migration `0002_recovery.sql` supplies the three-slot index; provisioning/reconciliation of its control row is an operator action after physical object and multipart inventory. Never automatically rebuild that row from an ordinary HTTP request. `MAX_RECOVERY_BYTES` is deliberately only 1 MiB for the dedicated small live QA; the protocol hard limit does not admit larger production loads.

The native controller exports/validates device ZIPs or the exact downloaded D1 catalog, records the approved payload before reservation, and resolves the same operation after interruption. Restores stay local and leave sync off. See [protocol](protocol/recovery-v1.md) for verification and cleanup rules.

The historical `test-support/run-live-recovery.py` driver must have its origin/scope checked before reuse; legacy resource pairs remain inspection-only. It uses two known isolated QA packages and a private synthetic credential file. It requires `--allow-phone` for a physical device. Provision/enable and subsequent revoke/disable/known-data cleanup remain explicit operator steps; the driver never clears app data or deploys by itself.
