# Release gates

**Assessment at implementation `1b7ab927a7840f3fc0e0b41cfd281199de77a6c8`: HOLD RELEASE.**

This checklist concerns technical readiness of the intended v1. It does not authorize production activation, a merge, a deployment, credential changes, scheduled housekeeping, a physical-phone install or reopening the intentionally closed QA environment.

There are five blocking findings. The other three findings still require an explicit disposition; they are not silently counted as passed.

## 1. Blocking corrections

| Gate | Required result | Verification needed |
| --- | --- | --- |
| [ ] ASTRA-CS-001 — Forget crash ordering | Persist OFF before cleanup; never commit baseline/context deletion while a matching old Room witness can survive rollback. Remaining cleanup must be repeatable after restart. | Native process death at the independent cleanup boundaries after a successful cloud apply. Verify exact native data, ordinary permitted operations, OFF and successful retry. |
| [ ] ASTRA-CS-002 — historical archives after recovery | A verified old-generation archive from the same library can be explicitly restored locally after fresh-generation reconnect, without adopting old authority or lowering current high-water. | Compose operator reconstruction/reopen, fresh reconnect and historical preview/apply. Verify current scope/counters, unchanged cloud, exact local contents and OFF. Reject wrong library/old token. |
| [ ] ASTRA-CS-003 — prepared native inventory | Missing/changed expected prepared files fail before destructive shutdown/swap, or are reconstructed from the exact verified archive. A lost tag file cannot become a stock-import fallback. | Native fixture with distinct live/archive tags; remove staged tag/binding/preferences separately between preparation and confirmation. Verify unchanged live state on refusal or exact restored state. Preserve genuine stock ZIP behavior. A native disproof must identify the effective guard, not merely repeat happy-path tests. |
| [ ] ASTRA-CS-004 — one-time restore from ON | Every successful one-time current-cloud restore leaves durable OFF state and invalidates stale automatic work. | Begin ON; confirm; restart; edit locally; advance/simulate periodic work. No automatic HTTP/apply. Explicit enable still works. Define and test preview-cancel behavior. |
| [ ] ASTRA-CS-006 — terminal credential rejection | Permanent credential/authority failure stops future automatic work for the failed generation while retaining reconciliation evidence. | Actual WorkManager/controller test for 401, native edits and restart; transient failures still back off; obsolete failure cannot disable a newer connection. |

Do not resolve these gates by removing identity/witness checks, relaxing ordinary service/library authorization, dropping pending-operation identities or allowing new automatic work after OFF. Those would trade a visible failure for weaker integrity.

## 2. Required regression testing after corrections

Use the smallest test set that verifies the changed invariant and the shared components it affects. Retained unaffected evidence can be reused; there is no requirement to rerun every old scenario merely to increase a pass count.

| Changed area | Targeted regression set | Required assertions |
| --- | --- | --- |
| Connection removal / mode transitions | CloudSyncControllerTest; scheduler/WorkManager tests; targeted host-driven kill harness | Correct generation, OFF persistence, stale-work refusal, native preservation, no false journal loss, pending uploads retained. |
| Historical provenance / apply journal format | CloudRecoveryControllerTest, CloudNativeApplyTest, codec/plan round-trip and migration tests, reconnect cases | Same-library historical import; new authority retained; rollback protection unchanged; old supported journal formats handled deliberately; crash completion uses the exact approved candidate. |
| Prepared native archive staging | TagBackupIntegrationTest, CatalogFileSwapTest, cloud native restore tests | Full archive inventory intact or explicit pre-mutation refusal; no accidental current-tag substitution; no empty settings wipe; legitimate optional inputs remain supported. |
| Replacement cancellation if fixed | Real HTTP Miniflare tests with DATA 0001–0006 and CONTROL 0001–0003 | RESERVED/UPLOADING/READY/rotation paths; <=32 public SQL positions; accurate error classification; exact retry/terminal state; no archive or receipt loss. |
| Preview fixes if included | CloudChangeExplorerTest; actual recovery/main/replacement dialog tests | Unavailable order is visible/searchable; filter text matches retained filter after rotation; complete paging; cancel does not apply. |
| Shared backend admission/budget changes, if any | Existing admission, budget, recovery, fence and housekeeping suites whose call paths changed | Auth in final transaction; missing metadata fail-closed; ambiguous writes keep tickets/holds; midnight carry-forward; race safety; high-water retention. |

Run builds after any implementation changes, but record build-only success separately from executed tests. If a shared protocol/journal/schema change broadens the risk, run the relevant full backend/JVM/native suite once and retain source/APK identity. Do not replace failed targeted assertions with weaker “operation returned” checks.

## 3. Integrated acceptance still required

### A. Corrected recovery composition

- [ ] In a disposable local/native setup, create generation-A archives, reconstruct DATA for B and reconnect with fresh B authority.
- [ ] Restore an A cloud-catalog archive through the real Android controller and exact preview. Verify both portable content and preserved unrelated native records.
- [ ] Restart across the relevant native journal boundaries; verify exact completion or safe refusal without adopting old credentials/counters.
- [ ] Then, only in a separately authorized isolated Cloudflare cycle, verify the same composition with real object inventory/download and the selected corrected APK.

The final closed QA's three preserved archives are evidence to protect, not disposable test material. No action on them is authorized here.

### B. Full integrated backup/replacement paths under the daily ledger

- [ ] Execute device ZIP backup and explicit native restore, including local safety copy, restart requirement and settings opt-in.
- [ ] Execute three-slot reservation/rotation and protected whole-cloud replacement with a dirty second installation.
- [ ] Verify outgoing archive identity/digest before publication, stale-device conflict, exact cancellation and later retry, and current-token revocation.
- [ ] Observe CONTROL tickets/reservations and DATA high-water before/after normal and interrupted paths. Unknown outcomes must remain held; a timeout is not a cleanup proof.
- [ ] Recheck bounded housekeeping against the resulting receipts/stages, with no archive mutation capability.
- [ ] Capture final closed-state evidence after the authorized cycle.

Earlier live recovery/replacement passes are useful historical evidence but predate the final budget integration. The most recent thirty-request sample does not exercise these complete R2 mutation paths.

### C. Resource and scheduling acceptance

- [ ] Keep the release restricted to the supported small catalog profile unless larger limits receive their own measurements.
- [ ] For backend changes, record a minified deployed source/bundle identity and a representative small-profile mix including changed mutation/cleanup paths. Retain request list, error counts, sample size, interval and raw analytics response/query with units. Reassess the <=8 ms deployed p95 target.
- [ ] Do not describe conservative daily rows/growth/statement guards as absolute provider-billing guarantees.
- [ ] Establish separate-device and longer offline/background/Doze behavior before broad rollout. Measure convergence delay and repeated wakeups sufficiently to expose battery/retry regressions.
- [ ] State supported Android/SQLite snapshot versions. Confirm foreground refresh expectations or narrow the foreground-latency documentation.

A rerun of the old head-heavy CPU sample alone cannot close mutation/recovery coverage. Conversely, an Android-only UI correction does not automatically invalidate unchanged backend measurements.

### D. Android user-facing acceptance

- [ ] Verify main sync, recovery and replacement dialogs in light, dark and black, portrait/landscape, with real multi-group differences and enough rows to exercise paging/search.
- [ ] Check direction/scope, global removals versus playlist changes, duplicate/order changes, Unavailable changes, orphan metadata, and affected-device warnings.
- [ ] Verify every destructive confirmation uses the same staged candidate after rotation; changed local/remote state demands a fresh preview.
- [ ] Verify credential dialogs remain protected before first display, do not save secret text and clear on dismissal.
- [ ] Verify messages distinguish OFF, offline, conflict, revoked authority and uncertain operation outcomes.
- [ ] Include touch/accessibility/large-font checks appropriate to the supported device profile; the existing programmatic preference-listener tests do not establish them.

## 4. Nonblocking findings and permitted deferrals

| Item | Disposition before limited release |
| --- | --- |
| ASTRA-CS-005 — replacement cancellation | Preferred: fix and run the bounded HTTP regressions. If deferred, explicitly document that the demonstrated first cancel can report unknown while cleanup is in progress and that repeating the **same pending cancellation** completes it. Keep all pending identity and never advise clearing app data/markers or creating a new operation. This workaround is established locally for RESERVED/UPLOADING, not every rotation/unknown-provider state. |
| ASTRA-CS-007 — Unavailable order details | May defer as a known ordering-only preview defect. Do not claim the explorer exposes every semantic change without qualification. Add the small deterministic regression when fixed. |
| ASTRA-CS-008 — recovery hidden filter | May defer with a known rotation/search issue; explicitly retain a way to clear/recreate the preview. Fix using the existing retained-query binding pattern when possible. |

Optional improvements that do not block the scoped v1 include broader automatic merging, richer operator dashboards, larger catalog admission, scheduled full-backup generation, and additional convenience UI. Scheduled full-backup generation is not part of this v1 design at all. Hardening the evidence package with complete raw analytics/build provenance is useful, but does not erase valid retained tests.

## 5. Conditions for a release recommendation

Recommend technical release only when:

1. The five blockers are fixed or convincingly disproved and independently re-reviewed on an exact new implementation commit.
2. Targeted regressions exercise the actual failing boundaries, with no weakened assertions or authority checks.
3. Native and integrated live acceptance appropriate to the promised v1 operations is retained at identified source/APK/deployment revisions.
4. CPU/resource claims match the tested profile and the application-level limits' documented scope.
5. Remaining Medium/Low issues and platform/background limitations have an explicit owner decision; broad rollout claims do not exceed the evidence.
6. The test environment has been safely closed again with preserved archives and no unresolved work improperly cleared.

The final recommendation should name the exact release candidate, supported profile and any accepted limitations. A technically ready candidate still requires separate owner authorization for merge and production activation.
