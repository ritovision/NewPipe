# R2 recovery independent review — 2026-10-20

**State: completed REQUEST_CHANGES; both findings reproduced and corrected, fix verification in progress.** Frozen source: `2cb2df30d7ffc3cbdf9c31b4388915a002a4701f` on primary NotCodex. This is a bounded recovery-checkpoint review; whole-cloud replacement and operator disaster tooling are explicitly unfinished.

Reviewer: `gpt-6-astra`, effort **max**, read-only sandbox, approval policy never. These values were verified in the persisted turn context. No Claude invocation or code-generation delegation was used.

The existing Windows CLI 0.77.0 rejected the `max` setting before making an inference call. A separate task-local official CLI 0.163.0 was installed; global CLI/configuration was not replaced. Its successful review session is **`01a158d5-cfd0-70d3-907a-f3babbf442ee`**. Do not restart this review from scratch if interrupted.

Primary-host Windows paths:

- Source-only immutable packet and SHA-256 manifest: `C:\Users\Mattj\AppData\Local\Temp\newpipe-recovery-review-20261020\packet`
- Exact prompt: `C:\Users\Mattj\AppData\Local\Temp\newpipe-recovery-review-20261020\prompt.txt`
- Event stream: `C:\Users\Mattj\AppData\Local\Temp\newpipe-recovery-review-20261020\events-current.jsonl`
- Final report destination: `C:\Users\Mattj\AppData\Local\Temp\newpipe-recovery-review-20261020\review.md`
- Persistent session: `C:\Users\Mattj\.codex\sessions\2026\10\20\rollout-2026-10-20T06-22-50-01a158d5-cfd0-70d3-907a-f3babbf442ee.jsonl`
- NotCodex job ID: `34f844855609`

The packet contains 68 source/test/protocol/evidence files (701,648 bytes) and no live credentials, APKs, build caches or another reviewer's findings. The reviewer may read/search the packet only, cannot edit/build/test/deploy, and was instructed not to delegate or use the web.

Review focus: physical three-payload cap under unknown outcomes/concurrency; multipart identity, stale callbacks and exactly approved rotation; two newer extant survivors; authority isolation; outgoing page-manifest verification; durable Android operation/payload identity; forget/disable; historical local restore and final native writer barrier. Completed local and live test evidence is supplied with its limits. Coordinator must adjudicate concrete findings and verify justified corrections before any approval claim.

## Adjudication

[Completed Astra Max report](cloud-recovery-astra-max-2026-10-20.md). Both P2 findings are accepted and were reproduced with real local D1/R2 and concurrent HTTP requests before correction. Both regressions failed against the reviewed code by returning CANCELED; neither was merely inferred from a mocked client.

1. **Completed archive deleted by abort:** pause abort after rotation's D1 batch, finish the same new archive via a concurrent resumer, then release abort. The old code converted DONE into ABORTING and removed it. The correction repeats terminal-state checks after rotation and the abort claim requires an exact slot in an explicitly abortable state. The new regression requires RECOVERY_ALREADY_COMMITTED with all three archives still usable.
2. **Hidden quarantined slot:** pause one abort after its claim, let another abort experience an uncertain R2 result and quarantine the operation, then release the first. The old code returned CANCELED while retaining the QUARANTINED slot. The correction requires ABORTING after reload and atomically guards final cancellation on the exact ABORTING slot. The regression requires the operation remain visible and the subsequent exact abort clean it successfully.

Both new tests pass after correction. The complete backend suite is being rerun, and a read-only follow-up will review only this fix delta. The unrelated protected-replacement transaction primitive is not part of this review packet or its verdict. No claim of release approval follows from this adjudication.
