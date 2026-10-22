# NewPipe Cloud Sync v1 — live Pixel conflict acceptance

**2026-10-22: PASS for all 8 specified conflict stages on a real Pixel 7.** Two independently installed, synthetic-only QA applications on the same physical Android 17 phone completed a real Cloudflare sync conflict and explicit reconciliation. This closes the exact assertion blocked by earlier emulator startup crashes. It is **not** a two-physical-device or long-running background/Doze/OEM acceptance. Production NewPipe, Music, the previous NewPipe QA installations and personal data were untouched; **no app data was cleared**.

## Executed, verified sequence

1. Both isolated QA apps separately resumed from the same accepted **120-item revision 6** catalog.
2. B made a real local category edit; a host process received and verified a **fsynced durable local-change marker**, then forcibly stopped only B's QA process while its native writer barrier remained held. Its instrumentation output reads `Process crashed.` by design: this specific step **must not** print `OK (1 test)`. The host's durable marker plus actual process stop and distinct saved fingerprint are the pass witnesses; see its SHA-linked result.
3. A edited a different tag, and its normal Android WorkManager auto-sync **published** the resulting catalog to Cloudflare as **revision 7**.
4. B restarted, retained the exact dirty local fingerprint and received **`CLOUD_CONFLICT_REVIEW_REQUIRED`**. The separately accepted remote fingerprint remained A's edit; neither state was silently overwritten.
5. B explicitly reviewed and confirmed the remote copy, saved a verified native safety backup, and ended with its fingerprint equal to revision 7. Both apps then disabled automatic sync and deleted their private QA credential files.

See the [step-by-step sanitized result](validation-summary.json) and the [instrumentation logs](instrumentation/). Seven regular instrumentation steps reported `OK (1 test)`; the deliberate host-killed process step is independently evidenced. All eight original host log SHA-256 values have been reverified. Credentials, raw client IPs and application data are not committed.

## Why the original test timed out

The earlier 600-item CPU fixture had a **61,885-byte backend catalog**, just inside the **64 KiB / 8-page** QA admission limit. When Android exported the same 600 items, it also included native display hints (titles and uploader/metadata), pushing the attempted upload beyond the admitted size. The QA connection recorded: `This library exceeds the server's configured size limit. Nothing was uploaded.` Cloudflare's head stayed at revision 5. The 60-second `LIVE_AUTOMATIC_SYNC_TIMEOUT` was a **test-harness diagnostics issue for a legitimate size-limit refusal**, not evidence of a lost write or a deadlocked scheduler. The QA-only timeout message now includes the connection's human-readable failure reason.

**We did not increase the cloud size limits or weaken correctness checks.** The existing two new QA apps were explicitly disabled, then a newly enrolled synthetic host-only device used **normal authenticated catalog publication** to shrink the test catalog to **120 items / 14,762 bytes** at revision 6, preserving its one category and tag. Both QA apps performed the application's explicit preview-and-confirm **one-time restore** back to the common remote baseline, with their safety backups retained. The conflict sequence was then rerun from that exact common fingerprint, without a database reset, app-data clear or production code change.

**Scaling caveat:** the successful 600-item Worker CPU sample does not establish that 600-item *native* catalogs with exported display hints fit the current 64 KiB admission. Broad production release needs an explicit realistic-size policy and measured expanded envelope; ordinary users with larger libraries could otherwise receive the size-limit refusal. The smaller conflict fixture is a scoped correctness check, not a declaration that all library sizes scale.

## Test-only changes and safe shutdown

The test changes are limited to an opt-in, branch-scoped debug build suffix (`-PcloudQaCpu20261022=isolated`) in `app/build.gradle.kts`, plus the explicit 2026-10-22 synthetic service origin and onboarding/rebase controls in `CloudLiveSyncTest.kt`. No application sync or backend production implementation was modified. Both isolated QA apps and their test APKs built and were installed on the authorized Pixel; prior QA and main installation IDs remained untouched.

After the passing result, the fresh QA Worker was redeployed with **all four API flags OFF**, returning `503 CLOUD_API_DISABLED`. All **six synthetic device credentials were revoked**, no unresolved catalog upload/recovery/replacement remained, and all **three prior R2 backup slots stayed `READY`**. The independent external operator fence has **zero tickets or running actions, but remains `OPEN`**, not `HELD`; earlier platform safety restrictions blocked its formal maintenance-close operation and were not bypassed.

## Release gates after this checkpoint

- **PASS:** deployed Free Worker CPU p95 **6.885 ms** for the scoped 313-request test mix ([original CPU acceptance](../cloud-cpu-qa-20261022/README.md)); backend regression **379/379**; exact live Android conflict and manual reconciliation **8/8**.
- **Still HOLD:** formal maintenance-fence closure; realistic native catalogs larger than the deployed 64 KiB / eight-page envelope; longer independent-device background, Doze and OEM behavior; explicit required provider-unknown/service-generation recovery flows not yet accepted on this final integrated candidate; final production merge/deployment and owner release authorization.

**Release remains HOLD for these non-CPU, non-conflict gates.** This test is not a substitute for them.
