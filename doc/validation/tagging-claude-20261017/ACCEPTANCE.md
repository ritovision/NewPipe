# Tagging Mode + AI integration — owner acceptance

**Decision date:** October 17, 2026  
**Status:** **ACCEPTED / FEATURE DEVELOPMENT COMPLETE**  
**Decision:** The owner explicitly elects to finish this feature without additional personal/manual testing and to move on to other projects. This is a **risk acceptance**, not a claim that unperformed verification passed.

## Accepted scope

- Persistent category/tag definitions and per-stream assignments, native accordion Library browser and editor, filters (including Untagged and AND/OR/NOT), sorting, playback, ordered queues, and static playlists.
- Automatic canonical Library preservation, coordinated tagging cleanup after Library deletion, independent tag SQLite database, backup/import/migration/recovery safeguards.
- Optional AI Control tag/category CRUD, tag assignment and tagged-Library search, with explicit target device, separately grantable tag permissions and existing authenticated relay.
- Main Room naming stays **NP9/RV1 = 9001**; independently versioned tag database stays **2**. No discarded experimental fork-v10 import migration added.
- Integrated source is merged into custom/dev; the reviewed gateway extension is deployed; the separate newtest QA installation was updated on the physical Pixel without clearing app data.

## Completed verification

| Verification | Recorded result |
| --- | --- |
| JVM tests | 308/308 PASS |
| Gateway tests | 56/56 PASS |
| Android native regression/integration tests | 153/153 PASS on owned synthetic-data emulator |
| Backup assertion rerun | 13/13 PASS, reruns of included backup scenarios (not additional unique tests) |
| Real host process-stop / restart checkpoints | 5/5 PASS |
| Claude Opus Max data and UI/API independent closure | PASS WITH NONBLOCKERS / PASS WITH NONBLOCKERS |
| Pixel newtest installation/cold startup and Tags page render | Verified |

Original reviewer reports, exact issue-by-issue adjudication, build/test logs, test APK fingerprints, and acceptance boundaries are preserved in this directory and doc/reviews/tagging-claude-*. Confirmations are scoped to the scenarios actually executed.

## Explicitly waived completion gates

1. **Owner hands-on/manual functional test:** Not performed; owner accepts the remaining uncertainty.
2. **Live ChatGPT-to-Android AI tagging command:** Not verified against a connected, online device with tags.read, tags.write, and tags.delete explicitly granted. The existing AI connector's paired Pixel was offline at last check; newtest was not silently enrolled. This test is **waived for feature-development acceptance**, not marked PASS.

A separate direct HTTP test to the deployed gateway received Cloudflare HTTP 403/error 1010. The gateway unit/integration tests and published deployment do not establish success for the waived live AI command. No security setting was bypassed to force that test.

## Scope excluded from the completed milestone

- Updating or clearing the separately installed **main NewPipe custom/dev** package on the physical phone, or altering stock/F-Droid NewPipe. The merged source exists in custom/dev, but an update of that installed package is an **optional future rollout**, not something implied by accepting this feature.
- Automatically pairing newtest, obtaining new OAuth consent, expanding existing client/device scopes, or declaring the paired device online.
- Live production usage guarantees, future upstream migrations, cloud tag synchronization, dynamic smart playlists, or other feature requests.

**Disposition:** Close the Tagging Mode development milestone as accepted with the two documented verification waivers. No more builds, reviewer cycles, installations, credentials, user testing or approval prompts are required by this milestone. If a defect is discovered during ordinary use, treat it as a new bug report rather than silently reopening this accepted milestone.
