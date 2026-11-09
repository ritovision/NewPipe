# AI Control durable authorization — Phase A acceptance (2026-11-09)

**Branch:** `feature/ai-passkeys-20261109`; source checkpoint `c4e8b8e39`. The test record was originally captured before commit; independent-review follow-ups are recorded below.
**Scope:** Phase A of [ai-control-passkeys-durable-auth.md](../specs/ai-control-passkeys-durable-auth.md): gateway OAuth token lifetimes only. No passkey, Android, Cloud & Sync or deployment work.
**Machine:** NotCodex-2, headless CLI only. All runs are local and synthetic (Wrangler **dry-run** plus Vitest/Miniflare with ephemeral KV/Durable Objects). **Not deployed. These tests made no production authorization/data-plane requests and did not read production credentials or KV.** Operator preflight separately read deployment metadata; see [preflight](ai-passkeys-preflight-20261109.md).

## Change

`gateway/src/index.ts`, provider options only:

| Option | Before | After |
| --- | --- | --- |
| `accessTokenTTL` | 600 | 600 (unchanged) |
| `refreshTokenTTL` | 604800 (fixed 7 days) | 7776000 (90-day initial grant) |
| `refreshTokenIdleTTL` | unset | 7776000 (90-day sliding expiry on successful refresh) |
| `clientRegistrationTTL` | 31536000 | 31536000 (unchanged) |

Scopes, PKCE S256, consent, device auth, rate limits and revocation code are untouched. There is no custom token storage, refresh cryptography or KV migration code. The built dry-run bundle contains `accessTokenTTL: 600`, `refreshTokenTTL: 7776e3`, `refreshTokenIdleTTL: 7776e3`, `clientRegistrationTTL: 31536e3`.

## Provider verified

- `@cloudflare/workers-oauth-provider` **1.2.3** (installed `node_modules` version, lockfile-resolved from `registry.npmjs.org` with integrity hash). Repo history pins 1.2.3 from the gateway's first commit (`75c2676c6`). The deployed Worker's bundle was not inspected.
- Source read in `dist/oauth-provider.js`:
  - The code exchange sets `expiresAt = now + refreshTokenTTL`.
  - A refresh accepts the current or the immediately previous refresh token. It rejects when `expiresAt - now < 60` **before** any write, then sets `expiresAt = now + refreshTokenIdleTTL`, rotates, and caps access-token TTL to the remaining grant lifetime.
  - Grant KV expiration is derived from `expiresAt`; KV metadata holds only clientId/resource/redirectUri.
  - Refresh `scope` is intersected with the stored grant scope, and the grant's own scope is never rewritten.
  - DCR records carry `registrationExpiresAt`; a successful token-endpoint response renews them once `≤ TTL/2` remains.
  - RFC 7009 revocation of a refresh token revokes the whole grant.

## Commands and results

From `gateway/`, with `WRANGLER_SEND_METRICS=false`. Logs were kept in `/tmp/ai-passkeys-phase-a-20261109/`, outside the repo.

| Step | Command | Result |
| --- | --- | --- |
| Install (scripts disabled) | `npm ci --ignore-scripts --no-audit --no-fund` | 166 packages; lockfile: 265 entries, all registry.npmjs.org with integrity |
| Install-script audit | lockfile `hasInstallScript` + installed `package.json` scan | Only `esbuild` 0.28.1 and `workerd` 1.20260825.1 `postinstall` (platform-binary check; `fsevents` optional, not installed). `prepare` hooks in deps are not run for registry installs. |
| Run vetted scripts | `npm rebuild esbuild workerd` | OK |
| Build script check | `package.json` `build` = `wrangler deploy --dry-run --outdir dist` | Dry-run: "without actually uploading"; log ends `--dry-run: exiting now.` |
| Baseline | `npm test` (tsc → dry-run → vitest) | **6 files, 59/59 passed** |
| New tests vs old 7-day config | `npx vitest run test/oauth-lifetime.test.mjs` before the source change | **Expected red: 4 failed, 5 passed** (initial 90-day expiry, sliding, rotation-slide and legacy adoption failed; policy-independent tests passed) |
| Final | `npm test -- --reporter=verbose` | **7 files, 68/68 passed** (59 existing unchanged + 9 new) |
| Repeat | new file ×3 | 9/9 each run |

No existing test was modified.

## New regressions (`gateway/test/oauth-lifetime.test.mjs`)

1. A new grant expires 90 days after code exchange (gateway `/admin/grants` and KV expiration agree); access tokens are `expires_in: 600` and stored for 600 s.
2. A successful refresh moves expiry to `now + 90 days` (KV expiration too) and keeps scope and 600 s access. Ordinary MCP calls (`tools/list`, `list_devices`) do **not** move expiry.
3. Idle expiry: with less than 60 s left, or already elapsed, both current and retry-window refresh tokens get `invalid_grant`. Those validation rejections leave the sampled parsed grant JSON and KV expiration unchanged; this does not prove that no storage write occurred. A deleted record stays `invalid_grant` and unlisted.
4. Rotation: the tested previous-token retry succeeds; the superseded response token, a two-generation-old token, and another client's use are rejected. Only the superseded-response-token case has its own unchanged-grant snapshot. The original retry-expiry assertion is too close in time to prove a second extension; see review follow-ups.
5. Scope: requesting extra scopes on refresh returns only consented scopes, and delete tools stay `PERMISSION_DENIED`. Requesting only unconsented scopes gets `invalid_scope` with sampled grant JSON and expiration unchanged. A narrowed refresh does not shrink the stored grant.
6. Existing seven-day grant (see below): untouched by ordinary use; the next successful refresh adopts the 90-day idle expiry with id, client, scope, createdAt, encrypted props, metadata, resource and redirect unchanged.
7. Existing seven-day grant already lapsed: `invalid_grant`, not revived; sampled grant JSON and expiration unchanged.
8. DCR: `registrationExpiresAt − registrationDate = 31536000`, with KV expiration matching. A young registration is not rewritten; a failed token request does not renew. A successful refresh past half-life renews to `now + 1 year` with the same `registrationDate`. A lapsed registration makes even a live grant fail with `invalid_client`. Also asserts `TTL/2 (182.5 d) > idle window (90 d)`.
9. Revocation after sliding: `/admin/revoke-grant` and client RFC 7009 refresh-token revocation each kill the access token (401) and both current and retry-window refresh tokens.

**Simulation method:** elapsed time is simulated only in the test's ephemeral Miniflare `OAUTH_KV`, via `getKVNamespace`. The test rewrites the provider's own TTL-derived fields (grant `expiresAt`/`createdAt`, client `registrationExpiresAt`) together with the matching KV `expiration`, and preserves KV metadata. For expired-but-present cases it keeps the KV entry alive, so the provider's logical check is what is under test; physical KV expiry is simulated by deleting the record. No clock is moved, and no production storage is involved.

## Existing seven-day grant migration behavior

- A grant written under the old config differs from a new one **only** in `expiresAt` (= code exchange + 604800) and the matching KV expiration. Test 6 reproduces exactly that record shape from a real grant, so the result reflects the real refresh path.
- **Deploying this change does not modify any stored grant.** Until it is refreshed, an existing grant keeps its original expiry: 7 days after its original code exchange, not after its last refresh. Under the old fixed policy, refreshes never extended it.
- **If the client refreshes successfully before that expiry** (more than 60 s before), the provider itself sets `expiresAt = now + 90 days`. That is the same grant write that rotates the token on every refresh, so no metadata migration and no scope change is involved. From then on the grant slides.
- **If it lapses first,** refresh returns `invalid_grant`, it is not revived, and one reauthorization is needed (owner-key consent today; passkey after Phase B).
- Whether the live ChatGPT connection avoids that one reauthorization depends on ChatGPT refreshing within its grant's remaining window after deployment. That window, and ChatGPT's refresh behavior, were **not** observed. **No claim of zero reauthorization is made.**

## Observed properties worth review

- There is no absolute maximum lifetime (as the spec intends). A grant refreshed at least every 90 days, by any holder of a valid refresh token, never expires by itself; revocation is the control.
- The provider does not revoke a grant when a superseded refresh token is presented; it rejects that one request.
- Outstanding access tokens are not revoked by rotation and remain valid up to their own 10 minutes. Grant revocation deletes them.
- `listUserGrants` returns any record still present in KV and does not filter by `expiresAt`.

## Not tested

- Real ChatGPT OAuth refresh/reconnect behavior, its refresh cadence, and whether it honours `client_secret_expires_at` (which could force re-registration and a new consent regardless of grant lifetime).
- Production Worker, production OAuth KV contents, the age and expiry of currently live grants, and Cloudflare KV global consistency or real TTL eviction.
- Real passage of 90 days or 1 year, since all lifetimes were simulated as described above.
- Any phone, Android app, browser, passkey or deployment step.

## Independent review and remaining acceptance work (2026-11-09)

[Astra Extra High review](../reviews/ai-passkeys-phase-a-astra-xhigh-20261109.md) found no blocking error in the lifetime configuration, but identified two material P2 coverage/wording gaps. README wording is corrected; test additions remain outstanding because the local coding service reached its usage limit. **This checkpoint is not deployment acceptance.**

1. The previous token is not limited to one retry: repeated use of the same presented token can continue until a different token is used, or expiry/revocation. Add repeated-reuse coverage and age the grant before asserting that a previous-token retry moves expiry.
2. The provider stores grant renewal/rotation before writing the access token. A later storage failure may leave a renewed grant despite a failed HTTP response. Add fault-injection and recovery coverage; restrict unchanged-state claims to observed validation rejections. Add snapshots for wrong-client/old-token failures and preserve/check KV metadata, not just parsed grant JSON and expiration.

Additional useful coverage: renewal of a grant originally created more than 90 days ago; denied capability checks on a narrowed access token; actual provider logical-versus-physical DCR expiration behavior. DCR renewal is best effort.

The primary machine independently ran `npm test` at source `c4e8b8e39`: TypeScript and dry-run passed, **68/68 tests across 7 files passed**. No source/test changes were made after that run in this documentation correction. No live authorization lifetime has been changed.
