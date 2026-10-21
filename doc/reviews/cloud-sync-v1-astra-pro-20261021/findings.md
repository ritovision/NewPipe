# Prioritized findings

Implementation: `1b7ab927a7840f3fc0e0b41cfd281199de77a6c8`, branch `feature/cloud-sync-v1-20261019`. References are pinned to that implementation commit, not the later documentation commit.

## Classification

Severity measures the demonstrated consequence and plausible trigger. **Confirmed** means that the implementation path is unambiguous or a reproduction established it; the evidence subsection identifies whether that was inspection, isolated local execution, Android execution or live execution. **High-confidence** means a strong source-backed failure scenario still needs the specified component-level reproduction. No suspected-only issue is included in the actionable count.

| ID | Severity | Confidence | Release blocker | Short description |
| --- | --- | --- | --- | --- |
| ASTRA-CS-001 | High | Confirmed | Yes | Forget can leave a Room witness after deleting its baseline. |
| ASTRA-CS-002 | High | Confirmed | Yes | Recovery-preserved catalog archives fail the new-generation restore guard. |
| ASTRA-CS-003 | High | High-confidence | Yes, until fixed or disproved by native evidence | Evicted prepared files can silently change native restore contents. |
| ASTRA-CS-004 | Medium | Confirmed | Yes | One-time restore preserves an already-enabled sync mode. |
| ASTRA-CS-005 | Medium | Confirmed | No, by itself; bounded repeat-cancel workaround observed | Replacement cancellation exhausts the public statement allowance and misreports an unknown outcome. |
| ASTRA-CS-006 | Medium | Confirmed | Yes for the specified automatic-stop behavior | Permanent credential rejection does not stop future automatic work. |
| ASTRA-CS-007 | Low | Confirmed | No | Unavailable-only ordering changes are absent from exact details. |
| ASTRA-CS-008 | Low | Confirmed | No | Recovery search becomes an invisible retained filter after rotation. |

**Counts:** Critical 0; High 3; Medium 3; Low 2; total 8; release blockers 5. No authentication bypass or confirmed live data loss was found. Absence of such a finding is not a universal security certification.

## ASTRA-CS-001 — Forget commits baseline deletion before the Room witness is durable

**Severity:** High

**Confidence:** Confirmed — code inspection plus an isolated SQLite crash-order reproduction

**Subsystem:** Android connection removal; Room/tag-store recovery journal

**Blocks release:** Yes

### Exact source and violated invariant

- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt:138–156](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L138-L156) nests the independent tag-store transaction inside a Room transaction. Tag identity/baseline deletion commits before Room witness deletion commits.
- [app/src/main/java/org/schabi/newpipe/custom/tagging/TagStore.kt:50–59](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/tagging/TagStore.kt#L50-L59) commits the tag database independently.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyStore.kt:115–118](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyStore.kt#L115-L118) discards the baseline/journal metadata.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:197–218](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt#L197-L218) requires a surviving Room witness to have a matching baseline when no pending plan exists.
- [app/src/main/java/org/schabi/newpipe/custom/tagging/TagRepository.kt:36–52](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/tagging/TagRepository.kt#L36-L52) invokes that recovery guard before ordinary guarded native operations.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt:120–134](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L120-L134) already uses the safe opposite ordering during reconnection.

Disconnect must leave native data usable and must not manufacture an unrecoverable cross-database journal mismatch. The specified disconnect operation disables sync and forgets connection authority; it does not impair local catalog use. See [doc/specs/cloud-sync-v1.md:39–51](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/doc/specs/cloud-sync-v1.md#L39-L51).

### Concrete failure scenario

1. Complete a normal cloud apply. The tag database contains the acknowledged baseline, and Room retains its matching apply witness.
2. Choose Forget with no pending upload/recovery/replacement. The method opens a Room transaction, then commits removal of the tag-side context and baseline in its separate database.
3. The process dies after that tag commit, either before the Room DELETE executes or after it executes but before the Room transaction commits.
4. SQLite rolls back the Room transaction. The witness survives; the baseline does not.
5. On the next guarded native/tag operation, `recoverLocked` throws `CLOUD_RECOVERY_JOURNAL_LOST`. Forget and reconnect also call recovery before their cleanup, so simply repeating those actions does not resolve the mismatch.

The shared Java lock cannot make two separate SQLite commits atomic.

### Impact and evidence

Local content is not shown to be deleted. The defect produces a persistent recovery-blocked state in guarded catalog/tag operations and interferes with the supported connection-removal/reconnect flow. A verified complete native backup import or salvage path remains relevant; this is not a claim that all app functions are permanently unusable.

A disposable Python probe used two real WAL SQLite databases containing one matching witness/baseline. A child committed the side deletion, then exited with `os._exit(17)` at each of the two boundaries above. In both cases reopening showed:

| Boundary | Room witness | Tag baseline | Existing recovery predicate |
| --- | --- | --- | --- |
| After tag commit, before Room DELETE | Present | Absent | Fails |
| After Room DELETE, before Room commit | Present | Absent | Fails |

This confirms the underlying database ordering failure, **not an Android/Room process-kill execution**. No app database was used. Existing controller tests cover happy-path Forget after apply and pending-operation exclusion, but no kill at this ordering boundary: [app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt:415–439](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt#L415-L439).

### Smallest safe correction

Persist OFF with a new local generation before destructive connection cleanup. Commit Room witness removal **before** deleting the side baseline/context, following the existing reconnection ordering. Make the remaining metadata/credential cleanup safely repeatable after restart; add a small durable cleanup marker only if the remaining transitions cannot be made idempotent. Do not weaken the general witness-without-baseline guard, because it detects real corruption elsewhere.

### Suggested regression

After a successful native cloud apply, interrupt Forget at every independent commit/rename boundary, especially the two demonstrated above. On restart verify exact native rows and unrelated metadata, permitted local/tag operations, sync OFF, no automatic request, and successful repeat Forget. Execute the important boundaries with actual host process death on an isolated Android fixture, not only exception injection.

## ASTRA-CS-002 — Preserved catalog archives cannot be restored after generation recovery

**Severity:** High

**Confidence:** Confirmed — deterministic cross-component scope contradiction in code

**Subsystem:** Operator archive reconstruction, recovered-service reconnection, historical catalog restore

**Blocks release:** Yes

### Exact source and violated invariant

- [cloud/operator/recovery.mjs:229–237](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/cloud/operator/recovery.mjs#L229-L237) revokes old authority and rebuilds archive slots while preserving each archive's original source service and manifest.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt:79–88](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L79-L88) requires fresh-generation, same-library reconnection using a fresh device.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryController.kt:253–278](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryController.kt#L253-L278) rejects a cloud-catalog archive unless its recorded source service generation equals the current connection's service generation.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyCompiler.kt:32–35](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyCompiler.kt#L32-L35) and [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyPlan.kt:99–103](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyPlan.kt#L99-L103) also require target-head scope to match receiving identity context.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt:77–89](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt#L77-L89) keeps same-service baseline checks outside the historical-counter exception.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryScreenModel.kt:187–192](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryScreenModel.kt#L187-L192) presents backend-usable archives as verified copies available for restore.

Recovery must preserve useful recovery copies across a DATA restore and fresh service-generation enrollment. Historical source provenance must not be confused with current device authorization.

### Concrete failure scenario

1. Generation A creates a valid cloud-catalog archive.
2. An operator restores/reconciles DATA under the external fence, reconstructs the verified archive slots and prepares generation B. Preserving the immutable A manifest and bytes is correct.
3. A device reconnects using a fresh B credential. Reconnection explicitly requires B to differ from A.
4. The owner selects the preserved A archive for a one-time historical local restore.
5. Download verification can succeed, but `prepareRestore` throws `CLOUD_SCOPE_MISMATCH` at line 270 because A is not B. Merely removing that check would still fail the compiler/plan scope checks later.

The retained final QA record preserves three cloud-catalog archives through recovery. This review did not reopen it or attempt a restore there.

### Impact and evidence

The ordinary Android restore path cannot use the very cloud-catalog copies preserved for disaster recovery after a generation change. Archive bytes are not lost, and native device-ZIP restore is a different path; this is an integrated recovery-completeness defect, not an R2 destruction claim.

The existing historical-restore test exercises an older revision within **one** service generation and correctly preserves the highest observed cloud head: [app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudRecoveryControllerTest.kt:120–136](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudRecoveryControllerTest.kt#L120-L136). Operator archive-rebuild tests and Android reconnection tests cover their pieces separately. None of those proves their composition with an old-generation archive.

Evidence is source inspection plus retained recovery/reconnection records. There was no new Android or live reproduction.

### Smallest safe correction

Represent historical archive provenance separately from the current receiving scope. Permit an explicitly reviewed, local-only import of a verified archive from an older generation of the **same library**, while keeping current credentials, current service identity, current highest-observed authority and synchronization OFF.

Carry that distinction through the apply compiler and persisted journal format; the round-trip decoder must understand the same semantics. Do not “fix” this by relaxing ordinary synchronization scope checks, accepting a different library, adopting an old token, or lowering current rollback counters.

### Suggested regression

Compose the complete scenario: archive in A; restore/rebuild and reopen as B; reconnect a device with fresh B authority; preview/apply the A archive. Verify exact native catalog contents, preserved unrelated native data, current B identity/counters, no cloud publication, and OFF after restart. Reject wrong-library archives and old credentials. Kill/restart at the historical native-apply journal boundaries after introducing the new provenance representation.

A future live end-to-end run is justified only in a separately authorized isolated cycle; it is not authorized by this report.

## ASTRA-CS-003 — Eviction of prepared native files can change the approved restore

**Severity:** High

**Confidence:** High-confidence — source-backed failure path; native reproduction still required

**Subsystem:** Full native ZIP preparation and installation, including cloud recovery restore

**Blocks release:** Yes until corrected or disproved with native evidence

### Exact source and violated invariant

- [app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:49–52](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L49-L52) stores only a prepared directory/generation and derives preference presence from current filesystem existence.
- [app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:67–110](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L67-L110) creates that directory under `context.cacheDir`. The validated ZIP inventory `seen` is local to preparation and is not retained.
- [app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:163–198](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L163-L198) treats a missing prepared tag file as a stock backup that intentionally omitted tags, then snapshots the **current** tag database.
- [app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:199–217](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L199-L217) passes that substituted file to the atomic native file swap; missing binding is also interpreted as absent input.
- [app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:120–151](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L120-L151) can parse no surviving prepared settings as an empty map and then clear preferences when settings restore was selected.
- [app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:412–428](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L412-L428) checks space, not the original prepared inventory/digests.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryController.kt:293–332](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryController.kt#L293-L332) rechecks the original downloaded payload but subsequently installs the separately prepared files.

The approved frozen candidate must be the candidate installed. Missing staging must require failure/re-preparation, not a different restore mode: [doc/specs/cloud-sync-v1.md:87–91](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/doc/specs/cloud-sync-v1.md#L87-L91).

### Concrete failure scenario

1. Prepare a valid complete native archive containing Room contents A and tag contents A. Keep the installed Room/tag stores at distinguishable contents B.
2. While the owner reads the preview, only the staged tag file is evicted from cache; the staged Room file and the persistent downloaded archive remain. Cache reclamation has left sufficient current free space for preflight to pass.
3. Confirm the restore. The downloaded archive's digest still matches; it has not changed.
4. The installer sees no staged tag file and takes its legitimate stock-backup branch, copying and sanitizing current tags B.
5. The file-swap mechanism safely installs the **wrong pair**: archived Room A with current tags B. Its transaction cannot detect that the caller substituted a different candidate.

A related case occurs when staged settings existed at preview time, the user selected settings restore, and those files disappear before `readPreferences`: an empty map may be applied with `clear()`. Lost binding metadata is another file-inventory case to cover.

### Impact and confidence boundary

The restore can report success while omitting archived tags or replacing settings with an unintended empty set. The original cloud archive remains intact, but the approved complete restore was not performed. This is a plausible silent integrity failure.

Android explicitly documents that internal cache files may be removed under storage pressure: [Android app-specific cache storage](https://developer.android.com/training/data-storage/app-specific#cache-files). The candidate's own validation notes report cache-based fixture loss under low storage, but **that is not a reproduction of this product path**.

The exact per-file eviction scenario has not been executed against Android by this reviewer. That missing evidence is why confidence is High-confidence rather than Confirmed. Loss of the entire directory or required Room file can fail instead; the finding concerns loss of an optional-by-existence member while other required files survive.

The existing archive digest check, space preflight and atomic file swap were considered. None binds the prepared tag/settings/binding inventory to the already validated original. Cloud native-upload metadata does require both database entries in its original ZIP; that does not protect files lost after restore preparation.

### Smallest safe correction

Retain the validated entry inventory and integrity identities of the sanitized/migrated prepared files. Before AI shutdown, disabling work, or swapping installed files, verify that every expected prepared file is present and unchanged. Keep authoritative restore staging outside evictable cache, or re-prepare from the same verified immutable archive and revalidate the exact candidate when staging is lost.

Preserve legitimate stock ZIP imports that originally contain no tag database. The distinction must come from the validated original inventory, never a later `Files.exists` result. Handle optional settings/binding consistently.

### Suggested regression

In an isolated native fixture, prepare a complete archive whose tags differ from installed tags, remove **only the prepared tag file**, then confirm. The result must either fail before installed-state/AI changes or faithfully reconstruct and install the exact archive. Repeat for binding and selected preference files, and prove a genuinely tagless stock ZIP still follows the intended preservation behavior.

Existing native full-roundtrip/stock-import tests are relevant starting points: [app/src/androidTest/java/org/schabi/newpipe/custom/tagging/TagBackupIntegrationTest.kt:105–155](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/androidTest/java/org/schabi/newpipe/custom/tagging/TagBackupIntegrationTest.kt#L105-L155). Add an inventory-loss test between prepare and install; checksum corruption of the original ZIP is a different scenario.

## ASTRA-CS-004 — One-time restore leaves an already-enabled connection enabled

**Severity:** Medium

**Confidence:** Confirmed — direct UI-to-controller state transition

**Subsystem:** One-time current-cloud restore and automatic mode consent

**Blocks release:** Yes

### Exact source and violated invariant

- [app/src/main/java/org/schabi/newpipe/settings/CloudSyncSettingsFragment.kt:46–48](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/settings/CloudSyncSettingsFragment.kt#L46-L48) calls `model.prepare(false)` for the one-time restore.
- [app/src/main/java/org/schabi/newpipe/settings/CloudSyncSettingsFragment.kt:105–110](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/settings/CloudSyncSettingsFragment.kt#L105-L110) keeps that action available while sync is enabled.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScreenModel.kt:119–120](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScreenModel.kt#L119-L120) forwards the one-time intent.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt:249–255](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L249-L255) uses `enabled = if (preview.enableAfter) true else it.enabled`, retaining true for a false one-time intent.
- [doc/specs/cloud-sync-v1.md:29–38](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/doc/specs/cloud-sync-v1.md#L29-L38) explicitly says “Use cloud library once” leaves sync off.

### Concrete failure scenario and impact

With an enabled, quiescent connection, select Use cloud library once, prepare and confirm successfully. The local catalog is restored, but `enabled` remains true and the message says automatic sync is on. Later local edits can publish, and later cloud changes can apply automatically. The operation does not provide the promised one-time mode.

This is not hidden behind an unreachable UI state and does not require a race or malformed server. It matters because ongoing automatic mutation is a separate consent decision. Existing mode-enabled behavior does not override the one-time action's specified postcondition.

### Evidence and smallest safe correction

The current test [app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt:253–262](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt#L253-L262) begins with a freshly connected OFF state. It demonstrates “does not enable from OFF,” not “ends OFF from every admitted initial state.” The retained live one-time steps also disable first. No new Android test was run.

Make the one-time path explicitly persist OFF and a fresh connection generation under the writer barrier before it can admit the destructive one-time apply. Bind the preview/admission to the resulting generation and reconcile/cancel scheduled work. Keep the enable-sync path separate. Account for process death, stale work and cancel behavior; changing the switch label after applying is insufficient. Preserve the existing warning that OFF cannot undo a request already sent.

### Suggested regression

Begin enabled; prepare and confirm the one-time operation; assert OFF and a changed generation, then edit locally and simulate periodic/native-change scheduling. Assert no further automatic HTTP or apply. Repeat after process restart and with queued old-generation work. Retain the current OFF-start test and verify explicit enable still enables.

## ASTRA-CS-005 — Replacement cancellation exhausts its request allowance and reports an unknown outcome

**Severity:** Medium

**Confidence:** Confirmed — current production router executed with real local Miniflare D1/R2

**Subsystem:** Protected replacement cancellation, recovery cleanup, public SQL accounting

**Blocks release:** No, by itself. Exact retry completed both demonstrated cases; fix or document the bounded workaround before a limited candidate is accepted.

### Exact source and violated invariant

- [cloud/src/replacement.ts:106–157](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/cloud/src/replacement.ts#L106-L157) composes repeated status/authority checks, catalog retirement, recovery abandonment and final attempt settlement in one request.
- [cloud/src/recovery.ts:269–277](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/cloud/src/recovery.ts#L269-L277) delegates the active recovery operation to abort.
- [cloud/src/recovery.ts:455–504](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/cloud/src/recovery.ts#L455-L504) persists ABORTING and then performs another guarded cleanup batch.
- [cloud/src/budget.ts:9–14](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/cloud/src/budget.ts#L9-L14) sets the public ceiling to 32 and reserves five statement positions for accounting.
- [cloud/src/budget.ts:123–126](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/cloud/src/budget.ts#L123-L126) refuses the next batch before dispatch if it would exceed that allowance.
- [cloud/src/recovery.ts:26–29](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/cloud/src/recovery.ts#L26-L29) converts the resulting non-RecoveryError into `RECOVERY_OUTCOME_UNKNOWN`.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudReplacementController.kt:300–320](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudReplacementController.kt#L300-L320) issues a single cancellation call and reaches local completion only after success.

A supported bounded cancellation path should fit its admitted request envelope or expose a deliberate resumable cleanup transition. A known pre-dispatch budget refusal should not masquerade as an uncertain database write.

### Reproduction and observed result

The reviewer reused the existing `cloud/test/recovery.test.mjs` HTTP fixture/helpers in temporary files outside the repository. All calls went to local Miniflare with `cf:false`, disposable D1/CONTROL/R2, synthetic credentials and current production source. No Worker deployment or live resource was used.

For each state, independently:

1. Seed the standard CONTROL budget and an initialized base catalog.
2. Enable the synthetic replacement grant and prepare a valid outgoing archive/incoming catalog replacement.
3. Reserve replacement, leaving its recovery operation RESERVED. For the second case, advance once to UPLOADING.
4. POST `/v1/replacement/abandon` with the exact approval ID and recovery sequence.
5. Inspect the response, DATA state and CONTROL accounting; repeat the identical cancellation once.

| Initial state | First response | Intermediate state | DATA statements dispatched | Exact retry |
| --- | --- | --- | --- | --- |
| RESERVED | 503 `RECOVERY_OUTCOME_UNKNOWN` | Recovery ABORTING, replacement ACTIVE | 27 | 200, replacement CANCELED |
| UPLOADING | 503 `RECOVERY_OUTCOME_UNKNOWN` | Recovery ABORTING, replacement ACTIVE | 27 | 200, replacement CANCELED |

A counter-only instrumentation change to the **test adapter**, not production source, established the 27 dispatched DATA statements. Together with five reserved accounting positions, no allowance remains for the next guarded cleanup batch. A final rerun loading DATA migrations 0001–0006 and CONTROL 0001–0003 reproduced the same result, ruling out the original fixture's reduced DATA schema as the cause.

All three probe invocations produced the same two first-response assertion failures; all six exact retries completed. The assertions expected the initial cancellation to finish with 200, so the logs correctly report **0/2 passed, 2 failed** per invocation. CONTROL remained ACTIVE with zero held reads/writes and zero request tickets in the inspected results. No catalog loss was observed.

Minimal regression shape, using the existing fixture helpers:

```javascript
for (const phase of ['RESERVED', 'UPLOADING']) {
  await enableReplacement();
  const source = await publishCatalog('base');
  const p = await reserveReplacement(await replacementPreview(source));
  if (phase === 'UPLOADING') {
    const op = ok(await recovery('advance', ref(p.operation)));
    assert.equal(op.state, 'UPLOADING');
  }
  const result = await replace('abandon', {
    approvalId: p.preview.approvalId,
    recoverySequence: p.attempt.recoverySequence
  });
  // Under a one-request contract:
  assert.equal(result.status, 200);
  assert.equal(result.data.attempt.state, 'CANCELED');
}
```

This is a reproduction recipe, not an implementation patch. If cleanup is intentionally split into several requests, change the expected protocol and test bounded progress to a terminal state.

### Impact and existing coverage

The first cancel reports an unknown failure and leaves the pending operation visible until repeated. A user may mistake this for a service/recovery failure. It is **not** a breach of the statement cap: the cap works and prevents dispatch. It is also not demonstrated to strand the fence or lose an archive.

Existing cancellation cases cover unadmitted reservations, READY archives and uncertainty cases, but do not establish this nested RESERVED/UPLOADING HTTP path within the final budget wrapper. A successful endpoint helper or cancellation before reservation is not enough.

### Smallest safe correction and regression

Consolidate redundant authenticated reads where the transactional guards already supply final authority, or make cancellation explicitly bounded/resumable with truthful progress and a client that continues the exact operation. Preserve all final scope, revocation, sequence and archive checks; retain the 32-statement ceiling. Propagate a known statement-budget error accurately instead of wrapping it as an unknown write.

Cover RESERVED, UPLOADING, READY, three-slot rotation, concurrent publication and retry. Assert per-request statement bounds, terminal local/server state, immutable outgoing safety archives, correct receipt/high-water behavior and zero settled CONTROL holds. The demonstrated workaround is to repeat the same pending cancellation, not discard its identity or start a new operation.

## ASTRA-CS-006 — Permanent credential rejection does not stop future automatic work

**Severity:** Medium

**Confidence:** Confirmed — controller/scheduler state transitions

**Subsystem:** Android revocation response, persistent scheduling and connection lifecycle

**Blocks release:** Yes for the specified immediate-stop behavior; this is not a server authorization bypass.

### Exact source and violated invariant

- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt:258–264](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L258-L264) admits automatic sync whenever the persisted connection remains enabled.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt:476–480](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L476-L480) records only a message after failure; it does not invalidate that generation or disable work.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScheduler.kt:45–74](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScheduler.kt#L45-L74) cancels periodic/immediate work and detaches observers only when enabled is false.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScheduler.kt:78–89](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScheduler.kt#L78-L89) continues accepting native-change wakeups for enabled connections.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScheduler.kt:107–120](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScheduler.kt#L107-L120) returns `Result.success()` for a nonretryable authorization error, ending that occurrence without removing the periodic request.
- [doc/specs/cloud-sync-v1.md:53–59](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/doc/specs/cloud-sync-v1.md#L53-L59) requires revoked credentials and unexpected authority changes to stop automatic work.

### Concrete failure scenario and impact

An enrolled, enabled device receives a permanent `INVALID_CREDENTIAL`/revocation response. The worker records a rejection message and completes the current invocation. The persisted toggle stays enabled, its periodic request remains, and later native-change notifications can enqueue more work. Restart/reconcile schedules it again because enabled is still true.

The server correctly rejects the token. No successful unauthorized read/write is claimed. The defect is repeated automatic traffic and background wakeups with an unusable credential, unnecessary use of battery and the project's admission/authentication budget, and a UI/lifecycle state inconsistent with the stated stop rule.

### Evidence and smallest safe correction

This finding is established by code inspection; no Android worker revocation test was run during review. Backend revocation tests establish server rejection, not cancellation of Android's future WorkManager activity. Returning success for one periodic occurrence does not cancel the periodic work; see [Android persistent work guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

On a permanent credential/authority rejection, persist a paused/OFF state and new local generation under the writer barrier, then reconcile work and observers. Preserve the credential record and unknown/pending receipts needed for explicit recovery. Do not treat ordinary offline errors or retryable 429/503 responses as permanent rejection.

Make the transition conditional on the generation that actually failed. `recordFailure` currently reads the latest connection; adding an unconditional disable there could let a late old-generation error disable a freshly reconnected connection.

### Suggested regression

Start enabled with periodic and immediate work present; deliver a terminal 401; verify durable OFF/paused status and no further automatic HTTP after native edits, later periodic time, and process restart. Verify explicit fresh-authority enrollment can resume. Separately cover transient offline/503 backoff and a late failed response from an obsolete generation arriving after reconnect. Inspect actual WorkManager state in native acceptance, not only the controller's message.

## ASTRA-CS-007 — Unavailable-only ordering changes are absent from exact details

**Severity:** Low

**Confidence:** Confirmed — semantic diff produced but not rendered

**Subsystem:** Catalog diff / exact preview explorer

**Blocks release:** No; bounded ordering-only preview gap

### Exact source and violated invariant

- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudCatalog.kt:211–221](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudCatalog.kt#L211-L221) fingerprints ordered catalog collections, including Unavailable.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudCatalog.kt:297–314](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudCatalog.kt#L297-L314) exposes the Unavailable sequence difference.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudChangeExplorer.kt:18–35](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudChangeExplorer.kt#L18-L35) renders Unavailable membership additions/removals and Library ordering, but has no branch for `unavailableSequenceChanged`.
- [doc/specs/cloud-sync-v1.md:78–91](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/doc/specs/cloud-sync-v1.md#L78-L91) requires every approved change to be discoverable.

### Scenario, impact and evidence

Use two otherwise identical valid catalogs: Library `[A, B]`; Unavailable `[A, B]` before and `[B, A]` after; no ordinary playlists, tags or category changes. The fingerprint/diff detects an ordered change, but the explorer emits no corresponding details because set membership did not change. The approved operation can reorder that native protected playlist without disclosing the move.

This does not delete a video or change its Unavailable membership. Evidence is direct inspection, not a new rendered UI run. The existing `CloudChangeExplorerTest` cases exercise artwork and tag/category details, not this pure ordering case.

### Smallest safe correction and regression

Add an Unavailable ordering group using the existing occurrence-aware order helper, with sufficient service/URL identity to identify affected items. Test the two-item case, unchanged order, membership changes together with order, and searchability of moved rows. Confirm that the summary/details never describe a fingerprint-changing target as having no discoverable change.

## ASTRA-CS-008 — Recovery search becomes an invisible retained filter after rotation

**Severity:** Low

**Confidence:** Confirmed — retained model and recreated view disagree

**Subsystem:** Historical cloud-catalog recovery preview UI

**Blocks release:** No; the frozen candidate is unchanged and the filter can be cleared by editing

### Exact source and violated invariant

- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryDialog.kt:32–34](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryDialog.kt#L32-L34) retains the model at activity scope.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryDialog.kt:71–92](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryDialog.kt#L71-L92) creates a new search editor with saved state disabled, but does not initialize it from the retained query or resynchronize it when details change.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryScreenModel.kt:45–48](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryScreenModel.kt#L45-L48) retains a private query.
- [app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryScreenModel.kt:161–185](https://github.com/ritovision/NewPipe/blob/1b7ab927a7840f3fc0e0b41cfd281199de77a6c8/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryScreenModel.kt#L161-L185) filters all rendered rows by that query.
- The main preview and replacement dialogs already have query-restoration patterns that can be reused.

A visible search field must describe the active filter, especially in a destructive historical-restore preview.

### Concrete failure scenario and impact

Prepare a historical cloud-catalog restore with several change groups, enter a filter and rotate the activity. The activity-scoped model retains the nonempty query, while the new editor starts blank. The row list remains filtered. The user sees an apparently unfiltered subset of changes and cannot clear the existing filter by deleting from an already empty field; typing and then clearing a character works.

The approved candidate remains frozen and is not changed by rotation. The defect conceals details through a misleading UI state rather than corrupting the target.

### Evidence, correction and regression

This is a source-based finding; no recovery dialog was opened on Android during this review. The three retained theme/rotation scenarios instantiate the **main** change preview, not this recovery dialog, so they do not cover it.

Expose the retained query and initialize/resynchronize the editor consistently with the other dialogs. Avoid resetting it accidentally while binding the new view. Add a recovery-dialog rotation test with a nonempty filter and multiple real change groups: query text and row set must agree before/after rotation, and clearing the text must restore every matching group/page. Include cancellation and a fresh preview so an old query cannot invisibly affect a new candidate.

## Findings deliberately not asserted

- No broad redesign, automatic multi-writer merge or new distributed transaction service is required by these findings.
- Unknown operations retaining nonexpiring tickets are an intentional integrity safeguard. A timer that clears them would not be an acceptable “availability fix.”
- Conservative budget estimates and overhead allowances are not absolute provider-billing caps. Administrative/provider activity and unlimited rejected traffic need their documented operational limits.
- The 6.950 ms CPU sample is narrow evidence, not evidence of a CPU bug or proof for every route.
- Doze/OEM and separate-device gaps are acceptance gaps, not confirmed scheduling defects.
- Potential R2 races without a demonstrated invariant violation were not promoted to findings.
