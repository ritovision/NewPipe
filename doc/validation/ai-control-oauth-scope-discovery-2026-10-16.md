# NewPipe AI Control — OAuth permission discovery correction

Date: 2026-10-16. Branch: `feature/ai-control`.

## Actual problem

Initial ChatGPT plugin integration could list devices and their presence, but Library reads, playlist listing/search and playback status returned OAuth `PERMISSION_DENIED`. The original gateway advertised only `mcp.connect` through its OAuth protected-resource metadata and unauthenticated `WWW-Authenticate` challenge. ChatGPT therefore requested and received a narrow token. Android had already paired the Pixel 7 with six locally granted permissions; this problem occurred at Cloudflare before dispatch, not at Android.

## Correction

`gateway/src/index.ts` uses all `SCOPES` except `offline_access` for the MCP protected resource's `requiredScopes`. `@cloudflare/workers-oauth-provider` v1.2.3 uses this configuration for OAuth protected-resource `scopes_supported` and Bearer challenge scope hints; it does not replace per-tool checks or silently grant token privileges. The gateway authorization page still requires the owner key, verified browser-bound consent, a signed original scope proof and an explicit user choice of scopes. `.delete` checkboxes are **off by default**; no automatically granted Library or playlist deletion. Each tool still checks the granted token scopes and the phone's local permission ceiling; revocation remains unchanged.

The test-only OAuth helper now permits requesting and granting different scope sets, modeling a real consent screen instead of approving all requested scopes unconditionally.

## Validation

- TypeScript `tsc --noEmit`: passed.
- Wrangler dry-run bundle: passed.
- Miniflare / Vitest: **32/32 tests passed** on the final candidate, recorded in `~/.cache/newpipe-ai-control/gateway-oauth-scope-r2.log`.
- New metadata-to-token integration regression uses the unauthenticated MCP challenge to determine requested scopes. It completes PKCE and owner consent, obtains a token with exactly `mcp.connect`, `library.read`, `library.write`, `playlist.write`, `playback.control`, then successfully reads catalog/playback and runs both empty and populated playlist creation against a synthetic paired WebSocket device. Omitting `library.write` blocks populated creation. Unselected `playlist.delete` and `library.delete` remain denied even when the synthetic device allows them; explicit opt-in to `playlist.delete` grants only that additional scope.
- Astra xhigh independent source review: **PASS**, two P3 coverage improvements addressed before deployment. Full report archived at `../reviews/ai-control-oauth-scope-astra-2026-10-16.md`. Claude review was attempted but unavailable because its session limit had been reached; do not claim Claude approval.
- Deployed via existing authenticated Wrangler session to Worker `ritovision-newpipe-ai-control`, **version `c386c303-d7cf-4c16-ac76-6ad16c1edebc`**. No secrets changed or disclosed.
- Live HTTP GET `/health` and `/setup`: HTTP 200; anonymous MCP request: HTTP 401 with all seven expected action scopes; live protected-resource metadata: HTTP 200 with matching scopes, excludes `offline_access`.
- After deploy, ChatGPT `list_devices` returned the Pixel 7 **online**. The preexisting connection's `get_catalog_status` and `get_now_playing` still returned `PERMISSION_DENIED` because its old OAuth grant has not been refreshed with new scopes. This is expected, not an Android issue.

## Required owner follow-up

Reauthorize or reconnect the *existing ChatGPT custom MCP plugin connection* on ChatGPT web so its OAuth request uses freshly advertised scopes; if the UI cannot reauthorize, remove and recreate this custom MCP connection with the same existing `/mcp` endpoint. On the owner's consent page, approve the desired non-destructive scopes and optionally check `playlist.delete` explicitly if testing temporary-playlist deletion. Leave `library.delete` unchecked unless expressly intending AI removal from the canonical Library. OAuth refresh tokens cannot widen the previous grant; the Pixel does not need re-pairing, an APK reinstall or data clearing.

Pending integration gate: repeat live ChatGPT-to-Pixel read/write tests *after* user-side reauthorization. Avoid changing or deleting any existing user Library content during smoke testing.
