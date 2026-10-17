# RitoVision NP9/RV1 clean-break schema cutover

**Date:** 2026-10-17 EDT
**Source baseline:** `custom/dev` at `47ce2f5c1`
**Review:** Prior full AI Control v1 independent review passed; a new targeted Claude review was requested but Claude reported a session limit (reset later). Never claim this review passed.
**Owner authorization:** Explicitly stated all experimental app data is disposable; requested immediate cutover with **no migration from our old fork-local schema 10**. Do not wipe any unrelated stock/F-Droid NewPipe or the independently developed Tagging branch.

## Code and version contract

- Canonical NewPipe schema 9 is named `NP9`, unmodified by the fork namespace.
- RitoVision's first extension is `NP9/RV1`, Room integer `9001` (`UPSTREAM_SCHEMA * 1000 + FORK_REVISION`).
- The AI receipt entity remains in the same Room database and atomic mutation/receipt transaction; current 9001 schema has the same 13 entity definitions and Room identity hash as the previously generated experimental fork-10 Room schema; only the physical version changes.
- Registered upstream `9 → 9001` migration remains for official upstream database/backup compatibility. There is **no experimental fork `10 → 9001` migration**; installed disposable customdev app data must be reset before starting the new version.
- ZIP import rejects the superseded fork-10 header, unrecognized integer gaps, and unimplemented future fork/upstream versions before replacing the current database files.
- Room's generated `9001.json` replaces the discarded active `10.json` schema file. The Git history keeps older evidence intact.

## Executed source checks

- Headless JDK 21 Gradle: `:app:testDebugUnitTest`, `:app:runCheckstyle`, `:app:runKtlint`, `:app:assembleDebug` and `:app:assembleDebugAndroidTest` all **PASS** at this feature branch's source candidate.
- **295/295 JVM tests PASS** (35 XML reports; 0 failed/errors/skipped), including new backup version whitelist and namespace tests.
- Room schema generator produced `9001.json`, confirmed `version:9001`, with the exact same 13 entity definitions and identity hash as the prior fork schema. No unrelated tables altered.
- Physical Pixel 7 instrumentation: **54/54 PASS**, zero failures, using the separately installed `org.schabi.newpipe.debug.featurervschemanamespacing20261017` QA APK and tests on synthetic data. This includes `AiDatabaseMigrationTest` (canonical upstream NP9 -> 9001) and `AiCatalogExecutorTest` (atomic receipt and catalog invariants). On the first attempt, the test APK lacked generated `9001.json` assets, so its single migration test failed due to a **missing test fixture**, not a database migration failure. Repacking the Android test APK **after** Room generated the schema and rerunning yielded all 54 test cases passing. Source validation remains 295/295 JVM tests, style checks, APK and instrumentation builds PASS.
- The compiled **main custom/dev** APK was also built and transferred with verified SHA-256, matching the installed app package and signer: `org.schabi.newpipe.debug.customdev` (1015), SHA-256 `3a37cad3f3b2dacc12d22f2c854fc39d547da65123aa6f16bc97f4bb86dc9062`. Stored privately on primary NotCodex at `~/.cache/newpipe-ai-control/rv-schema9001-20261017/NewPipe-customdev-NP9-RV1-9001.apk`.
- Actual main-package `pm clear` was **attempted but blocked by platform safety checks**. It did **not** run and the main app was **not** updated. The user was instructed to use Android Settings -> Apps -> NewPipe custom/dev -> Storage & cache -> Clear storage. A later read-only ADB check confirmed the main app still had its old database at this checkpoint. Once the owner clears it through Android Settings, the verified new APK can be installed without migrating any historical fork data.


## Device cutover boundary

Only `org.schabi.newpipe.debug.customdev` is authorized for data clearing here, once the new main-branch APK has been built with matching package ID and signer. Use `adb shell pm clear org.schabi.newpipe.debug.customdev` only with an explicit selected Pixel, and never run it against `org.schabi.newpipe`, the tagging demo, or another unrelated app. A full old-data migration is expressly omitted.

After the reset, launch the updated main app and verify SQLite `PRAGMA user_version=9001`, `PRAGMA quick_check=ok`, presence of `rv_ai_command_receipts`, and fresh initialized Library behavior. The separate QA phone pairing may continue to refer to the historical build; it is not a migrated main-device identity.
