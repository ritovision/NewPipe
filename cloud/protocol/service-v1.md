# Catalog service v1 — implementation and deployment gates

This is the actual typed Worker/D1 catalog service, separate from the existing AI relay in `gateway/`. It does not implement R2 backups, force replacement, a mobile settings screen or a general-purpose merge engine.

## Request surface

All routes are POST JSON on the configured HTTPS origin, require a device-scoped bearer token except that disabled service rejects before authentication, and return private/no-store JSON. No endpoint accepts SQL, a caller-supplied database/account identity, auth generation, provider API key, or other device authority. `deviceId.secret` maps through a SHA-256 secret hash to the configured library and service generation. Read-only tokens cannot stage, publish or retire writes.

- `/v1/connect` and `/v1/head`: authenticated status, scope, capability and transfer bounds. Uninitialized is explicit and distinct from a valid empty catalog.
- `/v1/pages`: one exact-head-fenced page segment, cursor and manifest; a changed head stops download rather than mixing revisions.
- `/v1/uploads/start`, `chunk`, `seal`, `publish`: reserve a bounded operation, accept idempotent immutable chunks, validate the complete actual target content and fingerprint, then atomically publish pages + catalog metadata + receipt/head revision.
- `/v1/uploads/status`, `retire`: resolve a committed operation or fence a discarded sequence before cleaning its payload. A timeout is never treated as proof of no commit.

Packed ordered records preserve logical catalog content and change only affected page rows, instead of writing one D1 row for each playlist occurrence. Page IDs, content hashes and manifest bind exact received bytes. The target is reconstructed and validated server-side before a seal can be published. Stage budget includes incomplete payloads; one active upload per device and four per library are admitted, and expiry never authorizes replay. Completed/retried publication returns the original receipt rather than creating another revision.

## Tests and explicit limitations

Local workerd tests cover independent packing vectors, duplicate/order semantics, device authentication, immutable retries, concurrent uploads, full transaction rollback, revocation during publication, restart/recovery, expiry/retirement, paging races, and a 20,000-stream / 100,000-membership / 20,000-assignment synthetic catalog. The local scale test does not certify deployed CPU, request/row allowances, production latency or account plan capacity.

The initial deployment configuration is **API disabled**, **64 KiB / 8 pages**, with a 120-per-60-second edge rate limit. Configured large-catalog maxima may not be raised incidentally. An explicit small synthetic live check may enable the API temporarily; future real-device enrollment/production activation is separate. This deployment is not full-feature acceptance. Expired stages that belong to an abandoned/revoked device still need an operator/housekeeping cleanup path before production; short-window rate limiting is not a durable daily-account budget. Receipts remain bounded by a future retention/maintenance policy before general rollout. No automatic upgrade to Workers Paid is authorized.

D1 calls deliberately use the ordinary database binding without unconstrained Sessions, which targets the primary. A future read-replica optimization must preserve sequential consistency and last-observed revision semantics. The configured D1 resource also has read replication disabled. The Worker doesn't use R2 for sync transport.

## CLI and provisioning

`cloud/scripts/wrangler.sh` uses the isolated NewPipe OAuth profile and pins the Ritorhymes account. It does not overwrite the default Matthew profile. It disables browser opening and clears conflicting API-token environment overrides. `cloud/wrangler.jsonc` contains resource IDs and feature/size limits only; no device secrets or OAuth tokens.

Apply the checked-in D1 migration only to the dedicated `newpipe-cloud-sync-v1` database after verifying its ID. Bootstrap one logical library head and issue/revoke device credentials using administrative CLI access; never expose a token-creation endpoint without a separate enrollment design. CLI credentials remain on the operator machine; Android receives only its Worker-issued/scoped device token.

## Provider references checked for this implementation

Cloudflare documents D1 batch rollback on statement failure and default-primary behavior without the Sessions API. Its edge rate limiter is approximate/per-location, not durable daily billing control; a distinct key prefix is used for this service.

https://developers.cloudflare.com/d1/worker-api/d1-database/
https://developers.cloudflare.com/d1/best-practices/read-replication/
https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/


Outcome contract: a timeout, expired upload response, authorization loss or error after a D1 publication attempt does not prove non-commit. `OUTCOME_UNKNOWN` and post-batch resolution failures return HTTP503 with `outcome: "unknown"`. Only a matching committed/retired receipt is definitive. Keep the same sequence/digest and resolve-or-retire rather than inventing a replacement operation identity.
