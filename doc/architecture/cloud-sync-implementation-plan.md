# Cloud & Sync v1 — Execution, review and acceptance

**Status:** Implementation is in progress. Catalog engine, native apply, Android connection/settings, previews and scheduling are written; current synthetic integration evidence is recorded below. Three-slot R2 recovery, forced replacement and final release acceptance remain outstanding. Historical checkpoints retain their original scope. No production activation or live-data migration is implied.
**Date:** 2026-10-19
**Canonical documents:** [Product contract](../specs/cloud-sync-v1.md) · [Architecture](cloud-sync-v1.md).
**Baseline:** `custom/dev` at `f76d89a378e71625616bba160a42de24498ccfa1`; documentation branch `feature/cloud-sync-spec-20261019`.

## 1. Owner constraints and authorization boundaries

Implement directly through the coordinating assistant; **Claude and Codex/Astra reviewer CLIs are review-only, never coding workers**. Preserve accepted native Library, Unavailable, Tagging and AI behavior. Do not spend repeated reviews on unchanged text or delegate an open-ended implementation.

The owner requested documentation first and then execution, with a heads-up before Claude starts. On 2026-10-19 the owner reported **1% Claude usage remaining** and an immediately available manual usage reset. Start one real review, not a token-burning authentication/model probe. If it stops for usage, report the actual stop and ask the owner to apply the reset. Preserve the exact session and resume it with **continue**; do not launch a new review or switch providers silently. Do not repeatedly poll a working reviewer.

The owner subsequently reset usage and requested **Opus, Extra High (`xhigh`)**, which completed this review. Use that effort for subsequent authorized Claude reviews, in a persistent interactive terminal, with only read-only tools. Disable edits, command execution, tests, subagents, browser/MCP operations and web/delegation. Record the exact candidate commit, session UUID, terminal name and resume command before sending the review. A terminal that remains alive does not prove Claude is still thinking; inspect actual review status/output when needed. No claimed approval without a completed report.

**Machine roles:** Originally NotCodex-2 owned source/documentation, headless builds and unit/backend tests, and primary NotCodex owned native UI work. **2026-10-20 owner override:** continue all work on primary NotCodex while NotCodex-2 is offline. The primary checkout is `/home/ritorhymes/techstuff/NewPipe`, on `feature/cloud-sync-v1-20261019`, restored from GitHub checkpoint `7640407ee`. Do not disturb an emulator/session belonging to Sleep or Music. Use isolated synthetic data and dedicated test packages; never clear or overwrite the owner's library. Previously authorized phone testing uses separate QA packages. Merge to `custom/dev`, production activation or destructive real-cloud action still requires a separate explicit release instruction.

## 2. Account and deployment preflight

Required account: **ritorhymes@gmail.com**.

Earlier read-only inspection in this conversation found:

- Global Wrangler OAuth was **matthewjpellerito@gmail.com** (wrong for this feature).
- An authorized My-Browser Cloudflare dashboard showed the Rito account, account ID `b35814b119cba4448e3e0ac59442cc25`.
- Existing `gateway/wrangler.jsonc` targets the Matthew account, ID `5b7f5d92c4eeed125617786cbe047552`, for `ritovision-newpipe-ai-control`.

These are recorded observations, not permanent authentication guarantees. Recheck immediately before any remote write. Account name alone is not sufficient; verify the account ID in the dashboard/API result and actual resource bindings.

1. Read fresh Git status/HEAD and live origin branch SHA. Do not overwrite concurrent work or change existing gateway configuration.
2. Confirm the Rito account through authorized My-Browser when CLI identity is wrong. Use a dedicated Cloudflare tab rather than hijacking the Music setup tab.
3. Prefer isolated scoped deployment credentials/config for this service. Do not silently replace global Wrangler login/settings used by other projects. Pin `account_id` and explicit environment config in every new deployment command. Setting an account ID does not grant the wrong account access.
4. Discover/check existing resources before creating uniquely named test resources. Proposed names only, not resources already created: Worker `ritovision-newpipe-cloud-test`, D1 `ritovision-newpipe-cloud-test`, R2 `ritovision-newpipe-cloud-backups-test`.
5. Separate test and production resources/configuration. No automatic `deploy` in a test script. Local emulator tests must not use remote D1/R2 bindings. Real endpoint acceptance uses synthetic test data only.
6. Check current plan/limits and billing status. Do not enroll a paid plan, authorize charges, raise spending limits or attach unapproved payment data as an incidental setup step. No secrets in Git, screenshots, terminal logs, final replies or exported docs.
7. Record created resource IDs only after successful creation. Verify the deployed binding/account and unauthorized access behavior, not just a CLI success line. Do not create production resources solely to finish documentation.

## 3. Milestones and gates

### D0 — Frozen design packet and independent review

Write product behavior, architecture/failure model and this test/rollout plan. Update the documentation indexes and mark the older exploration historical. Check links, whitespace, conflicting requirements, secret leakage and scope drift. Commit and push a clean documentation checkpoint before Claude reads it.

Run one independent Opus Extra High read-only review against a compact frozen source/docs snapshot. Ask for prioritized concrete failures, affected paragraphs/source seams, reproducible scenarios, and minimum corrections. Require a verdict and distinguish blockers, substantive risks and optional ideas. Do not show another reviewer's conclusions.

Adjudicate findings against the actual code and product choices. Fix supported issues; document disagreements and accepted limitations. Commit the amended design and review/adjudication record. Do not call it approved while the review is rate-limited or incomplete.

### M1 — Protocol and safety proofs, without live user data

Implement canonical codec/diff vectors, domain validation, state transitions and revision/receipt behavior with synthetic fixtures. Build a local Worker/D1 skeleton in isolated `cloud/`; no changes to `gateway/`. Prove the atomic CHECK-guard/receipt/CAS pattern on the D1-compatible test runtime and subsequently the dedicated synthetic D1 deployment before relying on it.

Prove generic large staged-delta publication and sparse playlist ordering without copying the whole catalog per routine edit. Define/freeze all row, byte, stage, batch and retry limits in one configuration contract; test rejection at each boundary. Measure the 20,000-stream fixture and propose any necessary reviewed adjustments before release.

Prove the native snapshot fingerprint and a record-level recoverable apply prototype, including native-ID mapping across interrupted insertions. Audit every UI/AI writer for writer-barrier coverage. If the two-store/native-ID design needs a small fork-owned migration, document and review it; never simply remove the crash-safety requirement.

**Gate:** no live service writes or owner enrollment until these core failure paths pass. An unresolved prototype is not a production sync engine.

### M2 — Connection and bounded recovery vertical slice

Build the isolated settings entry, secure device-token storage, connection test and independent disable/forget semantics. Provision only the dedicated test Worker/D1/R2 under the verified Rito account when needed for acceptance. Implement R2 three-slot reservations, archive subtypes, verification, multipart cleanup and visible usage.

Connect existing native backup/restore to authenticated transport. Verify credentials/runtime authority do not round-trip in backups and that native full restore keeps its documented AI-unpair/restart effect. Implement a one-time D1 catalog pull through the catalog adapter with preview; this is not a full native ZIP restore.

**Gate:** actual synthetic round trips plus storage-failure cases; no daily schedule, archive creep or public access. This milestone alone is backup/one-time transfer, not completed continuous sync.

### M3 — Continuous D1 sync and reconciliation

Connect all supported native/AI catalog writers, persistent baselines/in-flight operation identity, foreground/WorkManager triggers and offline behavior. Implement the coarse conflict policy, exact staged preview, searchable/paged details and source/destination confirmations. Implement deliberate whole-cloud replacement with an archive of the exact outgoing cloud state and a new catalog epoch.

**Gate:** two isolated Android installations converge for sequential edits; simultaneous/offline divergence preserves both branches until explicit reconciliation. No ZIP upload or R2 dependence for ordinary edits. Normal sync does not restart/unpair the app.

### M4 — Integrated failure/scale/security review and release candidate

Run native UI tests on primary NotCodex, verify new-page behavior/themes/rotation and broad integration regressions. Exercise actual process-stop checkpoints, not only caught exceptions. Independently review the frozen implementation/evidence; use read-only reviewers only. Adjudicate before any release claim. Keep test/deployment failures and waivers explicit.

Produce one current candidate APK and required test APK, concise results, source commit and exact configuration evidence. Clean obsolete reproducible outputs after preserving meaningful evidence. Stop before owner-phone install, `custom/dev` merge, production activation or real-data replacement unless separately instructed.

## 4. Required acceptance matrix

| Area | Required proof |
| --- | --- |
| Navigation | Separate Cloud & Sync page, native styling, searchable safe labels, no sensitive text in preference search |
| Credentials | Wrong/revoked token, wrong host/service/library, malicious redirect, forgotten key and cross-device token revocation; no secrets in exports/logs |
| Disable | No new network work after disable; late callbacks cannot apply; previously sent commit ambiguity recorded honestly; later local edits remain detached |
| Offline | Local commit followed by immediate process death still detected; offline is not disabled; no lost acknowledged baseline |
| Push retry | Commit then lost response; repeated identical request; same sequence with changed payload; receipt expiry; no duplicate application |
| Concurrent commits | Two devices submit the same base at once: only one commits; rejected batch has no domain/receipt/revision side effects |
| Conflict | Both local/cloud changed, even unrelated edits: pause; no silent whole-device winner or accidental merge |
| Local edits during upload | Acknowledgment advances only to the frozen submitted candidate; later edits remain pending |
| Consistent paging | Add/delete/reorder between cloud pages; stale cursor; missing/duplicate pages; mismatched checksum/count; no mixed snapshot applied |
| Preview | Exact videos/playlists/tags discoverable beyond page one; changed local or cloud revision invalidates approval; confirm never fetches unreviewed latest bytes |
| Native preview races | AI/native write and same-count reorder/rename while preview open; process-local restore generation alone cannot pass |
| Domain fidelity | Library uniqueness, ordinary duplicates/order, same-title different videos, special-role identity, Unavailable subset, global delete cascade, tag/category semantics |
| Local cross-store apply | Kill before/after journal, Room commit, tag/map/baseline commit and notification; deterministic completion or explicit recovery block; Room phase invariant-valid on its own; tag-dependent access recovers or blocks |
| Native ID mapping | Crash after playlist insertion but before sidecar completion; same-name playlists; DB restore/reused integer IDs; no mistaken binding |
| Restore scope | D1 one-time pull preserves non-sync native data; device ZIP restores supported broad data; cloud-catalog archive never claims settings/history coverage |
| Import interactions | Import/restore while work is queued/in flight; cloud stays disabled; old outbox/mappings/AI receipts cannot execute against replacement data |
| Cloud replacement | Old cloud revision, not incoming local, is recoverable; stale CAS never overwrites newer cloud; other dirty devices pause on new epoch |
| R2 cap | Three slots across devices/kinds; failed deletion blocks new upload; unknown upload occupies its slot; late completion/stale worker cannot allocate a fourth payload |
| R2 failures | Upload before index failure, index without object, checksum failure, truncated ZIP, oversized/bomb archive, repeated multipart completion, expired reservations |
| R2 recovery safety | Never delete last verified archive; active restore/replacement protects its archive; no lifecycle expiry of all committed backups; no scheduled backup creation |
| D1 history pruning | Cursor older than retained history requires resync; pruned receipts/changes never make stale writes replayable |
| Operator recovery | Synthetic Time Travel + new external service/auth generation; revoked tokens do not revive; R2 index reconciles against extant archives |
| Scale | At least 20,000 saved streams with 100,000 ordered memberships and tag relations; exact counts/diff search, bounded staging/memory/query work, no UI-thread database/network I/O |
| Compatibility | Unknown protocol/schema blocks mutation; old supported native backup migration; existing manual import/export and AI capabilities remain functional |
| Actual deployment | Verified Rito account and isolated bindings; no user data; invalid auth denied; old AI gateway untouched |

Tests must distinguish a rejected operation from transport uncertainty, local staging from installed data, and compiled instrumentation from executed native tests. Record real fixture sizes, timings, D1 usage and R2 byte counts rather than estimating them from media count.

## 5. Storage, evidence and Git hygiene

No new build clones, copied Gradle caches, repeated APK history or long-lived per-review worktrees. Use the existing checkout/branch and small source-only review snapshots. Do not delete any other project's files or another active review/session.

Commit source, tests, specs, small reviewer reports, adjudication and concise acceptance records. Preserve active raw evidence locally but exclude APKs, screen recordings, UI XML, full terminal transcripts, node_modules and generated build outputs from Git. Keep only the current needed candidate/test APK pair after a replacement has been validated; source can be rebuilt.

Record the exact review session and prompt paths so a machine reboot or usage reset does not require starting over. A persisted review packet is not evidence of a completed review. Keep separate state labels for **draft**, **review running**, **usage-limited**, **findings adjudicated**, **tests passed**, and **release authorized**.

## 6. Current checkpoint

2026-10-19: confirmed the documentation branch is clean at the original baseline before writing this packet; live `origin/custom/dev` matches that baseline. No prior cloud spec files had been saved, no cloud-specific reviewer session was running, and no cloud implementation existed. Documentation creation and design review are the current milestone. Update the dedicated review record with subsequent exact commit/session/status evidence; do not rewrite this historical observation as a later implementation claim.

### Adjudicated implementation checkpoint — 2026-10-19

The owner authorized adjudication, doc changes and implementation, with native/emulator work on primary NotCodex. Completed Claude report: [raw report](../reviews/cloud-sync-claude-xhigh-2026-10-19.md); coordinator decisions: [adjudication](../reviews/cloud-sync-design-adjudication-2026-10-19.md). M1 begins with portable semantic codec/diff/state vectors and a local D1 admission/receipt/retirement proof. These have no production endpoint or schema migration and do not depend on enrolling a paid plan. Native test execution uses a separate Cloud QA package on an otherwise unallocated primary-host emulator; active Music/Sleep emulators are not reused or stopped.

The full-scale physical catalog schema, large staged publication, deployed CPU/row budget, durable native record apply/mapping, and writer audit remain M1 gates; a passing first foundation checkpoint is not the whole M1 or finished sync. The Cloudflare browser read timed out during this adjudication, so actual account plan/remaining quota remains unverified. Free limits are the conservative design target, not a claim about the account subscription. No payment/subscription changes or live scale load are authorized incidentally.


### Executed foundation checkpoint — 2026-10-19

Implementation source `2f15bcaf6` on `feature/cloud-sync-v1-20261019`: portable Kotlin/TypeScript model/fingerprint/diff; pure state/preview rules; local D1 guarded commit/receipt/retirement proof. **111 Node tests, 322 full-suite JVM tests, and 24 executed Android instrumentation tests passed**, including 16 existing native backup regressions. See [evidence and limits](../validation/cloud-sync-m1-20261019/README.md).

This completes the first foundation checkpoint only, not M1. No new native record-apply, remote endpoint, sync settings or R2 implementation exists yet. Next is the bounded real-catalog schema/generic large-delta proof and native projection/ID/binding integration, with durable apply and full writer audit still gating live synchronization. The scale fixture's 9.848-second emulator exercise is not a main-thread or deployed CPU acceptance. Keep work off main and measure incremental behavior before rollout. Account plan/deployed budgets and independent implementation review remain unverified.


### Native read-side and sparse sequence checkpoint — 2026-10-19

Production source `01fac87a7`, corrected migration test fixtures `6644b98e2`, same feature branch. Native Room/tag capture and persistent portable identity maps now exist; tag schema is 4, main Room remains 9001. Proper staged backup/import clears runtime maps, with no credential/state export. Sparse exact sequence compilation avoids dense row rewrites on routine edits; this is not yet a generic large-delta staging/publish service.

Executed **133 backend tests, 322 full-suite JVM tests, 198 native tests**, plus a real host-induced QA-process kill during an open identity transaction and successful fresh-process rollback verification. Native testing used the idle existing 5590 emulator with the separate Cloud QA package, not a third emulator; both existing emulators remain running as requested. [Evidence, original failures and limitations](../validation/cloud-native-m1-20261019/README.md).

A focused Opus Extra High independent review has been launched and its persistent session saved. Its completed verdict/adjudication is not yet recorded at this checkpoint. [Review handoff](../reviews/cloud-native-review-2026-10-19.md). Do not treat test success as final review approval or restart the reviewer after a usage reset.

Read-side barrier inspection confirmed ordinary LocalPlaylistManager writes, AiCatalogExecutor authorization+TagRuntime paths, TagRepository mutations, Library and Unavailable repositories use existing native serialization seams. This is not the complete write-origin/outbox audit needed for automatic sync. Full-catalog capture currently holds writers for seconds on the synthetic 20k/100k scale; incremental/coalesced behavior and user-interaction budgets remain unproven.

Next M1 dependencies: adjudicate the bounded read-side review, then implement real catalog/staged delta publication and native write-side mapping/recovery. Network/settings/preview UI/R2 remain later gates. No cloud resources or production/physical-phone installation were created.


### Native review-fix closure — 2026-10-19 (historical)

Final source **5dba4d5cfae477d1fbe5fd7e33f2f95314f43564** closes the native read-side review's three P2 findings and justified optional issues. Astra Extra High completed both the main fix review and a final delta review, with no confirmed substantive P1/P2 and **APPROVE_FINAL_FIX_CHECKPOINT** for the final candidate. No Claude inference was launched for this fix pass.

Executed **140 backend tests, 327 full-suite JVM tests, 211 native tests**, plus two deliberate QA-process kill/recovery probes. Final native acceptance is on primary NotCodex's existing **Pixel_9_API_35 / emulator-5576 / API35 / 4KiB / user0**. Both probe drivers are committed and reproducible; neither claims a completed remote cross-database apply controller. [Complete evidence and remaining limits](../validation/cloud-native-fixes-20261019/README.md).

The special 16KiB emulator had a QA ART SIGSEGV on the earlier test helper and an actual system_server ART GC crash on the final-candidate rerun. Those attempts are retained as failures, not passes; 16KiB verification remains unresolved. A first-install startup ANR on the standard emulator was followed by an unchanged-APK passing focused retry and full suite. No emulator restart, Android user switch, app clear or global runtime timeout change was used by the coordinator.

Current identity behavior: explicit enroll versus required-incarnation capture; paired binding lifetime/playlist-sequence anchor checked against tag mapping context; recreated main database cannot trust matching native role IDs alone. Staged restore scrubs markers; legacy restore invalidates old binding before the main swap. Sparse head/tail growth is fixed-stride with bounded local interior redistribution and explicit cumulative row budgets. Room remains 9001 and tag schema4; native catalog contents are unchanged by capture.

**This review-fix checkpoint is closed. M1 as a whole and Cloud & Sync remain unfinished.** Next dependencies are real catalog/staged D1 publication and native remote-apply/ID adoption/recovery, then complete writer capture, network credentials/settings, visual preview and three-slot R2 implementation. No full-cloud/16KiB compatibility release, custom/dev merge or live deployment is implied. Full-catalog capture still holds writers for seconds at the synthetic 20k/100k size and is not a per-edit latency guarantee.

### Primary-host live Android checkpoint — 2026-10-20 (current)

The owner authorized continuing on primary NotCodex while NotCodex-2 is unavailable. Starting from `7640407ee`, the current engine and Android integration built successfully. Executed 197 backend tests, 337 JVM tests, and 121 native cloud tests (plus 10 explicit opt-in skips). The dedicated Worker was deployed and temporarily enabled for a small synthetic live acceptance.

Two independent Android packages on one Pixel 7 converged through real Cloudflare/D1. Initial publication, automatic push/pull, duplicate playlist fidelity, tags/Unavailable, disabled local divergence and one-time restore staying disabled passed. A second scenario killed B after a durable local edit, published a different edit from A, verified both branches survived the resulting conflict, then explicitly reconciled B from cloud. This is not two-physical-device, OS network-outage, full UI or release acceptance.

Both QA tokens were then revoked and rejected live, both app connections were disabled, the Worker returned to its default disabled gate, and guarded removal of the known synthetic D1 rows was verified. No R2 binding or payload was used. The old Music/AI infrastructure and owner app data were not changed. [Detailed evidence, exact limits and reproducible driver](../validation/cloud-live-android-20261020/README.md).

**Next implementation phase:** three-slot R2 recovery, including reservation/rotation safety, payload verification, Android backup/restore and exact outgoing-cloud recovery before forced replacement. Broader physical-device/UI/offline/scale acceptance and final independent whole-feature review remain required. Do not merge `custom/dev` or activate production from this checkpoint.

### Executed three-slot recovery checkpoint — 2026-10-20

Source `2cb2df30d` implements bounded R2 multipart storage, explicit oldest-only three-slot rotation, exact cloud-catalog verification, Android native ZIP/historical catalog restore and settings controls. Executed 239 backend tests, 28 focused JVM cloud tests and 132 native cloud tests (11 explicit opt-in skips). Two isolated phone packages completed the real R2 backup/rotation/catalog-restore/native-restart sequence. Live R2 completion metadata differences and landscape detail clipping were discovered and corrected. [Evidence and precise limits](../validation/cloud-recovery-20261020/README.md).

The temporary QA credentials were revoked, both APIs disabled and only recorded synthetic R2/D1 data removed with verification. Protected whole-cloud replacement and operator disaster-recovery tooling remain unfinished; physical multi-device/offline/scale, broader UI acceptance and final independent review still gate release. Read-only Astra Max review of the frozen recovery checkpoint is running; a started review is not approval.
