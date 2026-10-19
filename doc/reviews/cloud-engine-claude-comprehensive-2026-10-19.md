I found no P1 issues and four P2 issues. Verdict: approve this engine checkpoint, on conditions listed at the end.

**Scope:** I read `CANDIDATE.md`, the full `CANDIDATE.diff`, every Worker/D1 source file, the migrations and service doc, and on the native side the apply, compiler, plan, store, receipt, catalog and identity code. I also read the dependencies: `TagStore`, `TagRepository`, `TagRuntime`, `TagBackupCoordinator`, `CatalogFileSwap`, `CatalogOpenHelperFactory`, `LibraryRepository`, `UnavailableRepository`, `LibraryBindingStore`, `LocalPlaylistManager`, `StreamDAO`, `NewPipeDatabase` and the 9002 schema. I ran nothing, and I did not assume the reported test results or host-kill runs passed.

## P1

None found. I could not construct a reproducible data-loss, corruption or authority bug in the implemented paths within the declared scope.

## P2 findings

### P2-1: An unknown commit outcome is reported as HTTP 409, while the same situation elsewhere is 503
- **Where:** [index.ts:107](cloud/src/index.ts:107) maps every `AdmissionError` to 409 (or 403 for `AUTH_REJECTED`). `OUTCOME_UNKNOWN` comes from [admission.ts:70](cloud/src/admission.ts:70), [admission.ts:75](cloud/src/admission.ts:75), [admission.ts:114](cloud/src/admission.ts:114) and [admission.ts:141](cloud/src/admission.ts:141). The generic path at [index.ts:110](cloud/src/index.ts:110) returns 503 with `outcome:"unknown"` for the same meaning.
- **Scenario:**
  1. A publish's D1 batch throws a non-constraint error, such as a dropped connection.
  2. The receipt lookup finds nothing yet. The batch may still have landed, or may land later.
  3. The client gets `409 {"error":"OUTCOME_UNKNOWN"}`. A failure in `status()` at the same moment would have produced a 503.
  4. Variant: if the device is revoked between the batch and the lookup, `afterFailure` rethrows `AUTH_REJECTED` as 403 ([admission.ts:69](cloud/src/admission.ts:69)), even though the publish may have committed.
- **Impact:** The server stays safe. The sequence fence means a later `retire` either returns the committed receipt or retires the sequence. The risk is to the future client: 409 normally means a definite rejection, so a client following HTTP conventions could treat an unknown outcome as "not published".
- **Fix:** Return 503 plus `outcome:"unknown"` for `OUTCOME_UNKNOWN` and for any error raised inside `afterFailure`. State in `service-v1.md` that only receipts are definitive. Note that `UPLOAD_NOT_SEALED_OR_EXPIRED` is not proof of non-commit either, because an earlier publish attempt may have bound an earlier `now`.

### P2-2: Recovery cannot detect a lost tag-side journal when the Room witness did commit
- **Where:** [CloudNativeApply.kt:182-186](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:182) returns early when there is no pending journal, without looking at the Room witness ([CloudNativeApply.kt:85](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:85)) or the accepted baseline ([CloudNativeApply.kt:266](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:266)).
- **Scenario:**
  1. The Room commit and its witness survive on disk, but the tag database's earlier journal commit is lost. This needs a power loss under WAL with `synchronous=NORMAL`, or any rollback of the tag file.
  2. On the next start, recovery does nothing.
  3. Capture's `reconcile` ([CloudIdentityStore.kt:110-121](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudIdentityStore.kt:110)) gives the playlists the plan created fresh random portable IDs.
  4. The target's tags were never written, and tags on streams removed from Library become local orphans. The baseline still points at the old head.
- **Impact:** The next sync shows a spurious CONFLICT. Choosing "keep local" would push duplicate playlist identities and undo the remote's tag edits. The README disclaims power-loss durability, so this is hardening, not a correctness bug in scope.
- **Fix (cheap):** Enforce one invariant on every recovery: when there is no pending journal, a witness either doesn't exist or matches the baseline's operation (compare the incarnation as well). If it doesn't, raise `CloudRecoveryBlockedException` or a distinct "journal lost" state.

### P2-3: Running out of memory during recovery bypasses both the blocked state and the backup escape hatch
- **Where:**
  - Only `Exception` is wrapped as blocked: [CloudNativeApply.kt:182-194](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:182).
  - The restore escape hatch catches only `CloudRecoveryBlockedException`: [TagBackupCoordinator.kt:176-186](app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:176).
  - The journal may be up to 64 MiB ([CloudApplyStore.kt:13](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyStore.kt:13)).
  - Decoding holds several full copies at once: the reassembled payload ([CloudApplyStore.kt:73-88](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyStore.kt:73)), then a text copy, a parse tree, the catalog serialized again and parsed a second time ([CloudApplyPlan.kt:82-93](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyPlan.kt:82)).
  - `apply` also holds the compiled plan, its bytes and the decoded plan inside the Room write transaction ([CloudNativeApply.kt:70-71](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:70)).
- **Scenario:** A plan of tens of MB is allowed by the native limits (500k memberships). If decoding it during recovery throws `OutOfMemoryError`, that error escapes every `TagRepository.access` and `TagRuntime.mutate` call and also escapes export and `installWithShutdown`. Even the documented full-backup restore then fails.
- **Impact:** Not reachable at the deployed 64 KiB / 8-page bound or the server's 32 MiB page cap. It becomes a real gate as soon as service limits are raised.
- **Fix:** Cap the plan size at compile time to a heap-based or server-matched budget. Decode in a single pass. Convert any `Error` raised before a write into the blocked state.

### P2-4: Once recovery is blocked, the user cannot export, so the escape hatch only helps users who already have a backup
- **Where:** Export calls `beforeLibraryMutation()` ([TagBackupCoordinator.kt:230](app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:230)), which only tolerates the identity-repair exception ([TagRepository.kt:37-45](app/src/main/java/org/schabi/newpipe/custom/tagging/TagRepository.kt:37)). The engine itself takes no safety backup before journaling ([CloudNativeApply.kt:73-84](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:73)).
- **Scenario:** Any blocked state leaves all Library and tag mutation blocked and export failing. Examples include a corrupt payload chunk, `CLOUD_RECOVERY_TAGS_CHANGED`, a witness mismatch or a main-state mismatch. The only way out is a full backup made earlier, which rolls back everything since.
- **Impact:** Nothing is destroyed, but the user's current data is stuck inside the app. This is different from the earlier escape-hatch finding: the problem here is that the needed backup can't be created after the fact.
- **Fix:** Either make "a validated full backup taken immediately before apply" a hard precondition that the engine checks, or allow export while cloud recovery is blocked. The second option is safe because export already discards the pending journal, baseline, identity and witness, so the exported pair is a consistent, unenrolled local catalog.

## Verified in source
- **Retirement:** marking the upload RETIRED, deleting its payload, writing the receipt and advancing the device sequence all happen in one batch ([service.ts:308-322](cloud/src/service.ts:308)). A staged upload cannot be retired under a different digest. A retry after a lost acknowledgment gets the stored receipt. Races between start and retire, and between publish and retire, are fenced by the "previous sequence" check.
- **Publication:** seal rebuilds and validates the complete target and its fingerprint. Publish is atomic over pages, catalog info, head and receipt, and is fenced by head and sequence. Every download page is fenced to the same head. Authority comes only from the token, and there is no SQL over HTTP.
- **Native apply:**
  - The journal is durable before any Room write, and the witness commits in the same Room transaction as the changes.
  - Without a matching witness, recovery proves the before-state and only clears the intent. With one, it checks the exact main projection and the tag revision.
  - A final check of fingerprint, mappings and orphans runs before the baseline is accepted.
  - New IDs are assigned above the autoincrement high-water mark, so they are never reused.
  - Deleting before inserting, plus temporary name keys, avoids unique-constraint collisions. Category links are cleared first, so deleting a category doesn't cascade to its tags.
  - Recovery now opens Room before the tag database.
  - Every Room and tag writer in this packet goes through the shared lock and resolves cloud recovery first.
- **Backups and migrations:** backups strip the receipt, pending journal, baseline, mappings and binding markers. Migrations 9→9001→9002 and tag schema 4→5 match the schema file.

## Optional improvements
- `writeTags` bumps every Library key's stream revision and the catalog revision on every apply, even reorder-only ones ([CloudNativeApply.kt:363-382](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:363)). That is up to about 100k statements and invalidates every open editor. Limit it to keys whose tags changed or that left Library.
- Hint thumbnails and text skip any URL-scheme check ([CloudApplyCompiler.kt:87-96](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyCompiler.kt:87)). Allow only http(s).
- The library-wide upload budget counts expired stages and stages belonging to revoked devices ([service.ts:171-173](cloud/src/service.ts:171)), and a revoked device cannot retire them. Before revoking the QA tokens, confirm they have no UPLOADING or SEALED rows. If test state is torn down, rotate the epoch rather than rewinding the head counter.
- Orphan keys aren't validated at capture but are validated when the plan is decoded ([CloudApplyPlan.kt:150-159](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyPlan.kt:150)). An orphan with a blank or over-long URL would make every apply fail before journaling. This fails safe.
- `admit()` runs while both the shared lock and Room's write lock are held ([CloudNativeApply.kt:72](app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:72)). The future caller must not do network I/O there.

## Unverified assumptions
- **D1:** that batches roll back as a whole, foreign keys are enforced and operations are serialized on the primary. Also, predicates use the Worker's `Date.now()`, so a delayed batch can commit after its stage has nominally expired. That is harmless because of the sequence fence.
- **Android SQLite:** whether WAL runs with `synchronous=NORMAL` or `FULL` decides whether P2-2 can happen. I also assumed Room turns on foreign keys when the database opens, which the playlist-deletion cascade relies on.
- **Writers outside the packet:** I assumed upstream code (for example, other callers of `StreamDAO` and `PlaylistDAO`) routes playlist writes through `LocalPlaylistManager` or `TagRuntime`. I only checked this for files in the packet.
- **Tests and runs:** the 194/330/258 test counts and the three host-kill runs are not evidence I checked.
- **Out-of-scope callers:** preview, connection generation, safety backup and transport are absent by declaration.

## Verdict
Approved for the bounded engine checkpoint, on these conditions:
- **P2-1:** fix before any Android transport client is written.
- **P2-4:** make a pre-apply full backup, or export while blocked, a hard gate before any caller touches real data.
- **P2-2 and P2-3:** resolve before raising the service limits or rolling out.

APPROVE_ENGINE_CHECKPOINT
