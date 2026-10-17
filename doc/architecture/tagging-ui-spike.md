# Tagging UI Spike — Native NewPipe UX Validation

**Status:** Stage 0B native UI demo implemented, compiled and unit-tested (2026-10-17); user hands-on UX acceptance pending. No tagging storage architecture authorized by this document.
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

## Stage 0B evidence — 2026-10-17

- Implemented a self-contained native `TaggingDemoActivity` reachable from NewPipe's drawer as `Tags (Demo)`, with a throwaway, in-memory `TaggingDemoModel` of 12 synthetic videos and 10 synthetic tags. No real NewPipe Library query/write, metadata schema, player control, or AI bridge is used.
- Working demo interactions: local title/uploader search, AND/OR included filters, NOT exclusions via cycling chips, computed Untagged view, Library/title sorting, reusable bottom-sheet tag editor, tag creation/rename/move/deletion, category creation/rename/deletion with affected counts, and non-destructive playback/queue/playlist preview dialogs. Demo scenarios cover reset, empty Library and empty tags.
- Demo-only `feature/tagging-ui-spike` debug package: `org.schabi.newpipe.debug.featuretagginguispike`, Android launcher label **newtest**. Original `@mipmap/ic_launcher` icon was not edited. The demo is installed alongside the original F-Droid, custom Library and AI Control packages, without uninstalling or clearing their data.
- Full `:app:testDebugUnitTest` + `:app:assembleDebug` succeeded on NotCodex-2 with local unprivileged Temurin JDK 21. **182 JVM tests, zero failures, zero skipped**; Gradle Kotlin and Java source checks passed. No Android instrumentation tests were run. Focused tagging model tests cover filter matching, category cascade versus media preservation, derived Untagged, sorting/search and tag uniqueness.
- The Android app's standard `MainActivity` launched successfully on the user's Pixel 7 over Tailscale. **The Tags demo page and bottom sheets have not yet received independent on-device tap/visual acceptance**; the phone returned to an active ChatGPT session, so automated navigation stopped rather than interrupting user activity. The user should open `newtest` → hamburger menu → `Tags (Demo)` for subjective review.
- Initial prototype is deliberately disposable. UX sign-off is required before implementing the production tag database, two-database deletion journal, import/export and real player or playlist writes. The AI branch remains untouched; future integration must be explicit.

## Stage 0C theme and category accordion corrections — 2026-10-17

The user's first visual review rejected illegible white-on-white buttons, default white tag-edit and management sheets, and inconsistent theme styling. These are blocking UX issues, not subjective polish.

- Theme-sensitive views use NewPipe's own light/dark/black background, card, border and dialog color resources. Filled buttons use NewPipe red (not the upstream dark theme's white accent color), with white text; secondary buttons use readable contrasting foregrounds. Spinner options, checkboxes, text fields, preview alerts and category/tag forms are theme-aware. Bottom-sheet content and the actual Material bottom-sheet surface must both be tinted.
- Category filters are individually collapsible, with Expand all and Collapse all. The first group starts expanded for discoverability; other groups start collapsed. Collapsing a category shows all selected positive (+) and negative (−) tags as live chips, omitting only inactive tags. A user can cycle or clear the visible selected tags while the group remains collapsed. Category folded state is independent of filter state.
- The Manage bottom sheet also supports group folding and expanding and preserves visible active selections, rather than displaying an unbounded list of all tags. The user's sample/real media remains completely isolated from the demo.
- Android visual and interaction tests must explicitly check both main-page and bottom-sheet backgrounds, text, contrast and category behavior before declaring the UI acceptable. Automated tests cannot substitute for visual verification on the target phone.
