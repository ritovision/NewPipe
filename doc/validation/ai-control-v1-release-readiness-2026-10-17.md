# AI Control v1 — final integration and release record


> **Subsequent versioning decision (2026-10-17):** The schema 9 → 10 installation described below is historical. The owner explicitly made all experimental custom-app data disposable and adopted `NP9/RV1` / Room `9001` with **no old fork-10 migration**. See `rv-schema9001-clean-cutover-2026-10-17.md` for the new cutover. Keep the historical test record intact.

**Date:** October 17, 2026 EDT
**Baseline:** `custom/dev` at `b9a8f28d3` (completed Library polish)
**Source candidate:** `feature/ai-control` at `8147902d6`, incorporated in `integration/ai-control-v1-20261017`
**Scope:** Finished AI Control v1 without Tagging Mode. Tagging stays independent and is a later optional integration.

## Integration safety

- Git ancestry was explicitly checked: `custom/dev` is an ancestor of `feature/ai-control`, with 0 behind / 13 ahead before final documentation. Fast-forward integration is code-conflict-free.
- The old `custom/dev` checkout contained 6 preexisting modified/untracked historical AI planning documents. Each was preserved byte-for-byte and committed in the **local archival branch** `archive/ai-planning-wip-20261017` (`f4f0e726f`); the independent `feature/tagging` worktree was not modified.
- No tagging source, migration or branches are part of AI Control v1. Tagging's later migration integration must reconcile the AI receipt DB schema 10 rather than overwriting it.

## Automated final source validation

- Headless NotCodex-2 JDK 21 full Gradle run at the source candidate `8147902d6`: `:app:testDebugUnitTest`, `:app:runCheckstyle`, `:app:runKtlint`, `:app:assembleDebug` and `:app:assembleDebugAndroidTest` all **PASS**. **290/290 JVM tests**, 0 failures/errors/skips; 34 XML reports.
- The isolated integration branch compiled a 33 MiB APK and instrumentation APK. This intermediate **does not** have the main debug package ID: `org.schabi.newpipe.debug.integrationaicontrolv120261017`; never install it as a replacement for the main app.
- Gateway `npm run check`, `npm run build` and `npm test` **PASS: 45/45 tests** (4 test files). Wrangler `deploy --dry-run` PASS, without uploading a replacement Worker or changing production secrets.
- Independent Claude Opus high read-only final integration review: **PASS WITH NONBLOCKERS**, archived in `../reviews/ai-control-final-integration-claude-2026-10-17.md`. Reviewer highlighted package branch-derived identity, debug signer, one-way database upgrade and historical worktree WIP as mandatory safety checks before any install.
- Prior real ChatGPT plugin checks on separately paired QA Pixel verified device reads, playlist actions and playback control. Physical short screen-lock and foreground notification Stop/Start PASS. There is no proof of deep Doze, force-stop/reboot availability or long soak.

## Explicit owner LTE environmental waiver — NOT A PASS

Two physical handoff experiments failed: a Tailscale-connected Wi-Fi → LTE attempt had intermittent broad connectivity; a second test used **physical USB ADB with Tailscale disconnected** and all six LTE samples (+5 through +75 seconds) failed independent public-IP ping, public/DNS lookup and direct TCP connections to unrelated public hosts and the Cloudflare gateway. Android's own cellular network monitor logged `CELLULAR validation failed` and DNS timeouts, even though the radio displayed LTE connected. The AI Control foreground service remained active. Wi-Fi recovery and normal Wi-Fi-only AI Control reads worked.

On October 17 the owner explicitly accepted this as an **environmental limitation for v1** and asked to proceed assuming a working internet connection, without further work on LTE. **Mobility acceptance remains FAIL/WAIVED, not PASS.** There is not enough evidence to promise seamless mobile handoff, full Doze, cellular carrier reliability or indefinite background uptime. See the detailed `ai-control-physical-reliability-2026-10-17.md` and `ai-control-no-vpn-cellular-2026-10-17.md`.

## Main-app installation / data preservation gates

1. Build from the actual `custom/dev` branch **after** fast-forwarding. Android's debug package suffix depends on the branch: expected main ID `org.schabi.newpipe.debug.customdev`, not the QA/integration branch packages. Main versionCode is 1015.
2. Verify the main candidate APK is signed and the signing certificate matches the **already installed main customdev APK** before any in-place update. If it differs, stop; do not uninstall or clear application data as a workaround.
3. Preserve the current schema-9 Library via a user-visible export ZIP **off the phone** plus a copy of the installed APK before installing the schema-10 version. A newer backup cannot be imported into the old schema-9/F-Droid build; downgrade is unsupported. Take a pre-update Library and playlist count.
4. Never run `connectedAndroidTest` or destructive migration checks against the real customdev app on the Pixel. Only use the isolated `featureaicontrol` QA installation and synthetic data.
5. The main app has an independent device key and must be paired as a distinct device (suggested friendly name `Pixel 7 (main)`). Preserve old QA pairing until the main-device smoke test is completed. Explicitly configure the local capability ceiling; the default is read-only.
6. Do not change Cloudflare owner secrets, delete a user's real Library contents, revoke live clients or assume software installation is complete merely because the source and APK build succeed.

## Executed main-branch APK and data-protection preflight

- `custom/dev` was fast-forwarded locally to the reviewed integration head `6a9766d8f`. The worktree is clean, with Tagging Mode left in its own unchanged-by-this-task checkout. The original six historical AI spec drafts are retained separately at `archive/ai-planning-wip-20261017`, commit `f4f0e726f`.
- Actual main-branch JDK 21 `:app:assembleDebug` **PASS** (October 17). APK manifest verified: **`org.schabi.newpipe.debug.customdev`**, versionCode **1015**, versionName **0.29.1**. This is the correct existing custom-app update identity, **not** an integration/QA package.
- Main APK SHA-256: `e70e40420824c9606f4bd59904755ffcb869a54086e9afc323d71475ab3fa2f9`; release APK size 40,666,694 bytes. Built on NotCodex-2 and transferred to primary NotCodex through existing host-key-verified Tailscale SSH; the transferred bytes were verified against the original SHA-256.
- `apksigner verify` confirms main candidate signing certificate SHA-256 **`994ecd499bb2ae7e738f5992a47a3375b33f0e78a48e9462b9479c2e80b3ef7f`**, **exactly matching** the APK already installed on the physical Pixel. There is no signer mismatch and no need to uninstall the existing app.
- Primary NotCodex took a private **pre-upgrade schema-9 app-data tar snapshot**, including the complete databases with WAL sidecars, preferences, user files and no-backup data, while the main app process was not running, and pulled a separate copy of the original installed APK. Both archives were independently verified (tar listing and `apksigner`) and stored under the primary user's private `~/.cache/newpipe-ai-control/main-preupgrade-20261017/` directory with mode 0600. SQLite opened the local copy and verified `PRAGMA user_version=9`. Neither archive is committed or published.
- **At the pre-install preflight checkpoint:** The user-facing native NewPipe export ZIP, `adb install -r`, post-migration validation, main-device pairing and smoke testing were still pending. `adb install -r` and post-migration validation were subsequently completed; see below. The Pixel's lock screen prevented the native export UI at the preflight; **do not claim a completed installation or a verified migration until these steps actually run**.

## Executed physical main-app installation and database validation

- **2026-10-17 04:08 EDT:** On primary NotCodex only, Pixel 7 (wireless Tailscale ADB, explicitly selected physical device) accepted `adb install -r` of the verified main APK (`org.schabi.newpipe.debug.customdev`), returning **Success**; same versionCode 1015, installer reports updated `lastUpdateTime=2026-10-17 04:08:29`. The signer was previously verified equal to the installed original, and all pre-install private SHA-256 backup/archive checks passed. **No uninstall, clear, device data deletion, or stock/QA/tagging package modifications** were performed.
- The new main app process was launched; its live DB was read from a private on-host snapshot after opening. **SQLite `PRAGMA user_version` migrated successfully 9 → 10**, `PRAGMA quick_check=ok`, 16 total tables, and newly added `rv_ai_command_receipts` empty. A separate comparison of **every row and column in all 13 pre-existing noninternal application tables** against the host's pre-upgrade schema-9 snapshot found **no changes or missing tables**. This is stronger than count-only verification; only the expected AI receipt table and Room internal migration metadata differ. No user catalog rows or titles are committed.
- The original APK and a **verified complete preupgrade app-data tar** remain private on the primary machine, with restrictive permissions. These are not a NewPipe-native export ZIP, and a schema-10 app still cannot be downgraded to the old schema-9 build. No rollback has been attempted.
- Stock NewPipe, the isolated AI Control QA package, and the tagging demo package were present after install. The main AI Control instance **has not yet been paired, permission-configured, or started through its user-facing screen** because the phone screen remained locked during the verification. An owner one-time pairing code will be needed to enroll it as `Pixel 7 (main)` without confusing the existing QA device. The prior QA plugin tests do not prove that newly installed main-device identity's OAuth enrollment.
- The user-facing pre-upgrade native NewPipe ZIP export could not be obtained while the phone was locked; its absence was mitigated with the verified private full-data/schema-9 snapshot and retained signed original APK. No claim of a native ZIP is made.

## Release status

**AI Control v1 integration is complete on `custom/dev`, with fresh host tests, independent review, signed APK build, safe in-place Pixel installation, and verified schema-10 migration with identical pre-existing application rows.** The LTE mobility gate is owner-accepted but remains FAILED. **Main-device Cloudflare enrollment, UI-based smoke checks and user-visible backup ZIP are still pending unlock/setup.** No tagging changes are included.
