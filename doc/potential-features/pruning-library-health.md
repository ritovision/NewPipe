# Pruning — Library Health and AI-Assisted Repair

> **Historical / superseded for the next milestone (2026-10-17):** The owner chose a simpler [Unavailable Playlist — Manual Marking and Global Cleanup](../specs/unavailable-playlist.md) instead. No automatic availability scanner, YouTube API, thumbnail monitoring, or replacement engine is approved. The proposal below is retained as exploratory history only, not the implementation plan.

**Status:** Deferred historical feature proposal — planning only; not approved for implementation
**Priority:** Low / backlog
**Proposed navigation:** NewPipe main menu → Pruning
**Created:** 2026-10-15
**Dependency:** Library playlist semantics are defined separately in [Library Playlist System](../specs/library-playlist-system.md).

## Concept

Give the user a single place to check whether saved YouTube, SoundCloud, Bandcamp, and other supported streams are still accessible, review failures, export a list for AI analysis, find alternative uploads, and optionally remove flagged entries in bulk. The goal is to reduce manual maintenance of very large libraries while protecting the information needed to repair them.

“Pruning” is a working name, not a commitment to deletion-first UX. Other possible labels include **Library Health**, **Link Health**, and **Library Repair**.

**Core distinction:** A failed check does not necessarily mean the video was deleted. The default behavior is **mark and preserve**. Explicit deletion is also available.

## Proposed user experience

### Main-menu page: Pruning

The page could have these sections:

- **Overview:** Total saved unique streams; counts by available, unavailable, restricted, uncertain, and repaired; last full scan.
- **Scan:** “Scan Library” button, progress by service, pause/cancel/resume, retry ambiguous results, optional Wi-Fi-only behavior.
- **Findings:** Searchable, filterable list of items with status, source, last checked, last successful check, reason, affected playlists, tags, and any replacement candidates.
- **Repair:** “Find replacements” action for selected items; candidates with matching evidence, confidence, and previews.
- **Actions / Reports:** Export findings, share for AI review, mark/ignore, replace, bulk-delete eligible entries, and review previous changes.

Possible initial navigation: **Pruning → Scan results → Item details → Candidate replacements → Review/Apply**.

### Three major workflows

**A. Inspect and preserve (safe default)**

1. Scan unique streams referenced by the Library and other local playlists.
2. Classify availability and keep uncertain failures separate.
3. Mark suspicious items in the report *without removing playlist memberships*.
4. Let the user ignore an item, retry later, or ask AI to find a replacement.

**B. Bulk removal (explicit fast path)**

1. Scan and select a status filter, e.g., **confirmed/strongly indicated unavailable**.
2. Offer a clearly named **Remove all selected unavailable entries** button and show the exact affected count and playlists.
3. On confirmation, remove those streams from the canonical Library playlist, propagating their removal from all local playlists (see Library Playlist System).
4. Optionally create a small undo/restore manifest or versioned snapshot first. Do not silently remove uncertain, restricted, or unchecked streams by default.

The user specifically wants a fast “press a button and delete everything” option. Here **everything** means all items in the displayed, explicitly selected pruning scope—not every saved song in the entire library. If the product ever offers truly global library deletion, that must be a separate, unmistakably labeled command.

Open choice: whether to permit mass removal of *suspected* unavailable items with a stronger warning, or only high-confidence failures.

**C. AI-assisted review and replacement**

1. Export/share the affected-item list or make it available through a future read-only MCP tool.
2. AI searches for alternative uploads on supported services (YouTube, SoundCloud, Bandcamp, etc.).
3. Review candidates: same title, artist/uploader, duration, release/live/remix variant, audio/video type, and source credibility. Similar titles alone are insufficient.
4. User approves replacement(s), individually or through explicitly reviewed batches.
5. Preserve playlist positions where feasible, tags, history, and original-upload provenance. If requested, remove the old stream from Library and its other playlists as part of the replacement operation.
6. Keep a repair history and reversible mapping so that mistaken replacements can be undone.

A replacement is a *different stream*—potentially on a different service—not an automatic assertion that the recordings are identical.

## Availability classification

| Status | Interpretation | Default action |
| --- | --- | --- |
| Available | Metadata/extraction check succeeds | Keep |
| Suspected unavailable | Provider or extractor suggests missing/private/unavailable | Mark for review and recheck |
| Restricted | Login, age, country, rights or other access issue | Keep and label |
| Unknown/transient | Network error, rate limit, CAPTCHA, extractor incompatibility, timeout | Do not prune; retry |
| Replacement suggested | One or more candidates identified | Await user approval |
| Repaired | Approved replacement applied | Retain provenance/repair history |
| Ignored | User chooses to exclude from prompts | Keep; reversible |

Avoid presenting “deleted forever” as fact when the check cannot distinguish deletion from privacy, restriction, or provider blocking. A wave of simultaneous failures should trigger **scanner degraded** rather than classify the library as dead.

## How a quick preflight might work

Scan **unique source stream identities**, not every playlist membership. A video in 30 playlists is checked once.

Potential health-check strategies:

- **YouTube Data API** for cheap batch metadata lookups where a properly configured API key/quota is available. Up to 50 video IDs per videos.list request in current API design; for 20,000 unique IDs that is 400 batches *before* retries and other checks. Not definitive playback validation; absent videos might be private or restricted. Check actual API terms/limits when building.
- **NewPipeExtractor** to retrieve lightweight metadata per stream through the same service adapters NewPipe uses; more unified across providers but potentially slower and vulnerable to rate limits or upstream breakages.
- **Other service-specific probes** where supported. Generic HTTP HEAD/GET of a watch page is not necessarily a reliable indicator of stream playability.
- **Hybrid:** Fast metadata scan where supported, then selective extractor rechecks on ambiguous or risky statuses.

No video/audio download is required for the initial scan. Use bounded parallelism, adaptive delays, caching, check timestamps, pause/cancel/resume, and backoff on rate-limiting. Do not hammer services. Actual runtime for 20,000 items must be measured rather than promised.

## Data and architecture

Prefer a **separate versioned metadata database** in our fork, not changes to upstream NewPipe Room schema. Health-check code should call small per-service adapters.

Possible records:

- **StreamIdentity:** Service, stable provider identifier if available, canonical URL, last-seen URL; keep the original reference. Do not rely solely on NewPipe's internal numeric row ID.
- **HealthCheck:** Timestamp, method/version, resulting status, explicit error category and retry information.
- **CurrentHealth:** Last successful check, last attempted check, current classification, confidence, evidence.
- **ReplacementCandidate:** Original stream, candidate service/URL/metadata, evidence, proposed match score, approval status.
- **RepairHistory:** Before/after references and playlist memberships, original position, tag preservation, timestamp, source of approval, undo details.

A single logical recording may have multiple source uploads. Whether the library ultimately models recording identity separately from upload identity is an **open design choice**, not a prerequisite for basic scanning.

### Export / AI interfaces

**First implementation option:** Export a portable **JSON** report and human-readable **CSV** with source URL, title, service, duration, playlists, tags (if present), status, checked time, and error reason. User can share the file with ChatGPT or another AI; no always-on connection required.

**Later option:** MCP tools such as list_unavailable_streams, export_health_report, search_replacement_candidates, propose_repairs, and apply_approved_repairs. Keep report-reading tools separate from destructive mutation tools and require explicit approval for removals or replacements.

**Cloud option:** Upload only opted-in health metadata/reports to Cloudflare for cross-device review; not necessary for the first version. Do not expose the full library or private URLs publicly.

JSON/CSV and MCP should use a stable report schema with an explicit version. Reports are snapshots and may become stale.

## Integration with Library playlist behavior

- Scan scope is the union of unique saved streams, with **Library** as the intended canonical set.
- A Pruning removal from Library is a **global library removal**, removing that stream from all local playlists.
- Removal from one ordinary playlist should **not** mark a stream for pruning or remove it from Library.
- A replacement must preserve the original's playlist memberships and ordering when practicable; adding the replacement to ordinary playlists ensures it is also in Library.
- Imports, backups, and Cloud sync must not silently resurrect intentionally removed streams.
- On all destructive actions, preserve a recovery record or provide an explicit warning if recovery is unavailable.

## Open choices

1. Product label: “Pruning” versus “Library Health” or “Repair.”
2. Default scan scope: Library only, all local playlists, subscriptions, watch history, or a user choice?
3. Status confidence: what evidence is sufficient for “confirmed unavailable,” given platform restrictions?
4. Fast checking: provider APIs with credentials, extractor-only checks, or hybrid? Where do API credentials live?
5. Frequency: manual-only, periodic optional reminders, or automatic background checks?
6. Export: JSON, CSV, both; should report include tags, all memberships, artwork URLs, and title history?
7. One-click prune: only high-confidence unavailable, or allow suspected/unknown with extra safeguards?
8. Recovery: undo manifest vs. archived entries vs. periodic backups; retention duration?
9. Candidate search: local heuristic search, ChatGPT via MCP, cloud processing, or combinations?
10. Replacement semantics: replace original in all playlists or offer selective replacement; cross-service equivalents; duplicates and playlist order.
11. Data privacy, rate limits and cost for cloud/API/AI integrations.
12. How to handle URLs/identities that change after an upstream extractor or canonicalization update.

## Validation before development

- Large synthetic libraries (e.g., 20,000 unique streams with many playlist memberships).
- Temporary network failure does **not** result in mass-unavailable classifications.
- Provider/extractor outage pauses or downgrades status; no auto-delete.
- Restricted/private/region-blocked cases remain distinct from inaccessible or deleted.
- Repeated scan is incremental/idempotent; responsive cancel/resume.
- Bulk delete affects **only** the previewed scope and follows Library cascade behavior.
- Exported reports round-trip predictably for AI inspection.
- Repair preserves memberships, order and tags; supports undo or recovery.
- No existing NewPipe library/database data is wiped during development or testing.

## Priority and implementation boundary

**Deferred. No implementation now.** Revisit after Library playlist behavior, tagging foundations, and backups are proven. This document describes possibilities, not committed MVP scope or production-ready detection guarantees.
