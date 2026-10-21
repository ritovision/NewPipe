# Daily budgets: local implementation checkpoint — 2026-10-21

## Executed

- TypeScript `npm run check`: PASS.
- Full real local D1/R2 backend suite, `node --test --test-concurrency=1 test/*.test.mjs`: **358/358 passed**, zero failed/skipped, 345.007 seconds.
- Final review added a more conservative 8 KiB per configured page allowance for staging keys/indexes, including many tiny chunks. The focused budget suite then passed **20/20** in 9.114 seconds. The full-suite log predates that final conservative allowance increase; the focused rerun uses it.

`backend.log`, `growth-review.log`, and their SHA-256 hashes in `checkpoint.json` preserve the results. Sequential execution avoids competing local workerd instances on the shared build host.

## Changes covered

The external CONTROL ledger reserves daily reads/writes and DATA growth before token authentication. Reservations account for concurrent requests and UTC rollover, survive DATA rewind, and remain held for unknown writes. Wrappers meter `first/all/run/batch`, include indexed writes and CONTROL costs, cap statements before dispatch, and refuse missing policy/schema/metadata. Operator recovery is separately metered; authenticated status/close remains available at an exhausted/blocked policy and still charges its work. Scheduled housekeeping is opt-in, receives no R2 capability, and uses ordinary admission.

The suite covers migration refusal/rollback, atomic reservation competition, admission/settlement reply loss, retained uncertainty across days, DATA size holds, rollback accounting, bounded cleanup, post-restore schema requirements, and oversized legacy stages. One initial full run found a lost-reply replacement path exceeding the SQL ceiling. Combining its authenticated attempt lookup removed the redundant query; its retry and archive/epoch invariants passed in the 119-test focused correction run and the complete 358-test run.

A measured local sample used four CONTROL statements, 23 rows read and six written, within the conservative 128-read/32-write accounting allowance. Actual DATA metadata is charged in addition. This is not deployed CPU or provider billing verification.

See [design and operational limits](../../architecture/cloud-budgets-v1.md). At this checkpoint no budget/housekeeping migration or deployment has been applied to live QA. Its verified preflight remains HELD revision 10, zero pending/running, all API flags false, all three credentials revoked, and three READY archives totaling 4,995 bytes. Live validation is a separate next checkpoint.
