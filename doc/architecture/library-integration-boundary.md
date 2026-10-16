# Library Integration Boundary — RitoVision NewPipe Fork

**Current polish follow-up:** See [Library polish](library-polish.md) for the
latest fixes, eight-file upstream boundary, 176 JVM checks and final review gate.
Runtime revision 4881fc69c resolves the six reported polish findings. Astra's
final re-review passed; Claude's final retry was blocked by its session allowance.
Historical v1 notes below are retained as an audit trail, not current open issues.


**FINAL STATUS (2026-10-16): LIBRARY V1 COMPLETE.** Code milestone
577bf2f77 is merged into custom/dev and installed on the user's separate
custom NewPipe Android app. The user reports the feature looks good.
The final independent Claude Opus Max review found **no Critical or High
issues** and returned **PASS WITH NONBLOCKERS**.

**Release verification:** JVM tests 163 passed, 0 failed; builds passed.
The original 10 Android integration tests were executed successfully on
a separate emulator during development. The 12 later Android instrumentation
tests, including the five global-deletion cases, were compiled but deliberately
not run at the user's request. The user tested the installed app personally.

**Behavioral scope:** Saving to ordinary playlists auto-saves to Library;
direct Library saves deduplicate; ordinary-playlist removal is local;
Delete from Library removes every occurrence of that item across all
local playlists in one Room transaction after confirmation. The master
Library playlist itself remains present. Global removal is restricted to
a verified Library ID; restoring an unmatched import requires repair.

**Accepted nonblocking limitations for v1:**
- An ordinary playlist also named "Library" is confused with the canonical
  Library in some menus and can become awkward to rename or delete. The
  database operation refuses a wrong-ID global deletion. Avoid naming a
  separate ordinary playlist "Library" until a later UI cleanup.
- If a SQLite COMMIT fails after the transient removed-item marker was added,
  that marker might affect ordinary playlist edits until the process restarts.
  This is a rare disk/I/O failure edge.
- A playlist whose thumbnail was removed globally may show a placeholder
  instead of automatically choosing the next video.
- Failed global removals may show a full-screen error instead of a snackbar.
- An explicit re-add can clear the deletion marker; stale in-memory screens
  could then reintroduce the stream to that particular playlist. This does
  not reintroduce a stream that has not been intentionally re-added.
- Cross-database tag cleanup and multi-device sync tombstones belong to
  their future Tags and Cloud Sync milestones.
- The sidecar Library ID/name is not cryptographically tied to a database.
  The current design fails closed on ambiguous restores and offers manual
  repair, adequate for this personal fork.

The independent review and these accepted issues are preserved in
[Claude's final Library review](../reviews/library-final-claude-opus-max-2026-10-16.md).
No further Library feature work is required before starting Tags.

---


## Current milestone — interactive global Library removal (2026-10-16)

**Status:** Library item deletion is enabled in this revision. The earlier
add-only descriptions below document the previous implementation and tests.

- **Delete from an ordinary playlist:** removes only that playlist membership.
- **Long-press an item inside Library → Delete → Remove everywhere:** removes
  every occurrence of the item's database stream identity from **all local
  playlists**, including Library, in one Room transaction.
- When a deleted stream was used as a playlist thumbnail, that thumbnail
  reference is reset. Stream history and subscriptions are not deleted.
- Global deletion is authorized against a **verified bound Library**, never a
  playlist chosen just because its display name matches.
- The session tracks recently deleted IDs so stale, in-process ordinary
  playlist editors cannot quietly restore a globally removed item through a
  delayed reorder/save. An explicit new Add to playlist can save it again.
- Repeated global removal is idempotent. Duplicate occurrences in normal
  playlists are removed together.
- The special Library *playlist object* is still retained, so its membership
  and organization rules remain usable. Global removal of individual entries
  is now available; arbitrary deleting/renaming of the master playlist,
  Remove Watched and automatic bulk pruning are distinct operations.
- **Validation gate:** successful Gradle assembleDebug,
  assembleDebugAndroidTest, code style checks and 163 existing JVM tests.
  Five additional Room instrumentation regression cases were compiled but
  **not run**, per the user's request to test the new behavior personally on
  the Pixel 7 rather than on an emulator. No physical device data was wiped.
- **Integration boundary:** The incremental global-deletion change touched
  LocalPlaylistFragment and LocalPlaylistManager. Across the complete Library
  feature, five upstream-owned source files are patched: MainActivity,
  BookmarkFragment, ImportExportManager, LocalPlaylistFragment and
  LocalPlaylistManager. SQL and removal-marker logic live in our custom
  LibraryRepository.

---


**HISTORICAL STATUS:** Original add-only feature-branch notes, superseded by the final Library v1 section above.
**Branch:** `feature/library` (base: `custom/dev`)
**Product requirements:** [Library playlist system](../specs/library-playlist-system.md)
**External review:** [Claude Opus xhigh](../reviews/library-tags-claude-opus-xhigh-2026-10-15.md) — unadjudicated; we use its risk observations but have not ratified its complete implementation.
**Main constraint:** Rebase/merge upcoming NewPipe `dev` changes with a minimal number of upstream patch seams. Do not fork NewPipeExtractor or modify its Room migrations.

## What we own

```text
app/src/main/java/org/schabi/newpipe/custom/library/
  LibraryRepository.kt     # canonical membership, initialization, backfill,
                           # idempotent append, safe non-destructive reorder
  LibraryBindingStore.kt   # separate atomic sidecar paired with database backups

app/src/androidTest/java/org/schabi/newpipe/custom/library/
  LibraryRepositoryTest.kt # actual Room in-memory integration tests

doc/architecture/
  library-integration-boundary.md
```

The feature intentionally uses `:app`'s existing Room database and playlist entity types. A separate Gradle project/repository or generic extension system would require more integration surface and would not solve upstream API churn. The feature does not alter `AppDatabase`, `Migrations`, `NewPipeExtractor`, playback services, or the original history/subscription tables.

## Precisely what upstream code changes

| Existing file | Why the patch is necessary | Explicitly excluded |
| --- | --- | --- |
| `local/playlist/LocalPlaylistManager.java` | A narrow hook after ordinary join writes, safe Library append/reorder path, move next-index calculation into Room transaction, prohibit canonical Library deletion/rename | No new Room schema, no extractor code |
| `MainActivity.java` | Start background initialization once the app UI is created | No blocking DB writes on main thread |
| `local/playlist/LocalPlaylistFragment.java` | No rename/remove-watched/remove-duplicates/delete-item actions for Library during this milestone | No change to ordinary playlist removal |
| `local/bookmark/BookmarkFragment.java` | Hide Library rename/delete and prevent its ordinary deletion path | Ordinary playlists remain reorderable/deletable |
| `settings/export/ImportExportManager.kt` | Export/import the sidecar binding with the original DB, and invalidate old binding before DB replacement | No rewrite of existing NewPipe export or settings formats |

## Original add-only invariants (historical)

1. Any stream added to an ordinary playlist is also inserted once into Library **within the same Room write transaction** after Library initialization.
2. Existing ordinary playlists preserve their independent membership and order, including intentional duplicates.
3. A new Library starts as an empty, ordinary NewPipe local playlist; the upstream empty-playlist creation API is unchanged.
4. Initialization backfills the union of existing playlist stream identities and is idempotent. Add hooks plus an extra backfill address the bootstrap timing window.
5. Saving directly to Library is idempotent. Reordering Library does not implicitly delete members; stale editors cannot erase saved-only entries.
6. Library deletion, including watched/duplicate bulk removal, is **blocked** until an explicit, independently tested global-removal transaction and confirmation UX exist.
7. The Library is bound by its stored playlist ID and validated name, **not discovered by name alone**. A mismatched identity is fail-closed. This is not a cryptographic identity guarantee.
8. The sidecar identity lives at `databases/ritovision-library.binding`. It is included in custom ZIP backups, removed when an imported stock backup lacks one, and never imported through NewPipe's default preferences. In an ambiguous restore containing an unbound playlist called Library, the app requires later deliberate repair instead of silently adopting it.
9. No remote/cloud/multi-device write path is activated; eventual AI, Tags, pruning and sync will use the same Library repository.

## Original add-only backlog (historical, superseded where completed)

- **Global remove everywhere**: Needs explicit affected-playlist preview/confirmation, a single atomic transaction, thumbnail handling, cross-database tag cleanup journal, and protections against stale UI/editor writes. Not in initial APK.
- **Recovery UI**: An imported stock database with an existing unbound playlist named Library intentionally fails closed; provide a safe rebind/create-new workflow before broadly shipping this.
- **Long-term identity**: A playlist ID plus name is insufficient proof in arbitrary mismatched file restores; custom backup files are paired, but any fallback must stay non-destructive. Assess a stronger marker/repair protocol before enabling cascading deletes.
- **Import lifecycle**: NewPipe's own native restore replaces an on-disk DB then restarts. We suspend custom hooks; upstream's existing open-DB restore behavior deserves regression tests.
- **Cross-process sync**: Future cloud edits need tombstones, idempotency and conflict resolution and must not rely on observing missing entries.
- **Static package identity**: The upstream debug build derives `applicationIdSuffix` from the Git branch name. Feature branches therefore produce different throwaway APK identities. After integration, build `custom/dev` to retain the tested `org.schabi.newpipe.debug.customdev` package. A dedicated immutable application ID and release signing key should be established before adopting the fork as the user's primary app.

## Original validation plan (historical)

- Run `:app:assembleDebug` and `:app:assembleDebugAndroidTest` on @NotCodex-2 (build-only).
- Run focused `LibraryRepositoryTest` instrumented tests on a throwaway @NotCodex emulator, not a live user database. Explicitly test duplicate preservation, stale editor writes, direct Library saves, mismatch/restore rejection and idempotent backfill.
- Validate UI paths with synthetic playlists: Library visible/empty, normal Add to playlist, ordinary removal, disabled Library delete/rename/bulk actions.
- Inspect APK package + signer before physical installation. Do not uninstall, replace, clear or import data from the user's F-Droid NewPipe.
- Only after this first milestone passes: review/implement explicit global deletion as a separate commit with separate tests.

No automatic F-Droid application updates or data migrations are performed as part of this work.


## Verified test evidence — 2026-10-15

**Verification status:** Initial add-only Library milestone has passed build, existing JVM regression tests, targeted Android instrumentation tests, and a manual UI workflow on a disposable emulator. This is **not** acceptance of global Library deletion or broad restore safety.

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | PASS |
| `:app:assembleDebugAndroidTest` | PASS |
| `:app:testDebugUnitTest` | PASS, 155 tests |
| `LibraryRepositoryTest` (ADB instrumentation) | PASS, 10 / 10 tests |
| App boot / automatic Library initialization | PASS, sidecar identity created and Library visible |
| Add YouTube video to newly created ordinary playlist | PASS, one entry in ordinary playlist and one in Library |
| Remove video from ordinary playlist | PASS, ordinary playlist becomes empty; Library retains one entry |
| Library stream long-press menu | PASS, Delete action is absent |

**Environment:** app/test APKs built only on @NotCodex-2, run on primary @NotCodex Windows 10 headless Pixel 3a Android 14 (API 34), device `emulator-5574`, Windows ADB server port 5038. The specific isolated UI smoke test used a public Rick Astley YouTube video and a synthetic local playlist. No test used a physical phone, an F-Droid application database, or a destructive Library operation.

**APK identity during feature-branch testing:** `org.schabi.newpipe.debug.featurelibrary` (NewPipe's automatic branch-based debug suffix). Do not install this as the long-lived custom package; the `custom/dev` branch emits `org.schabi.newpipe.debug.customdev`. Preserve and pin a stable signing identity before production deployment.

**Known limitations:** The global remove-everywhere operation is intentionally absent. Bound-ID collision hardening, paired custom-backup restore roundtrips, startup migration failure recovery UI, concurrency stress, and emulator UI behavior at larger scale remain future gates. NewPipe's existing import tests are green, but that does not certify the new binding's full backup/restore lifecycle. Software-only `-gpu off` produced a transient emulator System UI ANR before recovering; it did not affect the targeted database tests.

**Engineering decision:** Keep this milestone on `feature/library` for review; do not merge into `custom/dev` or install on the user's Pixel 7 yet.
## Post-review hardening — 2026-10-15

After Claude Opus Max reviewed commit `f245e5497`, the following changes were implemented on `feature/library` before preparing the physical-device build:

| Review issue | Resolution in this revision | Remaining limitation |
| --- | --- | --- |
| Missing in-memory Library session allows destructive playlist rewrites or duplicates | Conservative name-based protection on the Room mutation path when unbound; no adoption/global deletion from name; repeat initialization retains valid session; single backfill publishes identity inside the write transaction | Cold-start behavior is covered by compiled Android regression tests but was intentionally not executed on an emulator at the user's request |
| Unbound Library has no recovery path | Bookmarked Playlists long-press offers an explicit **Use as Library** repair action only when unbound; rebind preserves contents and backfills additively. Mismatched saved binding with no named Library automatically creates a new protected Library and retains all previous playlists | Library names remain special; a future dedicated recovery UI should address multiple same-named playlists |
| Backup restore can remove binding before DB replacement | ZIP is read once and staged; SQLite header and Library binding validated before replacement; existing DB/binding/WAL siblings copied for exception rollback; binding is replaced only after DB move; temporary files cleaned | Multi-file restore is NOT crash-atomic. Process death during a swap can require explicit identity rebind; global remove-everywhere remains disabled |
| Unnecessary repeated large backfills | Initialization returns immediately for an already active DB session; first run backfills only once; small additions use per-stream membership probes | Large-list performance under 20k+ streams is not yet benchmarked |
| Obsolete add-on-update hook | Removed from ordinary `updateJoin`; only playlist append/create may auto-add to Library | Stale ordinary playlist edits remain an existing NewPipe behavior; direct global removal remains deferred |

### Build/test release gate

- `:app:testDebugUnitTest`: PASS — **163/163** JVM tests, including **8 new** `LibraryBackupRestoreTest` cases.
- `:app:assembleDebug` and Android instrumentation-test APK compilation: required before physical-device installation.
- Added further instrumentation regression cases for unbound/cold-start Library protection, additive backfill, duplicate handling, explicit rebind and metadata name protection. **No emulator tests run**, per user's explicit instruction.
- Physical installation is authorized **only** to the verified Pixel 7 reached through Tailscale ADB. Never uninstall or clear data of the installed F-Droid app.
- This is still the **add-only Library milestone**, not a complete global-removal feature or Tags system. The user will test actual playlist interactions manually.

### Important risk boundary

The saved playlist ID plus display name is not a cryptographically strong identity and cannot prove two separately imported databases correspond to the same Library. Until a stronger binding/migration scheme and cross-database journal are implemented and reviewed, do **not** expose cascading Library deletion or AI-controlled bulk deletion.
