# Astra findings: correction and verification checkpoints — 2026-10-21

This is an implementation/verification record, not independent review or release approval. The original Astra report and adjudication remain in `doc/reviews/cloud-sync-v1-astra-pro-20261021/`. No live deployment, production activation, phone install, app-data clear or unrelated-project cleanup was performed.

## Completed native-integrity checkpoint

Source `8ba94456fcee82565fe27042b98566694012d9b5` fixes ASTRA-CS-001 and 003. Forget persists OFF before independently committed cleanup and removes the Room witness before its side-store baseline. Prepared full native restores retain the validated original inventory and post-migration file hashes, use private no-backup staging, and reject a lost/changed expected file instead of treating it as an intentionally absent stock input. Originally tagless stock ZIP imports remain supported.

The app and instrumentation APKs built and were installed only on Windows `Pixel_9_API_35`, `emulator-5576`, user 0. **56 native controller/import tests passed**. These include deleting each of Room/tag/binding/settings staging entries independently, same-length byte mutation, immutable preference presence, injection of a tag file originally absent, refusal before shutdown/freeze, and existing complete/stock backup behavior. Missing-file fault injection exercised the corrected product path; it is not presented as a reproduction of the original bug on the old binary.

**Six real host-driven Forget process-kill/restart scenarios passed**, at OFF, before the Room witness commit, after its commit, after metadata cleanup, after binding cleanup and after final connection removal. Each fresh process preserved the full native digest (excluding the intentionally removed cloud witness), local tag definitions and usable guarded native operations, remained OFF, completed repeat Forget and made no fake-peer network call. Real process death is not physical power-loss testing.

APK identities are in `p0-apks.json`; executed logs and per-phase hashes are retained. Further integrated correction, full-suite results and independent Claude review are separate checkpoints and must not be inferred from these results.
