# NewPipe Cloud & Sync v1 — final independent release review

**Recommendation: PASS TO FINAL VERIFICATION.**

No unresolved code-level release blockers were found at **`2b5c85b801481923de9af88aaa082868db40cd92`** on **`feature/cloud-sync-v1-20261019`**. The corrected implementation is technically ready for integrated end-to-end acceptance testing, subject to the concrete acceptance requirements in [coverage-and-release-gates.md](coverage-and-release-gates.md).

| Classification | Count |
| --- | ---: |
| Critical | 0 |
| High | 0 |
| Medium | 0 |
| Low | 0 |
| Total actionable new findings | **0** |
| Unresolved code-level blockers to final verification | **0** |
| Unresolved code-level blockers to production release found by this review | **0** |
| Previous findings independently assessed as corrected | **17 / 17** |

The zero blocker count is a code-review conclusion, **not production approval**. Live integration, the deployed resource/CPU profile, and separate-device/background acceptance remain release requirements. They are missing acceptance coverage, not manufactured defect entries. This review neither activated production nor authorized another session to do so.

## Review identity and independence

Reviewer: **Astra Pro**, 2026-10-21. The primary NotCodex connector was used. The review was conducted alone, without Claude or other subagents.

Before inspecting implementation, the reviewer checked the branch, full HEAD, clean working tree, matching remote branch and recent source history, then read the specification, architecture, budget, operator-recovery and protocol documents. The native apply/import/export, synchronization, recovery/replacement, authorization, budgeting and operator paths were independently traced before consulting the earlier finding registers. The previous findings were then checked against both source and retained execution evidence.

Local and remote implementation HEAD remained at the source commit above during review. The later commit containing this directory is a **documentation commit**, not a new implementation candidate. Production paths at the reviewed commit are byte-identical to `0f9988b001248050097d96972ba1e86d1b312c5e`. Relative to the 213-test native checkpoint `439af53cca239a9bdf37c84790436c0fc695d0f0`, the production difference is the bounded detachment preflight and its error-message mapping in `CloudSyncController.kt`; that difference has its own retained controller execution.

## Technical assessment

The architecture is coherent for the specified conservative whole-catalog synchronization model. The mechanisms protect different failure boundaries rather than pretending that Android, D1, R2 and CONTROL share one transaction:

- Native record apply durably records a plan, commits Room changes with a witness, then completes tag mappings and baseline state. Restart recovery uses that witness and does not replay destructive Room mutations.
- A cloud publication's final D1 transaction checks current authority, exact base head, sequence, immutable stage and receipt identity. Replacement additionally requires the exact verified outgoing archive and a current replacement grant.
- Full native ZIP preparation and export bind the validated file inventory and bytes. Reversible AI quiescence precedes final semantic/inventory admission; irreversible restore and unpairing begin afterward.
- Cancellation preserves exact operation identity and records successful multipart consumption before returning bounded progress. It does not rely on repeated-provider abort succeeding.
- Historical archive provenance is separate from current receiving authority. Restoring an old same-library catalog does not adopt an old credential, lower current high-water counters, or acknowledge an old-generation head as current.
- Disabled synchronization, terminal rejection, unresolved outboxes and local detachment have distinct durable behaviors. Definitive stale-upload refusals resolve or retire the exact request; uncertain outcomes retain their evidence.

[architecture-assessment.md](architecture-assessment.md) explains the commit points and operational limits. **No broad redesign is justified by this review.**

## What is convincingly verified

**Executed during this review:** TypeScript checking passed, and **98/98 focused local Node/Miniflare tests** passed: 32 budget/fence, 19 upload/admission, 29 recovery/replacement, 16 operator recovery/reopening, and two independent adversarial probes. The independent probes sent ten malformed start headers and twelve malformed chunks, checked that reservations settled and staged content stayed unchanged, and then completed legitimate uploads. These are local executions using synthetic fixtures, not live Cloudflare tests. Commands, scope and raw logs are retained in this directory.

**Retained evidence independently checked:** all **52 Astra-package and 35 Claude-package SHA-256 entries** match. Raw backend results support 372 passes. Recomputed JVM suite totals support 353 passes across 45 suites. The native log contains 213 successful test completions at its stated checkpoint; the final controller log contains 52 at the follow-up checkpoint. **The 52 overlap the 213 and are not added as unique cases.** The five later host-driven death scenarios and the nine earlier Astra correction scenarios have matching raw logs, result markers and source identities. The driver actually force-stops the selected QA process at a durable marker; these are process-death tests, not power-loss tests.

All 17 corrections survived source review. Their individual adequacy, adjacent regressions and residual acceptance limits are recorded in [previous-fixes-assessment.md](previous-fixes-assessment.md). The additional coordinator-discovered export inventory correction was also inspected.

## Important concerns and remaining uncertainty

Unknown storage mutations intentionally retain non-expiring CONTROL tickets and budget holds. This protects recovery from delayed effects, but can make in-place recovery unavailable. Neither a local detach nor an archive inspection authorizes clearing such holds. That availability cost is explicit in the design.

The admitted deployment profile is small. The earlier **6.950 ms p95** sample contains 30 requests, **26 of them `/v1/head`**, with recovery and replacement disabled. It does not establish the 8 ms target for chunk, seal, publish, recovery verification or corrected cancellation. Larger catalogs, larger native ZIPs and additional resource pairs need measured admission decisions; codec ceilings are not rollout approval.

Current Android/Worker/R2 composition, actual paired-relay refusal and restore behavior, independent hardware devices, longer offline periods, Doze/OEM scheduling, and the newer dialogs still need the specified acceptance work. This reviewer ran no Android build, instrumentation, emulator command, host kill or live Cloudflare operation. Historical theme screenshots were inspected; they are not represented as a new UI execution.

## Recommended next action

Proceed to the bounded integrated acceptance matrix in [coverage-and-release-gates.md](coverage-and-release-gates.md) using an explicitly authorized isolated environment and the matching corrected Android/backend candidate. Preserve exact binary/deployment identities and distinguish local, native and live results. Resolve any concrete acceptance failure on its merits.

**Another general review is not required merely because those acceptance tests remain.** No implementation edit is requested by this report.

## Files

- [findings.md](findings.md): prioritized register and investigations that did not establish defects.
- [previous-fixes-assessment.md](previous-fixes-assessment.md): all eight Astra and nine Claude corrections.
- [coverage-and-release-gates.md](coverage-and-release-gates.md): evidence matrix and entry versus activation gates.
- [architecture-assessment.md](architecture-assessment.md): complete-system assessment.
- [review-metadata.json](review-metadata.json): source, execution, evidence limits and counts.
- [evidence/](evidence/): new local logs, evidence audit and independent-probe recipe.

Only this new review directory was added. Implementation, existing reviews, application data, credentials and live infrastructure were unchanged.
