# AI Control playback — author audit correction

Date: 2026-10-16. Baseline: local catalog checkpoint `2b5108659`.

## Current boundary

The initial unsolicited playback increment was not release-ready. Astra xhigh source review returned BLOCK with six findings. The full original reply is preserved in `../reviews/ai-control-playback-astra-initial-2026-10-16.md`. The main assistant wrote the corrections below; the reviewer did not code.

- Removed temporary service bindings entirely. PlayerService's existing lifecycle publishes/clears a main-thread weak reference in AiPlayerRegistry. Reading or pausing an absent player cannot create a service, register a dangling binding or start media.
- AiMainThreadCall distinguishes pending/executing/finished/cancelled work. All waiting exits clean up callbacks; interrupts preserve the interrupted flag. Cancellation before dispatch forbids an effect. A timeout after dispatch is UNKNOWN, not a promise that the side effect stopped.
- Each play queue has an opaque runtime UUID and monotonically increasing event revision. It changes after queue mutation and is recreated after deserialization. Skip requires the observed revision, index, service and URL and a consistent reference between the selected queue item and Player.currentItem. A stale asynchronous currentItem cannot authorize skipping another occurrence.
- Skip rejects the loaded tail of an incomplete queue and checks whether advancing actually changed the index. Resume rejects stopped/ended/transitional playback rather than restarting a finished queue. Pause/resume return observed readiness, not a hard-coded success independent of Player state.
- Playback executor retains correlation IDs before argument validation. Its bounded in-memory result journal returns UNKNOWN when an old result is no longer retained. Playback mutations require a process-specific playback_epoch, so a process restart cannot replay an old request. One singleton executor must own this journal in the production runtime.
- Protocol routing and scopes are exhaustive; every new command must explicitly classify its permissions and mutation semantics. Playback never executes inside a Room catalog transaction.

## Verification of this checkpoint

Headless JDK21 build on NotCodex-2: **256 JVM tests, zero failures/errors/skips**. Application and Android-test APKs compile, Ktlint and Checkstyle pass. Logs: `/home/rito/.cache/newpipe-ai-control/playback-audit-build2.log`.

Eight new JVM cases exercise cancellation, one-shot execution, correlation, forgotten results, process epochs, concurrent repeated requests and disconnect-before-effect. Four new Android cases cover queue revision/deserialization and an absent-player read. The Android cases are compiled, **not yet executed** at this checkpoint. Prior foundation 92-device-test evidence does not validate these new changes.

## Remaining work (not claimed complete here)

Physical-device tests; independent verification of these corrections; play/append operations; authoritative URL resolution; persistent grants/Keystore; main-menu UI; opt-in connectivity service; restore/stop/panic integration; authenticated Cloudflare relay, OAuth and real ChatGPT connection. Gateway development in the same worktree is separate from this native audit checkpoint.

The original documentation's temporary-bind and index-plus-URL safety claims are superseded. No production phone catalog was changed, no emulator/UI was launched on the build machine, and no code was delegated to reviewers.
