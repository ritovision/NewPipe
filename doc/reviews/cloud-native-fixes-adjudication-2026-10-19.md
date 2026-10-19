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
