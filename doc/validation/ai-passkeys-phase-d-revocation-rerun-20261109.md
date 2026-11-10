# AI Control Phase D — Fresh approved QA rerun, atomic relay marker and revocation (2026-11-09)

**Branch:** `feature/ai-passkeys-20261109`. **Previous checkpoint:** `1da50e379`.
**Test authority:** Explicit user authorization to create and approve **one additional isolated, read-only staging QA device**, not to modify production, canonical NewPipe, saved Library, or Cloud & Sync.
**Result:** **PASS** Android live `begin`, browser approval, force-stop/restart `complete`, live `relay` with corrected atomic completion marker, pre-revocation read, exact-device portal revocation, and post-revocation rejection for catalog/status. **Important sequencing:** portal revocation happened **after** this run's relay test had already stopped, due to an owner-portal test locator error. A previous follow-up separately proved revocation while relay was running but its instrumentation failed on the old marker race. Do not present the two runs as one uninterrupted fully passing active-relay revocation.

## Verified environment and boundaries

- Exact staging Worker: `np-passkeys-qa-20261109-a3d8`. No production Worker or Cloud & Sync resources changed.
- Real Pixel 7 through explicit Windows ADB server 5042 and Tailscale target. **Owned scrcpy virtual display 11**, not the physical biometric UI.
- Only QA APKs: `org.schabi.newpipe.debug.featureaipasskeys20261109` and matching `.test`. The guarded installer rechecked known APK metadata/signing before installing.
- Android-local scope was **only `library.read`**; service remained disabled after pairing. Browser ownership was authenticated through the **disposable virtual WebAuthn credential**, not a physical fingerprint.
- Staging synthetic OAuth test client: access token had expired when first read check returned HTTP 401 `invalid_token`. Normal `refresh` rotated it and reissued 600-second access, after which the pre-revocation read **passed**. No manual owner-key input.

## Actual results

| Gate | Observed result |
| --- | --- |
| New browser-pairing begin on QA Pixel | **PASS**, Android instrumentation `OK (1 test)`, signed staging transaction and read-only scope |
| Matching browser comparison and explicit approval | **PASS**, current exact request/code verified with staging virtual authenticator; did not use production |
| Force-stop/restart completion of pairing | **PASS**, Android `complete` `OK (1 test)`, same device identity, epoch and scope, no service auto-start |
| Foreground relay connected | **PASS**, native `ready.json` observed, `library.read` and connected |
| Pre-revocation gateway catalog read | **PASS** after ordinary synthetic OAuth refresh; live deployed worker-to-Pixel response |
| Corrected atomic `finish.tmp -> finish` marker | **PASS**, Android `relay` `OK (1 test)` (~179 seconds), after prior read test and before owner revocation |
| Owner portal selected exact new device by ID | **PASS**, identified unique target in **two identically named QA devices** (the other device was already revoked); actual passkey owner portal action returned `(revoked)` for the exact new device |
| Access after device revocation | **PASS**, `get_catalog_status` and `get_device_status` both yielded MCP `isError: true`, `code: DEVICE_NOT_AUTHORIZED` |
| Isolation and cleanup | **PASS**, both QA packages uninstalled successfully; only `org.schabi.newpipe` and `org.schabi.newpipe.ritoprod` remain, relay `enabled: false`, scrcpy display closed exit 0 and recording removed |

**Nonblocking test-harness observations:** The initial owner-portal Playwright locator selected by non-unique display name and failed **before clicking**. The next CSS `[value]` locator unexpectedly returned zero despite the target existing as an input's value. Read-only DOM inspection confirmed the exact target appears once in the two form inputs. Selecting the form by the unique **value-derived index**, validating its hidden device ID immediately before clicking, then checking the revoked flag succeeded. No wrong device was revoked.

**Sequencing limitation:** The test harness waited for a completion marker and was allowed to close before successful owner-portal revocation. This proves corrected relay termination independently of the exact-device revocation; previous live follow-up provides real while-relay-active revocation denial. A *single-run* active-relay/revocation/atomic-marker triple remains unproven if that stronger acceptance condition is required. Do not create a third QA enrollment without a fresh authorization.

## Local evidence and clean state

- Native test output: host test jobs and `~/.local/state/newpipe-passkeys-20261109/phase-d-pixel-{begin,complete,relay}.log`, plus QA `begin`, `complete`, `ready`, `stopped` coordination records before uninstall.
- Post-revocation compact evidence: `~/.local/state/newpipe-passkeys-20261109/phase-d-live-atomic-revocation-20261109.json` (no OAuth tokens or passkey secrets). Synthetic login/refresh traces remain in owner-only QA state, **outside Git**.
- The host-only QA completion coordination fix is `finish.tmp` then atomic rename, in `~/.local/state/newpipe-passkeys-20261109/run-phase-d-device.py`. It passed a live completed relay; it is not a production source-code modification.
- Temporary QA packages were removed. Canonical installed NewPipe packages were confirmed present via ADB. Owned display 11 and recording terminated/removed.
- The isolated staging Worker and its separate staging owner/grant state remain retained for later genuine Pixel passkey / ChatGPT acceptance. Both tested QA devices are revoked.

## Remaining user-present release gates

1. Actual Pixel credential-manager **passkey enrollment and authentication** using real biometric-or-PIN unlock, with the user present. Virtual Chromium authenticator is not evidence of this.
2. **Real ChatGPT** custom connection and token retention/reconnect checks (synthetic OAuth integration alone is insufficient).
3. State-aware production deployment review and **separate authorization** before any live gateway or canonical APK upgrade, including signer/pairing/Library preservation and rollback planning.
4. Optional only if demanded by sign-off: one single-run Android active-relay revocation test with atomic marker, using a separately authorized new QA device.

**No production deployment, owner-key rotation or canonical app-data mutation occurred.**
