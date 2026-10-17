# Tagging Milestone 1 adjudication

Candidate b55681983. Independent Astra xhigh: HOLD (3 P2); independent Claude Opus max: PASS WITH NONBLOCKERS (1 medium, 2 low). Both were read-only; neither ran tests. Coordinator evidence before review: 190 JVM, 12 new native persistence and 35 existing native Library tests passed on isolated API34 emulator.

- ACCEPT both lifecycle findings: Apply is now owned by a Fragment-scoped ViewModel, not a view subscription. It survives rotation and reports completion only to active observers. Successful sheet dismissal waits for RESUMED and an unsaved FragmentManager; onResume posts after the lifecycle event. Native regressions stop/rotate the host while a writer is delayed and require one durable write plus safe eventual dismissal.
- ACCEPT Astra metadata overwrite: tag edits resolve exact identity inside the Room transaction and use existing UID; sparse or stale editor metadata never overwrites a saved stream. Regression preserves newer title/views/thumbnail.
- ACCEPT both thumbnail race findings: playlist rename/thumbnail change now run synchronous narrow-column SQL under the mutation barrier and writer transaction. No Room query-executor thread blocks on TagRuntime. Bookmark reorder was audited too: only display_index changes, never stale thumbnails/names or resurrected playlist entities.
- ACCEPT Claude M2 hazard: Library snapshots use direct cursor reads rather than blocking on a scheduled Room Flowable under the lock. M2 change observers must schedule before querying.
- TRACK M3 release obligations: close/fence old repositories and pending intents across import, durable staged backup/restore, prevent matching integer Library IDs from authorizing pre-import intents, reject stale in-process queued writes; no production install before these gates. These are not represented as already implemented.

Coordinator revalidation of fixes: complete app/test APK build and style checks passed; 190 JVM tests passed. Actual Android API34 execution: 15 tagging repository tests, 2 retained-editor/background/rotation tests and 35 existing Library tests, **52/52 passed**. No physical-device instrumentation or real user data used.
