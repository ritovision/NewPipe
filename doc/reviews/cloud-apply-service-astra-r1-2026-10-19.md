Found **one P1 and four P2 corrections** in implemented paths. These are source-derived reproductions; I executed no tests, builds, network requests, or mutations.

1. **P1 — Recovery opens tags before native file-swap recovery.**
   [CloudNativeApply.kt:176](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:176) opens the tag helper before the guarded Room helper. The new call at [TagRepository.kt:51](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/custom/tagging/TagRepository.kt:51) makes this happen during ordinary startup, including when cloud has never been enrolled.

   **Reproduce:** Interrupt native backup installation after `new:ritovision-tags.db`, before the swap’s COMMITTED marker. Restart and call `definitions()` before another Room access. The tag helper opens the replacement tags; Room subsequently invokes file-swap recovery at [CatalogOpenHelperFactory.kt:41](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/custom/tagging/CatalogOpenHelperFactory.kt:41), restoring the old files while that tag connection remains open. The process can then read imported tags alongside restored old Room content, with subsequent writes encountering a replaced database file.

   **Minimum correction:** Complete guarded file-swap recovery before opening or querying tags, including the no-pending fast path. Extend the restore regression beyond its current failure point before tag replacement, and verify writes survive reopening.

2. **P2 — Retirement with a mismatched digest permanently strands an active upload.**
   [service.ts:310](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/cloud/src/service.ts:310) retires the sequence without checking its staged digest. The subsequent status update requires that digest, while payload deletion at lines 315–316 requires only the sequence. The retirement guard at [admission.ts:125](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/cloud/src/admission.ts:125) does not inspect uploads.

   **Reproduce:** Stage sequence `1`, digest A; retire sequence `1`, digest B. Retirement succeeds and deletes A’s payload, but A remains `UPLOADING` or `SEALED`. Sequence `2` is blocked by [service.ts:171](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/cloud/src/service.ts:171). Retrying retirement with A produces `REPLAY_MISMATCH`; retrying B leaves the active row unchanged.

   **Minimum correction:** Fence retirement against any staged operation’s identity inside the serialized transaction, and keep terminal upload state and payload cleanup consistent with the receipt. A separate preflight check would leave a race.

3. **P2 — Applied artwork-only streams remain eligible for native deletion.**
   [CloudApplyCompiler.kt:63](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyCompiler.kt:63) deliberately creates stream rows for permanent artwork outside Library membership. However, [StreamDAO.kt:112](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/database/stream/dao/StreamDAO.kt:112) treats rows without history, memberships, or feed references as disposable; it does not protect playlist artwork references.

   **Reproduce:** Apply an empty Library with an ordinary empty playlist whose permanent artwork references a new key. Invoke the existing `deleteOrphans()` DAO operation. It deletes the artwork row while retaining the playlist’s reference. Capture then fails at [CloudNativeCatalog.kt:214](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeCatalog.kt:214). If cleanup runs between Room publication and pending recovery, witness completion also fails.

   **Minimum correction:** Protect referenced permanent-artwork rows during native orphan cleanup. Do not silently add artwork to Library membership.

4. **P2 — Preserved orphan assignments can exceed the receiver’s bound after Room commits.**
   [CloudApplyCompiler.kt:40](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyCompiler.kt:40) validates orphan compatibility but does not bound preserved orphan relations plus target assignments. Final verification at [CloudNativeApply.kt:239](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:239) reads both together, subject to the 500,000-row limit at [CloudNativeCatalog.kt:284](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeCatalog.kt:284).

   **Reproduce:** Start with 500,000 valid orphan relations, retain their definitions, and apply a target adding one saved stream with one assignment. The target independently passes validation, and the plan can remain below its byte ceiling. Room commits; final tag verification encounters 500,001 relations and rolls back tags. Every recovery repeats that failure, leaving native/tag-dependent access blocked.

   **Minimum correction:** Validate the complete resulting native assignment count, including retained orphans, before journaling or committing Room.

5. **P2 — A blocked cloud journal prevents restoration of a valid complete native backup.**
   [TagBackupCoordinator.kt:176](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:176) requires pending recovery before installation and bypasses only `NativeBulkRecoveryBlockedException`. Cloud recovery failures propagate instead.

   **Reproduce:** Keep a valid complete backup; interrupt apply after Room commits; corrupt the pending payload, as the supplied corruption test already models. Attempt to install that backup. Installation fails with `CORRUPT_CLOUD_PAYLOAD_DIGEST` before replacing either store. The existing recovery mechanism therefore cannot recover this newly introduced blocked state.

   **Minimum correction:** Distinguish blocked cloud recovery and permit an explicitly selected, validated complete backup to replace both stores safely. Backups retaining live tags must remain blocked. This requires correcting existing import integration, not implementing the deferred cloud UI.

The **disabled/small synthetic deployment boundary is legitimate in source**: the API gate precedes authentication, and configured payload/page limits are checked during staging and target validation. The checked-in configuration specifies 64 KiB and eight pages. That boundary does not remove these corrections, particularly finding 1, which affects ordinary native startup. Production UI, R2, full synchronization, abandoned-upload housekeeping, daily-account budgets, and receipt retention remain later gated work.

Optional note: [cloud/README.md:3](/home/rito/.cache/newpipe-cloud-apply-service-astra-20261019/source/cloud/README.md:3) still describes apply, staging, and Wrangler configuration as absent; update it to match this checkpoint.

Unverified assumptions: actual deployment resources, credential population, D1 runtime guarantees, and reported test results were not independently checked. The packet omits callers of `deleteOrphans()`, so finding 3 establishes the DAO-level failure without claiming a particular production scheduling trigger.

REQUEST_CHANGES
