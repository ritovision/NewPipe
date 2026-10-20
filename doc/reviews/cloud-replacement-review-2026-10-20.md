# Protected replacement backend review — 2026-10-20

State: backend fix checkpoint approved at `6401e7907`; both findings reproduced, corrected and independently closed. Full backend suite passed 266/266. Frozen candidate: `62b9eeb5c5297afe38db78927b1f5e6454dbcef5`. Backend-only scope; Android orchestration and operator tooling remain outside this checkpoint.

Reviewer: `gpt-6-astra`, effort **max**, same persistent session `01a158d5-cfd0-70d3-907a-f3babbf442ee`, read-only sandbox, approval policy never. No coding delegation or Claude call. Source was supplied inline because the previous fix follow-up's file reads were blocked by execution policy. No permissions were broadened; this review is instructed to use no tools.

Primary NotCodex Windows evidence root: `C:\Users\Mattj\AppData\Local\Temp\newpipe-recovery-review-20261020`.

- Exact frozen prompt: `prompt-replacement-1.txt` (132,832 bytes).
- Commit/file digest manifest: `replacement-1-snapshot.json`.
- Output stream: `events-replacement-1.jsonl`; final report: `review-replacement-1.md`.
- Runner: `run-replacement-1.py`; exit marker: `exit-code-replacement-1.txt`.
- NotCodex job: `cd732b970a1c`.

263 backend tests passed before review, including fourteen replacement integration scenarios against real local D1/R2. Requested focus: exact incoming/outgoing approval binding, grant/CAS/receipt atomicity, delayed/unknown outcomes, cancellation and stale retry races, physical three-slot cap, preservation of the last verified copy, one active replacement and persisted cooldown. The later Android discovery field is outside the frozen candidate and will require a delta check.

## Findings and correction

The [completed report](cloud-replacement-astra-max-2026-10-20.md) found two P2 issues. Both new regressions failed against the reviewed code before correction.

1. Ordinary abandonment could overwrite the recovery-operation record required by an ACTIVE replacement using its old approval at the next sequence. Next-sequence abandonment now rejects any ACTIVE replacement for that device in the same guarded D1 transaction; matching-sequence resolution/cleanup still works.
2. A slow publication could record its old request-entry time as the cooldown start. Final publication now evaluates and records SQLite time in the atomic transaction. Replacement approval/seal expiry and preview/reservation cooldown checks also use database time, so delayed request timestamps cannot extend approval. A delayed-entry regression checks the stored timestamp; an additional expiry regression checks the final guard. The test-only route supplies an old entry timestamp and never exists in deployment code.

17 focused replacement tests passed after correction, including both reproductions and expiry. The full backend suite and broad native regression suite are running. Android's own-attempt discovery field is also in the upcoming delta; it returns only this authenticated device's ACTIVE/STALE attempt ID/state. A focused service/recovery discovery check passed.

SQLite time-function documentation checked: https://www.sqlite.org/lang_datefunc.html. The local D1 runtime executed the actual database-clock expression successfully. Live acceptance must also verify it on the dedicated QA D1 before replacement activation.

## Fix closure

The same Astra Max session completed **APPROVE_REPLACEMENT_BACKEND_FIX_CHECKPOINT** at `6401e7907`, with no confirmed P1/P2 remaining in that delta. [Completed fix report](cloud-replacement-astra-fixes-2026-10-20.md). The full backend regression subsequently passed **266/266**, and the Android cloud suite executed **144 passing tests plus 11 explicit skips** (runner total 155), including twelve new replacement-controller tests.

Fix evidence in the same private review root: `prompt-replacement-fix-1.txt`, `events-replacement-fix-1.jsonl`, `review-replacement-fix-1.md`, `exit-code-replacement-fix-1.txt`; NotCodex job `518975816440`. Full backend job `80bd93ea40a8`; native regression job `4567b935e402`. Android orchestration/UI and live acceptance remain outside this backend review verdict.
