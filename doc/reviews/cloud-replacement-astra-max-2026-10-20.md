Two confirmed P2 findings:

1. **P2 — Ordinary abandonment can strand an ACTIVE replacement.**  
   `RecoveryService.abandon()` (`cloud/src/recovery.ts:279–289`) permits an already-used approval to cancel the next recovery sequence, overwriting the device’s completed recovery-operation row.

   **Scenario:** Replacement A remains ACTIVE after its outgoing archive finishes at recovery sequence R. Call ordinary `/v1/recovery/abandon` with A’s approval and sequence R+1. The request succeeds and replaces R’s DONE record with a new CANCELED record. Publication then fails its required recovery-operation join (`service.ts:331–336`). Settlement also fails: `ReplacementService.settle()` calls abandonment for R (`replacement.ts:135–137`), which now returns `RECOVERY_SEQUENCE_GAP`. The ACTIVE attempt continues blocking recovery admission.

   **Minimum correction:** Prevent next-sequence abandonment from overwriting a recovery operation referenced by an ACTIVE replacement, while preserving matching-sequence cleanup; alternatively retain that required receipt independently.

2. **P2 — Delayed publication can consume the entire cooldown before committing.**  
   `ReplacementService.publish()` captures `now` at entry and passes it through asynchronous verification (`replacement.ts:89–101`). `CatalogService.publishSealed()` uses that timestamp for both the cooldown assertion and `last_replace_at` (`service.ts:342,347–349`).

   **Scenario:** A publication starts at T, stalls for over 60 seconds before its transaction, then commits. It records `last_replace_at=T`, so a newly approved replacement can immediately pass the cooldown despite the preceding replacement having just committed.

   **Minimum correction:** Evaluate the final cooldown assertion and record `last_replace_at` using database time within the atomic publication batch.

Static review of the supplied inline backend only; no tools or tests run. The reported 263 passes do not cover these scenarios. Android orchestration, deployed quota acceptance, and operator tooling remain outside this verdict.

**REQUEST_CHANGES**