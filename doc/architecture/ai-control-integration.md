# AI Control — Android activation and Cloudflare integration


> **Latest checkpoint — 2026-10-17 (supersedes dated October 16 milestones below):** The Cloudflare owner secrets are configured and the live ChatGPT plugin operated against a paired isolated QA Pixel. Integrated gateway 45/45, native JVM 290/290, app/test builds, Android short screen-lock and Stop/Start passed. A failed LTE handoff (general cellular IP, DNS and TCP probes, with and without Tailscale) is an **owner-accepted environmental limitation for v1**, not a passing mobility test. Independent final integration review returned PASS WITH NONBLOCKERS. Consult `doc/validation/ai-control-v1-release-readiness-2026-10-17.md` for current state and installation gates; the historical notes below remain dated.


Date: 2026-10-16. Branch: feature/ai-control. All source/fixes written by the main assistant; independent reviewers only inspect.

## Implemented Android candidate

Dedicated non-exported AI Control activity in the main menu; explicit pairing and standing scopes; non-exported specialUse foreground service with visible Stop notification; Android Keystore device keys and no-backup local policy; authenticated outbound relay protocol; singleton runtime with cancel-on-stop, permission intersection, rate/queue limits, generation fences and backoff; import/reset/panic disconnection and catalog-epoch rotation; player process-lifecycle coexistence.

Added authoritative new-URL resolution and atomic URL saves. The extractor runs outside Room with a dedicated public-network-only downloader context, bound response/time limits, and no model-supplied metadata. Existing stream metadata is preserved. The original command receipt and all catalog changes share a Room transaction. Optional deletion-preview revision checks are supported but never a human approval gate.

Added play_items and append_to_queue with saved references, main-thread player calls, process-epoch replay protection, and a one-shot service-start ticket rechecked at actual Android dispatch. A requested play returns queue acceptance, not proof of audible/network-successful playback. Append cannot start a stopped player. Original playback fixes remain preserved; the remaining skewed-clock receipt-expiry finding is corrected and regression-tested.

## Evidence boundary

The first integrated native candidate passed 272 JVM tests, style checks and both APK builds. Fifteen new activation/playback cases executed successfully on the physical Pixel through primary NotCodex: Keystore signatures/nonexportability, no-backup state failure, atomic new-URL save and rollback, no refetch on retry, no metadata overwrite, disconnect during resolution, queue identity, and absent-player reads. Synthetic data and only the separate featureaicontrol QA app were used. Later heartbeat/preview/UX refinements need the final build and test rerun; those earlier numbers are not blanket approval of subsequent code.

Cloudflare gateway code is not a live deployment yet. Claude's important initial gateway review is preserved and returned BLOCK; the main assistant is fixing it. Tests now include invalid pairing/nonce without object reset, revoked re-pair prevention, scoped consent, client-isolated receipts, stale-command truthfulness, JSON-RPC batch rejection and grant revocation. Original 11-test evidence is not sufficient for this new candidate.

Remaining gates: independent Android security/lifecycle review and fixes, expanded gateway tests/re-review, actual Wrangler deployment and origin/secrets configuration, real phone-to-relay tests, final QA regression run and ChatGPT plugin connection by the user. No stock/custom real catalog was altered and no emulator or desktop UI was launched on NotCodex-2.


## Current test and deployment checkpoint — October 16, 2026

- User explicitly approved activation of the existing Cloudflare Worker authentication secrets. `OWNER_KEY_HASH` and `SESSION_SECRET` were uploaded by Wrangler and verified: Worker health HTTP 200, owner setup HTTP 200, unauthenticated MCP HTTP 401. No raw owner key or signing secret was printed or committed; the raw key remains in owner-only local storage.
- The paired-device integration test was **not executed**. Owner-protected pairing from the automated Python client returned Cloudflare edge HTTP 403 (1010 request-signature block), and a subsequent authenticated tooling attempt was blocked by platform safety checks. Do not bypass; use the owner setup portal and an explicitly authorized pairing path next. ChatGPT plugin OAuth connection likewise remains incomplete.
- Headless Android build r16: 290 JVM tests passed, application + instrumentation APK compiled, Checkstyle/Ktlint pass.
- Physical Pixel (Android 37), separate featureaicontrol QA package: 110 selected instrumentation cases passed across catalog, activation, protected state, Room/Library, migration, safe foreground-service lifecycle, read-only public media extraction and playback dispatch. Test reports are recorded in `../validation/ai-control-r14-pixel-tests-2026-10-16.json`, with final r16 playback log added. No user's normal NewPipe catalog was modified or stock/custom packages updated; no emulator ran.
- A native playback test exposed a false OUTCOME_UNKNOWN on initial play: NewPipe uses a serialized queue clone. Then stronger tests exposed legitimate reuse of an existing equivalent queue and reinitialization of a disposed queue. The main assistant fixed the acceptance checks to use the consumed clone or the pre-equivalent prior active queue, and return the *actual* queue revision. Final physical Pixel QA test passed. Astra xhigh read-only follow-up **PASS** with nonblocking limits, archived in `../reviews/ai-control-playback-queue-final-astra-2026-10-16.md`.
- The QA playback test uses deliberately invalid synthetic stream IDs, establishing queue acceptance/append/retry correctness, **not** successful audible playback. The live owner-authenticated relay, long-term background connectivity, ChatGPT authorization, and final production rollout remain unverified.
