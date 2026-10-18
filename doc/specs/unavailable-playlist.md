# Unavailable Playlist — Manual Marking and Global Cleanup

**Status:** Implemented on the feature branch; final correction and test evidence are recorded in [post-review validation](../validation/unavailable-20261018/POST_CLAUDE_FIXES.md). Not yet merged or deployed.
**Date:** 2026-10-17
**Base:** `custom/dev`, after accepted Library, Tagging and AI Control integration (`a6d448d93` at scoping).
**Related:** [Library](library-playlist-system.md) · [Tagging](tagging-layer.md) · [AI Control](ai-control-v1.md).
**Supersedes as the next feature:** the separate [Pruning / Library Health exploration](../potential-features/pruning-library-health.md). Automated scanning remains deferred, not part of this feature.

## Goal and mental model

Give the owner a manual **Unavailable** list for saved songs and videos they encounter that no longer play. The list is an ordinary *membership relationship* with special handling, **not** a second media catalog, download, inferred playback status, tag, or automatic deletion queue.

- **Library** remains the canonical saved-stream set.
- **Unavailable** is a protected, visible *subset of Library*, implemented with NewPipe's existing local playlist rows and memberships. The same source stream may simultaneously appear in Unavailable and any number of ordinary playlists.
- **Mark Unavailable** means “I want this on my review list”; it does **not** claim a scanner proved that the source was removed.
- **Mark Available** only removes that manual marking; it does **not** check the provider or restore a globally deleted item.
- AI can read the list, help locate alternative uploads, and use existing playlist/Library operations when requested. No replacement finder, automatic replacement, or transfer of playlist positions is built here.

The initial use case is a video with an inaccessible or terminated uploader account that remains in several personal playlists. The owner marks it, asks AI to inspect Unavailable and find alternatives, and may later globally remove the original.

## In scope: native UX

### Special playlist lifecycle

1. Ensure one local playlist with the display name **Unavailable** exists, even if empty, and display it alongside ordinary saved playlists.
2. Give it a stable, **verified playlist identity**; never choose a playlist for special behavior based solely on the name `Unavailable`, a stale integer ID, or an arbitrary imported row.
3. Preserve its membership and identity through normal process restarts and the **existing** supported NewPipe import/export and recovery paths. An older backup without an Unavailable designation must be handled safely without losing existing playlists.
4. Prevent renaming or deleting the **playlist object** through both native and AI ordinary-playlist commands; changing its membership, playing it, inspecting it, and (optionally) reordering it remain allowed.
5. If a user-created playlist is already named Unavailable, do not automatically adopt, overwrite, delete, or repurpose it. Fail safely and offer an explicit resolution/designation path. Do not create an ambiguous second protected identity.
6. Normal Library behavior still applies: saving into Unavailable ensures Library membership; removing only an Unavailable membership never removes the stream from Library or another playlist.

### Long-press actions on a saved stream

Available from **Library, ordinary local playlists, and Unavailable**:

| Menu action | Effect | Confirmation |
| --- | --- | --- |
| **Mark Unavailable** (currently unmarked) | Add exactly one membership to the protected Unavailable playlist; leave all other memberships/tags alone | **None** |
| **Mark Available** (currently marked) | Remove the Unavailable membership only; leave Library, other playlists, and tags alone | **None** |
| **Remove Everywhere** | Delete the stream's memberships from Library, Unavailable, and **every** local playlist, including all duplicate occurrences; coordinate tag cleanup | **Required** |
| **Remove from This Playlist** (existing ordinary-playlist action) | Preserve existing local-only behavior outside Library. In Unavailable, interpret as **Mark Available** | **Required** for local removal; Mark Available remains confirmation-free |

- Show exactly one of **Mark Unavailable** and **Mark Available**, determined from authoritative current membership; never show both as independent options. A stale menu must recheck at commit.
- Keep the existing Library global-delete semantics and confirmation; avoid two ambiguous “Delete” entries with different effects.
- No network request, video extraction, thumbnail test, rate limiting, or automatic mark change is triggered by these actions.
- Mark/unmark operations are idempotent, including rapid repeated taps, retries, and concurrent UI/AI writes.

### Unavailable playlist overflow (⋮) menu

**Clear Unavailable** — remove all markings but keep every stream in Library, ordinary playlists, and tags. Confirmation must show the **unique item count** and say explicitly that other playlists are unaffected. Playlist remains present and empty.

**Remove All Everywhere** — globally delete all currently marked unique streams, including all their occurrences in Library and other local playlists, and clean up stream-tag assignments. Confirmation must show the **unique item count**, explicitly state the cross-playlist effect, and warn that there is no automatic undo. The protected Unavailable playlist itself remains present and empty.

- **Both bulk actions require an explicit native confirmation dialog.** Opening the menu or viewing the dialog has no side effects; cancel leaves data unchanged. Disable both when Unavailable is empty.
- **Remove Everywhere** for an individual item also requires an explicit native confirmation, including when invoked from Unavailable or an ordinary playlist.
- **Mark Available / Mark Unavailable** are the only newly introduced membership controls that do not require confirmation.
- Count distinct source streams, not duplicate playlist joins. Where practical, preview affected playlist count; never claim to delete files, history, or subscriptions.
- Work against a frozen, validated selection/revision. If the Unavailable set changes between preview and commit, reject and request a refreshed confirmation; never silently widen the deletion set.
- No silently successful partial bulk action: use the shared safe transactional/recovery path; an interrupted operation must have a documented, testable completion or rollback outcome.

### Wording of native confirmations

- **Remove Everywhere:** “Remove this video from Library and all local playlists? Any saved tags for it will also be removed.” Buttons: **Cancel** / **Remove Everywhere**.
- **Clear Unavailable:** “Clear the {N} marked items from Unavailable? They will remain in Library and all other playlists.” Buttons: **Cancel** / **Clear Unavailable**.
- **Remove All Everywhere:** “Remove all {N} marked items from Library and every local playlist? Saved tag assignments for these items will also be removed. This cannot be automatically undone.” Buttons: **Cancel** / **Remove Everywhere**.

Numbers are examples/placeholders, not fabricated live counts. Follow existing NewPipe light/dark/black themes and accessibility conventions; destructive vs non-destructive copy must be visually distinguishable.

## In scope: AI Control parity

AI must be able to **mark unavailable, mark available, clear Unavailable, remove an individual saved item everywhere, and remove all marked items everywhere** on an explicitly targeted device. AI can also list the protected playlist and every saved item in it for user-directed external research.

| User intent | Command behavior | Existing authorization basis |
| --- | --- | --- |
| “Show my unavailable songs” | `list_playlists` and paginated `list_playlist_items`, with a machine-readable designation for Unavailable | `library.read` |
| “Mark these saved songs unavailable” | Idempotently add their memberships to the verified Unavailable ID; can reuse `add_to_playlist` | Existing `playlist.write` + `library.write` if reusing `add_to_playlist` |
| “Mark these songs available again” | Idempotently remove membership from the verified Unavailable ID; can reuse `remove_from_playlist` | `playlist.write` |
| “Clear Unavailable” | Atomically clear only the protected list; provide a **dedicated bounded/transactional action** if the existing 500-item `remove_from_playlist` limit cannot guarantee whole-list behavior | `playlist.write` |
| “Remove these songs everywhere” | Reuse exact-item `remove_library_items` and existing global-deletion safeguards | `library.delete` |
| “Remove all unavailable songs everywhere” | Target the **verified Unavailable designation**, use a frozen expected revision, and execute a single safe bulk global-removal operation; add a dedicated bounded operation if generic 500-item requests cannot meet this contract | `library.delete` |

**AI authorization and confirmation boundary:** The owner has already accepted **standing scoped permissions and direct execution** of unambiguous AI Control commands in [AI Control v1](ai-control-v1.md). The confirmation *windows* above apply to actions initiated through the **native UI**. AI actions do **not** open an on-phone dialog, require a new approval website, or silently change the earlier AI authorization model. Explicit user instructions and existing grants are required; AI asks for clarification when targets or intent are ambiguous. Optional read-only previews are available on request but are not new mandatory approval gates. Record this distinction so that native confirmations and headless AI commands are not incorrectly conflated.

Security and correctness requirements:

- Treat titles, descriptions, URLs and playlist names as **untrusted data**, not instructions to AI.
- Verify selected device, canonical Library ID, Unavailable protected identity, exact source `service + URL` (and validated row ID), and standing scopes **on the device**; reject a similarly named playlist.
- Extend the ordinary-playlist rename/delete protections in native UI and AI catalog writes. **AI may change Unavailable membership; AI may not rename/delete its protected playlist object.**
- Add only backward-compatible, versioned capability/tool-schema changes required for whole-list operations; no automatic new grants, new pairing, or unrelated AI feature work.
- Reuse current request-ID receipts, expiration, conflict checks, exact-target semantics and recovery. Retrying an ambiguous remote bulk command must not pick up newly marked items or perform its effect twice.
- Existing older clients/devices must retain previous scopes and continue operating with previously supported commands. For unsupported new actions return an explicit capability error.
- Accessing the existing device relay to execute these commands is **not** implementing the deferred Cloudflare Library backup/synchronization feature.

## Data and implementation boundaries (proposed)

- Extend `custom/library/` rather than creating a new PreFlight subsystem, new Room migration, new extractor, or second health-check database.
- Store membership in existing `playlists` / `playlist_stream_join` using NewPipe's existing stream identity. **No duplicate files, stream rows, or tags.**
- Prefer a backward-compatible **optional Unavailable identity in the existing Library binding/recovery design** (or equally narrow validated local designation), retaining the accepted Library binding keys and import behavior. Existing writers must not accidentally discard the new designation. Never trust the identity after a mismatched import.
- Reuse `LibraryRepository.removeEverywhere` and `TagRepository`'s coordinated deletion/recovery; implement one common safe native/AI bulk path rather than looping through UI callbacks or writing directly to SQL from the gateway.
- For bulk global deletion, safeguard the selection under the appropriate writer/recovery barriers so the Room membership cascade is atomic and tag cleanup completes or fails closed without silently losing unrelated tags. For large lists, deliberately bound work and UI responsiveness rather than arbitrarily truncating the request.
- Extend only necessary seams in `LocalPlaylistFragment`, bookmark playlist management, the existing playlist manager, protected binding and backup handling, and AI protocol/catalog/gateway. Preserve upstream mergeability.
- Database work must run off the UI thread, and state/visible counts refresh after commits.
- Existing local NewPipe ZIP export/import must continue preserving the Library, tags, and newly designated Unavailable membership/identity. No **new backup product**, cloud service, storage resource, or live data reset.

## Out of scope

- Automatic unavailable detection, thumbnail-failure monitoring, playback-error collection, mass URL probing, YouTube Data API, scanning schedules, provider quotas and any promise that “Unavailable” was verified.
- Automated replacement finding, automatic insert/delete substitutions, playlist-position migration, “best match” scoring, or importing alternatives.
- Cloudflare Library sync or backup, cross-device Unavailable synchronization, downloads/files, history, subscription modifications, or an unrelated migration of the existing accepted Library/Tagging/AI features.
- Any physical-phone data clearing, unsolicited reinstall, or destructive testing with real saved items.

## Verification and acceptance

1. Existing Library/Tagging/AI source remains intact; the feature is built from the accepted `custom/dev` baseline.
2. When a saved Britney Spears video in `jams` is marked Unavailable, it appears **once** in Unavailable and still appears in `jams` and Library. A second mark adds nothing.
3. Mark Available removes it only from Unavailable. Other memberships, saved order and tags are untouched.
4. Marking and clearing require no network connection; thumbnail availability never changes the flag.
5. Individual Remove Everywhere from **each local playlist context** requires confirmation and removes **all** memberships for that exact stream, preserving unrelated items and history. Cancel is a no-op.
6. Clear Unavailable (after confirmation) empties only the marked list. Cancelling and a stale-set conflict change nothing.
7. Remove All Everywhere (after confirmation) removes exactly the confirmed marked set across all local playlists, handles duplicates and tag cleanup, and keeps the empty protected playlist. Cancel is a no-op.
8. Try same-name collision, missing/mismatched designation after restore, cold start, process death during each destructive operation, concurrent marking vs deletion, large synthetic playlists, and restoration of an older supported backup. **Fail closed**, never relabel/delete an unrelated playlist.
9. AI can discover the special identity, page every marked item, mark/unmark, clear and globally delete under **correct device/scopes**; wrong device, insufficient scopes, stale revisions, retries, missing items and unauthorized protected-rename/delete are denied or reconciled safely.
10. Native and AI use the **same business rules**. AI direct execution follows previously accepted standing-authorization semantics; native destructive menu actions always show dialogs.
11. Review builds and unit/instrumentation suites on isolated test data, plus actual native UI interactions through the **primary NotCodex** environment. Never treat a compiled instrumentation test as an executed one; record acceptance evidence accurately.

## Execution constraints and handoff

- **No implementation is authorized by this document alone**. Freeze product scope, review design risks, then implement on an isolated feature branch when instructed.
- **NotCodex-2:** source changes and headless builds only; never launch emulator, GUI, or anything requiring interactive display on that machine.
- **NotCodex:** UI, emulator, and specifically authorized device-side tests/installations, including Tailscale workflows. Never clear any existing app data or overwrite the real user's Library for destructive testing.
- **Codex:** review only; never coding. **Claude Opus Max:** independent review when its rate-limit window permits; do not pretend an unavailable reviewer approved work. Follow the user's current Astra/high-effort review policy.
- Confirmations described above are product UX, **not** authorization to run the destructive actions now.

### Implementation milestones (after authorization)

1. **Protected playlist + marking:** verified identity and restore compatibility; visible Unavailable list; native toggle; AI discovery and marking; tests.
2. **Native deletion and clearing:** shared global-removal operation, two bulk menu actions, individual Remove Everywhere in local playlists, confirmations, optimistic concurrency and recovery tests.
3. **AI bulk parity + final verification:** safe clear-all and all-everywhere AI commands, permission/capability checks, UI review, interruption tests and independent reviews.

**Success:** the owner can manually curate one Unavailable playlist, show it to AI, and intentionally clear its markings or remove the marked videos everywhere—without maintaining a network scanner or a replacement engine.
