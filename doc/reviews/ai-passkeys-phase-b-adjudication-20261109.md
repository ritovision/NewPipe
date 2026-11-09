# Phase B passkey gateway — review adjudication (2026-11-09)

**Source candidate, not deployed.** This record is not real-device biometric or live ChatGPT acceptance.

## Independent review

[Round 1](ai-passkeys-phase-b-astra-max-r1-20261109.md): GPT-6 Astra, **max**, read-only against baseline `743be6d3d` and a checksummed source snapshot. The assistant wrote the implementation; the reviewer did not code, run UI, access secrets or deploy. Its two actionable findings are accepted.

### P1: OAuth callback blocked by `form-action 'self'`

The generic owner portal keeps `form-action 'self'`. Each consent page additionally admits **only the canonical HTTP(S) origin of that request's provider-validated redirect URI**, kept in the server-side consent record. The provider continues to validate the exact callback, original consent browser cookie and PKCE. No wildcard, arbitrary return URL, or request-supplied CSP fragment is accepted. Both initial and resumed consent pages use this policy. Scope/consent fetch tests check the policy; primary Chromium approval and denial validation are still required before acceptance.

### P2: Anonymous pending-state starvation

Anonymous bindings are not identities and cannot hold all capacity indefinitely. Pending challenges and consents each retain a 64-record global limit, with an **anonymous FIFO budget of 32**. New anonymous requests may replace only anonymous entries; they never evict authenticated owner work. Authenticated requests may reclaim anonymous occupancy. Per-browser limits, expiry, cleanup and rate limits remain. Public-endpoint regression tests fill both anonymous pools and verify existing/new owner enrollment and OAuth consent still work.

This is not a claim of DoS immunity. Sustained public traffic may still evict an anonymous pre-login flow or hit rate limits, requiring a new sign-in attempt. It does not evict an authenticated pending approval or require the owner key merely to reclaim anonymous capacity.

## Other implementation decisions and validation

- Single-use WebAuthn challenges are consumed before verification; actual ES256 and RS256 signatures, RP hash, exact origin, UP/UV flags, credential counters, user handle and cross-origin rejection are tested. Synced passkeys with a zero counter remain supported.
- Additional enrollment requires fresh, exact action/label authorization. Enrollment completion cannot outlive the original five-minute verification deadline. Cancelled browser ceremonies retire their challenge. A cancelled, unsubmitted initial enrollment may retry within the original recovery freshness window; submitted/consumed challenges cannot be reopened.
- Sessions are random opaque cookies backed by hashed server records, not reusable signed cookie claims. Logout, credential removal, owner epoch reset and session expiry are checked before authority/consent commit. Verified recovery at session capacity retires the oldest session rather than locking out the owner.
- OAuth consent has an explicit serialized claim/commit point. Logout/revocation before claim prevents approval. Logout after claim does not retract an already approved grant; grant revocation is a separate action. Provider/storage failure afterward can leave an undelivered grant/authorization and requires a new flow, not replay of the spent consent.
- No new deployment resources, Android changes, real Library access, Cloud & Sync changes, owner-secret printing, or production deployment occurred.

## Dependency audit

The unchanged primary checkout and the first Phase B candidate each reported the same **six pre-existing** npm audit findings: body-parser, fast-uri, miniflare, sharp, undici and wrangler. Compatible lockfile updates to **fast-uri 3.1.9** and **body-parser 2.4.0** remove those two reports. Four high-severity reports remain in the existing development-tool chain (miniflare/sharp/undici/wrangler); do not characterize the overall audit as clean. The dry-run Worker bundle contained fast-uri but no body-parser/sharp/undici module sections. This is bundle attribution, not a blanket exploitability verdict.

Do not use `npm audit fix --force`: its proposed Miniflare downgrade changes the test architecture. Toolchain remediation remains an explicit follow-up/rollout check, separate from passkey correctness.

## Pending acceptance

Round-2 independent review of the fixes, fresh primary install/test run, actual Chromium UI approval/denial, real Pixel credential-manager/biometric validation and live ChatGPT reauthorization are not established by this record yet. One-tap Android pairing is still Phase C, not part of this candidate.
