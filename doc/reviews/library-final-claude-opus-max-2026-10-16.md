# Claude Opus Max — Final Library Implementation Review

**Status:** Independent final review; PASS WITH NONBLOCKERS. Findings retained for adjudication.
**Date:** 2026-10-16
**Reviewer:** Claude Code Opus (`--model opus --effort max`).
**Reviewed code:** `custom/dev` at `577bf2f77`, including the global Library removal behavior.
**Review permissions:** Read/Glob/Grep only. No builds, tests, installations, code changes or privileged commands.
**Scope:** Library deletion invariants, stale UI writes, import/backup integrity, upstream patch boundaries, and test coverage.
**Important:** The reviewer's assertions are its independent advice, not automatically adopted implementation decisions.

---

## Verdict: PASS WITH NONBLOCKERS

*This is my independent advice, not a ruling. It's a read-only review: I built, ran, installed and executed nothing, and every outcome below is traced from the source.*

I found no Critical or High issues. Global removal only runs against the Library ID that the running app has verified (`LibraryRepository.kt:267-271`, `:309-312`). It happens in one database transaction, and repeating it is harmless (`:272-297`). I could not find any way for the startup backfill or a rebind to bring a removed item back.

Before you call the milestone done, I'd make one small closing commit: fix one test assertion (M1) and the outdated comments and docs (L1).

### Release checklist

| Gate | Status |
|---|---|
| One-transaction delete, checked against the bound Library ID, removes every copy | ✅ |
| No item comes back via backfill, rebind or restart | ✅ none found |
| Library "Delete" entry and confirmation dialog are present | ✅ `LocalPlaylistFragment.java:769-770`, `:784-786`, `:600-630` |
| Deleting from an ordinary playlist stays local | ✅ `:634-641` uses the non-Library `updateJoin` branch |
| Instrumentation suite would pass | ❌ one existing test fails as written (M1) |
| Comments and docs match the code | ❌ several contradict what shipped (L1) |

### Findings, ranked

**M1 — Medium: one instrumentation test will fail when it's run.**
- The test at `LibraryRepositoryTest.kt:206` expects the error message to contain "Unbound playlist".
- The actual message is "An unbound Library playlist already exists…" (`LibraryRepository.kt:96`). Both the case and the wording differ.
- So `existingPlaylistNamedLibraryWithoutBindingIsNotAdopted` fails. It isn't one of your 5 new tests, but it will make Library identity protection look broken.
- **Fix:** `assertTrue(expected.message!!.contains("unbound Library playlist"))`.

**M2 — Medium (existed before, but deletion now adds a misleading prompt): a second playlist named "Library" becomes stuck.**
- Nothing stops you from creating or renaming an ordinary playlist to "Library". `renamePlaylist` only checks that the playlist being renamed isn't the Library (`LocalPlaylistManager.java:154-158`).
- Both UI guards then protect any playlist with that name (`LocalPlaylistFragment.java:99-104`, `BookmarkFragment.java:499-504`).
- **Repro:**
  1. Rename "Mix" to "library".
  2. Open it, long-press an item, choose Delete.
  3. You see "Remove this video from Library and every local playlist? This cannot be undone."
  4. Confirm. The removal is refused (`LibraryRepository.kt:310-312`) and a full-screen error replaces the list.
- After that, the playlist can't be renamed, deleted, or have items removed. "Use as Library" also fails, with "A different Library is already active" (`:139-141`).
- No data is lost; the ID check does its job.
- **Optional fix:** add `@JvmStatic fun isActive(db) = current(db) != null`. In both `isProtectedLibrary` helpers, use only the bound-ID check while the Library is active, and fall back to the name only when it isn't. The repository's own check already works this way (`:197-205`). Otherwise, just don't name another playlist "Library".

**L1 — Low: comments and docs contradict the shipped code.** This matters because future AI Control agents will read them.
- `LibraryRepository.kt:24-25` says remove-everywhere is "intentionally unavailable".
- `LibraryBackupRestore.kt:21-22` says it "remains disabled in this milestone".
- `LocalPlaylistFragment.java:395` says "until explicit global removal is tested".
- The spec (`library-playlist-system.md:3`) still says "implementation not started".
- The architecture doc (`:145-147`) says not to expose cascading Library deletion until there's a stronger identity scheme. Shipping it is a reasonable call for a personal fork, but record it there as an accepted risk.
- The architecture doc (`:30-33`) suggests only two upstream files are patched. The total is five: `MainActivity`, `BookmarkFragment` and `ImportExportManager` as well.

**L2 — Low: the "recently removed" marker can outlive a delete that rolled back.**
- The marker is added and cleaned up inside the transaction body (`LibraryRepository.kt:277`, `:290-293`).
- If the final commit itself fails (disk full or an I/O error), the delete rolls back but the marker stays.
- For the rest of that app session, `filterStaleOrdinaryUpdate` (`:323-333`) would then quietly drop that video from any ordinary playlist you reorder or edit. It would stay in Library.
- **Fix:** move the try/catch so it wraps the whole `db.runInTransaction(...)`, and only remove the marker if this call added it.

**L3 — Low: playlists whose thumbnail was the removed video get a placeholder, not the next video.**
- `:284-288` sets the thumbnail ID to `-1`, so the thumbnail URL comes back empty (`PlaylistStreamDAO.kt:80`).
- A normal local delete picks the first remaining item instead (`LocalPlaylistFragment.java:564-579`).
- **Fix:** replace the UPDATE with this one statement, run after the DELETE:
```sql
UPDATE playlists SET is_thumbnail_permanent = 0,
  thumbnail_stream_id = COALESCE((SELECT stream_id FROM playlist_stream_join j
    WHERE j.playlist_id = playlists.uid ORDER BY join_index LIMIT 1), -1)
WHERE thumbnail_stream_id = ?
```

**L4 — Low: a failed removal shows a full-screen error panel.** `:624-626` calls `showError`, which replaces the whole list. `showUiErrorSnackbar` is already imported (`:5`) and used at `:444`; switching to it is a one-line change.

**L5 — Low (accepted design): explicitly adding a removed video again clears its marker everywhere** (`LibraryRepository.kt:215`, `:226`).
- An unsaved, out-of-date edit screen for a different playlist could then put the video back into that playlist.
- This is very narrow, and the video is back in Library anyway because of your explicit add.

### Answers to your questions

**1. Delete transaction**
- `DELETE … WHERE stream_id = ?` removes every local playlist membership, including duplicate copies.
- Bookmarked remote playlists, watch history, playback progress, the feed and the stream record itself are untouched.
- The raw SQL runs inside Room's transaction, so Room still notifies observers and the Bookmarks list and open playlists refresh.
- IDs are stable: existing stream records are updated in place, never replaced (`StreamDAO.kt:67-86`).
- A playlist that merely has the name "Library" can never authorize deletion (`:267-268`).

**2. In-memory "recently removed" set**
- It doesn't survive the app's process being killed, and it doesn't need to, because no stale edit screen survives either.
- Recreated screens reload from the database (`LocalPlaylistFragment.java:256-275`), and an import restarts the whole process (`NavigationHelper.java:769-773`).
- No delayed playlist save can outlive the process that queued it.
- Recreating MainActivity keeps the Library session (`LibraryRepository.kt:72`).
- Creating a playlist or adding to one clears the marker for those videos (`:215`).
- The set is only touched inside database write transactions, which run one at a time.

**3. Library Delete UI**
- On confirmation, the item is removed from the list, and the count and empty state update (`:615-622`).
- If the database refresh already replaced the list, the removal is a harmless no-op (`LocalItemListAdapter.java:145-159`).
- Not touching the delayed-save mechanism (DebounceSaver) is correct. Any pending Library save goes through `rewriteLibraryWithoutRemoving`, which drops anything no longer in Library (`LibraryRepository.kt:241-247`).
- Double-tapping is safe: the second call finds nothing to remove and changes nothing.
- If you leave the screen mid-removal, the call is cancelled (`:321-323`). Because it's one transaction, the removal is either fully applied or not applied at all.

**4. Can backfill bring removed items back?**
- Backfill (`:403-415`) and rebind (`:161`) only copy memberships that still exist in other playlists. The removal deleted all of them, so there's nothing to copy back.
- I found only three ways an item returns: you add it again, you restore an older backup (both intended), or the narrow case in L5.

**5. Restore and rebind**
- This code hasn't changed since the last review.
- If the app dies in the middle of an import, you end up with either:
  - the new database plus the old binding, which only binds if that playlist ID is still named exactly "Library", or
  - no binding at all, which shows the repair prompt.
- That's acceptable for a disposable fork.

**6. Upstream maintainability:** the patch stays at five upstream files plus `strings_custom.xml`, and the Library logic lives in `custom/library`. The spots most likely to conflict with upstream merges are `updateJoin`/`appendToPlaylist`, `deleteItem`/`showInfoItemDialog` and `extractDb`.

**7. Test status**
- I didn't verify any of this. The docs report the 163 JVM tests passing and the APK building.
- According to the architecture doc (`:112`), only the original 10 instrumentation tests ever ran. The file now has 22.
- That leaves 12 that have never been executed: 7 hardening tests plus your 5 deletion tests.
- Reading the 5 deletion tests by hand, I expect them to pass. The test in M1 will fail.

### Narrow Pixel 7 checks (use test playlists, not real data)
1. Set up P1 with [A, A, B] and A as its thumbnail, and P2 with [A].
   - In Library, long-press A → Delete → **Cancel**. Nothing should change.
   - Repeat and confirm. A should be gone from Library, from both copies in P1 and from P2. B should remain, and the Bookmarks counts should update.
2. P1's thumbnail is now a placeholder (expected; see L3).
3. Force-stop the app and reopen it. A should **not** come back.
4. Add A to P2 again. Library should contain exactly one A.
5. Delete B from P1. B should still be in Library.
6. Drag to reorder Library, leave the screen, and come back. The order should persist and nothing removed should reappear.

### Closing commit for the milestone
- Change the expected string at `LibraryRepositoryTest.kt:206` (M1).
- Update the comment and doc lines listed in L1.
- Optional: L2 (move the try/catch) and L4 (swap to the snackbar).
- M2 and L3 can wait or be skipped.
