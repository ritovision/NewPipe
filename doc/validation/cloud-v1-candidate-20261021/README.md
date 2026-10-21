# Cloud & Sync v1 — tested candidate checkpoint, 2026-10-21

Status: the missing daily budgets and bounded housekeeping are implemented and live-verified; Android reconnect, offline/conflict, UI and regression coverage has executed. This is a **production-disabled, small-profile candidate**, not unrestricted rollout approval. The branch remains `feature/cloud-sync-v1-20261019`; no merge, physical-phone installation, paid-plan change, or legacy-resource activation occurred.

Code checkpoint: `3f49b6acf7aef6905d07871f9bec679e707264fd`. Backend polling change: `0f41d70b0`. Dialog/security fixes and minified provisioning: `c6619b57d`. Earlier live harness source and APK identities remain in the separate live checkpoint below; tests are not represented as all occurring in one binary or invocation.

## Executed evidence

| Verification | Result | Evidence |
| --- | --- | --- |
| Full backend suite, real local D1/R2 | 359 passed; 0 failed/skipped | `backend-359.log` |
| Android JVM suite | 351 passed across 45 suites; 0 failures/errors/skips | `jvm-results.json` |
| Native Android regression | 157 passed; 0 failed/skipped; 266.813 seconds | `native-157.log` |
| Real light/dark/black settings UI | 3 passed; 0 failed/skipped; 70.334 seconds | `ui-three-themes.log`, `ui/` |
| Two-installation live HTTPS matrix | 16 steps passed | [live Android evidence](../cloud-live-budget-android-20261021/README.md) |
| Real reconnection process-kill/restart boundaries | 5 passed, previously executed | [reconnection evidence](../cloud-reconnect-process-death-20261021/README.md) |
| Live budget, token accounting and bounded cleanup | 30 public HTTP checks and 6 operator passes passed on the final minified sample | `budget-functional.json` |
| Deployed public CPU target | p95 **6.950 ms**, target <=8 ms; 30 requests, zero Worker errors | `cpu-minified.json` |
| Final isolated shutdown and archive verification | HELD revision 15; six revoked credentials; three unchanged archives | `final-qa-state.json`, `final-deployed-preflight.json` |

The 34 controller cases, including nine reconnection cases, are a subset of the 157 native regression tests, not an additional 34 to add to that total. The five host-driven process-death scenarios are separate from exception-injection cases. App/test APK build success is separate from test execution. `tests-and-apks.json` records current APK sizes and SHA-256 hashes; `evidence-sha256.json` binds the retained evidence.

### What live Android acceptance covered

Two separately sandboxed QA packages on Windows `Pixel_9_API_35`, serial `emulator-5576`, Android user 0 joined the retained accepted catalog without deleting native content. They exercised automatic push/pull; duplicate memberships, tags and Unavailable; an actual emulator Wi-Fi/data outage; local edits surviving a fresh-process restart while offline; queued WorkManager work; automatic convergence after networking returned; conflicting branches preserved until an explicit preview; a real local safety ZIP before reconciliation; disabled local edits; and one-time restore leaving synchronization off. Both connections ended disabled and transient plaintext QA credential files were removed. Original emulator networking was restored.

This is **two installations on one emulator**, not two independent hardware devices, a Doze/OEM test, or power-loss durability. Full live backup/restore/replacement scenarios were recorded at earlier exact checkpoints; the new daily ledger's complete R2 mutation path is covered locally, while this live continuation enabled only catalog traffic and authenticated operator recovery. It does not silently recertify every earlier live path on the new deployment.

### Live budget/cleanup proof

Missing policy refused before DATA token lookup. A HELD fence refused housekeeping. Explicit policy initialization used a conservative 100,000-read / 5,000-write starting debit for earlier project activity, upgrades and administrative QA, not a fabricated zero-cost day.

The live pass authenticated or rejected tokens under the ledger, preserved an active upload, explicitly retired it, removed the terminal stage with bounded cleanup, preserved its sequence high-water, refused a new stage after a guarded temporary reduction of the read ceiling, and restored the original ceiling without refunding spending. All request reservations and operator holds settled to zero. Three existing archive keys/digests/lengths remained unchanged. Neither housekeeping nor ordinary sync performed archive deletion or creation.

The spending figures are conservative application charges with overhead allowances, **not exact provider billing or an absolute account cap**. Unlimited rejected requests, emergency operator control and unrelated account activity remain outside such a guarantee. The tested public profile is still eight packed pages / 64 KiB; no larger deployment is admitted by this sample. Scheduled cleanup is implemented and locally tested, but no cron was activated.

## Findings, corrections and reruns

**CPU:** the first deployed 30-request sample failed at p95 12.181 ms. Consolidating polling from token auth plus four DATA reads to token auth plus one authenticated indexed snapshot reduced a subsequent sample to 8.692 ms, still failing. Minifying the same public implementation reduced the measured sample to 6.950 ms, with p50 2.843 ms and p99 8.208 ms. Cloudflare's schema explicitly described these metrics in microseconds. The earlier failed measurements are retained in the previous live checkpoint and `cpu-optimized.json`; they are not overwritten or counted as passes. This is an empirical small-sample p95 result, not a promise that every invocation stays below 8 ms.

**UI:** real landscape testing found that a recreated host could temporarily provide its previous-orientation frame, clipping confirmation buttons. The shared dialog sizing now bounds itself to the current display and repeats the fit after layout. Credential screenshot protection is applied before the first visible frame rather than waiting for a show callback. UI tests confirm cancellation and rotation do not mutate the library or enable sync, retain the preview identity/query, clear a dismissed credential editor, and keep password fields out of saved state. Raw budget codes now have actionable messages that preserve pending-work uncertainty. The connection dialog no longer suggests the retired legacy Worker.

**Harness findings:** the initial theme harness omitted the app's normal Day/Night setup and did not wait for a resumed, focused replacement activity. Both were corrected; no product theme rewrite was made. The full native run initially passed 154 tests and failed three while cache-based synthetic stores disappeared, including an explicit `SQLITE_READONLY_DBMOVED` error; an earlier screenshot directory also vanished. The emulator had only 437 MiB free under `/data`. Moving synthetic stores and evidence out of evictable cache, while keeping per-fixture cleanup, produced the complete 157/157 pass. No application data was cleared, no other project's storage was deleted, and no product identity/transaction assertion was weakened to make the tests pass. Failed-run log hashes are retained in `review-findings.json`.

## Final operational state

Public Worker `newpipe-fence-qa-20261020`, operator `newpipe-fence-qa-20261020-op`; DATA `7fdf0708-4d39-4efb-946c-567109a6422b`; CONTROL `9e60001d-359c-481e-8b1d-eaf2058f2f42`. Both required upgrades are present. The latest canonical private files point to the closed generation, not the earlier open QA configuration.

- Fence: **HELD**, revision **15**, zero pending requests/running actions/operator holds.
- All six old and temporary QA credentials are revoked and non-writable.
- Catalog, recovery, replacement and scheduled-housekeeping flags are all false. Public `/v1/head` returns HTTP 503 `CLOUD_API_DISABLED`.
- Three READY archives remain, totaling **4,995 bytes**, matching their original keys/digests/lengths; no incomplete multipart upload remains.
- Active fence generation: `424c2b81-be62-4062-a407-04c86c49ec71`.
- Next/deployed/recovered generation: `86d99758-90af-45e8-a9de-f4a2e97613b2`.
- Maintenance: `29ecbbcd-da2c-49a6-9c60-3927ce326d3f`.
- The operator's guarded final rebuild committed with digest `8f83f4f2807106377a2824ba379dfdeac583e3248d71bcfe9dba21f21f0a6ec2` and preserved the catalog accepted by live Android.
- CONTROL budget policy remains ACTIVE with original ceilings and zero holds. Recorded conservative charges are 123,134 reads / 10,487 writes; DATA size high-water is 245,760 bytes.

The post-rebuild inspector reports only `FRESH_EXTERNAL_GENERATION_REQUIRED`, the expected result of asking for a new rebuild against the already-recovered generation. It is not permission to reopen or bypass the maintenance barrier. A future QA cycle must use the existing guarded reopening workflow, fresh credentials and a separately authorized deployment; never reset CONTROL or reuse revoked test credentials.

Current private directory: `/home/ritorhymes/.cache/newpipe-operations-20261021/`. Canonical names are `next-scope.json`, `application-closed.jsonc`, `operator-next.jsonc`, and `next-cycle.json`. `application-budget-open.completed-20261021.jsonc` is historical and must not be deployed. Exports, sealed operator plans and plaintext enrollment credentials remain private and are not included here.

## Final self-review and remaining release gates

The implementation self-review checked admission before token SQL, durable reservation/settlement transactions, rollover and unresolved-write retention, policy failure closure, SQL/growth envelopes, ordinary-fence housekeeping, preservation of replay high-water, final transactional authorization despite combined read queries, operator-only emergency control, and the native preview/credential changes. The executed tests above support these scoped checks. This is **not an independent whole-feature review**; no Claude or coding subagent was launched.

Before broad rollout: complete independent whole-feature review; validate real separate-device and longer background/Doze/OEM behavior; admit any catalog larger than the eight-page / 64 KiB deployed proof using measured limits; and rerun the applicable full live backup/restore/replacement paths against the selected integrated rollout candidate. Operator scheduling remains opt-in. Merge, production activation and physical-phone installation still require explicit authorization. These limits are not reasons to repeat completed reconnect, budget or two-installation tests without a relevant code change.
