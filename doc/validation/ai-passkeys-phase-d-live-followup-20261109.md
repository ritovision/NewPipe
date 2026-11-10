# AI Control Phase D — Authorized isolated live follow-up (2026-11-09)

**Branch:** `feature/ai-passkeys-20261109`. **Base commit:** `1e86fdf0e`.
**Scope:** The user's explicit authorization to pair and test one temporary **read-only QA NewPipe device** against the **isolated staging Worker**, not production.
**Final outcome:** Real staging/Pixel pairing, restart recovery, relay reads, local permission denials and gateway revocation **demonstrated**. The second revocation instrumentation run **failed in QA completion-marker coordination**. A clean retry was **blocked before staging approval**; no bypass was attempted. Physical Pixel passkey and real ChatGPT acceptance are still pending.

## Environment and scope proof

- **Only staged origin:** `https://np-passkeys-qa-20261109-a3d8.matthewjpellerito.workers.dev`; deployed source and isolated gateway identity are recorded in [Phase D baseline](ai-passkeys-phase-d-20261109.md).
- **Primary workstation only** used the Pixel 7 via Windows ADB server 5042 and Tailscale device `100.68.130.31:5555`, and ran Chromium with a **disposable virtual WebAuthn authenticator**. No physical user fingerprint was claimed.
- **Only QA Android packages:** `org.schabi.newpipe.debug.featureaipasskeys20261109` and its `.test` instrumentation. APK IDs/signing were verified by the existing guarded installer before installation. Only `library.read` permission selected.
- scrcpy's **owned virtual display 10**; normal NewPipe `org.schabi.newpipe` and `org.schabi.newpipe.ritoprod` were never installed-over, cleared or used by these tests.
- OAuth calls used a previously authorized **synthetic** staging client; one fresh token refresh rotated successfully, access lifetime remained 600 seconds. No production owner key/secret, cloud-sync account, actual video Library or real ChatGPT client was used.

## Executed live acceptance, first enrollment

| Check | Observed outcome |
| --- | --- |
| Fresh signed Android pairing `begin` | **PASS**, native instrumentation `OK (1 test)`. Isolated QA device name, stage origin and `library.read` selected. |
| Exact browser passkey approval | **PASS**, actual public staging browser page, disposable authenticator, exact ephemeral request ID and on-device comparison code verified, explicit checkbox/approval clicked. |
| Interrupted completion | **PASS**, force-stopped **only QA package**, then `complete` native instrumentation `OK (1 test)`; original device identity/epoch preserved, still only `library.read`, service not auto-started, attempted second pairing rejected. |
| Actual authenticated OAuth refresh | **PASS**, refresh token rotated; returned 600-second access token. |
| Foreground relay / MCP discovery | **PASS**, Android `relay` instrumentation `OK (1 test)`; connected QA device discovered as online by deployed gateway, then service stopped cleanly with a host-completion marker. |
| Live reads | **PASS**, `get_device_status` and `get_catalog_status` returned non-error responses over real Worker-to-Android relay. |
| Cross-scope denial: `pause` | **PASS**, isError true under device-local read-only scope (staging OAuth client had broader permissions). |
| Cross-scope denial: `create_playlist` | **PASS**, `DEVICE_PERMISSION_DENIED`, `NOT_EXECUTED`. No QA playlist created. |
| Gateway owner portal device revocation | **PASS**, signed in with staging browser passkey, selected the unique exact QA device entry and confirmed `(revoked)`. |
| Calls after device revocation | **PASS**, `get_catalog_status` and `get_device_status` both returned `DEVICE_NOT_AUTHORIZED`. |

The post-revocation checks are **real deployed responses**, not inferred from unit tests. The active peer's Android relay coordination test still had to close separately, as described below.

## Revocation instrumentation gap and clean-retry block

1. The second `relay` instrumentation observed its `finish` marker file before it contained the expected `host_verified` content. It failed **one assertion** (`expected host_verified, was empty`) despite confirmed post-revocation gateway denial. Do **not** count this instrumentation as a pass or misattribute it to the production relay logic without more evidence.
2. The private host QA runner `~/.local/state/newpipe-passkeys-20261109/run-phase-d-device.py` was changed **outside Git** to write `finish.tmp` and atomically rename it to `finish`, avoiding an observable empty completion marker. The private script passed `python3 -m py_compile`. **This fix has not passed a full re-run.** No feature/gateway/Android production source changed.
3. To re-run with a fresh Keystore identity, only the temporary QA/test packages were uninstalled and reinstalled with the previously validated signer. New `begin` passed `OK (1 test)` again.
4. **The next browser approval invocation was blocked by the execution safety gate before running.** Do not work around that by changing tools, splitting the command, or using direct secondary HTTP approval; request a renewed user checkpoint for another active approval test.
5. The unapproved retry was cancelled through native `cancel` (`OK (1 test)`) and its temporary QA packages uninstalled again.

The **first approval and complete device test remain genuine passing evidence**. The **post-revocation access denial remains genuine passing evidence**. The missing step is one **clean, fully passing** revocation/relay instrumentation run after the atomic coordination change.

## Final cleanup and retained state

- Temporary QA app and test APK: **uninstalled**. Canonical two NewPipe packages: **still installed** (verified via targeted ADB).
- The owned scrcpy virtual display: closed with exit 0, recording removed. No QA foreground relay left running; the first relay stopped normally, the second entered its `finally` service-stop path despite the test assertion failure.
- Staging Worker and independent staging OAuth/owner material remain; the QA device that was approved is **revoked**. No staging worker removal or production upgrade was performed.
- Branch source was otherwise unchanged during execution; local QA runner fix is outside the repository. Compact local acceptance summaries: `~/.local/state/newpipe-passkeys-20261109/phase-d-live-mcp-summary.json`, `phase-d-live-revoke-summary.json`. Do not store passkey, OAuth tokens, raw browser state, or owner credentials in Git.

## Remaining release gates — not assumed complete

1. Following a **new explicit user checkpoint**, run a new approved isolated QA pairing and complete the formerly failed atomic-marker revocation instrumentation, or document equivalent defensible coverage without pretending the failed run passed.
2. Actual **Pixel 7 credential-manager / biometric-or-PIN passkey enrollment**, plus browser UX/accessibility validation. The user must physically approve device authentication; virtual Chromium credentials are not a substitute.
3. **Real ChatGPT plugin** reauthorization/refresh/permissions check against the staging gateway, not just synthetic OAuth. Require a deliberate user-present step if host account interaction is needed.
4. State-aware production rollout only after acceptance and separate go-ahead: compare production bindings/version, preserve existing pairings/grants/Library and canonical APK signer, and do not blindly downgrade gateway auth after new owner/pairing state is committed.
5. Final source/security review of any required fixes, new clean acceptance evidence, and disposal of the owned staging Worker/resources after successful rollout or explicit decision to abandon staging.

**No production deployment occurred in this follow-up.**
