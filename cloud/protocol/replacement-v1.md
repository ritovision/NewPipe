# Protected cloud replacement v1

Implementation checkpoint only: backend and Android replacement orchestration exist and pass local D1/R2 and physical-phone integration tests. Live replacement acceptance and independent review closure remain required. ENABLE_REPLACEMENT_API defaults false; it also requires the catalog/recovery gates and private R2 binding. Apply migration 0003 before deploying this source, including when replacement remains disabled.

Ordinary catalog synchronization never uses replacement or R2. Replacement is a separately confirmed frozen catalog, a new random catalog epoch, revision 1 and a single increment of the globally monotonic head counter. Existing devices with dirty local work must reconcile the epoch change.

## Authority and exact approval

An operator explicitly provisions cloud_replace_grants for the device's current service and authentication generation; no migration grants replacement automatically. A normal writable token alone cannot replace. Revocation/grant changes are checked again in the publication transaction.

POST /v1/replacement/preview accepts incoming (the exact mode=replace StageHeader), manifest (the exact outgoing cloud-catalog archive) and retryAttemptId (null initially). The outgoing source must equal the incoming header's fenced before head. The existing recovery verifier binds the outgoing pages to the trusted D1 manifest. Preview allocates no payload. Its response names the single eviction, all surviving archives, expiry, incoming digest and proposed new epoch. Any cloud-head change invalidates that approval.

POST /v1/replacement/reserve accepts approvalId and recoverySequence. One D1 transaction reserves the recovery operation and claims the active replacement; two devices cannot both win. The incoming catalog sequence must still be next and have no conflicting upload. The ordinary recovery reserve endpoint cannot consume a replacement approval. While ACTIVE, ordinary backup admission is blocked even after the outgoing archive becomes READY.

The client persists its exact incoming upload, outgoing bytes, approval and both operation sequences before reserve. Recovery multipart transfer/verification uses the existing recovery routes. Incoming staging uses the normal typed start/chunk/seal routes with mode=replace and a matching active approval. The ordinary publish route rejects that mode.

## Publication, resolution and retry

POST /v1/replacement/publish accepts only attemptId. It checks the immutable outgoing R2 object, then atomically verifies the exact active approval/header, sealed incoming upload, current replacement grant, matching READY outgoing archive and DONE recovery operation, external service generation and 60-second persisted cooldown. The normal exact-head/auth/sequence CAS, page publication, new epoch, receipt, attempt terminalization and cooldown write share one D1 transaction. Unknown replies resolve the same sequence/digest; they never mint another epoch.

POST /v1/replacement/settle accepts attemptId and keepForRetry. It resolves or retires the incoming sequence before releasing the attempt. A committed receipt preserves the completed replacement. An uncertain backup abort keeps the attempt active and capacity occupied.

With keepForRetry=true, a verified outgoing archive remains linked to a STALE attempt. A new explicit preview names that prior attempt and evicts/reuses only its archive slot. It cannot rotate another historical archive. At least one other extant verified archive must survive this retry cleanup. If the failed attempt is the only verified copy, retry stops; an explicitly requested separate backup may use a free slot, then a new preview can proceed. Ordinary backups cannot evict history while a STALE replacement occupies a full three-slot namespace.

With keepForRetry=false, an already verified archive is kept as ordinary history; an incomplete upload must be definitively aborted or remain quarantined. No timeout alone frees capacity. POST /v1/replacement/abandon accepts approvalId and recoverySequence to resolve a lost reserve response or fence a delayed reserve before an attempt was created. For a never-admitted replacement, catalog-sequence retirement also fences delayed recovery admission; it does not consume a recovery sequence or require an obsolete recovery approval. A matching allocation that won before retirement is settled by exact attempt identity. Wrong-digest ordinary retirement cannot consume an active replacement's incoming sequence.

POST /v1/replacement/status reports only this device's last attempt and the library cooldown. Replays of an older superseded attempt fail rather than acting on its successor. Explicit cancellation and a later independently approved replacement are separate user actions, with the actual retained list shown again.

Android persists observed ACTIVE/STALE server authority independently of outgoing payload staging. Cancellation refreshes authenticated discovery before clearing the final intent; an older surviving STALE attempt stays locally protected and blocks Forget until explicitly resolved.

## Evidence and limits

At this checkpoint, 269 backend tests pass, including replacement admission, database-clock and cancellation regressions. The native cloud suite has 148 executed passes plus 11 explicit skips, including 16 replacement tests; the actual HTTP route boundary has 2 JVM passes. Local integration covers exact outgoing recovery, epoch/receipt atomicity, permission and expiry, stale-head admission, two-device contention, lost replies, cancel versus publish, same-slot retry, last-copy protection and unknown multipart creation. These tests do not prove live replacement, deployed Worker CPU quotas, two physical devices or OS-level network outages. No production gate was enabled for this checkpoint.
