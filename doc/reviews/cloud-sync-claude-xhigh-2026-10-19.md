# Cloud & Sync v1: independent design review

**What I covered:** `CANDIDATE.md`, the three cloud-sync documents, the Library, Tagging and Unavailable specs, `tagging-production.md`, and every baseline source file in the snapshot. Everything was read-only: no edits, no commands, no network.

## What holds up

- **The seams table is accurate.** `TagRuntime.generation` only advances on restore (`TagRuntime.kt:26`, `:61-67`). The tag schema is version 3 (`TagStore.kt:14`). A full restore unpairs AI and restarts the app (`TagBackupCoordinator.kt:154`, `:190-204`; `CatalogTransferModel.kt:103`). The lock order matches `AiCatalogExecutor.kt:14`.
- **Native ID mapping is viable as a gated prototype.** Every `playlists` insert in the snapshot runs under TagRuntime:
  - `LibraryRepository.kt:131-138`
  - `UnavailableRepository.kt:80-87`
  - `LocalPlaylistManager.java:63-66`
  - `AiCatalogStore.kt:107`
  - `TagRepository.kt:241`

  Room's `autoGenerate` uses AUTOINCREMENT, so IDs are never reused. Native uids pre-assigned in the durable journal would therefore give an unambiguous mapping.
- **Room-side stale writes are already rejected.** `PlaylistEditGuard.verifyUnchanged` (`LocalPlaylistManager.java:164-166`) and the AI revision digests (`AiCatalogStore.kt:457-464`) both refuse UI/AI writes that predate a sync apply.
- **The conservative whole-catalog conflict policy fits this project.** I am not asking for a merge engine. Pausing on any conflict, plus a CAS on the exact base, is a sound replacement for the tombstone requirement in `library-playlist-system.md:88`.
- **The D1 serialization primitive is sound** once finding 1 is fixed. D1 batch atomicity plus an in-batch guard works.
- **The R2 three-slot count/byte cap is enforceable.** Reservations serialized in D1, confirmed deletes, and lifecycle used only as a backstop all hold up. What is overstated is the recovery depth (finding 9).

## Findings

### 1. SUBSTANTIVE: the CHECK guard passes silently on NULL or no matching row, and retries can be misreported

**Refs:** arch `:59`, `:83`; plan `:50`.

**Problem 1, silent pass.** SQLite treats a CHECK expression that evaluates to NULL as satisfied. Two natural ways to write the guard both fail open:
- `VALUES((SELECT revision=? AND … FROM catalog_head/devices WHERE …))` yields NULL when the head or device row is missing, or when any compared column is NULL.
- `INSERT … SELECT … WHERE <expected>` inserts zero rows on a mismatch, so nothing raises at all.

*Scenario:* a revoked device whose row has a NULL auth generation submits a batch. The guard passes and every later statement writes.

**Problem 2, misreported retries.** If the guard runs before the receipt insert, a concurrent duplicate of an operation that just committed fails on the guard (the head has moved). It gets a definitive "conflict" instead of its receipt, and the client enters CONFLICT over its own committed write.

**Minimum change:**
- Declare `ok INTEGER NOT NULL CHECK(ok = 1)` and compute it as `(SELECT COUNT(*) … WHERE <every predicate>)`.
- After *any* batch failure, look up the receipt for (device, sequence) and return it if the digest matches.
- Add negative tests on D1 for a missing head, a missing or revoked device, and NULL columns.

### 2. SUBSTANTIVE: there is no path for large ordinary deltas, and whole-sequence storage multiplies writes

**Refs:** arch `:37`, `:91`, `:93`, `:95`; spec `:99`.

Arch `:91` defines only two large-operation paths: a single-playlist sequence rewrite, and a whole-library replacement, which requires an R2 archive. Ordinary commits are capped at 32 statements and 256 KiB chunks (`:93`).

*Scenario:* the owner runs Remove All Everywhere on 3,000 Unavailable items (`TagRepository.kt:149-176`; AI allows 5,000, `AiCatalogStore.kt:355`). The diff covers 3,000 global removals plus every playlist occurrence, Unavailable membership and tag assignment. It spans many playlists and exceeds one chunk. The only defined path that fits is whole replacement, which means:
- an R2 archive for an ordinary edit, contradicting spec `:99`;
- a new epoch, which pauses every other dirty device.

**Write amplification.** Arch `:37` versions a sequence as a whole and `:95` requires dense positions. Native global removal deletes join rows without renumbering (`LibraryRepository.kt:361-365`). If D1 stores one row per occurrence at a dense position, removing one item near the top of a 20k Library rewrites about 20k position rows plus index rows. Whole-sequence deltas would also re-send the entire Library on every save.

**Minimum change:**
- One generic sealed staged delta: chunks → seal → validate against the fenced base → one guarded batch that applies set-based `INSERT…SELECT`/`DELETE` from the staged rows, writes the receipt and increments the head. Use it whenever a delta exceeds the ordinary budget.
- This also covers large playlist rewrites, so the separate per-playlist content-version pointer can be dropped (a simplification).
- Express playlist changes as edit scripts against the fenced base content, not whole sequences.
- Use sparse or fractional sort keys in D1 so one insert or removal doesn't renumber a playlist.

### 3. SUBSTANTIVE: the account plan tier and provider budgets are unstated, but they decide the physical design

**Refs:** arch `:189` (says only "Free/Paid query budgets differ"); plan `:35`, `:52`, `:108`; `LibraryRepository.kt:88` (code comment referring to a ~20k-entry Library).

From my knowledge (recheck before relying on these):
- D1 on Workers Free allows about 100k rows written and 5M rows read per day, account-wide.
- Index updates count as additional rows written.
- Once a cap is hit, queries fail until 00:00 UTC.
- Workers Free allows 10 ms of CPU per invocation.

*Scenario:* the first device initializes the cloud with a ~20k Library. With one row per entity, streams plus Library membership and their indexes come to roughly 80k rows written before any playlist or tag. The 100k-membership scale fixture is roughly 300k. On Free, staging hits the cap partway through, and for the rest of the UTC day every D1 query fails, including auth reads. A whole replacement roughly doubles the writes (stage the new generation, retire the old one).

**CPU.** On Free, none of these fit in 10 ms, yet the design leans on all of them:
- server-side checksum verification of a 256 MiB archive;
- hashing 8 MiB multipart parts;
- having the Worker serialize the outgoing catalog into a cloud-catalog archive (arch `:155`, `:171`).

This is not a reason to drop D1. It is the fork between one row per entity with SQL foreign keys (arch `:61`, fine on Paid) and chunked rows with Worker-side validation (needed on Free).

**Minimum change:**
- Before the D1 skeleton, record the account's plan as a design input: either the owner explicitly approves Workers Paid (plan `:35` forbids enrolling it incidentally), or the design targets Free.
- Add per-operation budgets (rows read/written, CPU ms, queries per invocation) to the single limits contract (plan `:52`).
- Give them pass/fail thresholds measured on the deployed synthetic D1, not the local emulator, which doesn't enforce them.

### 4. SUBSTANTIVE: the canonical projection is undefined for derived, device-local and history-dependent native fields

**Refs:** spec `:19`; arch `:39`, `:67`, `:125`.

Each item below causes either a post-apply verification failure (`:125`) or a phantom "local change" at the next startup comparison (`:67`). A phantom change gets pushed back as an echo and opens needless conflict windows.

**(a) Automatic thumbnails depend on history.** `refreshAutomaticThumbnail` only replaces the thumbnail once the current one has left the playlist (`LibraryRepository.kt:436-452`). Two devices with identical sequences can hold different values.
- Rule: project the thumbnail only when `is_thumbnail_permanent = 1`, and call the refresh after apply.

**(b) Display order.** New playlists get `display_index = -1` and are renumbered later by BookmarkFragment (`LocalPlaylistManager.java:51-53`, `:199-205`; AI creates also use -1, `AiCatalogStore.kt:108`). If raw `display_index` is synced, each device renumbers after an apply, then pushes, then the other device renumbers, indefinitely.
- Rule: sync rank among synchronized playlists only; renumbering that preserves rank is not a change.

**(c) Library order is undecided.** The spec syncs "membership" and D1 splits out `library_members` (arch `:51`). Natively, though, Library is ordered and user-reorderable (`LibraryRepository.kt:310-335`), and "Library order" is a user-facing Tags sort (`TagRepository.kt:193-195`; `tagging-production.md:18`).
- Decide: either synced (using finding 2's sparse keys) or explicitly excluded from the fingerprint, with a deterministic apply order and the divergence documented.

**(d) Tag-store concurrency tokens.** The TagStore mutators bump `stream_revision` and `catalog_meta.revision` (`TagStore.kt:57`, `:79-82`, `:105-121`, `:137-149`). Editors and AI check them (`TagStore.kt:99-103`; `AiTagExecutor.kt:73`, `:213`).

*Scenario:* a bulk sync apply via raw SQL skips the bumps. An editor opened before the apply then passes `verifyEdit` and overwrites the sync-applied tags. That stale result is pushed back to the cloud as a fresh local edit, a lost update across devices.

**(e) Existing stream rows.** Apply must not overwrite existing stream metadata (`AiCatalogStore.kt:71-72`, `TagRepository.kt:101-103`).

**Minimum change:** add a per-field projection table to the architecture (included or not, normalization, apply procedure) with golden vectors. State that sync apply calls the native mutators, or reproduces their side effects and token bumps.

### 5. SUBSTANTIVE: orphaned tag assignments are a legitimate native state with no sync rule

**Refs:** arch `:53`, `:61`; `TagStore.kt:27`; `TagRepository.kt:187-189`; `TagBackupCoordinator.kt:182-186`; `tagging-production.md:43`.

After a stock-backup import, the fork deliberately keeps assignments for streams that are not in Library and counts them as `orphanedAssignments`. Assignments are keyed by `(service_id, url)` with no Library foreign key. D1 requires Library-only assignments, and the design says nothing about orphans.

*Scenario A (stale tags resurface):* device B holds orphaned tags for video X, and device A re-saves X. When B applies "add X", X immediately shows B's stale tags, because the native re-save path doesn't touch tags (`LocalPlaylistManager.java:78-79`). B's state now differs from the target, so either verification fails (APPLY_RECOVERY_REQUIRED) or B pushes the stale tags to the cloud as a new edit.

*Scenario B (initialize/replace from B):* including orphans fails D1's foreign-key checks. Silently dropping them contradicts the native rule against purging recoverable metadata (`TagRepository.kt:187-188`).

**Minimum change:**
- Project assignments only for Library members, matching D1.
- When sync adds a Library member, set that key's assignments to exactly the cloud set, purging local orphans in the tag phase.
- Disclose the purge count in previews of destructive replacements.

### 6. SUBSTANTIVE: protected-role identity lives in a third store, and the cloud doesn't enforce the reserved names

**Refs:** arch `:24`, `:95`, `:115`, `:123-124`; `LibraryBindingStore.kt:13-25`; `UnavailableRepository.kt:74-91`.

The design treats native apply as two stores. The Library and Unavailable identities actually live in a third file, `ritovision-library.binding`, written outside both database transactions. `ensure()` inserts the Room row and only then writes the binding. A crash between the two leaves an unbound "Unavailable" playlist, which `ensure()` refuses from then on (`:76-79`) until a manual rebind.

*Scenario 1:* on device B, `ensure()` failed at startup because a legacy playlist has the same name; `LibraryRepository.kt:164-166` swallows that failure. The cloud carries Unavailable members. Apply steps 3–4 have no binding phase, so recovery matches neither the old nor the target state and fails closed. If an implementation instead creates the role inside apply, it inherits the crash window.

*Scenario 2:* the cloud carries an ordinary playlist named "unavailable", because D1 validates only protected designations (`:95`). Applying it to a device whose Unavailable isn't bound yet makes `ensure()` fail permanently. AI marking then returns `UNAVAILABLE_NOT_READY` (`AiCatalogExecutor.kt:83-89`).

**Minimum change:**
- Admit sync only when the Library binding is verified and `ensure()` succeeds. Add a distinct blocked state (also covering `IdentityRepairRequiredException`, which still permits ordinary edits, `TagRepository.kt:37-45`) and repair through the existing rebind flows.
- The journaled apply never creates or rebinds roles.
- The codec and D1 reserve the trimmed, case-insensitive name "Unavailable" for ordinary playlists, matching `LocalPlaylistManager.java:56-61`, `AiCatalogStore.kt:103/116` and `LibraryRepository.kt:478`.

### 7. SUBSTANTIVE: there is no way to retire an abandoned operation sequence

**Refs:** arch `:75`, `:87`, `:113`; spec `:45`, `:51`.

The rules as written:
- the server admits one active operation per device and never treats a skipped sequence as success (`:87`);
- the client never re-issues an operation under a new identity (`:75`);
- quarantined work is never replayed (spec `:45`).

*Scenario:* B sends operation n against base r, the response times out, and the owner disables sync. On re-enable, B is stuck. It can't resend n because it is quarantined, and it can't send n+1 because that skips a sequence.

If an implementation instead "abandons" n after a receipt lookup returns nothing, a delayed copy of n can still commit against base r once B re-baselines on r. The copy might be a request still executing, or one OkHttp transparently re-sent on a stale pooled connection. The edit the owner deliberately abandoned then lands in the cloud and flows back to B.

**Minimum change:** a server call, resolve-or-retire(sequence), run as one guarded batch. It returns the receipt if the operation committed; otherwise it advances the device's high-water mark so that sequence can never commit. Require it before re-enable, before any destructive preview (Use cloud once, Replace, enrollment), and before discarding a quarantined operation.

### 8. SUBSTANTIVE: rollback safety depends entirely on the operator rotating `service_generation`

**Refs:** arch `:42`, `:69-75`, `:145`, `:183`; spec `:68`.

Epochs are random, and "cloud revision below my baseline" is not a defined case in the sync table.

*Scenario:* D1 is restored to two hours ago, either by running Time Travel without the rotation step or by restoring the wrong environment. Clean device A, with baseline r=120, sees head r=100. It classifies this as "cloud changed" and applies it, deleting two hours of its own edits locally. A restore to an older epoch instead looks like a whole-cloud replacement, which clean devices adopt automatically (`:145`).

**Minimum change:**
- Add a head counter that never decreases, including across epochs.
- If it regresses without a `service_generation` change, clients enter a rollback-suspected state and stop automatic work.
- Store the enrolling generation per device and require it to match, so rotating `service_generation` invalidates tokens by construction rather than by runbook.

### 9. SUBSTANTIVE: Replace-cloud retries contradict the preview contract and churn the three slots; the recovery guarantee is overstated

**Refs:** arch `:155`, `:171-173`; spec `:91`, `:93`, `:101-105`, `:111`.

**Contradiction.** Arch `:173` says that if the cloud changes during archival, the flow should "refresh/retry against the new head". Spec `:91` says a detected change requires a refreshed preview. If the retry publishes without a new preview, B deletes items A added during archival, which B's owner never saw.

**Slot churn.**
- Slots hold [device ZIP from Jan, catalog from Feb, catalog from Mar].
- Attempt 1 deletes the Jan ZIP and archives r100. The CAS fails because A synced r101.
- Attempt 2 deletes the Feb catalog and archives r101. The "two newer remain" rule is satisfied each time.
- Two retries later, the only device ZIP and the older catalog recovery points are gone, and the slots hold near-identical copies of the current cloud.

**Exhaustion.** Three consecutive replacements, whether from a buggy retry loop or a stolen token with the `replace` grant, rotate out the archive taken before the first replacement. The real guarantee is "the state before each of the last two replacements".

**Unspecified producer.** Nothing says who produces the outgoing cloud-catalog archive, or how its bytes are verified as equal to head h. Finding 3 rules out heavy Worker-side work on Free.

**Minimum change:**
- Any head change after approval invalidates the approval and requires a new preview.
- An archive whose replacement never published is not a recovery point; the retry deletes it and reuses that slot.
- Rate-limit `replace` (for example, one per 24 hours without operator action).
- Name the archive that rotation will delete in the confirmation.
- State the two-replacement recovery depth honestly.
- Specify the producer. Suggestion: the replacing device archives the exact page bytes it downloaded for the preview, with Worker-issued per-page digests (or MACs) bound to head h.

## OPTIONAL

- **Recovery evidence.** Prefer the existing same-database receipt pattern (`rv_ai_command_receipts` lives in Room, `TagBackupCoordinator.kt:302`; `TagRepository.kt:51-57`), or pre-assigned uids in the journal. Projection matching alone can't attribute a newly inserted, unmapped playlist after a crash between the Room and tag commits.
- **Wording that can't be met.** "No partial catalog exposed" (plan `:94`) and "UI invalidation only after coherent completion" (arch `:125`) are impossible for Room Flowables (`LocalPlaylistManager.java:209-232`), which Room refreshes at commit. Restate it as "the Room phase is invariant-valid on its own", which is already the native contract (`TagRepository.kt:107`).
- **Disable vs. an in-progress apply.** Have disable bump the generation under `TagRuntime.lock`. Otherwise the gap between the generation check and the Room commit lets an apply finish after disable (plan `:84`).
- **False conflicts.** If the downloaded cloud catalog equals the local fingerprint, adopt it as the baseline rather than raising CONFLICT. This covers the case after receipt expiry (`:87`).
- **Simplification.** With one active operation per device, the sequence number alone identifies the operation; the UUID is redundant.
- **Multipart.** Always upload archives as multipart, so `abort()` definitively fences an upload of unknown outcome (`:163`).
- **Download liveness.** Restarting the download whenever the revision changes (`:101`) can starve enrollment while another device keeps editing. A snapshot plus journal catch-up is bounded.
- **Local snapshot overwrite.** When a clean device automatically adopts a new epoch, don't overwrite the single local recovery snapshot (`:137`) if the outgoing state is already archived in R2. Otherwise an earlier "Use cloud" snapshot is silently lost.
- **One-time actions while disabled.** Persist the UUID maps, so a later Enable doesn't preview deleting and recreating every playlist.
- **Default SharedPreferences.** Don't store the Sync toggle or other cloud state there. Export dumps `preferences.all` (`TagBackupCoordinator.kt:221`), and restore runs `clear()` and then reapplies the backup (`:139`).
- **Change capture.** Don't use SQLite triggers. Native backup validation rejects any schema object that isn't a table or index (`TagBackupCoordinator.kt:382-388`, `:435`).

## Verdict

**APPROVE_FOR_M1_PROTOTYPE**

Nothing here makes the approved direction unsound: D1 as the live catalog, minimal R2 recovery, and the conservative conflict model all stand. Each finding needs a bounded amendment to the documents. Under the spec's own rule (`:15`), amend before coding the affected M1 piece:

| M1 piece | Findings to resolve first |
| --- | --- |
| Codec and golden vectors | 4, 5, 6 (reserved names) |
| D1 guard and receipts | 1, 7, 8 |
| Large-payload proof and physical schema | 2, 3 (needs the owner's plan-tier decision) |
| Native apply prototype | 6 (preconditions) |
| M2 R2 work | 9 |

## Unverified assumptions

- **BookmarkFragment** (not in the snapshot): I assumed it renumbers `display_index`. If, as in upstream, it also interleaves non-synced remote bookmarks in the same index space, raw values differ per device by construction.
- **`TagNames` normalization** isn't in the snapshot, so I couldn't check Kotlin/TypeScript key parity.
- **Cloudflare limits** come from my own knowledge, not rechecked: D1 Free daily row caps and index-row accounting, Workers Free 10 ms CPU, R2 multipart part-size rules and checksum support.
- **The rollout account's plan tier** is unknown.
- **Library size:** about 20k items, inferred from `LibraryRepository.kt:88` and the scale fixture. Actual membership and tag counts are unknown.
- **Unique stream index:** the upstream `(service_id, url)` unique index on `streams` is inferred, not seen.
- **Writer coverage:** I could only audit the writers present in the snapshot.
