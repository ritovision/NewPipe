# Bounded housekeeping checkpoint — 2026-10-21

Status: **implemented and locally executed; not deployed or live verified**. Builds on `ac2fa9dc52273f74e736729286303e251d425662`. Existing uncommitted work was inspected and privately preserved before this checkpoint. No external model review was used.

The authenticated operator `housekeep --scope scope.json` uses `/maintenance/housekeep` and an ordinary external request fence. HELD maintenance refuses cleanup. A persistent cursor inspects at most four devices per call. Expired, revoked or old-generation uploads are retired in a guarded transaction. Incoming replacement stages, ambiguous backup operations and oversized legacy stages are retained. This code receives no R2 capability and cannot delete archives.

Receipt cleanup deletes at most 64 rows per call, keeps the newest 16 sequences, and waits 30 days from first observation of a fixed eligible prefix. References from uploads and replacement attempts pin their receipts. Device sequence high-water marks survive pruning, so deletion never authorizes replay.

## Executed checks

- `npm run check`: passed.
- `npm test`: **332 tests passed; zero failures, cancellations or skips** on primary NotCodex.
- The preceding focused housekeeping/operator integration log records **47/47 passed**, including 17 housekeeping tests.
- A 10,000-receipt fixture measured **9 SQL statements, 332 rows read and 65 rows written** for one cleanup pass. This is local D1/workerd evidence, not a deployed CPU or universal row-cost guarantee.
- `git diff --check`: passed.

The full suite covers transactional retirement, delayed publication races, lost replies retaining the external fence, active-work protection, exact 64-bit retention arithmetic, rotating device windows, missing-schema rejection and bounded large receipt history.

## Live state and remaining work

No deployment or live migration was performed for this checkpoint. Isolated QA remains as previously closed: public APIs disabled, fence HELD, old QA credentials revoked, and three archives preserved. DATA migration `0006_housekeeping.sql` still needs an explicit transactional upgrade on the existing QA resources; do not replay initial migrations.

Daily usage budgets, budget-aware statement admission, isolated live housekeeping validation and an explicit housekeeping scheduling decision remain. This checkpoint does not claim release acceptance.

The prior Android blocker was incomplete: Windows on primary NotCodex has a running `Pixel_9_API_35` emulator reachable through Windows ADB even though WSL's separate ADB server reports none. Native verification is being resumed separately; it is not part of the backend result above.
