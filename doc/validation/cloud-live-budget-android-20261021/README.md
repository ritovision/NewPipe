# Live budgets, cleanup and two-installation Android acceptance — 2026-10-21

## Executed functional evidence

The budget implementation at `a9b89e5dd` was deployed only to the isolated fenced QA pair after transactional CONTROL 0003 / DATA 0006 upgrades. The public disabled deployment was `50472855-c758-46a5-a572-c36d1426be85`; operator `b14ec1e6-ca51-48f1-b8ee-d2220b335290`; catalog-only activation `4168f0da-fd49-48b0-9a79-8d1f8d1ba31c`. Recovery/replacement API flags and automatic housekeeping stayed disabled. No legacy resources, physical phone, paid plan, merge or production activation were used.

Missing-policy admission was refused live. A guarded, one-time policy insertion included a conservative prior/admin charge of 100,000 reads and 5,000 writes; no spending, unknown reservation or request ticket was reset. HELD housekeeping was refused. The existing operator safely reopened the recovered generation with three fresh QA devices while preserving the three old archives.

Thirty public HTTP checks and six bounded operator cleanup passes verified token rejection, active-stage retention, explicit retirement, terminal-stage cleanup, preserved sequence high-water, temporary-budget exhaustion refusal before a new stage, continued accounting for that rejection, restoration of the original policy ceiling without refunding spending, and all three READY archive identities/digests remaining unchanged. All reservations and operator holds were zero afterward. The conservative ledger charge rose from 100,530/5,061 to 105,304/6,208 read/write rows; these are application charges including overhead allowances, not a claim of exact provider billing.

The two independently sandboxed Android packages on Windows `Pixel_9_API_35`, `emulator-5576`, both passed all **16 instrumented live steps** at harness source `702f99f9f`: joining the existing catalog, automatic publication/pull, duplicate membership and Unavailable/tag propagation, real emulator Wi-Fi/data outage, local edits surviving fresh-process restart, queued WorkManager work while offline, automatic convergence after networking returned, conflicting branches preserved, explicit reconciliation with a local safety ZIP, disabled local edits, one-time restore remaining disabled, and both connections stopped. Original emulator networking was restored and transient plaintext QA credentials were removed. This is two Android installations on one emulator, not two physical devices or a Doze proof. APK hashes and individual logs are retained here.

## Resource gate still failed at this checkpoint

Cloudflare GraphQL reported **30 requests, zero Worker errors, p95 CPU 12.181 ms** (p50 3.509 ms; p99 13.780 ms) for the public budget-check interval. Schema introspection confirmed microsecond units. This exceeds the **8 ms** target. Functional success is therefore **not resource/release acceptance**. `cpu-initial.json` preserves the failed measurement rather than replacing it with a later sample. Follow-up query consolidation and a new deployed measurement are required.

This checkpoint also does not claim the pending theme/rotation/credential UI checks, broader physical-device/Doze behavior, large-catalog deployed CPU, or final release authorization. Production remains disabled; isolated QA closure is recorded separately after the remaining tests.
