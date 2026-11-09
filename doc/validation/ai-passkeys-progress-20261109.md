# AI Control passkeys / durable authorization — saved execution checkpoint

**Updated:** 2026-11-09, after the direct-implementation continuation. **Branch:** `feature/ai-passkeys-20261109`. **Last production-source change:** `44711d025`; subsequent checkpoint changes are documentation/evidence only. This is source acceptance of the gateway milestone, **not deployment or full-feature completion**.

## Actual status

| Work | State |
| --- | --- |
| Product/security design | Approved spec and design adjudication committed; user authorized implementation |
| Phase A durable OAuth | Implemented: 600-second access, 7,776,000-second initial/sliding refresh; provider remains pinned1.2.3, no custom token server |
| Phase A review follow-ups | Completed: 11 lifetime tests, repeated-token retry/expiry, KV metadata snapshots, narrowed scope, old grants, partial-write recovery |
| Phase B owner passkeys | Implemented: actual WebAuthn, recovery, revocable sessions, fresh authorization for credential changes, explicit browser-bound OAuth consent |
| Phase B review | Three read-only GPT-6 Astra max rounds. All actionable findings corrected; R3 approves source44711d025 within its focused follow-up, no new blocking findings |
| Exact-source gateway tests | **130/130 across8 files passed independently on both machines**; primary uses one worker, secondary normal concurrent run |
| Primary browser QA | **11/11 passed** using actual Chromium WebAuthn APIs and a disposable virtual CTAP2 authenticator; includes both approval and denial callbacks |
| Phase C no-copy Android pairing | **Not implemented**; existing app/manual pairing unchanged |
| Live Pixel/credential manager/fingerprint and ChatGPT validation | **Not performed**; synthetic browser results do not substitute for these |
| Deployment | **Not performed**; production owner-key experience and OAuth policy remain unchanged |
| Dependency audit | Two pre-existing reports patched compatibly; four high dev-tool-chain reports remain tracked before rollout |
| Phone access | Pixel7 wireless ADB over Tailscale still returns `device`; no installed APK/data changes |

Detailed evidence: [Phase B/source acceptance](ai-passkeys-phase-b-20261109.md), [review adjudication](../reviews/ai-passkeys-phase-b-adjudication-20261109.md), [Phase A acceptance/migration](ai-passkeys-phase-a-20261109.md).

## Implementation commits

- `c4e8b8e39`: original Phase A token configuration and tests.
- `20b07dd5a`: Phase B owner-state, browser UI, session/recovery/consent integration, tests, first review corrections and compatible dependency patches.
- `554f3e4ed`: primary-only browser test runner correction and explicit approval/denial checks; no production source change.
- `44711d025`: CSP-safe callback host restriction and failure/concurrency regressions; **exact source used in final130-test and11-browser-check runs**.

## Next engineering work

1. Implement Phase C from the accepted spec and security adjudication: app-initiated, device-key-bound `begin -> browser approve -> finish/status`, short-lived one-use transaction, explicit owner approval, no secret/pairing token in URL, no unsafe custom-scheme dependency. Keep pending Android enrollment separate so cancellation/expiry does not replace a working pairing. Do not regenerate an existing device key merely for this UX change.
2. Add native Connect to AI flow with browser return/resume, cancellation/network/lifecycle coverage, existing manual-code fallback under Advanced, and explicit foreground-service opt-in. Test only a separate synthetic QA Android package before any canonical APK update.
3. Review Phase C, run gateway/native tests and primary-only Pixel UI checks. Secondary remains headless/build-only. Do not ask the user to repeat the completed USB/Tailscale setup unless the connection actually fails.
4. Address the remaining development-tool advisories without blindly force-downgrading Miniflare; inspect exact dependency/Worker compatibility and rerun validation after changes.
5. Re-read deployed gateway metadata, stage compatible rollout/rollback, then perform user-present Pixel passkey enrollment and real ChatGPT reconnect/refresh validation. The owner will need to physically approve fingerprint/PIN verification at that point. Do not paste or request the owner key in chat; it already exists protected on the provisioning machine.

The gateway-only milestone can serve already-paired installations without a new APK once live rollout is validated. No-copy pairing is a separate Android milestone, not a prerequisite for keeping existing device identities valid.

## Workspaces and operational notes

- Primary implementation worktree: `/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109`.
- Secondary implementation worktree: `/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109`.
- Both use the same feature branch; synchronize only these dedicated worktrees. The original primary `custom/dev` and secondary older cloud-sync checkout remain intact, including their pre-existing uncommitted spec copies.
- The primary remote fetch refspec is narrow. Use `git fetch origin refs/heads/feature/ai-passkeys-20261109:refs/remotes/origin/feature/ai-passkeys-20261109`, then `git merge --ff-only origin/feature/ai-passkeys-20261109`, and **verify the exact commit** before treating test results as current. Do not reset or force-push.
- Logs/instructions are in each machine's `~/.local/state/newpipe-passkeys-20261109/`. Final evidence filenames/hashes are recorded in the acceptance document. Keep only compact source/evidence in Git, not raw test tokens, transient certificates or verbose runner events.
- Read [preflight](ai-passkeys-preflight-20261109.md) for Windows ADB **port5042**, explicit Pixel Tailscale transport and scrcpy details. The phone-facing machine is primary only.
- Claude's previous service-limit checkpoint is historical. This continuation was authored by the main assistant with Astra **review only**; no Claude job or coding-agent resumption is needed.
- All temporary browser-QA servers/profiles and synthetic credentials from this continuation were cleaned up. Reviews and test jobs completed. No implementation is continuing asynchronously after this saved checkpoint.

No production secrets were read/displayed/rotated, no production gateway was deployed, no Cloud & Sync state changed, and no Android APK was installed during this continuation. The user's Library, playlists, tags, existing paired device and live ChatGPT connection were not modified.
