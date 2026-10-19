# Cloud & Sync v1 — Product contract

**Status:** Draft for independent design review. Documentation only; no cloud-sync implementation or deployment is claimed.
**Date:** 2026-10-19
**Source baseline:** `custom/dev` at `f76d89a378e71625616bba160a42de24498ccfa1`.
**Working branch:** `feature/cloud-sync-spec-20261019`.
**Related:** [Architecture](../architecture/cloud-sync-v1.md) · [Execution and acceptance](../architecture/cloud-sync-implementation-plan.md) · [Library](library-playlist-system.md) · [Tagging](tagging-layer.md) · [Unavailable](unavailable-playlist.md).

## 1. Agreed direction and scope

The owner approved a separate **Cloud & Sync** settings page, Cloudflare Worker authentication, **D1 for the live shared catalog**, and **minimal R2 recovery storage**. Sync can be disabled without forgetting credentials. Re-enrollment and destructive replacements have an understandable preview with expandable item-level details. A one-time cloud pull does not silently enable ongoing sync. Local data can deliberately replace the cloud.

Cloud sync is not the existing AI device relay and does not require that relay to be enabled. The new service must use the Cloudflare account **ritorhymes@gmail.com**, not **matthewjpellerito@gmail.com**. Do not move, reconfigure, or break the existing AI gateway to implement this feature.

The following detailed defaults are implementation proposals made explicit for review, not additional choices previously made by the owner: conservative whole-catalog conflict detection; exact transfer quotas; recovery archive subtypes; no scheduled full backups in v1. Resolve substantive review findings before coding the affected subsystem.

### Active synchronization coverage

Synchronize canonical Library membership; ordinary local playlist definitions, display order, artwork selection metadata and ordered memberships including duplicates; the protected Unavailable designation and membership; tag/category definitions and stream-tag assignments. Preserve the existing fork's semantics rather than deriving them from names.

Do not actively synchronize watch/search history, playback progress, current queue/player state, subscriptions, remote playlist bookmarks, device paths, UI/player preferences, pairing, credentials, receipts or recovery journals. A **full device backup** covers the supported native backup data more broadly and is labeled separately. Downloaded audio/video, cached image bytes, APKs and build artifacts are never uploaded by this feature.

## 2. Settings and operations

Add one top-level settings entry adjacent to Backup and restore. Keep cloud code in its own package/screen and reuse existing services through narrow adapters; leave the native file-import/export screen intact. Match the fork's native light/dark/black themes and accessibility behavior.

The page contains connection status and masked credentials, a Sync toggle, last successful sync and pending/conflict status, one-time actions, and recovery-backup count/size/status. Credentials are never searchable preference text, diagnostic output, export content, or clipboard content unless explicitly copied by the owner.

| Control | Exact meaning | Destination and ongoing-sync effect |
| --- | --- | --- |
| Connect / test connection | Validate HTTPS Worker address and a device-scoped access token; bind to the service/library identity returned by the authenticated endpoint | Does not upload, replace data, or enable sync |
| Enable sync | Establish a reviewed baseline, normally using the cloud catalog; if the cloud is genuinely uninitialized, offer explicit initialization from local | After successful enrollment, automatic D1 push/pull is enabled |
| Disable sync | Stop admitting cloud sync jobs and detach future local edits from automatic publishing; keep credentials and local data | No automatic network activity; see in-flight rule below |
| Sync now | Run the enabled synchronization policy immediately; conflicts still require reconciliation | D1 only; while disabled this action is unavailable, not a disguised one-time restore |
| Use cloud library once | Preview and replace only the synchronized local catalog with the current cloud catalog | D1 pull; sync is off afterward |
| Replace cloud library with this device | Preview local-to-cloud replacement and the impact on other syncing devices; preserve the old cloud catalog first | D1 replacement plus one R2 safety archive; does not automatically change the current toggle |
| Save device backup | Save a complete supported native NewPipe ZIP, with secrets/runtime state excluded | R2 only; neither replaces the D1 catalog nor enables sync |
| Restore recovery backup | Restore a selected exact archive, with clear scope and consequences | R2; sync is off afterward; a native ZIP restore retains the existing restart/AI-unpair behavior |
| Disconnect / forget credentials | Disable sync, remove this device's local cloud credentials and connection state | Does not delete cloud catalog/backups or other devices |

Use **Replace cloud library**, not a misleading generic Backup label, for the owner's original one-time overwrite request. Saving a recovery copy and replacing the shared source of truth are separate explicit operations.

### Disabled, offline, and conflicted are different states

**Disabled:** No automatic uploads, downloads, status polling or backup jobs. Local edits intentionally diverge. Old pending work is quarantined, never silently replayed on re-enable. Manual cloud actions remain available and are explicitly user-triggered.

**Enabled but offline:** Local editing continues. The last acknowledged baseline and durable in-flight operation record allow retry/reconciliation after process death. The UI says pending/offline, not synced. Returning online does not blindly prefer local or cloud.

**Conflict:** Local and cloud catalog both differ from the acknowledged baseline. In v1, pause automatic apply/publish and offer **Use cloud**, **Replace cloud with this device**, or **Keep sync off**. Even unrelated concurrent edits may require this choice: automatic multi-writer merging is deliberately not promised in v1. Do not discard either branch before the selected replacement has been previewed and backed up as appropriate.

**In-flight disable:** Persist disable and a new local connection generation before canceling work. No work admitted afterward may contact the server or apply a result. A request already sent may have committed; cancellation cannot undo that. Record an unknown outcome for later explicit reconciliation and never claim that disabling rolls back an already committed remote write. Edits after disable cannot be included in that previously frozen request.

### Enrollment and resumption

Never infer an empty cloud from a timeout, authentication failure, missing page, unsupported schema or server error. The server distinguishes an authenticated **uninitialized** catalog from an initialized catalog with zero saved items.

If cloud data exists and differs from local, enabling sync shows the replacement preview. The normal path is **Use cloud and enable sync**. Publishing a divergent local branch instead is the separately confirmed **Replace cloud** action. If both already match, establish identity/baseline without destructive replacement.

A revoked credential or account/endpoint change immediately stops automatic work. A different service/library identity requires a new enrollment, even if display names match. Existing AI pairing does not grant cloud access.

## 3. Domain invariants

1. Every saved playlist item belongs to Library. Library and Unavailable have verified designations, not magic names or cross-device integer IDs.
2. Library contains each exact `(service_id, saved_url)` once. Ordinary playlists retain their native duplicate occurrences and order; different uploads with similar titles remain different media.
3. Removing a Library item removes all of its local playlist occurrences, Unavailable membership and tag assignments. Tag definitions themselves survive.
4. Removing an ordinary playlist membership or Unavailable mark does not globally delete a saved item. Deleting an ordinary playlist preserves Library membership.
5. Category deletion deletes contained tags and assignments, not videos/playlists. Tag deletion removes assignments, not saved media. Preserve the existing tag-name normalization/uniqueness rule.
6. A returning stale device cannot resurrect removed items automatically. It must reconcile against the current epoch/revision rather than replay old writes onto a new baseline.
7. UI edits, existing authorized AI edits, and cloud application use the same invariants. Sync-origin changes are not echoed back as fresh local intent.
8. Full native restore is not routine sync: ordinary synchronization must not restart the app, clear device preferences or reset existing AI pairing.

## 4. Change explorer and confirmation

Show a concise summary first, with **View changes** to expand a native, visual change explorer. This is not a terminal/Git patch interface. Use titles, service/uploader context and optional cached thumbnails; identity and confirmation must never depend on successful thumbnail loading or provider extraction.

Show direction prominently: **Cloud → This device** or **This device → Cloud**. Display source time/revision, comparison time, affected scope and whether other devices will later receive a cloud replacement. Example numbers in prototypes must be marked synthetic.

Details include:

- Exact added/removed Library videos, with global removals visibly distinct from playlist-only removals.
- Playlists created/deleted/renamed; expandable additions, removals, duplicate-count changes and reordering. Prefer named items and meaningful movement groups over marking every shifted index as a separate edit.
- Tag/category creation, rename, move, deletion and assignment changes. Explain category cascades without double-counting saved videos as deleted.
- Unique-video counts separate from membership-occurrence counts. Stable IDs distinguish rename from delete/create; uncertain legacy identity is disclosed, never guessed by title.

Search and filter the full difference set and page results; no silent 100-item/first-page truncation. Reading all detail rows is optional, but every approved change must be discoverable. If a comparison cannot be completed, confirmation stays unavailable. No "everything is unchanged" claim based only on totals.

### Frozen preview contract

Create a validated, disk-backed candidate and local comparison snapshot before presenting a preview. Bind approval to operation type, connection generation, local database incarnation/content fingerprint, exact remote epoch/revision and candidate digest. Do not hold database locks while the owner reads the dialog.

On confirmation, verify the local catalog again under the writer barrier and check the cloud head. Detected changes require a refreshed preview. Always install/publish **the exact candidate shown**, never download a fresh unreviewed "latest" version after approval. Expired or missing staging requires a new preview.

There is no atomic transaction spanning the phone and Cloudflare. A cloud write that occurs after the last head check does not change the already staged candidate: it is a later sync revision. Do not claim that a confirmation freezes all devices indefinitely. During enrollment, a newer destructive revision discovered before activation must pause activation for review, not immediately replace the just-approved candidate invisibly.

Automatic, enabled, nonconflicting sync runs under the owner's standing sync choice and does not show a dialog for each edit. Destructive initialization/reconciliation, one-time restore and whole-cloud replacement do require explicit preview approval.

## 5. Minimal R2 policy

R2 is **not** the transport, staging area or source of truth for everyday D1 synchronization. No ZIP upload per playlist edit, per sync, or per day. v1 has no scheduled full-backup generator. A housekeeping job may clean incomplete uploads; it must not create archives.

Keep **three recovery payload slots total per library**, shared by device-backup ZIPs and cloud-catalog recovery archives, not three per device or per subtype. A reservation/incomplete upload consumes a slot. Never create an unbounded series of date-stamped objects or spill into an unmanaged prefix.

At capacity, reserve room by deleting the oldest eligible completed archive only after verifying that two newer recoverable archives remain. Confirm deletion before starting another payload. This preserves the three-slot bound without a fourth temporary archive. If deletion/verification fails or an in-flight outcome is unknown, stop admitting new uploads; do not keep accumulating "temporary" backups. Never remove the last verified recovery copy to make space. The newest verified archive is not age-expired solely because backups are infrequent.

An unfinished upload is not a usable backup. Verify size, manifest, version and checksum before publishing it in the recoverable list. Deduplicate an explicit retry or identical backup where possible. Protect an archive involved in an ongoing restore/replacement until that operation finishes/expires. There is no unlimited pinning or separate protected-history pool in v1.

**Proposed bounded defaults, to measure before release:** 256 MiB compressed per archive, three slots (768 MiB admitted payload budget), one active upload per library, bounded multipart parts for larger archives. Reject oversize content before replacing anything. Do not silently raise limits. Byte accounting includes incomplete payload reservations and any quarantined upload; actual billing/metadata overhead is not an exact application-payload measurement.

A **device backup** is the supported native ZIP. A **cloud-catalog recovery archive** is a portable, versioned export of the exact D1 catalog being replaced; it cannot contain unsynchronized phone settings/history. Label these accurately. A local ZIP of the incoming replacement is not a backup of the outgoing cloud catalog.

Before destructive local replacement, keep one verified local recovery snapshot without uploading the private detached local branch. Before destructive cloud replacement, require a verified R2 archive of the exact outgoing cloud revision. Failure to make the required recovery point blocks that replacement, not ordinary local use or ordinary D1 sync.

Settings show archive type/date/source, count, measured bytes, last successful backup and cleanup errors. D1 Time Travel is additional operator recovery, not a user-visible promise of unlimited historical archives. R2 in the same Cloudflare account is not protection against loss of that entire account; native local export remains available.

## 6. Non-goals

No CRDT framework, automatic concurrent/offline merge engine, web player, new MCP library-management service, media downloading/uploading, AI permission expansion, automatic unavailable scanning, multi-user sharing, E2EE/recovery-passphrase product, account migration of the existing gateway, or daily archive timeline.

## 7. Completion criteria

The feature is not complete merely because a Worker deploys or an APK builds. [Execution and acceptance](../architecture/cloud-sync-implementation-plan.md) requires tested domain fidelity, exact preview binding, interruption recovery, two-device conflicts, disable/offline behavior, bounded cloud storage and independent review. Production activation, real-data replacement and physical-phone installation remain separate explicit release actions.
