# Coverage and release gates

## Decision boundary

**Ready to enter final integrated verification:** yes, at source **`2b5c85b801481923de9af88aaa082868db40cd92`**, branch `feature/cloud-sync-v1-20261019`.

**Code-level blockers found:** zero. **Production activation approved by this review:** no.

The missing acceptance work below does not require another general review. A concrete acceptance failure may require a bounded correction and the directly relevant rerun. This report does not authorize deployment, credentials, migrations, use of an active emulator, changes to the closed QA environment, or production activation.

## Evidence categories

| Category | Meaning in this report |
| --- | --- |
| Source inspection | Requirements and actual code/SQL/state transitions traced. Not execution. |
| Compiled/type-checked | Compiler/type checker succeeded. Not an Android or provider runtime result. |
| Executed JVM | Retained host JVM test results. Does not imply Android execution. |
| Executed Android instrumentation | Retained Android runner results with real native databases/runtime where specified; network peers may be synthetic. |
| Host-driven process death | Retained prepare/marker/host force-stop/fresh-process verification. Not exception injection, power loss or a new run by this reviewer. |
| Local Miniflare/D1/R2 | Executed local workerd/Miniflare and synthetic provider/authority fixtures. No live account data. |
| Actual live Cloudflare | Historical retained live evidence at its own deployment/source. No new live execution in this review. |
| Screenshot inspection | Retained image examined visually. Not new UI automation or a current-binary execution. |

## Executed during this review

All runtime tests used the reviewed source, Node v24.19.0, installed dependencies and isolated local fixtures. No dependency installation, Android build or emulator operation was performed.

| Run | Tests passed | Failed | Duration reported by runner | Purpose | Raw evidence |
| --- | ---: | ---: | ---: | --- | --- |
| Budget and fence | 32 | 0 | 19.090 s | Concurrent reservations, metadata, UTC rollover, unknown effects, SQL ceiling and operator exclusivity | [budget-fence.log](evidence/budget-fence.log) |
| Selected upload/admission | 19 | 0 | 9.668 s | Malformed cross-chunk guards, replay, exact retirement, expiry, races and authorization | [upload.log](evidence/upload.log) |
| Selected recovery/replacement | 29 | 0 | 49.623 s | Three-slot rotation, create/complete uncertainty, exact outgoing archive, grants, bounded cancellation, repeated-abort rejection | [recovery.log](evidence/recovery.log) |
| Selected operator recovery/reopen | 16 | 0 | 45.059 s | Real local D1 rebuild, receipt resolution, unknown restore/rebuild, fresh authority, external OPEN and bounded snapshots | [operator.log](evidence/operator.log) |
| Independent malformed-input continuation probes | 2 | 0 | 4.680 s | Ten malformed starts and twelve malformed chunks; zero holds, unchanged stages, valid subsequent commit and maintenance closure | [independent.log](evidence/independent.log), [recipe](evidence/independent-probes.md) |
| **Total test results** | **98** | **0** | — | 96 selected existing results plus two new probes | — |
| TypeScript `npm run check` | Successful | — | Not benchmarked | `tsc --noEmit` | [typecheck.log](evidence/typecheck.log) |

The filtered commands ran the selected cases; their unselected file cases were not rerun. The runners reported zero skipped/cancelled tests among the selected results. The 22 malformed request variants are assertions inside two tests, **not 22 additional test cases**. The 98 results are also not added to the earlier full-suite total as unique coverage.

Exact commands, log hashes and source scope are in [review-metadata.json](review-metadata.json). [evidence-audit.json](evidence/evidence-audit.json) records the retained-evidence audit.

## Retained evidence assessed

| Evidence | What was verified | Practical limit |
| --- | --- | --- |
| [Claude corrections](../../validation/cloud-claude-fixes-20261021/README.md): 372 backend | Raw runner summary: 372 passes, zero failure/skip; matching hash. Backend production unchanged from its stated correction source to reviewed HEAD. | Mixed local pure-logic/workerd/D1/R2 results; not live deployment. |
| Claude corrections: 353 JVM / 45 suites | Sum of every suite's test/failure/error/skip counts recomputed; source `0f9988b00`; current production identical. | JVM result aggregation, not a fresh JVM run or Android runtime proof. |
| Claude corrections: 213 native | 213 successful completion statuses and `OK (213 tests)`; APK/source metadata at `439af53cc`. | Selected native classes with real stores/runtime, synthetic HTTP peers. Not final full native execution at HEAD. |
| Claude corrections: final 52 controller | 52 successful completion statuses and `OK (52 tests)`; source/APK metadata at `0f9988b00`. | Overlaps 213. Covers the sole later production preflight/message delta; do not claim 265 unique tests or a full native rerun. |
| Claude corrections: five actual host kills | Four detachment boundaries plus orphan staging; result identity, log hashes, driver and fresh-process methods checked. | Actual process death on an isolated emulator fixture; not power loss, physical phone or live cloud. |
| [Astra corrections](../../validation/cloud-astra-fixes-20261021/README.md) | All 52 manifest entries match; raw 363 backend, 191 native, initial 56 and later export 93 summaries agree; 353 JVM totals recomputed. Six Forget and three historical host-death records inspected. | Earlier source checkpoints and overlapping follow-ups. They are not additive unique suites. |
| Post-snapshot export correction | Retained failing old-source export probe and 93-pass follow-up, plus current exact inventory/streaming checks. | Failed external output can remain incomplete; success must not be reported or promoted. |
| [Earlier live candidate](../../validation/cloud-v1-candidate-20261021/README.md) | 30-request CPU/budget record and its route composition, historical closure record, three theme landscape images. | Predates these corrections. No current live-state attestation. |
| [Earlier live Android matrix](../../validation/cloud-live-budget-android-20261021/README.md) | Retained candidate description identifies two sandboxed installations, outage/restart/convergence/conflict sequence. | Two installations on one emulator, not independent hardware or Doze/OEM; not a new corrected live recovery cycle. |

Across the two correction packages, **87/87 manifest entries match**. Hash consistency establishes retained-file identity, not an independent rebuild of the historical APKs. This review did not rehash/reinstall those historical binaries or run their Android cases again.

Superseded failures remain explicitly separated: the original malformed fixture assumption, native launch before any executed test, lint-only build failure, stale concurrent-quarantine regression and missing detachment rejection flag. The later source fixes and passing logs close the latter two; a bare successful build is not used to close them.

## Functionality and verification matrix

“None found” in the blocker column refers to code defects established by this review.

| Functionality / invariant | Implementation and available evidence | Missing coverage / required acceptance | Code blocker |
| --- | --- | --- | --- |
| Library/Unavailable identities; duplicates/order; tags/playlists | Strict shared codecs, persistent mappings, native capture/apply; golden/JVM and retained native identity/digest assertions | Representative real owner catalog; preservation audit during integrated cycle | None found |
| Two-store native transaction/recovery | Pending plan, Room witness, tag baseline; native interrupted-phase tests and historical host kills | Current integrated restart during native application; no claim of power-loss certification | None found |
| Current-cloud one-time restore | Exact snapshot/head/manifest and final OFF/new generation; retained controller ON/OFF/stale-preview cases | Human confirmation/cancel plus current live candidate | None found |
| Local safety ZIP | Snapshot/validation before destructive apply, atomic promotion, protected automatic-pull behavior | Low-space/failed output and usable restore in selected acceptance fixture | None found |
| Full native ZIP import/export | Bounded ZIP/schema validation, expected inventory, byte checks, independent file-swap journal | Actual native ZIP round trip under selected recovery cap; complete native/unrelated-data comparison | None found |
| AI pairing on full native restore | Reversible quiescence before final admission; real paired-runtime native refusal/success tests | Actual paired relay with queued work, final refusal, successful unpair and restart | None found |
| Temporary staging ownership | OS lock and narrow cold-owner sweep; retained native and actual host-death proof | Current process-restart check can be composed with ZIP acceptance; no broad retest needed | None found |
| Enrollment and sync disabled | Explicit preview; durable generation fencing; no automatic network when OFF | Actual two-device initial setup and OFF with offline edits/in-flight request | None found |
| Frozen outbox and conflict | Exact receipt resolution, definitive refusal retirement, bounded re-evaluation; retained native and new local backend execution | Real head race, expiry across prolonged outage, lost response/restart | None found |
| Revocation, rejected identity and detach | Current-generation stop, persistent flag, archival journal, fresh-device rule; native/WorkManager/four host kills | Live rejected credential with pending operation; local detach must not clear remote holds | None found |
| Three rotating archives | Survivor checks, slot reservations and immutable provider identity; local full and selected executions | All three slots, named rotation, exact survivor hashes and completed/multipart inventory on actual R2 | None found |
| Multipart cancel/retry | Durable abort proof and bounded same-identity progress; repeated-abort shim and race regressions passed | Corrected live provider cancellations and interrupted requests; genuine unknowns remain occupied | None found |
| Protected whole-cloud replacement | Exact outgoing archive + grant + final SQL head/epoch receipt; local/native tests | Live frozen incoming/outgoing identity, stale retry, lost receipt and cancellation | None found |
| Historical A→B local restore | Version-2 source/receiving distinction, non-acknowledging baseline; native changed-content and host-death tests | Rebuilt archive index → authenticated pin/download → current Android restore in fresh service generation | None found |
| Operator DATA restore/rebuild/reopen | External fence, actual inventory, bounded plans, exclusive actions and enrollment receipt; 16 selected local cases passed | Authorized provider recovery/reopen composition with current bindings; never reuse old revoked credentials | None found |
| Client input and final authority | Strict shapes/bounds, source-derived identity, guarded transactional SQL; 19 selected and two adversarial probes passed | Current deployed client/backend smoke; not an exhaustive malformed-input certification | None found |
| Daily budget, size and SQL ceilings | CONTROL holds/settlement, metadata validation, rollover and pre-dispatch ceilings; 32 new local results | Representative measured D1 metadata and workload including mutation/recovery; aggregate project policy | None found |
| Housekeeping/receipt retention | Bounded device/stage/receipt windows, protected active work, retained high-water; retained full suite and prior live cleanup | Explicit scheduling decision and scoped current execution if enabled | None found |
| Event-driven/background sync | Semantic observers, unique work, constraints/backoff; retained real Room/WorkManager tests | Separate hardware, long offline periods, Doze/OEM/battery and writer latency | None found |
| Preview/search/themes/credential UI | Retained models, exact groups, secure transient entry; retained native UI/JVM tests and screenshot inspection | Current recovery/replacement/detach dialogs, long text, keyboard, accessibility and all themes | None found |

## Entry requirements for final integrated verification

There are **no outstanding code corrections or new general-review prerequisites** from this review.

The verification owner should pin the selected Android app/test binaries and backend build to this source or an explicitly documented descendant; record any actual production delta. Coordinate a dedicated test surface so another session's emulator/build is not disturbed. Live operations require their own explicit authorization and isolated fixtures. This report is not permission to reopen the closed QA environment.

Use the matching client/server pair: corrected cancellation uses explicit progress and historical recovery uses the version-2 local journal. An older client that ignores progress or cannot decode the journal is not the reviewed candidate.

## Required integrated acceptance scenarios

| Gate | Scenario | Required result |
| --- | --- | --- |
| AV-1 — mode and preview | Enroll two installations; initial initialize/use-cloud; ON/OFF; one-time from ON; preview/cancel/expiry/rotation; in-flight disable | No unapproved local replacement; cancel retains mode; confirmed one-time ends OFF; obsolete generation cannot apply; exact visible candidate equals installed content. |
| AV-2 — normal sync and conflict | Semantic edits, duplicates, Unavailable/tag changes; actual outage and restart; competing head; expired stage; lost publish reply | Offline edits retained; exact receipt resolved once; definitive rejection reaches conflict/fresh policy without permanent stale resend; divergent branches preserved until explicit choice. |
| AV-3 — native backup/restore | Upload a real validated ZIP; download and restore it; include paired AI and a final-refusal race; compare native/unrelated content and preference choice | Exact approved ZIP candidate; required local safety copy; refusal preserves pairing/native state; successful full restore unpairs, restarts and ends OFF. |
| AV-4 — recovery/replacement/cancel | Fill all three slots; rotate the named oldest; replace with changed incoming catalog; cancel/continue from RESERVED, UPLOADING, ROTATING and verified READY; exercise stale retry/lost response | At most three physical/occupied payload slots; required survivors byte-identical; outgoing archive exact; no false cancellation, duplicate successful abort or extra epoch; truthful bounded progress/uncertainty. |
| AV-5 — service-generation recovery | In an authorized isolated recovery cycle, close/drain, provider DATA recovery, physical R2 reconciliation, fresh authority/reopen, then restore old-generation same-library archive on Android | No revived old token; receipts/high-water preserved as required; old provenance usable without old authority or false current acknowledgement; wrong library rejected; current highest counter retained. |
| AV-6 — rejection and local detach | Reject a credential with catalog/recovery/replacement evidence pending, then explicitly detach and enroll fresh authority | Automatic work stops; exact local evidence archived; native data remains usable; no HTTP cleanup/remote hold release implied; fresh enrollment starts OFF and requires preview. |
| AV-7 — deployed resource profile | Minified corrected Worker with representative status, chunk, seal, publish, verification, cancellation and cleanup requests at the admitted catalog/archive sizes | Valid D1 metadata; bounded statement/row/growth behavior and no unexplained holds; measured representative p95 meets the 8 ms target for the chosen profile. Report route mix and per-route results so polling cannot mask missing mutation evidence. |
| AV-8 — current native UX | Light/dark/black themes, portrait/landscape, retained queries, long entries, confirmation accessibility and detach/recovery wording | Actions reachable; visible filter matches rows; every approved semantic change discoverable; cancellation and errors accurately describe mode and unresolved work; credentials absent from saved state/screenshots/logs. |

These are coherent acceptance groups, not a demand to rerun every retained suite or inject every theoretical failure again. Existing proof can be reused when the relevant production code and environment assumptions are unchanged.

## Additional gates before production activation

1. **Supported profile:** choose and demonstrate the real supported catalog and native-ZIP sizes. The previous deployed proof is eight packed pages / 64 KiB catalog and a 1 MiB recovery cap. Do not equate 50,000-stream / 32 MiB codec ceilings or the 256 MiB archive format ceiling with an admitted production workload. Larger caps require explicit measurements and authorization.
2. **Actual device/background behavior:** test independent hardware devices, prolonged offline edits, reconnect, WorkManager under Doze/OEM conditions, and acceptable capture/writer-barrier latency. The two-package emulator matrix does not establish these.
3. **Operational readiness:** confirm bounded housekeeping policy/scheduling, project-wide allocation for any additional resource pair, monitoring of BLOCKED/held states, and an operator procedure that preserves unknown tickets rather than force-clearing them. Keep independent local recovery exports.
4. **Explicit release authorization:** merge, deployment, credential provisioning, plan changes and production activation require their own authority. This review performed none of them.

## CPU evidence boundary

The retained `cpu-minified.json` reports 6.950 ms p95 over 30 requests. Matching `budget-functional.json` shows **26 head requests, one connect, two upload starts (one refused), and one retirement**. Recovery/replacement were disabled. There are no chunk, seal, publish or recovery-verification requests in that sample. Earlier failed CPU samples remain retained.

Therefore the old sample neither proves nor disproves the current mutation/recovery target. AV-7 is an empirical release gate, not a new code finding. No extrapolation from polling to the unmeasured routes is made.

## Completion rule

When the scoped integrated/device/resource gates pass on the selected candidate and the owner provides release authorization, this review does not demand an additional general review. If acceptance reveals a genuine defect, document its exact revision, smallest safe correction and relevant regression; retain the completed evidence instead of restarting the entire review cycle.
