# AI Control foundation — author adjudication and release boundary

**Date:** 2026-10-16
**Base:** b9a8f28d3; initial code checkpoint fa2ad4d74.
**Scope:** Local catalog foundation only. Not a deployed remote-control release.
**Authorship:** Implementation and every fix were written by the main assistant. Reviewers only inspected source and reported findings.

## Reviews

- Astra xhigh round 1: BLOCK, four medium and two low findings. All six fixed by the author and independently closed in round 2.
- Astra xhigh round 2: BLOCK, two further UI interleavings and one test barrier gap. All three fixed by the author and independently closed with PASS in round 3.
- Claude Opus Max: PASS WITH NONBLOCKERS for its inspected local foundation, with ten findings/nits, two hypotheses and test gaps. It took a source snapshot earlier than the final follow-ups; its old test counts are not current build evidence. Do not describe this as Claude approval of later author changes.
- Final Astra xhigh verification of the Claude-driven fixes: PASS WITH NONBLOCKERS. No blocking regression in the six requested corrections; one backup-preflight documentation precision note is explicitly scoped below.

Raw outputs are preserved separately. This adjudication does not rewrite reviewer findings and is not another independent review.

## Claude findings and decisions

| Finding | Author decision and correction |
| --- | --- |
| F1 blocked queue after storage error | Accept. Already corrected after Claude's initial reads: a fresh unmodified reload resets idle blocked state, pending data and stale result. Deterministic regression added. A dirty editor is never rebased by an ordinary observer notification. |
| F2 view reload undoing retained edits | Accept. Recreated/reloading views wait for the queue to become idle before showing a new editable DB snapshot. Load generations and mutation guards prevent stale interactions. Astra round 3 independently passed this correction. |
| F3 unhandled expected thumbnail conflict | Accept. Automatic Unset selects current survivors inside the transaction; Add To no longer performs a second thumbnail write; relevant subscriptions have error consumers. Astra round 3 independently passed this correction. |
| F4 expired committed retry reported unexecuted | Accept. Mutations may look up their same-device/epoch/client receipt before deadline rejection. A matching retained receipt returns COMMITTED/replayed even after the execution deadline; no new work occurs. A missing record beyond its retention horizon is OUTCOME_UNKNOWN, never proof of no prior execution. New expired work remains forbidden. |
| F5 future commands implicitly treated as reads | Accept. Scope and mutation classifiers enumerate every sealed command explicitly, with no default branch. Adding a future command without classifying it now fails compilation. |
| F6 read-only history/feed probing | Accept. Get-stream and optional removal preview first require canonical saved membership. Hidden/missing/mismatched references all return the same NOT_FOUND code; a mixed preview returns no partial hidden data. Added saved-only read regressions. |
| F7 own global delete leaves stale editor baseline | Accept the reload approach. After the explicit UI global removal, discard the invalid editor baseline and wait for already-started saves before reloading committed membership. An unsaved overlapping reorder can be discarded; preserving an intent-level reorder across a simultaneous global deletion is not promised. The global deletion itself remains atomic and cannot be resurrected by that editor. |
| F8 schema downgrade compatibility | Accept. Clearly document that schema-10 AI-era backups cannot be loaded by pre-AI/schema-9 custom or stock builds. The current importer validates SQLite user_version before swapping files and rejects future/negative schema versions and header prefixes too short to contain user_version. Old installed apps cannot be retroactively protected. Preserve pre-upgrade backups. |
| F9 spec claimed durable rejection receipts | Accept documentation correction. This ledger stores COMMITTED mutations only. Lost rejection responses remain uncertain unless separately reconciled; missing receipts never prove no effect outside the retained evidence window. No extra rejection ledger is falsely claimed. |
| F10 shared receipt cap | Accepted bounded limitation, not a silent failure. At 10,000 retained operations, further writes fail closed until old receipts expire; no live deduplication receipt is evicted to admit a new write. Per-client admission/rate limits belong to activation/transport work. An authorized abusive writer can already alter its allowed catalog; this limit is not represented as a cross-client availability guarantee. |
| F10 unused guard / legacy overloads | Retain for compatibility with the existing repository/tests, rather than expanding scope with API removal. Production UI replacement callers use the guarded API; remote operations use the constrained executor. No generic unguarded API is exported to an AI client. |

## Hypotheses and test coverage

- **H1 lock order:** valid integration warning, not a presently reachable deadlock. Enable/disable must run outside a Room transaction, off the UI thread. The permission-gate-before-database lock order is now explicit in code documentation. The future stop/restore/panic wiring must obey this rule and be independently tested before activation.
- **H2 append into a deleted playlist:** hardened proactively. Validate target existence inside the append writer transaction before upserting metadata or inserting joins; return a handled conflict instead of reaching deferred foreign-key commit failure.
- **T1 targeted metadata test:** acknowledge that a sequential functional test is not a reproduction of an old read/write interleaving. The source now uses single-column-group transactional UPDATEs; actual UI/Room stress remains a device validation gate. Do not count the functional test as concurrency proof.
- **T2 cancellation barrier:** moved after successful authorization while another writer is held; no assertion of deterministic exact scheduler/lock placement. Astra round 3 accepted the corrected scope.
- **T3 detached listener:** replaced the swallowed exception assertion with an explicit callback counter checked outside the listener. The separate callback-failure-resilience test intentionally verifies that presentation exceptions do not stop the write queue.
- Pure JVM queue tests do not execute Fragment lifecycle. Android cases were compiled-only at review time. They have since executed successfully on the physical Pixel: 92 synthetic AI/Library/migration tests. This still does not exercise Fragment gesture/lifecycle stress. No emulator substitution was used.

## External source used for the backup guard

SQLite's file format specifies a four-byte big-endian user_version at offset 60: https://www.sqlite.org/fileformat2.html#user_version_number . This is a preflight compatibility guard, not a complete database-integrity check or a claim of crash-atomic native restore.

## Remaining activation gates

No pairing secrets, no-backup grant store, catalog-epoch restore rotation, opt-in foreground service, main-menu page, playback adapter, new URL extraction or Cloudflare/OAuth/WSS endpoint is implemented yet. They remain required for the actual AI Control release. The current code has no remote or newly exported command entry point. Only a separate QA application/test package was subsequently installed for the 92 synthetic device tests. The existing custom and F-Droid apps were not updated. Nothing was deployed to Cloudflare.


## Final verification note: backup preflight is not full integrity validation

Accepted Astra's precision correction. This preflight reads the first 64 bytes to validate magic and user_version; it rejects a prefix shorter than those fields, not every kind of truncated or corrupt database. A 64-byte compatible-looking prefix can still pass this limited check. The pre-existing importer is not made crash-atomic or fully corruption-safe by the version guard. No general database-integrity guarantee is claimed. Full staged database/Room validation is a separate restore-hardening gate, especially before integrating remote restore/backup control (not a v1 AI command).
