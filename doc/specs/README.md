# Product Specifications

Agreed product behavior for the personal NewPipe fork, with implementation details and unresolved decisions explicitly distinguished. These documents are not evidence that the features are already implemented.

- [Library Playlist System](library-playlist-system.md) — canonical Library semantics: any playlist or tag-based save adds to Library; removing from Library removes items from all other playlists.
- [Tagging Layer](tagging-layer.md) — separate metadata database, optional categories, tagging from details/playlist menus, dedicated Tags page with local search and filters, temporary playback queues, and regular playlists from results.
- [AI Control v1](ai-control-v1.md) — approved product scope and implementation plan for full Library/playlist management (including deletion), Cloudflare device routing, opt-in background access, and explicit device targets. Tags integrate later.

Deferred or exploratory work, including Cloudflare backup/sync and Pruning, lives under [Potential Features](../potential-features/README.md).
