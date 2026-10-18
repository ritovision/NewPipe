# Unavailable Playlist — Astra Adjudication and Corrective QA

**Status:** Corrective implementation and headless builds complete. **Final independent Astra review and Claude Opus review remain pending** at this checkpoint.
**Baseline:** `feature/unavailable-playlist-20261017`, initial implementation `1431da435` on `custom/dev` after accepted Library, Tagging and AI Control.
**Specification:** [Unavailable Playlist](../../specs/unavailable-playlist.md).

## Adjudicated issues

- **Blocking data-safety defect:** the original native *Remove All Everywhere* staged per-song replayable deletion intents before committing its atomic Room batch; a rollback could be followed by unintended deletion during recovery.
  - Correction: introduce **TagStore v3**'s `native_bulk_intent` table, keyed by a native batch UUID and exact media identities, separate from legacy single-item intents. A Room batch still commits atomically. Reconciliation compares *all* original items' canonical Library memberships: **all present** means the batch did not commit (discard intents, preserve tags); **none present** means the batch committed (finish tag cleanup only, no replay); **mixed** means fail closed without further deletion or tag changes.
  - The abandoned bulk replay helpers were removed. Legacy single-item and AI receipt-backed recovery remain unchanged.
- **P2:** failing to initialize Unavailable had suppressed the entire native song context menu; fallback now preserves the ordinary menu while disabling only mark/unmark.
- **P2:** successful Library rebind now attempts to initialize the protected Unavailable subset immediately, independently of another startup.
- **P2:** user-confirmed designation accepts trimmed/case-insensitive Unavailable names, then normalizes the canonical name.
- **P2:** explicit designation now deduplicates existing memberships, ensures canonical Library membership, and refreshes the thumbnail in one Room transaction.
- **P2:** the native playlist creator now rejects reserved `Unavailable` names, consistent with the AI creator.
- **P3:** ordinary Add to Playlist now refreshes the Unavailable thumbnail.
- **P3:** singular/plural Android resources produce `1 marked item` and `N marked items` correctly.

## Execution evidence

- NotCodex-2 performed source/build tasks, **no emulator or interactive UI**.
- Gradle `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest`: **BUILD SUCCESSFUL**, final incremental build log: `/tmp/unavailable-fix-final-build.log` on NotCodex-2.
- Gateway `npm run check` and `npm test`: passed, **59/59** gateway tests.
- Primary NotCodex machine, physical Pixel 7 using the isolated test package `org.schabi.newpipe.debug.featureunavailableplaylist20261017`:
  - Targeted Unavailable, TagBackupIntegration, TagRepository, AiTagIntegration instrumented suites: **65/65 passed**, logfile `~/.cache/newpipe-unavailable-qa/native-review-fixes-20261018.log`.
  - Existing Library, AI Catalog and TagScale (20,000 streams) instrumented suites: **89/89 passed**, logfile `~/.cache/newpipe-unavailable-qa/native-core-regression-after-fixes-20261018.log`.
  - **154 Android instrumentation tests total** in those two suites, zero reported failures.
- New regression tests cover simulated Room rollback during the *second* batch deletion, interruption before or after Room commit, mixed state fail-closed, v1/v2 tag database import upgrades, explicit designation with duplicates and case variants, native name reservation, thumbnail refresh, and singular/plural copy.
- The earlier separate-screen scrcpy walkthrough of the original candidate demonstrated native marking/unmarking, ordinary playlist actions and confirmation UI on the same isolated QA package. **No post-fix scrcpy UI walkthrough has been claimed yet.**

## Safety and handoff

- The original user Library and existing installed NewPipe packages were **not reset, cleared, overwritten or modified**. Updates and destructive synthetic fixtures were confined to the **separate QA package**.
- No Cloudflare backup/sync, remote scanning, or automated replacement feature was added.
- The feature remains on its isolated feature branch, **not merged** into `custom/dev`.
- The user requested: corrective coding + testing + **Astra Extra High independent review**, then **stop before running Claude Opus Extra High**, so the user can resume it later.
- Review outcomes should be appended only when they actually complete; a successful build or code review is not proof of all UI integration paths.
