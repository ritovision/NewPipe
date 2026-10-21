# Claude findings: correction checkpoints — 2026-10-21

Status: **all nine Claude corrections implemented with local/native verification completed. Not release approval.** Original independent review/adjudication: `doc/reviews/cloud-sync-v1-claude-20261021/`. No Claude coding or coding subagent was used. This cycle has not queried, deployed, migrated or reopened live Cloudflare QA, changed production, installed on a physical phone, merged a branch, upgraded a plan or cleared app data.

## Source checkpoints

- `a7d60141153564fd434717dfaf86d37be73f4f63`: initial corrections for the three blockers (001–003), built and executed native evidence below.
- `84c47ae6a8b7f7e80638a9ff7eca273f5e4caca5`: bounded cancellation/race handling and remaining lifecycle/preview/staging implementations. Broader runtime verification is separate from compile success.

## Correction contracts

| Claude finding | Corrected invariant and test approach |
| --- | --- |
| 001 — permanent fence from invalid chunks | A committing CHECK now requires every new chunk ID to follow all previously accepted IDs. This proves global order/uniqueness before the unique insert. The original valid fixture reproduced a permanent ticket for a cross-chunk duplicate. Corrected production-router/local D1 tests reject repeated invalid chunks, leave prior rows unchanged, settle all reservations and reach HELD maintenance. Exact retransmissions remain idempotent; unknown-write classification is unchanged. |
| 002 — irreversible unpair before final admission | Real AI runtime now offers reversible admission quiescence and command draining without altering persisted paired clients. Final native admission/inventory validation precedes an irreversible lease commit that freezes native restore and unpairs. Refusal closes the lease and resumes the latest durable policy, never a stale captured choice. No drain waits while a native/Room lock is held. Native tests use actual synthetic paired state and a test-owned Android Keystore key, assert exact pairing/native preservation on final refusal, and verify successful restore still unpairs/requires restart. |
| 003 — stale upload retry loop | Exact definitive head/expiry refusals resolve-or-retire the same frozen sequence/digest. A racing COMMITTED receipt is acknowledged; missing receipt/timeout never clears pending state. Automatic sync makes at most one fresh re-evaluation after retirement, reaching conflict or a newly admitted upload instead of resending forever. Network uncertainty remains pending. |
| 004 — rejected-device dead end | Explicit local detach archives encrypted connection state and every pending outbox/recovery/replacement record. An atomic private journal makes independent archive/native cleanup boundaries resumable. No HTTP cancellation or CONTROL release occurs. Native contents survive; the old device identity cannot be re-enrolled locally. Fresh authority starts OFF and requires a new preview. The action is restricted to recorded terminal credential/device-operation identity rejection, not a transient outage. Its confirmation explicitly warns that earlier remote work may have committed or still require operator reconciliation. |
| 005 — hint-only sync wakeups | Room observer no longer watches hint/history/feed-only `streams` invalidation. Playlist/membership notifications, explicit tag changes and periodic remote polling remain. The native regression counts real invalidation scheduling tickets for repeated metadata-only updates versus a semantic playlist edit. No battery savings or projected daily-cost figure is represented as measured. |
| 006 — repeated provider abort dependency | Cancellation separates ABORTING claim, proven multipart consumption, and final removal. It durably records `abort_fenced=1` before returning explicit progress and skips future abort calls once consumption is proven. A synthetic provider that throws on repeat successful-abort is exercised through the production router; the operation reaches CANCELED with one actual abort and zero settled holds. This is not a live R2 behavior measurement. Arbitrary failed aborts remain unknown/quarantined. |
| 007 — ordinary rotation cancellation envelope | Ordinary recovery abort/abandon now return explicit `cleanupPending` between bounded phases. Android continues the same identity at most four requests, keeping the marker when not terminal. Rotation, claim, abort-proof and final removal never borrow an unbounded SQL allowance. Both routes from three-slot ROTATING preserve two survivors and stay within 27 DATA + five accounting positions. |
| 008 — preview/toggle consent | Preparing/canceling replacement or recovery previews preserves the toggle/generation. Replacement success/cancellation also retains the owner's current toggle; durable pending replacement blocks automatic operations until resolved. Confirmed historical/native local restore turns OFF at its final admission/commit boundary. User-visible descriptions match these different operations. |
| 009 — orphaned persistent staging | Import/export staging has a process-lifetime OS ownership lock. A new owner sweeps only orphan `tag-import-*`/`tag-export-*` directories before allocating candidates; repeated same-process initialization never deletes active previews. Other live owners are refused. Cloud pending journals, retained recovery ZIPs and independent database swap journals are never swept. Cold-process cleanup and repeat preparation are tested separately from exception simulation. |

## Executed initial blocker evidence

At `a7d601411`, app and instrumentation APKs built. **70 native tests passed**, zero failures, 103.127 seconds (`blockers-native-70.log` and exact APK hashes). Real Android databases and paired runtime are exercised; remote peers are deterministic fakes, not live services. One first instrumentation launch exited before executing any test with `Process crashed`; the same installed binaries passed after force-stopping only the QA package and retrying. No data clear or weaker assertion was used. No root cause was established for that first launch.

Local adversarial upload tests: **3/3 passed**, 3.451 seconds. Their old-source negative run is retained; the preliminary unsuitable fixture is not used as defect evidence. Both duplicate and descending chunks are refused with zero pending CONTROL capacity afterward.

Cancellation negative probes reproduced the ordinary rotation SQL refusal and repeated-abort provider-dependency case. A full-suite run additionally exposed a concurrency regression in the first phased implementation: stale claim progress could auto-continue after a competing quarantine. The correction rechecks current operation/slot state after the claim; it does not weaken the existing race assertion. The **12-case focused suite passed**, 16.609 seconds, including that unchanged race test, eight replacement states/routes and all three new probes. These are local Miniflare/workerd D1/R2 tests, not live deployment.

## Remaining verification and release scope

The executed results below distinguish builds, local backend/JVM tests, Android instrumentation and real host kills. None substitutes for live provider or rollout acceptance. Existing live QA stays intentionally closed. A newly corrected integrated Cloudflare R2/native backup/restore/replacement cycle, representative mutation CPU, separate-device/background/Doze behavior and changed-code independent verification remain separate release gates. Earlier CPU/head-heavy samples do not certify these new cancellation paths.

## Final local backend and JVM execution

The full sequential backend suite after the concurrent-quarantine correction passed **372/372**, zero failures/skips, 385.219 seconds (`backend-372.log`). This includes both malformed cross-chunk guards, the original unknown-write/fence/budget tests, ordinary and replacement cancellation routes, simulated provider repeat-abort rejection, and existing recovery/reopen/housekeeping races. It is the mixed pure-logic/local workerd-Miniflare suite, not 372 deployed Cloudflare tests. TypeScript check passed.

The integrated app/test build completed and the JVM suite passed **353/353 across 45 suites**, zero failures/errors/skips (`jvm-353.json`). The retained failed/intermediate run hashes and explanations are in `superseded-runs.json`; failed runs are not counted as acceptance. The backend implementation at `84c47ae6a` is unchanged at `439af53cc`; that latter commit corrects native persistence of the rejected-identity flag and adds a replay-identity regression.

## Integrated Android and actual process-death verification

At `439af53cca239a9bdf37c84790436c0fc695d0f0`, the full selected native suite passed **213/213**, zero failures/skips, 442.534 seconds (`native-213.log`, `native-213-apks.json`). It includes real Room/tag stores, native import/export, actual synthetic AI pairing/refusal, real WorkManager and Room invalidation observation, current-cloud mode transitions, current replacement/recovery controllers, native apply and migration regressions, and the actual retained recovery DialogFragment rotation test. HTTP peers are synthetic where specified; this is not live Cloudflare or two-device/Doze acceptance.

The intermediate 212-test run had five detachment failures because the recorded rejected-identity flag was not persisted. Fixing that wiring (without weakening assertions), and adding an explicit conflicting-device replay identity test, produced the full 213-pass run. Transient offline failure still cannot authorize local detach. All failed-run identities remain distinguishable from passes.

**Five new host-driven force-stop/restart scenarios passed** against the same APK pair: detachment after journal persistence, after outbox archival, after independent metadata commit, after active connection removal; plus orphan import/export staging after process death. The fresh processes verify exact native digests, unchanged tag definitions, retained archived pending-file byte hashes, no detach network call, fresh-device-only OFF enrollment, and cleanup of only orphan temporary directories while the retained safety ZIP survives. The staging case then prepares and installs a new valid native import successfully. Each scenario's three raw logs and SHA-256 identities are retained.

These are actual process kills, **not power-loss testing**. The original staging result's generic `scope` text was inherited from the older driver; its `suite=staging`, `native_staging_cleanup_tested=true`, test class/marker and logs identify the actual scenario. That descriptive-label issue does not change which code ran; the raw output is preserved, not silently rewritten.

## Last production delta and exact tested candidate

Implementation checkpoint: **`0f9988b001248050097d96972ba1e86d1b312c5e`**. Relative to the 213-test / five-process-death source `439af53cc`, the only production difference is the local-detachment preflight in `CloudSyncController.kt`: a legitimate clean native import may have removed cloud identity while retaining a rejected connection/outbox. Detach now accepts that clean unenrolled state, still recovers/verifies native journals, and rejects a different extant cloud identity instead of requiring old cloud enrollment merely to disconnect. No backend, AI-runtime, native staging or post-journal cleanup logic changed in that follow-up.

The **complete CloudSyncControllerTest class passed 52/52** on this last app/test APK pair, zero failures/skips, 88.454 seconds. It includes the additional post-native-restore metadata scenario and all other controller/scheduler/detachment cases (`final-controller-52.log`, `final-apks.json`). The scenario constructs the exact clean metadata state produced by import; it is not misrepresented as another end-to-end live ZIP restore. The 52 tests overlap the earlier 213; do not add them as unique cases or claim the entire 214-test selection was rerun on the final APK. All 353 JVM tests also executed on this follow-up.

### Implementer closeout

All nine findings have a bounded correction and the appropriate local or native regression. The original three blockers are corrected in code and tested; this is the implementer's resolution, not a new independent review or permission to activate production. No assertion protecting uncertainty, current authorization, native content, receipt identity or concurrent quarantine was removed to obtain a pass. The 32-SQL public ceiling and conservative daily budget semantics are unchanged.

The current APKs remain in the normal build outputs; no duplicate APK archive was committed. Only compact test logs, hashes, source identities and results are retained here. Raw Windows ADB log endings and negative reproduction output remain intact. `evidence-sha256.json` binds the retained files. The independent review documents remain unchanged; current disposition is linked in `correction-resolution.md` beside that report.

### Still outside this correction checkpoint

- A newly authorized integrated **live** Cloudflare recovery/native-backup/restore/replacement cycle, including corrected bounded cancellation and archive preservation.
- Representative minified deployed mutation/cleanup CPU and provider behavior. Old head-heavy CPU measurements are not recertified by these local tests.
- Actual separate-device, prolonged offline, Doze/OEM/battery behavior and broader human-operated accessibility/visual acceptance, including the new local-detach confirmation.
- Independent review of the changed implementation, final release decision, merge and explicit production activation authorization.

The live QA's last prior retained state remains intentionally closed (HELD revision 15, APIs/housekeeping disabled, six revoked credentials and three archives totaling 4,995 bytes). **No fresh live state attestation is claimed in this cycle.** No Claude or other agent wrote these fixes. No emulator other than the explicitly checked Windows Pixel 9 was modified, and the physical phone was not used.
