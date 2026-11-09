# AI Control passkeys / durable authorization — execution checkpoint

**Updated:** 2026-11-09, Phase C continuation. **Branch:** `feature/ai-passkeys-20261109`. Pairing logic is committed at `f7c33bcdc`; native copy/virtual-display test follow-up is `c1af0c0ac`. **Source implementation is not deployment.**

## Actual status

| Work | State |
| --- | --- |
| Phase A durable OAuth | Implemented: 600-second access, 7,776,000-second initial/sliding refresh; original valid grants migrate on successful refresh, expired grants do not revive |
| Phase B owner passkeys | Implemented: WebAuthn enrollment/login/recovery, revocable sessions, fresh verification for sensitive owner changes, explicit scoped OAuth consent |
| Phase C no-copy native pairing | Implemented: public nonce preflight, device-key-bound begin/status/finish, browser passkey return and explicit comparison/approval, independent local permissions, staged durable native state, no auto-start |
| Compatibility | Existing paired installations are not re-enrolled or overwritten; manual code pairing remains an Advanced fallback; no Library/Cloud & Sync schema changes |
| Phase C security review | Two read-only GPT-6 Astra max rounds. Three R1 findings corrected; R2 approves `f7c33bcdc` within the requested source scope, no concrete remaining blockers |
| Gateway validation | **162/162 across 9 files passed independently on both machines** |
| Native host validation | **116/116 across 8 AI-control suites passed independently on both machines**; isolated debug/app-test APKs build successfully |
| Browser acceptance | **16/16 passed** in primary Chromium using actual WebAuthn APIs, disposable virtual authenticator and ephemeral loopback HTTPS gateway |
| Pixel native acceptance | **9/9 passed** on Pixel 7 / Android 17 using real Keystore/AtomicFile and isolated synthetic pairing transport; queued cancellation, late completion and restore/configuration barriers included |
| Pixel virtual-display UI acceptance | **10/10 total Pixel tests passed** (9 native + 1 UI) on final candidate `c1af0c0ac`; actual scrcpy display 8, screenshot inspected, client exited normally |
| Live credential-manager / physical fingerprint / deployed ChatGPT | Not performed; virtual authentication and injected native tests do not substitute for these |
| Deployment | **Not performed**; production gateway, owner key, canonical APKs and existing live authorizations remain unchanged |
| Dependency audit | Four pre-existing high development-tool-chain reports remain a release task, as documented in Phase B |
| Phone QA footprint | Only this session's isolated branch-debug app and its test APK were installed. Normal `org.schabi.newpipe` and `org.schabi.newpipe.ritoprod` were not replaced or cleared. Both temporary QA packages, virtual display and Windows APK/recording copies have been removed |

Evidence: [Phase C acceptance](ai-passkeys-phase-c-20261109.md), [Phase C adjudication](../reviews/ai-passkeys-phase-c-adjudication-20261109.md), [pairing architecture](../architecture/ai-control-browser-pairing.md), [earlier gateway acceptance](ai-passkeys-phase-b-20261109.md), [OAuth migration](ai-passkeys-phase-a-20261109.md).

## Implemented user path

NewPipe **AI Control > Connect to AI** shows a comparison code, opens owner authentication/approval in the browser, and completes enrollment with the same device key when the user returns. No pairing secret is copied or typed. Existing paired devices skip enrollment. Completion does not enable the service; the owner explicitly chooses **Start AI Control**. ChatGPT OAuth authorization remains separate. No per-command biometric prompts were added.

The original status text telling users to enter a pairing code was replaced. The manual-code fallback is hidden under **Advanced: pair with a code**.

## Remaining release work

1. Resolve or explicitly adjudicate the four remaining development-tool advisories without unsafe forced downgrades.
2. Validate a separately staged live TLS gateway-to-Android browser handoff, network interruption and existing-device/permission compatibility. The current host browser and native transport tests are separate, not a claim of deployed end-to-end acceptance.
3. Re-read active production deployment/rollback metadata before rollout. Preserve the existing owner identity, OAuth grants, device registrations and separate Cloud & Sync deployment. Gateway passkeys can be rolled out for already-paired installations independently of a canonical native APK update.
4. Perform user-present Pixel credential-manager passkey enrollment/sign-in and real ChatGPT reconnect/refresh validation. The user will need to approve fingerprint/PIN physically then; do not ask for the owner key in chat or repeat completed USB/Tailscale setup unnecessarily.

## Workspaces and repeatable commands

- Primary: `/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109`; UI/browser/Pixel only here.
- Secondary: `/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109`; headless source/build/CLI only.
- Original main and older cloud-sync worktrees are intentionally untouched, including their pre-existing local specification copies.
- The primary remote fetch refspec is narrow. Synchronize using `git fetch origin refs/heads/feature/ai-passkeys-20261109:refs/remotes/origin/feature/ai-passkeys-20261109`, then `git merge --ff-only origin/feature/ai-passkeys-20261109`, and verify the exact commit. Never reset or force-push unrelated work.
- Primary JDK21: `~/.local/share/jdks/temurin-21`; secondary JDK21: `~/.local/share/jdks/jdk-21.0.12.1+1`. Set JAVA_HOME/PATH explicitly for Gradle. Primary SDK is `~/.local/share/android-sdk`; secondary SDK is `/opt/android-sdk`.
- QA app ID: `org.schabi.newpipe.debug.featureaipasskeys20261109`, with `.test` instrumentation. Verify output metadata, manifest ID and signer before install. `qaApplicationIdSuffix` is not a supported override in this repository.
- Windows ADB server is **5042**, explicit Pixel target **100.68.130.31:5555**. Reconnect only that target if needed; do not disturb other transports. See [preflight](ai-passkeys-preflight-20261109.md).
- Primary browser Python: `~/techstuff/notcodex/.venv/bin/python`; use `gateway/scripts/qa-passkeys-browser.py`. Never run this browser script on the secondary.
- Compact results, temporary QA scripts and detailed logs: `~/.local/state/newpipe-passkeys-20261109/` on each machine. Preserve source + compact evidence, not credentials or raw browser profiles.

**Saved execution state:** this continuation's coding, review, build, browser and Pixel test jobs have completed. Temporary QA apps and displays are removed. No implementation is continuing asynchronously after this checkpoint. No production deployment, owner-secret rotation, canonical APK replacement, Library or Cloud & Sync change was performed.
