# Guarded reopening checkpoint — 2026-10-21

Status: **the isolated reopening/enrollment checkpoint passed**. Production remains disabled. This extends `cf686a631b9bb718f2487a361ed9f31ac3c0b164`; implementation and verification were performed directly without another coding agent. No Android code or native acceptance is included in this checkpoint.

## Behavior and local verification

A recovered service can now issue fresh device credentials and reopen only after independently verifying its accepted catalog, physical recovery archives, rebuilt slot index, revoked old authority and latest recovery receipt. The private CLI creates credentials locally; the operator receives only hashes. Credentials default to read-only. Public API flags are not enabled by reopening.

One data transaction inserts the enrollment and its receipt. A subsequent external-control transaction atomically opens the new generation and completes the action. Lost replies resolve through those receipts without resubmitting enrollment; unknown enrollment remains exclusive and closed. Historical actions retain their resource identity across maintenance cycles and become SUPERSEDED after later maintenance.

- `npm run check` passed.
- Final full backend suite: **313 passed, zero failed, zero skipped**.
- Seven added operator cases cover new-generation admission, credential roles, lost enrollment/open replies, unresolved effects, corrupted/stale plans, missing receipt schema after Time Travel, concurrent reopening and maintenance-ID reuse.
- Two migration cases verify ordered history preservation, per-resource exclusivity, and full rollback for open gates, pending requests, unknown actions or unmappable history.
- The isolated operator file completed with 28 passing tests. A focused name-filter run passed its selected assertions but failed to exit; verification used completed full-file and full-suite runs.

## Live sequence

The existing fresh-resource synthetic pair `newpipe-fence-qa-20261020` was reused. No user library, phone, legacy resource pair, plan upgrade or paid activation was involved.

| Step | Observed result |
| --- | --- |
| Schema upgrade | With APIs disabled and CONTROL HELD, Wrangler applied the whole CONTROL migration and data receipt migration transactionally. All three existing action rows retained their IDs, order and results. |
| Reopening | The operator verified three READY archives totaling 4,995 bytes and the unchanged catalog, enrolled one writer and one read-only device, and reached OPEN in generation `161790ee-29a5-4113-ac49-a54f12073a29`. |
| HTTP admission | Both fresh credentials connected. The old credential returned 401. The read-only device's valid write request returned 403. |
| Writer operation | A small upload was staged and explicitly retired. The accepted head and catalog pages remained unchanged; no R2 object was modified. |
| Retry | The HTTP verifier initially used the wrong receipt property name. After fixing the verifier, replay resolved the same retired operation and the complete check passed. |
| Closeout | All public API flags were disabled. A new maintenance cycle closed the fence; a guarded apply revoked all three QA credentials and preserved all three archives and the catalog. CONTROL is HELD at revision 10 with zero tickets/running actions. The old reopening action reports SUPERSEDED. |

[checkpoint.json](checkpoint.json) contains sanitized live evidence, exact resource/version identities, action digests and final archive checks. Full plans, device/provider credentials and private export files remain outside Git. The final operator additionally rejects a missing/incompatible reopening receipt table before claiming an action, so a Time Travel predating the new migration fails before enrollment starts.

The reusable [HTTP verifier](../../../cloud/test-support/live-reopen-check.mjs) is restricted to a fresh synthetic QA resource record and a private enrollment file. See the [operator runbook](../../architecture/cloud-operator-recovery.md) for commands, migration precautions and unknown-outcome handling.

## Remaining work

Android reconnection when old uploads/recovery/replacement work is unresolved; daily usage budgets and bounded housekeeping; broader device/offline/UI/scale acceptance; independent whole-feature review and release authorization remain. This checkpoint does not enable production or merge to `custom/dev`.
