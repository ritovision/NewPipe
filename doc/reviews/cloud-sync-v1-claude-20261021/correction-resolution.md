# Implementation resolution of the nine Claude findings

Date: 2026-10-21. Implementation: `0f9988b001248050097d96972ba1e86d1b312c5e`. Original independent review: frozen source `582f0791d`. The original review and adjudication are retained unchanged.

**All nine corrections are implemented and locally/native verified. Normal-use/production rollout is still on HOLD pending the remaining integrated/live and review gates.** This document records the implementer's resolution; it is not a claim that Claude re-reviewed or implemented any correction.

Primary evidence: [correction checkpoints](../../validation/cloud-claude-fixes-20261021/README.md), exact [results/source metadata](../../validation/cloud-claude-fixes-20261021/checkpoint.json) and retained raw logs/hashes.

| Finding | Implemented correction | Verification / qualification |
| --- | --- | --- |
| CLAUDE-CS-001 | Global cross-chunk ordering/uniqueness enforced in the committing CHECK guard; unknown-write fences unchanged. | Old-source negative reproduction; repeated duplicate/descending requests now refuse without held tickets/capacity, exact replays remain valid, and maintenance reaches HELD. Full 372-test backend run passed. |
| CLAUDE-CS-002 | Reversible AI admission quiescence/drain precedes final native admission; irreversible freeze/unpair only afterward. Refusal resumes latest durable policy. | Actual isolated paired runtime/store/Keystore, final expiry/local-change refusal, unchanged native content/pairing and successful unpair/restart paths passed. No live AI relay session was exercised. |
| CLAUDE-CS-003 | Exact definitive stale/expired upload refusals resolve-or-retire their same identity, then bounded policy re-evaluation. Racing COMMITTED receipts win; unknown/network outcomes remain pending. | Head race, expired stage, racing successful commit, unknown outcome and subsequent conflict/restart regressions passed. No false receipt or blanket discard. |
| CLAUDE-CS-004 | Explicit local detach archives encrypted connection/pending evidence in an interrupted-safe journal, preserves content, and requires a fresh device identity. Terminal credential or replay-identity rejection enables it; ordinary outages do not. | Controller cases plus four real host kills passed. Current final controller suite also covers a native-import-cleaned identity. Remote holds/outcomes are never cleared or declared failed by this local flow. |
| CLAUDE-CS-005 | Hint-only `streams` invalidation removed from the event observer; semantic playlist/membership/tag work and periodic remote polling preserved. | Real Room invalidation/scheduler-ticket test passed for repeated metadata updates and positive semantic edits. No unmeasured battery/cost savings are claimed. |
| CLAUDE-CS-006 | Definite multipart-consumption proof is persisted before progress returns; continuation skips already-proven abort rather than relying on repeated-provider idempotence. | A provider shim deliberately rejecting repeated successful abort passed through real local D1/R2/router. Concurrent quarantine regression also passed. Live R2/CPU remains an acceptance gate. |
| CLAUDE-CS-007 | Ordinary abort/abandon explicitly continue bounded rotation/claim/proof/terminal phases, same identity, at most four client requests per action. | Both ordinary ROTATING routes and eight replacement route/state cases passed within the statement ceiling, with surviving archives and settled holds checked. |
| CLAUDE-CS-008 | Preview/cancel preserves sync mode. Replacement completion keeps the owner's toggle; confirmed local historical/native restore turns OFF at final admission. | ON-preview/cancel/confirmation, OFF, generation, native-content and replacement-receipt regressions passed. No product spec was rewritten to excuse prior behavior. |
| CLAUDE-CS-009 | Process-owned OS-locked staging sweeps only orphan import/export directories before new candidate allocation; active/safety/journal data is excluded. | Native owner/active-candidate tests and real host death/restart passed, followed by successful new preparation/install. Live multi-process owners are refused rather than swept. |

## Executed results and source scope

Full local backend: **372/372**. Android JVM: **353/353**. Full selected native regression: **213/213** at `439af53cc`; final controller follow-up: **52/52** at `0f9988b00`. Five selected real process-kill scenarios passed at `439af53cc`. The controller follow-up overlaps the full suite and covers the sole later production delta, a clean-native-restore detachment preflight; it is not a claimed full rerun on the final binary.

During implementation, the original concurrent-quarantine test exposed stale cancellation progress and the new detachment tests exposed a missing persisted rejection flag. Both were corrected and rerun without weakened assertions. An initial native launch failed before any test; the same binaries then passed after a QA-package force-stop, without clearing data. Superseded failures and negative reproductions remain source-separated in the evidence package.

## Preserved boundaries

No live QA was reopened, no Cloudflare deployment/migration or credential/archive operation was performed, no phone installation or app-data clearing occurred, and no branch merge or production activation was authorized. Shared build dependencies and unrelated projects were not removed. New test fixtures and ordinary build outputs remained scoped to this project/emulator. The independent review report itself remains verbatim.

The next technical gates are changed-code independent verification and the separately authorized integrated live backup/restore/replacement/cancellation and representative resource checks. Separate-device/Doze/OEM acceptance and final release authorization remain distinct. Passing local tests does not erase these gates or reinterpret the old live CPU sample.
