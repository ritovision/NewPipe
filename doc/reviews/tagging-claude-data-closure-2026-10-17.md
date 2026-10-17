**VERDICT: PASS WITH NONBLOCKERS** (source review of the frozen candidate)

- **D-P1 is closed.**
- **D-P2 is closed in source** for normal app Room writers and their statement handles. Its runtime closure depends on the native rerun now underway. If the held-writer or old-handle test fails there, this becomes **HOLD**.
- **No new concrete regression found.**
- This is not a production-rollout approval.

I used only Read/Glob/Grep. What I say about Room 2.8.5 internals (the old-style begin/end path, how compat mode routes statements, the invalidation coroutine) comes from my knowledge of Room's public source. I could not re-read the Room jar in this worktree.

## D-P1: CLOSED

**The exception is narrow.**
- `TagBackupCoordinator.kt:349` only normalizes when all three hold: the table is `subscriptions`, the column is `notification_mode`, and the default text is exactly `0`.
- `:352` then replaces only the default field.
- Type, NOT NULL and primary key on that column are still compared.
- `'0'`, `1`, `(randomblob(8))`, and every other table's or column's default are still compared exactly as written.

**The exception is necessary and sufficient for the upstream chain.**
- `Migrations.kt` has three `ADD COLUMN … DEFAULT` statements: `:227`, `:234` and `:242`.
- The two `playlists` defaults are removed when the table is rebuilt at `:260-275` (6→7) and `:296-315` (8→9).
- `subscriptions` is never rebuilt, and `9001.json:9` declares no default. So exactly one mismatch survives the chain.

**Executable schema is still rejected.**
- A read-only scan runs before Room opens the copy (`:287`, before `:289`).
- `structure` rejects anything that isn't a table or index again after migration (`:416`).

**What "schema constraints remain checked" covers.** Foreign keys, index uniqueness and columns, NOT NULL, primary key and type are compared. CHECK, COLLATE and hidden columns were never compared (earlier P3.7, already adjudicated), and this fix doesn't change that.

**Export.** VACUUM INTO keeps the literal default in the installed database. The same normalization lets export revalidate it (`:226`). The v4 test re-exports and re-prepares the result (`TagBackupIntegrationTest.kt:286-287`).

## D-P2: CLOSED IN SOURCE (runtime pending)

The restore order matches your description (`TagBackupCoordinator.kt:166-197`):
1. AI `shutdown()` runs with no tagging lock (`:166`).
2. `TagRuntime.lock` is taken (`:167`).
3. `freezeForRestore` sets `restoring` and bumps `generation` (`:183`, `TagRuntime.kt:64-65`).
4. `drain` (`:184`).
5. Raw checkpoint (`:187`).
6. `NewPipeDatabase.close()` (`:188`).
7. Raw checkpoint (`:189`).
8. File swap (`:197`).

**Admission and leases.**
- `acquire()` (`CatalogOpenHelperFactory.kt:67-77`) refuses any thread not already in a transaction once `restoring` or the generation changes. That refusal is already active between `:183` and `:184`, before `frozen` is set.
- `begin()` keeps the read lease after a successful begin and releases it if begin throws (`:86-96`).
- `endTransaction()` commits or rolls back first, then releases the lease in `finally` (`:112-121`). Nested depth stays balanced.
- `setTransactionSuccessful` (`:122`) and statement execution pass for the owner because its transaction depth is above zero (`:69`).
- The lock is a fair `ReentrantReadWriteLock`, which lets a thread that already holds a read lock take it again even while a writer is queued. So the owner cannot be starved behind `freeze()`.

**Room's endTransaction path.**
- Room's old-style `endTransaction()` fetches `openHelper.writableDatabase` again.
- The Guard's fast path (`:37`) returns the cached proxy without the availability check. So the owner reaches `FencedDatabase.endTransaction` instead of an `IllegalStateException`.
- Room's following `inTransaction()` call is passed straight through. Its follow-up `refreshVersionsAsync` counts as a new request and is refused.
- This is exactly the failure the adjudication reproduced. Draining before close removes it.

**Lock order.**
- Restore takes, in order:
  - `TagRuntime.lock`;
  - the Library monitor, only inside `beforeLibraryMutation`, then releases it;
  - the drain write lock (3 s `tryLock`, `:99`);
  - a raw connection;
  - the NewPipeDatabase monitor;
  - Room close, then `Guard.close`, which freezes outside the Guard monitor (`:56-57`);
  - the CatalogFileSwap monitor.
- Library writers take `TagRuntime.lock` before the Room lease: `LocalPlaylistManager.java:53-54`, `:67-68` and similar wrap `TagRuntime.mutate` around `runInTransaction`.
- History, feed and subscription writers take only the lease.
- The restore thread never holds anything the owner needs in order to finish. I found no production code that holds a lease and then takes `TagRuntime.lock`. If such code existed, it would hit the 3 s timeout rather than hang.

**Bounded drain errors.**
- Both the `freeze()` timeout (`:99`) and the checkpoint deadline (`TagBackupCoordinator.kt:406`) throw before the swap (`:197`).
- No file is changed, and the fence stays up because production never resets `restoring`.
- An owner that outlives the drain still commits to the untouched database, because its transaction depth exempts it. No admitted commit is lost.

**Writes after the fence.**
- Once drained, `frozen` refuses the stale proxy permanently, even if `restoring` were reset.
- Cached statements are wrapped at `:135-141` (`execute`, `executeInsert`, `executeUpdateDelete`, `simpleQuery*`).
- In compat mode Room sends non-SELECT statements through `compileStatement` plus `execute`, and SELECTs through `query`. Both are gated.
- `NewPipeDatabase.close()` has a single production caller, the restore path at `:188`. So `Guard.close()` freezing doesn't affect anything else.

**Cancellation safety.**
- `CancellationException` is thrown only under the fence condition (`:70`). SQLite and storage errors are not converted.
- A refused call never reaches SQLite, so no caller is told a write succeeded when it didn't.
- `java.util.concurrent.CancellationException` extends `IllegalStateException`, so existing handlers and the `assertThrows(IllegalStateException)` checks still catch it.
- Coroutines, such as Room's invalidation refresh, end as cancelled instead of crashing.

**Outside the claimed scope, consistent with your boundary.**
- Some pass-through methods are still ungated: the `version` setter, `setLocale`, enabling or disabling WAL, `yieldIfContendedSafely`, and `close`.
- A cursor outside a transaction steps after its lease has returned. These are reads only, and the TRUNCATE checkpoint loops wait out any read in flight.
- Neither is a normal app writer.

## New concrete regression

None. After the fence, the getter's fast path returns the proxy instead of throwing, but every data-writing method on that proxy is gated.

## Nonblockers

1. **Held-writer test can pass without testing rollback** (`TagBackupIntegrationTest.kt:361`, `:380`).
   - `assertThrows(Exception)` also accepts a drain or checkpoint failure before the swap is published. In that case the commit made in place satisfies `:376-379` without ever exercising rollback.
   - `published` is only printed, never asserted.
   - Fix: use `assertThrows(java.io.IOException::class.java)` (the swap rethrows the original exception, `CatalogFileSwap.kt:56-64`) and `assertTrue(published)`.
   - Optionally, assert `finished.count == 1L` in the shutdown callback. That proves the writer was mid-transaction when install began.
2. **Old-handle test doesn't prove the gate** (`:426-427`). After close, SQLite's own "pool closed" or "already-closed object" `IllegalStateException` passes these checks even with no gate. Asserting `CancellationException` would prove the gate refused it.
3. **A realistic way to hit the drain timeout.** `HistoryRecordManager.java:100-111` (upstream code) runs a network `getStreamInfo(...).blockingGet()` inside `runInTransaction`. An import started at that moment stops before any file changes, but only after AI shutdown. That is the late-failure case already accepted in D-P3.2, not data loss.
4. **New requests wait instead of failing fast.** `acquire()` takes the fair read lock before checking the fence (`:68-70`). New requests made during the drain wait behind the queued writer for up to 3 s before failing. A volatile check for "not in a transaction and fenced" before `lock()` would fail them immediately. Optional.
5. **`drain()` silently does nothing if the helper isn't a Guard** (`:148`). That's fine today (`NewPipeDatabase.kt:53-54`, with no wrapping builder options). Adding `setQueryCallback` or auto-close later would quietly disable the fix. Consider `check(helper is Guard)`.
6. **Test name.** `unknownExpressionDefaultAndTriggerAreRejectedBeforeMigration` (`:293`) contains no trigger. Rejecting a trigger in the Room copy is verified in source but not tested there.

## Runtime evidence (kept separate)

- **Reported as run, not checked by me:** the R2 native run passed 11/12 backup tests (per the adjudication follow-up). They include the v4 import with the subscription row and re-export, expression-default rejection, busy-WAL refusal and invalid-binding export.
- **Not yet run on this candidate:**
  - the held-writer test (`:318`) and old-handle test (`:419`) against the current gate;
  - the full backup suite after the `CancellationException` change;
  - checking logcat for any fatal Room invalidation crash.
- The v4 test's install step now goes through the new drain, so the rerun also reconfirms D-P1 end to end.
- **Not covered by any test:** v1 has no schema JSON, so it can't be generated for a test. v2 and v3 imports go through the same 4→5 step but were not run.

I wrote no files and ran nothing.
