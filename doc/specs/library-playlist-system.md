# Library Playlist System — Canonical Saved-Media Semantics

**Status:** Library v1 implemented and installed on the separate custom development app; final independent source review PASS WITH NONBLOCKERS (2026-10-16). Tags and AI Control are separate milestones.
**Priority:** High (first custom-feature milestone)
**Project:** Personal NewPipe fork based on official dev
**Created:** 2026-10-15
**Scope:** Saved YouTube videos, songs, SoundCloud, Bandcamp, and other NewPipe-supported streams.

## Core mental model

**Library is the master list of everything the user has saved.** Other playlists are organizational subsets of Library.

We can implement this without modifying NewPipe's existing database schema by designating or automatically creating a local NewPipe playlist called **Library** and adding a small layer of automation around normal playlist operations. “Library” is the current working name; “All Songs” is an alternative display label.

The Library list represents *saving*; ordinary playlists represent *organizing*. A saved stream may belong to zero, one, or many organizational playlists, but must belong to Library.

## Non-negotiable behavior

1. **Add any item to any ordinary playlist → also add it to Library**, automatically and exactly once.
2. **Add an item directly to Library → saved, even when it belongs to no other playlist.**
3. **Remove an item from an ordinary playlist → remove it only there; keep it in Library and in all other playlists.**
4. **Remove an item from Library → remove it from every ordinary playlist too.** This is a global library removal, not merely hiding one membership.
5. Adding an item already saved in Library does not duplicate it within Library. Existing duplicate behavior in regular playlists is a separate question.
6. Applies regardless of source service and regardless of how the user adds/removes a stream: UI, batch operation, import/migration, future AI/MCP tooling, or cloud sync.

These semantics are agreed; implementation details below are proposals.

## Behavior matrix

| User action | Library result | Other playlists |
| --- | --- | --- |
| Add Song A to Favorites | A saved (if absent) | A added to Favorites |
| Add A to Workout | No extra Library copy | A also added to Workout |
| Remove A from Favorites | A remains saved | Removed only from Favorites |
| Remove A from Library | A removed from Library | A removed from Favorites, Workout, and every other playlist |
| Add B directly to Library | B saved | No other playlist affected |
| Delete the Favorites playlist itself | Library retains its saved songs | Favorites and its memberships removed |
| Add an already-saved A to Library | No duplicate | Unchanged |
| Bulk prune and remove A from Library | A removed globally | All memberships removed via the same global-removal rule |

**Delete from Library is intentionally consequential.** The UI should distinguish “Remove from this playlist” from “Remove from Library (everywhere).”

## UI proposal

- Add a top-level **Library** destination (or prominent entry in local playlists).
- Existing “Add to playlist” dialog retains its normal function; the app silently auto-saves to Library.
- Allow a simple **Save to Library** action for an item with no specific playlist.
- In an ordinary playlist, removing an item shows normal playlist removal. In Library, removing an item means global removal with appropriately clear confirmation—especially for bulk actions.
- Item details could show which playlists contain it and offer “Add to other playlists.”
- Later: search, tag filtering, and sorting across the entire Library; not required for this first automation.

Optional: a setting designating an existing playlist as Library instead of a hard-coded newly created one. Trade-off is flexibility versus invariants if the chosen playlist is renamed/deleted.

## Implementation sketch (no upstream schema changes)

A small **LibraryPlaylistCoordinator** (working architecture name) sits above the existing NewPipe local playlist operations:

- Identify the chosen canonical Library playlist by its stored playlist identity, **not just its display name**. Store a designation in our settings and re-resolve it after imports/restores.
- Intercept all *write paths* that add streams to user playlists and ensure the Library membership exists. Avoid recursion when writing to Library itself.
- Intercept removal from Library and remove **all occurrences** of the item from every other local playlist. Never cascade from removal in another playlist.
- Prefer normal NewPipe playlist-management APIs and their transaction support. Do not hand-edit Room tables unless strictly necessary.
- Ensure idempotency for repeated or retried operations, including sync/MCP requests. Failures must not leave a silently inconsistent Library.
- A service + stable stream identifier/URL identifies a unique saved stream. Never deduplicate solely by title: identical titles and different uploads can be distinct songs/videos.
- Preserve ordering in ordinary playlists when adding to Library; do not reorder them unnecessarily. Library's own order can initially append by save time.

This is a **conceptual** design: inspect exact manager/DAO operations and transaction boundaries before coding. A separate metadata database is reserved for tags/health information; the Library itself can be an ordinary local playlist in NewPipe.

## First-run migration and ongoing repair

When the feature is enabled on an existing installation:

1. Back up existing NewPipe data and verify it can be restored.
2. Identify or create the canonical Library playlist.
3. Traverse existing local playlists and backfill the **union of unique stream identities** into Library, without moving or removing existing entries.
4. Record/check consistency and surface any unresolvable entries rather than silently dropping them.
5. Repeating the migration should not create duplicate Library entries.

For imports/restore: re-identify the Library playlist, backfill imports, and resolve identity changes. For syncing multiple devices, the same logical Library must be mapped correctly to each device's local playlist IDs.

**Preserve existing user data throughout development**. Test destructive operations only on synthetic/isolated test data, with validated backups.

## Edge cases and safeguards

- **Duplicate rows:** Library has at most one membership per stream identity; regular playlists' ability to contain repeated entries is a separate product decision. Global removal from Library removes every occurrence from every ordinary playlist.
- **Removing Library as a playlist:** Do not allow an accidental deletion of the canonical Library list. Offer repair/re-designation if that playlist is missing after import.
- **Concurrent/failed writes:** If ordinary-playlist add succeeds but Library add fails (or vice versa), queue/retry reconciliation or use an atomic operation if supported. Show errors rather than silently violate invariants.
- **Bulk deletion:** Preview count and affected playlists and require an explicit confirmation; maintain rollback/restore information when feasible.
- **Offline/sync conflicts:** A deliberate global Library removal must be represented as a deletion event/tombstone so a second stale device cannot automatically resurrect the stream. This becomes important in the future Cloudflare sync design.
- **Same recording on different services:** Treat different uploads as different stream identities unless the user explicitly links/replaces them.
- **History/subscriptions:** Not automatically included unless the user saves them. “Watched/played” does not mean “saved.”
- **Tagging:** Tags belong to our separate metadata layer, associated with saved stream identities, not to a specific playlist.
- **Manual Unavailable list (next feature):** The agreed [Unavailable Playlist specification](unavailable-playlist.md) makes a second protected playlist a subset of canonical Library. Mark/unmark changes only its membership; global removal still removes everywhere.
- **Automatic Pruning:** The older [Pruning and Library Health proposal](../potential-features/pruning-library-health.md) remains deferred and is not part of the manual Unavailable implementation.

## Acceptance examples / tests

- Saving A to Favorites produces one A in Library; adding A to Workout leaves exactly one A in Library.
- Removing A from Workout leaves A in Favorites and Library.
- Removing A from Library removes A from every playlist and handles duplicates correctly.
- B can be saved only in Library and later added to a specific playlist.
- Playlist deletion alone does not remove Library entries.
- Backfill of pre-existing playlists preserves positions and is idempotent.
- Multiple supported media services work with the same behavior.
- UI, batch add, imports, and eventual MCP commands do not bypass the rule.
- A failed operation does not silently lose existing playlist data.
- Export/import and backups preserve Library semantics without duplicating streams.

## Decisions still open

1. **Display name:** “Library,” “All Songs,” or another label? The concept is fixed even if the label changes.
2. **Designation:** Create a protected built-in local playlist, or let the user designate any existing playlist?
3. **Deletion UX:** Confirm individual removals from Library every time, only for bulk actions, or configurable?
4. **Library ordering:** Date saved, manual reordering, or inherited from first playlist?
5. **Duplicates:** Allow duplicate occurrences in ordinary playlists; how should global removal treat them? (Proposed: remove all.)
6. **Imports/restore:** Automatic designation recovery, handling multiple playlists named Library.
7. **Cloud sync:** Exact conflict semantics and whether metadata/history persist after a global removal.
8. **Undo and trash:** Immediate destructive removal versus recoverable archive.

## Implementation priority

**First custom feature**, after verifying the clean upstream dev build. Implement and test this before tagging, MCP control, Cloudflare synchronization, or Pruning. Keep the patch small and isolated to make upstream dev merges easier.
