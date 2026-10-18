# Unavailable Playlist — post-review correction pass

Baseline: 73df1892b51f22c2ec3c2d78e38e129400dbfff5 on feature/unavailable-playlist-20261017.
Review request: Astra Max only. No further Claude review and no downgrade/migration feature for disposable QA data.

## Changes made

1. **Frozen AI bulk selection:** remove_all_unavailable requires expected_revision at gateway validation and device protocol admission; the device also checks it inside the atomic catalog transaction. Missing or changed revisions reject without deleting; replaying a committed UUID does not include subsequently marked items. Explicit user confirmation is not added to headless AI commands.
2. **Native menu state:** observe the Unavailable identity itself after binding/reset, and invalidate the toolbar options when committed playlist rows change. Clear/Remove All are disabled when empty, enabled when populated, without reopening the screen.
3. **Deferred marking:** retain requested data operations after view detachment, using the existing non-coalescing save-queue callback. A failed preceding save still prevents the mark and retains the Reload recovery action. The repository reference is captured before deferral so an old request cannot jump to a replacement catalog; lookup failures are delivered as Rx errors rather than thrown from a tap handler.
4. **Confirmed mutation lifetime:** Clear, Remove All Everywhere, and the new individual Remove Everywhere no longer share subscriptions that startLoading clears. View callbacks are guarded. Typed stale-selection failures present an actionable Reload message and do not re-confirm or re-target automatically.
5. **AI identity recovery:** authorized Unavailable commands retry verified setup before their Room transaction; reading playlist discovery can recover after a same-name collision is resolved. No implicit adoption of a name-only playlist.
6. **Bookmarks:** do not offer Delete for protected/name-only candidates, or Use as Unavailable when another protected list is already bound. Rename remains available for ordinary name-collision recovery.
7. **Java compatibility:** replace the two newly introduced Stream.toList calls with the surrounding-code-compatible Collectors.toList idiom. No minimum-Android increase.
8. **Rare recovery dead end:** a typed mixed-batch safety stop is reproduced in tests. Only a fully preflight-validated backup containing both databases may replace that blocked catalog. Stock backups retaining existing tags still fail closed. No partial deletion, broad exception suppression, new schema, or downgrade support was added.
9. **UI copy:** keep all user-requested removal confirmations. The bulk delete positive action says Remove Everywhere. Stale selections ask for Reload and re-confirmation instead of reporting a generic programming error.

## Verification recorded before final review

- Headless Android/JVM build succeeded: /tmp/unavailable-cf-build-r5.log. A final small Rx lookup-error guard is undergoing final build/regression.
- Core Android suite: 164/164 passed on the separate Pixel 7 QA package, ~/.cache/newpipe-unavailable-qa/native-cf-fixes-r2.log.
- Actual native Fragment/toolbar/dialog suite: 3/3 passed on explicitly selected scrcpy display 21, ~/.cache/newpipe-unavailable-qa/native-cf-ui-r1.log. Covers empty/populated/cleared menu transitions and cancel/confirm; confirmed Clear with its IO delayed across reload; two delayed marks surviving navigation away. No activity was allowed to launch on display 0. Dialog screenshots were visually inspected.
- Gateway TypeScript check and 59/59 tests passed: /tmp/unavailable-cf-gateway-check.log and /tmp/unavailable-cf-gateway-test.log on the build host.
- One initial regression expected NOT_EXECUTED instead of the existing REJECTED result enum; the assertion was corrected, retaining exact error-code and zero-deletion assertions, then all 164 passed.
- A 1,000-item in-memory AI batch completed in 1,540 ms on the phone. This is not a file-backed/worst-case 5,000-item performance guarantee; no speculative optimization was made.

## Deliberately not expanded

No network scanner, automatic replacement engine, cloud synchronization, or new migration/downgrade support. Existing forward schema handling was not redesigned. Long-press lock latency and large file-backed batch tuning remain follow-up measurement items, not proven release failures. Production installations and their Library data were untouched; only the feature-specific QA package was updated. NotCodex-2's connector was interrupted during the first build; subsequent headless builds remained on that same machine via the primary machine's established SSH connection. No emulator was launched.

## Review status

Astra Max completed this correction-pass review with **no actionable new defects** and **READY FOR USER ACCEPTANCE**. The session header confirms model gpt-6-astra, effort max, sandbox read-only. The unchanged full report is saved in [ASTRA_MAX_POST_CLAUDE_REVIEW.md](ASTRA_MAX_POST_CLAUDE_REVIEW.md). Its final-build test caveat is addressed by the final runs below; limitations about gesture dispatch and worst-case performance still apply. This is not permission to merge or deploy.


## Final exact-build acceptance evidence

- Final Android debug APK, AndroidTest APK, and JVM suite: BUILD SUCCESSFUL, /tmp/unavailable-cf-final-build.log.
- **314/314 JVM tests**, 37 suites, zero failures/errors/skips, from final testDebugUnitTest XML reports. The save-queue suite is included, not an additional total.
- **165/165 core Android instrumentation tests** on the final QA APK, in a fresh instrumentation process. Raw result: [native-cf-core-final-isolated.log](native-cf-core-final-isolated.log).
- **3/3 actual native UI instrumentation tests** on the same final QA APK, in their own fresh instrumentation process on the selected scrcpy virtual display. Raw result: [native-cf-ui-final-isolated.log](native-cf-ui-final-isolated.log).
- **59/59 gateway tests** and TypeScript validation passed. The revised gateway schema is not deployed to production by this work.
- QA app SHA-256: baff898d72cda0107e1388d8987ade4af209580d680b2cb057e6e40646490e95.
- QA test APK SHA-256: 346d2605cb512d631504f421713fe5232c745d4a7443beccca602f6f4a60269f.

### Test-run limitation, not counted as a pass

A combined run of all core and UI classes in a single instrumentation process reached the UI phase and failed with `ActivityScenario: Cannot run onActivity since Activity has been destroyed already`, then the runner process ended. Its raw failed log remains on the primary machine at `~/.cache/newpipe-unavailable-qa/native-cf-all-final.log`. The cause of that combined-run lifecycle interaction is not established. The unchanged final APK subsequently passed the complete core suite and all UI tests in separate fresh instrumentation runs. Do not report the combined run as 168/168 passed or claim its lifecycle problem was fixed. Use the two explicitly separated runs as acceptance evidence.

Our scrcpy session was stopped after testing; the separate QA installation remains. No emulator, production NewPipe install, cloud deployment, merge, or further Claude invocation was performed.
