# RitoVision Room schema versioning

**Accepted 2026-10-17:** Canonical upstream NewPipe schema and fork additions have separate versions. NP9 is official schema `9`; RV1 is our first fork extension. Current combined version is **NP9/RV1**, SQLite/Room integer **9001** (`upstream * 1000 + fork revision`).

When upstream advances to NP10, a possible future fork revision is NP10/RV2 = 10002. Fork revisions increase monotonically and do not reset with upstream updates. The actual upstream schema changes still need integration; namespacing only avoids ambiguous numbers.

The source of truth is `RitoVisionDatabaseVersion`. The AI command receipt remains in the **same Room database and transaction** as Library changes, preserving crash-atomic catalog mutations plus receipts.

## Explicit owner-authorized clean break

All experimental custom app data was declared disposable. No `10 → 9001` migration is implemented or required. Cut over by clearing only the selected `org.schabi.newpipe.debug.customdev` app package's data, then installing/launching the namespaced build. Do not clear the unrelated stock/F-Droid NewPipe package or change the separate Tagging worktree.

The existing canonical **upstream NP9 → NP9/RV1** migration (`9 → 9001`) remains registered for upstream NewPipe 9 imports. This is not a migration of the discarded experimental fork-10 data. Backups are accepted only from registered upstream versions 1–9 or the exact current 9001; do not treat all numbers under 9001 as valid. Explicitly reject experimental version 10, unknown gaps, and future schemas without support.

Room's generated schema JSON must be tracked as `app/schemas/org.schabi.newpipe.database.AppDatabase/9001.json`. Remove former experimental `10.json` from the active source tree; Git history retains it.

## Future version bumps

Reconcile actual upstream changes with our entity and migration definitions, increment the RitoVision revision monotonically, and implement versioned upgrades only for database states we deliberately support. Current disposable cutover has no legacy fork migration.

Tagging has its own separate `SQLiteOpenHelper` version. No tagging code belongs in this change.
