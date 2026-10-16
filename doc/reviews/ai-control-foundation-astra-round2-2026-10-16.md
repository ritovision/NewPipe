# AI Control foundation — Astra read-only follow-up, round 2

**Reviewer:** Codex GPT-6 Astra, xhigh, read-only sandbox; no coding/build/test authority.
**Reviewed checkpoint:** `fa2ad4d74` (source was being committed during review; no runtime changes from that commit).
**Verdict:** BLOCK. The six original findings are closed; two further UI integration issues and one test-synchronization gap are below.
**Evidence:** source inspection only. No Android runtime execution.
**Formatting:** Markdown trailing spaces normalized; reviewer wording unchanged. Original CLI response is retained in the review cache.

---

**BLOCK** — two P2 integration regressions remain, plus one nonblocking test gap. All six original findings are closed in their originally reported form.

1. **P2 — Recreating the view can silently undo a retained edit.**
   [LocalPlaylistFragment.java:288](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java:288) clears dirty state during loading. [The observer then installs the database snapshot](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java:410), even when [acceptLoadedBaseline declines it because the queue is running](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/library/PlaylistSaveQueue.kt:37).

   **Interleaving:** Start with `[A,B,C]`; delete A and queue `[B,C]`, with the worker delayed before writing. Destroy and recreate the same Fragment’s view. Its new read displays `[A,B,C]`. Delete C there, submitting `[A,B]`. [submit replaces the pending snapshot](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/library/PlaylistSaveQueue.kt:59), so the earlier deletion of A disappears. If the first snapshot was already extracted, the second can instead restore A using the queue’s updated committed baseline. No conflict necessarily occurs.

   **Correction:** Coordinate view initialization with retained queue state. Restore the pending editor snapshot, or wait for outstanding saves before accepting an editable database snapshot; preserve the relationship between each edit and its actual source baseline.

2. **P2 — Expected thumbnail conflicts reach an uncaught-exception handler.**
   [PlaylistEditGuard.kt:35](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/library/PlaylistEditGuard.kt:35) now intentionally throws for a globally removed thumbnail. Existing callers still omit error handling, including [BookmarkFragment.java:547](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/bookmark/BookmarkFragment.java:547) and [PlaylistAppendDialog.java:171](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/dialog/PlaylistAppendDialog.java:171).

   **Interleaving:** “Unset thumbnail” reads automatic candidate A; another catalog transaction globally removes A; the queued thumbnail update fails validation. The subscription has no error consumer, producing `OnErrorNotImplementedException`. [App.kt:201](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/App.kt:201) treats this as critical and forwards it to the uncaught-exception handler.

   **Correction:** Handle expected conflicts at every thumbnail caller. For automatic selection, derive the surviving candidate within the update transaction.

3. **P3, nonblocker — The disconnect test can pass without exercising rejection after a writer wait.**
   [AiCatalogExecutorTest.kt:528](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/androidTest/java/org/schabi/newpipe/custom/aicontrol/AiCatalogExecutorTest.kt:528) signals from the first clock call, which occurs **before authorization** at [AiCatalogExecutor.kt:28](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiCatalogExecutor.kt:28).

   **Interleaving:** Signal the latch; suspend the caller; the test disconnects the session and releases the writer; the caller resumes and fails initial authorization. All assertions pass without testing the transaction’s session recheck.

   **Correction:** Synchronize after successful authorization while the blocking writer remains held. The expiry case does establish a later deadline check because it captures the original clock value, although actual writer contention is still scheduling-dependent.

Original finding dispositions:

| Original | Status and current evidence |
|---|---|
| **1 — Lost save after detach** | **Closed as reported.** Departure captures the latest snapshot; the queue drains independently of view callbacks. Finding 1 above concerns subsequent view recreation. |
| **2 — Stale metadata overwrite** | **Closed.** [LocalPlaylistManager.java:235](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistManager.java:235) performs targeted transactional writes and thumbnail validation. |
| **3 — Reload wedges saving** | **Closed as reported.** Explicit reload waits for outstanding work; fresh unmodified reads now clear idle blocked state, pending data, and stale results. |
| **4 — Incompatible Library assertion** | **Closed.** [LibraryRepositoryTest.kt:309](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/androidTest/java/org/schabi/newpipe/custom/library/LibraryRepositoryTest.kt:309) expects completion and verifies the protected name remains unchanged. |
| **5 — Lost correlation** | **Closed.** The executor assigns the safely decoded envelope before semantic validation. |
| **6 — Permanent thumbnail cleared** | **Closed.** [AiCatalogStore.kt:226](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiCatalogStore.kt:226) uses the existing automatic-thumbnail policy; global deletion still clears affected pins. |

I inspected all **12 queue regressions**. The added recovery case correctly covers an idle blocked queue with pending edits, but none covers the Fragment interleaving above. Both bulk workers capture their original membership and verify it inside the writer transaction.

I found **no source-visible deadlock** in the new writer/session/concurrent-receipt tests. Concurrent-create assertions check one effect, one receipt, matching results, and one replay; blocking database reads occur after the worker futures complete.

This is source inspection only. I ran no builds or tests and changed nothing. The reported 228 JVM passes and both APK builds precede the additional regression; Android tests remain unexecuted. Deferred remote functionality and additional confirmations are not grounds for this verdict.
