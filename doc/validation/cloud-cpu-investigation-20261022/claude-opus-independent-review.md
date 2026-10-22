I've finished the read-only trace. Below is my complete report for the coordinator.

---

# NewPipe Cloud & Sync v1: independent read-only investigation of the remaining acceptance gaps

**Snapshot:** frozen tracked-source archive at `0681b0497a36…`. The coordinator states its production code is identical to `4871f3d8…`.

**Method:** I only read files with read-only tools. I ran nothing, deployed nothing, queried no provider and changed no file. No pending test is claimed to pass.

**What the snapshot is missing, which limits some conclusions:**
- the Android `Application` class (`App.kt`), `TagRuntime` and `AndroidManifest.xml`
- the earlier-candidate CPU artifacts (`doc/validation/cloud-v1-candidate-20261021/`)
- the private raw Worker tail
- `node_modules`, so the pinned `@cloudflare/workers-types` can't be inspected

Statements about how Cloudflare or Android behave come from my general knowledge and are marked **[platform; verify]**.

## 1. Bottom line

1. **The measured CPU can't be coming from payload work.** Every live catalog had 3 videos, 1 playlist and 1 tag (`android-initial-safe-retry/results.json:13-15`). Archives were 2,657–6,972 bytes (`recovery-live-after-network/results.json:35,52,112`). Hashing, JSON, page parsing and fingerprinting cost microseconds at that size.
   - What remains is fixed per-request cost: binding round trips (D1, R2, rate limiter) and isolate/code-path coldness.
   - Tuning `sha256`/JSON will not move the 20.263 ms number.
2. **Every admitted request makes at least 4 binding calls:** 1 rate-limiter call plus 3 D1 round trips (CONTROL admission, DATA token lookup, CONTROL settlement).
   - Route totals run from **4 D1 round trips** (`/v1/pages`) to **13–14** (recovery advance/complete, replacement preview/publish), plus up to 5 R2 calls.
   - If each binding round trip costs roughly 1–1.5 ms of CPU, that reproduces p50≈8 ms / p95≈20 ms. This is a hypothesis, not a measurement.
3. **There are redundant round trips that can be removed safely:**
   - post-write re-reads sent as separate round trips (catalog and recovery routes)
   - a receipt lookup repeated 2× in publish and 3× in replacement publish
   - recovery `operation()`/`slot()` re-read 2–3× per request
   - a separate `canReplace` query on the most frequent route (`/v1/head`, `/v1/connect`)

   Folding them in removes roughly 40–55% of D1 round trips on heavy routes. No authority predicate, guard, fence or budget rule changes.
4. **Even after that, heavy routes may stay above 8 ms.** A simple illustrative model leaves recovery/replacement routes over target. The 1 MiB profile also adds hashing/copy CPU that the 198-request sample never exercised. Whether the gate passes will depend on how the "representative mix" is weighted, so predeclare the weights and report per route (AV-7 already requires per-route results).
5. **Provider analytics can't split CPU by route.** Observability is disabled in the provisioned config (`cloud/operator/provision.mjs:70`), and workers.dev has no customer-zone HTTP analytics. Route attribution needs per-invocation capture or strictly time-sliced windows.
6. **Recovery profiling must not run on the preserved pair.** All three slots are full, so the next reserve would rotate out and delete the oldest preserved archive. Also, the ledger's 32-write allowance caps a resource pair at about 1,560 admitted requests per UTC day.
7. **The verifyConflict logs don't show an ANR by themselves.** They show only `Process crashed.` before any test started. Both failures happened in the same post-kill state, so a startup failure tied to that state is at least as plausible as emulator overload. The deciding evidence may still be readable, without touching app data, from the emulator's process-exit and crash/ANR history.
8. **OPEN→HELD close: no code-level blocker.** Two traps for the operator: close returns HTTP 200 even while still `DRAINING`, and the next generation is fixed by the deployed operator SCOPE.

## 2. Worker CPU

### 2.1 Evidence-backed facts

| # | Fact | Source |
|---|---|---|
| E1 | Live data was tiny (3/1/1 catalogs; archives ≤ 6,972 B; 16,523 B total survivors) | `android-initial-safe-retry/results.json:13-15`; `recovery-live-after-network/results.json:35,52,112`; `recovery-archive-survivors.json:3` |
| E2 | 198 requests: p50 8,041 µs, p95 20,263 µs, p99 22,937 µs, `errors 0`, `subrequests 0`. The earlier 43-request window already had p95 16,616 µs | `worker-cpu-metrics.json:14-21`; `worker-cpu-early.json:14-21` |
| E3 | `subrequests 0`, even though every admitted request makes ≥3 D1 calls, so binding calls aren't counted there. Analytics can't be used to infer round-trip counts | same |
| E4 | The provisioned app Worker has `observability.enabled:false`, minified, compatibility date `2026-10-19`, no `nodejs_compat` | `provision.mjs:67-74`; `wrangler.jsonc:45-47` |
| E5 | Ledger arithmetic: 29,895 reads ≈ 198×128 + ~4.5k; 6,878 writes ≈ 198×32 + ~0.5k. That's an upper bound of ~23 rows read and ~3 written of real SQL per request. SQL work is light, and SQL runs inside D1, not the Worker | `qa-safety-shutdown.json:16-17`; `budget.ts:14,99-100,161` |
| E6 | Fixed path for every admitted request: limiter → CONTROL batch → token lookup → route → CONTROL batch in `finally` | `index.ts:88,93-96,187`; `budget.ts:84,152`; `service.ts:419-429` |
| E7 | The window is full of high-round-trip routes: 4 R2 backups, a guarded rotation, a protected replacement, restores | `recovery-live-after-network/results.json` (20 steps) |

### 2.2 Per-route round-trip census (static, from source)

D1 counts include the 3 fixed round trips and assume recovery and replacement are enabled, as in the final run. "Proposed" is the approximate count after the least-risk changes in F3–F7.

| Route | D1 RT now | R2 calls | Main redundancy | Proposed |
|---|---:|---:|---|---:|
| `head`/`connect` | 5 (4 with replacement off) | 0 | separate `canReplace` (`index.ts:146`) | 4 |
| `pages` | 4 | 0 | — | 4 |
| `uploads/status` | 6 (4 if a receipt exists) | 0 | split status/upload (`service.ts:150-155`) | 5 |
| `uploads/start` | 9 | 0 | separate pre-reads (`service.ts:196-200`) + post-write `requireUpload` (`:227`) | 6 |
| `uploads/chunk` | 8 | 0 | `requireUpload` before and after the write (`:232,:265`) | 5 |
| `uploads/seal` | 10 | 0 | `requireUpload` ×2 (`:269,:313`) + STATUS again in the read batch (`:275`) | 6 |
| `uploads/publish` | 7 | 0 | receipt lookup ×2 (`service.ts:323`, `admission.ts:105`) | 6 (5 with merged pre-read) |
| `uploads/retire` | 5 | 0 | — | 5 |
| `recovery/status` | 6–7 | ≤3 `head` | separate control/slots/op reads (`recovery.ts:132,147`) | 4 |
| `recovery/preview` | 8–9 | 1 `list` + ≤3 `head` | `head`s overlap the `list` (`recovery.ts:176-177`) | ~6 |
| `recovery/reserve` | 12 | 1 `list` + ≤3 `head` | own op ×2 + view re-read (`recovery.ts:207,268`) | ~8 |
| `recovery/advance` (RESERVED) | 14 | 1 create | `operation()`+`slot()` ×3 (`recovery.ts:306-308,335,346`) | ~6 |
| `recovery/advance` (ROTATING) | 13 | ~5 | ×2 + slots + control (`recovery.ts:306-313,329`) | ~8 |
| `recovery/part` | 11 | 1 | ×2 (`recovery.ts:372,405`) | ~6 |
| `recovery/complete` | 14 | 1–3 | ×3 including nested advance (`recovery.ts:408-416`) | ~6–7 |
| `recovery/advance` (VERIFYING) | 9–10 | 1 `get` | view re-read (`recovery.ts:454`) | ~6 |
| `recovery/pin` / `download` / `release` | 6 / 5 / 6 | 1 / 1 / 0 | control + slots separate | 5 / 4 / 5 |
| `replacement/preview` | 13–14 | 1 `list` + ≤3 `head` | canReplace + inspect + deviceState separate (`replacement.ts:64-66`) | ~9 |
| `replacement/publish` | 13 | 1 `head` | receipt lookup ×3 (`replacement.ts:95`; `service.ts:323`; `admission.ts:105`) | ~9 |

Phase A1 below verifies these counts locally with no production change.

### 2.3 Ranked CPU contributors

| Rank | Contributor | Where | Evidence class | Relevance to observed p95 |
|---|---|---|---|---|
| 1 | Number of binding round trips. The client-side work per call runs on the isolate: serialize, build the internal request, parse the JSON result, build row objects. **[platform; verify]** In workerd the D1 binding is a JS wrapper over an internal fetch | §2.2 sites | Counts are fact; CPU per round trip is hypothesis H1 | Probably first-order |
| 2 | Isolate/code-path coldness: lazy compilation and interpreted execution on rarely-run routes, after each deploy (every var flip creates a new version), and at sparse pacing | whole bundle; rare routes | Hypothesis H2 | Possibly first-order, especially in the tail |
| 3 | R2 metadata calls in recovery | `recovery.ts:115-124,137,176-177,315-320` | Counts are fact | Second-order, concentrated in heavy routes |
| 4 | Manifest re-parse and metadata re-stringify per slot check | `recovery.ts:26,100-114` | Fact, small | Minor (≲1 ms) |
| 5 | Seal validation at 64 KiB: `readPages` + `readCatalog` ×2 + `canonicalBytes` (two `TextEncoder.encode` calls per token) + JS surrogate scans | `service.ts:286-300`; `pages.ts:63-110`; `catalog.ts:32-42,116-143` | Hypothesis; not in the sample | Not in observed data; may matter at the admitted maximum |
| 6 | 1 MiB part/verify: body copy + SHA-256 + R2 send/read | `recovery.ts:383-397,418-436`; `recovery-format.ts:73-76` | Hypothesis; not in the sample | Not in sample; inherent integrity cost |
| 7 | JSON body read/decode/parse, token SHA-256, response stringify | `index.ts:40-58,32`; `service.ts:423` | Fact | Negligible at QA sizes |
| 8 | `requestEstimate`, fence Proxies, metadata validation, UUID | `budget.ts:34-51`; `fence.ts:46-104` | Fact | Microseconds |

**The functions you asked about, one by one:**
- **`requestEstimate`** (`budget.ts:34-44`): one array scan, ≤2 regex tests, and a key sort/join. Negligible.
- **`reserveBudget`** (`budget.ts:52-97`) and **`BudgetReservation.settle`** (`budget.ts:146-170`): one CONTROL round trip each. The SQL text is built per request, which only affects D1-side statement caching, not Worker CPU. Both are required; leave them alone.
- **`RequestFence.finish`** (`fence.ts:105-111`): a gate plus `settle`.
- **`authenticateToken`** (`service.ts:419-429`): one round trip plus one tiny digest. Merging it into a later query is a phase-2 option only.
- **Duplicate selects:** the real safe target (F3–F7).
- **D1 wrappers** (`fence.ts:59-83`): microseconds per statement. `first()`→`all()` is harmless because every `first()` call site is a single-row-by-key query.
- **`sha256`/JSON:** negligible at the observed sizes.

**Nothing found:** no hot loop that runs before the first I/O, no unbounded loop on any request path (all loops are capped by `PAGE_LIMITS`, `TRANSPORT_LIMITS` or `RECOVERY_LIMITS`), and no fingerprinting outside `seal`.

### 2.4 CPU time versus D1/network wall time

- **Counts as Worker CPU:**
  - JS execution, including lazy compilation and GC.
  - Native work done synchronously on the isolate: JSON, TextEncoder/Decoder, WebCrypto digest, URL parsing.
  - The client half of every binding call.
- **Does not count as Worker CPU:**
  - SQLite execution inside D1, and the network hop to the D1 primary.
  - R2 storage latency, rate-limiter latency, and waiting for upload bytes.

  Repo doc C8 states that awaiting D1/network is not CPU (`doc/architecture/cloud-sync-v1.md:256`).
- **What follows from that:**
  - Heavy SQL (AUTH joins, `EXISTS` guards, `json_extract`) costs D1 time and wall latency, not Worker CPU. The number of calls and the size of returned rows are what cost CPU.
  - `Promise.all` cuts wall time, not CPU.
  - Moving work into `waitUntil` doesn't reduce billed CPU **[platform; verify]**.
  - In-Worker `Date.now()`/`performance.now()` don't advance during CPU work **[platform; verify]**, so adding timers to production code can't measure CPU.
  - Fewer round trips improves both CPU and Android-side latency.

### 2.5 Illustrative projection (assumed, not measured)

Model: CPU ≈ 1.0 + 1.3·(D1 round trips) + 0.6·(R2 calls) ms. The constants were picked only to show that a per-call model can reproduce p50≈8 / p95≈20. Replace them with the phase-B fit.

| Route | Now | After F3–F7 |
|---|---:|---:|
| head | ~7.5 | ~6.2 |
| chunk | ~11.4 | ~7.5 |
| seal (3 videos) | ~14.0 | ~8.8 |
| recovery/status | ~11.3 | ~8.0 |
| recovery/complete | ~20.4 | ~10.7 |
| replacement/preview | ~20.3 | ~13.8 |

The point: consolidation could bring catalog sync near 8 ms, but explicit recovery/replacement routes probably stay above it. 64 KiB / 1 MiB payloads add to this.

## 3. Profiling plan (no production code change)

### 3.1 What Analytics can and can't tell you

- **Not confirmable from this snapshot:** the retained JSON is an ungrouped query (only `cpuTimeP50/95/99` and `sum{errors,requests,subrequests}`). It doesn't show which dimensions exist.
- **[platform; verify with GraphQL introspection]** `workersInvocationsAdaptive` has script, time, outcome, colo and version-type dimensions, but **no request path, method or HTTP-status dimension**. Its `status` is the invocation outcome, so app-level 4xx/5xx responses count as success.
- Quantiles are computed per result group and **can't be added up**. Per-minute p95s can't be combined into a window p95, so query each stratum's window ungrouped.
- The p95 of a small sample is unstable (with N=30 it is roughly the 2nd-highest value). Aim for N ≥ 100 per stratum.
- workers.dev isn't a customer zone, so zone HTTP analytics (which do have paths) aren't available, and they carry no Worker CPU anyway.
- A window counts every invocation, which biases percentiles downward:
  - scanner hits on workers.dev
  - 404/405/403 rejections, which happen before any binding call (`index.ts:85-87`)
  - 503 probes after the API was switched off
- A window also includes cold isolates after each deploy.
- The 12-request raw tail fits a tail session that was attached late or briefly **[platform]**.

### 3.2 Phase A — local, zero cloud cost

- **A1 – round-trip census.** Drive each route once per profile through `test/runtime-catalog-worker.mjs` and `test/runtime-recovery-worker.mjs`. Both already wrap the real `handleRequest` and count statements, batches and R2 calls (`runtime-catalog-worker.mjs:4-28,41`; `runtime-recovery-worker.mjs:20-45,71`).
  - Add a test-only CONTROL wrapper to confirm 2 CONTROL round trips per admitted request.
  - Profiles: 3 videos; 64 KiB / 8 pages (expect about 2 chunks); a 1 MiB device ZIP; a ~64 KiB cloud-catalog archive.
  - Output: the §2.2 table, measured.
- **A2 – pure-function microbenchmarks** in Node 24 with type stripping, as the tests already do (`service.test.mjs:57-60`).
  - Functions: `readChanges`, `readPages`, `readCatalog`, `canonicalBytes`/`fingerprint`, `pageManifest`, `uploadDigest`, `verifyCloudArchivePart`, `readRecoveryManifest`.
  - Measure cold (first call in a fresh process, and with `--jitless` as an interpreter-only stand-in) and warm. This bounds the payload terms H3.
- **A3 – optional:** a workerd CPU profile through Miniflare's inspector, to see the split between binding-wrapper time and app code. Relative shares only.
- **A4 – check whether per-invocation CPU is already available.**
  - Search the private raw tail JSON (kept local, not committed) for `cpuTime`/`wallTime`.
  - Search the pinned `node_modules/@cloudflare/workers-types` `TraceItem` type for the same fields.

### 3.3 Phase B — deployed, on a fresh synthetic profiling pair

Run this on a new pair, not the preserved one, for the reasons in F10.

**Setup:**
- Same commit, same minified build, same small-profile vars.
- No cron. Record the version ID for every window. No deploys during a window.
- Check account-wide D1 usage first.
- Pace at ≤1.5 requests/s per client IP; the limiter allows 120/min (`provision.mjs:74`).

**Capture, in order of preference:**
1. `wrangler tail --format json`, started before any traffic. Pipe it straight into a local filter that keeps only timestamp, method, URL path (origin and query stripped), status, outcome, `cpuTime` and `wallTime`, and drops headers, `cf` and IP. This only works if A4 shows those fields exist. Reconcile the event count per window against analytics `sum.requests`.
2. Workers Logs on the profiling Worker only (`observability.enabled=true`, `head_sampling_rate=1`). This is a config change, not code. Check the CPU/wall field names with a 5-request smoke test first, export only the projected fields, then disable.
3. A tiny tail-consumer Worker, or Logpush trace events where the plan allows it. More moving parts; use only if 1 and 2 aren't available.
4. Fallback: analytics windows where only one route runs. This works for L0–L4 below but can't give per-route p95 for mixed flows.

**Query sketch** (field and orderBy names to be confirmed by introspection; no secrets in it):
```graphql
query($a:String!,$s:String!,$f:Time!,$t:Time!){viewer{accounts(filter:{accountTag:$a}){
 workersInvocationsAdaptive(limit:100,filter:{scriptName:$s,datetime_geq:$f,datetime_leq:$t}){
  sum{requests errors} quantiles{cpuTimeP50 cpuTimeP90 cpuTimeP95 cpuTimeP99 cpuTimeP999}}}}}
```

**Strata.** Each layer gets its own window. Sizes: 3 videos, 64 KiB / 8 pages, and 1 MiB.

| Layer | Request | Bindings exercised | N | Ledger cost |
|---|---|---|---|---|
| L0 | API disabled → 503 (`index.ts:81`) | none (floor); safe even on the preserved pair | 100 | none |
| L1 | API on, malformed bearer → 401 (`index.ts:89-90`) | limiter only | 100 | none |
| L2 | Well-formed unknown token → 401 | limiter + 3 D1 | 100 | 32 writes each |
| L3 | head, replacement on and off | 4 / 5 D1 | 150 | yes |
| L4 | pages at 2 sizes | 4 D1 | 100 each | yes |
| L5 | catalog flows at 2 sizes: start → chunk(s) → seal → publish; start → retire; uploads/status | per §2.2 | ~40–60 cycles per size | yes |
| L6 | recovery flows at 7 KB / 64 KiB catalog / 1 MiB ZIP: preview → reserve → advance → part → complete → advance; rotation once slots fill; pin → download → release; abandon | per §2.2 | ~25 cycles | yes |
| L7 | replacement flows (respect the 60 s cooldown) | per §2.2 | ~5–10 | yes |
| L8 | Coldness: head every 60 s for 30 min, plus first-K vs rest within each burst | — | ~30 | yes |

Split L0–L5 and L6–L8 across two UTC days so the ledger isn't exhausted (F10).

**Analysis:**
- Per route: p50/p95/p99, plus D1 round trips, R2 calls and payload KiB.
- Fit cpu ≈ a + b·D1 + c·R2 + d·KiB.
- Coldness = how much higher the first-K and sparse-paced invocations run than the warm ones.
- Compute the predeclared-mix p95 from per-invocation data.
- Gate on **all invocations**. Report warm-only figures as a diagnostic, not as the gate number.

**Decision rules:**
- b ≳ 0.7 ms per round trip → implement F6 → F4 → F3 → F5.
- Coldness dominates → consolidation helps less; the owner has to decide the gate's pacing and mix.
- The KiB term matters at 64 KiB / 1 MiB → do F8.

## 4. verifyConflict and the "startup ANR"

**Facts from the retained files:**
- Both `05-b-verifyConflict.log` files contain only `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` / `INSTRUMENTATION_CODE: 0` (`android-conflict/05-b-verifyConflict.log:1-2`; `android-conflict-resumed/05-b-verifyConflict.log:1-2`). The process died before AndroidJUnitRunner reported the first test start.
- Compare `03-b-holdOfflineEdit.log:7-10`: the test starts, then the deliberate host force-stop happens.
- Nothing committed shows an ANR rather than a crash, a low-memory kill or an external force-stop. The "ANR" label came from material that isn't in this snapshot.

**State at failure, the same for both attempts:**
- peer b was force-stopped while holding `TagRuntime.lock` after a durable rename (`CloudLiveSyncTest.kt:341-361`; `run-live-android.py:89-139`)
- sync was enabled
- the remote had been advanced by a (step 4)

The earlier `initialize` ANR happened in a different state (a fresh install). It shows the emulator *can* fail at startup, but it isn't evidence that this particular state is benign.

**Relevant code:**
- After a force-stop, WorkManager reschedules unfinished work at the next process start **[platform]**.
- The immediate sync job is unique with `KEEP` and a 30 s exponential backoff (`CloudScheduler.kt:83-91`). The worker retries when it gets `CLOUD_OPERATION_BUSY` (`CloudScheduler.kt:118`), which comes from `operation()`'s `tryLock` (`CloudSyncController.kt:50-64`).
- So a sync job very likely started alongside the verifyConflict launch.
- `CloudNativeCatalog` refuses to run on the main thread (`CloudNativeCatalog.kt:69`), but `CloudConnectionStore.read()` takes `TagRuntime.lock` with no such guard (`CloudConnectionStore.kt:55-59`). If startup code (not in this snapshot) calls it on the main thread, the main thread waits behind any capture/apply.
- The verifyConflict assertion is read-only and safe to retry: it throws on `CONFLICT` before any local mutation (`CloudLiveSyncTest.kt:376-387`; `CloudSyncController.kt:382`).

**Possible causes and how to tell them apart.** All of these reads touch system diagnostics only, not app data:
- `adb -s emulator-5576 shell dumpsys activity exit-info org.schabi.newpipe.debug.qacloudaccept20261021.peerb` shows the recorded exit reason and its description. Interpretation:
  - ANR → read the main-thread stack: app frames (TagRuntime, Room, Keystore, WorkManager init) mean a product fault; class-loading/dex/timeout frames mean emulator resources.
  - CRASH → read the stack.
  - LOW_MEMORY → a memory kill.
  - USER_REQUESTED → an external force-stop.
- `adb shell dumpsys dropbox --print data_app_anr` and `--print data_app_crash` near the two failure times.
- These records may still be on the emulator now.

**Smallest restart-safe test on the phone** (needs explicit owner authorization; the earlier authenticated check was blocked by a tool safety control and must not be worked around):
1. Before and after, record `dumpsys package` (versionCode, lastUpdateTime) for the personal NewPipe and the existing QA packages, to prove they weren't touched.
2. Install two **fresh QA application IDs** (an a/peerb pair). Don't reuse the 213-test package, don't run `pm clear`, don't touch the personal library. Patch only the IDs and origin in the harness, as `qa-harness-only.patch` already does.
3. Point them at a pair with **only `ENABLE_CATALOG_API=true`** (recovery and replacement off, so R2 can't be touched). On a fresh pair, the committed driver runs unchanged: `--allow-phone --scenario initial`, then `--scenario conflict` (`run-live-android.py:141-161`).
4. Capture per step: `logcat -v threadtime` for the QA UIDs; `exit-info` after every step; `dumpsys jobscheduler` just before verifyConflict.
5. QA harness only, production unchanged:
   - add a no-op startup-probe step before verifyConflict, to separate "won't start" from "assertion fails"
   - wrap verifyConflict and automaticRemoteEdit in `CloudSyncController.operationLock(dir).withLock{}`, mirroring the recovery harness patch
6. If a step fails, diagnose first, then re-run only that step with the recorded expected fingerprints, like the earlier resumption. Run `resolveConflictToDevice` (the only destructive step; it writes a safety ZIP first) only once verifyConflict has passed.
7. If independent hardware is still required afterwards, add a two-serial driver variant (phone plus emulator). That's a separate harness-only change.

## 5. OPEN → HELD close readiness

- **Code:** `closeFence` moves OPEN→DRAINING only if the current `service_generation` differs from the next generation and the maintenance id has never been used (`maintenance.ts:17`). It moves DRAINING→HELD only if there are zero `cloud_fence_tickets` (`maintenance.ts:23`).
  - Operator-budget reservations live in `cloud_budget_maintenance` and don't block HELD (`budget.ts:56-63`).
  - The next generation is `scope.generation` from the deployed operator SCOPE (`operator/worker.mjs:82-87`). For a pair that has never been reopened, it differs from the active generation (`provision.mjs:33,41-42,61-62,72`). After any reopen, the two become equal (`reopen.mjs:65`), and close would then fail safely with `MAINTENANCE_CONFLICT`.
  - The CLI requires an explicit `--maintenance` id (`cli.mjs:66-68`). Retrying with the same id after a lost reply converges.
- **Checklist (each remote step needs its own authorization):**
  1. Read-only `state`: `OPEN`, `pending 0`, `running 0`, provenance `fresh-resources-v1`, `service_generation ≠ scope.generation`.
  2. Generate a new maintenance UUID and record it before sending.
  3. Run `close`. Success means the response shows `state=HELD`, the same `maintenance_id`, `next_generation=scope.generation`, and 0/0 pending/running. **A 200 that still says `DRAINING` is not success.**
  4. On a lost reply: run `state`, then re-run `close` with the same UUID. Never use a new UUID while the fence is DRAINING under another id.
  5. Re-attest the archives with read-only `inspect`: R2 key/size/etag/version/custom metadata, D1 `cloud_backup_slots`, and `recovery-archive-survivors.json`. Optionally hash the three objects (16.5 KB total). Confirm the bucket has no object-expiration lifecycle rule **[platform; verify]**.
  6. Both public endpoints should return 503 (L0 requests).
- **Trade-off:** once HELD, the pair can't be used for any live test until a guarded reopen with a new generation and fresh tokens. Until HELD, the archives are protected only by the API flags being off, not by fence exclusivity.

## 6. Numbered findings

**F1 — High (gate interpretation) — High confidence. The observed CPU is fixed per-request overhead, not payload work.**
- Evidence: E1, E2, E5, E6.
- Action: don't spend effort on `sha256`/JSON for the current number. Measure at the admitted sizes too: the current sample under-represents the 64 KiB and 1 MiB costs, so the true admitted-profile p95 may be higher than 20.263 ms.
- Don't: present any re-measurement on 3-video data as admitted-profile proof.

**F2 — High — census High, CPU-per-round-trip Medium. Round-trip multiplicity is the leading hypothesis.**
- Evidence: §2.2, against `index.ts:88-96,187`, `budget.ts:84,152` and the route code.
- Action: Phase A1 then B; fit the slope.
- Tests: the A1 census table becomes enforced assertions (see F3–F7).

**F3 — Medium — High confidence. Catalog routes re-read state after the write.**
- `requireUpload()` (status + upload, 2 round trips) runs before and after the write in start/chunk/seal (`service.ts:150-155,227,232,265,269,313`).
- Fix:
  - one joined query giving the authenticated status plus the LEFT-JOINed upload, keeping 401 `AUTH_OR_LIBRARY_UNAVAILABLE` vs 404 `UPLOAD_NOT_FOUND`
  - append `UPLOAD_SELECT` as the last statement of the guarded write batch, the same pattern `admission.ts:118-129` already uses
  - in `seal`, put the upload row into the existing read batch (`:274-278`)
- Reviewed change in behavior: if a device is revoked after the commit, the response now reports the committed result, matching how publish/retire already behave.
- Tests: per-route round-trip ceilings computed from harness stats (statements − batch statements + batches); error-code equivalence for 401/404/409/410; existing `beforeCommit`/`afterCommit`/`revokeBeforeCommit` faults; the malformed-chunk probes; `view()` response equality; JVM wire tests.

**F4 — Medium — High confidence. The same receipt lookup runs 2× in publish and 3× in replacement publish.**
- Locations: `service.ts:323`, `admission.ts:105`, `replacement.ts:95`.
- Fix: an internal path that passes the already-resolved high-water into `guardedPublication`. The guard inside the batch (`admission.ts:108-113`) remains the actual safety mechanism.
- Tests: `admission.test.mjs` lost-reply, network-before-commit and revoke-before-batch; concurrent publish/retire; "one lookup per request" from harness stats.

**F5 — Medium-High — High confidence. Recovery mutations re-read control/operation/slot 2–3× and rebuild each response from fresh reads.**
- Locations: `recovery.ts:54-79,306-347,372-405,408-416,454`.
- Fix: one AUTH-preserving joined read (control + own op + slot via explicit aliases, `ROTATING`→`evict_id`), plus trailing SELECTs inside each guarded batch.
- Tests: full `recovery.test.mjs` (three-slot rotation, create/complete uncertainty, bounded cancellation, repeated-abort shim, the `count+5<=32` checks at `:525,:555,:575`), plus new round-trip ceilings.

**F6 — Low-Medium — High confidence. `/v1/head` and `/v1/connect` run `canReplace` as a separate query.**
- Locations: `index.ts:146`, `replacement.ts:19-21`.
- Fix: add `CASE WHEN d.can_write=1 AND <REPLACE_GRANT> THEN 1 ELSE 0 END` as a column in `connectionStatus` (`service.ts:177-182`).
- Tests: extend `service.test.mjs:364-374` to replacement-on (2 DATA statements); capability arrays unchanged with and without a grant.

**F7 — Medium — Medium confidence (needs protocol owner). `/v1/recovery/status` costs as much as a mutation, and the client calls it repeatedly.**
- It does a D1 read plus an R2 `head` per READY slot (`recovery.ts:131-148`), and the client calls it several times per flow (`CloudRecoveryController.kt:86-115,196,212,239`).
- Safe step: batch the D1 reads.
- Option, after checking on real R2: replace the per-slot `head`s with a single `list({prefix, include:["customMetadata"]})`, reusing the `list` already done in preview/reserve. This trades Class B operations for one Class A operation; the cost difference is negligible here.
- Don't: drop the `usable` verification without a protocol decision.

**F8 — Medium (admitted profile) — Medium confidence. Payload-scale costs at 64 KiB / 1 MiB have never been measured.**
- Safe micro-optimizations, all with identical results:
  - encode each token once in `canonicalBytes` (`catalog.ts:119-123`)
  - skip the second `readCatalog` on an internal path where input is already validated
  - use `String.prototype.isWellFormed()` instead of the JS surrogate scan (`catalog.ts:34-40`; check V8 support in workerd)
  - use `indexOf(10)` and one `TextDecoder` in `verifyCloudArchivePart` (`recovery-format.ts:98-104`)
  - stringify `changes` once in `chunk` (`service.ts:231,233,254`)
  - memoize the per-row manifest/metadata (`recovery.ts:26,100-114`)
- Tests: the golden vectors (`app/src/test/resources/cloud/golden-v1.json`), compared against the Python oracle without regenerating it; catalog/protocol/pages tests; archive-verification unit tests covering split lines, a missing final newline and non-canonical lines.
- Do F8 only if the 64 KiB / 1 MiB strata show the KiB term matters.

**F9 — High (measurement validity) — High/Medium confidence. Analytics can't attribute by route, and window aggregates are biased.**
- See §3.1.
- Action: per-invocation capture; reconcile counts; no deploys during windows; record version IDs.

**F10 — High (safety) — High confidence. Profiling constraints.**
- Recovery reserve with three full slots rotates and deletes the oldest preserved archive (`recovery.ts:225-237,309-330`).
- Ledger cap: 50,000 writes ÷ 32 allowance ≈ **1,562 admitted requests per day per pair** (`budget.ts:10-14`). L0/L1 requests are free.
- Account-level D1 Free limits are shared across projects (`cloud-sync-v1.md:255`).

**F11 — High (decision) — Medium confidence. The mix definition decides the outcome.**
- See §2.5.
- Action: predeclare mix weights from the client's actual scheduling (`CloudScheduler.kt:69-90`, recovery flows), and report per route. Don't let polling volume mask mutation cost.

**F12 — Medium — Medium confidence. The verifyConflict evidence doesn't establish an ANR; a startup failure tied to the post-kill state is plausible.**
- See §4.
- Action: read `exit-info` and DropBox first.

**F13 — Low — Medium confidence. The 41 s automaticRemoteEdit step is plausibly a startup sync job colliding with the foreground operation.**
- automaticRemoteEdit took 41.187 s vs 12.793 s for the comparable step (`android-conflict/results.json:48`; `android-initial-safe-retry/results.json:42`).
- Plausible mechanism: a startup-rescheduled sync job hits `BUSY`, is retried with a 30 s backoff, and `KEEP` blocks a fresh enqueue (`CloudScheduler.kt:69-91,118`).
- Impact: latency only, not correctness. Relevant to test determinism; serialize in the harness (see §4 step 5).

**F14 — Low — High confidence. Close is ready: no code blocker.**
- See §5. Watch the DRAINING-200 response and the next-generation precondition.

## 7. What not to change

- Don't remove or relax:
  - the AUTH predicates in DATA queries
  - the token lookup
  - guarded `INSERT…COUNT(*)` assertions
  - receipts inside the batch
- Don't skip CONTROL admission for reads.
- Don't defer settlement to `waitUntil`, batch it across requests, or cache tickets or authority in isolate memory.
- Don't raise the 32-statement ceiling, the 128/32 allowance, or route estimates.
- Don't expose `raw`/`exec`/`session` through the fence wrapper (`fence.ts:70-71`).
- Don't use read replicas or Sessions.
- Don't remove R2 integrity verification (`head`, ranged `get` with SHA-256, ZIP checks) or the `usable` flag without a protocol decision.
- Don't treat `Promise.all` as a CPU fix. Some read orders are part of correctness (receipt before state).
- Don't measure with warm-up-only or polling-padded mixes.
- Don't add timing instrumentation to production.
- Don't run recovery profiling on the preserved pair.
- Never describe the 20.263 ms result as meeting 8 ms.

## 8. Recommended order of operations

1. **Read-only, now:** emulator `exit-info`/DropBox for the two verifyConflict failures; check the private tail and pinned `workers-types` for `cpuTime`/`wallTime`; operator `state` on the preserved pair.
2. **Phase A locally:** the round-trip census and payload microbenchmarks.
3. **With authorization:** close the preserved pair to HELD and re-attest (§5). Provision a fresh synthetic profiling pair.
4. **Phase B on the profiling pair** over two UTC days, with per-invocation capture and a predeclared mix.
5. **Fit, then fix only what the data justifies,** in this order: F6 → F4 → F3 → F5, then F8 if needed. Run `npm run check`, `npm test`, the JVM wire tests, and the new round-trip-ceiling tests. Redeploy the profiling pair and re-measure the affected strata and the mix (all invocations).
6. **Phone conflict run** on the profiling pair with catalog-only flags and fresh QA app IDs (§4).
7. **Close the profiling pair** (HELD), revoke its tokens, switch its flags off, and record every outcome as it actually happened.
