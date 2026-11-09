# NewPipe AI Control — Passkeys, Passwordless Pairing, and Durable Authorization

**Decision date:** 2026-11-08  
**Status:** Approved product/engineering specification; **not implemented**. This document is a new work item, not a description of deployed behavior.  
**Scope:** Existing private NewPipe AI Control Android client and its separate Cloudflare MCP/OAuth gateway. **Do not change Cloud & Sync, its account, D1/R2, or its credential model.**  
**Related:** [AI Control v1](ai-control-v1.md), [Cloud & Sync v1](cloud-sync-v1.md), [gateway documentation](../../gateway/README.md).

**Implementation security requirements (2026-11-09):** Read the [independent design review and adjudication](../reviews/ai-passkeys-design-adjudication-20261109.md) before implementation. It clarifies authoritative session revocation, action-bound verification, single-use consent and pairing commit behavior without changing the approved user-visible goals.

## 1. Goal and accepted user experience

The user must never have to remember or carry a long owner setup key for normal AI Control use. Keep the underlying ownership and device-security boundaries, but replace the repeated manual-secret and manual-pairing workflow.

**Accepted product decisions:**

1. The owner enrolls a **WebAuthn passkey once** in a browser on the private gateway. Initial enrollment uses the existing owner setup key. The device's authenticator or password manager stores the private credential; the gateway stores a public credential and verification metadata. Fingerprint is a possible device-unlock method, not a promise: platform face unlock or PIN may be offered.
2. After enrollment, both gateway administration and new AI-client OAuth consent use the passkey. **The long owner key is a recovery/bootstrap credential, not a normal daily login method.**
3. Existing/authorized ChatGPT connections operate without fingerprint prompts **per command**; the user talks to ChatGPT to manage/play the selected NewPipe device. Reauthentication is only required when a grant expires, is revoked, scopes change, or the client loses credentials.
4. NewPipe gets a **Connect to AI** pairing path: initiate secure pairing in the app, open the trusted gateway in the phone browser, authenticate and approve, return to NewPipe connected. Do **not** make the owner copy an expiring pairing code by default. Retain the existing manual-code flow as a recovery fallback during rollout.
5. OAuth grant lifetime is **90 days of inactivity**, extended on successful refresh. Access tokens stay short-lived at **10 minutes**; normal token rotation is automatic; **no mandatory annual reset**. Revocation always remains available.
6. Existing device pairing, canonical Library, permissions, ChatGPT grant scopes and Android foreground service behavior are preserved. Authentication must not introduce a second approval prompt for each authorized catalog or playback command.

**Desired normal flow:** NewPipe is paired and AI Control foreground service is running -> user asks ChatGPT to play/organize on an explicitly named device -> ChatGPT uses its existing OAuth grant -> gateway applies scopes -> Android independently enforces local scopes and executes. No owner-key entry, app switching or biometric prompt for each command.

**Important distinction:** NewPipe service availability (Android Stop, force-stop, battery restrictions, offline) is independent of owner/passkey authentication. A grant does not keep a stopped phone online.

## 2. Source-verified baseline (before this feature)

- Gateway implementation: `gateway/src/auth.ts`, `gateway/src/index.ts` and `gateway/src/shared.ts`. Android pairing UI: `app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiControlActivity.kt`.
- `/authorize` currently requires `owner_key` on **every** OAuth authorization POST, even if a browser already has an admin session.
- `/setup` accepts the same key and issues a **one-hour** `__Host-newpipe-admin` cookie; this is an admin-browser session, **not** an OAuth grant.
- OAuth provider is currently pinned to `@cloudflare/workers-oauth-provider@1.2.3`; `accessTokenTTL: 600`, `refreshTokenTTL: 604800` (a **fixed seven-day grant**, not sliding), `clientRegistrationTTL: 31536000`.
- Pairing is currently portal-generated, five-minute, one-time code entered manually in the NewPipe AI Control screen. Android generates a Keystore-backed device identity and never exports its device private key.
- Credentials on the provisioning machine are stored outside the repository (documented `~/.local/share/newpipe-ai-control/owner-credentials.json` with owner-only permissions); the server stores an owner-key hash. Never log, publish, embed, migrate into APK/backup, or print the raw owner key.
- **Provider feasibility:** Cloudflare Workers OAuth Provider **1.2.3 already implements** `refreshTokenIdleTTL` for sliding grant expiry, refresh-token rotation and active dynamic-client-registration renewal; no homegrown token server is needed. Confirm behavior in the pinned installed version and real ChatGPT connection before release. References: [Cloudflare authorization-server docs](https://github.com/cloudflare/workers-oauth-provider/blob/main/docs/authorization-server.md), [advanced configuration](https://github.com/cloudflare/workers-oauth-provider/blob/main/docs/advanced-configuration.md), [changelog, v1.2.3](https://github.com/cloudflare/workers-oauth-provider/blob/main/CHANGELOG.md).

## 3. Passkey-backed gateway ownership

### Enrollment

- Add a dedicated passkey-management area to `/setup`, showing registered passkeys, display labels, creation/last-used timestamps, and revoke controls.
- To enroll the first passkey, owner authenticates with the **existing** owner key through a secure browser session. Display explicit account/gateway context and register a passkey using the browser's WebAuthn API, recommending a synced phone passkey and a second independent recovery authenticator where practical.
- Register additional passkeys only from an authenticated, CSRF-protected owner session with a fresh passkey/owner-key confirmation for sensitive enrollment. Never silently enroll on OAuth consent.
- Require WebAuthn **user verification**, correct RP ID, exact expected HTTPS origin, an unpredictable single-use server challenge with short TTL, and replay-proof verification. Store only credential ID/public key, signature counter and backup/transport metadata; update the counter safely when used. No biometric templates or passkey private keys reach Cloudflare or ChatGPT.
- The WebAuthn **relying-party ID must be a specific, controlled gateway host**. Decide whether to introduce a stable custom hostname **before enrolling**; changing host/RP ID later risks requiring new passkeys. Do not broaden RP ID to `workers.dev` as a shared parent domain.
- Implement with well-maintained WebAuthn libraries (e.g. `@simplewebauthn/server` and `@simplewebauthn/browser`), **not handmade signature/challenge parsing**. Verify current versions actually bundle and run in Cloudflare Workers/Miniflare. References: [SimpleWebAuthn server](https://simplewebauthn.dev/docs/packages/server), [Google passkey server guidance](https://developers.google.com/identity/passkeys/developer-guides/server-authentication).

### Login, consent, and recovery

- `/setup`: unauthenticated owner selects **Sign in with passkey**. On successful assertion, issue/rotate a short-lived, Secure + HttpOnly + SameSite cookie. Keep explicit sign-out and CSRF protection for privileged actions. A one-hour admin session is acceptable initially and remains independent of the 90-day OAuth policy.
- `/authorize`: if already signed into a valid owner session, do **not** ask for owner key again. Otherwise authenticate by passkey, bind the login to the pending OAuth consent transaction, then show requested client identity, redirect target and requested scopes for **explicit approval**. No auto-consent merely because biometric sign-in succeeded.
- Preserve OAuth authorization-code + PKCE S256, redirect/loopback warnings, consent transaction integrity, grant scoping, independent local Android permission ceiling and default-unchecked destructive scopes (`library.delete`, `playlist.delete`).
- Recovery: keep the original long owner key as a **carefully rate-limited emergency enrollment/recovery path**, outside normal sign-in and consent. Recommend at least two passkeys; define safe recovery if all passkeys are lost. Recovery must not bypass signed-in user CSRF, device proofs, or scope selection, and must be audited. Never display the original key in UI, logs, support text or chat.
- Explicitly test passkey cancellation, revoked credentials, counter anomalies, incorrect RP ID/origin, cross-account/device replay, concurrent challenges, recovery/last-passkey removal, browser restarts and failure states. Fail closed without stranding the owner if the documented emergency key is valid.

## 4. No-copy Android pairing

Keep pairing conceptually separate from OAuth consent: Android device enrollment authenticates the **device public key** to the gateway, while ChatGPT OAuth authorizes a **client** to operate within its scopes.

### Default user journey

1. Owner opens **NewPipe > AI Control > Connect to AI**. The app displays which account/gateway and device name it intends to pair; user reviews the Android-local permission ceiling.
2. App generates/reuses its Android-Keystore-backed device key, starts a short-lived pending pairing request bound to that key and a locally stored unpredictable state value, and opens the HTTPS gateway approval URL in a trusted external browser.
3. Browser authenticates the owner by passkey if needed and displays a clear **Approve this NewPipe device** page identifying the request and permissions/identity. User explicitly approves.
4. Gateway commits a **single-use** enrollment only for the same key that began the request; NewPipe retrieves/verifies completion using a device-key-signed proof over TLS, without a raw owner key or pairing credential in URL, clipboard, logs, or backups.
5. On success the app shows **Paired** and offers **Start AI Control**. The foreground service remains an explicit opt-in. A cancelled/expired/offline request shows retry without accidentally replacing a valid pairing.

**Implementation proposal, not a mandated protocol:** app-initiated short-lived `begin -> browser approve -> device-key authenticated status/finish` exchange. App can poll for completion with backoff (and pause when backgrounded), so Android verified App Links are **not a prerequisite**. A verified HTTPS App Link may optionally return the user from browser to app; do not rely on unsafe custom-scheme tokens. Design the session so theft of an opaque browser URL or pairing handle alone cannot enroll an attacker's device. Use a short TTL (target <=5 minutes), atomic consume, replay protection and device-key proof-of-possession. Rate-limit begin/status/approval routes. Require an affirmative owner approval screen.

- Retain manual five-minute pairing codes initially as an advanced fallback. Once the no-copy path passes Pixel QA, hide manual entry under **Advanced > Pair with code**, not as the main journey.
- No passkey UI or biometric verification belongs in Android playback/catalog commands; the authenticator is hosted by the **browser/gateway**.
- Preserve Keystore/no-backup boundaries, selected device ID, Android permission ceilings, current grant revocation and existing paired identities. Do not unpair working devices during migration, regenerate device keys, reinstall over existing data blindly, or change the Room schema merely for pairing.

## 5. Durable OAuth authorization — precise contract

- `accessTokenTTL = 600` seconds (10 minutes, unchanged).
- `refreshTokenTTL = 90 * 86400 = 7,776,000` seconds (**initial** grant expiration).
- `refreshTokenIdleTTL = 90 * 86400 = 7,776,000` seconds (**sliding** expiry on **successful refresh**).
- Refresh-token rotation/replay handling remain delegated to pinned Cloudflare OAuth Provider. Do not write custom refresh token storage, avoid tokens in logs/URLs, and preserve grant revocation.
- **Meaning of 'active':** a successful refresh moves grant expiry forward 90 days. A plain AI command, app open, or unsuccessful refresh **does not** itself extend expiry. With 10-minute access tokens, connected clients that continue making requests should regularly refresh, but it depends on the **host client's actual refresh behavior**; validate against ChatGPT. Neither the gateway nor this spec can guarantee that ChatGPT retains its tokens forever.
- No mandatory annual absolute expiry. Device revocation, user/client grant revocation, lost credentials, changes requiring additional scopes, and security incidents still require reauthorization.
- Keep the currently configured one-year client registration TTL and verify the provider's active DCR renewal behavior (in v1.2.3); otherwise a DCR registration could expire while the 90-day sliding grant remains active.
- Existing seven-day grants: prefer keeping existing client grants usable until their existing expiry and asking for **one passkey-based reauthorization** when needed. Do not silently rewrite OAuth KV grant metadata or expand scopes. Verify whether the provider can safely adopt the new idle policy for existing grants and document the migration behavior; no claim of zero reauth until validated.
- Upfront consent still needed for a **new client or new capabilities**. Never grant additional deletion scopes on token refresh or use the change to bypass existing OAuth/Android permission checks.

## 6. Implementation sequence, acceptance, and rollout

**Phase A — gateway token policy (low complexity):** configure two 90-day TTLs; keep access TTL and client registration TTL; add synthetic tests for initial expiry, successful sliding refresh, idle expiry, rotation/retry, DCR renewal, revocation, scope non-escalation and existing grants. Time-box real ChatGPT OAuth refresh verification. No Android code required.

**Phase B — WebAuthn ownership (moderate complexity):** choose stable RP host; add provider-compatible WebAuthn library and server-side challenge/credential persistence; enroll/login/manage/revoke/recover passkeys; use existing authenticated owner session for consent; integrate scoped OAuth approval. Unit + Worker/Miniflare + actual Pixel browser tests.

**Phase C — Android browser-mediated pairing (moderate complexity):** new pending-pair API and key-bound completion; NewPipe Connect to AI UI; fallback old pairing; cover cancellation, no-network/timeout, stale/replayed handles, unintended cross-device pairing, background/foreground, disabled service, rollback and app upgrades. Test in a **separate QA app** with synthetic data before main-app rollout.

**Phase D — release hardening:** independent security review, replay/CSRF/origin/rate-limit/revocation regression tests, OAuth reconnect on ChatGPT Android, credential manager compatibility, cross-browser/phone tests, live deployed Worker integration. Deploy gateway first compatibly; stage Android after gateway tests; preserve ability to roll back without orphaning existing pairings. Do not expose production key or real Library to destructive QA.

**Testable release acceptance:**

- After one owner-key bootstrap, owner can enroll passkey, sign into gateway and authorize ChatGPT using platform authentication **without typing owner key**.
- Returning owner with a valid admin browser session sees scopes/consent, not a second owner-key form; login alone never grants OAuth scopes.
- Regular ChatGPT command succeeds with no fingerprint prompt or NewPipe app-open requirement, provided device is paired and its AI Control service is online.
- After a real/simulated successful refresh, grant expiry moves out 90 days; after 90 days with **no successful refresh**, grant fails and presents passkey reauthorization. Access tokens remain 10 minutes.
- Revoking a ChatGPT grant/device or removing a passkey has the correct immediate next-request effect (accounting for token validation behavior); no implicit privilege expansion.
- A new Android install pairs with in-app Connect flow and browser passkey approval, **without manually copying a code**. Cancelled/replayed/unauthorized requests fail closed.
- Existing installed/persisted NewPipe library, playlists, tags, backup/restore, Cloud & Sync, paired AI devices and permission ceilings remain unchanged.
- User can regain owner access with documented emergency recovery after losing passkeys without a full gateway reset, and can enroll an additional authenticator.

### Engineering estimate (effort, not calendar promise)

| Work package | Estimated focused engineering effort | Main uncertainty |
| --- | --- | --- |
| A. Sliding OAuth config + regression tests | **0.5–1 day** | Real ChatGPT refresh/reconnect behavior |
| B. Gateway passkeys, recovery, consent/session + tests | **2–4 days** | WebAuthn challenge store, RP hostname and Worker bundling |
| C. No-copy Android pairing + gateway endpoints + tests | **2–4 days** | Safe key-bound enrollment, browser handoff and Pixel lifecycle |
| D. Security review, real-device QA, compatibility rollout | **1–3 days** | Cross-browser/authenticator edge cases, migration/recovery |
| **Full design** | **~5.5–12 focused engineering days** | Could extend if rollout/recovery or browser-host quirks surface |

The 90-day OAuth lifetime is an infrastructure/library-level change, but **passkeys and one-tap pairing are new security-sensitive workflows**. They reuse mature libraries and the existing gateway/Android identity architecture; this is **bounded feature work, not a rewrite**. A staged **A+B** release removes the need to carry the owner key before the Android pairing polish is ready.

### Workstation and deployment constraints

- `@NotCodex` is the device-facing machine for Pixel/browser/emulator/UI checks.
- `@NotCodex-2` may run source analysis, headless commands and builds; **no GUI, browser or emulator** on it.
- No implementation, secret rotation, deployment, new APK installation, or production device changes are authorized by creation of this spec alone.
