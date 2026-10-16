# Claude Opus Max — Independent Library Polish Review

**Review result:** PASS WITH NONBLOCKERS
**Date:** 2026-10-16
**Reviewed code:** `feature/library-polish` at `f98fa3092`, versus `fed10c6dd`.
**Permissions:** Read-only source review, no builds, tests, installations, commits or device changes.
**Status:** Independent reviewer observations, pending explicit adjudication of nonblockers. Other reviewer conclusions are recorded separately.

---

# Independent final review: Library polish (fed10c6dd..f98fa3092)

**Verdict: PASS WITH NONBLOCKERS.** Merge it and install it on the Pixel 7.

I found no Critical, High or Medium defects. There are five Low findings. I worked read-only with Read/Glob/Grep only and ran nothing: no git, builds or tests. Because I couldn't run a git diff, I worked out which changes belong to this delta from the source itself and from the file list in `doc/architecture/library-polish.md`. The 176/176 JVM pass and the APK builds are recorded evidence that I did not reproduce. I did not consult any other reviewer's evidence.

## Verification of the five requirements

**1. Special behaviour follows the bound ID — verified.**
- `LibraryIdentityPolicy.kt:8-12`: while bound, only the ID protects. The name is used only while unbound.
- Both the UI check (`LibraryRepository.kt:50`) and the manager check (`:207-215`) use the same rule.
- The screen guards all delegate to it: `LocalPlaylistFragment.java:98-101`, `196`, `301-304`, `395-399`, `453`, `602`, and `BookmarkFragment.java:341-342`, `498-528`.
- Global delete needs the bound ID in two places: `:270-276` and `:317-319`. A name match can block an action but can't authorise one.
- While bound, an ordinary playlist named Library behaves like any other playlist: rename, delete, local item delete, normal thumbnail, and no global delete.
- Hiding "Set as playlist thumbnail" can't crash. `InfoItemDialog.java:237-246` ignores a `setAction` call for an entry that was never added.

**2. Artwork — verified.**
- **Colours:** `rv_library_thumbnail.xml:8-13` draws white paths on `@color/ic_launcher_background`, which is `#CD201F` (`values/colors.xml:7`). There is no night-mode override.
- **Placement:** the glyph spans x 12–73 of 160, so it stays clear of the count overlays (45dp in list/bookmark, 60–70dp in grid, 35% in card).
- **Coverage:** the single hook at `LocalPlaylistItemHolder.java:42` covers the list, grid, card and bookmark holders (`LocalItemListAdapter.java:334-341`). The Add To dialog uses the same adapter, so it is covered too.
- **Recycling:** the old binding is closed through a keyed tag (`LibraryArtwork.kt:21-24`, `65-69`). Coil replaces the request per ImageView, so a late video thumbnail can't overwrite the Library icon.
- **Async updates:** the identity subject (`LibraryRepository.kt:38-46`) only does a volatile read, a distinct filter and a queue offer on the publishing thread. That means no I/O or locks, so no deadlock even though it fires inside the transaction and under the repository lock.
- **Dispose:** dispose and delivery both happen on the main thread, so a disposed binding can never render.
- **Rollback:** if the commit fails, the code republishes `null` (`:135-137`, `:177-179`).
- **Schema:** no schema change.

**3. Thumbnail replacement — verified.**
- `:284-300` deletes every join row for the stream, then updates only playlists with `thumbnail_stream_id = ?`.
- The new thumbnail is the first surviving row by `join_index`, or -1 if the playlist is empty.
- Pins on other videos are untouched. A pin on the deleted video is cleared, which is the right call.
- Everything runs in one transaction, so a failure rolls back cleanly. The raw SQL still fires Room's invalidation triggers, so lists refresh.

**4. Commit-failure marker fix — verified.**
- No in-memory removal state is left (`:277-305`).
- `filterStaleOrdinaryUpdate` (`:330-347`) reads Library membership inside the same `updateJoin` transaction (`LocalPlaylistManager.java:88-104`).
- Create and append still re-add items, through `afterPlaylistInsert` and `appendDirectly` (`:218-233`).

I specifically looked for a way an ordinary save could be dropped unexpectedly and didn't find one:
- The only code that writes playlist entries is `LocalPlaylistManager` plus this repository (checked by grep).
- Every bind backfills inside the same transaction that publishes the session (`:131-134`, `:164-176`).
- While unbound, the filter does nothing (`:335`).

So the filter only removes items that were globally deleted and not re-added. The documented exception (an explicit re-add can reappear in an older open editor) is still the only intended gap.

**5. Snackbar and patch area — verified.**
- Global-delete errors use the snackbar (`LocalPlaylistFragment.java:627-628`). The subscription is cleared in `onDestroyView`, so there are no callbacks after the view is destroyed.
- Grep finds Library symbols in exactly six upstream files: MainActivity, LocalPlaylistManager, ImportExportManager, LocalPlaylistFragment, BookmarkFragment and LocalPlaylistItemHolder. That matches the doc.

## Findings

**L1 — Every bind or reattach sends two Coil requests (Low, performance).**
- `LibraryArtwork.kt:42` resets `shownAsLibrary = null` in `connect()`. The identity subject then replays through `observeOn(AndroidSchedulers.mainThread())`, which always posts, so `render()` enqueues the same request a second time.
- **When it happens:** each Bookmarks refresh (`handleResult` rebinds every row), each Add To refresh, and each reattach after scrolling. For an uncached ordinary thumbnail, the first fetch is cancelled and restarted.
- **Impact:** the wrong image is never shown; it's just wasted work.
- **Fix:** delete line 42. Coil already restarts its last request on reattach, and the dedupe in `render()` still handles identity changes that happen while the view is detached.

**L2 — Menu items aren't refreshed when the identity binds (Low).**
- `LocalPlaylistFragment.java:299-305` only re-runs when the menu is invalidated, and nothing invalidates it when the session binds.
- **Reproduction:** cold start with an ordinary playlist named Library pinned as the first main tab, or opened before startup finishes. Rename, remove watched and remove duplicates stay hidden until a tab switch or navigation.
- **Impact:** the guards on the actual click are correct, so nothing gains extra powers. But the doc's claim (`library-polish.md:14-15`) that menus re-evaluate on their own is overstated.
- **Fix:** correct that doc sentence. Optionally, call `invalidateOptionsMenu()` when `observeLibraryId` emits; that stays inside a file that is already patched.

**L3 — The name fallback during startup shows the wrong dialogs (Low, by design, fails safe).**
Before `start()` publishes the session:
- Long-pressing the real Library offers Repair (`BookmarkFragment.java:504-525`). Confirming is harmless: it backfills again with the same ID.
- Repairing an ordinary playlist named Library, confirmed after startup finishes, makes `:149-151` throw. That error is shown with `showError`, the full-screen error state, not a snackbar.
- Deleting an item in that ordinary playlist opens "Remove everywhere". It then fails safe with the snackbar "Library identity changed" (`:317-319`).

No data is lost in any of these. **Optional fix:** when the Repair button is tapped, check the protection again and use the snackbar for the error.

**L4 — The global-delete identity check runs outside the transaction (Low).**
`:272-276` checks the identity before the transaction starts at `:279`. Only `suspendForRestore()` can slip in between. **Fix:** move the `current(db)` check inside the transaction block.

**L5 — One more thumbnail surface is not covered (Low, outside the requested scope).**
`SelectPlaylistFragment.java:157-161`, the main-page tab picker, still shows the video thumbnail for Library. Leave it unless you're willing to touch a seventh upstream file.

**Remaining risk (not a defect):** the Room-level paths — identity notifications, the thumbnail update through Room, and the same-name cases — only have instrumentation tests. Those were compiled but not run, and the JVM tests cover the pure logic only. Reading the code supports correctness, but the Pixel 7 will provide the first runtime evidence.

## Merge and install

Merge, then install the custom/dev build over the existing Pixel 7 installation. Afterwards, run this quick manual check:

1. **Cold start, Bookmarks:** the Library tile turns white-on-red once startup finishes. Fling a long list and switch between list, grid and card views.
2. **Second ordinary "Library":** it shows a normal thumbnail, can be renamed and deleted, and deleting an item only affects that playlist.
3. **Add To:** only the real Library shows the artwork.
4. **Global delete of video X**, where X is (a) the automatic thumbnail of playlist A, (b) the pinned thumbnail of playlist B, and (c) not the thumbnail of pinned playlist C:
   - A and B switch to their first surviving video.
   - C is unchanged.
5. **Stale editor, then re-add:** reorder an ordinary playlist containing X without saving, globally delete X from another tab, then come back. X must not reappear. Re-add X with Add To; it should come back.
6. **Images turned off or airplane mode:** the Library artwork still shows.

The L1 one-line fix and the L2 doc correction can go in before or after merging; neither should hold up Tags.
