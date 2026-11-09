Reviewed against `743be6d3d`. All nine source-manifest entries still match. **Two actionable findings:**

1. **P1 — The consent CSP blocks external OAuth callback navigation.**
   [owner-web.ts:58](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/src/owner-web.ts:58), [auth.ts:69](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/src/auth.ts:69)

   Consent pages set `form-action 'self'`, while successful approval—and denial—returns an HTTP redirect to the client’s external callback. Chromium applies this restriction to redirected form navigation. Consequently, authorization can commit and consume the consent, then fail to return the code to ChatGPT. Retrying the form cannot recover the spent transaction.

   Make the consent policy accommodate the provider-validated callback, or complete the same-origin POST with a separate navigation step. Validate approval and denial through an actual browser on the primary workstation. The current fetch-based tests inspect the `302` without enforcing CSP. **This finding follows from source and browser-policy semantics; I did not reproduce it in a browser.**

2. **P2 — Anonymous requests can exhaust capacity required by authenticated owners.**
   [owner-state.ts:147](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/src/owner-state.ts:147), [owner-state.ts:372](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/src/owner-state.ts:372)

   Once a passkey exists, an unauthenticated caller can obtain 16 browser bindings and allocate four login challenges per binding. Those 64 pending challenges also block authenticated step-up and registration. Separately, a public OAuth client can fill all 64 consent slots using fresh browser contexts, blocking new consent even for an already authenticated owner.

   Each attack fits below the configured 120-request/minute limit and can maintain occupancy with relatively little traffic. Browser cookies do not provide an effective attacker quota. Session-cap eviction does not address these shared pending-state limits.

   Reserve capacity for authenticated operations, separate anonymous allocation budgets, and allow verified owner activity to reclaim anonymous occupancy where appropriate. Exercise exhaustion through public endpoints. This is an availability defect; recovery login and an explicit recovery-key session reset provide a workaround.

The **durable consent claim is otherwise a sound authorization commit point** under the documented contract. Its serialized transition checks session authority and the original scope ceiling; subsequent provider operations still enforce the original transaction cookie and PKCE. Provider failure leaves the consent spent, preventing replay. Issuance and delivery are not atomic, so failure can leave an undelivered grant or require a new authorization flow. Logout after the claim need not retract that committed authorization.

I found no additional actionable bypass in the inspected WebAuthn verification, action/target permits, authorizer revocation, public-field filtering, legacy-cookie rejection, or scope enforcement. The verified-owner session eviction and original enrollment freshness deadline are appropriate. Native device cryptography and existing pairing storage remain separate.

Nonblocking coverage gaps:

- Real ES256 signatures and parallel assertion/consent replay are covered. Add deterministic revocation-versus-registration/claim races, including a fresh authorizer different from the session credential, and provider/storage failures after consent claim.
- RS256, browser restarts, credential-manager compatibility, Pixel biometrics, and live ChatGPT behavior remain separate validation work. I inspected the added browser QA scripts without executing them.
- Phase A follow-up tests now address repeated previous-token reuse and partial refresh writes. Dependency advisory attribution/remediation remains pending baseline comparison; this review establishes no new SimpleWebAuthn advisory.

**Verdict: request changes before release**, principally for the callback failure and anonymous capacity starvation.

This was read-only source review. I ran no tests, builds, deployments, or browser activity and changed no files. The inspected author-run logs report 11 lifetime tests, 70 existing/follow-up tests, and an updated 44 passkey tests passing.
