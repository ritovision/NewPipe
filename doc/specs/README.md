# Product Specifications

Agreed product behavior for the personal NewPipe fork, with implementation details and unresolved decisions explicitly distinguished. These documents are not evidence that the features are already implemented.

- [Library Playlist System](library-playlist-system.md) — canonical Library semantics: any playlist or tag-based save adds to Library; removing from Library removes items from all other playlists.
- [Tagging Layer](tagging-layer.md) — persistent categories and tags, approved accordion UI, search, queue and playlists; integrated with AI Control through shared services.
- [AI Control v1](ai-control-v1.md) — authenticated device control and Library/playback operations, extended with versioned tagging capabilities.


Deferred or exploratory work, including Cloudflare backup/sync and Pruning, lives under [Potential Features](../potential-features/README.md).
