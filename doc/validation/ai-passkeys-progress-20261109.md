# AI Control passkeys / durable authorization — saved execution checkpoint

Date: 2026-11-09. Branch: `feature/ai-passkeys-20261109`. This is a partial implementation checkpoint, not a release or deployment approval.

## Actual status

| Work | State |
| --- | --- |
| Approved spec | Saved, committed and pushed on the feature branch |
| Security design review | Astra Max completed; adjudication recorded in `doc/reviews/ai-passkeys-design-adjudication-20261109.md` |
| Phase A token policy | Implemented at source commit `c4e8b8e39`: 600-second access tokens, 7,776,000-second initial and sliding refresh lifetimes; client TTL unchanged |
| Phase A tests | 68/68 across 7 files passed on both machines; previous 59 tests unchanged; 9 new tests |
| Phase A implementation review | Astra Extra High found no blocking configuration error; two P2 documentation/coverage findings. Documentation corrected, test additions still required before deployment acceptance |
| Phase B passkeys | Source/consent API inspection started; **no Phase B implementation changes** before the coding service hit its usage limit |
| Phase C no-copy Android pairing | Not implemented |
| Live deployment / real ChatGPT validation | Not performed |
| Phone preflight | Authorized wireless ADB and isolated scrcpy virtual-display smoke passed; no installed NewPipe package/data changed |

## Next engineering actions

1. Finish Phase A review follow-ups in `gateway/test/oauth-lifetime.test.mjs`: repeated previous-token reuse, age-before-retry sliding assertion, complete relevant rejected-request snapshots including KV metadata, and access-token storage-failure injection/recovery. Do not describe token issuance as atomic. Consider a >90-day-old grant and a denied action on a narrowed access token. Re-run the full suite and review the delta.
2. Implement Phase B using the approved spec plus security adjudication. Reuse exact production gateway hostname as RP ID, maintained exact-pinned SimpleWebAuthn server/browser libraries, local browser assets, durable one-use challenges, server-revocable sessions, action-bound fresh verification, explicit single-use OAuth consent and recovery. Keep existing OAuth/device identities and Cloud & Sync unchanged.
3. Independently review Phase B and test the actual browser UI on the primary machine. Synthetic WebAuthn signatures/browser authenticators are not evidence of physical Pixel biometric enrollment.
4. Implement Phase C with a key-bound, app-initiated browser approval exchange and separate pending Android state; failed enrollment must preserve the active pairing. Use a separate QA package for Android tests.
5. Re-read active deployment metadata, validate rollback/migration, then conduct the compatible rollout and user-present fingerprint enrollment. Do not treat this saved checkpoint as permission to skip those gates.

## Workspaces and local handoff

- Primary implementation worktree: `/home/ritorhymes/techstuff/NewPipe-ai-passkeys-20261109`.
- Secondary implementation worktree: `/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109`.
- Original primary `custom/dev` and secondary older cloud-sync worktree were left intact, including their pre-existing uncommitted spec copies. Do not reset/overwrite them to synchronize this feature; use the dedicated implementation branch.
- Local instructions/logs are in each machine's `~/.local/state/newpipe-passkeys-20261109/`. On the secondary, `phase-b-instructions.md` holds the bounded implementation assignment.
- Secondary Phase B Claude session: `b5ab15b1-f406-45ee-8ef7-e74226aa5824` (Opus 5.5). It exited with a usage-limit error after source reads; no background coding continues. The primary no-tools availability check hit the same usage limit. The service reported reset at **17:40 America/New_York**; this is an external availability message, not a promise of automatic resumption.
- Existing Phase A coding session: `869bc2a0-18b5-4a95-84aa-2c78778c5a45`; source/test result is already committed. Prefer a focused follow-up rather than repeating all provider discovery.
- Read `ai-passkeys-preflight-20261109.md` for Windows ADB **port 5042**, explicit Pixel Tailscale transport and scrcpy command details. Use primary only for phone/browser/emulator/UI; secondary is CLI/build-only.

No production secrets were displayed or rotated, no Cloud & Sync data was changed, and no new Android APK was installed in this work session. The production owner-key experience is therefore unchanged.
