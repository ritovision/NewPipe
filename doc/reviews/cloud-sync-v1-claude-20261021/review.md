I have everything I need: independent pass, cross-check against Astra's review, verification of each correction and its evidence. Writing the final report.

# Independent whole-feature review — NewPipe Cloud & Sync v1

**Reviewer:** Claude (Opus 5.5), independent review-only pass. I made no code edits, used no subagents, touched no live resources and executed nothing at runtime.
**Date:** 2026-10-21
**Source identity (`REVIEW_SOURCE.json`):** branch `feature/cloud-sync-v1-20261019`, commit `582f0791d7ecf34291afd6009a4e09e92b7ddf19`, tree `59d7846c39d020ca75c8cb743afabb75d53fe248`. `review_only: true`, `runtime_tests_available_to_reviewer: false`.
**Method:** I read the product contract and architecture first, then traced the production Worker, operator, migrations and Android sources, including the tagging/import coordination they depend on. Only after forming my own findings did I read the Astra review, the implementer adjudication and the correction evidence.

Every finding below comes from reading source. **Nothing was reproduced at runtime.** "Source-confirmed" means the path is deterministic and fully traced in code. It does not mean it was executed.

---

## 1. Overall recommendation

**CONDITIONAL.**

| Scope | Decision |
| --- | --- |
| **A. Continued closed, isolated, owner-operated synthetic QA** at the deployed 8-page / 64 KiB catalog and 1 MiB recovery profile, with owner-held, unmodified clients | **PASS for this named scope.** The eight Astra corrections are present in source and backed by the retained local and emulator evidence, with the residuals noted in §5. None of my findings blocks this scope. |
| **B. Normal-use release or production activation** of v1 (real libraries, more than one real device, writable tokens on devices the owner does not fully control, native device-ZIP cloud restore with AI pairing in use) | **HOLD.** Three blockers remain, plus the integrated live, real-device and resource gates in §4. |

The correction evidence does not cover a live run of the corrected candidate. Live QA stayed closed during this cycle, so earlier live and CPU results apply only to their earlier commits.

**Finding counts (this review):** Critical 0 · High 1 · Medium 5 · Low 3 · **Total 9**.

**Blockers:**
- Scope B: 3 (CLAUDE-CS-001, CLAUDE-CS-002, CLAUDE-CS-003).
- Scope A: 0.

No redesign is required. Each finding has a bounded correction that keeps the current architecture (§3).

---

## 2. Prioritized finding register

| ID | Severity | Confidence | Blocker (scope B) | Short description |
| --- | --- | --- | --- | --- |
| CLAUDE-CS-001 | **High** | Source-confirmed | **Yes** | A duplicate page ID across upload chunks causes a non-CHECK constraint failure. The fence treats this as an unknown write, so the CONTROL ticket and budget hold are kept permanently. Maintenance can never reach HELD, and revocation does not clear it. |
| CLAUDE-CS-002 | Medium | Source-confirmed path; trigger likelihood inferred | **Yes** | Native ZIP restore unpairs AI permanently before its final local-catalog admission check. A refusal at that point leaves AI unpaired and fenced, with no restore and no restart. |
| CLAUDE-CS-003 | Medium | Source-confirmed | **Yes** | Automatic sync re-sends a definitively refused frozen upload indefinitely (CLOUD_CHANGED / UPLOAD_EXPIRED). The conflict path is never reached and the user sees a misleading "preview" message. |
| CLAUDE-CS-004 | Medium | Source-confirmed | No (fix before broad rollout) | A revoked or rejected credential plus any pending journal leaves no supported way to disconnect or re-pair the installation. |
| CLAUDE-CS-005 | Medium | Trigger source-confirmed; resource impact projected, not measured | No (rollout gate) | Event-driven sync watches the Room `streams` table. Routine playback and feed refresh therefore trigger full writer-barrier captures and metered requests with no catalog change. |
| CLAUDE-CS-006 | Medium | Medium (depends on how real R2 behaves) | No (verify before relying on it) | The replacement-cancellation `cleanupPending` continuation (Astra-005 fix) assumes a second R2 multipart abort succeeds. A successful first abort is not persisted, so a provider error on the repeat quarantines the slot. |
| CLAUDE-CS-007 | Low | High-confidence (static statement count, not executed) | No | Ordinary recovery Stop/abandon from ROTATING exceeds the 27-statement DATA allowance after the eviction is already done. It reports "exceeds safety limits… ask the operator"; repeating Stop completes it. |
| CLAUDE-CS-008 | Low | Source-confirmed | No (product decision) | Replacement and recovery-restore previews turn sync OFF at preview time and leave it OFF. This contradicts the spec's "does not automatically change the current toggle" for replacement. |
| CLAUDE-CS-009 | Low | Source-confirmed | No | Regression from the Astra-003 fix: prepared native staging now lives in non-evictable `noBackupFilesDir`. Nothing sweeps it after process death, so abandoned prepared copies accumulate. |

---

### CLAUDE-CS-001 — A client-triggerable non-CHECK constraint failure permanently wedges the external fence and budget

- **Severity:** High · **Confidence:** Source-confirmed (not executed) · **Blocker:** Yes for scope B; not for scope A.
- **Violated invariants:**
  - Operator recovery must stay possible: DRAINING→HELD requires zero tickets.
  - Only genuinely uncertain writes should keep non-expiring tickets and reservations.
  - Revoking a misbehaving device should restore control.

**Source evidence**
- `cloud/src/service.ts:56-69`: `readChanges` enforces strictly increasing page IDs only *within* one chunk (line 61). Nothing compares them with pages from earlier chunks.
- `cloud/src/service.ts:229-262` (`chunk`): the guard at lines 243-245 checks the sequence, status, digest, expiry, `received_chunks`, and the change/byte totals. It does not check whether an incoming page ID already exists. The batch then runs `INSERT INTO cloud_upload_changes … FROM json_each(?)` (lines 248-250).
- `cloud/migrations/0001_catalog_service.sql:89`: `PRIMARY KEY(library_id,device_id,sequence,page_id)`. A repeated page ID therefore fails with UNIQUE *after* the CHECK guard has passed.
- `cloud/src/fence.ts:40-42`: only the exact `CHECK constraint failed: ok = 1` message is classed as a known rollback. Any other mutation error sets `uncertain = true`.
- `cloud/src/fence.ts:109`: `finish()` returns without settling when `uncertain`, so the ticket and its reservation stay.
- `cloud/src/maintenance.ts:23`: HELD requires `NOT EXISTS(cloud_fence_tickets)`.
- `cloud/src/budget.ts:17` (holds carry forward) and `doc/architecture/cloud-operator-recovery.md:9` (no expiry or force-clear).
- `cloud/src/index.ts:185` returns `409 PRECONDITION_FAILED` to the client. The client sees a definite refusal while CONTROL records an unknown write.
- The legitimate Android client cannot trigger this: `CloudPages.kt:236` requires change IDs that are globally sorted and distinct.

**Concrete scenario**

Any writable device token, whether held by a modified client, a buggy third-party client or a stolen phone before revocation:
1. `/v1/uploads/start` with a valid header and `chunkCount: 2, changeCount: 2`.
2. `chunk` index 0 = `[{id: X, body: B}]`.
3. `chunk` index 1 = `[{id: X, body: B}]`.

Request 3 passes the guard, then fails UNIQUE. The batch rolls back, but the fence keeps the ticket and hold forever. Request 3 can be repeated against the same staged upload, and each repeat creates another permanent ticket.

At the deployed limits (`budget.ts:41`), each wedge permanently holds 1,280 reads, 352 writes and 384 KiB of growth. About 142 wedges exhaust the 50,000-write daily budget forever; at the 120/60 s edge rate limit that is roughly two minutes.

**Impact**
- After a single wedge, the operator can never close maintenance (DRAINING→HELD). Time Travel, rebuild and reopen are blocked; recovery would need a new resource pair.
- Repeated wedges permanently shrink the daily budget, eventually to zero.
- Revoking the device does not help, because the holds and tickets live in CONTROL.
- This differs from the accepted "an authenticated device can spend its admitted budget" limitation. It is deterministic, cheap, permanent, and it disables the very recovery path meant for a compromised device.

**Smallest safe correction**

Make every client-influenced precondition fail through the CHECK guard. For `chunk`, either:
- add to the guard condition: `NOT EXISTS(SELECT 1 FROM cloud_upload_changes c WHERE c.library_id=d.library_id AND c.device_id=d.device_id AND c.sequence=CAST(? AS INTEGER) AND c.page_id IN (SELECT json_extract(value,'$.id') FROM json_each(?)))`; or
- persist the upload's last accepted page ID and require the chunk's first ID to be strictly greater (global ordering).

Then audit every other client-reachable mutation batch the same way. I found none on the honest paths: recovery and replacement inserts are protected by revision/sequence guards that fail via CHECK first. Do not weaken the fence's uncertainty rule to make this pass.

**Targeted regression**

Production-router Miniflare test with DATA 0001-0006 and CONTROL 0001-0003:
- start (2 chunks / 2 changes), chunk 0 [X], chunk 1 [X];
- assert a 4xx response, zero `cloud_fence_tickets`, `held_*` back to 0, and `closeFence` reaching HELD.

Add a fuzz loop over chunk inputs asserting that every refused request leaves no ticket.

---

### CLAUDE-CS-002 — Native ZIP restore destroys AI pairing before the final admission refusal point

- **Severity:** Medium · **Confidence:** Source-confirmed ordering; the trigger likelihood is my inference · **Blocker:** Yes for scope B, because it violates "do not mutate installed data on preflight refusal".
- **Violated invariant:** a native import refusal must leave installed state unchanged. If AI pairing is reset, the restore must actually happen and the app must restart.

**Source evidence**
- `app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:208-273` (`installReviewedWithShutdown`): the order is
  - first `admit()` under the barrier (line 215);
  - `prepared.consume()` (line 217);
  - `shutdown()` (line 219), with no TagRuntime lock held;
  - the **second `admit()`** (line 249), and only after that `freezeForRestore()` (line 252).
- `installReviewed` passes `AiControlRuntime.beforeRestore()` as `shutdown` (line 206). In `AiControlRuntime.kt:473-486` that call sets `restoring` (line 475) and persists `paired=false, clients=emptyMap()` (line 482). Neither is ever undone.
- The cloud admission lambda is `CloudRecoveryController.kt:322-331`. It re-requires the current generation, OFF, and `elapsedRealtime < preview.expires`, then takes a full native capture that must equal the preview snapshot.
- `CloudRecoveryScreenModel.kt:85` restarts only when `TagRuntime.restoring` is set, and nothing sets it on this path.
- The existing test `CloudRecoveryControllerTest.kt:207-219` edits the catalog *before* `installReviewedWithShutdown`, so it only exercises the refusal before shutdown.

**Concrete scenario**

The user confirms a device-ZIP restore while AI control has a paired client with queued commands.
1. `shutdown()` drains those commands. They mutate the catalog through `TagRuntime.mutate`.
2. The second `admit()` sees a changed capture and throws `CLOUD_LOCAL_PREVIEW_CHANGED`.

The same happens if the 10-minute preview expires during the drain, which can take up to about 40 s, or if the connection changes in between.

**Impact**

The restore is not installed and no restart happens. AI clients are unpaired, and the AI runtime stays fenced (`restoring`) until the process restarts. No catalog data is lost. A user who retries usually completes the restore, which would unpair anyway, but a user who abandons it is left with lost pairing.

**Smallest safe correction**

Split `beforeRestore` into two steps:
1. a **reversible quiesce**: stop AI admission and drain, without persisting the unpair;
2. an **irreversible unpair**, run only after the final admission and `prepared.verify()` under the barrier, immediately before `freezeForRestore`.

On a refusal after quiesce, resume AI admission. The ordering of the stock-import path, which pre-dates cloud, should follow the same split.

**Targeted regression**

Through `CloudRecoveryController` with an injected `shutdown` that also edits the catalog, and separately one that lets the preview expire:
- assert the refusal;
- assert AI pairing and AI runtime state are unchanged;
- assert `TagRuntime.restoring` is false and the installed Room/tag files are unchanged.

Separately assert that a successful restore still unpairs and restarts.

---

### CLAUDE-CS-003 — A definitively refused frozen upload is re-sent forever and the conflict path is never reached

- **Severity:** Medium · **Confidence:** Source-confirmed · **Blocker:** Yes for scope B, because the core two-device flow breaks.
- **Violated invariants:** conflicting local and cloud changes must pause and offer Use cloud / Replace cloud / Keep off. Retries must be bounded and truthful.

**Source evidence**
- `CloudSyncController.kt:289`: `syncNow` calls `settle(s, api, resume = true)` before `decide`.
- `CloudSyncController.kt:383-399`: when there is no receipt and resume is allowed for the same generation and incarnation (lines 393-394), it calls `send(api, pending.upload)` again. It never retires on a definitive refusal.
- `cloud/src/service.ts:197-199`: `start` checks the head (`CLOUD_CHANGED`) *before* recognising the existing staged upload, and line 203 returns `UPLOAD_EXPIRED` (410) for a stage older than one hour. A seal or publish CAS failure surfaces as 409 and resumes into the same `start` refusal.
- `CloudHttpClient.kt:17`: 409/410 are not retryable. `CloudScheduler.kt:115-117` returns success, the periodic work stays scheduled, and every native edit enqueues again.
- The user sees `CloudSyncController.kt:553`: "The library changed after this preview. Refresh it before confirming." No preview is involved.
- Housekeeping, which could retire expired stages, is opt-in and disabled ("No cron was activated").
- No test covers this. `CloudFakePeer` can return `CLOUD_CHANGED` on start, but `CloudSyncControllerTest` only uses network-fault (`beforeStart`/`afterPublish`) resumes.

**Concrete scenario**
1. Devices A and B are both enabled. A edits and its worker saves the outbox.
2. B publishes between A's status read and A's `start`.
3. A's `start` returns `CLOUD_CHANGED`. Every later sync re-sends the same sequence and gets the same answer.

A second variant: a network drop mid-upload followed by more than an hour offline. On resume, `start` returns `UPLOAD_EXPIRED`, and the outbox is stuck the same way.

**Impact**
- Automatic sync on A stalls with a misleading message until the user finds "Resolve pending upload" or toggles sync.
- The CONFLICT decision is never presented automatically.
- Each periodic or event run spends about two metered requests (status + start).
- No data is lost: local edits are retained and B's edits are in the cloud.

**Smallest safe correction**

In `settle(resume = true)`, treat definite pre-commit refusals from `start`, `seal` or `publish` as terminal for that frozen request:
- 409 `CLOUD_CHANGED` / `INITIALIZATION_STATE_CHANGED` / `PRECONDITION_FAILED`;
- 410 `UPLOAD_EXPIRED` / `UPLOAD_NOT_SEALED_OR_EXPIRED`.

On those, call `/v1/uploads/retire`. Resolve-or-retire returns the original COMMITTED receipt if the request did commit. Then finish the receipt, clear the outbox and let `decide` run, which yields CONFLICT. Show a conflict-specific message. Keep unknown and network outcomes on the current resume path.

**Targeted regression**

With `CloudFakePeer`:
- enable, edit locally, change the external head between status and start;
- assert one retirement, a cleared outbox, the next sync reporting `CLOUD_CONFLICT_REVIEW_REQUIRED`, and no further `start` for the stale sequence.

Repeat for an expired stage with housekeeping off, and for a racing commit where retire returns COMMITTED and is acknowledged.

---

### CLAUDE-CS-004 — Revoked/rejected credential plus any pending journal = no supported disconnect or re-pair

- **Severity:** Medium · **Confidence:** Source-confirmed · **Blocker:** No; fix before broad rollout.
- **Violated invariants:**
  - Spec: "Disconnect / forget credentials … remove this device's local cloud credentials and connection state".
  - Architecture: "Owner revocation and independent re-pairing remain possible when a phone is lost."

**Source evidence**
- `CloudSyncController.kt:149`: Forget refuses while any outbox, recovery or replacement marker exists. `CloudReplacementController.kt:383-386` includes `observed.json`.
- `CloudSyncController.kt:325-330` and `:383-399`: resolving needs `/v1/uploads/status` or `retire`. Both require `revoked=0` and `can_write=1` (`admission.ts:16-18`, `:44-49`), so a revoked or downgraded token gets 401/403.
- `CloudSyncController.kt:64`: `connect` requires no existing connection.
- `CloudSyncController.kt:93-94`: `reconnect` requires a *different* service generation and a fresh device with sequence 0.

**Concrete scenario**
1. A lost phone holds an unsent outbox (or a recovery or replacement marker).
2. The owner revokes its token.
3. When the phone is found, the next sync gets 401 and pauses, as Astra-006 intends.
4. Forget fails with `RESOLVE_PENDING_UPLOAD_BEFORE_FORGET`. Resolve fails with 401. A new token for the same service cannot be attached.

The same dead end follows from `REPLAY_MISMATCH` (one token used on two installations) or from an operator downgrade to read-only.

**Impact**

That installation can never disconnect or rejoin the library short of an operator service-generation rotation. Local data is intact. Credentials and connection state persist indefinitely.

**Smallest safe correction**

After a current-generation terminal authority rejection, allow an explicitly confirmed Forget that **moves** the pending journals into the existing `retired-connections/<generation>/` archive (as `finishReconnect` already does) instead of deleting them. Require any later connection to use a fresh device ID.

This is safe because the server re-checks revocation and authority in every final guard. A revoked device's sequence can therefore never commit later, and the next enrollment goes through a fresh preview.

**Targeted regression**
- enable, create a pending outbox (`beforeStart`), make the peer return 401;
- assert Forget succeeds with the journal archived and the native catalog unchanged;
- connect a fresh token, assert a preview is required and the archived sequence is never reused.

Repeat with a pending recovery marker and with `observed.json`.

---

### CLAUDE-CS-005 — Playback and feed refresh drive full captures and metered sync requests through the `streams` observer

- **Severity:** Medium · **Confidence:** Trigger path source-confirmed; resource impact is my projection, not a measurement · **Blocker:** No, but it is a rollout gate.
- **Violated invariants:** daily safety targets (about 50,000 writes) should serve real catalog work. Native capture should not routinely block other Room writers.

**Source evidence**
- `CloudScheduler.kt:62-66` observes `"playlists", "playlist_stream_join", "streams"`, and `:78-82` debounces requests for 2 s.
- `streams` is written on ordinary use:
  - `HistoryRecordManager.java:113,116,144`: `streamTable.upsert` on every played or viewed stream, with history enabled;
  - `FeedDatabaseManager.kt:102`: feed refresh.
  - Stream metadata is hint-only and excluded from the semantic fingerprint (`CloudCatalog.kt` canonical bytes).
- Each run calls `syncNow`. That does a full `CloudNativeCatalog` capture inside `library.runInTransaction` under `TagRuntime.lock` (`CloudNativeCatalog.kt:68-86`), which blocks other Room writers, plus `/v1/connect` (`CloudSyncController.kt:290`).
- Each request is charged at least `ACCOUNTING` = 128 reads / 32 writes at settlement (`budget.ts:14`, `:99-100`, `:161`).

**Scenario and projected impact**

Periodic work alone is up to 96 requests per device per day, about 3,000 accounting writes. Each played track adds one coalesced sync. Two or three devices with heavy listening can plausibly use half or more of the 50,000-write target with no catalog change. Once exhausted, real edits are refused until UTC midnight ("paused by a resource limit"). On large catalogs each capture also holds Room's write lock for seconds, which the docs acknowledge.

**Smallest safe correction**

Remove `streams` from the observed tables, because membership and order changes are in `playlist_stream_join`/`playlists` and tags already signal through `TagRuntime.changed`. Optionally, for event-driven runs, skip the network when a cheap local dirty marker shows nothing changed since the last acknowledged baseline.

**Targeted regression**
- With sync enabled, record N history views and one feed upsert; assert no additional worker network calls or captures.
- Add one Library change; assert exactly one sync.

---

### CLAUDE-CS-006 — The `cleanupPending` continuation relies on a second R2 multipart abort succeeding

- **Severity:** Medium · **Confidence:** Medium. Miniflare tolerates the repeat; real R2 behaviour is not established by any evidence · **Blocker:** No, but verify or fix before relying on the Astra-005 path in production.
- **Violated invariant:** cancellation progress must be truthful and must not manufacture an unknown or quarantined state out of a known one.

**Source evidence (static statement count; not executed)**

Path: `/v1/replacement/abandon` from UPLOADING → `settle` → `retire` → `recovery.abandon` → `recovery.abort`.
- About 27 DATA statements are used by the time the final CANCELED batch is reached. `budget.ts:123-127` refuses that batch (27 + 5 + 5 > 32). Astra measured the same 27 and the intermediate state "ABORTING / ACTIVE".
- The R2 `abort()` has already succeeded by then (`recovery.ts:485-488`). Its success is held only in a local variable `fenced` (line 484). `abort_fenced` stays 0 and `object_etag` stays null.
- `replacement.ts:164-166` returns `cleanupPending`.
- On the continuation, `abort()` is called again. If the provider rejects a repeat abort (for example NoSuchUpload), `fenced` stays false and lines 495-497 quarantine the slot with `RECOVERY_ABORT_OUTCOME_UNKNOWN`. Later retries cycle QUARANTINED→ABORTING→quarantine.
- `cloud/test/recovery.test.mjs:503-535` passes for UPLOADING in Miniflare, which shows only that the emulator tolerates a repeat abort.

**Impact if real R2 rejects a repeat abort**

A clean cancellation becomes a permanently occupied, quarantined slot that needs operator cleanup. It blocks further backups and replacements and keeps the local marker, which in turn blocks Forget.

**Smallest safe correction**

Either:
- persist the definite abort (`abort_fenced=1`) in a guarded batch immediately after a successful `abort()`; or
- remove redundant `control()`/`operation()`/`slot()` reads so that the CANCELED batch fits in the first request.

Do not treat an arbitrary abort error as proof of consumption.

**Targeted regression**

In `runtime-recovery-worker.mjs`, make the second `abort()` of the same upload ID throw. Assert that UPLOADING cancellation still reaches CANCELED within four requests and never QUARANTINED. Confirm real R2 repeat-abort semantics in the next authorized isolated live cycle.

---

### CLAUDE-CS-007 — Ordinary recovery Stop/abandon from ROTATING exceeds the statement allowance after the eviction

- **Severity:** Low · **Confidence:** High-confidence (static statement count; not executed) · **Blocker:** No.
- **Violated invariant:** cancellation must be bounded and truthful about intermediate progress. This is the Astra-005 problem class, in the ordinary recovery routes that the fix did not touch.

**Source evidence**
- `recovery.ts:276-279`: abandon calls `abort`.
- `recovery.ts:460-465`: from ROTATING, abort first calls `advance()`. Lines 309-330 delete the evicted archive, create the new reservation, and re-read operation and slot.
- Then abort re-reads again and runs the ABORTING batch (lines 472-481).
- My count, including token authentication, against the 27-statement DATA allowance:
  - `/v1/recovery/abandon`: the ABORTING batch is refused at 24 + 5.
  - `/v1/recovery/abort`: the post-ABORTING `operation()` read is refused at 27 + 1.
- `recovery.ts:27-31` passes the `BudgetError` through as 503 `REQUEST_STATEMENT_BUDGET_EXCEEDED`.
- `CloudRecoveryController.kt:224-237` surfaces this as `CloudSyncController.kt:564`: "exceeds the configured safety limits … ask the operator to review the configuration."
- No test drives an ordinary ROTATING abort or abandon to completion (`recovery.test.mjs:292-299` blocks mid-way and expects `RECOVERY_ALREADY_COMMITTED`).

**Scenario**
1. Three READY archives exist. A fourth backup reserves (ROTATING).
2. The app dies, or `advance` fails transiently, before rotation.
3. The user taps "Stop upload". The eviction runs, then the request is refused.

A second Stop completes it (RESERVED → CANCELED).

**Impact**

The message is misleading and does not mention that the oldest archive has already been deleted, as the pre-confirm UI warned. A user who does not retry keeps a RESERVED or ABORTING operation that blocks backups, replacement and Forget.

**Smallest safe correction**

Consolidate the redundant reads, or add an explicit progress response for ordinary abort/abandon and have Android continue the same identity (as `finishCancellation` does). Map the budget refusal to "continue cancellation".

**Targeted regression**
- Production router: three backups, reserve a fourth (ROTATING), then abandon and separately abort.
- Assert ≤27 DATA statements per request, a terminal CANCELED within a bounded number of calls, truthful intermediate responses, and the two survivors intact.

---

### CLAUDE-CS-008 — Replacement and recovery-restore previews turn sync OFF at preview time

- **Severity:** Low · **Confidence:** Source-confirmed · **Blocker:** No; needs a product decision.
- **Source evidence:**
  - `CloudReplacementController.kt:117` calls `sync.disable()` in `prepare()`.
  - `CloudSyncController.kt:460-468` persists `enabled = false` after any replacement outcome.
  - `CloudRecoveryController.kt:246` disables in `prepareRestore()`.
  - The UI discloses this (`CloudReplacementScreenModel.kt:86`).
  - The spec (`doc/specs/cloud-sync-v1.md:36`) says replacement "does not automatically change the current toggle".
  - The adjudicated Astra-004 preference was that preview or cancel should not disable an enabled connection.
- **Impact:** opening and then cancelling a replacement or archive-restore preview silently ends automatic sync. Behaviour is inconsistent with the ordinary one-time preview.
- **Correction:** either amend the spec and keep the conservative behaviour, documented as intentional, or disable at confirmation under the writer barrier (as the Astra-004 fix does) and leave cancel without effect.
- **Regression:** start ON, open and cancel each preview, assert ON with an unchanged generation. Confirm, assert OFF.

---

### CLAUDE-CS-009 — Abandoned prepared native staging is never swept (regression from the Astra-003 fix)

- **Severity:** Low · **Confidence:** Source-confirmed · **Blocker:** No.
- **Source evidence:**
  - `TagBackupCoordinator.kt:112` now creates `tag-import-*` under `noBackupFilesDir`, which the OS does not evict.
  - Cleanup happens only through `Prepared.close()` (lines 98-100), called on explicit cancel or `onCleared` (`CatalogTransferModel.kt:108-124`) and in `use {}` blocks.
  - `CatalogFileSwap.cleanupInert` (lines 100-110) sweeps only swap prefixes.
  - No startup sweep for `tag-import-*` exists anywhere in the main sources.
- **Scenario:** the process dies between `prepare()` and install or cancel. This covers the stock import preview, the cloud restore preview, and the local-safety and device-ZIP validations (`CloudSyncController.kt:492`, `CloudRecoveryController.kt:127,273`). A full copy of both databases, up to the 1 GiB ZIP bound, stays behind permanently.
- **Impact:** storage leaks. Over time, `preflightInstall`'s free-space requirement (`TagBackupCoordinator.kt:478`) can refuse later imports.
- **Correction:** at process start, before any new prepare, delete `noBackupFilesDir/tag-import-*`. A `Prepared` is in-memory only, so no live owner can survive process death.
- **Regression:** prepare, kill the process, restart; assert no residual `tag-import-*`, and that a new prepare and install still work.

---

## 3. Independent architecture and trust-boundary assessment

**Is a redesign necessary? No.** The design's basic choices hold up under independent tracing:
- the native local catalog is the installed authority;
- whole-catalog optimistic concurrency with an acknowledged baseline;
- D1 publication through a single guarded batch;
- R2 used only for explicit recovery;
- an independent CONTROL database for maintenance fencing and daily budgets;
- journaled two-database native apply with a Room commit witness.

Each piece maps to a real failure boundary. All nine findings have bounded corrections.

**Trust boundaries verified (no finding):**

| Boundary | What I verified |
| --- | --- |
| Device → Worker authority | Token → device via secret hash plus configured library and service generation (`service.ts:415-425`). Every final publish, retire and replacement transaction re-asserts device, token hash, auth and service generation, `revoked=0`, `can_write=1`, `resolved_sequence = seq−1` and the exact base head (`admission.ts:108-124`, `:141-151`). The replacement guard adds grant, cooldown, READY archive with etag/version/digest, DONE operation and manifest-to-head binding (`service.ts:355-377`). There is no SQL-over-HTTP. |
| Receipts and sequence | Exact digest replay returns the original receipt. A changed digest gives `REPLAY_MISMATCH`. The high-water mark is never lowered by pruning (`housekeeping.ts`). Resolve-or-retire fences delayed commits. |
| Historical provenance vs. current authority | A historical baseline exposes no `acknowledgedHead` (`CloudApplyStore.kt:42-51`). `highest` is untouched by restore. A v2 `historical-local` plan is required for any cross-generation import, and same-library is enforced (`CloudApplyCompiler.kt:33-36`, `CloudApplyPlan.kt:98-109`). |
| CONTROL vs. DATA | Admission, tickets, holds and policy live only in CONTROL. A DATA restore cannot reset them, and the operator restore touches DATA only (`worker.mjs:116-125`). The settle/admit arithmetic keeps `spent ≥ held` across same-day and rollover cases (`0003_budgets.sql:29`). |
| R2 slots | Slot PK (library, 0..2), revision-guarded reservations, eviction only with two newer READY survivors (one for a retry), unknown creation and abort kept, `checkNamespace` refusing a fourth or unindexed object (`recovery.ts:120-124`, `175-185`, `225-229`, `309-330`). Ordinary sync has no R2 capability. |
| Native writers | All `LocalPlaylistManager` writes go through `TagRuntime.mutate` → `recoverPending` → `CloudNativeApply.recoverLocked`, so a pending cloud apply is resolved before any native or AI write. Lock order: TagRuntime → Room → tag transaction. |
| Backup sanitization | Exports and imports scrub cloud context, identities, pending, baseline, witness and binding markers (`TagStore.kt:314-321`, `TagBackupCoordinator.kt:150,371-372`). Credentials and AI state live in `noBackupFilesDir`, outside exported preferences. |
| Android transport | No redirects, no connection retries, bounded reads. Credentials are Keystore AES-GCM with AAD bound to origin, device and scope (`CloudConnectionStore.kt:95-126`). |

**Systemic observations:**
1. **The conservative fence needs a matching discipline everywhere upstream.** "Unknown unless it is the exact CHECK message" is safe only if no client-reachable batch can fail any other way. CLAUDE-CS-001 is the counterexample. The project should make "every client-influenced batch fails only through the CHECK guard" an explicit rule with a router-level test, rather than relaxing the classification.
2. **Ordering of irreversible side effects.** Astra-001 and CLAUDE-CS-002 share a pattern: an irreversible effect (a cross-store commit, or AI unpairing) placed before the last refusal point. Reviewing every "destructive step before final admission" ordering is worth doing.
3. **Liveness of the client state machine.** Safety is strong. Liveness after definite refusals (CS-003), after revocation (CS-004) and in cancellation (CS-006/007) is where users get stuck. None of these lose data.

---

## 4. Coverage and evidence assessment; remaining release gates

**Executed by the implementer** (from `doc/validation/cloud-astra-fixes-20261021/`; I read counts and log tails but could not recompute hashes or rerun anything):

| Evidence | Source commit | What it establishes |
| --- | --- | --- |
| Backend suite: 363 pass / 0 fail / 0 skip (`backend-363.log`) | integrated correction | Local workerd/Miniflare D1/R2 plus pure logic. Not deployed Cloudflare. |
| Cancellation probe: 4/4 (RESERVED, UPLOADING, READY, ROTATING) | same | The replacement `cleanupPending` path locally. The emulator tolerates repeat aborts (relevant to CS-006). |
| Android JVM: 353 (`jvm-353.json`) | same | JVM only. |
| Native instrumentation: 191 OK at `c8d852b`; 56 OK at `8ba9445` | as stated | Real Room/tag files, WorkManager and UI. Controllers use fake HTTPS peers. |
| Nine real host process kills (6 Forget boundaries, 3 historical-apply boundaries) | `8ba9445` / `437e61c` | Process death on the emulator, not power loss. |

**Evidence identity gap:** the evidence was produced at `8ba9445`, `c8d852b` and `437e61c`. The reviewed snapshot is `582f079`. This snapshot has no git metadata, so I could not confirm that production sources are unchanged between `437e61c` and `582f079`. The coordinator should diff `app/src/main`, `cloud/src` and `cloud/operator` across that range.

**Not covered by any test I found:** CS-001 (cross-chunk duplicates), CS-002 (refusal after shutdown), CS-003 (head race or expiry during automatic resume), CS-004 (Forget after revocation with a pending journal), CS-005 (playback-triggered syncs), CS-006 (provider-rejected repeat abort), CS-007 (ordinary ROTATING cancel through the router).

**Live evidence:** none for the corrected candidate. Earlier live results (two packages on one emulator, a 16-step matrix, the head-heavy p95 CPU sample of 6.950 ms) predate the corrections and are narrow.

**Remaining technical release gates (scope B):**
1. Fix CS-001, CS-002 and CS-003. Record owner dispositions for CS-004 through CS-009 and Astra's earlier deferrals.
2. Run the targeted regressions above against the actual failing boundaries, without weakening assertions or authority checks.
3. In a separately authorized isolated live cycle on the corrected candidate:
   - device-ZIP backup and native restore with AI paired;
   - protected replacement, including cancellation from every phase against real R2 (this settles CS-006);
   - generation A→B archive restore after operator rebuild and reopen;
   - revocation and re-pairing of a device with pending work.
4. A representative deployed CPU and resource sample that includes chunk, seal, publish and recovery verification, not only `/v1/head`. Also measure event-driven sync volume during normal playback (CS-005).
5. State the supported catalog size. By my rough estimate (about 400 bytes per saved item including hints) the deployed 64 KiB / 8-page profile holds only on the order of 150 saved items; this needs measuring. Note that the 1 MiB recovery cap excludes realistic device ZIPs.
6. Separate-device, longer-offline and Doze/OEM acceptance.
7. A decision on enabling housekeeping, which affects expired-stage self-healing.
8. Separate owner authorization for merge, deployment and activation.

---

## 5. Assessment of the eight Astra findings and their corrections

This section was written only after the independent pass above. Line references are to the reviewed snapshot.

| Astra ID | Correction present? | Assessment and residuals |
| --- | --- | --- |
| **001** Forget ordering | **Yes.** `CloudSyncController.kt:144-174`: OFF and a new generation first (154), Room witness deletion committed (157-160) before tag metadata (162-166), then binding (168) and connection (171). | Matches the safe reconnect ordering. A crash after the witness commit leaves the baseline without a witness, which the recovery predicate accepts. Repeats are idempotent. Six real kills support it. **Resolved.** Adjacent but distinct: CS-004 (Forget impossible after revocation with pending work). |
| **002** Old-generation archive restore | **Yes.** `restoreHistorical` (`CloudNativeApply.kt:32-38`); only the historical path may cross generations (`CloudApplyCompiler.kt:33-36`); v2 `historical-local` plan (`CloudApplyPlan.kt:67,98-109`); historical baseline with no acknowledged head (`CloudApplyStore.kt:42-91`); library-only check (`CloudRecoveryController.kt:269-271`); OFF afterwards (317). | Provenance and authority are now separate. The current `highest`, token and scope are preserved, and normal sync requires fresh enrollment. Native tests plus three historical kill boundaries support it. **Resolved locally.** The live A→B composition, including Worker-binding etag/version matching on rebuilt rows, is still a gate. |
| **003** Prepared staging inventory | **Yes.** Sealed inventory with size and SHA-256 per file; `expected()` replaces `Files.exists`; `verify()` before and after shutdown and before the swap; staging in `noBackupFilesDir` (`TagBackupCoordinator.kt:52-101,112,150-153,163-181,213-250`). | Fixes the substitution. **Residuals:** CS-009 (no sweep of the non-evictable staging) and CS-002 (the AI unpair still precedes the final cloud admission; the inventory check itself does run before shutdown). |
| **004** One-time restore from ON | **Yes.** OFF and a new generation persisted under the writer barrier before apply; admission asserts OFF (`CloudSyncController.kt:254-272`). | **Resolved.** A *failed* confirmation leaves sync OFF, which is acceptable and conservative. Related inconsistency: CS-008. |
| **005** Replacement cancellation budget | **Partially.** `BudgetError` is passed through accurately (`recovery.ts:27-31`); `cleanupPending` with an Android continuation of up to four calls (`replacement.ts:160-168`, `CloudReplacementController.kt:300-314`). | The replacement path now progresses truthfully *in the emulator*. **Residuals:** CS-006 (continuation depends on repeat-abort tolerance; the abort result isn't persisted) and CS-007 (ordinary recovery abort/abandon from ROTATING still exceeds the allowance with a misleading message). |
| **006** Terminal credential stop | **Yes.** `operation()` records the admitted generation (`CloudSyncController.kt:49-62`); `recordFailure` pauses only that generation, sets OFF with a new identity and reconciles WorkManager (`:503-524`). | Generation-conditional and keeps credentials and pending records. **Resolved.** Adjacent lifecycle gap: CS-004. |
| **007** Unavailable ordering | **Yes.** `CloudChangeExplorer.kt:36`. | **Resolved.** |
| **008** Hidden recovery filter | **Yes.** `CloudRecoveryDialog.kt:75,93-96` initialise and resync from the retained query. | **Resolved.** |

The corrections fix their stated invariants without weakening authority checks, statement limits or uncertainty fences. CS-009 is the only regression a correction introduced. CS-006 and CS-007 are incomplete coverage of Astra-005's problem class.

---

## 6. Subsystems inspected; limitations and uncertainties

**Read in full or substantially:**
- **Specs and architecture:** `doc/specs/cloud-sync-v1.md`, `doc/architecture/cloud-sync-v1.md`, `cloud-budgets-v1.md`, `cloud-operator-recovery.md`, `cloud/README.md`, `cloud/protocol/service-v1.md`, `recovery-v1.md`, `replacement-v1.md`.
- **Worker:** `cloud/src/index.ts`, `fence.ts`, `budget.ts`, `admission.ts`, `protocol.ts`, `service.ts`, `recovery.ts`, `recovery-format.ts`, `replacement.ts`, `replacement-state.ts`, `pages.ts`, `catalog.ts`, `housekeeping.ts`, `housekeeping-schedule.ts`, `maintenance.ts`.
- **Migrations:** DATA `0001`, `0002`, `0003`, `0006`; CONTROL `0001`, `0003`.
- **Operator:** `operator/worker.mjs` in full; `operator/recovery.mjs` lines 100-241.
- **Android cloud package:** every file in `custom/cloud/` except `CloudReplacementDialog.kt` and `CloudApplyBounds.kt`; `CloudChangePreviewDialog.kt` grepped only.
- **Tagging, library and AI:** `TagBackupCoordinator.kt`, `CatalogFileSwap.kt`, `TagRuntime.kt`, `CatalogTransferModel.kt`, `LocalPlaylistManager.java`, `CloudSyncSettingsFragment.kt`; relevant parts of `TagRepository.kt`, `TagStore.kt`, `HistoryRecordManager.java`, `AiControlRuntime.kt`.
- **Tests:** `cloud/test/fence.test.mjs`, `runtime-recovery-worker.mjs`, parts of `recovery.test.mjs`; `CloudSyncControllerTest.kt`, most of `CloudRecoveryControllerTest.kt`.
- **Prior review and evidence:** Astra `README.md`, `findings.md`, `implementer-adjudication.md`, `release-gates.md`, `architecture-assessment.md`; the correction README and logs (grepped).

**Not inspected, or only lightly:**
- `LibraryRepository`, `UnavailableRepository`, `LibraryBindingStore` internals.
- AI executors beyond their `TagRuntime.mutate` use.
- Operator `cli`, `provision`, `reopen`, `cloudflare` and `adapters` modules.
- Codec golden vectors and sequence tests.
- The Python host drivers.
- Astra's `coverage-matrix.md`.
- Earlier validation folders.

**Limitations:**
- Read-only review with no runtime, so every finding is a source inference.
- No git history, so the evidence-to-snapshot diff could not be verified. I did not recompute evidence hashes.
- Provider behaviour (exact D1 error strings, R2 repeat-abort semantics, Workers CPU for 8 MiB hashing) could not be checked. CS-006's confidence reflects this.
- The CS-005 budget figures and the catalog-size estimate in §4 are projections, not measurements.
- Absence of a finding in an area is not a certification of it.