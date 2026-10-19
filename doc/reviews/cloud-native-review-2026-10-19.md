# Cloud native capture / sparse ordering — independent review handoff

**Status:** Review launched; no completed verdict recorded here yet. This is not an implementation approval.
**Candidate:** `01fac87a762717e49f8a44a5c4d16a138d595af4`.
**Baseline:** `653adb2a399e216151426af86bda22b625b1896b`.
**Reviewer:** Claude Opus, Extra High (`xhigh`), read-only.
**Host:** NotCodex-2, no browser/GUI.
**Persistent session:** `7cf1e224-5044-423f-b4b4-e90c33fe5783`.
**tmux:** `newpipe-cloud-native-review-20261019`.
**Packet:** `/home/rito/.cache/newpipe-cloud-native-review-20261019/`; 26 exact committed files, per-file hashes in `manifest.json`, read-only source under `source/`.
**Resume:** `resume-review.sh` in that directory resumes this same UUID at Extra High with `continue`; do not start a new review after a usage reset.

## Scope

Native read-side capture of the real Room/tag catalog; persisted portable identity mapping and tag schema 4; full/stock backup map sanitization; sparse exact sequence compilation. Inspect domain semantics, legacy/corrupt data, failure/lock behavior, identity lifetime and regression tests. Record-level remote apply, large sealed D1 staging, the full deployed catalog schema/transport, UI and R2 remain later gates and are not claimed implemented.

Read/Glob/Grep only. Bash/edits/tests/agents/delegation/browser/MCP/network are disabled. The coordinating assistant is the only implementer. No prompts, tokens or personal library content are included in the packet.

## Launch notes

The initial packet preparation referenced a Java filename that is Kotlin in this fork; packet creation stopped before launching inference. Corrected to the actual `NewPipeDatabase.kt`, rebuilt all file hashes, and launched the one persistent session listed above. Claude initially requested trust for the newly created workspace; the coordinator verified its committed read-only contents, approved that folder, and confirmed that the persisted review session was created. No global model/login setting was changed.

## Test correction outside the frozen candidate

The first complete native run executed 49 tests: all 25 new native capture tests, 8 protocol groups and 14 backup regressions passed; two older backup-migration fixture tests failed. Those fixtures had been synthesized from the current tag DB and merely relabeled as v1/v2 while retaining the newly added v4 cloud tables, and still expected target version 3. The correction removes v4 tables before assigning the historical version and expects `TagStore.SCHEMA_VERSION`. It does not weaken production migration validation with CREATE IF NOT EXISTS or drop user tables. Rerun results are recorded separately after execution.

An earlier post-install emulator startup ANR happened before tests began. An unchanged-APK focused retry passed; it is recorded as an initial failed run, not as a pass or a proven 16 KiB compatibility defect.
