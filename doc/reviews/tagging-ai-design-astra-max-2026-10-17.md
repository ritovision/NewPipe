**DESIGN_BLOCKERS**

The proposed approach is viable. Three narrow additions are needed to the [contract](/home/rito/techstuff/ritoverse/newpipe-tagging-integrated/.work/integration/contract.md). References below identify pinned source, not the changing working tree.

1. **Check for an existing receipt before staging a new cleanup intent.**  
   The proposed sequence stages first, but `af902e5a8:AiCatalogExecutor.kt:105–114` can return an earlier receipt without executing a deletion. Consider: removal completes → user re-adds and tags the item → original request is retried. A newly staged intent would match the old receipt and erase the new assignments.

   Under the same access/tag barrier, recover existing intents, check both receipt stores and fingerprints, and return retained results **without creating another intent**. For new removals, stage only verified keys actually eligible for deletion: `AiCatalogStore.kt:194–211` permits a committed result that removes zero items. Staging every requested key could otherwise erase preserved orphan assignments. Keep the existing validation inside Room as well.

2. **Specify restore’s validation and lock-acquisition sequence.**  
   At `af902e5a8`, `BackupRestoreSettingsFragment.java:175` invokes AI shutdown before ZIP validation at line 213. `AiControlRuntime.kt:473–484` persistently disables/unpairs AI and rotates its epoch. Thus an invalid import already changes local state. The database validation in `LibraryBackupRestore.kt:141–153` checks only the header and version; it cannot establish that the staged database is usable.

   Require: stage and validate the imported databases/binding → complete AI shutdown and command draining → acquire TagRuntime’s maintenance barrier → close handles and perform the journaled swap. **Never wait for `beforeRestore()` while holding TagRuntime:** an AI command can hold the access read lease while waiting for TagRuntime, while restore waits for `access.disable()` to obtain the write lock. Validate staged schema/integrity before changing installed state.

3. **Give the swap journal an explicit completion and artifact-retention rule.**  
   “Durable journal replayed before Room initialization” needs one concrete recovery decision: for example, before a durable commit marker restore the entire old set; afterward retain the entire new set. Cover Room, tags, binding and their relevant companion files, including recorded file absence. Preserve recovery material until that decision has been fully applied and persisted.

   This specifically replaces `af902e5a8:LibraryBackupRestore.kt:120–137`, whose `finally` deletes rollback snapshots even when rollback itself fails. Recovery failure must leave the journal/material intact and prevent database opening. For imports without tag metadata, stage a sanitized copy of retained tags so neither M1 deletion intents nor AI cleanup intents survive into the replacement catalog.

The contract already gets the other priority decisions right, with these implementation boundaries:

- **Rolled-back AI removal:** use a separate discard-only operation. M1 `TagRepository.kt:36–43` replays deletion, and `TagStore.kt:187–191` deletes assignments when finishing an intent; neither implements rollback cancellation.
- **Receipt retention:** recovery must inspect physically retained receipts even after their public expiry. Do not reuse the public lookup’s expiry rejection (`af902e5a8:AiCatalogExecutor.kt:84–87`). Recovery must succeed before pruning at line 121; lookup errors are not proof of absence.
- **Lock order:** access → TagRuntime → Library monitor → Room is sound. Recovery invoked by UI must inspect durable evidence without acquiring AI authorization.

Read-only source/design review only; no tests, builds, edits, devices, network actions, or agents.