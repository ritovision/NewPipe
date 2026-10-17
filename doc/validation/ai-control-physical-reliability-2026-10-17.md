# AI Control v1 — physical Pixel short reliability acceptance

**Date:** October 17, 2026 (America/New_York)
**Candidate:** `feature/ai-control`, isolated package `org.schabi.newpipe.debug.featureaicontrol`
**Target:** paired Pixel 7 (`a39aabd6-c5f4-4f57-96d7-21be9b75b64c`)
**Scope:** Short, nondestructive hardware verification. Long soak and full Doze remain deferred.

## Results

| Gate | Result | Verified evidence |
| --- | --- | --- |
| Re-establish USB → Tailscale wireless ADB, unplug USB | **PASS** | Linux and Windows authenticated ADB to `100.68.130.31:5555`; later commands worked with USB disconnected |
| Foreground-service startup | **PASS** | Launched QA NewPipe from its own exported launcher, opened AI Control from the app menu, tapped Start. Service `AiControlService` entered foreground (`notification id 27100`) and screen said `connected`. Plugin catalog and playback read returned `COMPLETED/OK` |
| Screen-off / short locked interval | **PASS** | Used Android sleep key, observed `mScreenState=OFF`, `mWakefulness=Dozing` at sampled intervals through roughly 45 seconds. Foreground service stayed active; live MCP `get_catalog_status` and `get_now_playing` returned `COMPLETED/OK` while locked. This is **not** deep Doze certification |
| Wi-Fi → cellular → Wi-Fi reconnection | **FAIL / NEEDS FOLLOW-UP** | Wi-Fi disabled using `svc wifi disable` with a verified on-device 75-second restoration watchdog. While Wi-Fi was off, Tailscale ADB lost reachability; live MCP catalog and playback reads returned `OUTCOME_UNKNOWN` (`CONNECTION_LOST` and `DEVICE_TIMEOUT`). Wi-Fi was restored by watchdog; phone became reachable again via Tailscale, but ADB required another one-time debugging authorization. The AI Control gateway initially showed the device offline and later **automatically reconnected**, without changing local permissions, reinstalling the app or re-pairing. Root cause of cellular transition failure was **not established** (mobile/VPN routing, Android network events, and application reconnect remain to distinguish). No further network toggles were performed in this session |
| Foreground notification Stop action | **PASS** | Selected actual `Stop AI Control` notification action via Android notification shade. Foreground service and notification disappeared; local `enabled` switched to `false` while `paired=true`, six permission entries remained. ChatGPT `list_devices` showed offline; `get_catalog_status` returned `NOT_EXECUTED/DEVICE_OFFLINE` |
| Start again using app screen | **PASS** | From disabled AI Control screen tapped Start. Foreground service and app `connected` state returned; local `enabled=true`, `paired=true`, six permissions unchanged; plugin again returned `get_device_status` online and `get_catalog_status` / `get_now_playing` `COMPLETED/OK` |
| Data preservation | **PASS within observed scope** | No app uninstall, clearing, re-pairing, permission edits, real playlists mutations, Library deletions, credential revocation or Cloudflare deployment. Read-only catalog results consistently returned `library_id=4` and `saved_items=0` in this QA installation |

## Implementation and evidence limits

- NotCodex primary computer was used for the physical Pixel; NotCodex-2 remained Android build-only.
- USB debugging was explicitly authorized **once per prompt** by the owner. The owner did **not** select persistent “Always allow”. No changes to that preference should be requested or assumed.
- For the Wi-Fi test, a temporary marker and shell watchdog under `/data/local/tmp` restored Wi-Fi after 75 seconds and removed the marker. Later checks showed Wi-Fi on, mobile data on and the guard marker cleared. ADB authorization interruption during the handoff was observed; this alone does not locate the AI Control failure.
- The phone did not have confirmed live cellular Tailscale reachability during Wi-Fi-off. Do **not** label the failure definitely a gateway, VPN, application or cellular-network defect until isolated.
- Current normal end state: Wi-Fi restored, Tailscale wireless ADB authorized after owner approval, AI Control enabled/paired/connected, foreground notification active, and MCP catalog/playback read succeeded.
- Only ~45 seconds of display-off behavior was checked; full Android Doze / battery optimization, process death, reboot, long-lived socket durability and unattended restoration are **not verified**.
- Gateway synthetic acceptance is separately documented in `ai-control-reliability-fast-acceptance-2026-10-17.md` (45/45 tests passed; 12/12 fast scenario tests repeated three times; final Claude review PASS).

## Follow-up: Tailscale removed as sole cause

A second physical LTE test using USB ADB while Tailscale was **disconnected** failed ordinary public-IP, DNS and gateway TCP probes at +5/+15/+30/+45/+60/+75 seconds with Wi-Fi off. The AI service stayed active. Android separately logged cellular network validation and DNS-probe failure. Wi-Fi restored automatically and MCP reads recovered. This does not prove the carrier or Android routing root cause, but it shows that the failure is **not exclusive to Tailscale or the NewPipe MCP gateway**. Detailed evidence and raw-log pointers: `ai-control-no-vpn-cellular-2026-10-17.md`.

## Decision

**Physical short acceptance: PARTIAL PASS, NOT a full PASS.** Screen-lock, notification Stop and in-app restart gates passed. Wi-Fi/cellular handoff **failed** before the connection later recovered on Wi-Fi. The long-session test remains explicitly deferred. Before declaring unrestricted background mobility/release reliability, either investigate and fix the handoff failure with controlled network diagnostics, or explicitly accept a documented limitation. No waiver has been requested from or granted by the owner.
