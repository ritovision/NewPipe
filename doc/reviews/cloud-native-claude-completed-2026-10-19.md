# Review: NewPipe Cloud M1 native read-side / sparse ordering checkpoint (01fac87)

**Verdict: REQUEST_CHANGES.** There are no P1 findings and three P2 findings.

I reviewed the frozen source by reading it only; I did not run anything, so every "would fail" below comes from reading the code. Three things limited the review:
- `CatalogOpenHelperFactory.kt` and the Room DAOs are not in the frozen tree.
- No diff against the 653adb2 baseline is available.
- The host driver for `CloudIdentityProcessDeathTest` is not in the tree.

## P1
None. Capture changes no Room or tag content. When it fails, the mapping changes roll back. Exports and imports scrub the mapping data in their staged copies. I found no deadlock between the locks and transactions that capture takes.

## P2-1: Existing legacy-backup tests are now red, so the legacy restore-sanitization path is untested
- **Where:** `app/src/androidTest/.../tagging/TagBackupIntegrationTest.kt:258-274` and `:276-291`. This file is not in the changed list.
- **How the fixture is built:**
  - It runs `snapshotTag` on the live store, which is now schema 4 and already contains `cloud_catalog_context` and `cloud_catalog_identity`.
  - It drops only the AI and bulk tables, then sets `version = 1` or `2`.
- **What happens:** `sanitizeTagCopy` opens the copy, which runs `TagStore.onUpgrade`. `TagStore.kt:45` then always calls `CloudIdentityStore.createTables`, and the plain `CREATE TABLE` at `CloudIdentityStore.kt:30-43` throws "table cloud_catalog_context already exists". Even if that passed, both tests still assert `version == 3` (`:271`, `:287`).
- **Effect:**
  - The suite would be red on this candidate.
  - The new `CloudNativeCatalogTest.kt:453-480` only opens `TagStore` directly, with the cloud tables dropped. So no passing test now covers a v1–v3 tag file going through `prepare` → `sanitizeTagCopy` (schema comparison against a fresh v4, then the scrub). That path is how real legacy backups get imported.
- **Fix:**
  - Drop the cloud tables in those fixtures and assert `TagStore.SCHEMA_VERSION`.
  - Add a v3 tag-file round trip through `prepare` and `installWithShutdown`.
- **Production impact:** low. Only a crafted pre-v4 file that already contains these tables hits the error, and it fails closed. This is a gap in test evidence and a regression in the suite.

## P2-2: Identity lifetime is tied to the tag DB only; native IDs can be silently reused if `newpipe.db` is recreated
- **The mechanism:**
  - The incarnation and the mappings live only in `ritovision-tags.db` (`CloudIdentityStore.kt:92-103`).
  - The only checks that the main database is still the same one are the Library and Unavailable playlist IDs (`:94`).
  - Reuse is keyed purely by native ID (`:107`).
  - On a fresh `newpipe.db`, those role IDs are predictable: `LibraryRepository.kt:123-148` creates Library as uid 1 and rewrites the binding, then `UnavailableRepository.kt:73-91` creates Unavailable as uid 2.
- **Scenario:**
  1. Fresh install: Library is 1, Unavailable is 2. Create playlist P (uid 3) and capture, so `PLAYLIST:3 → U`.
  2. `newpipe.db` is deleted and recreated while the tag DB and binding file survive. Room's default `onCorruption` deletes the file. I couldn't check whether `CatalogOpenHelperFactory` overrides that.
  3. On restart, a new Library (1) and Unavailable (2) are created, so the binding again reads (1, 2).
  4. The user creates an unrelated playlist Q, which also gets uid 3.
  5. `capture(I)` passes every check, and Q inherits P's portable UUID under the same incarnation.
- **Why it matters:** `cloud-sync-v1.md:36/78` requires the maps to become invalid when the database incarnation changes. This is an in-app path, not the out-of-band tampering that `:239` excludes. The later apply step would trust this mapping.
- **Fix:** store an incarnation marker in the binding file and carry it through `copy` and rebind. A recreated Library writes a fresh binding without the marker, so the change is detected. As an extra guard, record the Room `sqlite_sequence` value for `playlists` at capture and fail if it ever goes down.
- **Severity:** if `CatalogOpenHelperFactory` provably never deletes or recreates `newpipe.db` while the tag DB survives, downgrade this to optional.

## P2-3: The sparse planner wears out the tail label space on appends, forcing periodic full-sequence rewrites
- **Where:** `cloud/src/sequence.ts:61-69`.
  - The open tail gap uses `upper = END` and is split at its midpoint (`step = (END-lower)/(count+1)`).
  - Each append therefore halves the remaining tail space.
  - When the step reaches 0, it rebalances the entire sequence (`:66`, `:71-74`).
- **Why this hits normal use:** appending at the end is the main native operation; `insertMissing` and `setMarked` both use `nextJoinIndex`.
- **Reproducer (by arithmetic):**
  ```
  rows = planSequence([], 20000 unique keys).rows
  repeat: rows = planSequence(rows, [...keys, newKey]).rows
  ```
  - At about append #115 (roughly 128 − log2(n+1) appends), `rebalanced` becomes true, with about 20k removes and 20k puts.
  - That repeats about every 114 appends: roughly 350 row writes per save instead of 1.
  - A single rebalance uses about 80% of the 50k-rows/day budget in `cloud-sync-v1.md:116`. `requireSequenceBudget` would reject an ordinary save.
- **What it contradicts:** `sequence-v1.md:7` says insertion normally changes only the new occurrence. `sequence.test.mjs:66-78` checks round-trip correctness but never counts writes for repeated appends, prepends, or inserts at the same interior position.
- **Fix:**
  - Use a capped, fixed step in the head and tail gaps.
  - Rebalance only a local window when an interior gap runs out.
  - Add write-count tests for those three patterns.
- **Timing:** the label layout can still change cheaply now, before any labels are stored in production.

## Optional improvements
1. **Artwork check blocks capture:** `CloudNativeCatalog.kt:142-149` checks permanent artwork for every row, including Library and Unavailable, whose artwork isn't part of the portable catalog. A dangling permanent thumbnail (a legacy upstream `deleteOrphans` state) blocks capture without saying which playlist. Skip the check for role rows, and include the playlist ID in the error.
2. **`capture(null)` silently enrolls:** with `expectedIncarnation` omitted, `CloudNativeCatalog.kt:50` and `CloudIdentityStore.kt:95-98` mix "enroll" with "capture". After a restore this quietly creates a new incarnation. Consider a separate explicit `enroll()`.
3. **Hints are incomplete:** `CloudMediaHint` (`CloudNativeCatalog.kt:20`, `:182`) has no duration or stream type. `cloud-sync-v1.md:68` lists duration, and apply will need both to create stream rows.
4. **Process-death test never runs here:** `CloudIdentityProcessDeathTest.kt:25-26` skips all three tests unless the host driver (not in the tree) is used, so its rollback claim can't be reproduced from this source.
5. **Weak assertions:** many tests assert only `IllegalStateException`, not the error code: `CloudNativeCatalogTest.kt:238`, `247`, `254`, `260`, `269`, `316-317`, `329`.
6. **Missing tests:**
   - The pruning of an existing mapping is rolled back when a later validation fails.
   - Playlist ordering matches `MergedPlaylistManager.merge`.
   - The scale test (`:482-540`) never checks that the Room content is unchanged, despite its name.
7. **Ranks can be reused within a plan:** in `sequence.ts:76-79`, a removed rank can come back for a different key. Example: base `[A,X,B]` → desired `[A,Y,B]` gives Y the old rank of X. "Apply removes before puts" should be a documented contract, and a rank shouldn't be treated as an occurrence identity.
8. **Downgrade note:** older builds can't import v4 tag files (their version gate rejects them). Worth a release note.

## Checked and sound
- **Transactions and rollback:**
  - The lock is taken before both transactions, and nested transactions are rejected.
  - All identity writes are in one tag transaction, and they roll back if any validation fails, including the final binding re-read.
- **Identity checks:**
  - Changes to scope, binding, or incarnation are rejected before any write.
  - Orphaned identity rows are rejected.
  - Tag IDs never get reused (AUTOINCREMENT).
- **Native semantics:**
  - Playlist order is display index, then case-insensitive name with nulls last, then id. Remote playlists are excluded.
  - Duplicate occurrences are kept, and duplicate Library entries are rejected.
  - Assignments outside the Library are reported separately without being deleted.
  - The reserved "Unavailable" name check is stricter than the codec's.
- **Backups:** stock and full restores both scrub the mapping data and only touch staged copies.
- **Planner:** besides the cost issue in P2-3, the labelling is correct: labels stay strictly inside each gap, and the `remove`/`put` sets apply to exactly the planned rows.

**REQUEST_CHANGES**
