# AI Control passkey gateway — Phase B source acceptance (2026-11-09)

**Branch:** `feature/ai-passkeys-20261109`. **Exact tested production-source commit:** `44711d025` (initial implementation `20b07dd5a`, primary-only browser runner correction `554f3e4ed`). **Not deployed.** No actual owner passkey, Pixel biometric or live ChatGPT reauthorization is established by this record.

The assistant authored the implementation directly. GPT-6 Astra **max** performed independent read-only reviews; no reviewer was authorized to code. Claude was not used in this continuation. Phone/browser/UI operations stayed on the primary workstation. The secondary performed only headless CLI, builds, tests and reviews.

## Implemented

- Passkey-first `/setup` and OAuth authentication using pinned SimpleWebAuthn server **14.1.0** and browser **14.0.0**, with browser code bundled locally. There is no CDN, biometric capture, custom signature verifier, exposed production test hook or new OAuth token server.
- Existing owner key retained for one-time enrollment and explicit emergency recovery. It was not read, displayed, copied into chat, rotated or added to source during this work.
- Revocable, opaque one-hour owner browser sessions backed by hashed records in the existing `ControlAccount` Durable Object. Browser binding, CSRF, exact Origin, credential linkage, expiry and owner epoch are checked authoritatively. Existing signed admin cookies are intentionally no longer accepted by this candidate.
- Single-use five-minute WebAuthn challenges with actual signature, RP hash, origin, UP/UV, user-handle and counter verification. Synced zero-counter keys are accepted; invalid counter/backup eligibility transitions are rejected. ES256 and RS256 are covered with real cryptographic fixtures.
- Additional passkey enrollment and removal require fresh action/target-bound authorization. Last-passkey removal requires fresh recovery proof and explicit confirmation. Cancelled initial enrollment can retry only within its original recovery freshness window; an already submitted/consumed challenge cannot be reopened.
- OAuth login remains distinct from explicit consent. Existing browser login avoids repeating the owner-key prompt; destructive scopes remain unchecked. Original provider consent cookie, PKCE S256, exact callback, scope ceiling and single-use claim remain enforced.
- Callback CSP admits only self plus the specific, provider-validated and CSP-safe callback origin. Public tests reject wildcard/metacharacter hosts even when the OAuth provider accepts their URL syntax.
- Bounded state and cleanup: maximum 16 credentials, 32 sessions, 64 challenges, 64 consents, 32 permits, 64 audit entries and a 1 MiB serialized record. Anonymous pending work has a separate 32-entry FIFO budget and cannot evict authenticated owner work. This does not claim immunity to ongoing traffic floods/rate-limit exhaustion.
- Phase A follow-up tests now cover repeated previous-token reuse, old grants, reduced-scope denial, full relevant KV snapshots/metadata and retry after partial token-storage failure. No claim that token issuance is atomic.

## Exact-source validation

| Environment | Command / check | Result |
| --- | --- | --- |
| Secondary, headless | `npm test` (browser asset build, TypeScript, Wrangler dry-run, Vitest/Miniflare) | **130/130 tests, 8 files passed** |
| Primary, independent checkout | Clean `npm ci` at the Phase B dependency commit; latest exact source followed by `npm test -- --maxWorkers=1` | **130/130 tests, 8 files passed** |
| Primary actual Chromium | Existing Playwright Python environment running `gateway/scripts/qa-passkeys-browser.py` | **11/11 checks passed** |
| Phone availability only | Windows ADB server `5042`, explicit Pixel Tailscale transport, `get-state` | `device`; no installed app/data modification |
| Source matching | SHA-256 of seven production source/dependency inputs, independently on both machines | Identical |

The 130 tests consist of **60 passkey/security tests + 11 OAuth lifetime tests + 59 existing gateway/reliability/tagging/contracts tests**. Existing tests needing owner consent were adapted to the real new owner-session/CSRF flow, not bypassed.

### Browser acceptance: what actually ran

A new primary Chromium instance, disposable browser context and virtual CTAP2 authenticator used real `navigator.credentials.create/get` against the bundled gateway. The server was an ephemeral Miniflare instance behind a temporary loopback-only HTTPS adapter. Synthetic credentials/certificates were discarded. External callback navigation was intercepted to a local synthetic response; no production gateway or actual OAuth client was contacted.

The browser checked:

1. Passkey-first page, secure context and actual local JavaScript bundle.
2. Recovery bootstrap into a resident/discoverable passkey.
3. Returning passkey login without owner-key entry.
4. User cancellation leaves a usable page and does not issue a credential.
5. A second virtual device enrolls after fresh recovery verification; the cancelled attempt had already exercised existing-passkey step-up.
6. OAuth login returns to an explicit permission screen with deletion unchecked.
7. Approval reaches the external callback under the actual browser CSP; code exchange validates PKCE and returns only approved scopes with 600-second access.
8. Cross-site return reuses the owner browser session.
9. Authenticated denial reaches the callback without a code.
10. Anonymous denial reaches the callback without owner authentication or a code.
11. No uncaught browser JavaScript errors.

**This is not evidence of a real Pixel credential manager, fingerprint, device PIN, browser restart, or the ChatGPT Android authorization surface.** Those remain live acceptance gates.

### Fault and concurrency tests

- Partial refresh writes: the real provider rotates/extends a grant before a synthetic access-token KV failure; a retry with the still-accepted previous token recovers. Later old tokens are rejected as expected.
- Initial grant KV failure after the durable consent claim: HTTP failure yields no authorization code, the claim remains spent, replay fails, and an independently started flow succeeds. Test-only injection clears a cached provider helper so the failure genuinely reaches the intended KV binding.
- A passkey used as a fresh authorizer may differ from the session credential. Revoking that authorizer invalidates its pending enrollment even while the other session remains live.
- Simultaneous enrollment/revocation and consent/logout are ordered by the serialized authority transition. Audit-order assertions verify successful operations commit before revocation/logout; late attempts fail.

Failure injection and deterministic state manipulation are in separate ephemeral test-entry Workers only. None is imported into the deployed source entry.

## Review record

- [Round 1](../reviews/ai-passkeys-phase-b-astra-max-r1-20261109.md): found callback CSP blocking and anonymous capacity starvation. Both corrected with browser/public-endpoint regressions.
- [Round 2](../reviews/ai-passkeys-phase-b-astra-max-r2-20261109.md): accepted capacity isolation, found canonical-URL/CSP syntax injection. Corrected before opening a consent transaction and before composing the HTML policy; negative and valid-origin regression tests added.
- [Round 3](../reviews/ai-passkeys-phase-b-astra-max-r3-20261109.md): **approved source candidate `44711d025` within the targeted follow-up; R2 CSP finding resolved, no new blocking findings**. The reviewer inspected source/callsites/tests and performed 29 rejection, 13 accepted-origin and 2,660 character probes; it did not independently run the project suite or Chromium. Those exact-source tests were separately run by the assistant as documented above. [Adjudication](../reviews/ai-passkeys-phase-b-adjudication-20261109.md) records the decisions and remaining live-release boundaries.

## Validation issues encountered and resolved/qualified

- The first primary fetch used a narrow remote refspec and left an old tracking ref. Its 68-test result is **not** Phase B evidence. This was caught before acceptance, and all subsequent candidate runs checked the exact commit after an explicit branch-ref fetch/fast-forward.
- An initial concurrent primary run at `20b07dd5a` had **117 pass / 1 timeout** in an existing tagging-permission test with its default five-second timeout. The unchanged 11-test tagging file passed alone. The full suite then passed serially, including the latest **130/130** run. No assertions or timeout limits were relaxed; primary acceptance uses one worker to avoid contention on the slower UI workstation. Secondary concurrent execution passed.
- The first browser QA runner stalled when a synchronous Playwright callback attempted nested CDP commands. The owned job was stopped, and only the QA runner was corrected to switch virtual authenticators between browser operations. The abandoned synthetic temporary directory was removed. Two subsequent complete browser runs passed, including the latest exact source.

## Dependency audit boundary

Six advisories existed on the unchanged baseline as well as the initial Phase B candidate. Compatible transitive updates to `fast-uri 3.1.9` and `body-parser 2.4.0` remove two. **Four high-severity reports remain in the development-tool dependency chain** (`miniflare`, `sharp`, `undici`, `wrangler`). The project audit is not clean. The Worker bundle did not contain body-parser/sharp/undici module sections; this is attribution, not a blanket exploitability assessment. Toolchain remediation remains a rollout task. No forced Miniflare downgrade was performed.

## Remaining work / live release gates

- Phase C no-copy Android pairing is **not implemented**. Existing Android/manual-code pairing is unchanged. Existing paired phones would not need a new APK merely for gateway passkey authentication.
- Actual Pixel browser/credential-provider enrollment and sign-in, actual user verification, live ChatGPT reconnect/refresh, Android host/browser-return behavior, and rollout/rollback validation are pending.
- Re-read actual deployed gateway metadata before deployment. Retain existing OAuth/device identity and separate Cloud & Sync state. Old admin sessions can require one new login; never silently broaden grants.
- Resolve/adjudicate development-tool advisories before live release. No claim of permanent online Android reachability or indefinite ChatGPT token retention.

## Compact evidence fingerprints

Logs remain outside the repository in each machine's `~/.local/state/newpipe-passkeys-20261109/`. They are not copied wholesale into Git.

| Evidence | SHA-256 |
| --- | --- |
| Secondary `phase-b-secondary-final-tests.log` | `9961b84c363e0a9bfaebe574aad778466341b730862c9de998ecacad2285406f` |
| Primary `phase-b-primary-final-tests.log` | `9ab9208e4cfd39a285290f73c63da97a7a4e9659bcfae304010a2b94e737cbf9` |
| Primary `phase-b-primary-final-browser.log` | `975b9eff520a245b4bcead939a410fa07cfdbdd3074fe9164cabed21bf328e68` |
| Primary `phase-b-browser-result.json` | `5b2e3cc615c4505f918682906a76af808fcda37c1ef6797cf9a548d25afb5a9e` |

| Matched production input | SHA-256 |
| --- | --- |
| `gateway/src/owner-state.ts` | `7425df4c30c281455503807c3b45d3f21229911e8e50f1bf2942c5d959f93faa` |
| `gateway/src/owner-web.ts` | `e22e39315969f3081671cfad057799206acfaccd6d5ddf5fbb3019dd93cd7c08` |
| `gateway/src/auth.ts` | `e799682f267c57ac9ec87ea0c3937574ba1f315016a7d015176c85122430bf3d` |
| `gateway/src/account.ts` | `6e8706b7e58cf8d2b2c960d9f9eb9061042d955cf5e85002e4fea9056e5e6116` |
| `gateway/src/index.ts` | `6c6a293993801b2479061986196455494a7598f871f9028d92af3e2246f804cc` |
| `gateway/web/passkeys.js` | `00651d1c0f7d10c30e9dac4ac6914e45e08b2e135303d010f2dd0545e818dc07` |
| `gateway/package-lock.json` | `165dced1d54a9626e2b56a98c63786ee420be5c47b0dfd4a8a0642db6c2cb52f` |
