# Library polish — 2026-10-16

**Status:** Original polish independently reviewed by Claude Opus Max and GPT-6 Astra xhigh: both PASS WITH NONBLOCKERS. All six follow-up findings are addressed below; validation and focused re-review in progress.
**Base:** fed10c6dd (Library v1 sign-off).
**Scope:** Small UI identity fixes, permanent artwork, thumbnail fallback, transaction rollback correctness, and non-disruptive error feedback. No Tags, cloud service, or schema change.

## Behavior

The verified canonical playlist ID, not its label, determines special behavior.
An ordinary playlist may still be named Library; it remains editable, removable,
and unable to authorize global deletion. No name reservation is introduced.
While the identity is unbound, the existing conservative name-based protection
remains. It is not authorization to delete globally or display canonical artwork.
Both existing screen guards delegate to one pure identity policy; menus re-evaluate
at prepare time so a menu inflated before initialization does not stay disabled.

Canonical Library rows show a white bookshelf on NewPipe's own
`@color/ic_launcher_background` red (`#CD201F`, defined in the upstream main
colors.xml). The artwork is a local vector and is not tied to a saved video,
network access, or image-download preferences. Its glyph is in the left half
so NewPipe's existing count panel does not cover it. The count panel and playlist
layouts themselves are unchanged. Ordinary playlists retain their normal thumbnails.

One shared local-playlist holder hook covers bookmarks (including grid/card
variants) and Add To. Our own artwork helper uses Coil requests, avoiding late
thumbnail requests overwriting the icon on recycled views. It observes identity
changes only while the ImageView is attached, so asynchronous initialization or
manual rebind updates already visible rows. Rebinding a holder removes its old
subscription and listener. Artwork grants no permissions. A canonical Library
item's menu no longer offers Set as playlist thumbnail, because its artwork is fixed.

Global deletion now picks the first surviving stream in each affected playlist
for its thumbnail (or the normal default for an empty playlist). An unaffected
manually pinned thumbnail remains unchanged. Library's own displayed artwork is
independent of this field.

The failed-COMMIT marker bug is removed at its source: deletion no longer keeps
any in-memory removal markers. A stale ordinary editor is filtered against the
actual canonical Library membership inside the same Room write transaction.
Successful deletion removes those memberships; rollback preserves them. There is
no second state store to disagree with the committed database. Create/append remain
the explicit save paths, and can re-add previously removed items. Small edits use
per-ID checks; large edits read the Library membership once. This preserves existing
order and ordinary duplicate entries without permitting editor rewrites to invent
new saved memberships. Deletion errors use the existing snackbar.

## Files and maintenance boundary

Owned package: `app/src/main/java/org/schabi/newpipe/custom/library/`
- LibraryIdentityPolicy.kt — pure bound-ID-first UI decision.
- LibraryEditPolicy.kt — pure order-preserving filter over canonical membership.
- LibraryArtwork.kt — local image request and attachment-scoped identity observation.
- LibraryRepository.kt — existing coordinator; small delegation, notification, and SQL changes.

Owned resources:
- drawable/rv_library_thumbnail.xml — white bookshelf and logo-red background.
- values/library_artwork_ids.xml — keyed tag for the thumbnail binding.

Existing upstream source files edited in this polish increment:
1. LocalPlaylistFragment.java — guard, menu visibility, thumbnail action and snackbar.
2. BookmarkFragment.java — shared UI identity guard.
3. LocalPlaylistItemHolder.java — one thumbnail-loading delegation.

Before the follow-up fixes, the whole Library feature touched **six** upstream source files:
those three plus MainActivity, LocalPlaylistManager and ImportExportManager from
prior milestones. No NewPipeExtractor, playback engine, upstream Room schema,
playlist layout, separate repository, or independent service is introduced.

## Validation and review gate

Run the JVM regression suite, style checks, application build and instrumentation
APK compilation on @NotCodex-2. Do not launch emulators or execute instrumentation
on the phone; the user chose manual Pixel 7 testing. Added pure JVM tests cover
same-name/initialization policy and canonical-membership filtering after removal,
rollback and explicit re-add. These are pure policy tests, not executed Android COMMIT-failure tests. Additional Room tests cover duplicate names,
identity notifications and thumbnail fallback; compilation is not execution.

Before merging, request independent Claude Opus Max and GPT-6 Astra xhigh reviews.
Preserve the full replies and adjudications under doc/reviews. Re-review any required
runtime fixes. Then build custom/dev, verify its established package/signing identity,
and update only the existing custom Pixel 7 installation, leaving F-Droid separate.

## Deliberately unchanged

The previously documented narrow stale-editor interaction after an *explicit*
re-add is not redesigned: a stream the user deliberately saves again may return to
an older still-open playlist editor. It does not authorize global deletion or make
an unsaved stream reappear after restart. Durable cloud tombstones and cross-database
tag cleanup remain future-feature responsibilities. Existing import crash-consistency
limitations are not represented as solved by this polish.

## Recorded pre-review evidence

176 JVM tests passed (13 added pure policy tests). Both APKs compile and style checks pass.
The actual thumbnail UPDATE SQL was exercised against in-memory SQLite for nonempty/empty
playlists, duplicates, unaffected pinned images and rollback. Vector/resource checks confirm
white artwork and the upstream #CD201F logo red. Android instrumentation was not executed.


## All review follow-ups — 2026-10-16

The user requested that every remaining finding from both independent reviews be
fixed. This follow-up implements all six distinct findings (the startup-menu
finding was shared by both reviewers):

| Finding | Implemented correction |
| --- | --- |
| Menu actions stay hidden after asynchronous identity binding | LocalPlaylistFragment observes identity for its view lifetime and invalidates its activity menu on the main thread. That subscription is separate from playlist-load disposables and ends at onDestroyView. |
| Duplicate artwork request at bind/reattach | LibraryArtwork retains the last rendered identity on reconnect. Unchanged replay events do not restart Coil; genuine identity changes still re-render. |
| Startup opens Repair/Remove everywhere on the wrong playlist | Name-only protected actions first await initialization on the IO scheduler, then route by verified ID. Genuine unbound data still offers repair. A stale repair confirmation atomically checks whether a canonical ID has already appeared; it returns to ordinary actions rather than adopting another playlist. Repair failures use a snackbar. |
| Identity checked before acquiring the database writer lock | removeEverywhere checks the current bound identity inside the Room transaction, after any writer-lock wait, so an intervening restore suspension refuses the operation. Native multi-file import crash-atomicity is unchanged. |
| Tab-picker artwork missing | SelectPlaylistFragment uses the shared LibraryArtwork loader for local rows. Remote rows explicitly discard any old local artwork binding before normal thumbnail loading, so recycling cannot give them Library artwork. |
| Pending Remove Watched overwrites thumbnail from a stale snapshot | An updateJoin overload refreshes automatic thumbnails from the actual surviving membership within the same transaction. The ViewModel no longer writes an image from its stale itemsToKeep snapshot afterward. A newly pinned thumbnail is guarded at write time. |

### Boundary changes

These follow-ups add two upstream integration files: SelectPlaylistFragment for
the previously uncovered image surface, and LocalPlaylistViewModel to remove the
stale post-transaction thumbnail write. The complete Library feature now touches
**eight upstream source files**: MainActivity, ImportExportManager,
LocalPlaylistManager, LocalPlaylistFragment, BookmarkFragment,
LocalPlaylistItemHolder, LocalPlaylistViewModel and SelectPlaylistFragment.
Owned logic stays in custom/library; no new service, database migration, extractor
patch, repository or dependency was introduced.

### Regression coverage and limitations

Seven additional Room instrumentation cases cover current thumbnail selection,
empty/pinned behavior, atomic thumbnail rollback, stale repair confirmation,
explicit repair while unbound, and a queued deletion whose identity is suspended
while it waits for the writer lock. This brings LibraryRepositoryTest to 32 cases.
They will be compiled, **not executed on an emulator or the phone**, per the user's
manual-device-testing instruction. A source-extracted automatic-thumbnail UPDATE
was exercised separately with five in-memory Python SQLite assertions (surviving
row, pin preservation, empty default, unaffected playlist and rollback). This is
SQL-level evidence, not Android Room/Coil/Fragment runtime evidence.

The original narrow stale-editor interaction after an intentional re-add, future
cloud/tag coordination and existing native restore crash-consistency limitations
are unchanged; they were not among these six follow-up findings.
