# Cloud & Sync v1 — targeted CPU investigation and corrections

**2026-10-22 · Scope: only the Cloud Worker on `feature/cloud-sync-v1-20261019`.** This checkpoint continues [final live acceptance](../cloud-live-final-20261021/README.md), not a replacement review cycle. **Technical release verdict remains HOLD** until representative deployed Worker CPU p95 ≤8 ms, the missing live conflict assertion and the external maintenance close are demonstrated. Nothing in this work authorizes merging, altering app data, reopening legacy QA, or deploying to production.

## Independent investigation and corroboration

[Claude Opus Max's independent read-only review](claude-opus-independent-review.md) traced the frozen previous code at `0681b0497a36aefcfcfe013938657bf2ca5ac535`, using only Read/Grep/Glob tools on a credential-free archive. It **did not code, run tests, or access Cloudflare**. Its principal useful finding is that the previous deployed sample contained only tiny catalogs and 2–7 KiB archives, making per-request D1/R2 binding overhead a stronger hypothesis than payload hashing for that specific sample. It identified duplicated D1 reads on catalog polling, publication and recovery, and the need to separate each endpoint before further optimization.

The coordinator separately inspected the actual code, checked the provider schema using authorized read-only GraphQL introspection, queried the original 198-request period without altering any cloud resource, and ran a local workerd Inspector profiler. **This adjudication does not adopt Claude's illustrative CPU-per-D1-call estimates as measurements.** All SQL authorizations, external fencing, receipts, budgets, and 32-statement request guards remain unchanged.

### Exact Cloudflare measurement boundary

| Original deployed Worker window | CPU measurements | Interpretation |
| --- | --- | --- |
| 198 mixed requests, 2026-10-21 16:26–17:33 UTC | p50 **8.041 ms**, p95 **20.263 ms**, p99 **22.937 ms** | **Fails ≤8 ms p95 goal.** Original version, not the changes here |
| 21 requests around 16:50 UTC | p50 **9.26 ms**, p95 **18.14 ms** | Original Android initialization/upload/pull activity |
| 21 requests around 17:08 UTC | p50 **14.1 ms**, p95 **20.26 ms** | Recovery initialization and native ZIP upload activity |
| 32 requests around 17:24 UTC | p50 **6.89 ms**, p95 **22.94 ms** | Protected replacement and restore activity |
| Sparse single-request minute bins | ~0.5–1.9 ms | Some invocations are cheap; avoid calling every request intrinsically slow |

[Cloudflare minute-bin analytics](cloudflare-cpu-by-minute-readonly.json) has 32 bins and totals 198 requests. Quantiles per minute **cannot be averaged or combined** into a valid overall p95. The provider's `workersInvocationsAdaptive` schema has script/version/outcome/time dimensions but **no request path or route dimension**. Attribution above is chronological and approximate, not an individual invocation-to-route join. The retained raw Worker tail contained client IP/headers and is deliberately not committed. The previous mixed p95 remains the official observed failure until a properly stratified deployed rerun establishes otherwise.

## Two small CPU-directed changes

1. **Exact canonical UTF-8 counting** (`cloud/src/catalog.ts`): `canonicalBytes()` originally UTF-8 encoded the token and then encoded its entire length-prefixed string again solely to calculate size. The corrected algorithm encodes each token once and adds the ASCII decimal-prefix length, colon and comma numerically. The *actual serialized part, total UTF-8 bytes, cross-language canonical fingerprint and hash are unchanged*. Added a regression with multibyte characters (`é`, a non-BMP musical symbol, emoji, CJK). Byte-for-byte verified against independent golden/catalog oracles and 13 synthetic+golden fixtures up to **59,522 bytes**. Pure Node benchmark measured **32.6% local canonical-encoding improvement** at 59,522 bytes (original 7.652 ms vs new 5.155 ms). This helps admitted larger catalogs; **it does not explain or guarantee improvement of the original 3-video deployed CPU sample**. See [the JSON benchmark](catalog-encoding-benchmark.json).
2. **Remove safe D1 read duplicates**, without altering security predicates:
   - `cloud/src/index.ts` + `cloud/src/service.ts`: `/v1/connect` and `/v1/head` resolve `catalog.replace` permission using `CASE WHEN d.can_write=1 AND <EXISTS grant> THEN 1 ELSE 0 END` in the **existing authenticated** status + pending-operation snapshot, rather than a separate `canReplace` query. The final replacement transaction still independently checks grant and authorization. Tests assert authorized/read-only/revoked/no-grant/recovery-disabled capability behavior, unchanged wire shape and only **two DATA statements** on each header route.
   - `cloud/src/service.ts`: an ordinary *new* sealed upload now reads its stage first, relying on the **existing** `guardedCommit` authenticated sequence/high-water/receipt preflight. Only when the staged upload is gone (replay/retirement/race) does it resolve the immutable receipt separately. The guard, transactional receipt, authenticated preflight and unknown-outcome resolution all remain intact. The targeted test verifies one fewer DATA read on a new publish, replay returns the identical committed receipt, and there is still exactly one receipt row.

These are bounded optimizations, **not** the broad F3/F5 restructuring Claude proposed. The riskier recovery, operator or budget transaction code was intentionally left unchanged pending per-route CPU attribution. The protected R2 backups were not rotated or mutated. No external scripts have been redeployed.

### Regression and local profiling

The full backend `node --test test/*.test.mjs` includes D1/Miniflare end-to-end, budget admission and settlement, hostile upload sequencing, lost replies, protected replacement, R2 rotations and operator-recovery logic. The TypeScript `npm run check` result and final suite summary/hashes are in [validation-summary.json](validation-summary.json).

The Miniflare/workerd inspector trials exercised 48 synthetic invocations per read route and 18 synthetic start/chunk/seal/publish cycles, both unminified and minified. Repeated CPU hits appeared in `cloudflare-internal:d1-api` result JSON conversion and dispatch and not in one runaway application loop. See [baseline](local-workerd-cpu-profiles.json), [minified](local-workerd-cpu-profiles-minified.json), and [synthetic mutations](local-workerd-cpu-profiles-mutations.json). These local workerd wall times include local D1/Miniflare waits and are **not** Cloudflare's deployed CPU p95; no raw scratch inspector/IP data is part of the repository. The private 8-page/64-KiB profile run overlapped other Node backend tests and is **not** offered as an uncontended benchmark.

## Claude findings adjudicated

| Claude concern | Disposition |
| --- | --- |
| D1 binding round trips likely dominate tiny requests | **Plausible, not proven quantitatively.** Local Inspector repeatedly sampled D1 result serialization. A route-stratified deployed measurement remains required |
| Separate replacement-grant lookup on connect/head | **Corrected** by one AUTH-preserving joined snapshot. Two-DATA-statement regression added |
| Duplicate normal-publication receipt preflight | **Corrected** for new sealed catalog publications; the guarded preflight and receipt resolution remain intact |
| Additional catalog stage rereads and recovery multi-round-trip paths | **Supported source inefficiencies, pending targeted profiling**. Not removed wholesale before their concurrency/failure assertions can be accounted for |
| `canonicalBytes` redundant encoding | **Corrected** as exact-wire micro-optimization. Not claimed to solve prior tiny-payload p95 |
| Worker CPU cannot be attributed to URL path through existing Analytics dimensions | **Confirmed through direct schema introspection.** Use short isolated route windows or appropriately sanitized per-invocation traces in a newly authorized synthetic environment |
| Emulator `verifyConflict` startup failures | **Still inconclusive for product correctness.** Retained instrumentation logs show `Process crashed.` before the assertion; prior session's contemporaneous system logcat also reported Android startup ANR, but full ANR thread data were not retained. Do not infer memory alone or call test passed |
| External fence close | **Still OPEN**, not HELD. Public QA APIs OFF; zero outstanding tickets; 3 READY R2 archives. Do not bypass the prior tool safety block or use this pair for performance tests that would rotate archives |

## Next bounded gates

1. When an explicitly authorized **fresh** synthetic profiling pair and suitable account-wide D1 budget are available, predeclare route proportions, enable per-invocation sanitized CPU capture or isolate each route in an Analytics time window, and measure **unminified vs minified corrected candidates under the same admitted profile**. Include real `status`, `chunk`, `seal`, `publish`, `recovery status/advance/verify`, cancellation and replacement; do not pad with inexpensive polls. A full performance PASS needs deployed p95 **≤8 ms** for the agreed representative mix and no unexplained holds.
2. If heavy routes still fail, apply only the next justified AUTH-preserving read consolidation (catalog stage read, repeated internal recovery reads). Add per-route read/round-trip ceiling, race and lost-reply regression tests, then repeat the deployed measurement.
3. Finish the **live** two-package/physical-phone conflict proof without touching personal NewPipe data, and perform separately authorized external `OPEN → DRAINING → HELD` fence closure with verified R2 inventory. No workaround for connector safety restrictions.
4. Reuse prior accepted 8/8 sync, 20/20 recovery and 213/213 Pixel 7 native tests; no new broad independent code-review loop unless a substantive new release risk is introduced.

**Current status: targeted source changes are locally verified, but Cloudflare deployed CPU and final release acceptance are not signed off.**
