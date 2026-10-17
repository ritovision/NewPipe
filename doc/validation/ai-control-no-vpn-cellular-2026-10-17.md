# NewPipe AI Control — Pixel 7 LTE A/B diagnostic with Tailscale disconnected

**Date:** 2026-10-17, America/New_York (03:08–03:13 EDT)
**Branch:** `feature/ai-control`
**Device:** Pixel 7, isolated QA app `org.schabi.newpipe.debug.featureaicontrol`
**Scope:** Isolate the failed Wi-Fi/cellular handoff previously recorded in `ai-control-physical-reliability-2026-10-17.md`. No product source changes, Cloudflare deployment, Library edits, re-pairing or data clears.

## Controlled setup

- Owner disconnected Tailscale on the Pixel and reconnected the phone over USB, approving the one-time Android debugging prompt. The diagnostic used the primary NotCodex Windows ADB server on port 5042 and physical USB serial, **not Tailscale ADB**. No new emulator or VPN connection was used.
- Android `dumpsys connectivity` showed **no active VPN agent**, including during every logged phone sample. Wi-Fi and mobile data were initially enabled. The Android cellular network reported `MOBILE[LTE] CONNECTED`, but this alone is not proof of working internet.
- The local shell script used non-destructive `ping` and `nc` tests plus Android state queries, storing its output outside all app sandboxes at `/data/local/tmp/qa_newpipe_ai_no_vpn_20261017.log`.
- A separate shell watchdog with a verified marker was armed to restore Wi-Fi after 110 seconds, independently of the main diagnostic script. The primary script also restored Wi-Fi on normal exit at ~03:10 EDT. The watchdog marker was subsequently confirmed cleared.
- Four probes per sample: ICMP to 1.1.1.1, DNS-name ICMP to connectivitycheck.gstatic.com, TCP 443 to 1.1.1.1 and TCP 443 to the public Cloudflare AI gateway. Each probe was bounded by a 4–5 second timeout. These do **not** constitute a browser TLS session or guarantee application-level internet access.

## Observed phone samples

| Phase | Wi-Fi | Active VPN | Public IP ping | DNS-name ping | Public TCP 443 | Gateway TCP 443 | QA foreground service |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Baseline before switch | On | No | PASS | PASS | PASS | PASS | Active |
| LTE +5 s | Off | No | FAIL | FAIL | FAIL | FAIL | Active |
| LTE +15 s | Off | No | FAIL | FAIL | FAIL | FAIL | Active |
| LTE +30 s | Off | No | FAIL | FAIL | FAIL | FAIL | Active |
| LTE +45 s | Off | No | FAIL | FAIL | FAIL | FAIL | Active |
| LTE +60 s | Off | No | FAIL | FAIL | FAIL | FAIL | Active |
| LTE +75 s | Off | No | FAIL | FAIL | FAIL | FAIL | Active |
| Restored Wi-Fi | On | No | Connectivity reverified afterward | — | PASS | PASS | Active |

The live ChatGPT `get_catalog_status` read issued during the LTE-only phase returned `OUTCOME_UNKNOWN` / `DEVICE_TIMEOUT`. After Wi-Fi restoration, the plugin reported the same paired device online, and a fresh `get_catalog_status` returned `COMPLETED` / `OK`, with `library_id=4`, `saved_items=0`. Pairing and permissions remained unchanged.

Android logcat separately recorded `ConnectivityService [101 CELLULAR] validation failed` and `NetworkMonitor/101` failing its DNS and HTTP/HTTPS validation probes shortly after the LTE-only run (around 03:10:09–03:10:27 EDT), including `DnsResolver ... ETIMEDOUT`. This supports a broader Android cellular networking failure rather than only one MCP client. The logs do not identify the underlying carrier, device routing or provisioning cause.

## Comparison with first, Tailscale-connected handoff

The earlier Tailscale-connected test at ~02:49 EDT briefly reached the public gateway over LTE (+5, +18 seconds), then had a loss of public connectivity (+32 seconds) and partial recovery (+47 seconds). The Tailscale peer's direct endpoint moved from local Wi-Fi to a cellular public endpoint before becoming unreachable. In contrast, the **no-VPN test failed every LTE sample from +5 through +75 seconds**. Removing Tailscale did **not** restore LTE connectivity under these test conditions.

## Assessment and next isolation point

1. **Strongly supported:** The failure is **not solely caused by Tailscale**. A public IP, DNS queries and TCP connections to unrelated hosts all failed during LTE-only with no VPN, while the AI foreground service stayed active. The gateway is not singled out: public connectivity failed too.
2. **Not established:** Whether the root cause is Verizon/carrier network coverage, SIM/eSIM provisioning, Android routing/network policy, another device setting or a transient radio issue. A network can report `MOBILE[LTE] CONNECTED` while validation and usable internet fail.
3. **One useful follow-up if troubleshooting continues:** On the phone, with Tailscale off and Wi-Fi temporarily disabled, check whether Chrome can load an ordinary HTTPS page. If Chrome also fails, prioritize cellular service/radio/network settings before changing NewPipe. If Chrome succeeds, investigate Android per-UID routing/proxy behavior and NewPipe transport callbacks. Use USB monitoring and Wi-Fi auto-restore. Chrome was **not** separately exercised in this run.
4. **No NewPipe code modification or Cloudflare deployment justified** on current evidence. The Android VPN/Wi-Fi behavior should be resolved before repeating a release acceptance for mobile handoff. The physical mobility gate remains **FAIL / NEEDS FOLLOW-UP**, unless the owner explicitly waives it.

## Evidence and final state

- Raw, nonsecret 89-line diagnostic log preserved on primary NotCodex host at `/home/ritorhymes/.cache/newpipe-ai-control/no-vpn-lte-probe-20261017.log`. Original remains on device at `/data/local/tmp/qa_newpipe_ai_no_vpn_20261017.log`.
- At ~03:13 EDT: Wi-Fi setting `1`, mobile data `1`, no active VPN, guard marker cleared, physical USB ADB authorized, public and gateway TCP 443 probes PASS, QA foreground notification active, and live ChatGPT MCP device online/readable.
- Tailscale was left disconnected as the owner had set it. No changes to the user's always-allow debugging preference; the one-time USB authorization was used.
- Previous short screen-lock, real notification Stop/Start, and gateway synthetic acceptance remain as separately documented; do not overwrite their results.
