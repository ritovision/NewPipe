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
