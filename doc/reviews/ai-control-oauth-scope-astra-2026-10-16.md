# NewPipe AI Control OAuth scope discovery — Astra xhigh review

- Date: 2026-10-16
- Review: read-only code/source review, no edits or runtime checks performed by reviewer
- Verdict: **PASS**, with two minor coverage recommendations implemented before deployment
- Scope: uncommitted gateway PRM scope advertisement, owner consent and synthetic OAuth tests

## Reviewer findings

**PASS — no P0–P2 defect found in the current uncommitted diff.** Two nonblocking P3 test gaps:

- **P3: `library.write` lacks an exercised operation.** The successful mutation at [gateway.test.mjs:86](/home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/test/gateway.test.mjs:86) creates an empty playlist, which requires only `playlist.write` ([tools.ts:34](/home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/src/tools.ts:34)). A populated creation would exercise both scopes.
- **P3: Assert the exact granted scope set.** [gateway.test.mjs:68](/home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/test/gateway.test.mjs:68) checks inclusion plus exclusion of the two delete scopes. An unexpected `offline_access` grant would escape this assertion. Exact equality with `core` would strengthen the unrequested-scope regression check.

The security and compatibility paths look sound:

- **Broader discovery does not broaden enforcement.** In v1.2.3, `requiredScopes` supplies protected-resource metadata and the challenge; token scopes still come from the stored token. The library explicitly documents it as advertised, not enforced ([provider declarations:1133](/home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/node_modules/@cloudflare/workers-oauth-provider/dist/oauth-provider.d.ts:1133)). Narrow tokens remain usable for their permitted operations.
- **No unrequested-scope grant found.** Delete checkboxes remain unchecked. The signed, handle-bound proof validates selected scopes against the original request before approval ([auth.ts:44](/home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/src/auth.ts:44), [auth.ts:54](/home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/src/auth.ts:54)). This matters because the library’s `approveConsent` itself permits scope expansion.
- **Action checks and revocation remain intact.** Tool permissions are checked before dispatch ([mcp.ts:47](/home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/src/mcp.ts:47)); grant revocation and client blocking are unchanged. The new delete-denial tests use a connected device advertising delete permissions, so phone restrictions do not mask missing OAuth enforcement ([helpers.mjs:62](/home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/test/helpers.mjs:62)).
- **Discovery interoperability is plausible, not live-verified.** Both discovery channels now advertise the action scopes, matching the [MCP client scope-selection order](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#scope-selection-strategy). Advertising optional deletes is broader than MCP’s recommended minimal baseline; consent still controls the actual grant. The synthetic helper does not establish ChatGPT’s acceptance of a partial grant.

**Existing `mcp.connect`-only authorizations need fresh consent.** Refresh remains bounded by the stored grant and cannot acquire the newly advertised permissions automatically ([provider source:3717](/home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/node_modules/@cloudflare/workers-oauth-provider/dist/oauth-provider.js:3717)).

Reviewed the actual diff, all requested files, and relevant installed v1.2.3 source. No files changed; no tests, builds, emulators, deployments, GUI, or credential access performed.
