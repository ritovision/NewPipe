# Protected replacement live acceptance — 2026-10-20

Status: **in progress**, not release acceptance.

Source candidate: `e5a9affef48bd0e072541b15fd76b77ba9d2d126`, primary NotCodex, two isolated QA applications on one Pixel 7 / Android 17 / 4 KiB physical device. The owner package and app data were not cleared or overwritten. QA APK hashes are recorded in `initial-apks.json`.

Dedicated Worker QA version: `01423c21-0664-44ab-afcd-5cc681dd2a82`. D1 migration 0003 was applied before deployment. Live D1 successfully evaluated the actual replacement database-clock expression. The temporary synthetic library is `newpipe-replace-qa-0ff7b6b0-49f1-4303-b111-9eabe9f5e851`; service generation `8c5ee780-166d-4497-8ad8-18e129dde998`. Limits remained 64 KiB/eight catalog pages, 1 MiB per archive and 120 requests/minute per edge location. The checked-in production gates remain false.

The first run connected both installations, initialized the catalog, created three verified native recovery archives, reconciled B, and durably created B's dirty local branch before stopping only its process. The first replacement failed before transmission because the real Android HTTP allowlist omitted the replacement routes. `initial-attempt.json` records the failed run; `initial-attempt-server-state.json` proves the cloud stayed at revision/counter 1 with the same three usable archives, no active recovery operation and no replacement attempt.

The route correction and guarded scenario continuation are being verified. Do not describe this initial failure as successful replacement acceptance. The private credential files, APKs and raw device logs remain outside Git. The prepared closeout revokes the two exact synthetic devices, disables all API gates, verifies the exact R2/D1 state, and removes only this test scope after all operations are terminal.

Remaining outside this checkpoint: two physical devices, OS network interruption, deployed large-catalog resource admission, operator index-loss/Time Travel recovery, broader phone usability and whole-feature review/release authorization.
