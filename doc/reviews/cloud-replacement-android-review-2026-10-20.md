# Android protected replacement review — 2026-10-20

State: **REQUEST_CHANGES**, two accepted P2 findings. Corrections await Android execution and follow-up review; this is not an approval. Frozen candidate `e5a9affef48bd0e072541b15fd76b77ba9d2d126`; 17 source/test/protocol files, 212,009 source bytes, supplied inline. Review prompt is 215,318 bytes. Backend fix checkpoint was independently closed at `6401e7907`.

Reviewer: `gpt-6-astra`, effort **max**, read-only sandbox, approval policy never. These settings were verified in the persisted turn context. The same authorized session `01a158d5-cfd0-70d3-907a-f3babbf442ee` is used. No coding delegation or Claude call. The reviewer was instructed to use only the frozen inline source, with no tools, edits, commands, network or delegation.

Primary NotCodex evidence root: `C:\Users\Mattj\AppData\Local\Temp\newpipe-recovery-review-20261020`.

- Prompt: `prompt-android-replacement-1.txt`.
- Frozen manifest: `android-replacement-1-snapshot.json`.
- Runner: `run-android-replacement-1.py`.
- Event stream: `events-android-replacement-1.jsonl`.
- Report: `review-android-replacement-1.md`; exit marker: `exit-code-android-replacement-1.txt`.
- NotCodex job: `45780dd4ccae`.

Evidence supplied: backend 266/266 passes; native Android cloud suite 144 executed passes plus 11 explicit skips, including 12 replacement tests. Live replacement was explicitly described as pending. The frozen packet includes controller, UI, native apply, connection/transfer/recovery seams and tests. Its transport file was not included; a subsequent delta must include `CloudHttpClient.kt` and the live-found route correction.

## Live integration finding

The first real replacement attempt failed locally with `INVALID_CLOUD_ROUTE` at `CloudHttpClient.post`, before any replacement request was transmitted. The transport allowlist omitted all six new replacement endpoints; fake-peer controller tests did not exercise that boundary. The outgoing cloud remained at epoch/revision/counter before replacement, and the same three archives remained usable, with no recovery operation or replacement attempt admitted.

The correction adds only the six typed replacement JSON routes. New real-client boundary tests require all six to reach connection admission and verify that an unknown route, query alteration and external URL are still rejected before admission. The authorized live scenario will resume the existing synthetic setup only after verifying its exact cloud head, three archive identities and lack of any pending operation. No app data is cleared.

This review is a bounded implementation checkpoint. Two physical devices, an OS-level network outage, deployed large-catalog resource limits, operator disaster recovery and release readiness are outside its evidence.

## Cancellation corrections

The completed [read-only report](cloud-replacement-android-findings-2026-10-20.md) identified two reproducible issues:

1. An ordinary backup preview can supersede a replacement's recovery approval. Retirement must still cancel the unadmitted replacement without requiring the obsolete recovery approval. The backend now retires the catalog sequence, then resolves only an exact matching allocation if one won the admission race. An unadmitted replacement consumes no recovery sequence and cannot overwrite another recovery receipt.
2. Canceling an unadmitted retry can leave an older STALE attempt. Android now durably records authenticated ACTIVE/STALE discovery independently of its payload marker. It refreshes that authority before removing a canceled intent, preserves the record across controller recreation, and blocks Forget until the server confirms resolution. The screen tells the user when the earlier attempt still needs attention.

Three D1/R2 regression tests cover superseded approval cancellation, preservation of an older STALE attempt and DONE recovery receipt, and a delayed reserve losing to retirement. Two failed before the correction; all three pass after it. TypeScript checking and the full backend suite pass: **269/269**, zero skipped, 86.377 seconds test duration. Primary NotCodex log: `/home/ritorhymes/.cache/newpipe-cloud-replacement-20261020/android-review-backend-fixed.log`.

Four new native tests cover overlapping previews, cancellation of an unadmitted retry across controller recreation, an observed ACTIVE attempt with missing payload staging, and failed authority refresh retaining the final local intent. All four pass on the physical Pixel 7. The full native cloud suite passes **148 executed tests plus 11 explicit assumption skips** (runner reports 159), including 16 replacement tests, in 72.086 seconds on-device. Both corrected QA APK builds and the 2 real-client JVM route tests also pass. See [test evidence](../validation/cloud-replacement-20261020/cancellation-tests.json).

The live run remains paused with all three deployed API gates disabled. Its exact synthetic scope and three usable archives are retained for the guarded resume; no replacement has been published in that scope. Phone connectivity is being restored. No production enable or merge is approved by this checkpoint.

Follow-up read-only review job `686b31251369` is running in the same session with verified `gpt-6-astra` / max / read-only / never settings. Its frozen packet has 11 full files plus the exact correction diff (148,791 prompt bytes), including the previously omitted real HTTP client, change explorer and settings entry point. Snapshot and prompt use suffix `android-replacement-fix-1`; native/live execution was still pending when the review snapshot was submitted.
