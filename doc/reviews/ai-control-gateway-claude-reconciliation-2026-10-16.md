# Claude Opus: gateway OAuth reconciliation — 2026-10-16

Independent *focused* read-only review of the proposed test-only gateway diff,
relevant OAuth configuration and owner-consent excerpts, supplied to Claude.
This is **not** a blanket audit of the entire gateway or full system.

# Verdict: **PASS**

Keeping the live 7-scope config, committing only the refresh test, and not deploying is sound. The pending diff touches only `gateway/test/gateway.test.mjs`. Nothing in it reaches the Worker bundle, so it can't affect the working live connection.

## P0–P2 regressions

**None.** This diff contains no production source change, so it cannot introduce a correctness regression.

On discarding the 5-scope patch, the proven fault and the speculative part are separate:
- **Proven from the code shown:** `authorize()` builds checkboxes only from `parsed.scope` ∩ `SCOPES`. A request without `.delete` scopes therefore gets no delete checkbox. Opting in becomes impossible for that authorization.
- **Speculative:** whether ChatGPT actually builds its request from the 401/resource-metadata hint on reconnect. That's plausible but not demonstrated.

Together, that's enough reason to reject the 5-scope patch while delete works in production. Discarding it was correct.

## Can the refresh test false-pass or hide escalation?

**It does not hide scope widening.** `expect(second.scope.split(' ')).toEqual(requested)` is an exact match. If refresh returned extra scopes such as `.delete`, the test would fail.

These are coverage gaps, not faults (P3):

1. **It doesn't check that the refreshed token enforces its scopes.** `tools/list` returning 200 only shows the token is accepted. It doesn't show the token is limited to `library.read` and `playback.control`. If refresh returned the right `scope` string but the token carried full grant props, the test would still pass. Suggested hardening: call a delete or playlist-write tool with `second.access_token` and expect an insufficient-scope rejection.
2. **It doesn't check rotation.** There's no assertion that `second.access_token !== first.access_token` or `second.refresh_token !== first.refresh_token`. The test name says "new" credentials, but nothing proves they are new.
3. **The scope check depends on order.** `toEqual(requested)` without sorting relies on `'mcp.connect'` being prepended and on the harness submitting checkboxes in a fixed order. It passes today, but it's brittle. The neighbouring opt-in test sorts both sides.
4. **The test pins refresh issuance without `offline_access`.** `requested` omits `offline_access`, yet a refresh token is expected. That matches current live behaviour (login prompts stopped), so it's fine. Just note that it locks the behaviour in.
5. **The "no owner login" claim is mostly safe.** If `h.get` reuses a cookie jar from the earlier `h.oauth()` login, the claim is technically untested. In practice `/oauth/token` is served by `OAuthProvider`, not the consent handler, so this is low risk.

A side observation: a 3-scope token gets 200 on `tools/list`. That implies `requiredScopes` (which includes the delete scopes) is not enforced as all-of on `/mcp` in v1.2.3. That's consistent with live behaviour, but it rests on test evidence, not a verified reading of the library.

## Release recommendation

- **Commit** the test-only change; optionally apply hardening items 1–3 first. **No deploy is needed or warranted.**
- **Leave production at the 7-scope hint.** Keep the backed-up 5-scope diff shelved.
- If narrowing the hint is revisited later, first change the consent page so optional `.delete` checkboxes (default OFF) render even when the client didn't request them. Then add a test covering reconnect from the narrowed hint. Narrow the hint only after both are in place.
- Not reviewed here: whether the consent POST limits submitted scopes to the offered set. `scope_proof` suggests it does, but that is outside this diff.
