# Product Specifications

Agreed product behavior for the personal NewPipe fork, with implementation details and unresolved decisions explicitly distinguished. These documents are not evidence that the features are already implemented.

- [Library Playlist System](library-playlist-system.md) — canonical Library semantics: any playlist or tag-based save adds to Library; removing from Library removes items from all other playlists.
- [Unavailable Playlist — Manual Marking and Global Cleanup](unavailable-playlist.md) — protected manual list, native marking/unmarking, confirmed bulk cleanup, and AI operations; consult its implementation/validation records for status.
- [Tagging Layer](tagging-layer.md) — persistent categories and tags, approved accordion UI, search, queue and playlists; integrated with AI Control through shared services.
- [AI Control v1](ai-control-v1.md) — authenticated device control and Library/playback operations, extended with versioned tagging capabilities.
- [Cloud & Sync v1](cloud-sync-v1.md) — implemented catalog engine, Android settings/transport and explicit restore previews; live synthetic integration is under validation. Three-slot R2 recovery and release acceptance remain unfinished. [Architecture](../architecture/cloud-sync-v1.md), [execution/acceptance](../architecture/cloud-sync-implementation-plan.md), and [implementation overview](../../cloud/README.md).


Deferred or exploratory work, including historical Cloudflare alternatives and the old **automatic** Pruning/scanning proposal, lives under [Potential Features](../potential-features/README.md). The Cloud & Sync v1 documents above supersede the older cloud exploration for current execution planning.
