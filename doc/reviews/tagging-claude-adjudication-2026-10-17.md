# Claude Opus Max review adjudication — Tagging + AI integration

Reviewed base: `a78207dd48a5b12e6d2245d5d9d82f4f831ec5c1`.
Original reports preserved unchanged in `tagging-claude-data-max-2026-10-17.md` and `tagging-claude-ui-max-2026-10-17.md`.

## Release-blocking findings

**D-P1 / legacy upstream migration defaults: ACCEPT, FIXED IN SOURCE.** The subscription column introduced by upstream MIGRATION_4_5 retains literal DEFAULT 0 while fresh NP9/RV1 schema has no default. The structural validator was stricter than the accepted migration. Normalize exactly this known column/default pair, not arbitrary defaults across the schema. All other columns, types, foreign keys, indices, unknown defaults and executable schema checks remain strict. New native regression creates genuine upstream schema4 through MigrationTestHelper, imports it through the standard pipeline, verifies the subscription and9001 version, then exports/revalidates again. A second test checks unexpected expression defaults remain rejected.

**D-P2 / leased connections at restore: ACCEPT AS AN UNPROVEN RISK REQUIRING A TEST, not a demonstrated corruption.** Inspected actual Room2.8.5 classes: close uses a CloseBarrier; compat begin/end transactions also interact with the open helper. The shipped fix adds a verified bounded native WAL checkpoint before any filesystem replacement; a busy checkpoint refuses to publish a swap. Added a held Room-backed writer across install plus injected IOException rollback and a persistently busy raw-WAL check. Do not call this risk closed until those tests and source re-review pass. A successful checkpoint alone is not claimed to prove arbitrary leaked third-party connection lifetimes.

**U-P2-1 / permanently stale Manage forms: ACCEPT, FIXED IN SOURCE.** Manage subscribes to live definition changes, coalesces pending reads, stops observing when hidden. A typed revision conflict carries authoritative definitions into the retained form; the current saved name/category are displayed, the draft is kept, and an explicit second Save uses that displayed revision. Another concurrent edit conflicts again. Deleted targets remain disabled instead of being silently recreated. Current revisions/categories survive recreation.

**U-P2-2 / false OUTCOME_UNKNOWN: ACCEPT, FIXED IN SOURCE.** Validate names, referenced tags/categories and namespace-wide duplicate names before any tag write. Predictable failures return TAG_NAME_EXISTS, CATEGORY_NAME_EXISTS, TAG_NOT_FOUND, CATEGORY_NOT_FOUND or INVALID_NAME / REJECTED. Receipt-capacity refusal returns RECEIPT_CAPACITY / NOT_EXECUTED. Storage/commit ambiguity still remains OUTCOME_UNKNOWN. New native cases cover case-insensitive duplicates across categories, missing IDs, invalid moves and a full10,000 receipt history; gateway test verifies cached rejection fidelity.

## Lower-priority findings

| Report item | Disposition |
|---|---|
| D-P3.1 bad binding export | Accepted: validate copied binding against staged DB before opening/truncating export destination. Regression preserves an existing destination on invalid binding. |
| D-P3.2 predictable preflight failures after unpair | Accepted: check snapshot-engine support when retaining missing metadata and conservative free-space reserve before shutdown, and recheck immediately before install. A late disk race cannot be guaranteed away; safe journal rollback remains required. Older SQLite versions still fail explicitly, not via unsafe raw snapshot fallback. |
| D-P3.3 unknown tag failures | Duplicate of U-P2-2; same fix/tests. |
| D-P3.4 AI cleanup after Library repair | Accepted: exact same-catalog committed receipt, not old Library numeric ID, authorizes metadata-only cleanup. No remote deletion is replayed. UI deletion intents still require verified matching binding. |
| D-P3.5 pending work before stock tag snapshot | Accepted: complete same-catalog pending recovery before snapshot; then discard imported runtime histories. Stock-import test now explicitly verifies a previously committed removal does not resurrect tags, while unrelated missing-video assignments are retained. |
| D-P3.6 leftover copies | Accepted: before-open recovery cleans only inert owned staging/retired directories when no active journal exists; active failed journals and unrelated folders remain. JVM regression added. |
| D-P3.7 trigger execution before rejection | Accepted: read-only sqlite_master scan rejects triggers/views before opening either imported copy for migrations/writes. Structural comparison remains after migration. No unrestricted SQL parser was introduced. |
| D-P3.8 restart blocking / unregistered test stores | Restart/panic path no longer blocks main thread closing DB behind a long IO barrier; ProcessPhoenix terminates process and SQLite handles. Runtime's unregistered repository tolerance remains for isolated existing test DBs, not production initialization, which always attaches tagging. Import still invalidates all live stores and fences open helpers. |
| D-P3.9 historical import helpers | Not a reachable production blocker: old helpers have no production callers; standard settings path is CatalogTransferModel/TagBackupCoordinator. Preserve legacy upstream tests rather than expand this release into unrelated API deletion. Mark as maintenance debt. |
| U-P3.1 raw native player errors | Accepted: common queue/player failures map to user-readable explanations. |
| U-P3.2 overwritten action messages | Accepted: separate retained action-status view is not overwritten by Library refresh. |
| U-P3.3 repeated background snapshots | Accepted: one read at a time plus pending refresh; subscriptions detach while screen stopped. No truncated results or new paginated storage redesign. |
| U-P3.4 page-size-dependent revision | Accepted: canonicalize tag ID ordering and exclude page-size/offset from semantic result revision. New native pagination test changes page size and equivalent tag order. |
| U-P3.5 Back cancels pending playback | Retain deliberate cancellation when leaving an uncompleted screen; no delayed playback after user exits. Not a data-safety blocker; broader persistent-action UI remains optional. |
| U-P3.6 plain secondary dialogs | Preserve playlist-name draft during typing so reopen/recreation does not require retyping. Short-lived menus and delete-confirmation presentation may be dismissed on rotation; mutations are still repository-checked. Not a blocking rewrite to a new dialog architecture. |
| U-P3.7 invisible names | Accepted: matching native/AI/gateway rejection of Unicode control and format characters; ordinary international text and emoji remain allowed. |
| U-P3.8 small UI | Correct singular result label, show uncategorized by default when flat-only, and keep regenerated checkboxes disabled during Apply. Full translation resource extraction is deferred for this personal English-language fork. |

## Evidence and scope

No tests are inferred from source: results are captured separately. First correction build passed JVM/style and app/test compilation, gateway56/56. Its actual native run exposed Android restrictions in two new preflight lines (Files.getFileStore permission; execSQL on a result-bearing PRAGMA); corrected to Android StatFs and rawQuery. These were development regressions caught before any phone rollout. Native rerun pending at this checkpoint.

No main customdev/F-Droid application data was cleared. No AI credentials, pairing, consent or gateway deployment are changed by adjudicating these reviews. Main Room remains NP9/RV1=9001; tag DB remains independently versioned2. Review closure and installed-device acceptance are recorded only after execution.

## Follow-up: restore admission ordering

The corrected Android preflight rerun passed 11/12 backup tests, including genuine upstream4 -> NP9/RV1 import and re-export, expression-default rejection, invalid-binding export preservation, and busy-WAL refusal. The remaining held-writer test reproduced a concrete lifecycle failure: closing Room before draining prevented an already-admitted transaction from completing (`attempt to re-open an already-closed object`). This is not evidence that silent corruption occurred, but it proves a post-close checkpoint alone is insufficient.

Correction: the live Room open helper now wraps transaction and statement execution with a bounded admission/drain gate. An admitted transaction holds a read lease through its matching end; restore fences new work and drains those leases BEFORE close. Existing transaction owners can finish; new transactions and cached statements cannot start after the fence. A bounded raw checkpoint still verifies WAL state before/after close. Getter/state inspection needed to finish Room's legacy endTransaction path can use the cached proxy without reopening the database. Added a stale cached statement/handle regression. No global queue or database framework was introduced, and main Room schema is still9001.

This specific helper change and runtime regression require focused data-review closure. The whole feature is not declared finished based only on these source changes.

The next native run exercised the new writer gate but exposed expected lifecycle cancellation arriving as a fatal exception in Room's asynchronous invalidation coroutine. The gate now throws CancellationException for requests refused by an active restore fence. This does not suppress storage faults or allow writes: it marks a deliberately stopped coroutine as cancelled rather than reporting a programming crash. Cached statements still refuse execution, and admitted transactions still drain. Full rerun and focused source review remain required.

## UI closure follow-up

Claude's first UI closure review closed the original stale-form and inaccurate-AI-rejection issues, but identified two narrow regressions. Both are accepted:
- A popup opened before a live Manage refresh could pair old values with the newly refreshed revision. Each rendered group now captures the same immutable definitions snapshot used by its popup and form values. A real popup/live-rename regression requires a conflict rather than silently undoing AI's rename. Popup references are dismissed with the parent view.
- Re-validating every stored pre-policy name during uniqueness scanning could reject unrelated new valid names. Stored-key comparison now uses the historical trim/lowercase normalization without validating old display text. New input still validates. Native/gateway Unicode policy permits ZWNJ/ZWJ for legitimate scripts and compound emoji, iterates code points consistently, and rejects other control/format code points including supplementary tag characters. Added legacy-name and Unicode regressions.

The original backup data fixes plus admission/cancellation correction now passed **13/13 actual Android backup tests**, including legacy import/re-export, malformed backup rejection, admitted Room writer rollback and cached statement refusal. Full integrated tests are still being rerun; this is not an inferred pass for unrun code.

## Final independent source closure

Claude Opus Max data closure at `54ed35ae6`: **PASS WITH NONBLOCKERS**, explicitly closing D-P1 and D-P2 in source for normal application Room writers and statement handles. Source after that candidate has no additional restore/helper changes. Runtime then passed all153native cases and all5genuine process-stop recovery checkpoints on the matching app; old-format roundtrip and held-writer/statement cases are included.

The review suggested stronger evidence assertions: a timeout before publication should not count as the intended rollback regression, and a generic already-closed SQLite error should not count as the new gate rejecting a stale statement. Tests now require the injected IOException, assert the journal was published, arm the writer release only after verifying it remains in flight at shutdown, and require CancellationException specifically for fenced old handles. These are test-only changes; no production runtime byte changed after the reviewed/installed candidate.

UI closure at `c8041a269`: **PASS WITH NONBLOCKERS**, explicitly closing stale-popup and legacy-name regressions. Remaining acknowledged nonblockers concern benign Unicode look-alikes involving joiners, a required rename when editing a legacy name now forbidden for new input, historical unused import helpers and future maintenance of the explicitly scoped Room wrapper. No known P0/P1/P2 source blocker remains in the completed review scopes. Live authorized tag commands are still a separate rollout acceptance check, not inferred from static review or gateway tests.
