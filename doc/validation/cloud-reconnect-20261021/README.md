# Android recovery reconnection checkpoint — 2026-10-21

Status: **implemented and built; native runtime validation remains blocked**. This extends server checkpoint `9d22549011ac090f40791c86e53f5d92d7b614e7`. The feature is not release-ready. Work was performed directly without another coding agent or external model review.

## Behavior

An existing connection now offers **Reconnect after cloud recovery**. It uses the existing HTTPS origin and accepts a new device token only for the same library in a different service generation. The remote catalog must be initialized, the device sequence must be zero, and there must be no remote upload or replacement. Same-generation, other-library and reused-device credentials fail before local changes.

Reconnection saves the verified local safety backup using the existing backup workflow, then rechecks the local catalog. It keeps videos, playlists, tags, native IDs and portable identity mappings. Old catalog outbox, recovery and replacement files move into a private retired-connection directory rather than being deleted or sent with new credentials. The old synchronization baseline is cleared; sync remains off until a new preview is explicitly confirmed.

The transition is recoverable across interruption:

1. Disable sync and atomically save an encrypted transition record before moving pending files or changing native metadata.
2. Block ordinary cloud actions while that record exists. The UI offers **Finish reconnecting**; resumption does not require another network call or plaintext token entry.
3. Move each old transfer directory independently, with replay/conflict checks.
4. Commit removal of the main database apply witness **before** clearing its tag-database baseline. An interruption between those commits leaves a valid old baseline without a witness, avoiding a false lost-journal state.
5. Change only the local cloud service scope, preserving identity mappings and incarnation; clear the old baseline; validate the native catalog again.
6. Atomically install the fresh encrypted connection, still disabled, then remove the transition record. Replaying after credential commit is safe.

The credential dialog keeps autofill/state saving disabled, uses the existing secure-window protection and scrolls on short screens. Pending reconnection disables conflicting cloud actions.

## Executed checks

- Kotlin/style compilation passed for app and instrumentation sources: `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.
- Cloud JVM unit tests: **34 passed, zero failures/errors/skips**.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed.
- `git diff --check` passed.
- The preceding server checkpoint passed **313 backend tests** and live isolated reopening/closeout. Server code was not changed by this Android checkpoint.

Nine added instrumentation tests compile. They cover local content/ID preservation with old pending catalog, recovery and replacement files; fresh-preview gating; rejected scope/device/empty-library credentials; edits during the safety backup; and interrupted transitions after journal, partial archival, witness, metadata and credential commits. The interruption tests recreate storage/controller handles before resuming and verify that ordinary apply recovery remains valid.

**These nine native tests were not executed.** Primary NotCodex currently reports no ADB devices, has no installed emulator and no `/dev/kvm`. A read-only check of NotCodex-2 returned an internal error. No physical phone, owner installation, app-data clear or unrelated emulator was used. Native persistence/encryption execution, actual process-death probes, and UI/theme/rotation verification remain required before release acceptance.

[checkpoint.json](checkpoint.json) records source hashes, successful build commands, JVM suite results, the unexecuted native cases and the current app/test APK hashes. The current build outputs remain in the repository build directory; no APK history is added to Git. Use the isolated `org.schabi.newpipe.debug.featurecloudsyncv120261019` package on a dedicated QA emulator when a target is available, and run `org.schabi.newpipe.custom.cloud.CloudSyncControllerTest` before broader native regression.

## Closeout and remaining work

Cloudflare was left as recorded by the [server checkpoint](../cloud-reopen-20261021/README.md): all public API flags false; CONTROL HELD; all three QA credentials revoked; three verified archives retained. This Android change did not alter that live state.

Native runtime acceptance, durable daily usage budgets, bounded housekeeping, broader multi-device/offline/UI/scale acceptance and independent final review remain. No merge to `custom/dev`, paid activation or production release is implied.
