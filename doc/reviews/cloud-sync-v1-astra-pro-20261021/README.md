# NewPipe Cloud & Sync v1 — independent Astra Pro review

**Review date:** 2026-10-21

**Reviewer:** Astra Pro, independent review; no subagents

**Branch:** `feature/cloud-sync-v1-20261019`

**Implementation inspected:** [`1b7ab927a7840f3fc0e0b41cfd281199de77a6c8`](https://github.com/ritovision/NewPipe/commit/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8)

**Recommendation:** **HOLD RELEASE** pending the blocking corrections and targeted acceptance gates below.

## Overall assessment

The candidate implements a coherent, substantial v1: a local catalog remains the native authority, explicit enrollment establishes a cloud baseline, ordinary synchronization uses guarded D1 publication, R2 holds explicit recovery archives, and an independent CONTROL database fences maintenance and retains budget reservations. The review did not find a foundational defect requiring a replacement architecture. It did find important failures where those components meet.

There are **8 actionable findings: 0 Critical, 3 High, 3 Medium and 2 Low**. Five are release blockers for the specified v1 behavior: ASTRA-CS-001, 002, 003, 004 and 006. ASTRA-CS-005 is a reproducible, recoverable cancellation defect; it does not independently establish unsafe publication or justify disabling the statement limit. ASTRA-CS-007 and 008 are bounded preview defects that can be deferred with an explicit known-issue decision.

“Confirmed” in this report identifies a demonstrated code path or local reproduction. It does **not** mean that the issue was reproduced on a phone or Cloudflare. Each finding states its evidence level. ASTRA-CS-003 remains **High-confidence**, pending a native regression reproduction; no Android data was changed to test it.

## Highest-priority findings

| ID | Severity | Finding | Consequence |
| --- | --- | --- | --- |
| [ASTRA-CS-001](findings.md#astra-cs-001--forget-commits-baseline-deletion-before-the-room-witness-is-durable) | High | Forget commits deletion of the tag-side baseline before the Room witness deletion commits. | A process death can leave a surviving Room witness with no baseline, blocking subsequent guarded native/tag operations and ordinary reconnect/forget attempts. Existing content is not proved deleted. |
| [ASTRA-CS-002](findings.md#astra-cs-002--preserved-catalog-archives-cannot-be-restored-after-generation-recovery) | High | Operator recovery preserves archives with their original generation; Android historical restore demands the current generation. | The preserved cloud-catalog copies become unusable through the intended Android restore flow after fresh-generation reconnection. Archive bytes remain intact. |
| [ASTRA-CS-003](findings.md#astra-cs-003--eviction-of-prepared-native-files-can-change-the-approved-restore) | High | A prepared full native restore lives in evictable cache and does not retain the validated file inventory. | Loss of just the prepared tag file can be interpreted as an intentionally tagless stock backup, silently substituting current tags for the archive's tags. Missing prepared settings can also change the selected restore. |

The other blocking gaps are a one-time current-cloud restore that leaves synchronization enabled when it started enabled (ASTRA-CS-004), and permanent credential rejection that leaves automatic work scheduled (ASTRA-CS-006). These conflict with explicit mode/lifecycle requirements in the [v1 specification](../../specs/cloud-sync-v1.md).

The smallest corrections fit the present design: repair cross-database cleanup ordering; separate historical archive provenance from current authorization; bind native staging to its validated inventory; and make one-time/terminal-error state transitions durable and generation-aware. Removing scope guards or uncertainty fences globally would weaken otherwise useful safeguards.

## Evidence audit

The reported results are supported by retained evidence, with important scope limits:

| Claim | Independent audit result |
| --- | --- |
| 359 backend tests | The retained log reports 359 passes, no failures/skips. This is a **mixed Node/local workerd-Miniflare suite**, including pure logic, injected faults and real local D1/R2. It is not 359 deployed Cloudflare tests. |
| 351 Android JVM tests | The retained aggregate has 351 tests in 45 suites, no failures/errors/skips. These are JVM results, including non-cloud suites, not Android execution. Raw per-case XML was not used as the evidence basis. |
| 157 native tests | The retained instrumentation log ends with 157 executed passes. Real Android/Room/tag stores are exercised, while several controller tests use fake remote peers. |
| Three UI scenarios | The retained log reports three successful light/dark/black scenarios. Test code and sampled screenshots support main-preview rotation/layout and credential protections, not all recovery/replacement dialogs or full touch/accessibility acceptance. |
| Sixteen live Android steps | All 16 retained step-log hashes match. This used two isolated packages on **one emulator**, with actual HTTPS, WorkManager and an emulator network outage. |
| Five reconnection process deaths | All 15 retained prepare/interrupted/verify log hashes match their five results records. These are real host force-stop/restart checkpoints for reconnection, not five general native-apply or physical power-loss tests. |
| Deployed p95 CPU 6.950 ms / 30 requests | The retained JSON reports 6,950 microseconds, consistent with 6.950 ms. The public sample includes 26 head requests, one connect, one stage start, one retire and one denied start. It excludes chunk/seal/publication and R2 mutation traffic. Raw analytics observations were not retained here to independently recalculate the percentile. |

All **22 candidate evidence-file hashes** matched their manifest. Both present APK binaries also match the retained SHA-256 hashes and sizes. The inspected production source directories are unchanged from the candidate's `3f49b6acf7aef6905d07871f9bec679e707264fd` checkpoint. This establishes artifact consistency, not a hermetic rebuild or independent replay of all results.

The live Android source was `702f99f9f184261eb9540928597490ffcc14a1bc`; the reconnection-kill source was `ed4886a0d5abf64935e4e48b4ee9bed66d67542d`. Later changes and their narrower regression evidence are accounted for in [coverage-matrix.md](coverage-matrix.md). Earlier live backup/replacement results were not silently promoted to final integrated-candidate certification.

### Checks executed during this review

The review performed source/history/status inspection, evidence parsing and hashing, and two narrowly scoped local experiments:

- A disposable two-database SQLite process-exit experiment confirmed both unsafe cleanup boundaries underlying ASTRA-CS-001. This models the actual transaction ordering; it does not execute Room on Android.
- A temporary harness using the current production HTTP router and real local Miniflare D1/R2 reproduced ASTRA-CS-005 from both RESERVED and UPLOADING. Each first cancellation returned 503; an exact retry returned CANCELED. A diagnostic rerun counted 27 DATA statements before the next batch was refused, with five positions reserved for accounting. A final rerun with all six DATA migrations produced the same result. The first-response assertions failed as expected; these are defect reproductions, not passing acceptance tests.

No full suite, build, Android instrumentation or live HTTP test was rerun. No implementation file was changed.

## Remaining acceptance gaps

Even after the blocking fixes, the current evidence does not establish:

1. Full integrated, budget-enabled live native-backup/restore and protected-replacement operation on the selected corrected candidate.
2. Preserved generation-A cloud-catalog archive restoration on a freshly recovered/reconnected generation-B service.
3. Actual native process death at the Forget cleanup boundaries, or cache-loss handling between native restore preview and confirmation.
4. Separate-device background behavior across prolonged offline periods, Doze/OEM scheduling and restart; two packages on one host are narrower evidence.
5. CPU/resource behavior for a representative mutation/recovery mix or a catalog larger than the deployed eight-page/64 KiB profile.
6. Complete recovery/replacement-dialog rotation, search, large-change paging and accessibility acceptance.

These gaps are tests to plan, not permission to reopen the closed QA environment.

## Recommended next actions

1. Have the implementing session adjudicate the eight source-backed findings and first address the five release blockers.
2. Add the targeted regressions specified per finding; retain production authorization checks, the 32-statement public limit and fail-closed uncertainty behavior.
3. Fix ASTRA-CS-005 or explicitly document its bounded repeat-cancel workaround for a limited candidate. Resolve or record the two Low preview issues.
4. Execute the corrected paths against native components and, only in a separately authorized test cycle, a fully integrated isolated Cloudflare environment.
5. Reassess technical readiness against [release-gates.md](release-gates.md). Technical approval does not authorize a merge, deployment, cron activation, physical-phone installation or production activation.

## Operational preservation and report map

The last retained shutdown evidence reports HELD revision 15, zero pending tickets/actions/budget holds, six revoked QA credentials, three READY archives totaling 4,995 bytes, all public/housekeeping flags false, and production disabled. This review **did not query or modify live resources**, reopen QA, install software on Android, or alter application data. That state is supported by retained evidence, not a new live attestation.

- [findings.md](findings.md): prioritized scenarios, exact source references, fixes and regression expectations.
- [coverage-matrix.md](coverage-matrix.md): requirements, evidence levels, source provenance and missing coverage.
- [architecture-assessment.md](architecture-assessment.md): trust boundaries, invariants and cross-component risks.
- [release-gates.md](release-gates.md): concrete readiness conditions and permitted deferrals.
- [review-metadata.json](review-metadata.json): machine-readable review scope, results and limitations.
