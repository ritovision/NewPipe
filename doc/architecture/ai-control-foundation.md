# AI Control — local catalog foundation

**CURRENT CHECKPOINT:** Local catalog foundation implemented and reviewed. Latest code passes **237 JVM tests and 92 tests on the physical Pixel 7**, all with zero failures. The 92 device tests comprise 54 new AI/migration cases, 35 existing Library cases and 3 legacy migration cases. Application/test APKs compile and signatures are verified. Read-only Claude/Astra reviews and author adjudication are in `doc/reviews/ai-control-foundation-*`.

**NOT A COMPLETE AI CONTROL RELEASE:** No main-menu AI page, background service, persisted pairing/Keystore grants, restore-session/epoch wiring, playback adapter, new URL extraction or Cloudflare/OAuth/WSS gateway is active or implemented yet. The command engine currently operates only through controlled local tests.

**PHONE SAFETY:** Only `org.schabi.newpipe.debug.featureaicontrol` and its `.test` package were installed. The original custom and F-Droid package paths/versions/install timestamps were verified unchanged. All destructive tests used synthetic data; no real catalog data was deleted. No emulator or secondary-machine UI was used.

Historical milestones and earlier compiled-only statuses below are retained as an audit trail; this current summary supersedes them.

Date: 2026-10-16
Branch/worktree: `feature/ai-control`, `/home/rito/techstuff/ritoverse/newpipe-ai-control`
Base: completed Library `b9a8f28d3`. This isolated worktree leaves the shared `custom/dev` and the separate tagging work untouched.

## Implementation milestone, not a remote-control release

The assistant has implemented the first local catalog/security substrate. No code was delegated to Codex or Claude. The new code is **not yet connected to a public endpoint or to a local exported component**, and is not a finished AI Control installation. There is no production pairing/UI, foreground service, playback adapter, URL extraction adapter or Cloudflare gateway in this milestone. Do not advertise those capabilities or mark the overall v1 acceptance criteria complete.

Implemented in `custom/aicontrol/`:
- Strict versioned command envelope with an explicit device, client, catalog epoch, immutable request ID and short expiry. Payload, depth, batch and page limits; unknown operations/fields rejected.
- Independent local permission ceiling and live authenticated-session lease. Disabled by default; no command can grant itself permissions. Session disconnect/disable invalidates queued work, and authorization/deadlines are checked again after acquiring the database writer lock and before committing.
- Saved-Library read/search, local playlist browsing, exact stream-reference validation, empty or populated playlist creation, rename/delete, add/remove/reorder, atomic move between ordinary playlists, save known streams, optional deletion preview, and direct canonical global deletion. No separate human confirmation token is required.
- A same-database receipt ledger: catalog edits and their receipt commit in ONE Room transaction. Retries with the same request ID return the original result; a changed payload with that ID is rejected. Status lookup is scoped to the original device/epoch/client and rechecks the original operation's permissions.
- Existing UI playlist editor and bulk cleanup paths now compare their loaded membership snapshot inside the writer transaction before replacing it. Bookmark reorder updates only ordering, not stale names/thumbnails or deleted playlist objects. A conflict offers an explicit reload rather than silently erasing another writer's changes.

## Atomic receipts and schema decision

Use a proper Room entity/DAO and a **fork-local schema migration from 9 to 10**, rather than an untracked custom table or a receipt sidecar. This adds `rv_ai_command_receipts` and its retention index only; existing catalog tables and rows are retained. Source seams are `AppDatabase.kt` and `NewPipeDatabase.kt`; the migration implementation lives in our owned package.

The receipt has a payload fingerprint and small mutation result, not tokens, secrets, stream titles or search text. Primary key is `(device_id, catalog_epoch, client_id, request_id)`. Results remain available for 24 hours. Expired original commands cannot execute; renewed headers are not retries. Receipt storage is capped at 10,000 retained operations and fails closed when full rather than evicting live deduplication records. A future upstream schema-10 migration must be explicitly reconciled with this fork's number: never silently replace either migration.

A storage/commit exception is reported as `OUTCOME_UNKNOWN`, not an invented failure/success. Missing/expired status records do not prove an action never executed. Mutation retries cannot reverse a later explicit user decision: a repeated old delete will not delete a later re-add, and a repeated old save will not resurrect a later deletion.

SQLite's same-database transaction atomicity is the basis for the ledger design: https://www.sqlite.org/atomiccommit.html . This does not make native multi-file restore crash-atomic. Production activation must still implement the restore/stop barrier and persistent catalog-epoch rotation from the approved plan.

## Selected operation semantics

- Ordinary playlist deletion keeps all Library memberships. Canonical Library cannot be deleted/renamed through ordinary playlist tools, even when another ordinary playlist has the same name.
- Add skips existing occurrences by default; `allow_duplicates=true` explicitly adds occurrences. Existing duplicates and order remain intact. Creating a populated ordinary playlist preserves supplied duplicates.
- Remove-from-playlist removes all occurrences of the exact selected streams only in that playlist. Global Library removal cascades through all local playlists in one outer transaction, reusing `LibraryRepository.removeEverywhere`.
- Move between ordinary playlists adds one copy per selected stream at the destination unless already present, then removes every selected occurrence from the source, atomically. It cannot be used as a lower-privileged alias for Library deletion.
- Full reorder requires a current revision and a complete permutation of existing join positions, so identical repeated streams remain distinct occurrences. A full reorder currently has the 500-item envelope cap; larger targeted reorder operations remain an extension.
- Adding to an ordinary playlist requires both `playlist.write` and `library.write`, because saving is an implied side effect. Deletion scopes are independent. No request-supplied scope list is accepted.
- New URLs are deliberately NOT accepted as caller-supplied metadata. This milestone only manipulates verified existing rows. The later device-side extraction adapter must resolve new URLs before writing authoritative metadata.
- Search is a parameterized title/uploader substring query over canonical saved membership only. LIKE wildcards are escaped. Pagination is live keyset pagination, not a frozen catalog snapshot; it can miss earlier-ID additions during paging. Basic SQLite LIKE case folding is not a semantic/Unicode search guarantee.
- The gateway must independently authenticate the client before asking the local gate for a session. In-memory grant setup is only a local integration seam, not OAuth or secure persistence.

## Validation and deployment constraints

All builds and host tests run on NotCodex-2 with user-local JDK 21, `/opt/android-sdk`, headless Java and no UI/emulator. Reviewers are read-only. Any Android instrumentation uses only an isolated AI QA package and synthetic in-memory databases on the physical Pixel through primary NotCodex. Do not overwrite, clear, uninstall or destructively test the user's existing custom or F-Droid installations.

The feature branch automatically emits `org.schabi.newpipe.debug.featureaicontrol`; verify the built manifest/signature before physical use. Do not present this disposable QA package as a long-lived replacement for `custom/dev`.

Validation evidence and independent reviews are recorded after actual execution, not inferred from compilation. Full lifecycle, restore, network/offline routing, battery behavior, OAuth, pairing and playback validation belong to the remaining implementation phases.


## First review and follow-up implementation

Astra xhigh reviewed the entire initial foundation read-only and returned BLOCK for four medium UI/test issues plus two low issues. Raw output is preserved under `doc/reviews/ai-control-foundation-astra-round1-2026-10-16.md`. The author, not a reviewer, implemented the following corrections:

- View-independent `PlaylistSaveQueue` retains/coalesces the newest submitted manual edit and drains it without relying on Fragment completion subscriptions. Its actual committed state is the expected baseline for the next queued save. UI reattachment observes results; reload waits for a started commit rather than cancelling a database operation. Eleven deterministic JVM tests cover these interleavings.
- UI metadata mutations now update only their intended columns inside the writer transaction. Thumbnail selection revalidates saved membership so a pending pin cannot resurrect a globally removed stream's thumbnail.
- Bookmark order-only behavior now has a matching Library regression assertion: stale names are ignored, not written and not treated as rename attempts.
- Safe correlation IDs are decoded before semantic validation, allowing bounded valid request IDs to be returned on invalid argument errors.
- AI ordinary membership edits reuse the existing automatic-thumbnail policy and preserve permanently pinned images while their streams remain in the Library. Canonical global deletion still clears affected pins as required.
- Added Android cases for writer-wait expiry/disconnect, simultaneous repeated create requests, preserved thumbnails/metadata, and correlation. They remain unexecuted until the physical Pixel is reachable.

The first candidate passed **217 JVM tests**, zero failures, and compiled application/test APKs. This is historical pre-follow-up evidence, not a claim that the revised code has passed yet. The revised headless build and a focused Claude Opus Max read-only milestone review have been requested; results will be recorded only when available.

## Connectivity during implementation

The NotCodex-2 connector began returning gateway errors after the first successful build and Astra review. Existing strictly host-key-verified, noninteractive SSH from primary NotCodex to the same secondary machine was used to continue editing and headless builds. No services, public ports, UI or emulators were started by this recovery. The physical Pixel was offline in Tailscale and absent from both existing ADB servers at the check, so no APK installation or Android instrumentation has been attempted. An unrelated emulator already listed on primary was left untouched.


### Latest executed host validation

After the review fixes and the additional queue-reload recovery check, the headless build passed with **229 JVM tests, 0 failures/errors/skips** (176 baseline + 53 new). Application and Android-test APKs compiled; all **44 new AI Android cases remain compiled-only**. Checkstyle and Ktlint passed. Host SQLite checks confirmed the 12 pre-existing physical table schemas are unchanged by the migration, exercised the source-extracted search SQL against 20,000 synthetic rows with bounded results, and verified rollback when receipt insertion aborts. These SQLite checks are not Android/Room runtime evidence or a phone performance benchmark.

QA application identity is `org.schabi.newpipe.debug.featureaicontrol`; its test APK targets that exact package. Both signatures were verified before any potential transfer/install. No physical installation has happened. The main code checkpoint may be committed before reviewer follow-up results; a commit is not release approval or evidence that the remaining AI Control phases exist.


### Second Astra follow-up — current uncommitted correction

The second Astra review closed all six original findings but found a retained-save/view-recreation interleaving, unhandled expected errors in existing thumbnail callers, and a test barrier positioned before authorization. Its full response is recorded in `doc/reviews/ai-control-foundation-astra-round2-2026-10-16.md`. The author accepted all three and implemented:

- A recreated/reloading playlist view now waits for retained submitted edits to drain before reading and presenting a new editable database snapshot. Loading generations invalidate old reads/callbacks; delete/drag mutations are blocked while loading. The queue's new idle observer does not discard saved intent and has dedicated deterministic tests for the reported `[A,B,C]` recreation case.
- Automatic thumbnail selection for Unset is performed inside the writer transaction. Ordinary append refreshes automatic thumbnails in the append transaction. The Add To dialog no longer runs a stale second thumbnail write or cancels the user's submitted append when automatically dismissed. Relevant read/write error subscriptions are explicit, so expected conflicts cannot hit the application's uncaught Rx handler.
- Cancellation/deadline tests synchronize at a second clock read after successful authorization while another writer is held. Their names describe post-authorization rejection, not a claim that operating-system scheduling necessarily paused at an exact lock instruction.

This adds two upstream integration files, `BookmarkFragment.java` and `PlaylistAppendDialog.java`, for the identified thumbnail callers. The new code is still a local foundation, with no network entry point. The previous 229-test result does not validate these subsequent corrections; updated build results and verification must be recorded separately.


## Final executed validation — 2026-10-16

- **Host:** 237 JVM tests, zero failures/errors/skips; Checkstyle and Ktlint pass; application and instrumentation APKs build successfully. 61 host tests were added relative to the 176-test completed Library baseline.
- **Physical Pixel 7, Android SDK37:** 89 combined AI/Library tests PASS, followed by all 3 existing legacy migration tests PASS. These execute Room transactions, receipt-insert and batch-delete rollback failures, concurrent duplicate commands, direct deletion/re-add/retry invariants, post-authorization cancellation/deadlines, saved-only reads, source/reference validation, schema migration, and Library regressions on synthetic data.
- **Reviewer outcome:** Claude Opus Max PASS WITH NONBLOCKERS for its inspected foundation. Author corrected actionable feedback. Astra's UI correction check passed, and its final Claude-fix check passed with the narrow backup-preflight precision note documented, not silently ignored.
- **Proof:** `ai-control-foundation-build-evidence.json`, `ai-control-foundation-pixel-evidence.json`, and the two full instrumentation logs under `doc/validation/`.
- **Not claimed:** real user-data mutation tests, actual Fragment lifecycle/gesture stress, older Android-device validation, complete corruption-safe native restore, full network/security deployment, or finished AI Control product.

The phone was initially offline, then reachable but unapproved, and finally authorized on the primary host's existing Linux ADB identity. The authorized hardware serial was verified before installation. The separate QA package is retained for continuing development; it is not an update to the user's main custom app.
