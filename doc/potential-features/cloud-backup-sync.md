# Cloud Backup and Multi-Device Sync — Exploration

**Status:** Planning proposal / unresolved; no implementation authorized
**Priority:** To be determined
**Created:** 2026-10-15
**Scope:** Personal NewPipe fork on multiple Android installations (including a Windows Android emulator), potentially using Cloudflare.

## Problem and goal

The user wants to be able to lose or replace a phone without losing their NewPipe library, and to move among their phone, tablet, and Windows emulator without repeatedly moving files or manually reconciling independent playlist edits.

**Existing manual export/import is already a NewPipe feature and is NOT the feature being proposed here.** Do not spend this project's scope reimplementing a manual export/import interface. It may be reused internally as a backup snapshot source, but cloud protection and device synchronization are the new work.

There are **two distinct possible features**, which can be built separately or together:

1. **Automatic cloud backup:** Periodically upload recoverable, versioned snapshots to remote storage. A replacement device can restore them. Snapshot restore does not merge independent live edits.
2. **Bidirectional cloud sync with a centralized shared store:** Multiple installations can add/remove/reorder songs and playlists, work offline, and reconcile changes when connected. Restoring the current shared library to a new device should not require the old device.

**Sync is not a replacement for backups.** A deletion or bug can propagate through sync; historical backup snapshots must remain independently recoverable.

## Expected user experiences

### Automatic cloud backup

- Enable Cloud Backup, choose/account-connect a provider, configure recovery credentials.
- The app automatically saves snapshots (on some schedule / significant changes / manual 'Back Up Now' — cadence open).
- See last success, destination, retention, errors, storage and restore availability.
- On a fresh install, pair/authenticate and restore without needing the previous device.
- Historical snapshots allow recovery from accidental deletions, corruption or unwanted sync events.
- Prefer retaining NewPipe's native database/preferences as well as custom Library, tags and other extension metadata; actual media downloads are *out of scope by default*, subject to a deliberate decision.
- Document exactly what is and isn't protected. A partial/failed upload must not replace the last known good snapshot.

### Bidirectional sync

- Pair Phone A and Windows-emulated NewPipe (and optionally other Android devices) to the **same** logical account/library.
- Save a video to Favorites on the phone → it becomes saved in Library and Favorites on the other device after syncing.
- Add unrelated media to another playlist while Windows is offline → changes merge after reconnection.
- Rename, reorder, tag, remove or create playlists on any device, subject to consistent conflict rules.
- Keep NewPipe functional while disconnected. Offer **Sync Now**, activity/status, pending changes, retry and diagnostics.
- Link/pair/revoke devices independently; losing one device should not prevent using the library.
- Prevent unintentional duplication, deletions or resurrection of globally removed items.

### What a cloud dashboard / MCP may do

A future MCP tool or website could read and manage the **shared library metadata** (if security choices permit), even when Android devices are offline. This is different from playing videos: NewPipe's extraction and playback engine remains on Android, and the synchronized schema alone does not provide a Windows/web player.

Cloud/MCP must use the same playlist semantics as the Android fork, with explicit permissions for destructive edits.

## Shared Library semantics to preserve

Follow [Library Playlist System](../specs/library-playlist-system.md):

- Every stream added to any playlist must also be in **Library**.
- Removing it from one ordinary playlist keeps it saved elsewhere.
- **Removing a stream from Library removes it from all other local playlists.** Sync must propagate this as a *global removal*, not an ordinary membership deletion.
- Concurrent/offline changes must not silently resurrect items intentionally removed from Library.
- Stream identity should include service + stable URL/provider ID; never rely on local auto-increment Room IDs across devices.
- Duplicates, ordering and cross-service equivalents require explicit policies.

Also consider preserving tags and future [Pruning / Library Health](pruning-library-health.md) status, but avoid making deferred features prerequisites for cloud backup.

## Candidate infrastructure (NOT a settled architecture)

**Cloudflare** is a plausible home because the project already plans to use it for other personal services:

- **Worker:** Authenticated API; paired-device identity; backup/sync endpoints; optional MCP-facing API.
- **R2:** Encrypted, versioned full backup archives; retention and restoration.
- **D1:** Canonical shared metadata / playlist membership state, revisions and sync cursors, possibly a change log.
- **Android side:** Small isolated adapter around NewPipe's existing playlist/database operations, plus our separate metadata layer. Avoid modifying NewPipe's internal Room schema where feasible.

An alternative is snapshots-only in R2 initially, then add D1 synchronization after the data semantics are understood. The split is a proposal, not a requirement.

Do not send actual audio/video files through this system by default. Saved stream links, titles, metadata, playlists and tags are tiny compared with media. Extractor/playback compatibility remains NewPipe's responsibility; Cloudflare sync doesn't resolve unavailable videos.

## Data concerns

**Backup coverage candidates:** NewPipe's native database and preferences, saved streams, local playlists, subscriptions, watch/play history, custom tags and other extension metadata, plus a manifest/checksums/schema versions. Verify the actual exported contents and consistency before promising full restoration.

**Sync coverage candidates:** Library set, playlist definitions/memberships/order, custom tags, subscriptions; optionally history, favorites, player state or selected preferences. Device-specific settings may need to stay local.

**Snapshot restore:** An intentional recovery operation; exact merge-versus-replace semantics should be explicit. No silent overwrite of valuable local data.

**Record sync:** Stable logical IDs, incremental changes, tombstones for deletion, idempotent retry, per-device cursors/revisions, atomic application where practical, reconciliation after skipped app versions or long disconnection. All changes must pass through Library rules.

**Cross-device compatibility:** Different app/schema versions, initial backfill of older local playlists, repeat imports, reassociation after local database IDs change, and migration/rollback handling.

## Conflict scenarios to define

- Phone adds to Favorites while Windows adds a different song to Workout: ordinarily merge both.
- Phone removes Song A from Library while an offline device adds Song A to a playlist: **global delete vs. concurrent add** needs a firm ordering/recovery policy; do not silently resurrect or discard without a rule.
- Devices rename a playlist differently, edit the same tag, or reorder the same playlist concurrently.
- One device deletes a playlist while another adds content to it.
- Device returns online after weeks/months with obsolete data or an earlier schema.
- Incomplete uploads, repeated requests, interrupted migrations, corrupted snapshots, compromised credentials, or accidental bulk changes.

Possible policies include delete-wins for older edits, explicit conflict review for ambiguous concurrent edits, or versioned/causally ordered operations. Decide only after comparing expected UX and implementation cost.

## Security, privacy, and recovery

- Authentication and device pairing; revoke compromised/lost devices; prevent unauthorized mutations.
- Protect credentials on-device; HTTPS in transit; encryption at rest/backups; independent recovery path when every existing device is lost.
- Open design choice: cloud-readable shared metadata versus end-to-end encrypted/zero-knowledge sync. If only devices can decrypt, ChatGPT/cloud MCP cannot search/modify the offline library without a trusted device or carefully scoped decryption capability.
- Consider opt-in backup encryption keys, passphrase recovery, key rotation, account recovery, and server access logging.
- Protect backups from destructive synchronization operations; use version retention and periodic restore drills.
- Treat private subscriptions, viewing history, and saved-video lists as potentially sensitive personal metadata.

## UI concepts (not approved)

Potential Settings section: **Cloud & Devices**.

- Enable Cloud Backup; last backup; Back Up Now; view retained snapshots; Restore.
- Enable Library Sync; Sync Now; last sync, errors and pending changes.
- Paired devices list: phone, tablet, Windows emulator; rename/revoke.
- Recovery and encryption settings; account/session management.
- Explain explicitly that Sync Now ≠ Back Up Now and that media files are excluded unless separately configured.

## Open product and architecture questions

1. Do we build **automatic backup only**, **bidirectional sync only**, or both? In what order?
2. How automatic should backups be, and how many historical generations are retained?
3. Which data is backed up vs. actively synchronized? What stays device-local?
4. What is the authoritative model: shared cloud library with offline replicas, per-device ledgers, or other?
5. Which conflict resolution policies apply to concurrent adds/removes, playlist reorder and global Library deletion?
6. Does deleting from Library immediately cascade and sync everywhere, and how does undo/trash work?
7. How are existing local libraries initially merged when enrolling a second device with its own playlists?
8. How are identities, duplicates, playlist ordering, and different uploads of the same song handled?
9. Cloud-readable vs. end-to-end encrypted metadata; what can AI/MCP do while devices are offline?
10. Authentication, pairing, revoked devices, recovery if all devices are lost, and credential/key rotation?
11. Can backups restore across NewPipe version/schema changes or across future refactor migrations?
12. Can multiple devices use different fork versions, and what minimum compatibility is required?
13. Sync frequency, background behavior, offline queue, battery/mobile-data policy and error reporting?
14. How should optional health/pruning status and AI-suggested replacements synchronize later?
15. Actual Cloudflare cost/quotas, data retention and audit needs at 20,000+ saved streams?
16. Should desktop access initially be through an Android emulator only (no separate web playback engine)?
17. How to test and validate lossless restore / bidirectional sync without exposing or overwriting real user data?

## Possible phased approach, not committed

**Phase 1 — Restore safety:** Extend an existing NewPipe export snapshot with custom metadata, automatically encrypt/upload it to R2, retain previous snapshots, and prove recovery on an isolated device.

**Phase 2 — Cloud data model:** Define stable stream/playlist identities, operations, invariants and sync conflict semantics. Test with synthetic offline changes.

**Phase 3 — Device sync:** Worker/D1 shared store, local incremental sync, device pairing, reconciliation, and explicit rollback/recovery.

**Phase 4 — Optional cloud MCP:** Make permitted shared metadata operations available to ChatGPT, only after auth/privacy trade-offs are resolved. Does not imply cross-platform playback.

## Acceptance / safety principles

- Existing NewPipe manual import/export continues to work; do not reimplement it.
- No unrelated source, schema or media changes solely to enable backup.
- No real library deletions, resets or test migrations without verified backup and explicit authorization.
- Sync must not accidentally drop playlist memberships, duplicate streams, or resurrect deliberate Library removals.
- Restore succeeds without possession of the original phone and verifies archive integrity.
- Recoverable historical snapshots remain available even after harmful sync operations.
- Baseline NewPipe functionality and extractor updates stay independently maintainable.
- Avoid elevated privileges; ask the user before any system-level administrative action.

**Disposition: Documentation only. No Cloudflare resources, database migrations, client code, background jobs, credentials or API deployment should be created until the user explicitly chooses to implement them.**
