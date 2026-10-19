# Cloud native capture / sparse ordering — executed checkpoint

**Date:** 2026-10-19.
**Production implementation:** `01fac87a762717e49f8a44a5c4d16a138d595af4`.
**Corrected test candidate:** `6644b98e231c884fd9cab2a64271ec8f6be459d4` (test-fixture and review-handoff changes only; app APK hash unchanged).
**Status:** Native read-side and sparse sequence foundations tested. **Not all of M1, not live sync, not final independent approval.**

## Implemented

- Read the real Room/tag catalog consistently under the shared writer barrier; no native catalog content edits or hidden protected-role repair.
- Persist portable UUID mappings across ordinary reopen, rename and reorder; distinguish same-named playlists; block scope/incarnation/binding changes rather than silently reuse identities.
- Tag schema **4**, preserving v1/v2/v3 definitions/assignments; main Room remains **9001**. Staged full and stock backup/import paths discard runtime maps without deleting live mappings or orphan tag data.
- Preserve exact Library/Unavailable order, ordinary duplicate occurrences, local relative playlist rank, permanent artwork and active assignments. Cached titles/artwork and remote bookmarks do not alter semantic fingerprints. Orphan assignments remain local and are separately reported.
- Compile exact sequence edits into sparse 128-bit TEXT ordering labels, including deterministic duplicate matching and explicitly budgeted gap-exhaustion rebalance. This is a planner, not a completed D1 catalog/staging service.

## Executed validation

| Check | Result | Evidence |
| --- | --- | --- |
| Strict TypeScript check | Passed | Candidate source and lockfile; `npm run check` |
| Backend tests | **133/133 passed**, including prior D1 transaction races and new sparse sequence/runtime D1 tests | [Node output](node-tests.log) |
| Full app JVM suite | **322/322 passed**, zero skipped | [JVM results](jvm-results.json) |
| Android builds | App and instrumentation passed | [Build log](build.log) |
| Scoped Kotlin formatter | Passed on changed native source/tests | [Formatter log](format.log) |
| Actual Android instrumentation | **198/198 passed**, zero skipped: 25 new capture tests, 8 previous cloud protocol groups, 16 backup, 16 tagging, 35 Library, 19 Unavailable, 25 AI-tag, 53 AI catalog, 1 Room migration | [Complete case-by-case results](native-results.json) |
| Actual process-death probe | Prepared valid identities, killed QA app during an open replacement transaction, reopened and verified the exact original mapping state | [Probe result](process-death-results.json), [prepare](process-prepare-r1.log), [expected interrupted run](process-interrupted-r1.log), [verification](process-verify-r1.log) |

Instrumentation ran on **primary NotCodex**, existing `emulator-5590`, AVD `MusicYtdlp_16K_Temp_API35`, API35, **16 KiB pages**, Android user11. Separate package `org.schabi.newpipe.debug.featurecloudsyncv120261019`; no Music/Sleep data or physical phone was touched. The emulator was verified idle before using the separate QA package. No third emulator was launched; existing 5576 and 5590 remain running as the owner requested. The full 198-test runner took **99.709 seconds**.

The process probe deliberately force-stopped only the QA package (PID28484) **after an fsynced marker confirmed the identity transaction was open**. The interrupted test is not counted as a passed test. Separate preparation and fresh-process verification passed. This establishes rollback of the single tag-database identity transaction, **not** crash-safe remote application across Room/tags/binding/maps; that larger coordinator is not implemented.

## Large synthetic data, not user data

The native file-backed scale fixture contains **20,000 streams, 100,000 ordinary playlist entries and 20,000 tag assignments**, plus the normal Library memberships. Capture produced **6,402,851 canonical bytes**. In the final broad run, database seeding took 23,359 ms, first capture 6,136 ms and repeated capture 4,865 ms; see [scale output](scale-final.log). This is an off-main full-catalog operation holding writer barriers. It does not prove a per-edit/UI-latency budget; future coalescing/incremental transport and apply still need measurement.

The sparse planner separately processed 100,000 unique and duplicate occurrences, removing one occurrence with **one deleted row and zero surviving-row rewrites**. The reported Node durations include constructing the initial sequence and planning, not deployed Worker CPU. No scale fixture was uploaded to Cloudflare, and neither canonical byte count is a ZIP/R2 size estimate.

## Failures retained honestly

An initial post-install emulator startup ANR occurred before any test began. The unchanged APK then passed a one-test startup check; no package clear, global timeout change or emulator restart was used. Its cause is not established as a 16 KiB compatibility defect. [Startup summary](startup-failure-summary.txt), [unchanged-APK smoke](startup-smoke-r2.log).

The first complete run had 47 passes and two failures in older backup-migration test fixtures. Those fixtures were cloned from the new v4 database but only relabeled as v1/v2, retaining new cloud tables, and expected target version3. Corrected the fixtures to actually remove post-version tables and expect the current schema; the production migration was **not** relaxed with IF NOT EXISTS or table deletion. The subsequent broad run passed all 198 cases. [Failed-run summary and original hash](failed-native-fixture-summary.json).

Initial formatter/compiler issues were corrected before the tested implementation: explicit JUnit imports and `arrayOf<Any>` for heterogeneous SQL bind values. Original failure logs remain under build-host `.work/cloud-m1-native/`.

## Review, recovery and storage boundaries

The focused **Claude Opus Extra High** review was launched in a persistent read-only session on NotCodex-2. Its completed verdict has not been recorded/adjudicated in this checkpoint. [Exact session, frozen candidate and resume instructions](../../reviews/cloud-native-review-2026-10-19.md). Passing tests are not independent review approval. Do not restart the reviewer or repeatedly poll it.

APK transfer bytes were verified against [original candidate](candidate.json) and [corrected test candidate](candidate-r2.json). Primary-host temporary app/test APK copies were removed after successful runs and hash checks. Only current build-host outputs are retained; evidence is small text/JSON. Raw logs stay on their execution hosts. Repository log copies normalize LF/trailing whitespace only, with original and stored hashes in [results](results.json).

Still required: adjudicate the independent review; stable remote identity adoption and durable native record apply; generic large sealed D1 publication and measured production physical schema; complete write-capture integration; real account/CPU/row-budget verification; authenticated transport and credential/settings UI; exact visual preview; minimal R2 recovery; true multi-device testing and release approval. Existing AI gateway and `custom/dev` are unchanged. No Cloudflare resources, live endpoint, phone installation or production activation was created.
