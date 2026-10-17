**HOLD** — source review of frozen `ac12df5df0efb4125a304c6a7c0561a70c23d871`. One concrete blocker remains.

**P2 — Add to Queue can replace the currently selected auto-queued item.** [PlayQueue.java:320](/home/rito/techstuff/ritoverse/newpipe-tag-review-ui/app/src/main/java/org/schabi/newpipe/player/playqueue/PlayQueue.java:320) removes an auto-queued tail without checking whether it is selected.

Reachable scenario: A automatically queues B; disable automatic queuing before B starts. While B plays at the tail, append saved results C, D. `[A, B]`, index `1`, becomes `[A, C, D]`, index `1`: C silently becomes selected and B disappears. Both native and AI append reach this method through [AiAndroidPlaybackPort.kt:98](/home/rito/techstuff/ritoverse/newpipe-tag-review-ui/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiAndroidPlaybackPort.kt:98).

Minimal fix: restrict automatic-tail removal to a tail strictly **after** the selected index, retaining the existing backup cleanup. Extend [AiPlaybackAndroidTest.kt:58](/home/rito/techstuff/ritoverse/newpipe-tag-review-ui/app/src/androidTest/java/org/schabi/newpipe/custom/aicontrol/AiPlaybackAndroidTest.kt:58) with a selected auto-queued tail and assertions preserving the current item through append/unshuffle. Its existing scenario covers ordered suffixes and duplicates, but never marks an item auto-queued.

Original findings are explicitly resolved as follows:

- **UI-1 closed:** forms carry observed `expectedRevision`; repository/store guards reject concurrent changes atomically without overwriting AI edits.
- **UI-2 original ordering defect closed:** incoming order and duplicates survive shuffled append/unshuffle. The separate selection blocker above prevents overall queue acceptance.
- **UI-3 closed in source:** child dialog ViewModels retain drafts, saves and results across rotation. Retained operations capture repository/value objects, not destroyed UI callbacks. Lifecycle-bound delivery selects the created tag in the current editor once; failures retain the draft.
- **UI-4 closed:** local playback resolves exact saved references with native eligibility; the remote reader retains strict host validation.
- **UI-5 closed:** receipt replay and lookup preserve required IDs, revisions, deletion counts and bounded tag-ID lists without retaining names.
- **UI-6 closed:** tag/category deletion and assignment replacement advertise `destructiveHint:true`.
- **Adjudicated data findings 1–3 closed in source:** repair acquires locks in the corrected order; schema validation rejects triggers/views before name filtering; guarded database recovery precedes binding reads.

Category deletion still preserves videos and playlist memberships. Captured result lists are not silently truncated or recomputed at dispatch; playback limits reject oversized requests explicitly. Existing grants remain unchanged, devices are explicit, capabilities are checked, native Library UI requires no AI opt-in, and inspected production paths inject no fixtures.

Supplied evidence remains R7 compile/style/307 JVM and 54 gateway passes, plus prior R5 native/20k/theme acceptance. R7 native, form-lifecycle and actual process-death acceptance remain pending; they are separate from this source blocker. I read regression scenarios without running them and performed no edits, builds, device actions, deployments, agents or Git writes.

This source verdict grants no production deployment approval and makes no claim of Claude review.