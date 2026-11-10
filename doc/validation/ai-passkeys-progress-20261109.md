# AI Control passkeys / durable authorization — execution checkpoint

**Updated:** 2026-11-09, Phase D hardening and staged-live continuation.
**Branch:** `feature/ai-passkeys-20261109`. Production-source/toolchain candidate: `8e470232e`. Pairing implementation remains `f7c33bcdc` plus native UI follow-up `c1af0c0ac`. **An isolated QA Worker is deployed; production is not upgraded.**

## Actual status

| Work | State |
| --- | --- |
| Phase A durable OAuth | Implemented: 600-second access and 7,776,000-second initial/sliding refresh. Valid old grants migrate on successful refresh; expired grants do not revive. Partial-storage-failure/retry regressions are covered. |
| Phase B owner passkeys | Implemented: WebAuthn enrollment/login/recovery, revocable sessions, fresh verification for sensitive changes, explicit scoped OAuth consent. |
| Phase C no-copy pairing | Implemented: signed nonce/begin/status/finish, exact device key, browser comparison/approval, staged native state, independent local permission ceiling, no auto-start. |
| Compatibility | Existing pairings are not overwritten; manual-code fallback retained; Library and separate Cloud & Sync schemas unchanged. |
| Dependency hardening | **Zero full-graph npm audit advisories on both machines** after matched Wrangler4.158.0/Miniflare5.20261104.0-beta/types5.20261104.1 update. |
| Current gateway verification | **162/162 on both machines** after the tooling update; primary runs serial workers. |
| Current local browser verification | **16/16 primary Chromium checks** after the tooling update. Real WebAuthn APIs, virtual authenticator, loopback HTTPS. |
| Prior native/Pixel baseline | Phase C **116/116 native host tests on both machines and 10/10 Pixel native/UI checks** remain historical passing evidence; not newly rerun in Phase D. |
| New guarded live instrumentation | APK and test APK build succeeded. Four successful real-Pixel TLS invocations: begin twice, denial handling, cancellation. Only isolated QA app/storage and read-only permission. |
| Deployed QA WebAuthn/OAuth | Public attestation verified, actual browser passkey sign-in, explicit consent/PKCE exchange and live rotating refresh passed. Disposable virtual authenticator and synthetic OAuth client, **not** physical user/ChatGPT acceptance. |
| Final live pairing and relay | **Pending.** Tool blocked QA force-stop/approval/completion command before execution; no approved-pairing completion, resumed relay or live negative permission result is claimed. |
| Release review | Astra MAX found no forward-migration/dependency blocker; rollback documentation finding accepted. README/runbook now require state-aware fallback and prohibit blind legacy auth downgrade. |
| Production | **Unchanged**, still version `75645ddd-3e81-4c9e-b4d0-a04f517a9b4c` at the final read-only inventory. No original owner-secret, production grant/device-state, canonical APK, Library or Cloud & Sync change. |
| Phone cleanup | Both temporary QA packages uninstalled; owned virtual display9/recording closed/removed; canonical packages remain installed. No QA relay was started. |

Evidence: [Phase D acceptance](ai-passkeys-phase-d-20261109.md), [manifest](ai-passkeys-phase-d-20261109.json), [review adjudication](../reviews/ai-passkeys-phase-d-adjudication-20261109.md), [state-aware rollout](../architecture/ai-control-passkey-rollout.md), [Phase C baseline](ai-passkeys-phase-c-20261109.md), [pairing architecture](../architecture/ai-control-browser-pairing.md).

## Next execution checkpoint — user authorization needed

Request explicit permission to approve the **temporary read-only QA NewPipe device on the isolated staging gateway**, then resume the final live pairing/completion/recovery checks. The tool rejected that operation before execution. Do not retry it by splitting commands, changing tools or switching to the secondary HTTP approval helper without the user checkpoint. No production deployment or real-Library access is part of this approval.

The previous request expires after five minutes; begin a fresh signed transaction and compare its new code on an authorized resumption. Physical Pixel credential-manager enrollment and real ChatGPT authorization/refresh are later user-present release gates. Do not ask for the long owner key in chat or claim a physical fingerprint prompt is already waiting.

## Remaining release work

1. Complete the explicitly authorized isolated live phone/browser pairing, interruption recovery, relay, local-scope denial and revocation checks. Partial live successes do not close the full end-to-end gate.
2. Perform actual Pixel passkey/credential-manager verification and real ChatGPT reconnect/refresh. Virtual WebAuthn plus a synthetic OAuth client do not establish these.
3. Recheck production bindings/version immediately before rollout; prepare a state-aware fallback or explicit controlled fix-forward plan. Legacy code is not a drop-in rollback after new owner/pairing state exists.
4. Only after acceptance, do compatible gateway and canonical APK rollout, preserving app identity/signer/data, existing pairings and grants. Record exact deployed versions and remove the disposable stage when no longer needed.

## Retained staging resource

- Worker: `np-passkeys-qa-20261109-a3d8`.
- Origin: `https://np-passkeys-qa-20261109-a3d8.matthewjpellerito.workers.dev`.
- QA version: `89bd1b11-b734-4518-9171-0db7b651dd3d`.
- Independent OAuth KV: `2dd7221a333245b09102728b4293cf3a`; owner ID `3587bf07-836d-4fed-a602-e328c1ab27ad`.
- Secondary protected manifest/config/owner credential and primary synthetic authenticator/OAuth credentials are under each machine's `~/.local/state/newpipe-passkeys-20261109/staging/`, outside Git. Never print or move private credential content into chat. Only public WebAuthn options/attestation crossed machines successfully; unused primary bootstrap-transfer files were removed.
- A hosted Worker is a retained test resource, not a running background implementation/monitoring task. Remove its exact owned resources after final staged acceptance; do not delete similarly named production resources.

## Workspaces and repeatable checks

- Primary: `/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109`; browser/Pixel/UI only here.
- Secondary: `/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109`; headless source/build/CLI/review only.
- Original main and older Cloud & Sync worktrees, including their pre-existing spec changes, remain untouched.
- Primary remote refspec is narrow: use `git fetch origin refs/heads/feature/ai-passkeys-20261109:refs/remotes/origin/feature/ai-passkeys-20261109`, then `git merge --ff-only origin/feature/ai-passkeys-20261109`, and verify exact commit. Never reset/force-push unrelated work.
- Gateway: `npm ci`; full `npm audit --json`; secondary `npm test`, primary `npm test -- --maxWorkers=1`.
- Primary JDK21 `~/.local/share/jdks/temurin-21`; secondary `~/.local/share/jdks/jdk-21.0.12.1+1`. Primary SDK `~/.local/share/android-sdk`; secondary `/opt/android-sdk`. Set JAVA_HOME/PATH explicitly.
- QA package `org.schabi.newpipe.debug.featureaipasskeys20261109` and `.test` only. Check output metadata, manifest ID and signer before any reinstall. No unsupported application-ID override.
- Windows ADB server **5042**, explicit Pixel target **100.68.130.31:5555**. Do not restart unrelated ADB servers or transports.
- Primary Playwright Python `~/techstuff/notcodex/.venv/bin/python`. Local browser harness `gateway/scripts/qa-passkeys-browser.py`; isolated live harness `gateway/scripts/qa-passkeys-live-browser.py` requires the explicit QA origin. Never run browser scripts on secondary.
- New instrumentation `AiBrowserPairingLiveAndroidTest` is opt-in and strict QA-host/package/display-guarded. `complete` and `relay` modes exist in source but have **not passed live** in this checkpoint.
- Owned Pixel helper and compact evidence remain outside Git in `~/.local/state/newpipe-passkeys-20261109/`; no whole browser profile or raw credential belongs in source.

**Saved execution state:** all coding/review/build/browser/Pixel jobs for this checkpoint are complete or cleaned up. No implementation continues asynchronously. The next action is the explicit QA-device authorization above, not another unsolicited production change.
