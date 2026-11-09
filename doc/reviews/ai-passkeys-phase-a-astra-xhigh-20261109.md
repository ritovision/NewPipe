Reviewed exact `c4e8b8e39` against `b2013928c`, read-only. **No blocking error found in the Phase A lifetime configuration.** Two material documentation/test findings remain:

1. **[P2] Retry wording understates refresh-token reuse, and the retry test does not prove another expiry extension.**
   [README:45](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/README.md:45) describes “one lost-response retry.” However, the provider assigns `previousRefreshTokenId = providedTokenHash` after every successful refresh ([provider:3792](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/node_modules/@cloudflare/workers-oauth-provider/dist/oauth-provider.js:3792)). Consequently, `r0 → r1`, followed by retrying `r0 → r2`, leaves **r0 reusable again**. Repeated use can continue extending the grant until a different token is successfully presented or the grant is revoked. There is no separate time-bounded retry window.

   The [rotation test:75](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/test/oauth-lifetime.test.mjs:75) advances to `retry.refresh_token` before checking that `r0` fails, so it misses repeated reuse. Its expiry assertion immediately follows the preceding successful refresh; the ±1-second tolerance would also permit a retry that did not extend expiry. Document the actual reuse behavior, test another `r0` retry, and age the grant before asserting that a previous-token refresh slides it. The reuse mechanism predates this commit, but sliding expiry removes its former seven-day absolute bound.

2. **[P2] “Failed refresh writes nothing” needs a narrower guarantee and stronger acceptance evidence.**
   The [test:59](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/test/oauth-lifetime.test.mjs:59) covers validation rejection, not storage failure. The provider extends expiry and saves the grant **before** writing the access token ([provider:3797](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/node_modules/@cloudflare/workers-oauth-provider/dist/oauth-provider.js:3797)). If the latter write fails, the request fails after rotation and extension have persisted. Thus an unsuccessful HTTP refresh can extend authorization; issuance is not atomic.

   Also, [`stored()`:21](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/test/oauth-lifetime.test.mjs:21) compares parsed grant JSON and expiration only. It cannot establish the [report’s “byte-identical”/no-write claims:52](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/doc/validation/ai-passkeys-phase-a-20261109.md:52): it excludes KV metadata, access-token records and client records. The wrong-client and two-generation-old rejection cases lack their own unchanged-state assertions. Restrict the guarantee to validation failures, add those snapshots, and cover recovery after an access-token storage failure.

The configuration itself checks out. [`index.ts:42`](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/index.ts:42) uses supported fields in installed and pinned `@cloudflare/workers-oauth-provider@1.2.3`: 600-second access tokens, 7,776,000-second initial and sliding grant lifetimes, and unchanged 31,536,000-second DCR lifetime. Refresh validates the existing expiry before extension; fewer than 60 seconds remaining is rejected. DCR renewal occurs after successful token-endpoint responses when at most half its TTL remains, and is best effort.

The tests exercise the built gateway and real provider. Initial issuance, aged-grant sliding and legacy adoption assertions would detect reverting the policy; they do more than verify their own fixtures. The legacy simulation is consistent with the unchanged provider’s old record format. Scope intersection, restoration of originally consented scopes after narrowing, and sequential grant revocation match the implementation.

The supplied [primary log](/home/ritorhymes/.local/state/newpipe-passkeys-20261109/primary-phase-a.log) records successful type-checking, dry-run build and **68/68 tests**, including all nine new tests. I did not rerun them.

Residual limitations and useful acceptance additions:

- Exercise a grant older than 90 days across successive refreshes, and verify that a narrowed access token actually denies a removed permission.
- Assert preservation of **KV metadata**, beyond the grant’s JSON `metadata`.
- DCR expiry rejection currently simulates deletion. The provider relies on KV eviction rather than logically rejecting an elapsed `registrationExpiresAt`.
- The README’s assertion about the live Worker’s seven-day configuration is not established by this source review; qualify it as the expected prior deployment state.
- Real client behavior, production deployment state, distributed KV races/eviction, and the reported historical red/repeat runs remain independently unverified.
