# Independent Architecture Reviews

These documents preserve external model reviews for later adjudication. **Nothing in a review is automatically an accepted requirement or approved implementation plan.** Record decisions separately when the user requests adjudication.

- [Claude Opus xhigh — Library and Tagging Architecture (2026-10-15)](library-tags-claude-opus-xhigh-2026-10-15.md) — critique of Library writes/removals, cross-database tags, backup/restore, UI hooks, and upstream compatibility. Unadjudicated.
- [Claude Opus max — Device-First AI Control and Executor (2026-10-15)](ai-control-claude-opus-max-2026-10-15.md) — original unchanged read-only source review of Android executor, gateway, pairing, security, foreground-service constraints and MCP interface. **Adjudicated separately** below.
- [AI Control Claude review adjudication (2026-10-16)](ai-control-claude-adjudication-2026-10-16.md) — source-checked accept/modify/reject decisions against the finished Library polish and user-approved full-management v1. No new Claude/Astra review implied.

- [Claude Opus max — Library implementation review (2026-10-15)](library-implementation-claude-opus-max-2026-10-15.md) — independent review of commit `f245e5497`; PASS WITH CHANGES: initialization session gaps, recovery, restore atomicity, and performance. Unadjudicated.
- [Claude Opus max — Final Library review (2026-10-16)](library-final-claude-opus-max-2026-10-16.md) — independent source review at commit 577bf2f77. PASS WITH NONBLOCKERS; closing test/documentation corrections made; remaining v1 limitations accepted. Library milestone complete.

- [Claude Opus Max — Library polish review (2026-10-16)](library-polish-claude-opus-max-2026-10-16.md) — independent read-only review of f98fa3092: PASS WITH NONBLOCKERS, five Low findings.
- [GPT-6 Astra Extra High — Library polish review (2026-10-16)](library-polish-gpt6-astra-xhigh-2026-10-16.md) — independent read-only review of f98fa3092: PASS WITH NONBLOCKERS, two Low findings.

Future external reviews belong in this directory, with model/effort, reviewed revision, permissions, source references, known uncertainties, and adjudication status.

- [Claude Opus Max — Library polish follow-ups, round 1](library-polish-followups-round1-claude-2026-10-16.md) — source review of 6af54b825: PASS WITH NONBLOCKERS; follow-up findings corrected, final verification pending.

- [GPT-6 Astra Extra High — Library polish follow-ups, round 1](library-polish-followups-round1-astra-2026-10-16.md) — source review of 6af54b825: BLOCK; follow-up findings corrected, final verification pending.

- [Final GPT-6 Astra xhigh follow-up verification](library-polish-final-followups-astra-2026-10-16.md) — PASS on 4881fc69c; all requested fixes cleared.
- [Final Claude Opus Max attempt](library-polish-final-claude-attempt-2026-10-16.md) — not completed: session limit, no final verdict.


## AI Control local foundation (2026-10-16)

- [Astra xhigh round 1](ai-control-foundation-astra-round1-2026-10-16.md) — BLOCK; six findings, subsequently fixed and independently closed.
- [Astra xhigh round 2](ai-control-foundation-astra-round2-2026-10-16.md) — BLOCK; original findings closed, two new UI issues and one test barrier correction.
- [Astra xhigh round 3](ai-control-foundation-astra-round3-2026-10-16.md) — PASS; all three round-two corrections verified in source.
- [Claude Opus Max milestone review](ai-control-foundation-claude-2026-10-16.md) — PASS WITH NONBLOCKERS for the inspected local foundation; author corrected additional findings afterward.
- [Final Astra xhigh verification](ai-control-foundation-astra-final-2026-10-16.md) — PASS WITH NONBLOCKERS for the Claude-driven corrections; limited backup preflight claim clarified.
- [Author adjudication](ai-control-foundation-adjudication-2026-10-16.md) — accepted fixes, explicit limitations and remaining activation gates. Reviewers did not write code or execute tests.
