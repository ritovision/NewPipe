# NewPipe Cloud & Sync v1 — final integrated acceptance checkpoint

**2026-10-21 · Technical release verdict: HOLD — remaining scoped acceptance and deployed CPU target.**

This checkpoint follows the final independent [Astra review](../../reviews/cloud-sync-v1-astra-final-20261021/README.md), which found **zero new code-level blockers** at source **`4871f3d8bf75f356a4b83b97cbbc3eef2d110b63`**. It does not rescind that source review or claim production readiness. The corrected production Android/Worker implementation remains unchanged. The reviewed integration ran against a fresh *synthetic-only* Cloudflare resource pair, its own R2 bucket, and isolated QA Android application IDs.

## Executive verification

| Verification | Observed result | Honest coverage boundary |
| --- | --- | --- |
| Initial live catalog sync, two separate QA app installations | **PASS — 8/8** sequential Android steps | Both on the emulator; initial launcher ANR before any test was retried without data clear. Exact accepted catalog fingerprints agree. |
| Automatic upload/pull and explicit OFF + one-time restore | **PASS** as part of the 8 steps | Actual HTTPS Worker + D1. Confirmed one-time restore ends OFF. |
| Real Worker lost/uncertain catalog write recovery | **PASS** | `CLOUD_NETWORK_UNAVAILABLE` left a frozen outbox; subsequent actual WorkManager step resolved its exact identity. Same content is acknowledged on remote HEAD; no outbox left. Original postcheck misread a missing folder's `ls` exit code; native `OK (1 test)` and explicit recheck are both retained. |
| Real R2 backup, catalog restore, rotation, protected replacement, native ZIP restore | **PASS — 20/20** sequenced step records | Includes four R2 uploads, a guarded three-slot eviction, exact outgoing archive on replacement, diverged-device pause, older catalog restore, full native ZIP import and restart digest check. The network recovery step composes an independently passing WorkManager run, not a second native `changeCloud` execution. |
| On-phone controller and conflict handling (synthetic peer) | **PASS — 52/52** | Actual Pixel 7 / Android 17; real native QA data, offline fake HTTP peer, no live Cloudflare. |
| On-phone native backup, restore, replacement, migrations and stores | **PASS — 161/161** | Actual Pixel 7; isolated native/Keystore fixtures, no personal NewPipe data. **213/213** total selected phone tests, disjoint from the 52 controller tests. |
| Two-client **live conflicting edits** | **INCOMPLETE** | The test emulator failed during `verifyConflict` startup after four successfully executed steps, then had another startup ANR on exact resumption. Neither ANR executed the conflict assertion. No loss or correctness conclusion is inferred. A subsequent phone-vs-emulator authenticated check was blocked by security controls and was **not** run. |
| Deployed representative CPU | **FAIL against current target** | Cloudflare Analytics reports **20.263 ms p95** CPU, **8.041 ms p50**, **22.937 ms p99** across **198 mixed real Worker requests**; agreed p95 target is **≤8 ms**. These are provider analytics, not localhost timings. The mix is not stratified enough to attribute cost to a particular route. |
| Budget / fence / recovery inventory | **PASS for no outstanding holds; formal closure OPEN** | CONTROL read-only result: 29,895 spent reads, 6,878 spent writes, zero held reads/writes/growth; fence **OPEN**, zero tickets/running, not **HELD**; three R2 archives READY. |
| Public API and production isolation | **PASS for API shutdown** | New isolated and old closed QA endpoints both respond `503 CLOUD_API_DISABLED`. No production deployment, branch merge or phone original app modification. |

## Executed live recovery sequence

The exact 20-step host result in [`recovery-live-after-network/results.json`](recovery-live-after-network/results.json) is linked to retained step logs from four directories. Initial `connect`, `enable`, `deviceBackup` passed in [`recovery-live-locked/`](recovery-live-locked/); the second `catalogBackup` passed in [`recovery-live-serialized/`](recovery-live-serialized/); the expected `changeCloud` publish received an actual network error and completed via independently passing [`network-reconcile-live/`](network-reconcile-live/). The final 15 steps are in [`recovery-live-after-network/`](recovery-live-after-network/). Each result's SHA-256 was matched to its executed log, including the host-driven synthetic dirty-branch process stop.

Three immutable READY survivors were verified immediately before shutdown; archive identifiers, kinds, digest and sizes are preserved in [`recovery-archive-survivors.json`](recovery-archive-survivors.json). Total retained compressed payload: **16,523 bytes**. Three-slot rotation and protected outgoing-copy behavior were checked against the selected archive identities, not only the number of records.

**No archive or database rollback, cleanup deletion, provider time-travel restore or service-generation reopen was conducted on the closed prior QA.** The fresh test pair remains available privately for operator recovery; its public catalog/recovery/replacement/housekeeping API switches are all OFF.

## Physical-phone acceptance

The Pixel 7 connected via Tailscale ADB, with Android SDK **37**, using scrcpy **4.1** on a genuine independent virtual display **ID 8, 1080 × 2160 / 420 dpi**. The scrcpy window uses the display's content, not a mirror of physical display 0. A separate `org.schabi.newpipe.debug.qacloudaccept20261021` APK was installed. The preexisting standard NewPipe and Cloud Sync QA APK paths were verified unchanged. No original app was uninstalled/updated; app data was not cleared.

Actual controller instrumentation passed **52/52** and native instrumentation passed **161/161**: see [`phone-controller-results.json`](phone-controller-results.json) / [`phone-controller-physical-hardware.log`](phone-controller-physical-hardware.log) and [`phone-native-integrated-results.json`](phone-native-integrated-results.json) / [`phone-native-integrated-hardware.log`](phone-native-integrated-hardware.log). All fixtures were synthetic and scoped to the new QA application package. **Do not imply the 213 native test cases also performed the real HTTPS conflict cycle.** The virtual-display settings screen verified the correct feature was present and initially disconnected.

## Failure and recovery ledger (preserved rather than erased)

1. The first emulator `initialize` launch exited as a startup ANR **before executing any test**. The same QA binaries then passed the initial eight-step live sequence without app-data reset. This is a suspected emulator startup/resource issue, not yet diagnosed to a blocked application thread.
2. A live conflict sequence passed its first four stages (two clients on the same accepted baseline, a real host-driven dirty local process stop, and an accepted remote edit). The fifth `verifyConflict` instrumentation launch failed with a startup ANR; exact recovery attempt also failed before its assertion. Both failed logs are retained in [`android-conflict/`](android-conflict/) and [`android-conflict-resumed/`](android-conflict-resumed/). **It is not a passing live conflict test.**
3. A background WorkManager operation raced the original synthetic recovery reconnect and subsequent R2 status call, yielding `CLOUD_OPERATION_BUSY`. The *QA test harness only* was serialized using the same operation gate; the unchanged production controller intentionally rejects concurrent operations. Its patch is saved as [`qa-harness-only.patch`](qa-harness-only.patch), not merged into application code.
4. A real catalog upload then received `CLOUD_NETWORK_UNAVAILABLE`. The frozen outbox remained on the QA device; actual WorkManager recovery committed its exact target, remote HEAD was verified, and the outbox disappeared. This is a **passing uncertain-response recovery**, not an unresolved leaked write.
5. The historical Android `verifyConflict` runtime was not rerun successfully on separate physical hardware. The **52** on-phone controller tests exercise deterministic competing-head and definitive-refusal branches, but not this missing live assertion.
6. Formal operator fence close from **OPEN** to **HELD** was **not completed**. A tool safety restriction blocked that remote action; no alternative path was used. The authenticated live phone-vs-emulator probe was also blocked. Do not call either action complete.

## Deployed CPU and budget limits

The real corrected minified Worker version had no reported execution errors in the Analytics sample, but **198 requests** yielded p95 **20.263 ms**, above the 8 ms acceptance threshold. An earlier **43-request** subsection had p95 **16.616 ms**. These include both reads and recovery/mutation requests; their distribution is not a per-route benchmark. The earlier head-heavy 30-request sample (6.950 ms p95) predates this accepted live composition and **cannot override** the failure. Provider data is retained in [`worker-cpu-metrics.json`](worker-cpu-metrics.json) and [`worker-cpu-early.json`](worker-cpu-early.json). Raw Worker tail was retained privately but **not committed** because it contains client IP/header telemetry.

The retained read-only CONTROL snapshot at shutoff showed `ACTIVE`, 29,895 reads, 6,878 writes, 0 held reads, 0 held writes, 0 held growth; no ticket backlog. The 2M read / 50K write per UTC day and 32 SQL statements/request policy remain implemented, but those static limits do not establish CPU compliance. No blanket SQL cap increase or unmetered cleanup was used.

## Required bounded closeout (no new general review loop)

1. **Deployed CPU:** Measure the named catalog/commit/recovery routes separately at the admitted eight-page/64-KiB and 1-MiB profiles. Identify the expensive route(s), make the smallest justified change if needed, rerun directly affected backend/Android suites and the **deployed** p95. Never claim the existing 20.263 ms result meets the 8 ms goal.
2. **Actual phone-vs-emulator conflicting edits:** Finish the missing competing-head/lost-reply test on authorized isolated hardware, preserving frozen outbox/backup identities. The QA-specific live authorization/session must be permitted; do not bypass tool security controls. Existing real Pixel 7 tests already cover deterministic local scenarios.
3. **External operator close:** Complete the separately supported OPEN → DRAINING → HELD procedure with zero outstanding tickets and exact next-generation ID. Re-attest the three READY R2 copies and verify both public endpoints remain disabled. An `OPEN` fence is *not* a completed exclusive maintenance close.
4. **Remaining narrow acceptance:** Explicit live cancellation/provider unknowns and service-generation recovery/reopen, along with longer independent-device background/Doze and the full human-accessibility/visual matrix, remain separately required for production release. Reuse all accepted proof above rather than start the previous reviews over.

**Recommendation: HOLD** until those specific acceptance gates pass and the owner authorizes merge/activation. No merge or release activation was performed. No additional broad architectural review is requested by this checkpoint.

All included test artifacts have SHA-256 hashes in [`evidence-sha256.json`](evidence-sha256.json). This evidence package intentionally excludes APKs, keys, credentials, raw browser telemetry, personal UI captures and large build directories.
