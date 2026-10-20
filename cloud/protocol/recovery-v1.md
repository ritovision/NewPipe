# Recovery protocol v1

This feature is explicitly invoked. Catalog synchronization, periodic WorkManager runs and local edits perform no R2 operations. The recovery API is separately gated and defaults off. Forced cloud replacement is not enabled by this protocol alone.

## Capacity and identity

One library has three numbered slots, shared by native ZIPs and cloud catalogs. Each slot contains an immutable random archive ID/key and reserves its declared payload bytes. The hard cap is 256 MiB per slot, 768 MiB total. The operator may set a smaller per-archive limit. A reserved, incomplete, deleting, or uncertain slot remains occupied. One nonterminal upload is allowed per library.

Each device has a separate monotonic recovery sequence. A preview issues a ten-minute approval bound to the exact manifest, rotation revision, outgoing cloud head when applicable, and named eviction. Reserve is idempotent for its sequence/approval pair; terminal replays do not create another archive. Old sequences are never reused. The Android client durably writes its exact payload and approval/sequence before reserving.

At capacity, reserve marks the oldest archive for deletion only after checking that two newer READY archives remain and all objects are extant, unchanged and unprotected. Advance rechecks the survivors, deletes the immutable old key and confirms its absence before creating the replacement reservation. A failed deletion never authorizes a fourth payload.

## Payload and integrity format

All objects use multipart upload, including a single final part for small archives. Nonfinal parts are exactly 8 MiB; at most 32 parts. Part hashes are lowercase SHA-256 hex of exact bytes. Parts are sequential. Retry may write only the same immutable bytes to the same upload ID/part number.

The JSON manifest has exactly:

- version: 1
- kind: device-zip or cloud-catalog
- bytes: total payload bytes
- source: null for a device ZIP; otherwise head, pages, bytes and trusted page manifest
- native: null for a cloud catalog; otherwise database and tags schema versions from the original SQLite headers and uncompressedBytes from the validated native ZIP
- producerValidation: native-restore-v1 or catalog-pages-v1, respectively
- parts: ordered objects containing bytes and sha256

The manifest digest is SHA-256 of UTF-8 JSON.stringify of:

`["rv-recovery/1", kind, producerValidation, bytes, sourceTupleOrNull, nativeTupleOrNull, [[partBytes, partSha256], ...]]`

A head tuple is serviceGeneration, library, epoch, revision, headCounter, fingerprint, in that order. A source tuple is [headTuple, pages, bytes, manifest]. A native tuple is [database, tags, uncompressedBytes]. Counters in a head are decimal strings. This digest binds the ordered chunk hashes and archive interpretation; it is **not** advertised as a whole-archive SHA-256 or multipart ETag.

A device-zip payload is the normal complete native export. Android exports and successfully prepares it with TagBackupCoordinator before upload. The Worker verifies transfer hashes and ZIP container boundaries, not SQLite semantics. Producer validation is explicitly identified as a producer claim. Android downloads, hashes and prepares the actual bytes again before offering restore; a supported schema migration of temporary copies does not alter the original archive schema metadata.

A cloud-catalog payload is canonical UTF-8 NDJSON:

1. JSON.stringify(["rv-cloud-catalog/1", headTuple, pages, bytes, manifest]) followed by LF.
2. One JSON.stringify([pageId, canonicalPageBody]) followed by LF for each page, ascending by page ID.

No extra lines, noncanonical JSON, duplicate pages or trailing partial line are allowed. The source root is captured from authenticated D1 catalog status at preview/reservation. The Worker checks each page's schema and complete ordered root against that trusted value. A client's own hashes cannot substitute incoming/local pages for the outgoing catalog. Verification advances one 8 MiB part per request and persists only page leaf hashes plus a bounded incomplete line. Android also validates complete catalog semantics and fingerprint before applying.

Object metadata records format, type, length, digest algorithm/value, source/native metadata, producer validation, creation time, device, original service generation, library, slot and archive ID. Reconciliation can recompute the fixed-size part hashes from immutable bytes after index loss. It must not infer readiness merely from a D1 row or R2 object name.

## Interrupted operations

Creation is claimed in D1 before the one permitted createMultipartUpload call. If its result or identity write is uncertain, the slot stays CREATING/QUARANTINED. A retry never repeats non-idempotent multipart creation. Missing upload identity requires operator reconciliation.

Known multipart part retries are protected by a short lease. Lease expiry permits another identical part operation, **never** slot reclamation. Completion first checks for the matching committed object; a lost completion/index response resumes verification of that object. Verification records immutable object ETag/version and hashes exact ranges. A stale verifier cannot quarantine a successor's claim.

Abort first fences further admission. It must have a definitely unused reservation, definite known-upload abort, or matching completed object proving the upload was consumed; then it deletes any completed bytes and confirms absence. Ambiguous cleanup retains/quarantines the slot. A timeout alone is not proof of cleanup. Abandon resolves an approval whose reserve reply was lost: if not reserved, it persists a terminal sequence that blocks delayed reserve; otherwise it aborts that exact operation. DONE history is preserved.

## Restore

Pin protects a READY archive for ten minutes. Repeated pin by the same device does not extend the original expiry. Download serves authenticated, immutable, bounded ranges with expected part hash headers; Android independently hashes them. Read-only credentials may list, pin, download and release but never upload or abort.

After download/validation, the client releases the remote pin and retains the private local candidate for an expiring confirmation. Release failure is visible and blocks that preview. Local confirmation rechecks connection generation and exact local catalog. It first saves the existing verified local safety backup.

A cloud-catalog restore is a local historical apply with automatic sync disabled. It preserves the highest observed live cloud head, makes no D1 publication and requires a new reconciliation preview before enabling sync. Native ZIP restore uses the existing native install path, explicit optional settings restore, AI unpairing and process restart. The final native writer barrier repeats the local-preview check.

## Operator reconciliation

Never insert a missing control row automatically. Initial provisioning is an operator action after verifying the correct account/library and an empty namespace, including incomplete multipart uploads. Bind the control row to the external Worker service generation.

After D1 rollback/index loss, disable gates outside D1, rotate external service generation/authentication authority, inspect completed objects and incomplete multipart uploads, fence stale workers/credentials, and reconcile every physical payload before re-enabling. Workers R2 bindings cannot enumerate unknown multipart IDs; use an operator facility that can. Unknown creation cannot be safely resolved by clearing a D1 row.

Keep permanent archives out of age-based lifecycle deletion. Incomplete-upload lifecycle cleanup is only a delayed backstop, not quota authority. Do not recreate payloads or discard the last recovery point to repair metadata.

## Routes and gates

Authenticated POST JSON: /v1/recovery/status, preview, reserve, advance, complete, abandon, abort, pin, release, download. Part upload uses /v1/recovery/part with application/octet-stream, Content-Length and X-Recovery-Sequence, X-Recovery-Archive, X-Recovery-Part headers. No client-supplied R2 key, SQL, upload ID or account credential is accepted.

ENABLE_CATALOG_API and ENABLE_RECOVERY_API must both be true, the private RECOVERY R2 binding must exist, and MAX_RECOVERY_BYTES must be valid. No browser CORS grant or public bucket is required. Device tokens stay in encrypted Android private storage and outside exported archives.

Tests use real local D1/R2 with failures before/after effects, race gates, two identities, fixed multipart bounds and independent TypeScript/Kotlin vectors. Live activation and whole-cloud replacement require their additional acceptance gates.

Live R2 completion responses need not include the custom metadata exposed by the local emulator. Completion always follows with an authoritative `head()` read, even after a successful or lost completion response, before binding/verifying the immutable object. A missing/unknown result retains its slot.
