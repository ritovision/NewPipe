# AI Control browser-mediated device enrollment

**Source candidate:** `feature/ai-passkeys-20261109`, introduced by `f7c33bcdc` (2026-11-09). This document describes implemented source, not a deployed feature. See the execution checkpoint and Phase C acceptance record for actual test/release status.

## Product boundary

NewPipe's **Connect to AI** button replaces routine pairing-code copying. It does not replace the Android device identity, local permission ceiling, foreground-service opt-in or ChatGPT OAuth consent. Existing paired installations are left alone. The legacy one-time pairing code remains available under **Advanced: pair with a code**.

The app displays a short comparison code; the browser displays the same code and requires explicit confirmation that setup was started on that device. This is a comparison, not a credential the owner must type, remember or carry. Passkey login authenticates the owner; it does not approve the device or the AI client automatically.

No enrollment operation starts the service. After completion, the local state is paired with `enabled=false`; the owner chooses **Start AI Control**. A separate ChatGPT authorization must still permit the requested operations. There are no added per-command fingerprint or approval prompts.

## Protocol and immutable identity

1. Persist separate pending Android state: original local configuration, proposed gateway/name/permissions, existing device ID and Keystore public key, timestamp, staging nonce and cancellation/finish flags. Do not overwrite the active state.
2. Fetch `GET /device/pairing/nonce` over the exact configured HTTPS origin. Persist the returned public nonce before sending any signed request. This is an admission-generation marker plus randomness, not owner authentication or a bearer credential.
3. Sign `POST /device/pairing/begin` with the device's non-exportable P-256 key. The immutable transcript binds protocol version, operation, origin, device ID, SPKI public key, nonce, timestamp, name and sorted local permission names. Its SHA-256 digest is the request ID.
4. Open `/pair/device?request=<request-id>` in the external browser. The URL alone cannot enroll a device. The browser authenticates the owner by passkey/recovery, displays the exact request, and requires code comparison plus an explicit approve/deny decision.
5. When the AI Control screen is visible again, the app issues key-signed status/finish calls. Every operation binds the same full transcript and request ID; signatures are operation-specific.
6. Validate the final response against the staged public-key digest, request, device, nonce, owner, name, permissions and deadline. Commit to the active state only under the original local policy/admission guards. The service remains off.

Initial begin timestamps have a bounded clock-skew allowance; approved transactions expire after five minutes. Exact stored retries never renew their deadline. A durable committed registration has a bounded recovery receipt so the same signed finish can recover from a lost response. Failed, denied, expired or cancelled requests cannot silently become new enrollments.

## Bounded anonymous admission and replay

The account stores at most 64 enrollment records, including at most 32 anonymous pending/cancelled records. Owner-viewed, approved and committed work is protected from anonymous eviction. Anonymous cancellation is not a route to reserve all owner slots.

A 16-character server admission generation is included in the public 43-character nonce. Unknown signed begin requests must use the current generation. If an anonymous record is evicted, the server atomically rotates the generation with eviction/insertion. Replaying an evicted pending or cancelled request therefore cannot recreate its request ID with a new deadline or revive a cancelled request. Retained records continue to work with their original generation. Fetching a nonce neither allocates an enrollment record nor rotates the generation.

Generation churn can require a brand-new, unviewed setup attempt to restart under sustained anonymous load. It cannot enroll a device or invalidate already retained owner-approved work. The existing per-IP route limiter and bounded timestamp/input validation remain in force.

## Authorization and cross-object commit

An owner approval is bound to the browser session, session CSRF token and a transaction-specific form proof. The server checks exact Origin and explicit decision/comparison. Logging out or revoking the owner session before the initial durable enrollment commit invalidates that approval, including a stale form reopened after re-login.

The account serializes state changes. Initial authorization linearizes when an account-storage transaction marks the pairing committed and reserves the device identity. DeviceRoom registration then happens outside the account lock, avoiding a cross-object call while holding the account gate. Registration is idempotent for the same key, and existing revocation tombstones win. Final account completion rechecks device revocation.

If registration or its response fails after the durable authorization commit, an identical signed finish recovers that already-authorized operation; it does not create fresh authorization after logout. Tests force registration failure both before and after its write, and revocation before/after DeviceRoom registration. A cancellation after commit reports the truthful state rather than claiming remote enrollment was undone. Device revocation remains a distinct owner operation.

## Android lifetime and failure rules

Pending enrollment is an AtomicFile in `noBackupFilesDir`, outside the active configuration and exported backups. It binds the original local state and catalog epoch. A configuration/permission change, restore, Stop or cancellation fences old requests. The admission record captures request ID, generation and policy version at transaction creation/recovery, never by borrowing the latest generation when an old queued callback finally runs.

Cancellation and local completion have a consistent locking order. A local cancellation marker is persisted before deleting staging, so interrupted deletion cannot resume a cancelled attempt. Configuration invalidation falls back to invalidating only the pending device key if it cannot persist the staging tombstone. A completed active state is saved before staging cleanup; leftover staging cannot overwrite it on restart.

The screen polls only while started, with backoff on network/temporary gateway failures; no periodic background pairing job is installed. HTTP 429, 408 and 5xx are classified before reading an error body, because proxies may return HTML or nothing. Successful responses retain strict schema/identity validation. Temporary transport failures do not turn a recoverable committed finish into a new request.

## QA and release separation

The branch-isolated debug application ID is `org.schabi.newpipe.debug.featureaipasskeys20261109`; instrumentation targets its `.test` package and has an explicit package guard. `qaApplicationIdSuffix` is not a supported Gradle property in this repository and must not be relied upon. Read the APK output metadata before any installation.

Native instrumentation uses synthetic no-backup directories and an internal injected pairing client to deterministically exercise real Android Keystore, AtomicFile, queued cancellation and late-response behavior. These tests do not establish a real network/browser/Android end-to-end pairing or physical biometric acceptance.

The Chromium runner uses the actual browser bundle and WebAuthn APIs, a disposable virtual authenticator, a synthetic signed device and an ephemeral loopback HTTPS gateway. It must run only on the primary workstation. It proves browser return/approval/denial/session behavior, not physical fingerprint/PIN or production ChatGPT acceptance.

No production gateway, canonical APK, Library, Cloud & Sync data, user passkey or owner secret is changed by these QA procedures. Live TLS gateway-to-phone integration, actual credential-manager enrollment and ChatGPT authorization/refresh remain separately recorded release gates.
