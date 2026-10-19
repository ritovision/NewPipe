# Native cloud read-side review — adjudication and fix contract

Date: 2026-10-19. Base: d1aa5936c. Source reviewer: Claude Opus Extra High, candidate 01fac87a7, completed 07:10 America/New_York, REQUEST_CHANGES: zero P1, three P2. [Unmodified report](cloud-native-claude-completed-2026-10-19.md), SHA-256 a2401ffff545d9a84f662350f059d62497e8b16f2ef288e58f2cf7f8536528e3.

The owner authorized adjudication, corrections and **Astra final review**, not another Claude run. Finish this bounded native snapshot/identity/sparse-order checkpoint; do not claim the unimplemented cloud transport/UI/R2 is complete.

## Decisions before changes

- **P2-1 accepted; partly already fixed.** Commit 6644b98e2 corrected actual v1/v2 fixtures and the subsequent 198 native tests passed. Add the missing v3 tag backup through full prepare/validate/install and explicit runtime-map scrub assertions. Never make CREATE TABLE permissive to accommodate a mislabeled fixture.
- **P2-2 accepted.** CatalogOpenHelperFactory delegates the framework corruption handler and does not prevent main-database recreation while tags survive. Store an optional incarnation plus observed playlists sqlite_sequence high-water mark in the atomic Library binding, matched to tag-context incarnation. Native initialization of a recreated Library writes a new unmarked binding; ordinary binding copy/same-Library rebind preserves the marker. Capture cannot enroll, reset, or silently relink. Explicit enrollment requires no existing context; legacy unanchored contexts fail closed for explicit reset/re-enrollment. High-water is an additional regression check, not cryptographic anti-tamper detection. Main Room remains 9001; tag schema remains 4 (no new SQL schema). Backup staging strips these runtime binding fields along with tag mappings.
- **P2-3 accepted.** Center initial labels, use capped fixed strides for head/tail growth, and redistribute only a bounded local window for an exhausted interior gap. Reject a plan that cannot fit the explicit local/write budget instead of falling back to rewriting the whole library. Add repeated 20k-entry append/prepend/interior write-count regressions, not only correctness round trips.
- **Optional 1 accepted.** Ignore nonportable protected-playlist artwork for capture; preserve all native data. Ordinary dangling permanent artwork still fails with its playlist ID.
- **Optional 2 accepted.** Separate enroll(scope) from capture(scope, requiredIncarnation), so restore cannot silently enroll.
- **Optional 3 accepted.** Include native duration/stream type in display hints, outside semantic fingerprints.
- **Optional 4 accepted.** Commit a reproducible, explicit-serial/package/user host process-death driver. It may force-stop only the isolated QA package and requires the test marker before doing so.
- **Optional 5/6 accepted.** Assert exact failure reasons; test pruning rollback, parity with MergedPlaylistManager, and unchanged native SQL content before/after scale capture.
- **Optional 7/8 accepted.** Document delete-before-put in one revision-fenced transaction; rank is position, not occurrence identity. Retain explicit no-downgrade/schema-4 backup warning.

## Atomicity of binding anchor and maps

A capture/enrollment validates all content before persisting binding metadata. Binding metadata is written and read back before tag-transaction commit under the shared writer barrier. A failure after that file write can leave a marker ahead of an absent/older context, but cannot bind an old mapping to a new catalog: mismatches block; explicit enrollment after an absent context replaces the orphan marker. An unchanged context/capture never rotates incarnation. A high-water increase before tag commit is conservative because capture changes no Room sequence and already observes a committed Room state. This is fail-closed cross-file coordination, not a claim of transactional file+SQLite atomicity. Failure/process-death tests must exercise both orderings.

## Verification inputs

The guarded helper and NewPipeDatabase source were inspected, not omitted as in the original packet. Android's SupportSQLiteOpenHelper.Callback default corruption behavior deletes the affected database file; sqlite_sequence is per database and not cross-file identity. AtomicFile protects its own file, not a database+file transaction. Official references: https://developer.android.com/reference/androidx/sqlite/db/SupportSQLiteOpenHelper.Callback ; https://www.sqlite.org/autoinc.html ; https://developer.android.com/reference/android/util/AtomicFile .

## Required closure

Executed backend/JVM/native regressions, host-driven process-death evidence, immutable Astra read-only candidate with full relevant dependency/driver coverage, finding adjudication, clean pushed checkpoint. Results and verdict must be filled only from actual execution. No cloud resources, physical phone or existing AI gateway changes in this fix pass.


## Astra first-pass disposition

Astra Extra High reviewed candidate 01a5c022a with full dependency packet and base diff, reported **no confirmed P1/P2 findings** and **APPROVE_FIX_CHECKPOINT**. [Unmodified report](cloud-native-fixes-astra-xhigh-r1-2026-10-19.md). Both optional suggestions are accepted: invalidate the legacy helper's old binding before moving the new main database (its active UI reachability was not established), and assert cumulative interior writes plus uneven-label/mixed-base workloads. No full-library minimum-edit guarantee for arbitrary duplicate reorders is introduced.

During native execution the Android API35 16KiB runtime SIGSEGV occurred in ArtMethod lookup while the newly added test-only roomContentDigest helper was executing, before scale capture. Evidence identifies the failing frame but not a proven platform/compiler root cause. Replace the nested collection digest with a small test-only Java streaming SQL digest, preserving every table/cell/type and adding its own value/null sensitivity test. The database-preservation check is not disabled. Final native rerun and a bounded Astra delta review must cover these subsequent changes.


## Final Astra source verdict

The supplemental final Astra Extra High review covers candidate **5dba4d5cfae477d1fbe5fd7e33f2f95314f43564**, including legacy binding-before-main ordering, the Java streaming test digest and strengthened cumulative/mixed ordering tests. It reports **no confirmed substantive P1/P2 findings** and **APPROVE_FINAL_FIX_CHECKPOINT**. [Unmodified final report](cloud-native-fixes-astra-xhigh-final-2026-10-19.md), SHA-256 ce33d2b3cdf27e23e707419b88a9a4b036a579f550964a7bc51bc699b2cd5e2c. No code, tests, builds or edits were delegated; both Astra reviews inspected immutable source only. No Claude run was started for this fix pass.

Astra appropriately leaves actual test execution, OS/power-loss behavior, crash root cause and legacy helper reachability unverified. Those are not source-approval claims. Actual test evidence must be read separately, and the 16KiB system_server ART crash is not converted into a pass or claimed fixed by the helper change.


## Executed closure

Final source 5dba4d5cf: **140 backend, 327 JVM, 211 standard-page native tests passed**; both committed actual process-kill probes passed on the same source. All three P2 findings are closed for this read-side checkpoint. [Evidence](../validation/cloud-native-fixes-20261019/README.md) explicitly retains the earlier QA ART crash, final-candidate 16KiB system_server crash and standard-emulator first-install ANR. Final 4KiB native acceptance does not erase those failures or establish 16KiB compatibility. No production/cloud rollout is claimed; full remote apply and the rest of Cloud & Sync remain separate milestones.
