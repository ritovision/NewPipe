# NewPipe AI Control v1 — Accepted Product Decisions & Implementation Plan

**Date:** 2026-10-16
**Target:** RitoVision personal NewPipe fork, `custom/dev`
**Status:** Android catalog, playback, URL ingestion, pairing UI/security, opt-in service and relay implementation are in the isolated `feature/ai-control` candidate. Cloudflare Worker/Durable Objects are deployed **fail-closed pending owner secret configuration**. Native/gateway tests and reviews are recorded in `doc/architecture/ai-control-integration.md`; this is not a verified end-to-end ChatGPT release.
**Source proposal:** `doc/potential-features/ai-control-device-mcp.md`
**Source review:** `doc/reviews/ai-control-claude-opus-max-2026-10-15.md` (raw October 15 review; retained unchanged)
**Adjudication:** `doc/reviews/ai-control-claude-adjudication-2026-10-16.md` (accepted, modified, rejected, and superseded recommendations; independent implementation review still to come)
**Library baseline:** completed October 16 Library v1 and polish, through `b9a8f28d3` when last inspected

## Product decision record — authoritative for AI Control v1

1. **Full catalog management in v1.** The AI may read, search, save, organize, create/rename/reorder/delete ordinary playlists, manage membership, and **remove items from the canonical Library**, subject to user-granted standing permissions and safeguards. This is not a read-only or playback-only milestone. **When permission is granted, explicit AI commands execute directly without an additional per-action or per-batch confirmation step.**
2. **Tags are not a prerequisite.** Tagging is being implemented independently. Integrate it later via optional versioned capabilities; do not modify or block on the separate tagging work during this milestone.
3. **Assume ChatGPT write-capable plugin/MCP integration works.** Don't spend the first phase re-proving that capability or gate the project on plan entitlement research. Verify protocol details as needed while building, but treat write operations as an accepted product assumption.
4. **Cloudflare is the intended production gateway.** Use a Cloudflare Worker-based authenticated MCP endpoint and a small Durable Object-based device relay. Android is the authority and executor; Cloudflare must not maintain a duplicate Library or run a media engine/model.
5. **Background availability is an explicit opt-in.** Build a dedicated Android foreground-service path, with a persistent notification and immediate Stop/Disconnect control. Expect lifecycle/battery limitations; don't promise guaranteed always-online behavior.
6. **Dedicated AI Control destination in NewPipe's main menu.** It provides connection and pairing status, settings, device identity, permissions, paired clients, activity, and Stop. Keep low-level options in that destination or a nested settings screen.
7. **Offline devices fail promptly.** Do not queue any playback or metadata writes for later execution. A disconnected device means `NOT_EXECUTED`, or `OUTCOME_UNKNOWN` if the disconnect happened after dispatch and before acknowledgement.
8. **Every device-directed command names its target device explicitly.** This applies to reads, playback, writes and destructive actions. `list_devices` / account-level pairing metadata is the sole natural discovery exception. Never silently infer a default target or broadcast a command.
9. **Review strategy:** Make the design and implementation as robust as possible without waiting for Claude's temporarily unavailable session. Use available independent Astra review where useful; obtain Claude Opus review later and remedy findings before final sign-off. Review is not falsely represented as already complete.
10. **Implementation authorization and operating constraints (2026-10-16).** The assistant writes all code. Codex/Astra and Claude may only review; use Astra more freely and Claude at important milestones. Headless builds on NotCodex-2 are allowed; no UI, browser, emulator (including headless), or elevation prompts there. No emulators are used in this implementation session. Physical Pixel testing through primary NotCodex is authorized only with isolated synthetic data and a separate QA package, never destructive tests of the real Library. Keep the tagging worktree separate. Tell the user when ChatGPT plugin setup is actually required.

## User-visible workflows to support

- "On my Pixel 7, find the synthwave videos in my saved Library."
- "On my Pixel 7, make an empty playlist called Road Trip."
- "On my Pixel 7, add these 20 Library items to Road Trip."
- "On my Pixel 7, save this YouTube video and put it in Road Trip."
- "On my Pixel 7, remove these three items from Road Trip, but leave them in my Library."
- "On my Pixel 7, rename Road Trip to Night Drive, and move these items to the top."
- "On my Pixel 7, delete the playlist Night Drive, keeping all its videos saved."
- "On my Pixel 7, remove these five videos from my Library everywhere." **This directly invokes canonical global removal**, including membership in all ordinary playlists, when the AI client already has the necessary granted capability. It does not require a separate confirmation or approval UI. The AI may show a preview if requested or clarify genuinely ambiguous selection.
- "On my Pixel 7, play the results", "pause", "resume", "skip this track", or "append the results to the active queue."
- "What is happening on my Windows emulator?" only when targeting that specifically paired Android installation.

## Architecture

```
ChatGPT / authorized AI client
  |
  | authenticated MCP over HTTPS + OAuth grants
  v
Cloudflare Worker (MCP resource endpoint + authorization verification)
  |  scopes, user account, target device ID, schema and rate validation
  v
Durable Object device router (per-device presence, bounded in-flight requests,
                             request receipts/deduplication, WSS hibernation)
  ^
  |  outbound authenticated TLS WebSocket opened by NewPipe
  |
NewPipe Android process (selected device)
  |-- Reachability policy / opt-in foreground-service notification
  |-- DeviceKey + local grant store (not backed up)
  |-- AiCommandDispatcher (in-process Kotlin, not exported IPC)
  |     |-- read-only Library query repository
  |     |-- serialized mutation coordinator + request receipt journal
  |     |-- Library/playlist repository operations through Room transactions
  |     `-- playback adapter on main thread / player service
  `-- future optional Tag adapter (capability-advertised)
```

**Division of responsibility:** MCP and OAuth live at Cloudflare; NewPipe speaks only a small versioned command protocol with the relay. The gateway routes and authenticates; Android validates scope and data, executes mutations, and returns the authoritative result. No generic local HTTP server, exposed device port, ADB-over-Internet bridge, exported command receiver, arbitrary shell command, or direct SQL from MCP is part of production.

**Cloudflare state allowed:** minimal user/client grant metadata, device ID/name/public key/capabilities, connection last-seen and revoked state, short-lived in-flight request IDs, deduplication receipts, and small audit events (`command type`, `device`, `time`, `outcome`). **Excluded:** replicated playlists, a media Library, video content, transcripts, arbitrary saved search results, bulk logs of stream titles and tokens. Use a Worker plus Durable Objects first; no D1 or R2 requirement in v1.

**Cloudflare implementation assumptions to verify during coding:** Workers OAuth Provider v1 supports current MCP OAuth authorization, possibly with a separate authorization-server Worker from the MCP resource Worker; this is not a prebuilt sign-in identity solution. Durable Object Hibernation WebSocket API allows routing without continuously billed active duration while idle. Keep command tracking independent of volatile DO in-memory state, since hibernation clears it. There may be charges; no guarantee of free operation.

## Android integration points and source-aware constraints

- Build inside `app/src/main/java/org/schabi/newpipe/custom/aicontrol/` (owned package), with a thin integration into upstream code. The v1 Library implementation is already in `custom/library/` and does not need to be copied.
- Existing `LibraryRepository` has canonical identity, protected membership, `appendDirectly`, and `removeEverywhere`. **Reuse the verified-ID global-removal transaction**, not a generic `updateJoin` or a name-based Library lookup. Extend with preview/batch API as required; do not bypass its invariants.
- Existing `LocalPlaylistManager` supports appending, playlist creation with a nonempty stream list, list retrieval and playlist deletion/update. **It currently rejects creating empty playlists**; add a safe dedicated empty-playlist creation path as part of AI CRUD (minimal upstream-facing integration, not a fake placeholder video).
- Make **targeted membership add/remove/reorder operations** instead of blindly rewriting entire playlist snapshots. Existing editor rewrites can race with remote writes; protect both paths with transaction-aware concurrency handling and regression tests. Continue to preserve ordinary duplicate semantics unless changed explicitly.
- Stream references on the wire should be selected-device-scoped, include validated `service + canonical URL`, and may carry an internal row ID resolved/verified on device. A device's integer stream/playlist ID is not a cross-device identity.
- For an unknown new URL, ask NewPipe's extractor **on-device** to resolve authoritative metadata before upsert. Never let the AI supply title/uploader fields to `upsertAll` and overwrite existing catalog metadata.
- Playback must use existing in-process player/queue interfaces through a narrow adapter. Do not use `playPause` as a retryable toggle. Explicit `pause`, `resume`, `skip_if_current_track`, and `append_to_active_queue`; return `NO_ACTIVE_QUEUE` rather than accidentally starting playback for append. Handle Android background playback restrictions and main-thread player calls.
- Native backup export includes default SharedPreferences; importing replaces them. Keep AI enablement, device secret material, pairing and local scope constraints **outside default SharedPreferences and NewPipe ZIP backups**. Use Android Keystore for private keys and `noBackupFilesDir` for sensitive local pairing/permission state. Restored devices must be disabled/unpaired until explicitly re-paired.
- Import/restore must stop accepting new mutations and invalidate identity/session assumptions before replacing database state; resume only after validated state is reloaded. Never preserve an in-flight global-delete authorization across a restore. LibraryBackupRestore now stages/snapshots restore files but is explicitly not multi-file crash-atomic; do not present its error rollback as a solution to open DB handle or process-death races.
- PlayerService.onTaskRemoved can currently call Runtime.getRuntime().halt(0), terminating the entire NewPipe process. A dedicated AI foreground service in the same process is not immune. Plan a narrow lifecycle compatibility change (or fail offline), and validate swipe-away/stop behavior on actual Android before promising background reachability.
- **Backup downgrade compatibility:** this fork adds receipt schema 10. A database/ZIP exported after that upgrade cannot be imported into the older schema-9 custom app or stock/F-Droid builds; do not overwrite a pre-upgrade backup. The new importer rejects future schema versions before replacing files, but it cannot retrofit that protection into already-installed old apps. No automatic downgrade/export conversion is promised.
- No tags repository or tag database changes in this milestone. Later Tag integration must save to Library first and clear associations on Library global deletion via the tagging project's supported transactional/repair semantics.

## Proposed v1 command surface

All device tools require `device_id` and a `protocol_version`/capability-compatible envelope. Server and Android independently validate scopes, payload size, expiration, and device identity. `list_devices` is account-scoped discovery and does not require a target.

| Group | Tools / actions | Constraints |
| --- | --- | --- |
| Discovery | `list_devices`, `get_device_status` | No implicit default; disconnected/stale marked offline |
| Browse | `list_playlists`, `list_playlist_items`, `search_library`, `get_stream`, `get_now_playing` | Keyset/cursor pagination, bounded pages (initially max 50), canonical Library-only search |
| Save | `resolve_stream_url`, `save_to_library` | Device-side extractor and canonical deduplication; never model-supplied DB metadata |
| Playlists | `create_playlist` (including empty), `rename_playlist`, `delete_playlist`, `add_to_playlist`, `remove_from_playlist`, `reorder_playlist_items` | Protect canonical Library identity; transactions; preserve ordinary playlist duplicates/order as appropriate |
| Bulk organize | `add_many_to_playlist`, `remove_many_from_playlist`, `move_items_between_playlists` | Bound counts; report individual errors/affected counts; no silent partial success |
| Canonical deletion | `remove_library_items`, optional `preview_library_removal` | Direct permissioned deletion using exact identifiers and one-device transaction, full cross-playlist cascade, validate current state at commit; preview is optional, not a mandatory approval step |
| Playback | `play_items`, `append_to_queue`, `pause`, `resume`, `skip_if_current`, `get_now_playing` | Main thread; playback commands expire quickly; no accidental toggle/restart |
| Operation receipts | `get_command_result` | Find authoritative result after uncertain connection loss, while receipt retained |
| Future tags | `get_tags`, `set_tags`, `search_by_tags` | Not advertised until separately implemented and reviewed |

**Scope model:** user/device permission ceilings, granted client scopes for `library.read`, `playback.control`, `playlist.write`, `library.write`, `library.delete`, and `playlist.delete`; independent connection and revoke controls. `library.delete` is **part of v1** but off by default until explicitly granted. Narrow command semantics prevent permission escalation through creatively shaped input. Avoid exposing a generic SQL interface.

**Direct AI execution (explicit product decision):** The AI chat is the action interface. Once the user explicitly enables AI Control and grants the client device-scoped catalog/deletion capabilities, an unambiguous AI deletion instruction is executed directly. **Do not add mandatory per-item/per-batch confirmation, an on-phone approval prompt, or a Cloudflare approval webpage.** Optional `preview_library_removal` is a read-only convenience when the user asks to inspect the affected items first; it is not a mandatory prerequisite to `remove_library_items`. If the requested selection or target is genuinely unclear, the AI can ask in conversation. Ordinary playlist deletion must preserve Library contents; canonical Library deletion must cascade through all playlists. The device still enforces trusted pairing, permission limits, exact device/stream/playlist identities, scoped commands, atomic transactions, idempotency, and audit trails. The gateway/Android integration cannot suppress any confirmation imposed independently by the ChatGPT host, but must not invent an additional approval workflow.

**Optimistic concurrency:** `remove_library_items` receives exact selected-device-scoped item identities and validates current canonical Library binding and selected membership under the DB writer lock immediately before mutation. The command has a request ID with crash-safe deduplication; never expand the selection to additional items during retries. If an optional preview supplied an expected-state digest, reject a stale mismatch rather than silently widening the deletion. Neither preview nor human approval token is required for a direct, permissioned delete. Explicit user-directed re-add is allowed later.

## Transport, lifecycle, offline behavior

- NewPipe initiates a TLS WSS connection **outbound** to Cloudflare; no inbound Android listener or publicly forwarded phone port.
- When AI Control is disabled: zero remote connection, reject all commands locally, revoke in-process sessions, show disconnected state.
- Dedicated non-exported **opt-in foreground service**, persistent notification with device/connection state and **Stop AI Control**. Candidate foreground-service type: `specialUse`, with manifest permission/subtype justification, subject to Android runtime and distribution requirements. Avoid `dataSync` for permanent control (Android target SDK 35 is subject to cumulative runtime limits).
- Start background service via an explicit user action while foregrounded; Android 12+ imposes background-start restrictions. Opt-in is **not** a promise of perpetual connectivity through force-stop, battery restrictions, reboot, or process death. Test on the physical Pixel through primary NotCodex; do not use an emulator in this implementation session. Mark truly disconnected devices offline.
- If offline at arrival: return `DEVICE_OFFLINE` / `NOT_EXECUTED` promptly, **never store a queued metadata operation for future replay**. If connection drops during execution: report `OUTCOME_UNKNOWN` until a device command receipt can establish `COMMITTED`. The implemented durable ledger stores successful mutations only; a lost rejection or an absent/expired receipt does not prove the earlier logical operation was not executed. Live replies may report `REJECTED` or `NOT_EXECUTED`, but those outcomes are not currently durable receipts. An identical expired mutation may return its still-retained committed receipt; expiration never permits new execution.
- In-flight commands have short TTLs, are canceled on disable/disconnect where possible, and are never newly dispatched after expiration. Persistent command receipts allow query/reconciliation but are **not an offline command queue**.
- All writes and playback name one `device_id`. `list_devices` may enumerate devices and show reachability; the AI must select a specific ID before any action.
- Account-side and Android-side rate limiting, request schema validation, length limits, replay prevention, bounded bulk sizes, crash-safe deduplication receipts, minimal audit metadata, revocation, and strong device pairing.

## Key technical design details requiring implementation decisions

1. **Standing authorization for destructive actions.** Pairing and client capability grants establish whether the AI can call Library delete tools; no subsequent approval screen or confirmation token is part of the product. Define precise command-scoped target validation, permission ceilings, idempotent receipts and optional read-only previews. Untrusted Library metadata cannot grant permissions or turn a read tool into a write tool.
2. **Transaction/batch boundaries.** Whether a multi-playlist operation is all-or-nothing or returns a documented partial result. Canonical global remove should be atomic for one device/Room DB; tag cleanup will be a separate future integration.
3. **Stable result and idempotency journal.** Device-side durable requests/results with crash recovery. Exactly-once at the SQL/business layer where practical; ambiguous crash between commit and receipt must have recovery logic rather than blind replay. Prefer a receipt/operation ledger co-committed with the corresponding Room mutation or an explicitly reconcilable idempotent business operation. A standalone sidecar receipt written after the database commit is not atomic, and may require a narrowly scoped Room schema/migration trade-off.
4. **Search indexing/pagination.** Define case folding, title/uploader matching, stable pagination under edits, filtering of saved-only canonical members, search performance with 20,000+ streams. AI semantic interpretation is separate from basic text search; no on-device embedding model required in v1.
5. **Playlist semantics.** Empty playlist creation; duplicate occurrence behavior; one occurrence versus all occurrences on remove-from-playlist; persistent ordering; thumbnail behavior; how name collisions are handled.
6. **Pairing UX.** Independent device pairing (one-time code + device key), ChatGPT authorization at OAuth consent, multiple clients, grants, device selection and lost-phone revocation. Friendly device names are not identity proof.
7. **Foreground service reliability and costs.** Notification permission behavior, Doze, force-stop/reboot, WSS reconnection policy, battery consumption, foreground-service type, Cloudflare usage. Define reconnect backoff and truthful presence TTL.
8. **Prompt injection.** Stream titles/uploader descriptions originate from strangers and are untrusted input to the model. Enforce standing permission, narrowly typed command/target validation, and a strict boundary between retrieved metadata and executable instructions. Do not add separate user approval as a purported prompt-injection defense; the remaining model-manipulation risk must be acknowledged and tested.
9. **Review of upstream exported player surfaces.** Remote commands must use in-process non-exported execution, not repurpose existing exported media receivers/services. Do not claim this new feature fixes every pre-existing external-player interface exposure.
10. **Future compatibility.** Keep tool protocol and Android capability/version information explicit so newer Cloud clients do not call tools unsupported on an older installation.

## Execution plan (all phases contribute to the single full-management v1 release)

### Phase A — documentation and threat model
- Freeze the v1 command semantics, error categories, device-scoped identities, optional preview behavior, direct execution and standing permission model.
- Map every source hook to `custom/aicontrol/`, `custom/library/`, and the narrow existing UI/settings files, minimizing upstream diff.
- Review with available independent reasoning (Astra as appropriate). Preserve findings for the later Claude review.

### Phase B — local in-process command executor
- Implement versioned envelopes, validation, read-only Library queries, pagination, playlist list/view, and playback adapters.
- Use debug-only ADB/instrumentation against isolated synthetic data in a separate QA package on the physical Pixel through primary NotCodex. **NotCodex-2 is build-only; no UI or emulator, including headless. No emulator is used in this session.**
- No network or Cloudflare infrastructure at this phase.

### Phase C — full local catalog writes
- Implement save-to-Library, URL resolution, empty/create/rename/delete playlists, add/remove/reorder playlist memberships, and atomic global Library removal using the verified canonical ID.
- Add durable deduplication and collision-safe, transaction-aware targeted operations; do not let remote editor operations erase concurrent manual edits.
- Add atomic, directly executable deletion with exact targets and optional read-only preview, **not** a mandatory per-batch approval protocol. All v1 management operations must exist before release; tagging is excluded.

### Phase D — dedicated UI, device security and lifecycle
- Add top-level drawer **AI Control** destination and nested settings/status; master switch off by default.
- Device Keystore key, no-backup identity/grants, explicit pairing, scope controls, audits and revoke. Verify NewPipe export/import cannot clone credentials or reenable control.
- Add opt-in non-exported foreground service, persistent notification, disconnect action, reachability and explicit stop.

### Phase E — production Cloudflare transport
- Implement Worker MCP gateway (OAuth + auth scopes) and per-device Durable Object router, durable lightweight presence and bounded receipts, outbound WSS authentication.
- Register each Android installation independently. Send every command to an explicit device; no implicit target or broadcast.
- Enforce fail-fast offline behavior, no deferred queue, outcome reconciliation, and robust network reconnect handling.

### Phase F — integrated validation, reviewers and rollout
- Test isolated synthetic DB: duplicates, canonical deletion cascades, deleting an ordinary playlist without deleting saved media, empty playlist creation, thumbnail selection, pagination, stale previews, same-name playlists, restore/import during action, UI-vs-AI conflicts, process death between commit and receipt, and untrusted metadata.
- Test multi-device routing, wrong-device rejection, scope escalation attempts, replay, revoke, expired tokens, offline/unknown outcomes, background lifecycle, battery and notifications.
- Compile/build on NotCodex-2. Run physical-device instrumentation only through primary NotCodex on synthetic data; never use emulators in this session or destructively test the live Library.
- Independent Astra review during the implementation when available; Claude Opus review when its limit resets; fix discovered issues and re-review changes. Do not claim an unavailable reviewer has approved a build.
- Then prepare a reviewed build with the existing fixed `custom/dev` package/signing identity, and install only with explicit authorization; preserve the separate stock/F-Droid NewPipe data.

## v1 acceptance criteria

1. The user can request **all ordinary playlist CRUD, Library saves and canonical Library deletes** through ChatGPT and get actual confirmed device results.
2. Adding to any ordinary playlist preserves the canonical Library save invariant; ordinary playlist removal does not remove from Library; **global Library removal removes every matching occurrence everywhere** on the selected device.
3. Library identity must be verified by the repository's bound identity; a same-named playlist or ambiguous restore cannot authorize global removal.
4. NewPipe's independent UI and AI catalog edits must not silently clobber one another; deletion operates on exact verified selected-device items and rejects stale expected state when provided. No extra approval screen is required.
5. Only the explicitly named paired device is affected by a command; permissions cannot be raised remotely beyond the Android device's configured ceilings.
6. Disabled/revoked AI Control cannot be reached; restored/backed-up settings do not clone pairing or silently switch it on.
7. Opt-in background mode has a visible notification, reliable user-controlled stop, and transparent offline reporting in all lifecycle conditions actually observed.
8. No delayed playback or offline metadata edit can occur. An uncertain result is never reported as success; status receipts can reconcile a previously dispatched operation.
9. Large libraries remain responsive with bounded pagination; no synchronous long-running DB work on the UI thread.
10. No tagging integration, cloud Library sync, pruning engine, separate embedded model, arbitrary shell execution, or external device-listener exposure is required for this milestone.

## External technical documentation referenced (checked 2026-10-16)

- [Cloudflare Durable Objects — WebSockets and Hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
- [Cloudflare Workers OAuth Provider v1, October 1, 2026](https://developers.cloudflare.com/changelog/post/2026-10-01-workers-oauth-provider-1x/)
- [Android — Foreground service types, including `specialUse`](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android — Restrictions on starting foreground services from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

**Document disposition:** This supersedes the old `doc/potential-features/ai-control-device-mcp.md` where its deferrals conflict with accepted product decisions. The historical review is separately adjudicated at `doc/reviews/ai-control-claude-adjudication-2026-10-16.md`; the initial adjudication's per-batch confirmation proposal was later explicitly rejected by the user in favor of **standing permission and direct AI execution**, which this specification now reflects. No later Claude re-review has been performed yet. The subsequent user implementation request supersedes the original planning-only restriction. Current progress and remaining release gates are tracked in `doc/architecture/ai-control-foundation.md`; do not imply that code written equals deployed or tested on-device. The separate tagging session's files remain untouched.
