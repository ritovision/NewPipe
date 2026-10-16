# AI Control foundation — Astra read-only review, round 1

**Reviewer:** Codex GPT-6 Astra, xhigh, read-only sandbox; no code generation/edits/build/test authority.
**Target:** uncommitted feature/ai-control foundation based on b9a8f28d3.
**Verdict:** BLOCK. Full raw reply below; findings are addressed by the author in the follow-up revision.
**Evidence:** source inspection only, not an Android test run.
**Formatting:** trailing Markdown spaces normalized; reviewer wording unchanged. Original CLI output retained in the local review cache.

---

**BLOCK** for this foundation because of the UI integration issues below. These are source-verified findings and interleavings, not Android reproductions.

1. **P2 — Newer dirty edits can be lost when leaving the playlist screen.**
   [LocalPlaylistFragment.java:673](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java:673) refuses another save while one is running. Only its completion callback submits the newer snapshot at line 695, but [view destruction disposes that callback](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java:349).

   **Interleaving:** Save snapshot S1; make another deletion/reorder producing S2 while S1 is pending; navigate away before its completion callback. `onPause()` cannot submit S2 because saving is already in flight. S1 can commit, while S2 is never submitted and disappears with the adapter.

   **Correction:** Keep the pending snapshot and serialized save coordinator alive independently of the view. Departure must preserve or submit the latest edit without depending on a disposable UI callback.

2. **P2 — UI metadata updates can still overwrite committed AI changes.**
   [LocalPlaylistManager.java:217](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistManager.java:217) reads a playlist, changes one property, and updates the entire entity at line 229. The read and write share no transaction. The bookmark-order fix does not cover this path.

   **Interleaving:** A UI thumbnail update reads name “Before”; AI [renames it to “After”](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiCatalogStore.kt:70) and commits its receipt; the UI then writes its stale entity, restoring “Before”. Conversely, a pending UI rename can restore a thumbnail that global deletion just cleared.

   **Correction:** Update only the intended metadata columns, with any required thumbnail validation inside the writer transaction. This is an existing helper that remains unsafe with the new AI writers.

3. **P2 — Reload during a pending save can permanently disable saving for that view.**
   [Reload clears the save subscription](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java:273), but does not reset `playlistSaveInFlight`. Its reset normally happens in callbacks that disposal suppresses.

   **Interleaving:** Encounter a conflict, leaving the Reload snackbar visible; make another edit that starts a save waiting behind another writer; press [Reload](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java:724). The list reloads, but every subsequent save returns at line 673 until the view is recreated.

   **Correction:** Reconcile save ownership and in-flight state when reloading or cancelling subscriptions, including operations that continue executing after disposal.

4. **P2 — The changed bookmark contract leaves an existing Android regression test incompatible.**
   [LibraryRepositoryTest.kt:309](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/androidTest/java/org/schabi/newpipe/custom/library/LibraryRepositoryTest.kt:309) expects `IllegalStateException` when bookmark metadata contains a different Library name. The new [order-only update](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistManager.java:150) ignores that name and completes successfully.

   **Reproduction:** Run `boundLibraryMetadataCannotBeRenamedByBookmarkUpsert`; its exception assertion contradicts the implementation. This is a predicted failure from source, not an executed result.

   **Correction:** Update the test to assert successful order-only behavior while verifying that protected metadata remains unchanged. Preserve the new safer implementation.

5. **P3, nonblocker — Semantic validation failures lose valid request correlation.**
   [AiProtocol.kt:209](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiProtocol.kt:209) validates arguments before returning the parsed request. [AiCatalogExecutor.kt:25](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiCatalogExecutor.kt:25) therefore never assigns `request` when validation fails.

   **Reproduction:** Supply valid identifiers with `list_playlists.limit = 51`. The response is rejected with empty `request_id` and `device_id`, despite both being available in the valid envelope.

   **Correction:** Preserve safely validated correlation fields across subsequent command-validation failures; malformed envelopes can remain uncorrelated.

6. **P3, nonblocker — AI membership operations unexpectedly clear permanent thumbnails.**
   [AiCatalogStore.kt:226](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiCatalogStore.kt:226) clears permanence whenever the thumbnail stream is absent from that playlist. Existing [UI thumbnail maintenance](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/library/LibraryRepository.kt:391) preserves permanent thumbnails.

   **Reproduction:** Pin A, then remove A locally while retaining it in Library. An AI add of already-present B—even reporting zero added memberships—runs thumbnail refresh and replaces the pin.

   **Correction:** Align AI and UI thumbnail semantics and test this case. The behavioral discrepancy is verified; the intended policy for a surviving Library item’s pin needs to be explicit.

Coverage: I read the architecture document first, all five new AI source files, `PlaylistEditGuard`, every requested tracked modification, schema 10, all four new test files, and relevant callers/helpers. The same-transaction mutation/receipt structure, replay protection, scope checks, exact stream-reference validation, and additive 9→10 migration look consistent on inspection. I found no verified alternate-command permission bypass or deadlock in the new tests.

No builds or tests were run. The new tests do not establish writer-wait deadline/revocation behavior, fragment lifecycle correctness, or concurrent commit behavior; compilation supplies no such evidence. Migration/import compatibility was inspected, not executed. Deferred pairing, restore/stop wiring, networking, playback, extraction, and background-service phases were excluded from the verdict.
