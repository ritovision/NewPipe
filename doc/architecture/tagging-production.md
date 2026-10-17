> **Latest acceptance (October 17, 2026):** Integrated native tagging, optional AI command support and backup/recovery are implemented and independently reviewed. 308 JVM / 56 gateway / 153 native tests and 5 real process-stop checkpoints passed; strengthened rollback tests also passed. Newtest is installed on the Pixel; production customdev/F-Droid apps remain untouched. Gateway extension deployed; live authorized AI tag-write acceptance still needs the paired device online and explicit tag grants. See `../validation/tagging-claude-20261017/README.md` and `../reviews/tagging-claude-adjudication-2026-10-17.md`. Earlier milestone notes below are historical.

# Production Tagging — execution contract

Date: 2026-10-17. Authorized: finish production tagging, independent Astra and Claude read-only milestone reviews. Based on approved native UI at `0bd2e1854`; no AI Control merge.

Branch `feature/tagging`, isolated worktree `newpipe-tagging`. Debug package remains `org.schabi.newpipe.debug.featuretagginguispike`, launcher `newtest`, original icon and signing key. Neither stock, Library nor AI installation is updated. Demo code is a reference only; production has no fixture injection/reset.

## Milestones
1. Persistent category/tag/stream metadata; same-screen reusable editor; Library-save and deletion coordination; real details and playlist editing. Prove SQLite/Room behavior, not only compilation. Independent Astra xhigh and Claude Opus max review.
2. Approved native accordion UI on the actual Library; background search/filter/sort with bounded displayed rows; real ordered playback/queue/playlist snapshots. Review and native acceptance.
3. Standard backup/restore integration, interruption/recovery and large-data validation; final review, installation and handoff. No migration or import of existing user data without explicit action.

## Product decisions
- Category deletion removes contained tags and assignments, never Library/media/playlist membership. Untagged means zero assignments.
- Globally unique case-insensitive trimmed tag names; categories optional. Initial matching uses a global AND/OR include set plus NOT exclusions, not grouped expressions. Library order and title sort; snapshot playlists are static and nonempty. Bulk edits, dynamic smart playlists, cloud and AI integration are deferred.
- Preserve native NewPipe theme and accordion UX. Collapsing is presentation only: active positive/negative chips remain usable. Explicit Apply for persistent single-item changes; cancel/dismiss does not save. Stale editors fail with reload feedback rather than resurrecting removed media.

## Integrity design
Independent SQLite database, no upstream Room schema edits. Stream identity is the exact saved `(service_id, url)`, never cross-installation row ID. Tagging ensures Library save first; failed tag write can leave a valid saved-but-untagged item.

Global removal uses a durable intent in tag storage, the existing verified Library transaction, then tag cleanup. All ordinary create/append/update and tag mutation entry points cross the same lock and recover pending intents before new explicit saves. Recovery resolves the current row from service+URL, not a stale UID. Import must fence pending intents from the replaced Library; no missing-association purge inferred from stock imports. Association revision tokens reject stale tag editor writes.

No SQL writes on the UI thread, no SQLite ATTACH claim of cross-WAL atomicity. A narrow native integration, not a generic adapter/plugin framework.

## Evidence policy
Build/tests on NotCodex-2 only; runtime on primary NotCodex or the explicitly connected Pixel. Test-only databases/fixtures use isolated package/cache paths. No app data clears. Reviews read exact candidate bytes; coordinators implement fixes. Preserve failures, reviewer reports and accepted limitations. Do not call compiled instrumentation executed, or dispatch call accepted playback proven.

## Integrated continuation — 2026-10-17

User authorized continuation on the completed AI-enabled base with Astra Max reviews while Claude is unavailable. Current integration branch: `integration/tagging-ai-20261017`, based on `custom/dev` at `af902e5a8`, preserving tagging M1 history. Main Room remains **NP9/RV1 / 9001**. The independent tag database upgrades 1 -> 2 to add tag command receipts and AI-removal cleanup intents; existing categories/assignments are preserved.

### Current implementation (not yet a final release acceptance)
- Real native Tags page, approved NewPipe light/dark/black styling, category accordions retaining selected +/- chips, text/AND/OR/NOT/Untagged filtering, Library/title sort, bounded rows with full exact result snapshots, shared persistent editor, category/tag manager, real playback/append and static playlists.
- Native and AI share the query/model/repository. Local playback uses the existing verified Android playback adapter without requiring AI opt-in. Retained ViewModels own save/queue tasks across rotation. No production demo fixture injection/reset remains.
- New optional device capabilities: category/tag list/create/rename/move/delete, get/set saved-item tags, and tagged Library search. Explicit `tags.read`, `tags.write`, `tags.delete` permissions; old device/client grants do not expand. Tag/category removal never deletes videos. AI assignments target exact already-saved streams; new URLs use existing save tools first, then tag assignment. Deletion of Library items remains independently controlled by `library.delete`.
- Lock order: authorization lease -> TagRuntime -> Library monitor -> Room. Existing UI optimistic-concurrency guards and Room mutation/receipt atomicity are retained. Tag-only operations and their receipts commit together in the independent tag DB. AI Library removals stage only eligible saved keys before Room, then clean tags only if the matching Room receipt proves commit; a rolled-back operation is never replayed as a destructive action.
- Exact receipt lookup across both stores precedes intent staging. Replaying an earlier delete cannot erase later re-added tags. Recovery runs before receipt pruning or new Library/tag operations.
- Standard backup/export uses SQLite-owned `VACUUM INTO` snapshots under the shared canonical/tag writer barrier, not raw copies of an open WAL database. This requires SQLite 3.27+ (present on the target Android test devices); older engines fail explicitly without altering catalog data rather than producing unsafe copies. Exported metadata contains no pairing/AI enablement keys or executable command/recovery history.
- Import first stages, bounds and validates the entire ZIP, SQLite integrity/schema and binding before AI shutdown. Then AI drains outside the tagging lock, all old repositories/helpers are invalidated/closed, and a durable multi-file swap journal installs main DB, tag DB, binding and companion-file absence. Before COMMITTED, recovery restores the whole old set; after COMMITTED it installs the whole new set. Recovery precedes database open. Failed recovery keeps its evidence and refuses access.
- Missing tag metadata in a stock import retains unmatched tag associations while removing old executable intents from the staged copy; reused integer Library IDs do not authorize replay of pre-import deletions. Successful imports restart with AI unpaired. Preferences are optional and parsed before data replacement. No actual user app is cleared or upgraded by this development work.

### Validation checkpoints
- Initial integration APK R3: SHA-256 `0020bc7f6d372103f1e9c01906b575be2cfd30dc7f16ec62b921597e546b7d74`, test APK `47d617c2d3d102ab08d7e0597649ab25bcbe1dc2dded40b301b3fd246869694b`; same newtest package and existing debug signer.
- **307 JVM tests PASS**, Java/Kotlin style checks and app/instrumentation builds PASS. **51 gateway tests PASS**, including optional capability negotiation, explicit new scopes, old-grant rejection and replay.
- Primary owned API34 emulator `emulator-5574`: **125/125 native tests PASS** on that APK (13 new AI/tag coordination; 6 backup/restore; 15 tag repository; 2 retained editor; 53 existing AI catalog; 35 existing Library; 1 NP9 -> 9001 migration). Physical production package untouched.
- Additional scale and actual host-driven process-death tests, final pinned code reviews, real native visual acceptance, and gateway rollout are still pending at this checkpoint. Do not treat a compiled test or a simulated Error as an executed process-death test.
