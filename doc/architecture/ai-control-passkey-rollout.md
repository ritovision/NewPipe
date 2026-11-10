# AI Control passkeys — state-aware rollout and rollback

**Date:** 2026-11-09. **Status:** required operational runbook; not evidence of production rollout.
**Applies to:** the private AI Control Worker and its NewPipe integration, not Cloud & Sync.
**Review:** [Phase D review/adjudication](../reviews/ai-passkeys-phase-d-adjudication-20261109.md).

## 1. Release entry conditions

Complete the exact-source gateway/native/browser checks, full dependency audit and the staged TLS gateway-to-phone pairing/relay checks. Physical credential-manager enrollment/sign-in and real ChatGPT reconnect/refresh require separate explicit acceptance; a virtual authenticator and synthetic OAuth client are not substitutes. Resolve an execution-tool authorization block through the user, not another command path.

Immediately before production changes, inspect the active Worker version and bindings again. The read-only inventory on November 9 still showed version `75645ddd-3e81-4c9e-b4d0-a04f517a9b4c` at 100%. This is a historical reference, **not authorization to restore that version automatically**.

Record source commit, bundle hash, deployed version, binding identities, migration history and the state-aware fallback version. A Git commit alone is not an uploaded Worker version. Do not claim production readiness while these records or live acceptance are absent.

## 2. Identity and data invariants

Preserve all of the following through forward deployment and fallback:

- Existing production Worker name and exact HTTPS `PUBLIC_ORIGIN`. The WebAuthn RP ID is that exact hostname; staging credentials cannot authenticate production.
- Existing `OWNER_ID`, `OWNER_KEY_HASH` and `SESSION_SECRET`. Do not rerun the owner provisioning script or rotate secrets as an incidental upgrade step.
- Existing `OAUTH_KV` namespace, ACCOUNT and DEVICES Durable Object namespaces, class names and migration lineage. Matching binding labels without matching actual namespaces is insufficient.
- Credential records, owner epoch, device/client revocations, canonical device IDs, local Android permission ceilings, OAuth grants and scopes. No bulk grant rewrite or privilege expansion.
- Library, playlists, tags, backup/restore rules and the separately deployed Cloud & Sync service/account/storage.

The candidate intentionally rejects legacy signed browser-admin cookies. Existing paired devices and OAuth refresh do not require an owner passkey/browser session, so browser reauthentication must not force phone re-pairing. Current valid seven-day grants adopt the new policy on an accepted refresh; expired grants require reauthorization, not a new device identity.

## 3. Forward deployment order

1. Finish isolated-stage acceptance and confirm recovery credentials exist in their protected local location without printing/transferring them into chat. Register no production virtual test authenticator.
2. Choose a fully compatible fallback artifact and verify it against the current persistent state schema. The default fallback must retain the new owner-auth and browser-pairing handlers; do not percentage-split the legacy and new authentication implementations.
3. Replace only the existing AI gateway with the accepted source while keeping its bindings/secrets. Recheck health, OAuth discovery/refresh, existing paired-device reachability and revocation. Do not reset state on an unexpected result.
4. Enroll the actual owner's passkey on the stable production hostname with the owner's physical verification. Keep explicit OAuth consent and destructive-scope defaults. Confirm recovery without exposing the key.
5. Upgrade the canonical APK only after verifying the correct installed package, signer and data-preserving upgrade path. A gateway passkey rollout for already-paired devices does not by itself require this APK upgrade. Never install a QA package over a canonical package.
6. Record exact live results and the usable fallback version. Distinguish service availability from authorization persistence and the host client's own token retention.

## 4. Supported fallback

The normal fallback must understand `owner-auth:v1`, `browser-pairing:v1`, its admission generation and recovery of committed enrollment. Application source at `a80426b90` includes those formats; prefer the accepted patched-toolchain build or a later reviewed compatible build. This statement does not establish a deployed fallback exists.

On the first production passkey rollout, there may be no previously deployed state-aware version. In that case prepare and validate a compatible fallback artifact first, or explicitly choose a documented fix-forward/maintenance strategy. Do not label the legacy version a safe fallback merely because deployment metadata contains its ID.

Use a single compatible authentication version during a transition. Keep pending and committed pairing records: a lost completion response can leave a valid registration needing recovery. Reconcile outcomes through supported account/device handlers; never recreate an operation under a new identity to hide an unknown outcome.

## 5. Exceptional downgrade to legacy code

A downgrade to the owner-key implementation is **not approved by this runbook**. It requires an explicit operational decision and a separate plan covering all of these risks:

| Risk | Required handling before any such downgrade |
| --- | --- |
| Legacy cookie resurrection | Old code validates an HMAC and expiry, not the new owner epoch. New-code logout/reset therefore does not invalidate an old signed cookie. Prevent legacy-cookie authentication until every potentially valid old cookie is expired or invalidate it through a deliberately reviewed mechanism. Secret rotation, when explicitly chosen, has its own impact and is never an automatic workaround. |
| New-session resurrection on roll-forward | Legacy logout cannot revoke retained opaque owner sessions. Invalidate them through the state-aware handler or wait out their original expiry before treating legacy logout as an effective global boundary. |
| Partially completed device enrollment | ACCOUNT can durably commit before DEVICES registration or response delivery. Legacy code has no `/device/pairing/*` endpoint; a 404 can stop native retries. Drain, reconcile, or explicitly revoke affected enrollments while the state-aware service is available. An unresolved committed enrollment blocks ordinary downgrade. |
| Persisted OAuth expiry | Restoring old code does not shorten already-extended grants to seven days; it stops future sliding renewal under that configuration. Newly exchanged grants use the old policy. Audit and explain the actual behavior rather than editing opaque KV records. |
| Data loss disguised as recovery | Never clear credential records, revocation tombstones, grants, pairing journals or namespaces. Cloudflare version rollback is not a storage restore. |

If the state-aware service is unavailable, prefer a compatible fix-forward or controlled maintenance while investigating. Do not erase state or silently introduce the legacy auth downgrade to regain availability.

## 6. Staging and cleanup boundaries

Staging uses a distinct Worker, owner ID, OAuth KV, Durable Object namespaces, rate-limit namespaces and synthetic credentials. No production grants, Library data or owner key are copied into it. Phone tests target an explicitly isolated package and non-default scrcpy display. Only owned QA apps/displays/recordings are removed during cleanup; other ADB transports and canonical apps are left alone.

A hosted staging Worker is a retained test resource, not a running background task. Record its name, status and intended removal; keep local QA secrets mode-600 outside Git. Do not leave temporary browser processes, instrumentation or virtual displays active at a user checkpoint.

## References

- [Cloudflare version rollbacks](https://developers.cloudflare.com/workers/versions-and-deployments/rollbacks/): code/config rollback does not restore connected storage.
- [Cloudflare compatibility dates](https://developers.cloudflare.com/workers/configuration/compatibility-dates/): compatibility behavior is distinct from the runtime/toolchain release.
- [Browser-pairing protocol](ai-control-browser-pairing.md).
- [Current execution checkpoint](../validation/ai-passkeys-progress-20261109.md).
