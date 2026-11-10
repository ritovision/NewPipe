# AI Control Phase D — dependency hardening and isolated live acceptance

**Date:** 2026-11-09. **Branch:** `feature/ai-passkeys-20261109`.
**Production-source/toolchain candidate:** `8e470232e9b02904dba3378268f026ababf1b1f4`.
**Verdict:** dependency hardening and part of deployed acceptance passed. **Production is unchanged. Final staged device approval/completion, physical biometrics and real ChatGPT acceptance are not complete.**

## 1. Dependency hardening

The matched development tools were updated without forced downgrades or peer-dependency bypasses:

| Package | Previous | Accepted |
| --- | --- | --- |
| Wrangler | 4.126.0 | 4.158.0 |
| Miniflare | 5.20260825.0-alpha | 5.20261104.0-beta |
| Workers types | 5.20261016.1 | 5.20261104.1 |

The new graph includes patched sharp 0.35.5 and undici 7.30.1. **Full `npm audit --json`: zero vulnerabilities on both machines**, not merely a production-only audit. Runtime application dependencies and the `2026-08-25` compatibility date are unchanged. The initial install without matching Workers types was rejected by npm; the matching types update resolved it. Neither `--force` nor `--legacy-peer-deps` was used.

Independent exact-source checks after the update:

| Environment | Executed result |
| --- | --- |
| Secondary gateway | **162/162**, nine files; TypeScript, bundle build and Miniflare/Vitest |
| Primary gateway | **162/162**, nine files, serial workers |
| Primary local Chromium | **16/16** with real WebAuthn browser APIs, disposable virtual authenticator and loopback HTTPS gateway |
| Primary QA APK and instrumentation build | Both built successfully with the new opt-in live test class |
| Python live browser harness | Syntax compilation passed |

Earlier Phase C's **116 native host tests and 10 Pixel checks** remain separately recorded evidence, not freshly rerun results of this phase. The Android production pairing code did not change in Phase D; only opt-in instrumentation was added.

## 2. Isolated deployment

A new Worker was provisioned with independent owner identity, OAuth KV, Durable Object namespaces, rate-limit namespaces and synthetic secrets. The deployed entry is the production source entry, not a test Worker with bypass hooks.

- QA Worker: `np-passkeys-qa-20261109-a3d8`.
- HTTPS origin: `https://np-passkeys-qa-20261109-a3d8.matthewjpellerito.workers.dev`.
- QA owner ID: `3587bf07-836d-4fed-a602-e328c1ab27ad`.
- QA OAuth KV: `2dd7221a333245b09102728b4293cf3a`.
- Active QA version after its initial secret setup: `89bd1b11-b734-4518-9171-0db7b651dd3d`.
- Production before/after inventory remained at **100% `75645ddd-3e81-4c9e-b4d0-a04f517a9b4c`**, created October 17. Production health returned 200. No production deployment, original owner-key rotation or binding/state change occurred.

Staging remains a retained hosted test resource for the authorized follow-up; it is not a background coding job. Its manifest/config and credentials remain outside Git in owner-only local state. The normal Cloud & Sync deployment/account was not changed.

## 3. Executed live WebAuthn and OAuth checks

All browsers ran on the primary machine. The secondary performed CLI/HTTP owner setup only, without GUI/browser/emulator.

1. A normal authenticated QA-owner recovery session on the secondary issued fresh registration options. Only the public WebAuthn challenge/options crossed to the primary.
2. Actual Chromium created a resident credential for the exact QA RP using its disposable virtual authenticator. The private virtual key remained on the primary; only the public attestation crossed back.
3. The deployed Worker verified that public attestation through its normal authenticated enrollment endpoint (`registered: true`). No production key or test-only server bypass was involved.
4. The primary subsequently signed in through the **actual bundled passkey UI and live assertion verification**, without entering an owner key there.
5. A synthetic OAuth client received explicit browser consent and completed PKCE code exchange. The resulting access token lifetime was 600 seconds; a refresh credential was present.
6. A live refresh rotated credentials and retained the 600-second access lifetime. Tokens were stored only in protected local QA state, never printed or committed.

The external synthetic callback was intercepted locally in the browser. This is a real deployed OAuth server check, **not actual ChatGPT authorization or host token-retention acceptance**. A virtual authenticator is not the owner's Google Password Manager or physical fingerprint/PIN. The 90-day sliding policy is covered by deterministic gateway tests; this run did not wait 90 days.

Tool jobs preserving the observed outputs: primary `88f89faaf0a4` (public registration), `7d9d6ebb8c80` (live sign-in/consent/refresh plus fresh native begin). The server registration-finish result was a separate successful secondary normal-auth HTTP invocation.

## 4. Executed actual Pixel TLS checks

Only the guarded QA APK `org.schabi.newpipe.debug.featureaipasskeys20261109` and its `.test` instrumentation were installed. APK application IDs and signer digests were checked before install against the existing QA signer. The real Pixel 7/Android 17 was reached through explicit Windows ADB server 5042 and its Tailscale transport. Tests ran on owned scrcpy virtual display **9**.

The new `AiBrowserPairingLiveAndroidTest` requires explicit opt-in, the exact isolated package, a specific QA-only hostname pattern and a valid non-default display. It uses the real production HTTPS transport and real Android Keystore, not injected responses.

| Native invocation | Actual result |
| --- | --- |
| First `begin` through the setup screen | **OK (1 test)**. Signed request reached deployed staging and returned pending; only `library.read` was selected. |
| Owner denial through staging's normal authenticated form | Passed; exact request ID, QA phone name and comparison code checked before denial. This denial was HTTP-driven, not a physical browser click. |
| `denied` on Pixel | **OK (1 test)**. Device remained unpaired and AI Control remained off. |
| `cancel` on Pixel | **OK (1 test)**. Pending local enrollment cleared without pairing/enabling the device. |
| Second `begin` | **OK (1 test)**. A fresh signed transaction was issued using the same device identity and epoch. |

These are **four successful instrumentation invocations across three modes**, not four independent full end-to-end pairing tests. The second `begin` overwrote the same compact begin log; both execution outputs remain in the tool jobs (`1cfc8f453be0`, `ccc65eebfee2`, `7d9d6ebb8c80`).

The following were **not executed**: QA force-stop/recreation check, final live device approval/completion, relay commands, live negative permission checks and live device revocation. The later combined approval/recovery command was blocked before execution. No result was inferred from source tests and the blocked operation was not rerouted through another tool.

## 5. Current user checkpoint and secret handling

The next action needs explicit user authorization to approve the **temporary read-only QA phone on the separate staging gateway** and resume the live completion/recovery checks. This is not permission to deploy production, change the real Library, or bypass a physical fingerprint prompt. The existing short-lived request may expire; a newly authorized run must create and compare a fresh QA transaction rather than reuse a stale URL.

Earlier attempts to transfer a disposable QA bootstrap secret or replace its staging verifier were also blocked before execution. Those approaches were abandoned. The successful setup instead kept the root QA secret on the secondary and exchanged only public registration material, then used normal passkey authentication on the primary. Unused primary bootstrap-transfer artifacts were removed. The production owner credential was never read or exported in this phase.

## 6. Review, rollout restrictions and cleanup

The independent Astra maximum-effort review found no forward-migration/dependency blocker but identified a release-blocking rollback-documentation gap. [Adjudication](../reviews/ai-passkeys-phase-d-adjudication-20261109.md) and the [new rollout runbook](../architecture/ai-control-passkey-rollout.md) document why blindly restoring legacy auth can revive old browser cookies or strand committed enrollment. Default fallback must be state-aware; a source commit is not a verified deployed fallback.

Cleanup completed: the owned scrcpy client/display closed (exit 0), its temporary recording/Windows APK copies were removed, and **both temporary QA packages were uninstalled successfully**. Canonical `org.schabi.newpipe` and `org.schabi.newpipe.ritoprod` remain installed and were not cleared, replaced or used for destructive QA. No QA foreground relay was started. Browser instances closed; no coding or test job continues after this checkpoint.

Protected synthetic staging credentials are retained outside Git only for the explicit follow-up. Compact logs, APK identities, cleanup record and public evidence hashes are in the [Phase D evidence manifest](ai-passkeys-phase-d-20261109.json). No credential/browser-profile content is included in that manifest.
