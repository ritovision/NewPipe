# GPT-6 Astra Extra High — Independent Library Polish Review

**Review result:** PASS WITH NONBLOCKERS
**Date:** 2026-10-16
**Reviewed code:** `feature/library-polish` at `f98fa3092`, versus `fed10c6dd`.
**Permissions:** Read-only source review, no builds, tests, installations, commits or device changes.
**Status:** Independent reviewer observations, pending explicit adjudication of nonblockers. Other reviewer conclusions are recorded separately.

---

**Verdict: PASS WITH NONBLOCKERS.** I recommend merging this increment and proceeding with the planned custom/dev installation for manual Pixel 7 validation. I found no Critical, High, or Medium regression in the committed delta. There is one Low startup UI issue and one Low inherited thumbnail race worth addressing surgically.

I reviewed `fed10c6dd..f98fa3092`, confirmed branch `feature/library-polish`, HEAD `f98fa3092a7548a8ea0c9f29029f5a88e40aef50`, and a clean working tree. This review was independent: I did not consult another review. I performed source and diff inspections only; I did not modify files, build, execute tests, contact external services, or touch the Pixel installation.

1. **Low — An ordinary playlist named Library can retain hidden menu actions after initialization.** [LocalPlaylistFragment.java:299](/home/rito/techstuff/ritoverse/newpipe/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java:299)

   Moving visibility decisions into `onPrepareOptionsMenu()` improves the previous behavior, but identity publication does not itself request another menu preparation. If a restored ordinary playlist named Library prepares its menu before initialization finishes, the conservative guard hides Rename, Remove watched, and Remove duplicates. Binding a different canonical ID subsequently changes the policy result, but the prepared menu retains its existing visibility until another preparation or recreation occurs.

   This matters particularly on a standalone playlist screen: Share can occupy the action bar while all overflow entries are hidden, leaving no overflow button to reopen. Navigation or recreation recovers the actions; the title-click rename guard already reads current identity.

   **Minimal remedy:** subscribe to identity changes for the fragment’s view lifetime and invalidate the options menu on the main thread. Keep the existing policy and action-time guards. This is a source-traced UI issue, not a device reproduction, and does not grant destructive authority.

2. **Low, inherited from the base — A pending ordinary “Remove watched” operation can overwrite a thumbnail using its stale stream list.** [LocalPlaylistViewModel.kt:89](/home/rito/techstuff/ritoverse/newpipe/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistViewModel.kt:89), [LocalPlaylistViewModel.kt:129](/home/rito/techstuff/ritoverse/newpipe/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistViewModel.kt:129)

   A concrete interleaving is: an ordinary playlist contains watched thumbnail A followed by unwatched B and C; bulk removal captures `[B, C]`; global Library deletion removes B; the pending `updateJoin()` correctly filters B out and stores `[C]`; its subsequent thumbnail update still selects B from the original `itemsToKeep`.

   Membership remains correct, but the playlist can display B’s retained stream thumbnail despite containing only C. The thumbnail write occurs after the membership transaction and uses the earlier snapshot.

   **Minimal remedy:** derive the automatic thumbnail from current surviving membership, ideally within the membership-update transaction, while preserving current permanent-thumbnail protection. I checked the base revision: this interaction already existed with the previous removal-marker filter. It is advisory for this increment, not a newly introduced regression.

The central identity correction is otherwise sound. [LibraryIdentityPolicy.kt:8](/home/rito/techstuff/ritoverse/newpipe/app/src/main/java/org/schabi/newpipe/custom/library/LibraryIdentityPolicy.kt:8) gives the bound ID exclusive precedence; names are considered only when no session is bound. Both fragment guards delegate to it. Consequently, after binding, an ordinary duplicate named Library follows ordinary rename, deletion, bulk-edit, and thumbnail-menu behavior whenever those UI decisions are evaluated. Repository global deletion independently requires the verified canonical ID, so a name collision cannot authorize removal everywhere.

The artwork integration is appropriately narrow. [LocalPlaylistItemHolder.java:42](/home/rito/techstuff/ritoverse/newpipe/app/src/main/java/org/schabi/newpipe/local/holder/LocalPlaylistItemHolder.java:42) covers the shared local-playlist holder, including its bookmark, grid/card, and Add To uses. The vector supplies white books against `@color/ic_launcher_background`, whose upstream definition is exactly `#CD201F`. Its placement leaves room for the existing count overlay. Neither the playlist schema nor stored thumbnail representation changes.

[LibraryArtwork.kt:20](/home/rito/techstuff/ritoverse/newpipe/app/src/main/java/org/schabi/newpipe/custom/library/LibraryArtwork.kt:20) closes the previous binding when a holder is rebound. Detachment disposes the identity subscription; attachment reconnects and obtains current identity through the replaying subject. Resetting the render state on reconnection allows a detached view to reflect identity changes that occurred while it was absent. The subject therefore addresses asynchronous initialization even when no playlist metadata change causes a RecyclerView rebind.

Both ordinary thumbnails and canonical artwork use Coil requests targeting the same `ImageView`. This preserves Coil’s request-replacement mechanism instead of placing a drawable over an outstanding network request. The canonical resource request also bypasses the image-download preference, while ordinary images retain the existing `CoilHelper` behavior. I found no source-level path that permanently assigns canonical artwork to a same-named ordinary playlist. Actual scrolling, crossfade, and attachment behavior still require Android validation.

The stale-editor correction removes the previous rollback inconsistency at its source. There is no longer a `recentlyRemoved` set whose contents can disagree with a failed database commit. [LocalPlaylistManager.java:87](/home/rito/techstuff/ritoverse/newpipe/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistManager.java:87) invokes the canonical-membership filter inside the same Room transaction that replaces ordinary playlist joins. The small-list branch queries membership there; the larger-list branch reads canonical membership once and filters against that set. Both preserve proposed order and deliberate ordinary duplicates.

Normal create and append operations bypass that rewrite filter and add canonical membership through the existing insertion hooks. They can deliberately restore a globally removed item. Ordinary deletion, reordering, duplicate cleanup, and watched removal retain their intended membership semantics. When identity is unbound, the filter returns the proposed list rather than treating a missing authority as an empty Library and stripping ordinary content. Initialization backfills before publishing the active session within the writer transaction. I found no ordinary CRUD path in scope that invalidates that membership invariant during normal bound operation.

The new deletion SQL is correct for its transaction. [LibraryRepository.kt:279](/home/rito/techstuff/ritoverse/newpipe/app/src/main/java/org/schabi/newpipe/custom/library/LibraryRepository.kt:279) deletes every occurrence of the selected stream, then updates only playlists whose thumbnail references that stream. Its correlated query selects the surviving stream with the lowest `join_index`; gaps and duplicate memberships do not break selection. Empty playlists receive `-1`, matching `DEFAULT_THUMBNAIL_ID`. A pin referencing the deleted stream is cleared and replaced; a pin referencing another stream is untouched. The inherited later-write race described above does not invalidate this SQL’s selection logic.

Deletion failure handling also meets the requested behavior. [LocalPlaylistFragment.java:627](/home/rito/techstuff/ritoverse/newpipe/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java:627) invokes the existing snackbar helper on the main thread. Failure does not remove the adapter item or invoke the fragment’s full-screen error panel. Opening detailed error reporting remains an explicit snackbar action.

The maintenance boundary remains narrow: this increment changes three upstream Java integration files, with the implementation concentrated in `custom/library` and owned resources. `LibraryBindingStore`, `LibraryBackupRestore`, the DAO, and the entity introduce no new schema or restore-format changes in this delta. No Tags, cloud, or MCP functionality appears.

Validation must remain precisely qualified. **According to the supplied evidence, 176 JVM tests executed and passed with zero failures.** `:app:assembleDebug` and `assembleDebugAndroidTest` also succeeded. **Android instrumentation tests compiled but did not execute.** I did not rerun any checks beyond read-only diff inspection.

The added JVM tests cover identity decisions and order-preserving membership filtering. The “rollback” policy test supplies an assumed post-rollback set; it does not induce a Room commit failure. Likewise, the instrumentation identity-observer test checks emitted IDs, not an attached `ImageView` or Coil cancellation. Manual Pixel validation should therefore concentrate on cold-start duplicate-name menus, scrolling and reopening Add To, thumbnail replacement with surviving entries, and deletion-error feedback. These remaining runtime checks do not constitute a merge blocker, and the existing Pixel app remains untouched.
