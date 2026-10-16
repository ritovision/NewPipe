# AI Control — Android activation and Cloudflare integration

Date: 2026-10-16. Branch: feature/ai-control. All source/fixes written by the main assistant; independent reviewers only inspect.

## Implemented Android candidate

Dedicated non-exported AI Control activity in the main menu; explicit pairing and standing scopes; non-exported specialUse foreground service with visible Stop notification; Android Keystore device keys and no-backup local policy; authenticated outbound relay protocol; singleton runtime with cancel-on-stop, permission intersection, rate/queue limits, generation fences and backoff; import/reset/panic disconnection and catalog-epoch rotation; player process-lifecycle coexistence.

Added authoritative new-URL resolution and atomic URL saves. The extractor runs outside Room with a dedicated public-network-only downloader context, bound response/time limits, and no model-supplied metadata. Existing stream metadata is preserved. The original command receipt and all catalog changes share a Room transaction. Optional deletion-preview revision checks are supported but never a human approval gate.

Added play_items and append_to_queue with saved references, main-thread player calls, process-epoch replay protection, and a one-shot service-start ticket rechecked at actual Android dispatch. A requested play returns queue acceptance, not proof of audible/network-successful playback. Append cannot start a stopped player. Original playback fixes remain preserved; the remaining skewed-clock receipt-expiry finding is corrected and regression-tested.

## Evidence boundary

The first integrated native candidate passed 272 JVM tests, style checks and both APK builds. Fifteen new activation/playback cases executed successfully on the physical Pixel through primary NotCodex: Keystore signatures/nonexportability, no-backup state failure, atomic new-URL save and rollback, no refetch on retry, no metadata overwrite, disconnect during resolution, queue identity, and absent-player reads. Synthetic data and only the separate featureaicontrol QA app were used. Later heartbeat/preview/UX refinements need the final build and test rerun; those earlier numbers are not blanket approval of subsequent code.

Cloudflare gateway code is not a live deployment yet. Claude's important initial gateway review is preserved and returned BLOCK; the main assistant is fixing it. Tests now include invalid pairing/nonce without object reset, revoked re-pair prevention, scoped consent, client-isolated receipts, stale-command truthfulness, JSON-RPC batch rejection and grant revocation. Original 11-test evidence is not sufficient for this new candidate.

Remaining gates: independent Android security/lifecycle review and fixes, expanded gateway tests/re-review, actual Wrangler deployment and origin/secrets configuration, real phone-to-relay tests, final QA regression run and ChatGPT plugin connection by the user. No stock/custom real catalog was altered and no emulator or desktop UI was launched on NotCodex-2.
