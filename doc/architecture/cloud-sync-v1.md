# Cloud & Sync v1 — Architecture and failure model

**Status:** Amended after independent review; required architecture, not a completion claim. See [adjudication](../reviews/cloud-sync-design-adjudication-2026-10-19.md). See [product contract](../specs/cloud-sync-v1.md) and [execution gates](cloud-sync-implementation-plan.md).
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
- Represent a playlist as an ordered sequence of media keys with duplicate occurrences retained. Wire snapshots retain ordered duplicate media keys. Deltas use base-fenced edit scripts with occurrence positions relative to the exact base, not cross-device native row IDs. D1 ordering uses sparse/fractional labels (bounded rebalancing) rather than rewriting dense positions on every insertion/removal. No attempt to merge two independent reorder/delete histories. Freeze the physical ordering representation only after the large-delta proof.
- Library and Unavailable use explicit protected roles/designations. Never claim a legacy playlist named Library/Unavailable by name alone. Preserve optional legacy bindings through the already validated native import path.
- Define one canonical semantic fingerprint covering Library, playlist identities/names/order/artwork choices/memberships, protected roles, tag definitions and assignments. Exclude volatile caches, player state, network freshness and local integer IDs. A separate payload digest covers the exact transfer bytes, including display metadata used in the preview.
- Encode 64-bit counters as validated decimal strings in JSON; no JavaScript precision loss. Use canonical ordering, explicit nullable fields and pinned normalization rules. Hash equality is meaningful only within the same codec version.

Cloud head is `(service_generation, library_id, catalog_epoch, revision, head_counter, active_generation)`. `head_counter` advances on every publish, including epoch replacement; it must never intentionally decrease within a service generation. Clients retain the greatest observed counter and corresponding head identity/digest outside catalog backups. A lower counter, or equal counter with different epoch/revision/digest, means `ROLLBACK_SUSPECTED`, not a normal pull. `revision` is a monotonically increasing counter within an epoch. A deliberate whole-cloud replacement creates a new random `catalog_epoch` and advances the published head. `service_generation` lives in Worker deployment configuration **outside D1** and is rotated for database rollback/disaster recovery; old tokens/requests cannot be replayed into a rewound database.

### Proposed logical D1 tables

Names are illustrative; physical schema/migrations are a milestone deliverable.

| Group | Contents |
| --- | --- |
| `catalog_head` | Initialized flag, epoch/revision, active dataset generation, current protocol |
| `streams`, `library_members` | Portable saved stream references/metadata and canonical membership |
| `playlists`, `playlist_items` | Stable playlist ID/role, sparse order labels, ordered stream references; native duplicates allowed |
| `categories`, `tags`, `assignments` | Portable definitions, normalized uniqueness and Library-only assignments |
| `devices` | Token hash, grant set, revocation/auth generation, enrolled service generation, monotonic resolved operation sequence |
| `operation_receipts` | Device sequence + request digest + committed/retired result; bounded retention. Sequence is the operation identity; no redundant UUID |
| `changes` | Atomic revision-tagged edit scripts, including deletions and large sealed-delta references |
| `transfers`, staged rows | Owned immutable chunks/candidate digest, intended base, expiry, quota and validation status |
| `backup_slots` | Three-slot reservation state, immutable R2 key/upload ID, declared/verified size, kind, digest and active-operation protection |
| `commit_assertion` | Temporary CHECK-constrained guard rows used inside an atomic batch, removed before successful completion |

Foreign keys/constraints enforce membership and definition references. Every active read is scoped to the authenticated library and active generation. Staged data never becomes visible merely because upload finished. Cloud tokens cannot submit SQL or choose arbitrary R2 keys.

### Exact semantic projection (codec v1)

| Native value | Fingerprint / application rule |
| --- | --- |
| Stream identity | Exact numeric service mapping version + saved URL; no URL/title equivalence |
| Titles/uploader/duration/thumbnail cache | Transfer/display hints only, excluded from semantic fingerprint. Preserve existing native metadata; hints initialize genuinely new stream rows only |
| Library and Unavailable | Verified role + ordered unique media keys. Library order is synchronized because native reorder and Tags Library sort expose it |
| Ordinary playlists | Portable UUID, exact supported name, ordered keys with duplicate occurrences retained |
| Playlist display_index | Rank among synchronized local playlists only; exclude gaps and remote bookmarks. Preserve remote bookmarks' relative placement on apply. Equal native indices use the existing deterministic order as captured, then persist portable rank |
| Automatic playlist thumbnail | Excluded; refresh using native behavior after membership apply |
| User-selected permanent thumbnail | Selected stream key or explicit null, never native stream UID; selection need not be a membership only if existing native policy allows it (prototype gate) |
| Tag/category definition | Portable ID, exact stored display name, stored normalized key and category relationship. Preserve readable legacy names; new edits use TagNames.clean/key. Pin trim/case rules through Kotlin/TypeScript parity vectors, including non-ASCII and legacy controls |
| Tag assignments | Only active Library keys enter sync. Unrelated recoverable orphan assignments remain local and are excluded, not purged |
| Materializing an orphan key | Target assignments are exact cloud assignments. Divergent recoverable orphan metadata requires review and a local recovery point before replacement, including on an otherwise automatic addition |
| Tag concurrency tokens | Local-only; apply increments catalog revision and affected stream revisions using native semantics |
| Cloud/native maps, receipts, UI preferences | Excluded from portable semantic content; maps persist separately while disabled and invalidate on database incarnation changes |

Canonical collection order is explicit, strings must be valid Unicode, numbers never pass through imprecise JS 64-bit arithmetic, and exact transfer digest is separate from semantic identity. Codec/normalization/counter vectors are shared between JVM, Android and TypeScript. Readable legacy data is never silently normalized into a different name or dropped to fit the cloud.

## 3. Normal synchronization protocol

Android keeps one acknowledged baseline plus at most one frozen in-flight request. A persistent journal records monotonic device sequence, base cloud head, semantic before/after fingerprints, exact request bytes/digest and connection generation **before sending**. Local catalog changes remain usable while a network request is in flight.

On app startup/foreground and sync work, compare the actual canonical catalog with the saved baseline under the writer barrier. In-memory observer events are wakeups, not the sole source of durable truth. This catches an edit committed immediately before process death even if scheduling never ran.

| Local versus baseline | Cloud versus baseline | Action |
| --- | --- | --- |
| Unchanged | Unchanged | No transfer; update verified status |
| Changed | Unchanged | Send the frozen delta against that exact base; continue local editing independently |
| Unchanged | Changed | Download validated changes/current snapshot and apply only if local still matches its captured fingerprint |
| Changed | Changed | If a validated cloud snapshot exactly equals local, adopt it as baseline after resolving outstanding operations; otherwise pause for reconciliation |
| Unknown receipt for an already sent operation | Any | Resolve the same operation's outcome first; never create a replacement request with a new identity |

When a push succeeds, advance the baseline to the **acknowledged frozen candidate**, not to whatever is currently on-screen. Edits made during transmission remain a new local delta. A remote apply does not generate an echo upload. If the same known operation committed but its response was lost, return the existing receipt after authentication and request-digest verification.

### Atomic D1 commit and retry

A preflight SELECT followed by unguarded UPDATE is insufficient. For a small operation, one `D1Database.batch()` must cover: current authorization/head assertion, unique operation receipt/sequence admission, invariant-preserving record changes, change-journal entry, revision increment, and final result. A failed precondition must abort the **whole** batch, not just affect zero rows while later statements still write.

Assertion pattern: `ok INTEGER NOT NULL CHECK(ok = 1)`, populated with an **unconditional VALUES insert of a scalar SELECT COUNT(*)** over unique head/device rows satisfying every head, token hash, grant, enrollment/service generation, nonrevoked status and next-sequence predicate. Missing rows and NULL comparisons yield count 0, never a successful empty INSERT or NULL CHECK. Authorization-sensitive schema fields are NOT NULL. Remove the guard row at the end. After **any** failed batch, first reauthenticate and resolve the same (device, sequence) receipt: an identical committed digest returns its original result even when another concurrent request advanced the head. Changed digest is a replay error; missing receipt after an unclassified database/transport error remains outcome-unknown. Only proven precondition failures are definitive conflicts. Prove this on D1, not only generic SQLite.

Validate domain changes against a primary, revision-fenced read; the commit's in-batch head assertion ensures that validation is still applicable. Use prepared statements. Budget each batch below the deployed plan's query, parameter and duration limits. Do not assume an arbitrary interactive BEGIN/await/COMMIT transaction exists in the Worker binding API.

Receipt pruning does not permit replay: each device retains its accepted sequence high-water mark. An old sequence whose result has expired returns an explicit resynchronization/unknown-outcome error, never applies again. One active operation per device prevents skipped sequences from being silently treated as success. App data loss requires new device enrollment rather than guessing a sequence.

**Resolve-or-retire:** for an authenticated next sequence, one guarded batch either discovers its existing receipt or writes a RETIRED receipt and advances the device high-water mark without changing the catalog. A delayed original commit must then fail. A concurrent winner is resolved by receipt lookup after the guard failure. Run this before discarding/quarantining work, re-enabling, or creating a destructive preview. Do not retire on mere timeout; unknown retirement outcomes are resolved with the same sequence. Expired receipts never make an old sequence admissible.

### Large operations and bounded transfers

Normal edits update changed records only; never clone/re-upload the whole catalog for each save. All ordinary deltas, including Remove All Everywhere across thousands of items/playlists/tags, use the same admission/receipt/epoch semantics. Oversized ordinary deltas use one generic sealed staging flow: bounded typed chunks -> seal -> validate against exact base -> one guarded set-based publication. No R2 archive and no epoch reset are required for a routine large edit. Whole-cloud replacement remains a distinct explicitly approved operation with a new epoch and an outgoing recovery archive.

The initial control-plane proof is plan-independent, local-only and targets **Workers Free limits** by default; it is not approval for a row-heavy production catalog on Free. No paid upgrade is authorized. Use sparse ordering and bounded set-based SQL; prototype packed bounded pages only if the measured row budget makes them necessary. Do not prematurely build a second chunk-store database just to avoid an unmeasured bill. Full-scale physical-schema/publication choice is a required later M1 gate, not part of the small admission skeleton.

For that gate, measure 20,000 streams / 100,000 memberships plus tags locally, then run only an admitted **small synthetic** deployed sample after account/remaining-quota checks. Never exhaust the account allowance with a live scale fixture. Proposed Free safety budgets: <=32 SQL statements including recovery lookups per request, <=64 KiB JSON request for the first control-plane proof, <=8 ms measured deployed p95 CPU, <=50,000 project rows written / 2,000,000 read per UTC day including staging/indexes/cleanup, <=250 MB database. These are conservative application targets, not guaranteed unused account capacity; shared-account usage is checked independently. Daily-budget rejection keeps local changes and last cloud state intact. If full scale cannot meet them, leave large enrollment disabled and present the measured alternatives before changing a plan. Small local proofs may proceed without a paid-plan choice.

The [implemented daily-budget and housekeeping design](cloud-budgets-v1.md) puts durable reservation/spending state in external CONTROL and meters token authentication, DATA statements, index writes, cleanup and CONTROL overhead. Its caveats distinguish project admission from shared-account allowance and post-query metadata from an absolute billing cap. Production activation and larger deployed limits still require their own measured acceptance.

Chunk ownership includes library, initiating device, sequence, exact base head, counts, digest, expiry and byte/row reservations. Sealed chunks are immutable; publication consumes exactly the validated seal. Missing/exceeded budgets fail before publication. Foreign keys/reference scans cover the resulting state, not only each chunk. Preserve occurrence order, Library uniqueness, Unavailable subset and reserved names; global removals affect all ordinary duplicates and assignments. Expired/canceled/ambiguous staging occupies its reservation until fenced cleanup; never silently allocate another generation. Bounded compaction/rebalancing must be measured; sparse labels are not infinitely divisible.

The change journal is bounded by both age and bytes; it is not backup history. Initial proposal: up to 7 days and 16 MiB, whichever is reached first. Publish a minimum available cursor. A device behind it must fetch a fresh catalog; it must not interpret missing history as no changes. The strict base/epoch policy prevents old deletion replay or resurrection without permanent tombstones.

### Consistent downloads without R2

Read the head from primary, fetch paginated data tied to that epoch/revision, and check the head inside each page's consistent query/batch and again at completion. If the revision changed, discard/restart the mixed candidate with bounded retries, then report busy instead of retrying forever. An individually consistent page is not a consistent multi-page snapshot by itself.

Stream the validated result to device staging; no permanent cloud copy is needed for a confirmation dialog. Once completely downloaded, the candidate is immutable locally. Confirmation checks current cloud head and exact local fingerprint, but always applies these same bytes. Normal sync must not create R2 objects even for a very large catalog.

## 4. Android durability and concurrency

### State and scheduling

Durable states: `DISCONNECTED`, `DISABLED`, `ENROLLMENT_PREVIEW`, `ENABLED_CLEAN`, `ENABLED_PENDING`, `OFFLINE`, `CONFLICT`, `OUTCOME_UNKNOWN`, `IDENTITY_REPAIR_REQUIRED`, `LOCAL_METADATA_REVIEW`, `ROLLBACK_SUSPECTED`, `APPLY_RECOVERY_REQUIRED`, and `AUTH_REVOKED`. A transient network failure is not user-disabled or an empty cloud. Persist desired mode separately from transient status.

Use one uniquely named sync-work chain per bound library/device, with connected-network constraints and backoff. Coalesce local events. Foreground entry and explicit Sync now request prompt checks; optional periodic background checks use WorkManager and do not promise instant delivery. No perpetual wake lock, playback foreground service or websocket is introduced. Existing AI foreground-service behavior is independent.

A persisted connection generation fences callbacks and worker requests. Disable or endpoint changes persist the generation under the shared apply writer barrier before cancellation. The linearization point is the committed disabled generation, not the initial button tap; an already admitted apply finishes before it. WorkManager cancellation alone is not a network-commit barrier. An already submitted request can finish remotely; retain its receipt identity while refusing any stale callback's local apply.

### Record-level apply: two databases and protected binding preconditions

There is no cross-file SQLite atomic transaction. Hold the existing canonical/tag writer barrier only for local snapshot/admission/application, never while waiting on HTTPS or user confirmation. Preserve the established lock order: authorization admission/drain where applicable, TagRuntime barrier, Library monitor, then native transactions. Audit all native and AI writers before claiming coverage.

Protected identities also live in `ritovision-library.binding`, outside both databases. Admission requires verified existing Library and Unavailable roles and successful existing identity/recovery checks. Sync apply does **not** create/rebind these roles; unresolved name collisions/identity repair block it and use existing explicit repair flows. Ordinary playlist names reserve trimmed case-insensitive `Unavailable`; roles are still never inferred from names. Library-name handling follows existing native policy, not an invented blanket ordinary-name ban.

Proposed recoverable apply mechanism:

1. Stage the complete target and a compact durable apply journal containing database incarnation, native ID mapping plan, exact old/target semantic projections, operation identity, and checksum. fsync required files and commit journal state before changing the native catalog.
2. With the writer barrier held, recheck the local fingerprint/connection generation and complete prior domain recovery. Resolve/validate native IDs; preassign new ordinary native playlist IDs under the shared allocation/writer barrier, persisting the exact IDs and incarnation before insertion. Read both sqlite_sequence and current maximum; use checked arithmetic and explicit IDs. Never rely on AUTOINCREMENT alone across restored databases or external manual ID insertions. This must be tested against every allocation path before production. Never infer a new playlist's identity solely from its display name.
3. Apply the main catalog changes in one native Room transaction for the admitted bounded operation. For larger operations, use a separately proven bounded/recoverable path; never call a partially committed loop atomic. Preserve history, subscriptions and unrelated native metadata.
4. Apply tag changes, cloud ID maps, baseline pointer and completion state in a tag-store transaction. The baseline payload is already durably staged before its pointer changes.
5. Verify the complete target projection and complete cloud-level notification, then remove superseded temporary state. Room observers may fire at Room commit: do not promise atomic visibility across stores. The Room phase must be invariant-valid by itself; tag/AI reads and writes must finish/reject pending recovery before observing mixed logical state. Reproduce native tag catalog/per-stream revision bumps so stale native/AI editors cannot overwrite an apply.

Recovery runs **before catalog reads/writes, AI catalog access or sync resumes**. If the main projection equals the recorded old state, replay the frozen operation idempotently. If it equals the target, finish the tag/baseline phase. If it matches neither, fail closed and retain recovery evidence; do not replay a stale global delete into an unrelated local database. A process-local generation/lock alone is not recovery evidence. Map allocation and interruption at every boundary are explicit prototype gates, not claimed solved merely by writing a journal.

If this cannot be proved without an additive fork-owned Room receipt/migration, document the narrow change and adjudicate it before implementation rather than secretly weakening the recovery guarantee. Do not repurpose AI command receipts as cloud authorization or operation identity.

### Native import/export interaction

Cloud credentials, Sync toggle, endpoint, connection generations and all cloud runtime state reside outside default preferences and outside all exported files, using Android Keystore-protected encryption for local token bytes. Exclude cloud endpoints, enablement, device identity, sequences, outbox, executable intents and local mappings from native backup restore activation. Restoring portable catalog content must not restore transport authority.

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

The replacing Android device produces the outgoing cloud-catalog archive from its exact downloaded preview pages. Worker-issued digests/MACs bind each page to the fenced head; final verification must validate the complete ordered manifest and exact uploaded bytes, not trust device-supplied hashes. Stream verification in bounded work; do not buffer/hash/decompress a 256 MiB archive in a Free Worker request. Freeze/test the concrete multipart and per-page verification format in M2 before allowing replace.

The Worker can validate format/manifest/size and archive transfer integrity; Android's existing native DB integrity/schema checks remain required to call a device ZIP restorable. Do not claim a remotely accepted arbitrary upload passed native restore validation. Record producer validation plus checksum and exercise real round-trip restoration in acceptance.

### Three-slot admission and cleanup

Serialize reservation changes transactionally in D1. Each immutable object key belongs to one of three occupied/reserved slots. Completed payloads, multipart uploads and ambiguous outcomes all count; concurrent devices share the same cap. No public bucket, user-chosen path, bulk public listing or long-lived upload capability.

At three completed slots, verify two newer backups remain, select the oldest unprotected one, mark deletion in progress, delete through R2 and confirm absence, then free that slot. Only then admit the new upload. Do not free a slot merely because a timeout/lease expires: an outstanding upload/complete may still finish. Reconcile/abort it definitively or quarantine its slot and block further growth. Stale workers must not create new keys or repeat a completed rotation.

Use multipart for every managed recovery payload, including a single final part for small archives, to retain a concrete abortable upload identity. Proposed part size is 8 MiB, <=32 parts for the 256 MiB cap; every non-final part is the same size and >=5 MiB. Multipart ETag is not a whole-archive SHA-256. Do not assert that abort alone resolves a raced completion: verify/clean any completed object before freeing a slot. Validate each part's number, size and ownership; restrict concurrent part requests and retries to the reserved key/upload ID. Completion is idempotent. Count declared reservations until exact committed bytes are known. If verification fails, retain good archives and clean/quarantine the failed slot.

Include enough manifest metadata in the immutable object to rediscover recoverable objects after losing/rewinding the D1 backup index. D1/R2 have no shared transaction: an upload committed before its index write is an orphan to reconcile, not a reason to upload another key. Index-first records do not prove an object exists. Every listed recovery point must refer to verified extant bytes.

Use lifecycle rules only as a secondary incomplete-multipart cleanup backstop (proposed one day). Do **not** age-delete all committed archives: with infrequent backups that could erase the last copy. Worker admission/count/size accounting provides the cap; lifecycle cleanup is delayed and cannot implement a reliable keep-latest-three policy [C4]. Scheduled housekeeping checks at most this small namespace and does not generate archives. If cleanup stays broken, new backups stop and the status says so.

### Before whole-cloud replacement

Export and verify the **outgoing cloud catalog**, not the incoming local branch. Approval includes outgoing head, incoming digest and the specific eligible archive to evict. Final D1 publication CAS checks approval, seal and matching verified recovery point. Any head change invalidates approval and requires a new preview; no automatic reapproval or retry against latest. A stale-CAS archive remains linked to its failed attempt; resolve/clean it and reuse that same slot, without another historical eviction. Requests replay the same receipt/attempt identity and never rotate twice. Require a brief persisted per-library cooldown (proposed 60 seconds) and one active replacement, not a blanket one-replacement-per-day restriction. Repeated separately approved replacements can still exhaust old history: three mixed slots provide no fixed day count or guaranteed two/three-replacement depth. Communicate the actual retained list.

## 7. Authentication, privacy and account recovery

Operator provisioning creates a per-device high-entropy bearer token with narrowly scoped library grants (read/write, replace, backup) and a server-side hash. Device tokens are not Cloudflare account API keys. Token generation/admin bootstrap stays off ordinary mobile APIs; ordinary devices cannot mint new devices/grants. Owner revocation and independent re-pairing remain possible when a phone is lost. Use standard cryptographic primitives, bounded constant-time comparison where relevant, HTTPS only, no credentials in URLs, logs or exports.

Verify authenticated service/library identity during connection. Do not forward Authorization across redirects or to a changed host. Protect private backups with authenticated Worker access and no shared public cache. Whitelist request shapes, reject unsupported versions, enforce rate/byte limits, and treat all titles/URLs as data. The Worker must not fetch arbitrary media URLs; sync does not introduce a server-side URL proxy.

The service is cloud-readable, not end-to-end encrypted. The owner controls the Worker and account; provider transport/storage protections are not a zero-knowledge claim. Local exports provide an independent recovery option, since R2 and D1 in one account can both become inaccessible.

For D1 Time Travel recovery, enter maintenance outside D1, record current recovery information, restore, rotate `service_generation`/authentication generation in Worker configuration, invalidate old device tokens and cursors by requiring devices.enrolled_service_generation to match external configuration on every auth/guard, validate catalog invariants and reconcile the R2 index before reopening. Re-enroll devices with a preview. Never roll back the token table and thereby silently revive revoked devices. Never test Time Travel against the live owner's database.

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

### Additional verified sources for adjudication

- [C7] D1 pricing: Workers Free 100,000 written / 5,000,000 read rows per day; indexes add writes, staging and deletes count, limits reset at 00:00 UTC, exceeding a daily limit prevents D1 queries. These are shared-account limits, not a dedicated project allowance. https://developers.cloudflare.com/d1/platform/pricing/
- [C8] Workers Free HTTP CPU budget is 10 ms, Paid default 30 seconds; awaiting D1/network is not CPU time. Our local runtime cannot certify deployed CPU/quota acceptance. https://developers.cloudflare.com/workers/platform/limits/
- [C9] SQLite CHECK(NULL) passes, so admission explicitly uses NOT NULL and COUNT predicates. https://www.sqlite.org/lang_createtable.html#check_constraints
- [C10] R2 non-final multipart parts must be >=5 MiB and equally sized; multipart ETag is not full-payload SHA-256. https://developers.cloudflare.com/r2/objects/upload-objects/


### M1 native projection decision — 2026-10-19

Implement native capture before record apply. Capture reads the actual Room catalog plus tags behind the shared writer barrier, requires existing verified persisted Library/Unavailable identities, and refuses pending native recovery rather than creating/rebinding protected roles or silently repairing data. It preserves native content; the only writes are portable identity mappings in the existing fork-owned tag store.

Add tag schema **4** (main Room stays **9001**) with `cloud_catalog_context` and `cloud_catalog_identity`. One bound service/library scope and a random local incarnation identify the mapping set. The same incarnation and observed main-playlist sqlite_sequence high-water value must be present in the binding file; role-ID equality alone never proves database lifetime. UUIDs are assigned to native playlist/category/tag rows, never inferred from names; deleted mappings are pruned only after a complete valid capture. Binding changes, missing/mismatched lifetime anchors, a decreasing playlist sequence, or an expected-incarnation mismatch block capture. Native full export/import sanitizes all mapping/context rows and runtime binding markers in staged copies; missing-tag stock imports also sanitize retained tag copies. No token, endpoint, sync toggle, baseline or outbox is introduced in this migration. Ordinary app startup does not enable or invoke cloud capture. Explicit enroll(scope) creates local identity state only when no context exists; capture(scope, requiredIncarnation) cannot enroll or reset it.

Supported restore paths replace/clear these runtime mappings and produce a fresh incarnation on subsequent explicit enrollment. This is not tamper detection for arbitrary out-of-band sqlite replacement. Stable ID adoption from another device and crash-safe record application are separate later work; this capture adapter cannot apply remote data.

Query bounds and limits apply before mapping commits. Invalid stored name keys, dangling joins/artwork, duplicate protected membership or missing protected binding fail without silently dropping or normalizing content. Active assignments enter the portable catalog; orphan assignments remain intact locally and are exposed separately for future reconciliation. Native playlist rank uses display_index plus native case-insensitive name ordering and deterministic ID tie-break; remote bookmarks and numeric gaps are excluded.


### Read-side review corrections — 2026-10-19

[Adjudication](../reviews/cloud-native-fixes-adjudication-2026-10-19.md) governs P2-1/2/3 and optional fixes from the completed native review. Standard Library creation after a recreated main DB writes a new unmarked binding, preventing surviving tag mappings from being reused by unrelated native row IDs. Same-Library explicit rebind/copy preserves marker fields. Existing unanchored preview-build mapping contexts fail closed; clear them only through an explicit recovery/reset path, not by ordinary capture. An external arbitrary replacement that also copies a matching binding/context and nondecreasing sequence is outside this detection guarantee.

The optional marker pair is backward-readable in the binding and requires no new Room/tag SQL migration (still 9001/4). Standard staged backup/import strips runtime marker state. Capture validates content before writing a changed marker, reads it back, then permits its tag transaction to commit. The two stores are **not atomically transactional together**: failure can leave an unclaimed anchor or conservative advanced high-water, which must block or require explicit re-enrollment rather than resurrect old maps. Actual native failure/probe tests cover this boundary.

Only ordinary permanent artwork is portable; corrupted nonportable protected-role artwork does not block an otherwise valid capture. Native duration and stream type are included in display hints, not semantic hashes. The scale regression compares complete Room table contents before/after capture, and ordering parity is checked against the real merged-playlist function.

Sparse ordering uses centered/fixed-stride head/tail growth and bounded local redistribution; see [sequence contract](../../cloud/protocol/sequence-v1.md). It must never conceal a full-list rewrite as a cheap ordinary append. Delete-before-put and exact base fencing are mandatory.


### Actual publication and native apply implementation — 2026-10-19 continuation

The catalog API now has typed endpoints, device-token-derived authority, exact-head paging, immutable staged packed-page uploads, content validation/sealing and one atomic D1 publication with the existing sequence receipt guard. Packed rows avoid one D1 write per playlist occurrence. The default deployment admission remains conservatively limited (512 KiB/128 pages) until deployed CPU/quota measurements; a larger local scale proof is not Free-tier CPU acceptance. There is no live deployment or forced replacement/R2 endpoint yet.

Native write-side adds one fork-owned Room receipt table: **NP9/RV2 9002**, with explicit 9->9001->9002 and 9001->9002 migration, preserving upstream-owned tables. This is a bounded commit witness, not reuse of the AI receipt/grant namespace. Tag schema **5** adds a single pending apply, bounded chunked local payload and acknowledged baseline. The pending plan commits to tags before Room mutations; the exact same Room transaction commits the witness. Recovery never replays a destructive main transaction: no matching receipt means rollback/clear pending; a matching receipt permits idempotent completion of tags/maps/baseline. Inconsistent content/identity/digest blocks for explicit recovery.

Ordinary native/AI tag-dependent access must resolve pending cloud apply before existing deletion-intent recovery. Full native exports strip cloud receipt, pending payload/baseline, mapping and binding runtime markers from staged copies. Plain cloud apply does not restart the app, reset preferences, alter subscriptions/history or unpair AI. Conflicting recoverable orphan tags block before modification until the future preview/recovery UI explicitly resolves them. Small synthetic test contexts are the only enrolled/applied catalogs at this point.


### Native apply coordinator constraints — continuation

A native apply entry point consumes a previously captured exact local snapshot plus the exact validated remote catalog/head and hints. It does not obtain network authorization, bypass preview/recovery UI, or enable sync. It rechecks runtime generation, incarnation, semantic fingerprint, tag revision and orphan metadata under the shared barrier. Native IDs are preassigned under the Room writer transaction, and the complete typed plan commits to tag storage before any Room mutation. The Room transaction commits its own matching witness with all main changes. Recovery never replays the main transaction: absent matching witness discards pending local intent; matching witness completes only tag/maps/baseline, after checking exact current main projection and database lifetime. All normal native/AI writes and tag reads resolve this before existing deletion recovery. Standalone cloud capture also resolves pending work.

Main catalog publication preserves existing stream metadata/history/subscriptions/progress, roles and remote bookmarks. Ordinary playlist membership writes are restricted to changed sequences. Definition-name swaps and category moves are staged safely inside the tag transaction; affected stream/catalog revisions advance to invalidate stale native/AI editors. Unknown orphan-to-target conflicts block for explicit metadata review rather than silently deleting recoverable associations. The binding high-water may conservatively advance before tag completion; no cross-file ACID guarantee is claimed. Tests include interruption before/during/after Room commit and before tag completion, on-disk corruption, and unchanged unrelated native state. OS process-kill verification is separate from exception-injection tests.
