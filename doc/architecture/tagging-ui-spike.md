# Tagging UI Spike — Native NewPipe UX Validation

**Status:** Stage 0 approved for isolated prototyping; no tagging storage architecture authorized by this document.
**Date:** 2026-10-17
**Base:** `custom/dev` (Library v1 complete; commit `b9a8f28d3`)
**Dedicated branch/worktree:** `feature/tagging-ui-spike` at `/home/rito/techstuff/ritoverse/newpipe-tagging`
**Parallel AI worktree:** `feature/ai-control` at `/home/rito/techstuff/ritoverse/newpipe-ai-control`
**Product spec:** [Tagging Layer](../specs/tagging-layer.md)

## Why UI first

The first user-visible deliverable is a **real, interactive Android UI inside NewPipe**, not a website pretending to be NewPipe and not a complete database implementation. The user wants to inspect the actual navigation, density, screen sizes, filters and editor behavior before committing to integration architecture.

Treat this as an intentionally throwaway UI harness. Prefer using stock NewPipe theme, typography, components and navigation patterns. Code may later be refactored, rewritten or discarded. Architecture is *not* validated by a UI-only demo.

## Branch and build isolation

- **Build from Library on `custom/dev`, not the AI branch.** AI Control may finish first; do not couple or merge it just to demonstrate tags.
- Use an independent Git worktree, not branch checkout in the active Library tree or the AI worktree; leave their dirty/uncommitted files alone.
- NewPipe Gradle derives a branch-specific debug package name. The UI prototype's expected package is `org.schabi.newpipe.debug.featuretagginguispike` (verify against APK manifest when built). It must not replace `org.schabi.newpipe.debug.customdev`, the AI debug app, or the user's F-Droid NewPipe.
- @NotCodex-2 is **build-only**: edit code and build/compile on it; no emulators or GUI. Runtime/UI acceptance uses @NotCodex (primary) or the user's own explicitly selected device, without wiping existing app data.
- No cloud services, gateway, MCP, remote commands, forced installs, new signed production identity or real-library data migration for a UI demonstration.

## Exactly what to demonstrate

1. A `Tags (Demo)` destination integrated into the actual NewPipe menu/drawer. It opens a native screen matching NewPipe's visual language.
2. Main Tags page:
   - Local search field (video title/uploader) and visible matching-results count.
   - Category-based list of tag chips / selections; `Uncategorized` grouping for tags without a category.
   - Included tags with global **AND / OR** matching, independent excluded tags (**NOT**).
   - A distinct built-in **Untagged** filter; not a tag definition. It displays saved demo items with *zero* tags, and updates immediately when tags are assigned or removed.
   - At least Library/original order and Title sorting; result rows show recognizable video/song titles and assigned tags.
   - Three visible actions: **Play Results**, **Add to Queue**, **Save as Playlist**. They open an explicit non-destructive *demo-only* explanatory dialog/preview showing current selection/order; do not change real playback or playlists.
3. Reusable `Edit Tags` modal/bottom sheet from a results row: searchable tags, multi-select, create tag, add/remove assignment. The same demo editor can be temporarily exposed through the demo screen without patching the actual video detail page yet. Evaluate whether the editor feels better as sheet or dialog before wiring real detail and long-press actions.
4. Manage tags/categories within the demo: create, rename and delete a tag, create/rename/delete a category, assign a tag to a category or leave it Uncategorized.
5. Category deletion **must** prompt with tag/assignment counts. Confirming deletes the category, every contained tag and its assignments; saved videos/playlists are never deleted. Newly zero-tag videos appear under Untagged.
6. Distinguishable empty states: no Library content, no tags, no matches, Untagged=0.

### Synthetic fixtures only

Use a small, deterministic set of about 12 fictional or generic media rows with distinct titles/uploaders, varied Genre/Mood/Era tags, at least 3 untagged items, and a few tags assigned across categories. Make the demo state **in-memory**, reset on screen or process restart, and visibly label the page `Demo — changes aren't saved`. No background import, tag database, Library writes or modifications to existing user data.

### Interaction and visual acceptance

- User can tap through and meaningfully judge navigation hierarchy, thumb reach, spacing, screen density, tag discovery, Boolean filtering, result sorting and editor workflow on the **actual Android app**.
- Tag creation/assignment and category deletion demonstrably change the demo results and the Untagged count.
- No fake success: playback/playlist actions explicitly say they are previews.
- Responsive at narrow screens, landscape and larger font settings; accessible action targets.
- UX changes should be localized to demo UI and easy to revise. Do **not** build the production tag repository, dual-database deletion journal, backup/import or AI adapters as a prerequisite.

## Stage gates (in order)

**0A — Agreement and isolated docs**: Update spec with the approved category deletion and Untagged rules, create this UI-spike plan on its own worktree/branch, keep AI separate.

**0B — Interactive native page**: Build and compile a minimal demo screen and a narrow entry point, wired to synthetic in-memory data; verify APK package identity. Nothing in the user's real Library may change.

**0C — User UX review**: User examines native interactions and identifies what feels good/bad. Adjust layout and controls rapidly before introducing real persistence. For v1, favor simple AND/OR plus NOT; grouped filter expressions and bulk edits remain optional/uncommitted.

**1 — Architecture and production integrations**: After UI sign-off, implement tag storage, Library coordination, deletion journal, backup/recovery, real video-details/playlist long-press editor, playback/queue/playlist adapters, test coverage and reviewer gates.

**2 — AI integration**: Once both independent branches are stable, compare against `feature/ai-control` and merge/rebase explicitly. AI tool handlers must call established tag/library services; tagging does not import the AI implementation as its foundation.

## Explicitly deferred

- Global NewPipe provider search integration, smart playlists with live saved queries, cloud sync, AI/MCP write operations, bulk tag editing and cross-provider matching.
- Persistent tagging state in the demo; a good-looking clickthrough is *not* evidence of database consistency or crash safety.
- Any claim that Claude has reviewed the demo or the eventual production implementation before that actually happens.
