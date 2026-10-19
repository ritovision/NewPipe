# Native cloud review fixes — closed checkpoint

**Date:** 2026-10-19.
**Final tested and source-reviewed implementation:** `5dba4d5cfae477d1fbe5fd7e33f2f95314f43564`.
**Initial corrections:** `01a5c022a3e30fbdb8541685d94ab6daebd8c978`.
**Scope:** Close the native catalog-capture/identity/sparse-ordering findings. **Not completion of all M1 or live Cloud & Sync.**
**Disposition:** Three Claude P2 findings adjudicated, justified optional fixes implemented, final Astra source approval obtained, standard-page native regression and process-death verification passed. Special 16 KiB emulator acceptance remains unverified after runtime/system crashes; no release or compatibility guarantee is inferred.

## Independent review

Claude's completed report had zero P1 and three P2 findings. P2-1's old v1/v2 fixture correction was already in the prior baseline; this pass also adds the missing v3 ZIP through prepare/sanitize/install. P2-2's database-lifetime issue and P2-3's repeated-append rewrite amplification are fixed. [Adjudication](../../reviews/cloud-native-fixes-adjudication-2026-10-19.md).

**Astra Extra High** reviewed the frozen full dependency packet and previous-base diff, finding **no confirmed P1/P2** and `APPROVE_FIX_CHECKPOINT`. Its two optional suggestions were accepted. A second, bounded Astra delta review then approved the exact final source with **no confirmed substantive P1/P2** and **`APPROVE_FINAL_FIX_CHECKPOINT`**. Reviewers inspected source only; they neither wrote code nor ran tests. No additional Claude review was launched.

[First Astra report](../../reviews/cloud-native-fixes-astra-xhigh-r1-2026-10-19.md) · [Final Astra report](../../reviews/cloud-native-fixes-astra-xhigh-final-2026-10-19.md) · [Exact candidates/session IDs/report hashes](review-results.json).

## Executed checks

| Check | Result | Evidence |
| --- | --- | --- |
| TypeScript strict check | Passed | [Type-check output](typecheck.log) |
| Backend and local D1 tests | **140/140 passed**, none skipped | [Backend output](backend-tests.log) |
| Full app JVM suite | **327/327 passed**, none skipped | [JVM results](jvm-results.json) |
| Android application/test builds and Java style | Passed; final Checkstyle has zero errors | [Build log](build.log), [scoped Kotlin format](format.log) |
| Exact native regression run | **211/211 passed**, none skipped, **103.030 seconds** | [Every executed case](native-results-final.json) |
| Focused digest sensitivity check | Passed on unchanged final APK after the initial startup ANR | [Focused native result](native-4k-digest-retry.log) |
| Actual process kill during identity transaction | Prepare passed; QA PID14649 deliberately killed after fsynced ready marker; original mapping verified in a fresh process | [Result](process-4k-identity/results.json), [prepare](process-4k-identity/prepare.log), [intentional interruption](process-4k-identity/interrupted.log), [verification](process-4k-identity/verify.log) |
| Actual process kill after binding persistence / before tag enrollment commit | Prepare passed; QA PID14808 deliberately killed after fsynced ready marker; capture refused uncommitted identity and explicit re-enrollment succeeded | [Result](process-4k-enrollment/results.json), [prepare](process-4k-enrollment/prepare.log), [intentional interruption](process-4k-enrollment/interrupted.log), [verification](process-4k-enrollment/verify.log) |
| Additional exhaustive sequence exercise | **9,680 transitions passed** across three-symbol small sequences with centered and densely packed labels | [Exercise result](exhaustive-sequence.log) |

The 211 native cases comprise **38 native capture/identity tests**, 8 portable cloud protocol groups, 16 backup/restore regressions, 16 tag repository tests, 35 Library tests, 19 Unavailable tests, 25 AI-tag tests, 53 AI catalog tests and 1 Room migration. The focused digest test is already represented in the broad run and is not double-counted. Intentionally killed tests are not counted as passing JUnit cases; their separately executed preparation/recovery phases are recorded as two process-death probes.

Final execution used primary **NotCodex**, existing `emulator-5576`, **Pixel_9_API_35 / API35 / 4 KiB pages / Android user0**. Separate package: `org.schabi.newpipe.debug.featurecloudsyncv120261019`. Before selecting it, the expected emulator/user/page size and absence of active instrumentation were verified. No new emulator or Android user was created, no existing emulator was stopped, no Android user switch was requested, and no physical phone or unrelated app data was touched.

## What was corrected

**Identity lifetime:** The atomic Library binding carries an optional paired incarnation and observed native playlist sequence high-water. Capture requires its match to the existing tag context and a nonregressing sequence; matching role IDs alone are insufficient. Recreated native Library initialization drops the marker, so unrelated newly allocated integer IDs cannot reuse surviving cloud mappings. Same-Library binding copies preserve the anchor. Explicit local enrollment is separate from required-incarnation capture and cannot replace an existing context.

**Crash behavior:** Marker persistence precedes tag mapping commit. It is not a cross-file ACID transaction: a failure can leave an unclaimed marker or conservative high-water, but capture refuses uncommitted identity rather than silently adopting it. Both exception injections and actual host-induced process death were executed. This does not prove the still-unimplemented cross-database remote record-apply coordinator or power-loss durability.

**Backups:** Staged standard backup/import strips runtime marker/map state. Genuine v1/v2/v3 tag migrations preserve content, and a v3 tag ZIP traverses full prepare/sanitize/install. The older helper also invalidates old bindings before replacing the main database; its production UI reachability was not established and no cross-file crash-atomic claim is added. Main Room remains **9001** and tag schema remains **4**.

**Ordering cost:** Initial ranks are centered with fixed head/tail stride. Interior exhaustion redistributes only a bounded local window or refuses the plan; there is no automatic full-library rewrite fallback. Delete-before-put in one exact-base-fenced transaction is explicit. Tests assert cumulative as well as per-edit costs.

**Read-side details:** Nonportable protected artwork does not block capture; ordinary dangling artwork errors identify the playlist. Hints include duration and stream type without changing semantics. Tests assert the exact error reason, pruning rollback, actual native merge-order parity, same-name distinct identities, and unchanged complete Room contents before/after scale capture.

## Measured synthetic workloads

Repeated edits on **20,000-entry** bases:

| Pattern | Edits | Maximum deletes+inserts for an edit | Cumulative deletes+inserts | Rebalances |
| --- | ---: | ---: | ---: | ---: |
| Append | 160 | 1 | 160 | 0 |
| Prepend | 160 | 1 | 160 | 0 |
| Repeated interior insertion | 160 | 17 | 192 | 2 |

These are planner mutation counts, not deployed D1 billing totals; indexes, staging and admission need their own budget. The mixed duplicate-base/uneven-label regression also asserts bounded cumulative writes. No globally minimal matching claim is made for arbitrary duplicate reorder patterns.

The native scale fixture has **20,000 streams, 100,000 ordinary playlist memberships and 20,000 tag assignments**; canonical capture bytes **6,402,851**. On the standard-page emulator, seeding took 20,705 ms, capture 13,146 ms, repeated capture 10,449 ms. [Native scale output](scale-4k-final.log). These are full off-main captures holding writer barriers—not per-edit/UI-latency acceptance or Worker CPU/R2 ZIP sizes. Automatic sync still needs incremental/coalesced work and measured budgets.

## Failed attempts and remaining environment uncertainty

1. On the initial fix candidate, the 16 KiB emulator's QA process SIGSEGV'd in ART method lookup while executing the newly added Kotlin test-only digest helper. Three tests completed before the fourth was aborted. The full content assertion was retained but its implementation changed to a Java streaming digest with a sensitivity regression. The stack identifies the faulting runtime/function, not a proven compiler or platform root cause.
2. The unchanged final candidate's 16 KiB rerun was aborted because **system_server itself SIGSEGV'd in ART MarkCompact GC**. No focused test completed. This is not a passing run, not a claimed fix for the special emulator, and not an assumed low-memory root cause. The emulator's framework restart returned its foreground user to 0; the coordinator did not switch users.
3. First launch on the standard-page emulator hit a **startup ANR before any test executed**. One unchanged-APK retry passed the digest check; the subsequent complete 211-case run and both process probes passed. No app-data clear, runtime flag or global timeout change was used to force a pass.

[Failed-attempt accounting/original hashes](failed-attempts.json) · [Scoped QA crash trace](crash-scoped-r1.log) · [Scoped system-server trace](system-server-scoped.log). Raw complete execution logs remain on their original hosts. Final acceptance applies to the stated standard-page environment; 16 KiB verification is still unresolved and must not be advertised as passing.

## Storage, source and deferred scope

[Final APK manifest](candidate-final.json) records hash-verified transfers and the original 5590 attempt target. The identical artifact hashes were reused on 5576; [native-results-final.json](native-results-final.json) records the actual passing environment. The two temporary primary-host APK copies were deleted after completed tests and verified against that manifest; [cleanup record](cleanup-final.json). Build host retains only the current app/test output pair. Source/review/evidence text is retained, not APK history. Existing emulators remain running; the phone, existing AI gateway and Cloudflare resources were not changed.

Repository log copies normalize line endings and trailing whitespace only. Original raw-log hashes and stored-copy hashes are recorded in [results](results.json). Nested process-probe result hashes refer to original host logs; use the root stored-evidence map for normalized repository copies.

**Closed:** this native read-side review-fix checkpoint, with source approval and the explicitly scoped executed validation.

**Still not implemented:** generic large sealed D1 publication/physical schema, remote ID adoption and crash-safe record apply, complete mutation capture, secure transport/credential settings, visual delta UI, minimal R2 recovery and true multi-device sync. No merge to `custom/dev` or release/production authorization is implied by this checkpoint.
