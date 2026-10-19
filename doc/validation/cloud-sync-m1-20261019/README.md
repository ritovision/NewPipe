# Cloud M1 foundation — executed checkpoint

**Date:** 2026-10-19. **Tested source:** `2f15bcaf6221a95705a7379be14d98b0105f60e6`.
**Status:** First implementation foundation passed; **not the entire M1 and not completed live synchronization**.

## Executed evidence

| Check | Result | Evidence |
| --- | --- | --- |
| TypeScript strict type check | Passed (`npm run check`) | Exact source/lockfile in candidate commit |
| Node/Worker tests | **111/111 passed**; includes 28 actual local-workerd D1 admission/race tests | [Node output](node-tests.log) |
| Full existing + new app JVM suite | **322/322 passed**, 38 suites, no skipped tests; includes 8 new cloud groups | [JVM summary](jvm-results.json), [cloud XML](jvm-cloud-results.xml) |
| Debug app + instrumentation builds | Passed on NotCodex-2 using JDK 21 | [Build log](android-build.log) |
| Kotlin formatting / project style pipeline | Targeted formatter completed successfully; project's runKtlint/runCheckstyle tasks reported up-to-date in final build | [Scoped formatter](scoped-format.log), [build log](android-build.log) |
| Actual Android instrumentation | **24/24 passed**: 8 new cloud groups + 16 existing backup/restore regressions, 21.805 seconds | [Native output](native-tests-r1.log) |
| APK identity and byte-for-byte transfer | SHA-256 verified before install; separate feature QA app/test package | [Candidate manifest](candidate.json), [app install](install-app.log), [test install](install-tests.log) |

Native execution used primary **NotCodex**, `emulator-5574`, existing `Pixel_3a_API_34`, Android API 34. Target package: `org.schabi.newpipe.debug.featurecloudsyncv120261019`; test package adds `.test`. No physical phone was selected. The other active emulator serials 5576 and 5590 were not stopped/reused. Our emulator was shut down after the tests without deleting its AVD or userdata. Two temporary primary-host APK transport copies were deleted after hash verification and successful execution; the build host retains only the current app/test output pair.

## What the tests establish

Independent synthetic golden data agrees between the Python oracle, JVM Kotlin, Android Kotlin and TypeScript for the explicit fixtures. It preserves canonical order, duplicate ordinary memberships, exact deletion scope, stable rename identities, legacy-name cases, tag/category effects and full diff details. Shared policy vectors cover disabled operation without a remote head, enrollment, conflicting divergence, equal-content rebasing, epoch/rollback detection, unresolved operations and decimal counters above JavaScript's safe integer range. Preview tests reject changed local/cloud/candidate identity, generation, action, incarnation and expiry.

The D1 tests execute the actual admission implementation **inside Cloudflare's local workerd runtime**, including missing/NULL assertions, auth revocation racing preflight, concurrent duplicate commits, competing devices, changed request digests, late domain failure rollback, lost acknowledgments, unknown outcomes, permanent sequence retirement, receipt expiration, gaps, row revision overflow and signed 64-bit precision. This is local D1 behavior, not deployed per-account CPU/quota evidence. No resource was provisioned and no real Cloudflare catalog was mutated.

The native backup tests exercise existing Room/tag/binding migration, ZIP validation, staged restore, fault rollback and writer barriers in isolated test contexts. They do **not** prove a new cloud native-apply controller or new OS process-kill recovery; neither was implemented at this checkpoint.

## Scale observation, not a performance acceptance claim

Synthetic input: **20,000 streams, 100,000 ordinary playlist occurrences and 20,000 tag assignments**. Both encoders produced **6,402,769 canonical bytes**. The full Android exercise (validation, encoding twice including hash, then exact diff) took **9,848 ms** on the 2-core/2 GiB emulator; see [scale log](native-scale.log). This is not a ZIP/R2 size estimate, a main-thread budget, phone benchmark or Free Worker CPU result. The implementation must keep such work off the UI thread and measure actual incremental/chunked workload before live rollout. The fixture was never uploaded to D1.

## Corrections made while testing

- First Android build could not see the shared Kotlin test helper through Java source directories. Corrected to AGP built-in Kotlin source directories; no unrelated build migration.
- Second JVM run exposed NanoJSON's null-object convenience accessor in the **test loader**. Explicit nullable object casts fixed the fixture reader; policy behavior then passed all vectors.
- Miniflare's Node D1 proxy smoke stalled. The test harness instead runs the real functions and D1 binding inside workerd via dispatchFetch. No mock substituted for the D1 proof.
- The TypeScript 7 compiler API is not the old transpileModule API. Test-only source stripping uses Node's built-in helper, with an independent strict tsc check. Earlier failed logs remain in build-host `.work/cloud-m1/`; final executed evidence is linked above.

## Remaining work and authority

Still required: the real catalog physical schema and large staged-delta publication; deployed account/CPU/row admission; native projection and stable ID mapping; protected binding admission; durable Room/tag/map apply and actual process-death tests; complete native/AI writer capture; secure connection credentials/settings; the visual change explorer; authenticated Worker transport; three-slot R2 recovery; true two-device testing; independent implementation review; release approval.

This candidate has no live endpoint, HTTP SQL API, new Room/tag migration, production settings toggle or R2 implementation. `cloud/test/runtime-worker.mjs` is a local-only synthetic SQL test harness and must never be deployed. The existing AI gateway and custom/dev remain untouched. Claude's **design** review was adjudicated; no second Claude review or implementation approval is claimed here.

See [machine-readable results](results.json), [adjudication](../../reviews/cloud-sync-design-adjudication-2026-10-19.md), and [implementation plan](../../architecture/cloud-sync-implementation-plan.md).

Repository log copies normalize CRLF to LF and trim trailing whitespace only. Original unmodified host logs and their hashes are preserved; `results.json` records both original-log and stored-evidence hashes. No test result or assertion text was edited.
