# Cloud v1 daily admission budgets and bounded housekeeping

Status: implemented for the fenced DATA/CONTROL pair. Execution evidence belongs to the exact checkpoints under `doc/validation/`; this design does not grant production activation or large-catalog admission.

## Safety targets and scope

The project policy admits at most 2,000,000 estimated D1 rows read and 50,000 estimated rows written per UTC day, with a 250,000,000-byte DATA ceiling. Public requests, including operator and scheduled housekeeping, have a 32-SQL-statement ceiling. A deployed small public sample must separately demonstrate p95 Worker CPU at or below 8 ms. SQL limits count individual statements inside batches.

These are application safety targets. They are not an absolute billing cap: metadata arrives after a query; estimates can prove wrong; unlimited rejected requests still cost CONTROL work; authenticated emergency status/close can run above the daily target; and account activity outside these Workers is not controlled by this ledger. Shared-account usage must be inspected independently before live tests. No paid-plan change is implied. The current public deployment remains limited to eight pages / 64 KiB until a measured larger candidate is admitted.

A policy is scoped to the registered resource pair in external CONTROL. Additional resource pairs need an explicit aggregate allocation within the project target; allocating the full target independently to several active pairs is not a project-wide cap. Legacy pairs remain disabled and inspection-only.

## Durable admission and settlement

`control-migrations/0003_budgets.sql` adds reservations to the existing non-expiring request tickets and creates `cloud_budget`. Restoring DATA cannot reset CONTROL's spending, pending capacity, observed size, policy, or blocked state. The migration creates no policy automatically. New provisioning initializes it explicitly with measured schema-creation costs and DATA size.

Before the public DATA token lookup, one CONTROL transaction inserts the request ticket only when the fence is OPEN with the exact resource/library/generation and sufficient budget, then debits its reservation. Missing schema, missing policy, malformed estimates, invalid policy limits, a future ledger date, or unavailable CONTROL all refuse admission. Cheap origin/method/content-type/header checks and the existing per-location rate limiter run first and use no D1.

Concurrent requests compete in that transaction. `spent_reads/writes` includes completed charges and all outstanding holds; `held_reads/writes/growth` tracks the latter separately. Outstanding reservations have no deadline. On the first admission or settlement in a new UTC day, completed charges from earlier days fall out while every unresolved hold carries forward. A request spanning midnight is charged on settlement in the current day, without refunding its earlier day's reservation. This deliberately overcounts uncertain cross-day work.

Every DATA and operator CONTROL statement is wrapped. `first()` uses `all()` internally so rows read, rows written, and size metadata remain available. Index writes are included in D1's metadata. Unmetered execution methods are not exposed. Each normal request conservatively charges 128 reads / 32 writes for admission, settlement, indexes, and a possible policy trip; measurements separately verify that overhead. An ordinary denied admission debits 16 reads / 4 writes when a valid policy row exists, even if that takes spending above the target.

After all effects have definite outcomes, a second CONTROL transaction replaces the reservation with the measured DATA/CONTROL usage plus the accounting allowance, records the maximum observed DATA size, and deletes the matching ticket. A lost settlement reply is never blindly retried, so capacity cannot be refunded twice. A known assertion rollback releases its fence but charges the full route estimate because D1 did not return the failed batch's row metadata.

Unknown writes preserve both their request fence and full reservation. Missing metadata or an underestimated result blocks further ordinary admission and retains capacity. No timer, daily rollover, deployment, or DATA restore clears that state. An operator must establish a separately valid resolution; there is no force-clear endpoint.

## Bounds before execution

Estimates are internal route policies, never client-provided numbers. They require safe nonnegative integers and cannot exceed the configured project maxima. Catalog limits constrain the envelopes:

| Request family | Read reservation | Write reservation | DATA growth reservation |
| --- | ---: | ---: | ---: |
| Catalog/status/download reads | 512 | 32 | 0 |
| Catalog staging/publication | 1,024 + 32 × page limit | 256 + 12 × page limit | 128 KiB + 3 × catalog byte limit + 8 KiB × page limit |
| Recovery/replacement mutations | 1,024 + 32 × page limit | 256 + 12 × page limit | 256 KiB + 3 × catalog byte limit + 8 KiB × page limit |
| Housekeeping pass | 16,384 | 4,096 | 128 KiB |
| Authenticated recovery operator | 200,000 | 5,000 | 2 MiB |

Public statements reserve five positions for admission/settlement/failure accounting; an oversized batch is rejected before any of its statements dispatch. The separate recovery operator has a 48-statement ceiling, below the provider's Free per-invocation query limit; it is outside the 32-statement public proof. Its snapshots stop at 2,049 rows per table and reject more than 2,048, retain the existing 512 KiB per-table / 2 MiB request limits, and reject a rebuild whose conservative indexed-write estimate exceeds 5,000 before claiming an action.

Uploads require chunk count no greater than change count. Publication, retirement, and replacing an older stage transactionally reject oversized legacy child collections using bounded index scans. Sealing reads only the configured maximum changes plus one. Housekeeping uses the partial active-upload index and the existing unique active-backup/replacement indexes.

Size admission uses the latest maximum DATA `size_after` plus all concurrent growth holds. The observed high-water does not fall just because DATA was restored or rows were deleted. CONTROL size is metered as row work but is never mistaken for DATA size. A query can reveal an underestimated size only after execution; this is another reason not to present the ledger as an absolute storage or billing guarantee.

Authenticated operator state/close operations require the policy but may exceed the ordinary daily thresholds or operate while it is BLOCKED. They receive only CONTROL in their worker route and are still metered, so an exhausted application can be closed. Unknown provider restore calls retain their budget-only maintenance reservation as well as the existing exclusive action. Budget-only operator reservations never grant maintenance authority or public access.

## Housekeeping behavior and scheduling

`housekeep --scope /private/current-scope.json` invokes the authenticated operator endpoint. It verifies the deployed resource bindings and uses ordinary OPEN-fence admission. It cannot run through a HELD fence. The public Worker also exposes a scheduled handler, gated by `ENABLE_HOUSEKEEPING=true`. It receives no R2 capability and cannot delete or generate archives.

Each HTTP/scheduled pass visits at most two devices so the complete invocation fits the 32-statement ceiling. The reusable cleanup function permits at most four only when called with a separately appropriate envelope. A persistent cursor bounds device enumeration. Expired, revoked, or old-generation stages retire transactionally, preserving sequence high-water and exact terminal receipts. Oversized stages, active replacement work, and ambiguous backup operations are retained.

Receipt cleanup removes at most 64 per pass. It protects the newest sixteen sequence positions, uploads, and replacement references. A fixed eligible prefix is observed first and retained for thirty days; advancing the high-water cannot prematurely age newer receipts. Deleting a receipt never lowers the device's resolved sequence, so replay is still rejected.

Production scheduling is opt-in. Set both the explicit flag and a reviewed cron trigger in the exact deployment config; generated configs default to false and have no cron. An hourly trigger is an initial operational choice, not an enabled production schedule. Disable the flag/trigger when closing QA. Scheduled passes consume the same daily policy as app requests and stop when it is exhausted.

## Existing-resource upgrade and recovery

1. Verify exact DATA/CONTROL IDs, account, active deployments, and current private scope. Keep the public API flags and housekeeping flag false. Prove HELD with zero request tickets and running actions.
2. Preserve a DATA export/bookmark and the external action history privately. Read current account usage; allocate a conservative same-day starting debit for earlier project work, migration queries, and administrative inspection. Never initialize an existing day's spending to zero merely because this schema is new.
3. Apply only DATA `0006_housekeeping.sql` and CONTROL `0003_budgets.sql`, each transactionally, using explicit upgrade-only migration directories and a distinct tracking table when earlier raw provisioning has no normal migration history. The CONTROL migration itself rejects OPEN/DRAINING resources, pending requests, and unknown actions.
4. Inspect actual DATA `size_after` and initialize the single policy row explicitly. Do not overwrite an existing ledger or silently recreate a missing one. Preserve all action ordering/results, old device high-water, and archive identities.
5. Deploy the two exact isolated configurations with API flags still false. Verify missing-policy refusal and HELD housekeeping refusal, then use the existing guarded reopening procedure for a small approved QA cohort. Reopening does not enable API flags.
6. After a DATA restore predating migration 0006, reapply that DATA migration under HELD maintenance before recovery/reopening. Fenced operator inspection rejects missing housekeeping tables/indexes. CONTROL must never be restored alongside DATA.

CLI inspection, exports, migration tools, and direct administrative provider queries occur outside the Worker ledger. Their metadata/account analytics must be recorded and included in the operational starting debit or additional conservative allocation. They are not made free by using read-only credentials.

## Verification model

Local real D1 tests cover concurrent admission, rollover at both admission and settlement, DATA rewind, missing schema/policy/metadata, lost admission and settlement replies, unknown writes, indexed row costs, storage holds, statement ceilings, rollback accounting, operator close above the threshold, guarded schema upgrade, scheduled housekeeping, and retained oversized stages. A deployed proof separately checks actual metadata, small authenticated traffic, exhaustion/refusal, bounded cleanup, archive preservation, and Worker CPU. Local wall-clock measurements cannot establish deployed CPU acceptance.

References: [D1 result metadata](https://developers.cloudflare.com/d1/worker-api/return-object/), [prepared statements](https://developers.cloudflare.com/d1/worker-api/prepared-statements/), [transactional batches](https://developers.cloudflare.com/d1/worker-api/d1-database/), [D1 row pricing](https://developers.cloudflare.com/d1/platform/pricing/), [D1 metrics](https://developers.cloudflare.com/d1/observability/metrics-analytics/), [Workers metrics](https://developers.cloudflare.com/analytics/graphql-api/tutorials/querying-workers-metrics/).
