# AI Control — Device-First MCP and Remote Commands

**Status:** Future-feature proposal / architecture exploration; implementation NOT approved
**Priority:** Deferred; does not depend on Cloudflare library synchronization
**Created:** 2026-10-15
**Project:** Personal NewPipe fork (`custom/dev`)

Related: [Library Playlist System](../specs/library-playlist-system.md) · [Tagging Layer](../specs/tagging-layer.md) · [Cloud Backup and Multi-Device Sync](cloud-backup-sync.md) · [Pruning](pruning-library-health.md)

## Goal and guiding principle

Let the user ask ChatGPT (or another explicitly authorized AI client) to **read, organize, and control the actual NewPipe installation on a selected Android device**, without first building Cloudflare's centralized shared-library database.

**Device-first is the initial architecture.** NewPipe on Android remains the executor and owns its local Library, playlists, tags, and playback. A remote gateway is a **transport/router**, not a duplicate media library or a second streaming engine.

The intended user experience includes commands such as:
- “Search the Library on my phone for energetic rock songs and play the matches.”
- “Add this video to Favorites and tag it 2000s and Funny.”
- “Create a playlist from these tag-filtered results.”
- “What is playing on my Windows NewPipe emulator? Pause it.”
- “Find missing videos on my phone.” (only after the separately proposed Pruning feature exists)

Multiple Android installations—including a Windows Android emulator—may be paired independently; each has its own local state until an optional cloud-sync system is implemented.

## Important separation of concerns

- **MCP** exposes a structured tool interface to an AI client; MCP by itself does **not** locate a phone behind NAT, bypass Android background restrictions, or authorize anyone to use it.
- **Gateway/relay** accepts authorized requests from the MCP side and routes them to paired devices. It need not hold copies of saved streams or playlists.
- **Android command handler** validates and executes a bounded set of commands through NewPipe's regular internal operations and our extension services.
- **Cloudflare library sync (optional future)** would allow metadata changes even while devices are offline, but **actual device playback control still requires a reachable device**. Don't couple the first AI Control version to that service.

### Conceptual path

```text
ChatGPT / other approved MCP client
    |
    v
Authenticated MCP gateway (HTTPS / supported MCP transport)
    |
    v
Device router / minimal relay
    |
    | outbound secure connection initiated by NewPipe
    v
NewPipe's AI Control bridge (Android)
    |-- Library and local-playlist adapter
    |-- Our tag metadata service
    |-- Existing NewPipe playback/queue operations
    '-- Future library-health operations
```

No arbitrary command execution, unrestricted Android shell, generic ADB executor, raw database editing, or publicly exposed phone port is needed for the production design.

## Connectivity options to compare

### A. Development-only: existing @NotCodex + ADB

Use the already-established primary NotCodex environment, connected to the **primary** Android phone by USB or secure wireless ADB, to test on-device commands and responses. A localhost-only development bridge / ADB forwarding may be useful.

**Pros:** Fast initial validation, no new public endpoint or Cloudflare deployment.
**Cons:** Depends on a running reachable computer and ADB; not the desired long-term user experience. Do not build or run an emulator on @NotCodex-2; it is build-only.

### B. Device-initiated relay: candidate production path

When AI Control is enabled and the device is authorized, Android initiates an outbound TLS connection to a small relay (for example, a Cloudflare Worker with a Durable Object, if warranted). Consider authenticated **WSS/WebSocket** for interactive commands, or HTTPS polling/long-polling for simpler intermittent connectivity.

The gateway authenticates the MCP client, determines which paired device receives the command, forwards a structured request, waits for the device's result (within a timeout), and returns actual success/failure.

**Pros:** No inbound port forwarding, public phone IP, or desktop requirement; supports independently paired devices.
**Cons:** Requires deployment and secure pairing, device lifecycle/reconnect handling, and careful Android background behavior. WebSockets aren't inherently guaranteed to stay alive while Android suspends an app.

The minimal relay may store paired-device identity, key metadata, presence/last-seen information, bounded request state, and audit metadata. **Do not store the whole Library just to enable AI Control.** No need for D1-based metadata sync or R2 backups in this phase.

### C. Alternative private transport

A trusted local-network or private-VPN bridge (such as Tailscale plus a compatible MCP-facing service) could reduce public relay exposure but needs evaluation for usability and reachability from the ChatGPT environment. Never assume ChatGPT can directly access a private Tailscale IP.

**Decision open:** Which transport is supported for the intended ChatGPT/client connection and Android constraints when implementation starts? Current ChatGPT custom MCP/app capabilities and permission requirements must be verified then rather than assumed.

## AI Control settings page (proposed)

Add a user-visible **AI Control** entry under NewPipe Settings; a dedicated top-level destination remains an optional UX decision.

Possible controls:
- **Allow AI Control** master switch (off by default; disabling terminates sessions and rejects commands).
- Device's friendly name and unique pairing identity, e.g., “Pixel 7” / “Windows Emulator.”
- **Pair a client** via one-time code or QR flow. Pairing is distinct from merely enabling the switch.
- List of paired clients, their permissions, last activity, current connectivity, and **Revoke** action.
- Permissions for (a) read Library/playlists/tags, (b) playback/queue control, (c) playlist/tag edits, (d) destructive actions; default to least privilege.
- **Allow background connectivity** as an explicit opt-in separate from ordinary in-app connectivity.
- Connection indicator, last-seen status, recent commands/results, and a Stop/Disconnect action.

The settings should explain that enabling a relay connection does **not** allow arbitrary users on Wi-Fi to operate the phone: both client and device authorization are required.

## Device execution model

A narrow, versioned **DeviceCommandHandler** interface exposes operations already supported by NewPipe or by our planned extensions. One bridge serves local development access, a future relay, and other authorized clients.

Potential MCP-facing actions:

| Group | Example operation | Scope |
| --- | --- | --- |
| Devices | `list_devices`, `get_device_status` | Pairing, reachability, capabilities |
| Read Library | `search_library`, `list_playlists`, `get_stream_tags` | Existing saved content only |
| Organize | `create_playlist`, `add_to_playlist`, `set_tags`, `remove_from_playlist` | Writes via Library/metadata coordinators |
| Playback | `get_now_playing`, `play_results`, `append_to_queue`, `pause`, `skip` | Existing player/queue |
| Future | `scan_library_health`, `find_replacement_candidates`, `apply_approved_repair` | Only after Pruning functionality exists |

All operations should:
- Specify a **target device** or use a user-configured default; never silently broadcast destructive actions to every device.
- Validate schemas, payload sizes and permissions **inside Android**, not just at the gateway.
- Follow [Library semantics](../specs/library-playlist-system.md): adding to ordinary playlists or tagging a stream saves it to Library; removing from Library propagates and clears tag associations.
- Use existing NewPipe player/playlist code or our stable metadata service API, not direct SQL from MCP.
- Return structured results with affected counts, device ID, error category, and authoritative completion state.
- Be idempotent where practical; use request IDs and deduplication for retries so reconnects don't replay mutations.
- Make bulk/destructive changes previewable and subject to explicit approval. AI must never claim execution succeeded before Android confirms it.
- Bound long-running actions and permit cancellation/timeouts where appropriate.

An initially useful subset is **list/search Library, inspect/manage tags/playlists, and basic playback controls**. Do not assume future health-check or cloud features exist.

## Several devices

Each NewPipe installation can register as a separate named device with its own credentials and permission scope. The MCP gateway lists which devices are reachable.

Examples:
- “Play on Phone” targets phone.
- “Pause on Windows” targets the Android emulator's NewPipe installation.
- If target is ambiguous, ask or use an explicit default selection.

**Without cloud sync**, playlists and tags can differ between devices. A command sent to Phone A changes Phone A only. AI Control itself must not be misrepresented as synchronization. If sync arrives later, shared metadata edits can be routed to the cloud-side store while play/pause/queue remain per-device commands.

## Android foreground and background operation

This is a major **unresolved product choice**:

1. **While NewPipe is open/active:** Enable a connection in-app, reconnect as needed. Simpler initial implementation.
2. **While NewPipe is not foregrounded:** User opts into background reachability. Investigate Android foreground-service and notification requirements, Doze/battery/network restrictions, reconnection behavior and device-specific limitations. No guarantee of indefinite reachability from a simple enabled toggle.

For offline/unreachable devices, report **offline / not executed** promptly. Whether to queue deferred metadata commands is a separate future decision; playback commands should normally expire rather than execute unexpectedly hours later.

Avoid declaring success for queued requests. A persistent notification, battery cost, foreground-service classification, permission prompts, and a user-friendly disable mechanism require testing on real devices before promising always-on operation.

## Security and privacy baseline

- **Off by default**; explicit client pairing, device consent, permission grants, and easy revocation.
- Use TLS (HTTPS/WSS), authenticated device credentials, short-lived pairing tokens with expiration, and independent client-side authorization. Plan key rotation and lost-device revocation.
- Store device secrets in Android-protected storage; don't ship static shared passwords/API tokens in the APK or write them to logs.
- Default to **read-only** until write permissions are granted; destructive actions need explicit user authorization, clear target and affected count.
- Reject unknown commands and unpaired clients; apply rate limits, request timeouts, replay protection and audit trails without unnecessarily logging private library contents.
- No remote shell or unscoped filesystem/database access; Android service/app component must not be exported broadly to other installed apps.
- Don't expose the phone's ADB-over-Wi-Fi listener to the public internet as the production integration.
- Provide visible indicators of connection and recent operations, and a single switch that revokes active connectivity.

## Validation / acceptance ideas

1. Disabled AI Control rejects operations and does not maintain remote connectivity.
2. Unpaired clients cannot query the Library even on the same local network.
3. Explicit pairing grants only selected capabilities; revoke immediately blocks subsequent operations.
4. Two connected devices appear distinctly and commands reach only the selected one.
5. Read/search, playlist/tag modification and basic playback return verified success/failure from Android.
6. Library and tagging invariants hold for remote mutations; repeated requests don't duplicate content.
7. Large libraries (e.g., 20,000+ streams) can be queried with bounded response size and pagination.
8. Offline, timeouts, battery-suspended apps and reconnects never produce false success or unintended late playback.
9. Android UI remains usable while requests execute; no changes to upstream extractor are necessary.
10. Test only with synthetic/isolated data for destructive operations; preserve real NewPipe data and backup/restore integrity.
11. Development bridge works via primary @NotCodex; @NotCodex-2 remains build-only.
12. No elevated system privileges, sudo or Windows admin prompts without notifying the user first.

## Open decisions

1. Is the first usable mode **foreground only** or opt-in background control? How reliable must background access be?
2. Preferred transport: WebSocket relay, HTTPS polling, local private tunnel, or two supported modes?
3. Should the AI Control UI live solely in Settings or also appear prominently in the main menu?
4. Which MCP clients and ChatGPT configurations can currently connect with read/write permissions? Confirm capabilities when implementing.
5. How should users pair/re-pair clients and authenticate the gateway, and how many independent clients/devices?
6. What is the default device, and when must ChatGPT explicitly ask which installation to control?
7. Permissions and approvals: specific tool scopes, bulk-add thresholds, deletion confirmation, transaction previews.
8. Should commands be queued for offline devices (only low-risk metadata changes), or immediately fail?
9. Foreground service/battery/notification trade-offs if background connectivity is requested.
10. Cloudflare Worker/Durable Object costs, limits, latency, device-presence retention, and failure handling.
11. Request/result schemas, protocol versioning, app version compatibility and telemetry retention.
12. Can a future synchronized cloud library bypass the Android client for **metadata** management while still routing playback to the device?
13. How will disconnected/revoked clients and recovered phones avoid stale credentials or unintended access?

## Possible development stages (not a commitment)

1. **Prove in-app command interface** using isolated data and the existing primary @NotCodex/ADB path; no cloud.
2. **AI Control settings and permissions** with real pairing/authorization.
3. **Small external MCP gateway + outbound relay** for reachable Android installations, without Cloudflare Library sync.
4. **Background reachability**, only if chosen and validated with Android lifecycle/battery testing.
5. **Optional integration with future Cloudflare sync**, keeping playback device-specific and library semantics consistent.

**Disposition:** Documentation only. Do not deploy a relay, create cloud resources, expose network ports, modify app code, install additional services, or set up a persistent background process without a separate implementation request.
