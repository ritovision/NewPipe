# Three-slot R2 recovery — 2026-10-20

Implementation source **`2cb2df30d7ffc3cbdf9c31b4388915a002a4701f`** follows the live-sync checkpoint `35afff7fd7b356b1288a11462ef89bde1c5325fc` on `feature/cloud-sync-v1-20261019`. This is an integration checkpoint, not release approval or completion of Cloud & Sync.

## Implemented behavior

- Three physical recovery slots shared by native device ZIPs and exact outgoing cloud-catalog archives. Reservations and unresolved multipart uploads consume a slot.
- Explicit, expiring approval names the oldest archive to evict and the two retained copies. Deletion must be confirmed before a new multipart payload is created. Unknown creation outcomes quarantine the slot; expiry alone never frees it.
- Fixed-size parts, immutable object identity, complete-byte verification and resumable operation receipts. Cloud archives are checked against the exact trusted D1 page manifest. Device ZIPs undergo native validation on Android; the Worker verifies transfer integrity and the supported ZIP container, not SQLite semantics.
- Android settings list measured sizes and states, preview upload rotation, resume/stop unfinished work, and preview local historical or native restores. Native restoration preserves the documented AI-unpair/restart behavior. Catalog restoration does not publish to D1 or turn sync on.
- Native restore repeats the exact local-candidate check at the final writer barrier. The local safety backup precedes replacement. Cloud credentials, pending operations and runtime identity do not round-trip in exported backups.

Protocol and operational limits: [recovery-v1.md](../../../cloud/protocol/recovery-v1.md).

## Executed tests

| Check | Actual result |
| --- | --- |
| Worker TypeScript | `npm run check` passed |
| Complete backend suite | 239 passed, 0 failed, 65.852 seconds |
| Focused JVM cloud suites | 28 tests across 5 suites passed, including 5 independent recovery-format vectors |
| Native cloud instrumentation | 132 executed tests passed; 11 explicitly opt-in probe methods skipped (runner reports 143) |
| Recovery native tests within that suite | 11 passed, including native ZIP validation, historical restore, lost replies, payload corruption, pin-release failure and a late writer-barrier edit |
| Android A/B builds | Both isolated app/test pairs built and installed with the expected package IDs and primary QA signer |
| Live Cloudflare round trip | PASS; 13 actions after resolving the original failed operation, no app-data clear |

Fault coverage includes failures before and after R2 effects/D1 index writes, two device identities racing, missing survivors, unknown multipart creation, delayed completion, stale verification callbacks, revoked/read-only credentials, external-generation/index loss, corrupt/truncated archives, and explicit abandon fencing a delayed reservation. The local harness measures physical payloads, not just the number of D1 rows. No local failure injection is mislabeled as an actual cloud outage.

## Live evidence

Primary NotCodex, selected Pixel 7 / Android 17 API37 / 4 KiB pages, Tailscale ADB. Two separate packages on one physical phone:

- A: `org.schabi.newpipe.debug.featurecloudsyncv120261019`
- B: `org.schabi.newpipe.debug.featurecloudsyncv120261019.peerb`
- Signer SHA-256: `934d9e21083080b4d409074d68afe64fed6e34d66bb18db7378b2c0a587b12a2`

Dedicated Worker `newpipe-cloud-sync-v1`, dedicated D1 `bde936ac-caa7-42bb-a931-208118a8cd6a`, private R2 `newpipe-cloud-recovery-v1`, verified Rito account `b35814b119cba4448e3e0ac59442cc25`. The temporary random library was `newpipe-recovery-qa-00cdc038-c8ca-4641-ba6a-18c52a90c09b`; ordinary owner scope was not enrolled. Live limits stayed 1 MiB per archive, 64 KiB/eight catalog pages and 120 requests/minute/location. No DO/KV or paid-plan change was used.

The first deployment (`3ff3b5c5-906c-4092-b169-da6ab5295eec`) revealed that live R2 completion did not supply all metadata exposed by the local emulator. Verification conservatively quarantined the slot even though the complete object existed. Explicit abandonment confirmed its identity and deletion; the Android client then resolved that same canceled sequence. The fix reads authoritative object metadata with `head()` after every completion attempt. A sparse-completion regression was added. The corrected deployment was `c5c54020-3b80-46bc-acb1-4202093a78cf`.

| Live action | Result | Time |
| --- | --- | --- |
| A resolves canceled original operation | No pending archive, zero occupied slots | 3.889s |
| A device ZIP backup | 6,893 bytes, first slot verified | 8.047s |
| A cloud-catalog backup | 2,653 bytes, exact old cloud manifest | 8.128s |
| A publishes tag rename, disables sync | Cloud advances to revision/head counter 2 | 5.693s |
| B connects / joins current cloud | New cloud content, remains disabled | 2.987s / 4.234s |
| B restores older catalog archive | Old local tag restored; live cloud fingerprint unchanged; sync off | 4.928s |
| B device ZIP backup | 6,964 bytes, third slot verified | 7.953s |
| A fourth backup | 6,887 bytes; only the approved first archive deleted; exactly three retained | 8.868s |
| B restores A's newest native ZIP | Native restart fence established | 4.781s |
| B starts a fresh process | Restored semantic content and marker match; sync off | 2.549s |
| Stop A / B | Both disabled; plaintext QA configuration removed | 2.397s / 2.374s |

The three final extant objects total **16,504 bytes**. Both device backup operations were DONE; all slots READY and unpinned. Preview/cancel UI checks did not create another archive or modify D1 catalog content.

Reproducible explicit host driver: [run-live-recovery.py](../../../cloud/test-support/run-live-recovery.py). `--resume-from-backups` is only for the recorded initialized scope, after resolving the same original operation; it is not a generic skip-over-failure option. Raw private logs, APKs, credentials and screenshots are excluded from Git.

## UI and closure

Portrait dark-theme navigation, retained-copy list, named rotation warning, cancel, historical restore summary, and expandable exact tag detail were exercised on a separate scrcpy virtual display. Landscape exposed clipped detail rows in the fixed-header layout. Both cloud dialogs now use a scrolling header and virtualized detail list, sized to the visible window excluding system bars/taskbar. The rebuilt APK passed portrait and landscape checks: expanded exact tag detail, restore/cancel controls and Close remain reachable, and the same prepared preview survives rotation. Native ZIP confirmation shows restart, AI unpairing, sync-off behavior and an initially unchecked settings option. No additional restore/upload was submitted from these preview-only checks. Broader light-theme, large-font, keyboard and accessibility acceptance remains pending.

Final visually checked A app SHA-256: `70d78d44eaa03763d6efb70d307b775dbf8b890dad5eb11e3fe536891be2821f`. The integration test APK is `13463fbd584bcfdddb05cf16520ab44a3d3e1fc8f304bf4220f7b711c7c2509e`; B live app/test hashes are `239d988673bf85dac4085b710f230c681f59df7bd592c9542cb087ff019b72fe` / `8dd34fbffe7c147a15194a0c362b82c81b0d69d6e7d9f348d50e8c2ce91f7997`. B's live build precedes the layout-only correction. Core live actions ran before those layout-only rebuilds.

Both QA device credentials were revoked and independently returned HTTP401 on catalog and recovery endpoints. The Worker was redeployed with both checked-in API gates disabled as version `f5edffb3-979d-4a86-aa99-f37d87364584`; catalog/recovery requests return HTTP503 `CLOUD_API_DISABLED`. Only the three recorded synthetic R2 objects were deleted after terminal-operation, unpinned-slot and revoked-device checks. Exact-namespace inventory then returned zero completed objects; every multipart upload created in this fresh scope had already completed or been explicitly canceled. Guarded exact-scope D1 cleanup was followed by fresh reads proving zero rows across all 13 feature domain/control tables, retaining migrations/schema. Both isolated app connections stayed off, plaintext QA credential files were absent, and the dedicated scrcpy virtual display was stopped. No owner app data, unrelated emulator, gateway or production library was changed.

## Remaining acceptance

Protected whole-cloud replacement (new epoch, persisted cooldown, exact outgoing recovery/CAS and same-attempt stale retry), operator index reconciliation/Time Travel recovery, two physical devices, OS network-outage behavior, broader scale/scheduling and whole-feature independent review remain unfinished. The 1 MiB synthetic live cap is deliberate; the 256 MiB protocol hard limit is not large live Worker CPU acceptance. No merge, owner-app update or permanent production activation is authorized by these results.
