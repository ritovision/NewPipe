# AI Control Phase C — browser pairing acceptance

**Date:** 2026-11-09. **Branch:** `feature/ai-passkeys-20261109`.
**Pairing logic:** `f7c33bcdc`. **Pixel/UI candidate:** `c1af0c0ac` (same pairing logic, corrected unpaired-screen copy and an additional UI test).
**Verdict:** source, independent host tests, browser acceptance and isolated Pixel native/UI acceptance passed. **Not deployed; physical passkey and live ChatGPT acceptance are not claimed.**

## Results

| Gate | Executed result | What this proves / does not prove |
| --- | --- | --- |
| Secondary gateway | **162/162**, 9 test files | Worker/Miniflare protocol, permission, revocation, session, OAuth and failure regressions; no live deployment |
| Primary gateway | **162/162**, 9 test files, serial workers | Independent workstation verification of the same source |
| Secondary Android host | **116/116**, 8 AI-control suites; app and instrumentation APK build passed | Existing AI policy tests plus 20 browser-pairing policy/admission/HTTP tests |
| Primary Android host | **116/116**, 8 AI-control suites; both APKs build passed | Independent build/unit verification; the later copy/UI-only follow-up reused unchanged passing unit outputs |
| Primary real Chromium | **16/16** | Actual browser bundle/WebAuthn APIs, virtual CTAP2 authenticator, explicit OAuth and device approval/denial; not physical biometrics |
| Pixel 7 / Android 17 | **10/10** | 9 real-device Keystore/storage/runtime tests plus 1 setup-screen test on scrcpy virtual display **8** |
| Independent source review | Astra **max**, two read-only rounds; R2 approved | R1 findings fixed and reviewed; no claim of formal proof or deployed acceptance |

The Pixel transport tests intentionally inject a synthetic pairing client. The browser tests use a synthetic signed device and an ephemeral loopback HTTPS gateway. They are complementary tests, **not** a real Android-to-deployed-gateway end-to-end run.

## Important exercised behavior

The gateway rejects wrong device keys, changed scopes/transcripts/origins, stale approvals, missing CSRF/comparison, expired transactions and revoked identities. Signed finish is idempotent. Tests force registration failure before a write, response loss after a write, and revocation both before and after DeviceRoom registration. Recovered durable enrollment is distinct from a new owner authorization.

Anonymous begin/cancel churn cannot consume protected owner capacity. Evicted signed requests cannot revive a cancelled transaction or acquire a renewed deadline. Retained owner-approved requests remain usable after admission-generation rotation.

Native tests prove that pending enrollment is separate from active configuration, cancelled/restored/changed state cannot accept a stale callback, queued work cannot borrow a newer cancellation generation, and successful completion leaves the service disabled. Real Android Keystore signatures verify only for the exact operation transcript. Synthetic state was contained in isolated no-backup directories.

The UI test explicitly requires a valid non-default scrcpy display before launching. It verifies the actual activity display ID, browser-first guidance, hidden manual-code input, Advanced fallback toggle, enabled Connect action and disabled Start action while unpaired. The captured native view was visually inspected: the new guidance and Connect button are visible, no owner-key input is added, the legacy pairing-code field is hidden by default, and there is no overlapping/clipped setup text. The remainder of the existing settings remains scrollable.

## APK isolation and cleanup

The verified package IDs were:

- `org.schabi.newpipe.debug.featureaipasskeys20261109`
- `org.schabi.newpipe.debug.featureaipasskeys20261109.test`

Output metadata, manifest application IDs and matching signer certificates were checked before installation. The APK SHA-256 hashes and signer digest are in the [compact evidence manifest](ai-passkeys-phase-c-20261109.json). The unsupported `qaApplicationIdSuffix` property was not relied upon for isolation.

Both temporary QA packages were removed after acceptance. `org.schabi.newpipe` and `org.schabi.newpipe.ritoprod` remained installed and were never replaced or cleared. No user Library or Cloud & Sync data was used by the tests. No production gateway or owner key was modified by this work, and no production enrollment was initiated by Pixel QA.

The scrcpy client exited normally (code 0), its virtual display closed, and temporary Windows APK copies and recordings were deleted. Compact logs/results and the native view capture remain under `~/.local/state/newpipe-passkeys-20261109/`; the evidence manifest records their hashes. No font files, credentials or browser profiles are retained in the repository.

## Diagnostic attempts, not hidden acceptance claims

Setup-only failures were corrected before final acceptance: the Java 21 toolchain path, supported Astra model identifier, SDK 37 signer-output parsing, and the scrcpy display-log parser. The first UI harness incorrectly read the resolution width as a display ID; the test's explicit valid-display guard rejected it before launching an activity. The parser was corrected to read `(id=...)`; the guard was not relaxed, and the final same-candidate run passed all 10 tests.

One early local negative-auth assertion observed HTTP 500 instead of the expected 403. The unchanged focused case and subsequent complete independent suites passed; no assertion was weakened. Its cause was not established and should be investigated if it recurs during staged/live validation. It did not represent successful unauthorized access.

## Release gates still open

1. Resolve or explicitly adjudicate the four pre-existing high development-tool-chain reports documented in Phase B.
2. Exercise a separately staged live TLS gateway with the actual Android/browser handoff, including interruption and reconnect. Preserve the stable passkey relying-party hostname and existing device/permission semantics.
3. Perform actual Pixel credential-manager enrollment/sign-in with the owner's physical fingerprint/PIN and real ChatGPT authorization/refresh checks. No owner key is to be pasted into chat.
4. Re-read production deployment and rollback metadata, then perform a compatible gateway/canonical APK rollout with the correct application ID and signing identity. Existing paired devices must not be forced through new enrollment. Cloud & Sync remains independent.

See [source review adjudication](../reviews/ai-passkeys-phase-c-adjudication-20261109.md), [protocol architecture](../architecture/ai-control-browser-pairing.md), and [current checkpoint](ai-passkeys-progress-20261109.md).
