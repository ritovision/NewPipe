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

### Bounded cancellation progress

The corrected integrated client/server pair supports explicit progress on `abandon` and `settle`. Recovery cleanup now also yields explicit bounded phase transitions: claim, durable multipart-consumption proof, and terminal slot removal; `abort_fenced=1` prevents dependence on repeat-abort success. A competing quarantine is rechecked before continuing from a claimed phase. After the incoming sequence has been durably resolved/retired, a request whose next SQL batch would exceed the public statement allowance may return HTTP 200 with the same `receipt`, same `attemptId`, and **`cleanupPending: true`**. This means cancellation is **not terminal**. It is a known pre-dispatch allowance refusal, not proof of an uncertain write and not permission to discard the operation. The next exact request continues durable ABORTING/retirement progress without rotating another archive or minting a new operation identity.

Android repeats the same cancellation route/body at most four times per user action. It clears the local pending marker only after a response without the progress flag and a CANCELED/DONE attempt (or the valid unadmitted null-attempt result). If bounded progress is still incomplete, the UI retains the identity/files and asks to continue the same cancellation. Genuine unknown storage effects still retain their original fence tickets and reservations; elapsed time never clears them.

Every request retains the 32-statement ceiling including accounting allowance. Deploy/select the matching integrated Android client; older cancellation clients that ignore `cleanupPending` must not be admitted to this changed contract. No deployed worker was changed in the correction cycle. See [correction evidence](../../doc/validation/cloud-astra-fixes-20261021/README.md) for four-state real local D1/R2/router tests and Android progress-retention tests.

POST /v1/replacement/status reports only this device's last attempt and the library cooldown. Replays of an older superseded attempt fail rather than acting on its successor. Explicit cancellation and a later independently approved replacement are separate user actions, with the actual retained list shown again.

Android persists observed ACTIVE/STALE server authority independently of outgoing payload staging. Cancellation refreshes authenticated discovery before clearing the final intent; an older surviving STALE attempt stays locally protected and blocks Forget until explicitly resolved.

## Android mode consent

Preview and cancellation preserve the owner's current sync toggle/generation. Successful replacement also retains that toggle; a user disable during an interrupted replacement is not undone. Once confirmation writes durable pending replacement identity, ordinary automatic sync is blocked until that exact operation is resolved. A preview left open while native/cloud data changes is invalidated by its existing exact-candidate checks, not silently refreshed. Recovery restoration on this device remains a different operation whose confirmed postcondition is OFF.

## Evidence and limits

At this checkpoint, 269 backend tests pass, including replacement admission, database-clock and cancellation regressions. The native cloud suite has 148 executed passes plus 11 explicit skips, including 16 replacement tests; the actual HTTP route boundary has 2 JVM passes. Local integration covers exact outgoing recovery, epoch/receipt atomicity, permission and expiry, stale-head admission, two-device contention, lost replies, cancel versus publish, same-slot retry, last-copy protection and unknown multipart creation. These tests do not prove live replacement, deployed Worker CPU quotas, two physical devices or OS-level network outages. No production gate was enabled for this checkpoint.
