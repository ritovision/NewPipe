# NewPipe Cloud & Sync v1 — production scalability risk acceptance

Date: 2026-10-22
Status: **Scalability NOT accepted. Owner has nevertheless requested a production launch with these limitations disclosed.** This document is not performance certification, production enablement, or permission to bypass the existing loss-prevention and operational barriers. Record the actual production release/feature state separately.

## Verified baseline (not conjecture)

- The implemented catalog stores compact, content-addressed D1 pages, with bounded changes and staged/chunked uploads, exact revision/epoch guards, conflict refusals and final atomic publication. It avoids one SQL row per playlist occurrence. These design choices have plausible scaling benefits; there is no evidence requiring a whole-system rewrite.
- Local Miniflare/workerd tests cover 20,000 saved streams, 100,000 playlist memberships and 20,000 tag assignments, and validate portable contents after initial publication, removal and download. They **do not** establish real Cloudflare Free CPU, account-wide quotas, Android memory or end-to-end wall-clock acceptance at that size.
- The actual deployed, minified **small profile** had Cloudflare Worker CPU p95 **6.885 ms**, p99 **9.630 ms** over **313 sampled requests**, zero Worker execution errors (2026-10-22). The acceptance target was p95 at or below 8 ms and was met **for that mixed test profile only**. It does not authorize larger payloads by extrapolation.
- The QA deployment admitted **eight pages / 65,536 catalog bytes**. A 600-item backend-only synthetic catalog occupied **61,885 bytes** and was accepted, but the equivalent Android native catalog added video display hints (titles/uploader/duration/type/thumbnails) and **could not publish**: the application recorded `CLOUD_SERVER_LIBRARY_LIMIT` and cloud revision remained 5. A reviewed, smaller 120-item/14,762-byte dataset supported a full **8-step** real Pixel 7 two-installation conflict test, including explicit reconciliation and native safety backup. The size failure was a correctly enforced admission cap, **not a data-loss incident**.
- Both Android and server support **up to 2,048 catalog pages / 32 MiB** at the codec boundary, but available bounds are not deployed performance evidence. The source default is 512 KiB/128 pages whereas the demonstrated QA production-like admission is just 64 KiB/eight pages. Each HTTP request remains limited to 64 KiB and must be chunked.
- Cloudflare Workers Free has a nominal **10 ms CPU budget per HTTP request**, distinct from elapsed network latency. The account also hosts unrelated apps; D1 quotas are shared at account level. Internal CONTROL accounting is an admission safeguard, not an absolute Cloudflare usage/billing cap.

## Specific suspected failure modes, ranked

| Rank | Issue | Evidence / mechanism | What is at risk |
| --- | --- | --- | --- |
| P0 | **Native payload exceeds catalog admission** | Actual 600-item Android publish rejected above 64 KiB while the backend-only representation fitted | Sync becomes unavailable for ordinary libraries, though accepted remote data remains unchanged |
| P0 | **Larger-catalog CPU exhaustion on Free** | p95 6.885 ms for small mixed workload; larger seal/download/fingerprint and page validation are not deployed-measured | CPU exceptions, transient 503/unknown outcomes, upload stalls, retries |
| P1 | **Full-catalog work for small changes** | Android captures/validates snapshots, page packing and fingerprinting; server seal validates the resulting full bounded catalog | CPU and Android latency grow with library size even when only a handful of pages change |
| P1 | **Display-hint storage/transfer amplification** | Native metadata adds extra per-video page entries even though hints do not affect the semantic fingerprint | More pages, more changed bytes, metadata churn and 64 KiB request/chunk overhead |
| P1 | **D1/CONTROL quota reservation overhead** | Every request uses external-fence and conservative read/write/growth reservations; budgets account for extra SQL/FKs/indexes | Free daily write/read caps, conservative admission refusals and lower throughput under repeated polling |
| P1 | **Android full-catalog memory/time cost** | 20K scale passed in local backend fixtures, not on physical device with real media metadata | Memory pressure, long restores, larger backups, background process interruption |
| P1 | **R2 backup/restore large-scale behavior** | Three small real READY archives and live recovery exercised; large native ZIPs/multipart, rotation and full restore at realistic size not measured | Long-running transfers, failure recovery complexity and storage costs |
| P2 | **Large ordered playlists/rebalancing** | Sparse rank packing and changed-page plans tested on representative local cases, but repeated pathological edits not endurance-tested | Extra page rewrites, higher latency/write amplification |

## What we predict, not what we measured

Small collections are likely to work within configured admission; **hundreds** of items might already exceed 64 KiB if titles/URLs/artwork/hints are long. **Thousands** will be refused under this exact admission unless size is raised or hints optimized. A higher limit may unmask CPU and daily D1 budget bottlenecks; no numeric video-count cutoff, p95, or paywall requirement is guaranteed without a real native profile. We cannot equate the successful 20K-item local test with a 20K-item production Free-tier pass.

## Minimum requirements for a scale-supported release (deferred by owner)

1. Build real Android metadata fixtures at 600 / 2,000 / 5,000 / 20,000 items, preserving realistic video URLs, titles, thumbnails, duplicate playlist occurrences and tags; measure exact page counts, metadata share, and bytes.
2. Identify and reduce unnecessary hint churn and total-catalog encoding; preserve semantic fingerprints, deterministic packing, atomic revisions and conflict behavior. Do not simply silence oversize errors or remove loss-prevention checks.
3. At each candidate admission limit, benchmark deployed p50/p95/p99 CPU and end-to-end app time separately for head, page download, small edit, large deletion, seal/publish, backup/recovery and retries; measure D1 read/write rows and CONTROL reservations.
4. Establish an acceptable safe library-size policy with explicit blocking/error UI and conservative budgets. Stop at the first Free-plan CPU/quota bottleneck; consider a Workers Paid plan only with explicit cost authorization.
5. Finish independent-device/background/Doze/OEM proof, full provider-unknown/reopen and **formal external operator maintenance closure**; a disabled Worker and revoked credentials are not the same as a `HELD` fence.

## Production-launch boundaries and evidence

The owner requested release despite unaccepted scaling. That changes prioritization, **not** the truthfulness of acceptance evidence. Any limited release must truthfully identify the admitted bytes/pages, report oversize refusal rather than claiming all libraries work, and preserve data when rejected or when cloud authority fails. Do not silently increase the quota or pretend that an unproven maximum is supported. Do not connect an existing user's personal library, overwrite app data, or activate a stale Worker config without valid DATA+CONTROL bindings, separately provisioned operator credentials, and a recoverable device-enrollment path.

The checked-in `cloud/wrangler.jsonc` is an obsolete inspection-only config: its `newpipe-cloud-sync-v1` D1 target was intentionally deleted and it lacks required CONTROL/FENCE bindings. Never redeploy it as production. The prior 2026-10-21 and 2026-10-22 isolated QA D1 DATA/CONTROL pairs were intentionally deleted at the owner's express request after their test evidence had been committed; the associated QA Workers and R2 objects have not been reclassified as production. Production, if enabled, must use **new, dedicated** resource identities, not resurrect deleted test data or bind to Music.

Evidence: `doc/validation/cloud-cpu-qa-20261022/README.md`, `doc/validation/cloud-conflict-android-20261022/README.md`, `doc/architecture/cloud-budgets-v1.md`, `cloud/src/pages.ts`, `cloud/src/service.ts`, and `app/src/main/java/org/schabi/newpipe/custom/cloud/CloudPages.kt`.
