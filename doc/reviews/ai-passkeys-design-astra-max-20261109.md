The design fits the existing architecture. The main blockers are precise revocation, transaction-consumption, and pairing-commit contracts. Your additional constraints should be incorporated into the [specification](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/doc/specs/ai-control-passkeys-durable-auth.md:35).

This was a read-only source review. No changes, builds, tests, external services, secrets, agents, or devices were involved.

Must-fix contracts, in priority order:

1. **P0 — Make browser revocation authoritative, including pending operations.**
   The current cookie contains only expiry and nonce; logout merely clears it. A copied cookie remains usable until expiry. See [session validation](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/auth.ts:5) and [logout](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/auth.ts:107).

   Store an opaque session ID with credential ID—or explicit recovery authentication type—owner epoch, absolute expiry, and CSRF state. Check durable validity on every privileged request and again when committing authorization. Logout invalidates the session; credential removal invalidates its sessions; emergency recovery can advance the owner epoch. Reject legacy stateless cookies after migration. Pending assertions, enrollment, consent, and pairing approvals must not survive their authorizing session’s invalidation.

2. **P0 — Specify challenge binding and what qualifies as fresh owner verification.**
   The [enrollment requirements](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/doc/specs/ai-control-passkeys-durable-auth.md:41) need an explicit stored tuple: challenge, purpose, owner, browser/session binding, operation/target, configured RP/origin, and deadline. Use the existing gateway hostname as RP ID and its exact HTTPS origin; accept neither from submitted options.

   At verification commit, atomically recheck challenge availability, session/epoch and credential status, consume the challenge, and persist verified credential metadata. Concurrent successful cryptographic verifications must produce only one authorization effect.

   Adding/removing credentials requires a fresh assertion from an **already enrolled, active credential**, or freshly verified recovery key. Verification of the newly created passkey alone does not prove owner authorization. Define a short freshness window and bind confirmation to the operation and target. Define last-passkey removal explicitly; requiring fresh recovery-key verification is a safe default. Preserve rate limiting and secret-free auditing.

3. **P0 — Bind OAuth continuation to both the browser and the current owner session.**
   The current [scope proof](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/auth.ts:44) binds scopes and handle, but contains no owner-session binding. Preserve the provider’s browser cookie and immutable authorization request; add durable handle consumption and transaction-specific CSRF tied to the authenticated session.

   Require an explicit `decision=allow`: currently, anything other than `deny` proceeds toward approval at [auth.ts:49](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/auth.ts:49). Login success must return to an approval screen. Never reconstruct client, redirect, resource, state, scopes, or PKCE from login-return parameters. Logout/revocation must prevent old consent from being resumed under a new session. Claim authorization atomically before provider issuance; an ambiguous issuance failure must not reopen the transaction. Preserve S256 and unchecked deletion scopes.

4. **P0 — Define pairing’s signed transcript and comparison ceremony.**
   The [proposed exchange](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/doc/specs/ai-control-passkeys-durable-auth.md:61) needs versioned, operation-specific signatures binding the configured origin, device ID/key, locally generated nonce, transaction handle, and immutable request details. Deduplicate identical begin requests; replay must not create fresh lifetimes. Status/finish must prove possession of the original key.

   A key-bound handle prevents substitution, but an attacker can generate their own key and send the owner its approval URL. The browser must require explicit comparison with the code/identity displayed by the **live, locally initiated app request**. A device name or code supplied in the URL is insufficient. Opening a URL must never approve, change the app’s gateway/key, or create a local pairing attempt. Human comparison reduces phishing; device signatures do not establish that the requester is a legitimate NewPipe installation.

5. **P0 — Define one enrollment commit point; existing `pending` is not quarantine.**
   Use separate pending-pair records with fixed expiry ≤5 minutes and explicit pending/approved/committed/cancelled states. At signed finish, atomically check key binding, deadline, approver session/credential/epoch, and consume approval. Cancellation or revocation before that decision wins; after committed enrollment, device revocation is the applicable operation. Identical completion retries may recover the original result, never create another enrollment or undo revocation.

   Do **not** place unapproved requests into `devices[].pending`: [account.ts:45](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/account.ts:45) uses it for registration retries, while [DeviceRoom](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/device.ts:37) and [MCP authorization](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/mcp.ts:50) do not enforce that flag. Register with DeviceRoom only after authorization commits. Preserve crash recovery across the account/DeviceRoom boundary without cross-object waits inside the account gate.

6. **P0 — Stage Android pairing separately from the working configuration.**
   Reusing the current flow unchanged would disable existing configuration before network success at [AiControlRuntime.kt:139](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiControlRuntime.kt:139). Its general [failure path](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiControlRuntime.kt:462) can delete keys if disabling cannot persist.

   Keep pending state separate, excluded from backup, and fenced against cancellation, configuration changes, restore, and late callbacks. Before replacing state, validate completion against the entire local transaction, including expected gateway/owner, device/key, and permission snapshot. Current [completion validation](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiRelayTransport.kt:298) checks device ID and owner UUID syntax only. Preserve existing identity and manual fallback; starting the service remains explicit.

The smallest practical implementation boundaries are:

| Boundary | Responsibility |
|---|---|
| `auth.ts` plus small WebAuthn/session modules | Owner authentication, fresh verification, consent continuation, recovery, browser pages. |
| `account.ts` | Credential/session/challenge/consent/pairing records and atomic decisions. Extend the [existing alarm](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/account.ts:124); prune expired records before capacity checks. |
| `index.ts`, `shared.ts` | Explicit route/method dispatch, bounded schemas, browser-origin checks, and separate abuse limits for begin/status/recovery. |
| Local browser bundle, package manifest/lockfile | Pin exact 14.x versions. Existing [CSP](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/auth.ts:26) blocks scripts: permit the local bundle and same-origin requests, add `form-action 'self'`, retain framing restrictions. |
| Android activity/runtime/transport and pending-state storage | Browser launch, comparison UI, signed exchange, lifecycle fencing. Reuse [Keystore signing](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiDeviceKeys.kt:24); no Room migration is needed. |

Prioritized acceptance tests:

- **P0 — Revocation:** replay saved cookies and pending assertions/consent/pair approvals after logout, credential deletion, epoch change, and expiry. Race revocation against verification and commit; old transactions must not revive after another login.
- **P0 — WebAuthn:** wrong RP/origin/purpose/user handle, missing UV, duplicate credentials, concurrent challenge use, durable restart, stale confirmation, and last-credential recovery. Test synced/zero-counter credentials; counters are not the replay defense.
- **P0 — OAuth:** replay the complete previously valid approval POST, concurrently and sequentially. The current [replay test](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/test/gateway.test.mjs:137) omits `scope_proof`, so rejection does not establish single-use consent. Cover cookie/handle/session swaps, redirect/PKCE/scope tampering, and login without approval.
- **P0 — Pairing:** stolen handle with another key, attacker-created approval link, mismatched comparison code, signed-field substitution, expiry without polling renewal, duplicate finish, cancellation/revocation races, and crashes around DeviceRoom registration.
- **P1 — Android/browser integration:** process death, background/resume, timeout, upgrade, and cancellation preserve working pairing and permissions; no automatic service start. Verify real browser cookies, multiple `Set-Cookie` headers, CSP, locally bundled assets, and recovery without secret leakage.

Two implementation pitfalls remain: [`sameOrigin()`](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/gateway/src/shared.ts:86) accepts absent Origin for native clients, so it is not sufficient browser CSRF protection; and Android’s SPKI/DER signature verifier must remain separate from WebAuthn credential handling.

Keep the one-hour owner session independent of the [90-day successful-refresh timeout](/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109/doc/specs/ai-control-passkeys-durable-auth.md:78). Neither measures human inactivity. Credential removal should invalidate browser authority; effects on existing OAuth grants/devices require a separately defined revocation action.

SimpleWebAuthn is absent from the current manifest, and installed gateway package sources were unavailable. Exact 14.x APIs, Worker compatibility, and provider concurrency behavior remain unverified.
