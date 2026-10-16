# NewPipe AI Control — gateway pending-file reconciliation

**Date:** 2026-10-16
**Working branch:** `feature/ai-control`
**Change submitted:** test-only OAuth refresh-token regression
**Live deployment:** unchanged (Worker `ritovision-newpipe-ai-control`)

## Decision

Three uncommitted gateway files proposed narrowing the initial OAuth challenge
from seven action scopes to five, excluding `playlist.delete` and `library.delete`.
That was an **undeployed experiment**, not a required production correction.
ChatGPT users were already using authenticated playback, playlist management and
delete actions successfully without repeat login prompts. The consent form only
renders checkboxes for scopes named in the client's authorization request; a
client requesting scopes based on the reduced five-scope challenge would lack
an opt-in path for deletion during that authorization. Whether ChatGPT actually
constructs its requests that way is unproven. Preserving demonstrated delete
functionality takes precedence over deploying a speculative session fix.

The full original pending diff is saved in the private local reviewer cache as
`~/.cache/newpipe-ai-control/oauth-five-scope-candidate-20261016.patch`.
All three working files were restored to their baseline, then the isolated
OAuth refresh test was retained and enhanced. No Cloudflare authentication
config, client grants, secrets, token lifetimes, Android app, or Library data
were changed.

## Test evidence

- `npm test` on NotCodex-2 — TypeScript check PASS, Wrangler dry-run bundle PASS,
  **33/33 gateway tests PASS**.
- New synthetic Miniflare test authorizes `mcp.connect`, `library.read`, and
  `playback.control` with PKCE; receives a 600-second token and refresh token;
  exchanges that token for new access and refresh credentials without owner-key
  interaction; verifies rotation, exact unchanged scopes, permitted Library and
  playback reads, and denial of unauthorized playlist creation/deletion.
- Test log: `~/.cache/newpipe-ai-control/gateway-refresh-final-verified-20261016.log`.
- Claude Opus focused code review **PASS**, no P0-P2 issues. Suggested stronger
  post-refresh scope enforcement and rotation assertions; both were added before
  final test and commit. See
  `../reviews/ai-control-gateway-claude-reconciliation-2026-10-16.md`.

**Not claimed:** a real ChatGPT refresh after seven days, indefinite Android
WebSocket uptime, or root-cause proof of the earlier intermittent ChatGPT UI
reauthorization prompt. These are distinct long-term reliability checks.
