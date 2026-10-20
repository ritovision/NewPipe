# Preview UI checkpoint — 2026-10-20

Tested on primary NotCodex, isolated QA B on Pixel 7 / Android 17, virtual display 2 (1080×2160 and 2160×1080). Existing data retained; automatic sync off throughout. This checkpoint does not approve production activation.

Candidate: 1de0f39a90acf2f32f5892babeac2787bd9fb6d3 plus the preview changes in this commit. QA B app SHA-256: `79ecae8f9cf1560a64d43d8a799782eb6c39fc4bb7b2f563f73e7cc4912549b2`. Test APK SHA-256: `2f48484344f0dd9ef8f707dc6de48dc7fb6e9f5adc4a9be9ec620ce8771ea52b`.

Build: `:app:testDebugUnitTest --tests org.schabi.newpipe.custom.cloud.CloudChangeExplorerTest --tests org.schabi.newpipe.custom.cloud.CloudHttpRouteTest :app:assembleDebug :app:assembleDebugAndroidTest -PcloudQaPeer=b`, JDK 21, two Gradle workers. All six focused tests passed (four explorer, two route). Both APK builds and in-place install succeeded.

Observed phone checks:
- Replacement status showed all three retained copies with exact archive IDs. Preview named the exact eviction ID and the two survivors.
- Replacement filter text `tagtag` stayed visible after portrait-to-landscape recreation. Clearing it restored the Tags and categories group. Repeated typing/deleting retained editor focus. The replacement was canceled without confirming a new cloud write.
- One-time restore filter `missing` stayed visible after rotation alongside No matching changes. Clearing it restored the one tag-change group. Typing a filter, canceling and starting a fresh preview reset the editor and rows together. The restore was canceled without applying it.
- Portrait orientation was restored on virtual display 2. No owner-package data was cleared.

Screenshots are in the private host QA evidence directory, named 34-replacement, 35-preview, 37-query, 38-query-landscape, 39-query-cleared, 41-restore-preview, 42-restore-filter-rotated, 43-restore-filter-clear and 44-fresh-preview. This UI fixture had one tag change and no video removals; removal/artwork/category semantics are covered by the focused explorer tests rather than a claimed phone removal scenario. No new end-to-end replacement was performed for these display-only changes.

Prior live replacement success at 1de0f39a9 is recorded separately in live-success-1de0f39a9.json and live-apks-1de0f39a9.json. It validated a protected replacement, dirty-peer pause, and restoration of the outgoing cloud archive; it does not validate later source changes by implication.
