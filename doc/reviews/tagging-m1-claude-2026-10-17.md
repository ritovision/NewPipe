I found no data-loss or deadlock defect reachable in M1. Verdict: **PASS WITH NONBLOCKERS**. There is one crash path in the tag editor and two smaller issues, plus assumptions the M2 and M3 work must uphold.

This is source inspection only: I didn't build, install or run any tests. The worktree files I compared match `candidate.patch`. I couldn't confirm the HEAD SHA myself because I had no shell access. I also didn't write the plan file, since you asked for no writes.

## Findings (most severe first)

### 1. Medium — Apply can crash the app after the save has already committed
- **Where:** `TagEditorFragment.kt:175-177` calls `if (isAdded) dismiss()` when the save finishes.
- **Scenario:** The user taps Apply, then presses Home or locks the screen before the background save finishes.
  - By then Android has saved the screen state, and the fragment still counts as added.
  - `dismiss()` then throws `IllegalStateException: Can not perform this action after onSaveInstanceState`.
  - Because it is thrown inside an RxJava callback, the app's error handler treats it as critical and crashes the app (`App.kt:194-213`).
- **When it gets likelier:** the window is short normally but widens whenever the global lock or the Room write transaction is busy. Examples: rewriting a large Library during reorder, the startup backfill, or upstream `markAsWatched`, which holds a Room transaction during a network fetch (`HistoryRecordManager.java:100-111`).
- **Consequence:** the app crashes, but the tags are already saved. No data is lost.
- **Fix:** use `dismissAllowingStateLoss()` on success. This is safe because the write is durable and the sheet has no state worth keeping. Alternatively, set a flag and dismiss in `onResume`.

### 2. Low — Rotating the screen during "Saving…" loses track of the save
- **Where:** `TagEditorFragment.kt:172`, `:190-201` and `:112-114`.
- **Scenario:** `onDestroy` cancels the subscription, but the write itself still completes. The lock wait can't be interrupted and the SQLite calls don't support cancellation. The recreated sheet then:
  - restores the selection and revision saved *before* the write, and comes up editable;
  - keeps `isCancelable = false`, which the framework restores, so Back and tapping outside don't close it.
- **Consequence:**
  - If the save landed, tapping Apply again fails with "tags changed", even though the stored tags already match what the user picked.
  - Cancel can't undo a save the user may think was abandoned, which breaks the Apply/Cancel promise.
  - No data is corrupted.
- **Fix:** either:
  - run the apply in a ViewModel scoped to the fragment, so it survives rotation; or
  - save an "applying" flag and the requested tag set. On restore, reload; if the stored tags equal the requested set and the revision has advanced, treat the save as done and dismiss. Also reset `isCancelable` on restore.

### 3. Low — `modifyPlaylist` reads the playlist row before taking the lock
- **Where:** `LocalPlaylistManager.java:202-214`.
- **Scenario:** the playlist row is read outside the lock. Inside the lock, the barrier may finish a pending global removal, or a removal may commit between the read and the lock. That removal resets `thumbnail_stream_id` and `is_thumbnail_permanent`. `playlistTable.update(playlist)` then writes the old row back.
- **Consequence:** a playlist that was renamed or had its thumbnail changed can keep pointing at a globally removed video, possibly pinned. This is cosmetic; there's no foreign key and memberships are unaffected.
- **Side effect:** this `map` runs on Room's query-executor thread. It is the only path that parks a Room executor thread on the global fair lock.
- **Fix:** move the read, change and update into `Maybe.fromCallable(() -> TagRuntime.mutate(db, () -> db.runInTransaction(...))).subscribeOn(io)`.

## What I checked and found sound
- **Separate storage:** there are no Room entity or migration changes. The cascading deletes stay inside `ritovision-tags.db`, so deleting a category cannot touch media or playlist rows.
- **Global removal:** it records the intent, runs the verified Room transaction, then cleans up tags.
  - Both crash windows replay safely, using the stream's service + URL to find its current row.
  - The replay runs before any write that goes through the barrier.
  - Library rebind (`rebindExisting`), which bypasses the barrier, can't bring an item back: its backfill only copies memberships that still exist.
- **Stale editors:** the stream's revision is bumped on removal and on tag or category deletion. It is checked before the Library save and again inside the tag transaction, all under one lock.
- **Lock order:** it is consistent everywhere — TagRuntime lock, then the `LibraryRepository` monitor, then the Room write lock. Nothing takes the TagRuntime lock inside a Room transaction, and no main-thread caller takes it.
- **Other writers:** every playlist-membership writer I found goes through the barrier. History and feed writers never change memberships.
- **Test gap:** `TagRepositoryTest` runs with trampoline schedulers and in-memory databases. It doesn't exercise lock/executor interaction, the editor lifecycle, or any wiring of the restore flag.

## M2 hazards (not reachable in M1)
- `TagRepository.snapshot()` at `:94-97` calls `blockingFirst()` on a Room Flowable while holding the global lock. That query needs a Room executor thread, and finding 3 parks executor threads on the same lock, so they can starve each other. It also queues all Library writes behind large reads. Use a direct cursor query instead.
- `TagRuntime.changed()` fires synchronously while the lock is held. M2 subscribers must switch threads before doing anything that needs the lock.

## Assumptions the M3 import fence must uphold
1. **`TagRuntime.restoring` is never set in production code**, only in tests.
   - `LibraryBackupRestore.kt:105` only calls `suspendForRestore()`.
   - Import runs on the main thread (`BackupRestoreSettingsFragment.java:167-168, 204`), and the process stays alive until `restartApp`. That can wait on the "import settings?" dialog (`:213-242`).
   - M1's barrier now calls `LibraryRepository.initialize()` from every playlist change and every tag operation. In that window, any such call re-initializes through the old Room instance against the swapped files and can rewrite the binding file.
   - The fence should set the flag while holding `TagRuntime.lock`, before the file swap, and keep it set until restart. Since import runs on the main thread, don't block there on the lock: move import off the main thread or use a time-limited `tryLock`.
2. **Pending removals are fenced only by the Library playlist's row ID** (`TagRepository.kt:38`). A backup restored from the same install often has the same ID. A leftover pending removal would then replay against the restored data, deleting that video (by service + URL) and its tags from every restored playlist. Clear these intents as part of the staged swap, or tie them to a generation token stored in the binding file.
3. **A genuine ID mismatch locks out everything.** Every playlist write and every tag operation fails, because `beforeLibraryMutation` (`TagRepository.kt:24-32`) only tolerates the "repair required" error. There is no way out inside the app, and the test `changedLibraryIdentityNeverReplaysDestructiveIntent` locks this behaviour in. M3 needs an explicit, user-confirmed way to discard such intents.
4. **Stale-editor safety across an import** depends on stream revisions only ever increasing, and on import always restarting the app (ProcessPhoenix clears the task, so no editor is restored). An in-process import would need to bump the affected revisions.
5. **Reopening the database in-process** via `NewPipeDatabase.close()` then `getInstance()` registers a second tag repository on the same file and keeps the old one in the map (`TagRuntime.kt:27-31`). If the tag database joins backups, stage and roll it back together with `newpipe.db` and the binding file.

## Verdict
**PASS WITH NONBLOCKERS.** Fix finding 1 (a one-line change) before M2 reuses the editor on the Tags page. Fix findings 2 and 3 before a release candidate, and track the M2 and M3 items above.
