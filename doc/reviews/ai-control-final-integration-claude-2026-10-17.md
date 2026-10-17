# AI Control v1 integration review (HEAD 8147902d6): **PASS WITH NONBLOCKERS**

I found nothing in the source that blocks the release. The real risks are all in how the build gets made and installed, and four of them are mandatory: G1–G4 below. If G1 or G2 is skipped, the installed app could end up being the wrong one, or the only way forward could be an uninstall that wipes the real Library. I haven't seen any build or test results for this checkout, so I'm not treating them as passing.

## What I checked directly
- **Branches:** `integration/ai-control-v1-20261017` and `feature/ai-control` both point to `8147902d6a05…`. `custom/dev` is `b9a8f28d3a8a…`. The main checkout is on `custom/dev`, and the tagging worktree is on `feature/tagging`. I can't read git objects, so I could **not** confirm that `custom/dev` is an ancestor of the integration branch. That claim is still unverified.
- **Database:** the only change is additive (version 9 → 10 adds one receipt table, `AiCommandReceipt.kt:57-74`). There is no "wipe the database if migration fails" setting (`NewPipeDatabase.kt:29-43`). No raw table or index creation anywhere else, so the existing schema hasn't drifted.

## Findings

**1. HIGH (gate G1): the app's package name comes from the git branch name.** `app/build.gradle.kts:20-27,63-70` builds debug APKs with a package suffix taken from `git rev-parse --abbrev-ref HEAD`:
- Built from `custom/dev` → `org.schabi.newpipe.debug.customdev`. This is the installed real-Library app (`library-polish.md:201`).
- Built from the integration branch → `org.schabi.newpipe.debug.integrationaicontrolv120261017`. That would install as a brand-new, empty app.
- Built from a detached commit → `…debug.HEAD`.
- Built from `feature/ai-control` → `…debug.featureaicontrol` (the QA app).

**Fix:** build only from the checked-out `custom/dev` branch after the fast-forward. Before installing, confirm the package name with `aapt2 dump badging`.

**2. HIGH (gate G2): signing.** Debug builds have no signing override, so they're signed with whatever debug keystore is on the build machine. If NotCodex-2's keystore isn't the one that signed the installed customdev app, the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Android's only remedy for that is uninstalling, which means losing the Library.

**Fix:** compare the APK's signing certificate (`apksigner verify --print-certs`) with the certificate of the installed base.apk. If they differ, stop. Use `adb install -r` only, and never uninstall or run `pm clear`. Both builds have versionCode 1015, which `-r` accepts.

**3. MEDIUM (gate G3): the 9 → 10 database upgrade is one-way.**
- If someone later reinstalls an old `b9a8f28d3` APK (Android allows it because the versionCode is the same), I expect Room to refuse to open the database. The app would be unusable until the v10 build is reinstalled. The data isn't wiped.
- Backups exported after the upgrade (schema 10) can't be imported by the old custom app or F-Droid NewPipe. The new importer rejects any schema above 10 (`LibraryBackupRestore.kt:148-151`).

**Fix:** before installing, export a schema-9 backup ZIP from the current customdev app, copy it off the phone, and keep the pulled current APK. In practice, rollback means staying on v10.

**4. MEDIUM (inference): don't run instrumentation tests from `custom/dev` on the Pixel.** As far as I know, Gradle's `connectedAndroidTest` installs the app and test APKs and uninstalls them by default when the run finishes. Run from `custom/dev`, that would target the real-Library package. Some tests do check that they're running in the QA package (`AiLiveRelayAndroidTest.kt:36`, etc.), but the install/uninstall would still happen. Keep all device testing on the `featureaicontrol` QA app.

**5. MEDIUM (gate G4): the 6 dirty spec docs in the main checkout.** `git merge --ff-only` refuses to run if incoming commits touch those files, so nothing gets silently overwritten. Otherwise they just stay uncommitted. **Fix:** commit them to a side branch first; don't use `git stash`, because the stash is shared with other sessions.

**6. LOW: branch integration.** If the ancestry check passes, a fast-forward can't create code conflicts. Tagging is still at database version 9 (`newpipe-tagging/.../AppDatabase.kt:37`), so when tagging is integrated later it must build on version 10 and use 11. The warning in `AiCommandReceipt.kt:50-53` already covers this. Nothing in this release touches tagging.

**7. LOW (inference):** `gateway/node_modules` shows as untracked even though `.gitignore` ignores `node_modules/`. It's probably a symlink, which that pattern doesn't match. Don't use `git add -A` or `commit -a` in this worktree.

**8. Security, identity, foreground service, backups.** What I saw looks sound:
- The AI service isn't exposed to other apps, uses the `specialUse` foreground type with an explanation, doesn't restart itself, and has no boot autostart.
- Stop and swiping away the notification both disable AI Control (`AiControlService.kt:54-58,117`). If notifications are blocked, the service shuts itself down.
- Pairing state lives in `noBackupFilesDir`, and corrupt state fails closed (`AiLocalStateStore.kt:67-92`).
- Import, settings reset and panic exit all disconnect AI first.
- The `PlayerService` force-kill is bypassed only while AI Control is running (`PlayerService.java:207-211`).

Nonblockers:
- **(a)** The daily app is a debuggable build, so anyone with ADB access can read the database and the AI state file through `run-as`. The Keystore private key still can't be extracted.
- **(b)** `allowBackup=true` means the receipt table goes into Android's Auto Backup along with the database. It holds no secrets, so this is harmless.
- **(c)** customdev gets a new device key and ID and has to be paired again. Both devices default to the name "Pixel 7" (`Build.MODEL`), and the gateway doesn't reject duplicate names (`account.ts:40-46`), so ChatGPT could pick the QA device by mistake. **Fix:** name the new one something like "Pixel 7 (main)", then revoke the QA device at `/setup`.
- **(d)** A new device starts with only `library.read` (`AiLocalStateStore.kt:27`). Set the permission ceiling when pairing.

**9. Gateway and ChatGPT read/write/chat paths.** The Worker config (origin, Durable Objects, KV, rate limits) is consistent, and nothing in it depends on the Android package name. The phone takes the gateway address as user input and validates it. I could not confirm that the deployed Worker matches the gateway code at HEAD. Compare the deployment before shipping rather than redeploying by habit. One thing to cover in the smoke test: when the screen is locked, AI-started playback launches the player from a background service (`AiPlayerStartBridge.kt:54`). Android may restrict that, and it isn't documented as tested.

**10. Documentation accuracy.**
- The LTE waiver isn't written down anywhere. `ai-control-physical-reliability-2026-10-17.md:36` and `ai-control-reliability-fast-acceptance-2026-10-17.md:108` still say no waiver was granted, and `ai-control-no-vpn-cellular-2026-10-17.md:42` is conditional on one. Add a dated owner waiver that keeps the result as **FAIL – accepted limitation** (it failed with Android cellular validation/DNS errors, and only Wi-Fi is verified), not a pass.
- Stale statuses:
  - Spec line 5 still says the Worker is waiting for secrets, and line 184 still points to the foundation doc.
  - `ai-control-integration.md:17-19` says "not a live deployment" and "BLOCK"; mark that as superseded.
  - `gateway/README.md:13` (503 status), `:18` (points to a secrets script that isn't in this checkout), and `:29,33` (QA-only install guidance) need updating.
- Acceptance criterion 1: a canonical Library delete through ChatGPT on a live device isn't documented, only on synthetic test data. Say so plainly. If you want that evidence, do it once on the QA app — it's the one v1 path not yet run end-to-end.

## Safe finalization sequence (tagging untouched, phone data preserved)
1. Get and record the build and test results for `8147902d6`. Optionally add a docs-only commit (waiver plus stale statuses) on the integration branch.
2. In the main checkout: `git switch -c docs/preserve-ai-spec-history-20261017`, commit only the 6 dirty files, then `git switch custom/dev` and confirm the tree is clean.
3. `git merge-base --is-ancestor custom/dev integration/ai-control-v1-20261017 && git merge --ff-only integration/ai-control-v1-20261017`. Confirm HEAD is the integration tip. Don't touch `feature/tagging` or the `newpipe-tagging` worktree.
4. Build `assembleDebug` on the checked-out `custom/dev`. Check the package is `org.schabi.newpipe.debug.customdev` and versionCode is 1015 (G1), that the signer matches the installed app (G2), and record the APK's SHA-256.
5. Phone pre-flight:
   - Export a schema-9 backup ZIP from customdev and copy it off the phone. Pull the current base.apk (G3).
   - Note the Library item count and playlist count.
   - Stop AI Control on the QA app.
6. `adb install -r`. On any error, **stop**: don't uninstall or clear data.
7. Open the app. The Library and playlist counts should be unchanged (this confirms the 9 → 10 migration worked), and the F-Droid app should still be installed.
8. Pair: AI Control → gateway address → "Pixel 7 (main)" → owner one-time code → permission ceiling (leave `library.delete` off at first) → Start, and confirm the notification appears.
9. ChatGPT smoke test on real data, non-destructive:
   - `list_devices` shows the new device online, then `get_catalog_status` and `search_library`.
   - Create, rename and delete an empty "AI Smoke" playlist, then confirm the Library count is unchanged.
   - `get_now_playing`, play one item, pause, resume.
   - One read while the screen is locked.
   - Tap Stop in the notification and expect `DEVICE_OFFLINE`, then Start again.
10. Revoke the QA device at `/setup`, or at least keep it stopped and renamed.
11. Export a new v10 backup ZIP, labelled as not importable into old or F-Droid builds. Write a release record: commit, APK hash, signer, install time, smoke results, LTE waiver.

**Not reachable from this session:** several claude.ai connectors (Blockscout, Cloudflare Developer Platform, Figma, Gmail, Google Calendar, Google Drive) aren't authorized. I didn't need any of them for this review. They can be connected in claude.ai's connector settings if you want them later.
