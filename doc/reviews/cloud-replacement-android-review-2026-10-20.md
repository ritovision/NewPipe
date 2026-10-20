# Android protected replacement review — 2026-10-20

State: **review running**, not an approval. Frozen candidate `e5a9affef48bd0e072541b15fd76b77ba9d2d126`; 17 source/test/protocol files, 212,009 source bytes, supplied inline. Review prompt is 215,318 bytes. Backend fix checkpoint was independently closed at `6401e7907`.

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
