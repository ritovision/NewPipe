# Cloud & Sync — primary-host Android/live checkpoint

Date: 2026-10-20. Base: `7640407ee8ae8ba9fc61b40f8089d49acee86510`, branch `feature/cloud-sync-v1-20261019`. The commit containing this record adds the explicit live driver, peer-B debug package, named Wrangler profile and documentation; Gradle also normalized the checkpoint's Kotlin formatting. No production engine behavior was changed in this pass.

## Scope and environment

The owner authorized primary NotCodex because NotCodex-2 was unavailable. The GitHub checkpoint was restored to `/home/ritorhymes/techstuff/NewPipe`; primary now builds and tests it with JDK21, SDK37, Gradle9.8.1 and Node24. No Music/Sleep emulator was reused or stopped.

Previously authorized physical-phone testing used a Pixel7, Android API37, 4KiB pages, with two separately sandboxed debug packages:

- `org.schabi.newpipe.debug.featurecloudsyncv120261019`
- `org.schabi.newpipe.debug.featurecloudsyncv120261019.peerb`

Both installations have independent native databases, private files, Android Keystore credentials, baselines and WorkManager state. All content is synthetic `example.test` metadata. The owner's existing NewPipe packages/data were not replaced or cleared. This is **two Android installations on one physical phone**, not two physical devices and not final phone/UI acceptance.

## Executed checks

| Check | Result |
| --- | --- |
| Backend TypeScript check | Passed |
| Backend suite with real local workerd/D1 | 197 passed |
| Full JVM suite | 337 passed, 41 suites; zero failures/errors/skips |
| Default debug app + instrumentation build | Passed, 13m3s including first-host dependency/setup work |
| Peer-B app + instrumentation build | Passed, 4m3s |
| Focused controller/wire native run | 32 passed, 37.201s; included in the broader suite below |
| Broader native cloud suite | 121 executed passes, 10 explicit opt-in skips, zero failures; runner reported 131 methods, 70.523s |
| Initial live Android scenario | All eight steps passed |
| Conflict/process-stop live scenario | Seven instrumented steps plus one actual process-stop probe passed |

The broader native skips are nine host-driven process-death probe methods and the opt-in live method. They are not counted as executed successes. This pass did not rerun the older native apply boundary-kill matrix; its historical evidence remains tied to the older candidate.

## Actual Cloudflare synchronization

Verified account: `b35814b119cba4448e3e0ac59442cc25`. Dedicated Worker: `newpipe-cloud-sync-v1`. D1: `bde936ac-caa7-42bb-a931-208118a8cd6a`. Service generation: `d124175c-82e8-4fb7-9534-b62b6c9f0eba`. The existing Music/AI gateway was not changed.

The Worker was first deployed disabled (version `178670af-27e1-431c-a008-d73e978fda69`) and the endpoint returned503 `CLOUD_API_DISABLED`. Temporary synthetic activation used deployment override `ENABLE_CATALOG_API:true` (version `2ebf68b6-f614-408c-b913-97027d6923f1`); checked-in defaults stayed disabled,64KiB/eight pages,120requests/minute/location, no R2/DO/KV binding. Two synthetic hashed device credentials were provisioned outside Git.

Initial live steps used the real controller, HTTPS transport, Keystore, Room/tag stores and WorkManager:

| Step | Observed result | Host elapsed |
| --- | --- | --- |
| A initializes cloud | Exact catalog published from an explicit preview | 9.048s |
| B joins | Same fingerprint, three videos, duplicate playlist entries, one tag and one Unavailable video | 4.865s |
| A edits category | Automatic WorkManager publication reaches accepted baseline | 4.906s |
| B pulls | Automatic WorkManager apply converges to A/cloud | 3.401s |
| B disables and edits | Detached fourth video stays local; syncNow rejects disabled state and cloud head is unchanged | 3.230s |
| B restores once | Exact cloud content restored from preview; automatic sync remains off; local safety backup exists | 4.263s |
| A/B stop | Both connections disabled and plaintext QA credential files removed | 2.381s / 2.352s |

These are small-fixture host/instrumentation timings, not user-perceived latency guarantees. Initial accepted fingerprint was `3230bec4098720998fd8c772f8b97579cdbf5c17d1f4a2a08a91381faca70b09`. After the category edit both apps/cloud reached `39ad7cdad2de2c87494d25c402b5bd9de53aa36e9ea419151658bc58605a6f5f`. A separate authenticated status read confirmed revision2, one packed page,2,086 payload bytes. No archive was created in R2.

## Conflict preservation and cleanup

The second live scenario resumed both accepted installations, committed a
category rename in B, fsynced a ready marker, and actually force-stopped B while
the writer barrier still prevented automatic capture. A then automatically
published an unrelated tag rename. This deliberately models a durable edit
followed by process death; it does not simulate a physical network outage.

On restart B retained its dirty fingerprint
`2addbcbee8a0092046aa8a39b4d2e7501bea34fc2085f91d76e8c5ff65b433d2`
and sync rejected with `CLOUD_CONFLICT_REVIEW_REQUIRED`. A/cloud retained
`16f3718108c15a2316239f03eb7c1c1141d93578bca21aada2709eb565ef0969`.
Only the subsequent explicit preview/confirmation reconciled B to cloud, with
a verified local recovery ZIP created by the production controller first.
Both installations then stopped with automatic sync disabled. The final cloud
head was revision 3, one page, 2,104 bytes. The automatic publication in this
scenario took 36.024 seconds, conflict verification 7.085 seconds and explicit
reconciliation 4.142 seconds including instrumentation startup. Background
scheduling latency still needs broader acceptance.

After acceptance:

- Both synthetic credentials were revoked; missing credentials and each revoked
  token returned 401 `INVALID_CREDENTIAL` through the still-enabled API.
- The default disabled Worker was redeployed as
  `200db3d3-5804-4f01-9ae0-c356df5930fe`; a direct request verified 503
  `CLOUD_API_DISABLED`.
- A guarded cleanup checked the exact QA epoch/head and two known revoked
  devices before removing only this run's synthetic state. A fresh D1 query
  verified zero rows in all nine domain/control tables; the schema/migration
  history remains.
- Each phone connection was read back as disabled and its plaintext QA
  credential file was confirmed absent. The two isolated QA app libraries
  remain available as evidence; the owner's app data was not changed.

## Failures and corrections

- The first live attempt stopped before initialization: Windows ADB `exec-in` silently wrote an empty private credential file. The driver now uses `shell -T`, verifies exact private read-back, and requires a freshly written private result witness rather than assuming Android println appears in the instrumentation stream. The successful retry used the same untouched empty QA installations; no app data was cleared.
- Initial deployment authorization lacked `workers_scripts:write`; deployment failed before publication. The dedicated named profile was reauthorized with the needed Worker/D1 scopes, then deployment and live requests succeeded. The wrapper now explicitly selects `newpipe-cloud`, because legacy `~/.wrangler` can override the XDG directory. No other project's credentials were overwritten.
- An auxiliary Python urllib status read returned 403; the equivalent Node fetch and actual Android HTTPS requests succeeded. The Python diagnostic is not counted as a passed backend request.
- An initial operator revocation statement hashed the complete token instead of the protocol's secret suffix and changed zero rows. The live rejection check caught this; the hash input was corrected, both credentials were revoked, and actual 401 responses were verified before cleanup. A separate cleanup file-existence check was corrected to use ADB shell's remote exit status rather than legacy exec-out's host status. Neither failed check was treated as completed cleanup.

## Evidence and remaining gates

Raw logs, private test provisioning files and the current APK pairs are retained on primary under `/home/ritorhymes/.cache/newpipe-cloud-live-20261020`; build/backend logs use the named `/tmp/newpipe-*20261020.log` paths. Only sanitized result/hash records are committed here. APKs, secrets and full transcripts are excluded from Git.

Reproduction and opt-in requirements are documented in [cloud/README.md](../../../cloud/README.md). The initial scenario requires empty QA installations and an uninitialized synthetic service; the conflict scenario follows the accepted initial scenario without clearing either installation. The operator must explicitly provision/activate and subsequently revoke/disable the dedicated service.

Outstanding: three-slot R2 payload reservation/rotation/verification and Android backup restore; exact outgoing-cloud recovery before forced replacement; independent physical-device/network/Doze acceptance; UI/theme/rotation and broader native/AI writer acceptance; deployed large-catalog resource/CPU and daily-budget gates; final independent whole-feature review and release authorization. This checkpoint is not a merge, production activation, or a claim that Cloud & Sync is finished.
