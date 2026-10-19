# Cloud & Sync v1 — Architecture and failure model

**Status:** Design-review candidate, not implemented. See [product contract](../specs/cloud-sync-v1.md) and [execution gates](cloud-sync-implementation-plan.md).
**Date:** 2026-10-19. Baseline `f76d89a378e71625616bba160a42de24498ccfa1`.

## 1. Components and isolation

- **Android:** isolated `custom/cloud/` model, native settings screen, authenticated transport, durable sync coordinator, catalog adapter and preview model. Reuse Android networking/WorkManager dependencies already present; no generic replication framework.
- **New Worker:** proposed source directory `cloud/`, independently named/configured from `gateway/`. A small authenticated JSON API; its own D1 database and private R2 bucket. The existing `gateway/` remains the accepted AI device relay and is not edited or redeployed by this work.
- **D1:** current canonical catalog, bounded change journal, device authentication/operation receipts, upload reservations and staged large replacements. Normal synchronization reads/writes D1 records, not R2 objects.
- **R2 Standard:** at most three managed recovery-payload slots across all devices/kinds, including incomplete reservations. No routine sync transport and no daily snapshots. Worker housekeeping may clean up but not generate backups.

No additional KV, Durable Object, Queue, Workflow, web dashboard or third-party synchronization service is required by this design. D1 atomic batches and explicit concurrency conditions are the proposed serialization primitive; their suitability must be proven before deployment rather than assuming an in-memory Worker mutex serializes requests.

### Actual baseline integration seams

Inspected files under `app/src/main/java/org/schabi/newpipe/`:

| Existing seam | Reuse or limitation |
| --- | --- |
| `settings/SettingsResourceRegistry.java`, `res/xml/main_settings.xml` | Add one isolated settings entry and registry mapping |
| `custom/tagging/TagRuntime.kt` | Shared canonical/tag writer barrier; its `generation` advances on restore, **not every catalog edit** |
| `custom/library/LibraryRepository.kt`, `UnavailableRepository.kt` | Verified identities and native membership/global-removal rules |
| `custom/tagging/TagRepository.kt`, `TagStore.kt` | Tag semantics and two-store coordination; baseline tag schema is 3 |
| `custom/tagging/TagBackupCoordinator.kt`, `CatalogTransferModel.kt` | Supported native ZIP export/staging/validation; full restore invalidates handles, unpairs AI and restarts |
| `custom/tagging/CatalogFileSwap.kt` | Existing full-file restore recovery; not a ready-made record-sync transaction |
| `custom/aicontrol/AiCatalogExecutor.kt`, `AiCatalogStore.kt`, `AiTagExecutor.kt` | Existing authorized writers that cloud integration must not bypass or deadlock |

Main Room is NP9/RV1 (9001). Prefer preserving that schema. Add cloud metadata to the already fork-owned tag store, with an explicit independent migration and backup-sanitization update, rather than adding an entire upstream replication schema. No migration is performed by these docs.

## 2. Catalog, identity and version model

Represent portable catalog semantics independently of NewPipe's local row IDs. Use a versioned canonical codec shared by golden test vectors in Kotlin and TypeScript.

- `service_identity + exact saved URL` is the media key. Preserve existing numeric service identity only with an explicit protocol/service mapping version. Never deduplicate by title or speculative URL normalization.
- Assign UUIDs to cloud playlists, tags and categories. Persist device-local mappings to native row IDs, scoped by service/library identity and **local database incarnation**. Maps from a restored/replaced database are invalid until re-enrollment.
- Represent a playlist as an ordered sequence of media keys with duplicate occurrences retained. In v1 a sequence is versioned as a whole: positions are positions, not durable per-occurrence IDs. No attempt to merge two independent reorder/delete histories.
- Library and Unavailable use explicit protected roles/designations. Never claim a legacy playlist named Library/Unavailable by name alone. Preserve optional legacy bindings through the already validated native import path.
- Define one canonical semantic fingerprint covering Library, playlist identities/names/order/artwork choices/memberships, protected roles, tag definitions and assignments. Exclude volatile caches, player state, network freshness and local integer IDs. A separate payload digest covers the exact transfer bytes, including display metadata used in the preview.
- Encode 64-bit counters as validated decimal strings in JSON; no JavaScript precision loss. Use canonical ordering, explicit nullable fields and pinned normalization rules. Hash equality is meaningful only within the same codec version.

Cloud head is `(service_generation, library_id, catalog_epoch, revision, active_generation)`. `revision` is a monotonically increasing counter within an epoch. A deliberate whole-cloud replacement creates a new random `catalog_epoch` and advances the published head. `service_generation` lives in Worker deployment configuration **outside D1** and is rotated for database rollback/disaster recovery; old tokens/requests cannot be replayed into a rewound database.

### Proposed logical D1 tables

Names are illustrative; physical schema/migrations are a milestone deliverable.

| Group | Contents |
| --- | --- |
| `catalog_head` | Initialized flag, epoch/revision, active dataset generation, current protocol |
| `streams`, `library_members` | Portable saved stream references/metadata and canonical membership |
| `playlists`, `playlist_items` | Stable playlist ID/role/order, content version, ordered stream references; native duplicates allowed |
| `categories`, `tags`, `assignments` | Portable definitions, normalized uniqueness and Library-only assignments |
| `devices` | Token hash, grant set, revocation/auth generation, monotonic accepted operation sequence |
| `operation_receipts` | Device sequence + operation UUID + request digest + exact committed result; bounded retention |
| `changes` | Atomic revision-tagged deltas, including deletions and whole-playlist replacement references |
| `transfers`, staged rows | Owned immutable chunks/candidate digest, intended base, expiry, quota and validation status |
| `backup_slots` | Three-slot reservation state, immutable R2 key/upload ID, declared/verified size, kind, digest and active-operation protection |
| `commit_assertion` | Temporary CHECK-constrained guard rows used inside an atomic batch, removed before successful completion |

Foreign keys/constraints enforce membership and definition references. Every active read is scoped to the authenticated library and active generation. Staged data never becomes visible merely because upload finished. Cloud tokens cannot submit SQL or choose arbitrary R2 keys.

## 3. Normal synchronization protocol

Android keeps one acknowledged baseline plus at most one frozen in-flight request. A persistent journal records operation UUID, monotonic device sequence, base cloud head, semantic before/after fingerprints, exact request bytes/digest and connection generation **before sending**. Local catalog changes remain usable while a network request is in flight.

On app startup/foreground and sync work, compare the actual canonical catalog with the saved baseline under the writer barrier. In-memory observer events are wakeups, not the sole source of durable truth. This catches an edit committed immediately before process death even if scheduling never ran.

| Local versus baseline | Cloud versus baseline | Action |
| --- | --- | --- |
| Unchanged | Unchanged | No transfer; update verified status |
| Changed | Unchanged | Send the frozen delta against that exact base; continue local editing independently |
| Unchanged | Changed | Download validated changes/current snapshot and apply only if local still matches its captured fingerprint |
| Changed | Changed | Pause for explicit reconciliation, even if the edits appear unrelated |
| Unknown receipt for an already sent operation | Any | Resolve the same operation's outcome first; never create a replacement request with a new identity |

When a push succeeds, advance the baseline to the **acknowledged frozen candidate**, not to whatever is currently on-screen. Edits made during transmission remain a new local delta. A remote apply does not generate an echo upload. If the same known operation committed but its response was lost, return the existing receipt after authentication and request-digest verification.

### Atomic D1 commit and retry

A preflight SELECT followed by unguarded UPDATE is insufficient. For a small operation, one `D1Database.batch()` must cover: current authorization/head assertion, unique operation receipt/sequence admission, invariant-preserving record changes, change-journal entry, revision increment, and final result. A failed precondition must abort the **whole** batch, not just affect zero rows while later statements still write.

Proposed assertion pattern: insert a row whose `CHECK(ok = 1)` value is computed by SQL from the expected head, active token/auth generation and device sequence. A mismatch attempts `ok = 0` and raises a constraint error. Mutation statements are only constructed from a validated bounded typed operation. Remove the guard row at the end. Unique receipt keys make concurrent duplicates conflict transactionally; after a duplicate error, read the existing receipt and require identical request bytes/digest before returning success. A replay with different bytes is rejected. Prove this on D1, not only generic SQLite.

Validate domain changes against a primary, revision-fenced read; the commit's in-batch head assertion ensures that validation is still applicable. Use prepared statements. Budget each batch below the deployed plan's query, parameter and duration limits. Do not assume an arbitrary interactive BEGIN/await/COMMIT transaction exists in the Worker binding API.

Receipt pruning does not permit replay: each device retains its accepted sequence high-water mark. An old sequence whose result has expired returns an explicit resynchronization/unknown-outcome error, never applies again. One active operation per device prevents skipped sequences from being silently treated as success. App data loss requires new device enrollment rather than guessing a sequence.

### Large operations and bounded transfers

Normal edits update changed records only; do not clone or re-upload the entire catalog for each save. Large playlist rewrites may stage a versioned item sequence, validate it, then atomically switch that playlist's content-version pointer under the same catalog head/receipt guard. Whole-library replacements stage a complete new dataset generation and atomically switch the head only after validation and the required outgoing-cloud recovery archive exist.

Use stable-key pagination, bounded chunks and explicit manifests/counts/checksums. Defaults proposed for the proof: at most 256 KiB uncompressed per JSON chunk, at most 32 SQL statements per ordinary commit, at most one whole-catalog candidate per library, and explicit total staging/row/byte quotas. Measure aggregate quota values on the scale fixture before release; all endpoints must reject missing/exceeded limits. The proof must establish a supported maximum catalog, not leave production admission unbounded.

Chunk ownership includes library, initiating device, request UUID and expected epoch. Validate duplicate keys, dense sequence positions, referential integrity, protected designations and tag uniqueness. A sealed candidate is immutable; a changed chunk invalidates the seal. Publication references its verified digest, not client-supplied counts alone. Expired/canceled chunks are never publishable. Quota reservations and cleanup failures stop further staging growth.

The change journal is bounded by both age and bytes; it is not backup history. Initial proposal: up to 7 days and 16 MiB, whichever is reached first. Publish a minimum available cursor. A device behind it must fetch a fresh catalog; it must not interpret missing history as no changes. The strict base/epoch policy prevents old deletion replay or resurrection without permanent tombstones.

### Consistent downloads without R2

Read the head from primary, fetch paginated data tied to that epoch/revision, and check the head inside each page's consistent query/batch and again at completion. If the revision changed, discard/restart the mixed candidate with bounded retries, then report busy instead of retrying forever. An individually consistent page is not a consistent multi-page snapshot by itself.

Stream the validated result to device staging; no permanent cloud copy is needed for a confirmation dialog. Once completely downloaded, the candidate is immutable locally. Confirmation checks current cloud head and exact local fingerprint, but always applies these same bytes. Normal sync must not create R2 objects even for a very large catalog.

## 4. Android durability and concurrency

### State and scheduling

Durable states: `DISCONNECTED`, `DISABLED`, `ENROLLMENT_PREVIEW`, `ENABLED_CLEAN`, `ENABLED_PENDING`, `OFFLINE`, `CONFLICT`, `OUTCOME_UNKNOWN`, `APPLY_RECOVERY_REQUIRED`, and `AUTH_REVOKED`. A transient network failure is not user-disabled or an empty cloud. Persist desired mode separately from transient status.

Use one uniquely named sync-work chain per bound library/device, with connected-network constraints and backoff. Coalesce local events. Foreground entry and explicit Sync now request prompt checks; optional periodic background checks use WorkManager and do not promise instant delivery. No perpetual wake lock, playback foreground service or websocket is introduced. Existing AI foreground-service behavior is independent.

A persisted connection generation fences callbacks and worker requests. Disable or endpoint changes invalidate old work before cancellation. WorkManager cancellation alone is not a network-commit barrier. An already submitted request can finish remotely; retain its receipt identity while refusing any stale callback's local apply.

### Record-level apply over two databases

There is no cross-file SQLite atomic transaction. Hold the existing canonical/tag writer barrier only for local snapshot/admission/application, never while waiting on HTTPS or user confirmation. Preserve the established lock order: authorization admission/drain where applicable, TagRuntime barrier, Library monitor, then native transactions. Audit all native and AI writers before claiming coverage.

Proposed recoverable apply mechanism:

1. Stage the complete target and a compact durable apply journal containing database incarnation, native ID mapping plan, exact old/target semantic projections, operation identity, and checksum. fsync required files and commit journal state before changing the native catalog.
2. With the writer barrier held, recheck the local fingerprint/connection generation and complete prior domain recovery. Resolve/validate native IDs; create a durable mapping plan before any auto-increment allocation can become ambiguous after a crash. Never infer a new playlist's identity solely from its display name.
3. Apply the main catalog changes in one native Room transaction for the admitted bounded operation. For larger operations, use a separately proven bounded/recoverable path; never call a partially committed loop atomic. Preserve history, subscriptions and unrelated native metadata.
4. Apply tag changes, cloud ID maps, baseline pointer and completion state in a tag-store transaction. The baseline payload is already durably staged before its pointer changes.
5. Verify the complete target projection, publish UI invalidation only after coherent completion, then remove superseded temporary state.

Recovery runs **before catalog reads/writes, AI catalog access or sync resumes**. If the main projection equals the recorded old state, replay the frozen operation idempotently. If it equals the target, finish the tag/baseline phase. If it matches neither, fail closed and retain recovery evidence; do not replay a stale global delete into an unrelated local database. A process-local generation/lock alone is not recovery evidence. Map allocation and interruption at every boundary are explicit prototype gates, not claimed solved merely by writing a journal.

If this cannot be proved without an additive fork-owned Room receipt/migration, document the narrow change and adjudicate it before implementation rather than secretly weakening the recovery guarantee. Do not repurpose AI command receipts as cloud authorization or operation identity.

### Native import/export interaction

Cloud credentials reside outside default preferences and outside all exported files, using Android Keystore-protected encryption for local token bytes. Exclude cloud endpoints, enablement, device identity, sequences, outbox, executable intents and local mappings from native backup restore activation. Restoring portable catalog content must not restore transport authority.

Extend sanitization/version checks when adding tag-store cloud metadata. Existing `TagBackupCoordinator.prepare/install` is reused for **full ZIP restore**, not ordinary sync. Before any native import, fence cloud work and quarantine unknown remote outcomes; keep cloud credentials separately, restore with sync disabled and a new local database incarnation. Preserve the existing AI unpair/restart behavior for full restores; do not impose it on record sync.

Local safety recovery stores one most-recent verified pre-replacement snapshot and bounded active staging, not an endless on-phone archive series. Cleanup never discards evidence needed to complete or roll back an active operation. Startup sweeps only this feature's known temp prefixes after validating journal ownership.

## 5. Preview binding and side-effect boundary

The preview model owns a frozen candidate, complete diff index, full counts, native UI paging, source scope and approval token. Rotation survives via a retained model; process death either restores the persisted candidate safely or requires a new preview. No remote publish or local replacement occurs from expanding/canceling a preview.

Bind approval to `(action, connection generation, local incarnation, local semantic fingerprint, cloud head, candidate digest, scope, expiry)`. Search/pagination must reference that same diff. Ordinary playlist deletions, global Library removals, assignments and duplicate occurrences have distinct accounting.

Cloud replacement is a distributed operation, not a simultaneous swap on all devices. Recheck its cloud head by CAS at publication. If local changes after the last capture, publish only the approved frozen candidate; never silently widen it to include newly made edits. Keep later local edits pending and reconcile against the actual published head. A changed source detected before submission requires a fresh preview. Other devices with local work pause on the new epoch rather than silently losing it.

A one-time local restore never publishes to D1. A historical cloud-catalog archive can be imported locally without changing the cloud; subsequently making it authoritative is another separately previewed Replace cloud action.

## 6. Minimal R2 recovery implementation

### Archive formats

`device-zip`: existing supported ZIP payload plus a versioned cloud envelope/manifest with type, checksum, compressed/uncompressed sizes, schema information, snapshot timestamp and optional known cloud head. It is a snapshot of that device, not necessarily the latest cloud. Restore strips transport/runtime authority.

`cloud-catalog`: versioned portable catalog archive for the exact outgoing D1 epoch/revision, with integrity manifest and full reference coverage. It contains no D1 device-token table or native settings/history. Native Android restore uses the catalog adapter rather than pretending this file is a stock NewPipe ZIP.

The Worker can validate format/manifest/size and archive transfer integrity; Android's existing native DB integrity/schema checks remain required to call a device ZIP restorable. Do not claim a remotely accepted arbitrary upload passed native restore validation. Record producer validation plus checksum and exercise real round-trip restoration in acceptance.

### Three-slot admission and cleanup

Serialize reservation changes transactionally in D1. Each immutable object key belongs to one of three occupied/reserved slots. Completed payloads, multipart uploads and ambiguous outcomes all count; concurrent devices share the same cap. No public bucket, user-chosen path, bulk public listing or long-lived upload capability.

At three completed slots, verify two newer backups remain, select the oldest unprotected one, mark deletion in progress, delete through R2 and confirm absence, then free that slot. Only then admit the new upload. Do not free a slot merely because a timeout/lease expires: an outstanding upload/complete may still finish. Reconcile/abort it definitively or quarantine its slot and block further growth. Stale workers must not create new keys or repeat a completed rotation.

Upload under bounded request sizes (proposed 8 MiB multipart parts, at most 32 for the 256 MiB archive cap). Validate each part's number, size and ownership; restrict concurrent part requests and retries to the reserved key/upload ID. Completion is idempotent. Count declared reservations until exact committed bytes are known. If verification fails, retain good archives and clean/quarantine the failed slot.

Include enough manifest metadata in the immutable object to rediscover recoverable objects after losing/rewinding the D1 backup index. D1/R2 have no shared transaction: an upload committed before its index write is an orphan to reconcile, not a reason to upload another key. Index-first records do not prove an object exists. Every listed recovery point must refer to verified extant bytes.

Use lifecycle rules only as a secondary incomplete-multipart cleanup backstop (proposed one day). Do **not** age-delete all committed archives: with infrequent backups that could erase the last copy. Worker admission/count/size accounting provides the cap; lifecycle cleanup is delayed and cannot implement a reliable keep-latest-three policy [C4]. Scheduled housekeeping checks at most this small namespace and does not generate archives. If cleanup stays broken, new backups stop and the status says so.

### Before whole-cloud replacement

Export and verify the **outgoing cloud catalog**, not the incoming local branch. Bind the R2 recovery record to its exact head/digest. If cloud changes during archival, do not overwrite: refresh/retry against the new head. Final D1 publication CAS checks both candidate seal and the matching verified recovery point. A failed CAS may leave one valid old-cloud recovery archive, within the same three slots, but must not delete the latest cloud state.

## 7. Authentication, privacy and account recovery

Operator provisioning creates a per-device high-entropy bearer token with narrowly scoped library grants (read/write, replace, backup) and a server-side hash. Device tokens are not Cloudflare account API keys. Token generation/admin bootstrap stays off ordinary mobile APIs; ordinary devices cannot mint new devices/grants. Owner revocation and independent re-pairing remain possible when a phone is lost. Use standard cryptographic primitives, bounded constant-time comparison where relevant, HTTPS only, no credentials in URLs, logs or exports.

Verify authenticated service/library identity during connection. Do not forward Authorization across redirects or to a changed host. Protect private backups with authenticated Worker access and no shared public cache. Whitelist request shapes, reject unsupported versions, enforce rate/byte limits, and treat all titles/URLs as data. The Worker must not fetch arbitrary media URLs; sync does not introduce a server-side URL proxy.

The service is cloud-readable, not end-to-end encrypted. The owner controls the Worker and account; provider transport/storage protections are not a zero-knowledge claim. Local exports provide an independent recovery option, since R2 and D1 in one account can both become inaccessible.

For D1 Time Travel recovery, enter maintenance outside D1, record current recovery information, restore, rotate `service_generation`/authentication generation in Worker configuration, invalidate old device tokens and cursors, validate catalog invariants and reconcile the R2 index before reopening. Re-enroll devices with a preview. Never roll back the token table and thereby silently revive revoked devices. Never test Time Travel against the live owner's database.

## 8. Provider facts and verification sources

Checked 2026-10-19. These are platform facts, not measurements of this library or its account plan. Recheck at deployment.

- [C1] D1 limits: Free database maximum **500 MB**, account aggregate 5 GB; Paid database maximum 10 GB. Per row/string/BLOB maximum 2,000,000 bytes; 100 bound parameters per statement; Free/Paid query budgets differ; query/batch duration is bounded. This is why arbitrary ZIP blobs and unbounded batches are excluded. https://developers.cloudflare.com/d1/platform/limits/
- [C2] D1 batches are transactional and roll back on statement failure. Without the Sessions API, Worker queries use primary. Sessions provide sequential consistency, not an immutable multi-request snapshot. https://developers.cloudflare.com/d1/worker-api/d1-database/
- [C3] Time Travel is always on for production-backend D1, with 7-day Free/30-day Paid windows. Restore overwrites the database in place. It is additional operator recovery, not an archive-retention policy. https://developers.cloudflare.com/d1/reference/time-travel/
- [C4] R2 lifecycle deletion is not immediate; documents say typically within 24 hours of expiry. Incomplete multipart uploads have a default seven-day lifecycle. https://developers.cloudflare.com/r2/buckets/object-lifecycles/
- [C5] R2 direct reads/writes/deletes/listing are strongly consistent; conditional writes support create-once semantics. This does not imply a transaction with D1. https://developers.cloudflare.com/r2/reference/consistency/ and https://developers.cloudflare.com/r2/api/workers/workers-api-reference/
- [C6] R2 Standard includes 10 GB-month monthly storage allowance plus operation allowances; that is not a per-project guarantee or spending cap. Do not infer the account's unused allowance. https://developers.cloudflare.com/r2/pricing/
- [A1] WorkManager periodic work has a minimum 15-minute interval and timing depends on OS constraints/optimization. Event-driven work improves responsiveness but is not an instant-delivery guarantee. https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- [A2] Android Keystore protects cryptographic keys; encrypt stored token bytes with a Keystore-backed key and exclude them from backup. https://developer.android.com/privacy-and-security/keystore
