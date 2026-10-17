**VERDICT: HOLD** for a78207dd4.

I found no P0 defect. The hold rests on one deterministic P1: the import validator rejects a backup format the code explicitly says it supports, and that same check also blocks export. There is one P2, whose likelihood I'm unsure of, on the restore rollback path. Everything else is P3.

I did not build, run tests or use devices.

## P1

**P1-1. The import schema check rejects every upstream database that went through `MIGRATION_4_5`.**

- **Where:**
  - `TagBackupCoordinator.kt:282` compares `structure()` output. At `:343/:347`, the `PRAGMA table_info` rows skip only `cid`, so `dflt_value` is compared as text.
  - `Migrations.kt:225-229` adds `notification_mode INTEGER NOT NULL DEFAULT 0` to `subscriptions`.
  - The generated 9001 schema has no default for that column (`app/schemas/.../9001.json:9`, `:48-53`).
  - Later migrations (`Migrations.kt:232-349`) rebuild only `playlists` and `remote_playlists`, so `subscriptions` keeps the default forever.
  - `RitoVisionDatabaseVersion.kt:24` advertises import support for versions 1–9.
- **Trigger:**
  - Importing any upstream v1–v4 backup fails every time, because migrating to 9001 always adds the default.
  - Importing a v5–v9 backup also fails if that NewPipe install's database was created before DB v5. That is typical for long-lived installs.
  - Room itself accepts these databases (it ignores DB-side defaults when the entity declares none). Then `structure()` compares `notification_mode|integer|1|0|0` with `notification_mode|integer|1|NULL|0` and throws "Backup schema does not match the supported catalog".
- **Consequence:**
  - Import fails closed. No data or AI state changes, because `prepare()` runs before AI shutdown.
  - But the owner cannot bring an older stock NewPipe Library into the fork, which conflicts with the approved stock-import behaviour.
  - The same check runs in export (`TagBackupCoordinator.kt:216`). An installed database with this history (for example one restored earlier through the old import path) can never be exported.
- **Uncertainty:** I can't tell from the worktree whether the owner's real backup has this history. The rejection itself is certain for v1–v4.
- **Minimal fix:** Stop comparing `dflt_value` when it is a literal constant (number, quoted string or NULL), which matches Room's own rule. Keep rejecting parenthesised expression defaults if you want to stay conservative.
- **Focused test:**
  - Build a v4 database with `MigrationTestHelper.createDatabase(name, 4)` (`4.json` exists) and insert a subscription.
  - ZIP it as `newpipe.db`, then run `prepare()` and `installWithShutdown()`.
  - Reopen and assert version 9001 with the row intact.
  - Separately, assert a crafted `DEFAULT (randomblob(8))` is still rejected.
  - No current test can catch this: `AiDatabaseMigrationTest.kt:21` creates v9 fresh, `TagBackupIntegrationTest` only round-trips fork-made exports, and the old `LibraryBackupRestore` checked only the header.

## P2

**P2-1 (uncertain). The rollback ("old") copies are taken from a Room file that may still have open connections.**

- **Where:**
  - `TagBackupCoordinator.kt:178-187`
  - `NewPipeDatabase.kt:89-97`
  - `CatalogFileSwap.kt:36-41` (snapshot), `:56-63` (in-process rollback)
- **Gap:**
  - `TagRuntime.lock` only excludes Library, tag and AI writers.
  - These do not take it:
    - history, stream-state, feed and subscription writes;
    - Room Flowable re-queries and the invalidation tracker.
  - The freeze and the open guard block *new* opens. A connection already leased when `NewPipeDatabase.close()` runs is closed only when it is released.
  - SQLite's last-connection close checkpoints into `newpipe.db` and deletes `newpipe.db-wal` by name.
- **Trigger:**
  1. A playback progress save, feed refresh or UI re-query is in flight at `close()`.
  2. It overlaps the `Files.copy` of `newpipe.db` (old.0), which starts microseconds later.
  3. The swap then fails after publish (I/O error or disk full, which triggers the in-process `recover`), or the process dies before COMMITTED.
- **Consequence:**
  - The restored "old" database can be a torn copy (checkpointed pages mixed with old ones, WAL gone) or can be missing recent commits.
  - After an in-process rollback, a late connection close can also delete the restored `-wal` file.
  - This breaks "restore ALL old files" in exactly the failure case the journal exists for. A successful swap is unaffected.
- **Uncertainty:** This depends on how Room 2.8.5 closes in compat mode (no sources in the gradle cache) and on timing. Probability per import is low; impact is silent corruption after a "failed, nothing changed" import.
- **Minimal fix:**
  1. After `close()` and before `replace()`, open a raw `SQLiteDatabase` on `newpipe.db`.
  2. Require `PRAGMA wal_checkpoint(TRUNCATE)` to report busy=0 and log=0, with bounded retries.
  3. If it never does, abort before publish (nothing has changed yet). Otherwise close the raw connection and take the snapshots.
- **Focused test:**
  - Hold a Room transaction or open cursor on another thread across `installWithShutdown`.
  - Inject an IOException at `new:newpipe.db`.
  - Assert the install waits or aborts, and that the restored database passes `quick_check` and contains the in-flight commit.

## P3

1. **Export can produce a backup that import will always reject.**
   - Export copies the binding file without validating it (`TagBackupCoordinator.kt:213-214`); `prepare()` requires it strictly (`:107`, `:309-319`).
   - Export continues when the Library is unbound, because `beforeLibraryMutation` swallows the "needs repair" exception (`TagRepository.kt:36-40`).
   - If the binding file is then corrupt or mismatched, the export reports success but can never be restored.
   - Fix: call `validateBinding(stage/BINDING, stage/DB)` after `:216` and fail with a clear message.
   - Test: corrupt the binding, export, and assert it fails or produces a ZIP that imports.

2. **Predictable failures surface only after AI has been unpaired.**
   - The SQLite ≥3.27 check (`:265-270`) is missing on API ≤29, and minSdk is 23 (`ProjectConfig.kt:8`).
   - Disk space for roughly 2× old + 2× new copies is never checked.
   - Both are hit only after `beforeRestore()` has saved paired=false and rotated the epoch (`AiControlRuntime.kt:482`).
   - Data stays safe, but a failed import still costs the pairing, and export is impossible on API ≤29.
   - Fix: check both up front in `prepare()`.
   - Test: force a low reported version and assert the AI state is untouched.

3. **Some tag commands are reported OUTCOME_UNKNOWN when they were definitely not executed.**
   - This covers the tag-receipt capacity limit (`TagStore.kt:220`), constraint violations (`create_tag` with a missing category, duplicate names) and "no longer exists" checks.
   - They all reach the generic catch at `AiCatalogExecutor.kt:117-118`. The Room path returns NOT_EXECUTED for its capacity limit (`:92`).
   - Nothing commits and retrying with the same ID is safe, so this is protocol accuracy, not integrity.
   - Fix: throw `AiFailure` with NOT_EXECUTED or REJECTED for these cases.
   - Test: fill 10,000 tag receipts and assert NOT_EXECUTED.

4. **A pending AI cleanup can block all edits after a Library rebind.**
   - `TagRepository.kt:48` requires the cleanup's Library ID to match the current one, even though the Room receipt alone decides whether it committed.
   - Scenario: a crash leaves AI cleanup unsettled, the binding becomes unverifiable, and the user rebinds a different "Library" playlist.
   - Every tag access and playlist mutation then throws. The message says "requires a restart", but a restart doesn't clear it; only an import does.
   - Fix: settle AI cleanups by receipt regardless of Library ID.

5. **Stock import snapshots the live tag DB without settling pending work first** (`TagBackupCoordinator.kt:168-175`). Export does settle (`:208`).
   - If Room never opened in this process and a swap journal remains, or committed removals are still unsettled, those removed streams' tag assignments survive the import. They come back if the stock DB contains those streams.
   - This is narrow: it needs a prior crash plus a failed startup initialisation.
   - Fix: run `CatalogFileSwap.recover(directory)` and a best-effort settle before the snapshot.

6. **Leftover swap directories are never cleaned up.**
   - Process death before publish leaves `.rv-swap-staging-*` (`CatalogFileSwap.kt:31`, `:65-69`). A failed cleanup leaves `.rv-swap-retired-*` (`:135-137`).
   - Each can hold full database copies, potentially gigabytes.
   - Fix: delete stale staging and retired directories in `recover()` when no journal exists.

7. **A crafted backup's triggers run before they are rejected.**
   - Room opens and migrates the staged copy (`TagBackupCoordinator.kt:277-280`) before the trigger/view rejection (`:337`).
   - For a v1–9 database, upstream migration INSERT/UPDATE statements and Room's identity write fire attacker triggers.
   - Effects stay inside the discarded copy (triggers can't ATTACH or run DDL); the worst case is a stuck worker thread.
   - `table_info` also does not compare CHECK, COLLATE or hidden generated columns.
   - Fix: scan `sqlite_master` with a read-only connection before Room opens the file.

8. **Fail-open close paths.**
   - `TagRuntime.mutate` silently skips recovery when no repository is registered (`TagRuntime.kt:76`).
   - `NavigationHelper.restartApp` (`NavigationHelper.java:770`), used by reset settings and panic exit, closes the database on the main thread under `TagRuntime.lock`. That is an ANR risk if an export holds the lock.
   - It also closes without setting the restore fence, so old database handles can reopen and mutate in the short window before the process dies.

9. **The legacy import/export code is still compiled.** `LibraryBackupRestore` and `ImportExportManager.extractDb`/`exportDatabase` bypass the AI drain, the freeze, the tag DB and the journal. Export also copies a live WAL database raw.
   - No production code calls them; only JVM tests do.
   - Fix: delete them or make them throw, so they can't be wired back in by accident.

## Checked, no defect found

- **Lock order:** authorization lease → `TagRuntime` → Library monitor → Room holds on every path. Restore drains AI before taking `TagRuntime` (`:164-165`). Rebind takes `TagRuntime` before the monitor (`LibraryRepository.kt:221-223`).
- **Staged AI delete:**
  - Cleanup is staged after the receipt lookup (`AiCatalogExecutor.kt:71`, `:80`).
  - Assignments are deleted only if a Room receipt exists (`TagRepository.kt:49-52`). A rolled-back delete is discarded, never replayed.
  - Pruning cannot outrun recovery: recovery runs in `mutate` first. When the Library is unbound, every Room-path command fails LIBRARY_NOT_READY after `deleteExpired` (`:91`), which rolls the prune back.
  - Retrying an old delete after the item was re-added replays the result and keeps the new tags (covered by a test).
- **Tag-only receipts:** the tag effects and the receipt share one tag-DB transaction. Nested Android transactions roll back as a unit.
- **Stale editors:** every removal path bumps the per-stream revision (`TagStore.kt:194`, `:236`), so an old editor cannot re-add a removed video.
- **Scopes:**
  - Tag scopes are explicit, and old grants don't gain them (covered by a test).
  - The receipt lookup is limited to the caller's client, and a replay re-checks the original scopes.
  - Device and epoch are checked on every request.
- **Policy behaviours:**
  - Deleting a category cascades only within the tag DB (`TagStore.kt:25-27`, `:159-172`).
  - "Untagged" means saved items with an empty tag set (`TagModels.kt:53`).
  - Global delete removes every membership (`LibraryRepository.kt:341-344`) and the item's assignments.
  - Stock import keeps unmatched assignments.
- **Swap journal:** the manifest/stage is published atomically and the commit marker is fsync'd. Recovery hashes the chosen side before applying, runs before the binding is read (`LibraryRepository.kt:103`), and failed recovery keeps its evidence.
- **VACUUM INTO:** snapshots are taken under the lock with `inTransaction` checked. Staged copies use rollback journaling and leftover WAL files are checked.
- **Token cloning:**
  - Device keys are non-exportable Keystore entries (`AiDeviceKeys.kt:14`).
  - AI state lives in `noBackupFilesDir` (`AiLocalStateStore.kt:67-69`).
  - ZIP export strips receipts and cleanup intents (`TagBackupCoordinator.kt:283`, `:301`).
  - Preferences hold no AI data.
  - Android Auto Backup (`allowBackup=true`, no rules) copies receipt tables, but they carry no credentials.
- **Tag DB 1→2:** the real v1 schema (read-only, from the `newpipe-tagging` worktree) equals v2 minus the two AI tables, so the upgrade passes the structure check.

## Evidence gaps (not source bugs)

- **Reported test counts:** I found no artifact for a78207dd4 with the 307 JVM / 54 gateway / 141 native counts or the three force-stop recoveries. `tagging-production.md:42-45` records 307 / 51 / 125 for an earlier APK, and the latest data review marks process death "pending". From my side, all of these are unverified.
- **`TagProcessDeathTest` coverage:** it covers three host-kill points (`new:newpipe.db`, `new:binding`, `committed`). It does not cover:
  - "prepared" (journal published, nothing applied yet);
  - a kill during the tag-DB write;
  - an in-process Exception rollback on real SQLite (the JVM swap tests use fake bytes).
- **Untested hazards:** import of a pre-v5 database (P1-1), a Room connection open across install (P2-1), export with a bad binding, and devices on API ≤29.
- **Weaker tests:** the v1 tag-migration test fakes v1 by dropping tables from a v2 copy. The lock-order test depends on a 100 ms sleep.
