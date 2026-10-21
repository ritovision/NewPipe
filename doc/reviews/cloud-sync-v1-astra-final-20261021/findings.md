# Findings register

## Verdict and counting rules

**PASS TO FINAL VERIFICATION** for source `2b5c85b801481923de9af88aaa082868db40cd92`, branch `feature/cloud-sync-v1-20261019`.

| Severity | Confirmed | High-confidence | Suspected actionable | Total |
| --- | ---: | ---: | ---: | ---: |
| Critical | 0 | 0 | 0 | 0 |
| High | 0 | 0 | 0 | 0 |
| Medium | 0 | 0 | 0 | 0 |
| Low | 0 | 0 | 0 | 0 |

**Actionable new findings: 0. Actual unresolved code-level release blockers found: 0.**

There is no `ASTRA-FINAL-001` entry because this review did not establish an actionable defect. IDs have not been assigned to routine acceptance tasks, intentional uncertainty fences, or unsupported hypotheses. Accordingly, there is no implementation correction or new defect-specific regression demanded before entering final verification.

This is not a claim that every input, crash boundary, provider behavior or device has been exhaustively tested. [coverage-and-release-gates.md](coverage-and-release-gates.md) records those limits separately, including the requirements that still prevent treating this report as production activation approval.

## Challenges that did not establish a new defect

All source references below are pinned to the reviewed implementation.

| Challenge | Evidence and conclusion |
| --- | --- |
| Can malformed upload data still manufacture a permanent uncertainty hold? | [service.ts:229–265](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/src/service.ts#L229-L265) asserts global page ordering before the unique insert. The repeated duplicate/descending router cases passed. Two additional review probes rejected ten malformed headers and twelve malformed chunks, left zero tickets/held capacity, preserved staged rows, and then completed valid uploads. This is bounded adversarial evidence, not exhaustive fuzzing. |
| Can a definite stale refusal discard a racing commit, or strand an automatic outbox? | [CloudSyncController.kt:441–518](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L441-L518) narrows automatic retirement to exact 409/410 codes, verifies the returned receipt, and acknowledges COMMITTED before clearing. Unknown transport/write outcomes keep the outbox. Retained native head-race, expiry, racing-commit and unknown-response tests cover the alternatives. |
| Does phased cancellation depend on abort being repeatable? | [recovery.ts:456–527](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/src/recovery.ts#L456-L527) persists consumption proof and separates rotation, claim, proof and removal. The focused run passed both ordinary rotation routes, eight replacement state/route cases, provider repeat-abort rejection and concurrent quarantine protection. A provider result lost before its durable proof remains genuinely uncertain; that is not classified as successful cleanup. |
| Does restore still unpair AI before a final preview refusal? | [TagBackupCoordinator.kt:220–288](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L220-L288) runs final admission and inventory verification before lease commit. [AiControlRuntime.kt:483–547](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiControlRuntime.kt#L483-L547) resumes current durable policy on a reversible refusal. Later checkpoint/filesystem failures are after the declared irreversible restore boundary and require restart; they are not represented as harmless preflight refusals. Retained real paired-runtime tests cover final expiry/content refusal and successful unpairing. |
| Can full restore silently substitute a missing optional staged file, or can export silently omit one? | [TagBackupCoordinator.kt:52–181](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L52-L181) and [290–345](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L290-L345) preserve inventory, expected absence and byte identities. The retained missing-member/same-length corruption/export-loss regressions exercise these exact boundaries. |
| Do old-generation archives or clean imported identities defeat recovery? | Historical plan version 2 separates source head from receiving context in [CloudApplyPlan.kt:65–163](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyPlan.kt#L65-L163). Detachment accepts a legitimately absent cloud identity only after native recovery in [CloudSyncController.kt:183–232](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L183-L232). The follow-up controller case covers that final source delta. Different extant identity remains a refusal. |
| Is conservative blocking itself evidence of a broken recovery design? | [fence.ts:32–56,105–110](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/src/fence.ts#L32-L110) and the operator contract deliberately retain unresolved effects. The 32 budget/fence tests and selected operator tests passed. Automatic expiry/force-clear would undermine the documented safety invariant; none is recommended. |

## Acceptance gaps are separate

The unexecuted corrected live recovery/native-restore/replacement composition, representative deployed CPU, separate-device/background behavior and current human UI acceptance are listed with expected results in the coverage matrix. They do **not** become High or Medium findings simply because they remain unexecuted.

The previous 17 findings are closed by the independent assessment in [previous-fixes-assessment.md](previous-fixes-assessment.md), with explicit evidence boundaries. In particular, the earlier broad cancellation conclusion is supported here by durable abort-proof and ordinary-rotation corrections as well as the original replacement cases.
