# Android reconnection runtime checkpoint — 2026-10-21

**Executed: 34/34 CloudSyncControllerTest instrumentation tests passed**, including all nine previously unexecuted recovery reconnection tests. The app and instrumentation APK hashes and native source hashes match the `ac2fa9dc52273f74e736729286303e251d425662` checkpoint.

Primary NotCodex can reach the existing Windows `Pixel_9_API_35` emulator (`emulator-5576`) through Windows ADB. The previous WSL-only availability check did not inspect that environment and was incomplete. The other emulator and physical phone were not used.

The first instrumentation launch timed out during Android application startup before producing test results. ActivityManager recorded CPU and memory pressure with a startup ANR. Compiling the isolated QA app and instrumentation bytecode with Android package manager, then retrying the same class, produced **OK (34 tests)** in 39.815 seconds. No app data was cleared.

The nine reconnection tests now have real Android persistence/Keystore/controller execution, including all five injected interruption boundaries. They preserve native content and mappings, archive old work, reject invalid reconnection authority, and keep sync disabled until preview. These tests use a deterministic network peer. **Actual host process-kill/restart verification and live recovery-to-Android reconnection are separate acceptance gates.**

See [checkpoint.json](checkpoint.json) and [controller.log](controller.log) for the exact test list and execution output. This is not a release-completion claim.
