# Cloud & Sync v1 — Independent design review handoff

**Date:** 2026-10-19
**Status at this checkpoint:** Claude review is actively reading the design and baseline native source; no completed findings or approval recorded yet. The last status inspection showed Read/Glob activity and Max-effort reasoning, with no usage-limit message. Do not treat this handoff as a review verdict.
**Frozen candidate:** `529c3f211cffc0e5d7d67bc0a5bafd57ee65adca` — `docs: define cloud sync v1 contract and review gates`.
**Branch:** `feature/cloud-sync-spec-20261019`; candidate verified committed, pushed and equal to its live remote head before review.

## Scope and completed pre-review checks

The candidate contains the [product contract](../specs/cloud-sync-v1.md), [architecture/failure model](../architecture/cloud-sync-v1.md), [execution/acceptance plan](../architecture/cloud-sync-implementation-plan.md), and historical/index updates. No app code, Worker, schema migration, Cloudflare resource, real catalog, APK or device installation changed.

Relative documentation links resolved; whitespace checks passed; expected six-file scope was verified; basic secret-assignment/placeholder checks passed. These checks are documentation validation, not native/backend feature tests.

Reviewer input is a source-only frozen packet containing the three new documents and relevant existing native code/domain documents: 25 committed files, **315,247 bytes**. Each file was pinned to the candidate commit and SHA-256 checked. The sorted compact JSON manifest of each path's `sha256` and `bytes` hashes to:

`83668458bd0523539b0dccc0eb78b58ed2da1e1de55d5adeb0cf5f06e28614d7`

A GitHub raw-content transfer returned different bytes for one baseline source file. The discrepancy was caught before review, the file was retrieved through the base64 contents representation with its Git blob hash verified, and the complete packet then matched the original manifest exactly. No near-match was reviewed.

## Active reviewer and session

- **Machine:** primary NotCodex, not NotCodex-2. Source implementation remains on NotCodex-2.
- **Actual startup:** Claude Code `2.1.302`; banner `Opus 5.5 with max effort · Claude Pro`.
- **Requested model/effort:** `--model opus --effort max`.
- **Session UUID:** `01b01580-a181-4667-b0bf-bbaac3ce95ae`.
- **Persistent tmux:** `newpipe-cloud-design-20261019`, pane `:0.0`.
- **Packet:** `/home/ritorhymes/.cache/newpipe-cloud-design-review-20261019/`.
- **Working directory:** the packet's `source/` subdirectory.
- **Conversation:** `/home/ritorhymes/.claude/projects/-home-ritorhymes--cache-newpipe-cloud-design-review-20261019-source/01b01580-a181-4667-b0bf-bbaac3ce95ae.jsonl`.
- **Other local evidence:** `prompt.txt`, `manifest.json`, `terminal.log`, `start-review.sh`, `resume-review.sh`; raw terminal/session logs are not committed.

Source files/directories are read-only. Claude has only Read/Glob/Grep tools, explicit denies for mutation/command/delegation/web tools, no MCP servers, no Chrome, disabled hooks/skills and no permission bypass. It must return findings in its conversation, not edit the implementation or write its own report file. The coordinator will extract and adjudicate the report.

The prompt asks for concrete failure scenarios, source/section references, severity, minimum corrections, and either `APPROVE_FOR_M1_PROTOTYPE` or `REQUEST_DESIGN_CHANGES`. It explicitly distinguishes design/prototype suitability from production acceptance and avoids a gratuitous CRDT/infrastructure expansion.

## Usage reset and resumption

The owner reported 1% Claude usage remaining and a manual reset available. A heads-up was given before launching Claude. Do not consume the owner's reset for an authentication issue or assume a low quota means a confirmed rate-limit stop.

On an actual usage-limit message, tell the owner to apply the reset. Preserve this exact session and candidate. After the owner reports reset, send `continue` to the existing idle reviewer session. Do not resubmit the initial prompt, launch another reviewer, silently change model, or interrupt an active response. A startup/process check is not a completed review; avoid repetitive progress polling.

If the terminal has been lost or the computer rebooted, use the saved `resume-review.sh` in a new persistent interactive terminal on **primary NotCodex**. That script uses `--resume 01b01580-a181-4667-b0bf-bbaac3ce95ae` and the message `continue`, retaining the same read-only Max-effort configuration. Never run `start-review.sh` again for an existing review.

Before adjudication, verify the source-packet checksums, actual completed report, and current repository status. Any supported spec amendments must be recorded in a new commit; the original frozen candidate must not be rewritten while Claude is reviewing it.

## Authentication detour — historical, not quota exhaustion

The first attempt on NotCodex-2 used the same session UUID but stopped immediately with `Login expired`, before reading files or producing a review. Local status confirmed no usable saved OAuth access/refresh token. The authorized-browser subscription login attempt also failed; no paid API fallback or usage reset was used.

Primary NotCodex had a saved Claude subscription login for the same existing Claude account. Only the small frozen review packet was moved there, not a full repository/build clone. No active review findings were discarded because the first attempt never began review work. Its original failed-login transcript and packet remain under `/home/rito/.cache/newpipe-cloud-design-review-20261019/` and its corresponding `.claude/projects/` path on NotCodex-2.

Only the two idle terminals created for that failed review/authentication attempt and its newly opened browser tab were closed. Other projects, terminals, device sessions and Cloudflare/Music browser tabs were left untouched. The Claude subscription's account identity does not change the requirement that new Cloudflare resources belong to **ritorhymes@gmail.com**.
