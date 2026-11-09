# AI passkeys implementation preflight — 2026-11-09

This record covers environment checks only. It is not evidence that passkeys, durable grants or browser-mediated pairing have shipped.

## Source and isolation

- Baseline: `custom/dev` at `5b545c26b`; accepted spec checkpoint `9b28b3e4f` on `feature/ai-passkeys-20261109`.
- Primary isolated worktree: `/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109`.
- Secondary isolated worktree: `/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109`, based on the same primary baseline (not the older cloud-sync checkout).
- Original worktrees and their uncommitted spec copies were left unchanged. No canonical branch source reset, deployment, database change, owner-key rotation or app replacement occurred in preflight.

## Baseline gateway checks

`npm ci` and `npm test` ran on both worktrees. Each passed TypeScript, Wrangler dry-run and **59 tests across 6 files**. Secondary baseline test duration 3.09 seconds; primary 7.94 seconds. These are recorded observed test durations, not completion estimates.

## Pixel / virtual display

- Pixel 7 identified via Windows ADB server on port **5042**, explicit Tailscale transport **100.68.130.31:5555**. Remote `get-state` returned `device`.
- Metadata-only package listing found `org.schabi.newpipe.ritoprod` and `org.schabi.newpipe`. Neither was launched, cleared, reinstalled or modified by this check.
- Installed scrcpy: **4.1**. Windows SDK ADB path: `C:\Users\Mattj\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- scrcpy path: `C:\Users\Mattj\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v4.1\scrcpy.exe`.
- Process-local environment: `ADB_SERVER_SOCKET=tcp:127.0.0.1:5042`, `ADB` pointing to the SDK executable. Do not replace or restart another ADB server merely because the default 5037 list is empty.
- Successful isolated smoke command used explicit wireless serial, `--new-display=1080x1920/300 --no-audio --no-window --max-fps=10 --video-bit-rate=1M --time-limit=8`, and a unique temporary Matroska recording sink. scrcpy reported Android 17, created **display ID 6**, reached the time limit and exited **0**. The temporary recording was deleted in the command's `finally` block; the display/server were closed by normal scrcpy cleanup.
- An initial no-window attempt without a video sink exited 1 because scrcpy disabled video, which conflicts with `--new-display`. The recording sink resolved it. Do not count that failed attempt as phone/app QA.
- No passkey assertion/enrollment, browser UI, Android QA installation or real ChatGPT command was exercised.

## Deployment inventory (read-only)

`gateway/node_modules/.bin/wrangler deployments list` on the authenticated secondary machine showed the latest listed existing gateway version at **100%** as `75645ddd-3e81-4c9e-b4d0-a04f517a9b4c`, created 2026-10-17T17:50:56.956Z. This is a pre-change rollback reference; re-read active state immediately before any future deployment. No deployment was made by this command. No secrets were read into this record.
