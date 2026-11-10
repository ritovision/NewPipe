# AI Control passkeys / durable authorization — execution checkpoint

**Updated:** 2026-11-09, Phase D fresh approved QA rerun. See [atomic-marker and revocation rerun](ai-passkeys-phase-d-revocation-rerun-20261109.md) and [previous live follow-up](ai-passkeys-phase-d-live-followup-20261109.md).
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
| New guarded live instrumentation | Initial Phase D begin/deny/cancel checks plus subsequent **approved begin, force-stop/restart completion, a fully passing relay**, and a failing second relay host-marker assertion are recorded separately. A later fresh begin/cancel also passed. All used isolated QA package and read-only permission. |
| Deployed QA WebAuthn/OAuth | Public attestation verified, actual browser passkey sign-in, explicit consent/PKCE exchange and live rotating refresh passed. Disposable virtual authenticator and synthetic OAuth client, **not** physical user/ChatGPT acceptance. |
| Final live pairing and relay | **Two genuine isolated staging approvals:** Pixel restart/recovery, real read-only relay and permission-denial checks, portal revocation and post-revocation `DEVICE_NOT_AUTHORIZED`. A fresh rerun **passed Android `relay` with the corrected atomic host marker**. Revocation on that fresh run occurred **after** relay stopped; the earlier follow-up demonstrated live revocation while relay was running, but its Android test failed on the old marker race. See the two evidence records. |
| Release review | Astra MAX found no forward-migration/dependency blocker; rollback documentation finding accepted. README/runbook now require state-aware fallback and prohibit blind legacy auth downgrade. |
| Production | **Unchanged**, still version `75645ddd-3e81-4c9e-b4d0-a04f517a9b4c` at the final read-only inventory. No original owner-secret, production grant/device-state, canonical APK, Library or Cloud & Sync change. |
| Phone cleanup | Both temporary QA packages uninstalled again; **owned virtual display 11** closed and recording removed. QA relay disabled and both QA devices revoked. Canonical NewPipe packages remain installed and untouched. |

Evidence: [Phase D corrected relay and revocation rerun](ai-passkeys-phase-d-revocation-rerun-20261109.md), [Phase D authorized live follow-up](ai-passkeys-phase-d-live-followup-20261109.md), [Phase D acceptance](ai-passkeys-phase-d-20261109.md), [manifest](ai-passkeys-phase-d-20261109.json), [review adjudication](../reviews/ai-passkeys-phase-d-adjudication-20261109.md), [state-aware rollout](../architecture/ai-control-passkey-rollout.md), [Phase C baseline](ai-passkeys-phase-c-20261109.md), [pairing architecture](../architecture/ai-control-browser-pairing.md).

## Next execution checkpoint — physical user presence required

With new explicit user approval, a second temporary staging QA device paired successfully, its interrupted completion recovered, and a fresh **Android relay test passed with an atomic completion marker**. The device was then explicitly revoked through the owner portal using its exact device ID; two OAuth catalog/status calls returned `DEVICE_NOT_AUTHORIZED`. The device was revoked **after** the relay closed in this run. Previous separate execution covers revocation while relay was active. Both temporary QA devices are revoked, packages removed, display closed, and production unchanged.

The next mandatory user-present release gate is **actual Pixel credential-manager/passkey enrollment and authentication**, followed by real ChatGPT OAuth reconnect/refresh. Neither has been passed using the user's actual biometric/PIN or real ChatGPT account.

## Remaining release work

1. Perform **physical Pixel credential-manager/passkey verification** and real ChatGPT reconnect/refresh. Virtual WebAuthn plus a synthetic OAuth client do not establish these.
2. Recheck production bindings/version immediately before rollout; prepare a state-aware fallback or explicit controlled fix-forward plan. Legacy code is not a drop-in rollback after new owner/pairing state exists.
3. Only after acceptance and separate approval, do compatible gateway and canonical APK rollout, preserving app identity/signer/data, existing pairings and grants. Record exact deployed versions and remove the disposable stage when no longer needed.
4. If the independent release review specifically requires a **single-run** relay-active revocation plus atomic marker result, obtain fresh authorization for another disposable QA device rather than treating two separate runs as one.

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
- New instrumentation `AiBrowserPairingLiveAndroidTest` is opt-in and strict QA-host/package/display-guarded. `complete` and two independent `relay` runs passed live, including the corrected atomic marker in the latest run. The earlier post-revocation relay run failed on a premature marker read. Revocation after this latest relay passed; see both records for ordering.
- Owned Pixel helper and compact evidence remain outside Git in `~/.local/state/newpipe-passkeys-20261109/`; no whole browser profile or raw credential belongs in source.

**Saved execution state:** fresh staged QA pairing, Android relay and post-revocation denial passed, and test jobs/QA packages/virtual display were cleaned up. The private host test runner's atomic-marker correction **was verified in a passing real Pixel relay**. No task continues asynchronously. Next steps require real physical biometric/ChatGPT acceptance and separate production rollout approval.
