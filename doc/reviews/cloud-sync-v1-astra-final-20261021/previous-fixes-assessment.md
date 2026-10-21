# Independent assessment of the 17 previous corrections

**All 17 corrections survive this review at `2b5c85b801481923de9af88aaa082868db40cd92`.** No unresolved recurrence or newly established regression is counted against them.

This assessment followed an independent specification/implementation pass. It distinguishes the reason the current code closes each defect from the implementer's disposition. “Adequate” below means adequate for that bounded correction and entry to integrated acceptance; it does not substitute for the remaining live/device matrix.

## Evidence identity

The original Astra findings concern `1b7ab927a7840f3fc0e0b41cfd281199de77a6c8`. The original Claude review concerns `582f0791d7ecf34291afd6009a4e09e92b7ddf19`. Their original reports remain unchanged.

The [Astra correction evidence](../../validation/cloud-astra-fixes-20261021/README.md) has 52 manifest entries and the [Claude correction evidence](../../validation/cloud-claude-fixes-20261021/README.md) has 35; every SHA-256 entry was independently recomputed and matched. JVM totals were recomputed from the 45 per-suite records. Native success-status counts match the runner summaries, including 213 and the overlapping 52. Negative/intermediate failures remain identified in [superseded-runs.json](../../validation/cloud-claude-fixes-20261021/superseded-runs.json); they are not counted as passes.

Git comparisons establish:

- Backend production paths are unchanged between `84c47ae6a8b7f7e80638a9ff7eca273f5e4caca5` and reviewed HEAD.
- Production paths are unchanged between `437e61c3c9c11c6f689e55c4e3c425c6a4f326cc` and the frozen Claude source `582f0791d7ecf34291afd6009a4e09e92b7ddf19`, resolving that review's source-identity uncertainty.
- Current production paths are unchanged from `0f9988b001248050097d96972ba1e86d1b312c5e`.
- The only production delta from the 213-test source `439af53cca239a9bdf37c84790436c0fc695d0f0` is the clean-native-identity detachment preflight and its message mapping. The 52-case controller follow-up covers that delta. It is not a full native rerun at HEAD.

## Original Astra findings

### ASTRA-CS-001 — Forget witness/baseline commit ordering

**Disposition: corrected.**

**Implementation:** [CloudSyncController.kt:147–177](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L147-L177) commits OFF/new generation, then Room witness deletion, then independent side-store cleanup, binding cleanup and active connection removal. [CloudNativeApply.kt:197–265](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudNativeApply.kt#L197-L265) still rejects a surviving witness without its required evidence.

**Independent reasoning:** a crash before the Room transaction commits leaves the matching old witness/baseline. A crash afterward can leave an old baseline without a witness, which is recoverable. The dangerous opposite pair is no longer produced by Forget. Repetition does not need to replay content changes or weaken corruption checks.

**Tests:** [CloudSyncControllerTest.kt:733–776](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt#L733-L776) exercises clean and interrupted Forget. Six retained host-force-stop cases cover OFF, before/after witness commit, metadata, binding and connection boundaries. Their prepare/interruption/fresh-process verification records and driver markers support actual process death rather than exception injection.

**Regression/uncertainty:** no recurrence found. The later rejected-authority detachment path follows the same witness-before-baseline ordering. Physical power-loss durability was not established by these process kills.

### ASTRA-CS-002 — Historical catalog archives across service generations

**Disposition: corrected.**

**Implementation:** [CloudRecoveryController.kt:259–363](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryController.kt#L259-L363), [CloudApplyCompiler.kt:25–43](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyCompiler.kt#L25-L43), [CloudApplyPlan.kt:65–163](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyPlan.kt#L65-L163) and [CloudApplyStore.kt:35–100](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudApplyStore.kt#L35-L100) represent historical source provenance separately from the receiving context. Only the explicit historical mode permits a different source service; same-library checks remain.

**Independent reasoning:** the decoder understands the same version-2 mode as the compiler, so restart does not reinterpret a valid historical plan as an ordinary current-service apply. Its historical baseline exposes no acknowledged current cloud head. The current credential, receiving scope and highest observed authority are retained, and confirmation turns synchronization OFF.

**Tests:** [CloudRecoveryControllerTest.kt:120–184](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudRecoveryControllerTest.kt#L120-L184) includes a changed generation-B catalog before restoring A, so it proves content replacement rather than only header acceptance. Native apply tests also reject a different library and old-service ordinary apply. Three retained host kills cover pre-Room, post-Room and pre-tag-commit historical journals.

**Regression/uncertainty:** no weakening of ordinary synchronization scope found. Operator rebuild plus current Android restore of that rebuilt old-generation archive still needs the authorized live composition test.

### ASTRA-CS-003 — Missing prepared files changing native restore contents

**Disposition: corrected.**

**Implementation:** [TagBackupCoordinator.kt:52–181](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L52-L181) retains original expected members/absence and hashes the migrated, sanitized installation candidate. [TagBackupCoordinator.kt:220–288](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L220-L288) verifies it before quiescence and again at final admission. Staging uses private no-backup storage.

**Independent reasoning:** losing an originally present tag/binding/settings file no longer selects the stock-backup branch or an empty preferences map. An originally absent tag file cannot be injected later. Rechecking the downloaded ZIP alone would not have been enough; the separately prepared installation files are now bound too.

**Tests:** [TagBackupIntegrationTest.kt:205–274](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/tagging/TagBackupIntegrationTest.kt#L205-L274) covers each missing member, same-length byte corruption, immutable preference presence and injection of an originally absent tag member. Complete and intentionally tagless stock imports remain exercised.

**Regression/uncertainty:** the persistent-staging orphan regression was real but is separately corrected by CLAUDE-CS-009. Export's analogous inventory gap was also corrected after the frozen Claude snapshot; see the additional assessment below. No remaining substitution path was established.

### ASTRA-CS-004 — One-time restore from an enabled connection

**Disposition: corrected.**

**Implementation:** [CloudSyncController.kt:295–341](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L295-L341) binds confirmation to its candidate, then durably disables with a new generation under the native barrier when `enableAfter` is false. Preparation does not change the toggle.

**Independent reasoning:** old workers/callbacks lose admission before the one-time apply. An explicit enable preview still has different semantics, while cancellation leaves the pre-existing mode intact.

**Tests:** [CloudSyncControllerTest.kt:468–499](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt#L468-L499) starts enabled, confirms one-time restore, recreates the controller, edits locally and tests stale-generation exclusion and later explicit enable.

**Regression/uncertainty:** no recurrence found. A failure after confirmation has crossed its OFF transition can conservatively leave OFF; it does not falsely report enabled or undo the owner's explicit one-time choice. New dialog wording/theme acceptance remains separate.

### ASTRA-CS-005 — Replacement cancellation exceeding the SQL envelope

**Disposition: corrected, including the later adjacent cancellation gaps.**

**Implementation:** [replacement.ts:129–170](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/src/replacement.ts#L129-L170) propagates explicit `cleanupPending` when a bounded phase cannot finish. [recovery.ts:456–527](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/src/recovery.ts#L456-L527) splits rotation, abort claim, durable consumption proof and terminal removal. [CloudReplacementController.kt:291–365](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudReplacementController.kt#L291-L365) retains exact identity and bounds continuation.

**Independent reasoning:** a pre-dispatch statement ceiling is no longer reported as an ambiguous provider mutation. The public 32-position limit is preserved. Only a terminal result authorizes dropping local pending identity; a racing COMMITTED receipt is resolved as committed.

**Tests:** the eight RESERVED/UPLOADING/READY/ROTATING × abandon/settle router cases in [recovery.test.mjs:511–544](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/test/recovery.test.mjs#L511-L544) all passed again during this review, asserting at most 27 DATA plus five accounting positions, preserved head/survivors and settled holds. Retained Android tests cover continuation and exhausting the client bound without discarding the marker.

**Regression/uncertainty:** the original fix alone was incomplete. CLAUDE-CS-006/007 are now present and tested too. Live R2/provider and deployed CPU acceptance remains required.

### ASTRA-CS-006 — Permanent credential rejection leaving automatic work active

**Disposition: corrected.**

**Implementation:** [CloudSyncController.kt:50–64](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L50-L64) captures the generation issuing the operation; [CloudSyncController.kt:588–615](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L588-L615) conditionally persists OFF/new generation on terminal failure and reconciles scheduling. [CloudScheduler.kt:35–123](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScheduler.kt#L35-L123) removes future automatic work/observers when disabled.

**Independent reasoning:** a late failure from obsolete authority cannot disable a newer connection. The credential and pending evidence survive. Ordinary offline/rate-limit/temporary-service failure is not converted to permanent detachment.

**Tests:** [CloudSyncControllerTest.kt:500–565](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt#L500-L565) covers durable generation-sensitive stop, actual WorkManager cancellation after rejection/native edits and transient failures.

**Regression/uncertainty:** no server authority check was relaxed. The adjacent inability to detach rejected pending work is closed by CLAUDE-CS-004. OEM/Doze scheduling and actual service rejection remain integrated acceptance topics, not evidence that these source transitions fail.

### ASTRA-CS-007 — Unavailable-only ordering absent from exact details

**Disposition: corrected.**

**Implementation:** [CloudChangeExplorer.kt:27–36](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudChangeExplorer.kt#L27-L36) emits the separate Unavailable ordering group through the existing occurrence-aware move formatter, including service and URL.

**Tests/adequacy:** `CloudChangeExplorerTest.kt:61–79` covers a pure reorder, mixed membership/order and searchable identity. These cases belong to the retained JVM execution. The unchanged-order behavior follows the sequence-diff guard and is covered by the surrounding explorer tests.

**Regression/uncertainty:** no content mutation or new truncation is introduced. This is a bounded disclosure correction. Current visual acceptance of populated previews remains distinct from the pure semantic/JVM assertion.

### ASTRA-CS-008 — Invisible retained recovery filter after rotation

**Disposition: corrected.**

**Implementation:** [CloudRecoveryDialog.kt:71–100](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryDialog.kt#L71-L100) initializes the editor from the retained query and resynchronizes it when details change. `CloudRecoveryScreenModel` exposes that query read-only to the view.

**Tests/adequacy:** [CloudRecoveryFilterUiTest.kt:27–95](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudRecoveryFilterUiTest.kt#L27-L95) uses the actual retained DialogFragment with synthetic multi-group preview data, rotation, clearing and cancellation. Its successful method is present in the retained native runner output. This establishes more than a source-only assertion.

**Regression/uncertainty:** no hidden candidate change occurs; query and displayed rows now agree. There was no new UI execution in this final review. Broader accessibility and current recovery/replacement theme checks belong to acceptance.

## Claude independent findings

### CLAUDE-CS-001 — Malformed cross-chunk constraints stranding CONTROL forever

**Disposition: corrected.**

**Implementation:** [service.ts:229–265](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/src/service.ts#L229-L265) adds a global ordering precondition to the committing CHECK guard. With strict within-chunk ordering, requiring every prior ID to precede the new first ID proves cross-chunk uniqueness before insertion.

**Independent reasoning:** checking only JSON shape or catching a UNIQUE exception afterward would not close the fence defect. This fix moves the relevant client-controlled refusal before that UNIQUE effect. The narrow known-rollback classifier in `fence.ts` is unchanged; arbitrary write errors still retain uncertainty.

**Tests:** repeated duplicate and descending router cases pass with unchanged rows, zero tickets/held capacity and HELD maintenance. They were rerun here. The two new independent probes additionally cover ten malformed headers, twelve malformed chunks, exact first-chunk replay, successful continuation/publication and maintenance closure.

**Regression/uncertainty:** legitimate globally sorted continuation still commits. No new client-driven constraint escape was established in the inspected mutation guards. The bounded probes are not exhaustive fuzzing or a guarantee against all future schema changes.

### CLAUDE-CS-002 — Premature AI unpairing on final native refusal

**Disposition: corrected.**

**Implementation:** [AiControlRuntime.kt:480–552](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiControlRuntime.kt#L480-L552) supplies a reversible quiescence lease. [TagBackupCoordinator.kt:220–288](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L220-L288) drains outside the native lock, then repeats exact admission/inventory checks before irreversible lease commit.

**Independent reasoning:** the lease has not persisted `paired=false` when the second admission rejects. Closing it resumes the latest durable policy rather than a captured stale enabled state. A policy-version race itself refuses before native freeze. After commit, restore remains fenced and requires restart even if later I/O fails.

**Tests:** [TagBackupIntegrationTest.kt:277–373](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/tagging/TagBackupIntegrationTest.kt#L277-L373) uses actual isolated AI runtime/state and a test-owned Android Keystore identity. It checks final-expiry refusal, a concurrent local edit during quiescence, exact pairing/native preservation, and successful unpair/restart. Those are executed native tests, although the expiry is injected at the admission callback.

**Regression/uncertainty:** no drain wait is held under the TagRuntime lock. An actual live paired relay with queued commands was not exercised; that remains an integrated scenario.

### CLAUDE-CS-003 — Definitively rejected frozen uploads retried indefinitely

**Disposition: corrected.**

**Implementation:** [CloudSyncController.kt:342–390](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L342-L390) bounds fresh policy evaluation; [CloudSyncController.kt:441–518](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L441-L518) resolves/retire-checks only the specified definitive head/initialization/precondition/expiry refusals and validates the exact receipt.

**Independent reasoning:** the stale sequence can terminate and reach conflict or a fresh valid upload. A COMMITTED receipt wins over a racing refusal. No receipt, network failure or unknown write becomes permission to discard the frozen candidate; later local edits remain dirty because acknowledgement uses the frozen target.

**Tests:** [CloudSyncControllerTest.kt:66–125](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt#L66-L125) separately tests head race, expiry without housekeeping, racing commit and uncertain transport. All are in the final 52-case controller log. Relevant real local D1 retirement/racing-publication cases were rerun here.

**Regression/uncertainty:** no blanket catch-and-clear was introduced. Long real outages and live two-device races still require acceptance; they do not invalidate these exact state-machine tests.

### CLAUDE-CS-004 — Rejected credential with pending journals prevents re-pairing

**Disposition: corrected.**

**Implementation:** [CloudSyncController.kt:183–232](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L183-L232), the durable rejected-identity flag at [CloudSyncController.kt:588–615](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L588-L615), and the detach/retired-device records in `CloudConnectionStore.kt:170–241` implement explicitly confirmed local detachment. Pending payloads and encrypted authority are archived, native content is retained, and reuse of the retired identity is refused.

**Independent reasoning:** this local action does not claim remote cancellation or release CONTROL. It is safe to preserve uncertainty while removing active local authority and requiring fresh-device OFF enrollment. A resumable marker covers partial archival, metadata cleanup and connection removal.

**Tests:** [CloudSyncControllerTest.kt:167–279](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt#L167-L279) checks all pending evidence, replay-identity rejection, transient-outage refusal, interruption and fresh-device enrollment. Four retained actual host kills validate post-journal, post-outbox-archive, post-metadata and post-connection boundaries.

**Regression/uncertainty:** the intermediate missing rejection-flag persistence was exposed and corrected; it is wired through current encode/decode. The final follow-up permits a clean absent identity after native import while still rejecting a different extant identity. Its 52-case follow-up covers the sole later production delta; its synthetic clean-metadata setup is not claimed as another complete ZIP restore.

### CLAUDE-CS-005 — Hint-only stream changes trigger full automatic capture

**Disposition: corrected.**

**Implementation:** [CloudScheduler.kt:49–95](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudScheduler.kt#L49-L95) observes semantic playlist/membership changes and tag notifications; it no longer observes the hint-only `streams` table. Periodic remote polling remains.

**Tests/adequacy:** [CloudSyncControllerTest.kt:127–154](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/androidTest/java/org/schabi/newpipe/custom/cloud/CloudSyncControllerTest.kt#L127-L154) uses real Room invalidation and scheduling tickets for repeated metadata updates, plus positive semantic playlist/tag controls. It establishes the corrected wakeup trigger; it is not a measured battery or daily-request study.

**Regression/uncertainty:** membership/order and tag paths still trigger work, and remote-only changes remain discoverable periodically. Writer-barrier duration on larger real libraries and device scheduling/battery behavior remain measured rollout gates. No unmeasured cost saving is claimed.

### CLAUDE-CS-006 — Cancellation continuation repeats a successful multipart abort

**Disposition: corrected.**

**Implementation:** [recovery.ts:490–525](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/src/recovery.ts#L490-L525) persists `abort_fenced=1` before returning progress. Later continuation skips abort once that proof or an exact completed-object identity is durable.

**Independent reasoning:** proof survives request completion and is checked again in the terminal SQL guard. An arbitrary abort exception is still insufficient proof. There is intentionally no claim of an atomic transaction across R2 consumption and D1 proof persistence.

**Tests:** [recovery.test.mjs:566–584](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/test/recovery.test.mjs#L566-L584) uses a provider shim that throws on repeated successful abort. Through the production router and real local D1/R2, cancellation completes with exactly one successful abort and zero tickets/holds. It passed in this review, alongside the unchanged concurrent-quarantine regression.

**Regression/uncertainty:** a provider success whose proof is itself lost can still require conservative reconciliation. That accepted uncertainty is different from deliberately repeating a durably proven abort. Live provider behavior remains an acceptance gate.

### CLAUDE-CS-007 — Ordinary ROTATING cancellation exhausts its statement allowance

**Disposition: corrected.**

**Implementation:** [recovery.ts:456–487](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/src/recovery.ts#L456-L487) returns truthful progress after rotation rather than attempting the next claim in an exhausted request. [CloudRecoveryController.kt:232–253](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryController.kt#L232-L253) continues the same exact abort/abandon identity at most four times and retains unresolved markers.

**Tests:** both ordinary abort and abandon from full three-slot ROTATING were rerun and pass; [recovery.test.mjs:546–564](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/cloud/test/recovery.test.mjs#L546-L564) checks the per-request allowance, terminal CANCELED and both surviving copies. Retained Android tests check exact identity across progress replies.

**Regression/uncertainty:** cancellation does not promise to undo an already approved oldest-archive eviction; it preserves the surviving archives and prevents an extra allocation. Bounded progress beyond one UI action retains evidence for another attempt. No statement cap was increased.

### CLAUDE-CS-008 — Preview/cancel changes the owner's sync mode

**Disposition: corrected to the approved product semantics.**

**Implementation:** `CloudReplacementController.prepare` and `CloudRecoveryController.prepareRestore` no longer disable on preview. [CloudSyncController.kt:531–555](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudSyncController.kt#L531-L555) preserves the mode when resolving replacement receipts. [CloudRecoveryController.kt:310–363](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/cloud/CloudRecoveryController.kt#L310-L363) turns OFF at final confirmed local restore admission.

**Independent reasoning:** replacement changes the shared cloud, and preserves the current toggle; historical/native local restore is a one-time local action and ends OFF. A replacement marker blocks automatic work while unresolved without silently rewriting the toggle. Local/cloud edits during a preview are rejected at confirmation rather than locked out while the owner reads.

**Tests:** enabled preview/cancel/completion in `CloudReplacementControllerTest.kt:122–140`, historical enabled preview/cancel/confirm in `CloudRecoveryControllerTest.kt:185–200`, and current-cloud one-time tests establish the distinct cases.

**Regression/uncertainty:** the specification was not weakened to excuse the earlier behavior. Current human-operated dialog acceptance remains outstanding, especially explaining paused work with a retained ON toggle.

### CLAUDE-CS-009 — Persistent prepared staging accumulates after process death

**Disposition: corrected.**

**Implementation:** [NativeCatalogStaging.kt:1–67](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/tagging/NativeCatalogStaging.kt#L1-L67) obtains a process-lifetime OS lock before sweeping orphan `tag-import-*`/`tag-export-*` directories. Creation initializes ownership synchronously even if asynchronous startup has not completed. Repeated initialization by the current owner leaves active candidates alone.

**Tests:** `TagBackupIntegrationTest.kt:375–395` checks orphan cleanup, active-candidate preservation, protected recovery-file exclusion and competing ownership. The retained staging host-kill run restarts, verifies cleanup and safety-ZIP survival, then prepares/installs another valid import.

**Regression/uncertainty:** the sweep does not touch outboxes, retained safety ZIPs or database-swap journals. The retained staging result has an inherited generic `scope` string, explicitly disclosed by the implementer; its class, suite, marker, boolean and logs identify the actual staging execution. This labeling issue is not an unexecuted test.

## Additional post-snapshot export correction

The coordinator's export-loss correction at `4d65d5788` is not one of the 17 findings, but was included in the whole-feature review. [TagBackupCoordinator.kt:290–345](https://github.com/ritovision/NewPipe/blob/2b5c85b801481923de9af88aaa082868db40cd92/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt#L290-L345) freezes the validated snapshot inventory and verifies each member while streaming the ZIP. Disappearing tags or binding can no longer be omitted from a successful export. The retained old-source negative reproduction and overlapping 93-case native follow-up support the change; current native coverage includes this path.

A failed destination write can still leave an incomplete external file. The export must report failure, and the local safety-backup coordinator does not promote that failed pending file. This is an ordinary failed-output boundary, not silent successful corruption.

## Closure

The earlier findings required bounded corrections, and those corrections are present with appropriate local/native evidence. The retained intermediate failures demonstrate why source identity and actual assertions matter; they were not concealed or reclassified as passes. No additional general review is required to compensate for routine live/device acceptance that has not yet run.
