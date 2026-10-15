# Tagging Layer — Categories, Library Search and Playback Queues

**Status:** Agreed core product behavior; architecture and detailed UX still subject to validation
**Priority:** Planned after the canonical Library playlist behavior; not yet implemented
**Project:** Personal NewPipe fork tracking upstream `dev`
**Created:** 2026-10-15

Related: [Library Playlist System](library-playlist-system.md) · [Cloud Backup and Sync](../potential-features/cloud-backup-sync.md) · [Pruning and Library Health](../potential-features/pruning-library-health.md)

## Purpose and scope

Make the existing saved **Library** useful for flexible organization, searching and immediate playback, without turning the NewPipe codebase into a custom media-management application.

Although the user primarily listens to music, **NewPipe treats these items as streams/videos**. Tagging is available for *any saved stream*, across NewPipe's supported services (e.g. YouTube, SoundCloud, Bandcamp); no artificial music-versus-video classification is necessary.

**Primary architectural goal:** Keep our metadata independent from NewPipe's canonical Room schema and extractor/playback code. Prefer a small in-app metadata module with its **own SQLite database** and a few well-defined adapters rather than extending upstream tables or rewriting upstream search.

## How tags, categories, playlists and queues differ

- **Library:** Canonical set of user-saved streams. An item can be in Library even if it is in no organizational playlist and has no tags. The [Library spec](library-playlist-system.md) owns its invariants.
- **Ordinary playlist:** Explicit, ordered collection saved through NewPipe's existing playlist system. Contents change by playlist operations.
- **Tag:** Reusable user-defined attribute attached to zero or more saved streams (e.g., Hip-Hop, Energetic, Funny, 2000s). Each stream may have many tags.
- **Category:** Optional grouping of tag definitions (e.g., Genre, Mood, Era). A tag may have a category or remain **Uncategorized**. Users can also use a flat tag list with no categories.
- **Filtered results / temporary smart queue:** On-demand query over the Library. Filters determine the streams; sort order determines playback order. Playing results creates a normal NewPipe playback queue; it need not save anything.
- **Saved normal playlist from results:** Explicit snapshot of the *current matching items in the current order*. Subsequent changes to tags do not automatically change this playlist.
- **Persisted smart playlist:** A saved definition of the *query rules*, whose results refresh over time. Useful possible extension, **not currently a requirement**.

Tags overlap with playlists when filtering by a single tag, but become substantially more useful when combining attributes and exclusions. Tags also are not required to have independent manual playback order.

## Agreed Library and tag lifecycle rules

1. **Assigning a tag to an unsaved stream automatically saves it to Library**, even if the user never adds it to an ordinary playlist.
2. Assigning additional tags to a saved stream does not duplicate its Library membership.
3. Removing an individual tag assignment does **not** remove the stream from Library, even when that was its final tag.
4. Removing a stream from one ordinary playlist leaves Library membership and tag assignments untouched.
5. **Removing a stream from Library removes it from every ordinary playlist AND deletes its stream–tag associations** in our metadata database.
6. **Deleting a stream does not delete the tag definitions.** Tags are allowed to exist with zero assigned streams.
7. Deleting a tag definition should remove that tag's associations; category-deletion behavior remains open.
8. Saving from anywhere (video screen, long-press menu, tag editor, filtered-result action, import, future MCP/cloud flow) must obey the same Library rules.
9. Tags apply to the specific saved stream/upload identity, not automatically to every upload that appears to be the same song. Cross-service equivalence is a separate, later feature.

For the globally removed stream, do **not** quietly retain orphaned active tag associations as if it were still in Library. Independent historical backup snapshots may of course preserve recoverable older data; a sync tombstone is a separate synchronization concern.

## UX: how tags get assigned

### Video/details screen

NewPipe currently presents **Add To · Background · Popup · Download** actions on the video screen. Add a **fifth action, Tags**, as shown in the user-provided screenshot context.

- Tapping **Tags** opens a reusable **bottom sheet / modal** on the same screen; it should not navigate away.
- Show the item's existing tag selections; let the user search, multi-select, add and remove tags.
- Permit creating a new tag from this editor and, optionally, assigning it to an existing/new category.
- When the first tag is saved to an unsaved stream, invoke the canonical Library-save operation first or coordinate both writes safely.
- No need to add the stream to a separate ordinary playlist.
- If five actions overflow on small devices, use a responsive layout, a horizontal strip, or an overflow mechanism. Do not squeeze controls until the targets become unusable. Exact UI arrangement remains open.

### Playlist item long press

Add an **Edit Tags** item to NewPipe's existing playlist-item long-press/context menu. It opens the **same tag editor** as the video-details screen, not a second implementation.

Possible secondary action: **Open in Tags** to navigate to the full Tags page with the stream selected. Prefer inline editing for routine single-item changes. Multi-select/bulk tag assignment is a later UI option, not required to make simple tagging usable.

### Tags page in the main menu

Provide a directly accessible **Tags** destination in NewPipe's main navigation/drawer, alongside other existing destinations. This is our main feature screen rather than a modification to the upstream service-search interface.

**Main page capabilities (core scope):**

- Create, rename, organize and delete **tag definitions**.
- Create, rename and manage optional **tag categories**; view category-grouped tags or a flat uncategorized list.
- Search **saved Library** streams by textual metadata such as title/uploader.
- Select multiple tags with **AND / OR** matching and exclude selected tags (**NOT**); show a result count.
- Filter and sort the **current results**. Exact sort options TBD; at minimum a deterministic order and a title-based option.
- See the matching stream list, inspect a stream and edit its tags.
- **Play Results** — replace current NewPipe playback queue with the matching streams in current sort order.
- **Add to Queue** — append matching streams to the existing NewPipe queue in current sort order.
- **Save as Playlist** — create an ordinary NewPipe local playlist containing a snapshot of the currently matching streams in current sort order.

Filtering should update the results without saving anything. Queue generation is **ephemeral**: it uses the existing NewPipe playback engine and playlist items, not a new streaming engine or separate persistent playlist.

**Empty states:** No matching results, no tags yet, no saved Library items, and items that have become unavailable should each have clear behavior.

### Search boundaries

Keep **local-library/tag search** separate from NewPipe's ordinary provider search (which uses service-specific extractor queries). We can offer a shortcut or optional **My Library** mode inside existing search later, but do **not** begin by deeply modifying NewPipe's long, evolving `SearchFragment`.

There is no requirement to tag content returned by a provider search before it is saved, because assigning a tag automatically **saves it to Library**.

## Example scenarios

**Tag and save from any video:** Open the details screen → Tags → select `Hip-Hop` and `Energetic`. The stream is now in Library with two tags, whether or not it is in a named playlist.

**Filter and listen:** Go to Tags → include `Hip-Hop` AND `Energetic` → exclude `Live` → sort → **Play Results**. NewPipe plays the present matching set. No playlist is created.

**Queue after currently playing music:** Apply tags/search → **Add to Queue**. Matching results append without disrupting the current item.

**Keep a curated snapshot:** Apply filters → **Save as Playlist** → name it `Workout Mix`. Later tag changes do not silently rewrite that ordinary playlist.

**Remove a saved item entirely:** Delete from Library → remove its memberships from every local playlist and remove its tag assignments. Keep the `Hip-Hop` and `Energetic` tag definitions for future videos.

## Proposed data layer (isolated from upstream)

**In-app metadata module**, not a separate always-running Android process or cloud dependency. The module can expose an internal repository/service API reusable by the Tags UI, the tag editor and future MCP/cloud adapters.

Possible tables in our independently versioned SQLite database:

| Table | Fields (illustrative) | Purpose |
| --- | --- | --- |
| `tag_category` | category_id, name, sort_order | Optional category definitions |
| `tag` | tag_id, name, nullable category_id, sort_order | Tag definitions; unassigned tags retained |
| `stream_tag` | stable_stream_ref, tag_id, created_at | Many-to-many links between saved streams and tags |
| `stream_reference` (optional) | local_ref, source_service, provider_id, canonical_url, identity_version | Reconciliation aid if upstream identity conventions evolve |
| `saved_query` (future only) | query_id, rules, sort, name | Persisted smart-playlist definitions, if ever requested |

Do **not** add columns/tables to upstream NewPipe's `AppDatabase`. Avoid mirroring its whole stream catalog unless a derived local search index is genuinely necessary; use NewPipe's existing saved Library for canonical stream metadata. SQLite indexes can support tens of thousands of stream–tag memberships without an elaborate search service.

NewPipe's current stream identity is unique within its Room schema by **serviceId + URL**; our adapter should resolve a stable logical stream reference and must not depend on local auto-increment `uid` across installations/imports. Provider IDs and URL normalization may change and need migration/reconciliation procedures.

### Narrow integration points

Conceptual interfaces (names not commitments):

- `LibraryAdapter`: enumerate saved stream references; ensure saved; observe global remove.
- `MetadataService`: CRUD for tag categories and tags, get/set stream tags, search/filter Library.
- `PlaybackAdapter`: turn ordered StreamRefs into NewPipe's playback queue or append to it.
- `PlaylistAdapter`: create a normal local playlist from the ordered result snapshot.
- `ExportSyncAdapter` (future): include our metadata database in backups and synchronize tags independently of upstream schema.

Changes to NewPipe's actions/menus and navigation are thin **UI adapters**; our tag data model and query logic remain contained. Future upstream UI rewrites could still require reconnecting the adapters, even though the metadata schema stays intact.

### Important integrity issue: two databases

Saving an unsaved stream when assigning its first tag requires a write to NewPipe's own Library/playlist storage and another write to our tag database. This is **not automatically a single atomic transaction** across two SQLite databases.

Implement safe ordering and failure recovery (e.g., ensure Library save succeeds, then write the tag; on failure surface a retry and reconcile). Similarly, a global Library removal must reliably clean up associated tags. Reconcile orphan associations after import/restore or interrupted operations; do not silently erase unrelated data in error cases.

## Backup, Cloudflare sync and MCP

- Existing NewPipe export/import behavior does **not automatically include a newly added separate metadata database**. Extend the backup integration so categories/tags/memberships survive restore; never assume NewPipe's current export captures them.
- If [Cloudflare sync](../potential-features/cloud-backup-sync.md) is built later, tags/categories/stream-tag associations should sync just like the canonical Library, with stable logical IDs, offline edits, deletion events, conflict handling, and device re-pairing.
- Cloud sync is **not a prerequisite** to using tags locally. No Cloudflare resources or credentials are required for the first tagging implementation.
- Future MCP operations can use the same metadata service and approved playlist/playback adapters: search tagged content, bulk tag, queue results, make playlists. MCP should not write directly to either app database. No MCP server is being built by this spec.
- Global Library removal must delete the active stream-tag relations on all synchronized devices; stale offline devices must not resurrect them inadvertently.

## Core acceptance criteria

1. Any saved NewPipe stream from supported services can be tagged; no artificial songs-only path.
2. Categories optional, flat tags supported, unused tag definitions persist.
3. Video-details Tags action and playlist long-press Edit Tags invoke one reusable editor.
4. Assigning a tag to an unsaved item automatically saves it to Library exactly once.
5. Removing the last tag leaves the stream saved; removing it from Library deletes its tag associations and playlist memberships but retains definitions.
6. Tags page is directly accessible in the main navigation and supports creating/managing tags and categories.
7. Text search, multi-tag AND/OR, exclusion, sorting and result counts work on the saved Library; unaffected by ordinary provider-search changes.
8. Play Results / Add to Queue use NewPipe's existing player with the exact current result set/order; neither implicitly creates a playlist.
9. Save as Playlist creates a regular NewPipe playlist snapshot; subsequent tag changes do not mutate that saved playlist.
10. Offline use works. Interrupted writes, imports/restores and failed operations do not silently corrupt the canonical Library or leave permanently inconsistent tag associations.
11. Existing data is backed up before migrations; all destructive testing uses isolated or synthetic data. No production user data cleared.

## Open implementation / UX choices

1. Names and tag uniqueness: global uniqueness or names scoped to category? Can the same label appear in multiple categories?
2. Category deletion: move contained tags to Uncategorized (suggested) or delete tags and associations with explicit confirmation?
3. Editing UX: checkbox list versus chips; save immediately or explicit Apply; create tag/category from bottom sheet or separate form?
4. Fifth details action layout on smaller screens, landscape and accessibility text sizing.
5. Exact filter grammar: global AND/OR plus excluded tags initially versus grouped expressions by category (Genre OR Genre, AND Mood, NOT Live).
6. Filtered results sort options: title, original playlist order, time saved, duration, artist and shuffle; whether random order is seeded.
7. Does Play Results replace current playback immediately or offer an optional confirmation? Queue behavior when already playing.
8. Save as Playlist: creation of empty playlists, duplicate streams, static order, whether to append to an existing playlist too.
9. Saved filter presets/dynamic smart playlists: attractive future option but not required in first build.
10. Should streams without any tags be filterable through a special `Untagged` facet?
11. How to efficiently get the canonical Library stream set at 20,000+ items; indexing/cache invalidation strategy.
12. URL/service identity reconciliation across upstream upgrades, database imports and multiple devices.
13. Whether bulk tag edits are included initially or delivered afterward.
14. How to expose tag operations in cloud/MCP later without duplicating authorization and Library logic.

## Priority / implementation boundary

**Document only for now.** Implement canonical Library automation first. The agreed first tagging milestone is the separate metadata layer, tag/category management, reusable tagging sheet, Tags page with text/AND/OR/exclusion/sort, and **Play Results / Add to Queue / Save as Playlist**.

Deep integration into upstream global search, a completely separate player, persisted dynamic smart-playlist subscriptions, cloud sync and MCP are later decisions. Do not add them implicitly during initial tagging work.
