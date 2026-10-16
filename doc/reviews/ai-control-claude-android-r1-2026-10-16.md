# Independent source review — raw response

Reviewer inspected a bounded snapshot and did not implement code. Author fixes/adjudication are recorded separately.

**Verdict: BLOCK.** This is a static, read-only review. Nothing was built, run or edited.

The core authorization model holds up. Two problems block activation:
- **LAN fetches via playback:** an AI client can save a media URL on its own server, then trigger playback that fetches addresses on the user's home network or the phone itself. This defeats the "public-network-only" guarantee for the feature as a whole.
- **Remote shutdown:** a single malformed or oversized request turns AI Control off until the user reopens the app.

Findings 3–5 should also be fixed before activation. None of the fixes reduce destructive catalog capability or add confirmations.

## Findings (priority order)

**1. High — AI-saved URLs on arbitrary hosts lead to unrestricted LAN/loopback fetches at playback; cookies also leak to those hosts**
- **Where:** `AiUrlResolver.kt:25-47`, `49-70`; `AiCatalogStore.kt:58-83`; `AiAndroidPlaybackPort.kt:28-34`; `AiPlayerStartBridge.kt:35-107`; `DownloaderImpl.java:155-158`
- **Problem:** `NewPipe.getServiceByUrl` accepts PeerTube `/videos/watch/` and `/w/` URLs on any host. It also accepts Bandcamp custom domains, which it confirms by fetching the page.
  - Resolution itself is public-only. But `play_items` then plays the saved item through the ordinary player and ExoPlayer, outside `AiExtractionNetwork`.
  - The attacker host's JSON/HTML supplies absolute stream and caption URLs. These are fetched with no IP or port check, so they can point at 192.168.x, 127.0.0.1 or any port.
  - An authorized client, or a prompt-injected model, can therefore make the phone send blind GETs into the user's LAN or to local services.
  - Separately, `getCookies()` attaches the reCAPTCHA cookie to every host, so restricted extraction sends it to the attacker host.
- **Remedy:**
  - For AI-originated `resolve_stream_url`/`save_urls`, check the host against the matched service's first-party domains before calling `getServiceByUrl`, so no custom-domain probe happens. Check again on `info.url`.
  - Allowed hosts: fixed YouTube, SoundCloud and media.ccc.de hosts; `*.bandcamp.com` only; PeerTube only on instances the user has configured.
  - In the restricted scope, only send each NewPipe cookie to its own service's domains.

**2. Medium-High — Request-level validation failures permanently disable AI Control**
- **Where:** `AiRelayTransport.kt:88-118`, `136-139`, `233-237`; `AiRelayWire.kt:24-25`, `68-89`; `AiControlRuntime.kt:321-331`, `415-432`
- **Problem:** every exception in `onMessage` becomes `fail(..., permanent=true)`. That leads to `stateFailure()`, which persists `enabled=false`. Realistic triggers:
  - A frame over 72 KiB. A legitimate 500-item request with long SoundCloud/PeerTube URLs is enough; requests are only capped at 64 KiB.
  - A non-UUID `request_id`.
  - A `client_name` over 80 characters, such as a dynamically registered OAuth client name.
  - Any future gateway scope string like `openid`.
  - On the connect path, any 401/403 (including WAF challenge pages) and any non-JSON 2xx response also persist the disable.
- **Remedy:**
  - Treat only envelope or session violations (frame type, `connection_id`, `owner_id`, a command before `ready`) as connection-fatal. Handle those with a non-persistent reconnect and backoff.
  - For a command frame with a valid envelope, refuse that one request (`NOT_EXECUTED`, `INVALID_REQUEST` or `PAYLOAD_TOO_LARGE`) when its `request_id` can be correlated. Otherwise drop it.
  - Ignore unknown scopes instead of failing.
  - Persist `enabled=false` only for a genuine pairing or key rejection.

**3. Medium — The policy commit is check-then-act, so a stale relay can be re-armed after `beforeRestore()`/`forget()`**
- **Where:** `AiControlRuntime.kt:190-199` (version check at 193, then `allowed.set` at 197 and `connect()` at 199); the same pattern at `76-78`; `280`; `435-445`; `208-228`
- **Problem:** `policyChange()` can run from the import/panic worker or the notification Stop. If it lands between lines 193 and 197:
  - `allowed` is set back to true and `connect()` builds a relay with the old identity.
  - The later `beforeRestore`/`forget` task never sets `allowed=false` again and never calls `stopConnection()`.
  - `current()` checks only `allowed` and the generation counter, not the saved state.
  - That relay can pass `accepted()`, call `enableGatewayFromLocalConsent` with the old catalog epoch, and run catalog commands during or after an import.
  - The window is narrow, but it breaks the restore barrier's core guarantee.
- **Remedy:**
  - Make "increment version and set `allowed=false`" and "check version and set `allowed=true`" atomic under one lock.
  - In the `beforeRestore`/`forget` tasks, set `allowed=false` and call `stopConnection()` after `access.disable()`.
  - Make `current()` require the published state to be enabled and paired, with deviceId, catalogEpoch, permissions and origin matching `transport.identity`.

**4. Medium — The in-process PlayerService path bypasses Android's "started service" rules**
- **Where:** `AiPlayerStartBridge.kt:48-53`, `105`; `PlayerService.java:141-177`
- **Problem:** `serviceInstance() != null` is also true when PlayerService is only bound. That happens with PlayQueueActivity, PlayerHolder or MediaBrowser binds that use `BIND_AUTO_CREATE`, for example after `destroyPlayerAndStopService`.
  - Calling `onStartCommand` directly creates a Player and calls `startForeground`, but the service was never "started". AI playback then dies when the last bound client disconnects.
  - In the background on API 31+, `startForeground` can throw after `new Player(...)`. That leaves a player with no queue that PlayerHolder and the UI can see, and `play_items` reports `OUTCOME_UNKNOWN`.
  - The dummy-notification fallback at line 105 also runs on this path, where it isn't needed.
- **Remedy:**
  - Take the direct path only when `service.player != null`; otherwise always use `startForegroundService` with the ticket.
  - Run the dummy-notification fallback only for starts dispatched by Android.
  - If the handoff throws after the player was created, destroy that player.

**5. Medium — `stopService` can run before `startForeground` and crash the app**
- **Where:** `AiControlRuntime.kt:200`, `220-221`, `428-430`, `444`; `AiControlActivity.kt:86-98`, `209-227`; `AiControlService.kt:49-63`
- **Problem:** right after Start succeeds, the button already reads "Stop" (the connection shows "STARTING") and is no longer locked as busy.
  - A quick second tap, Forget, import or panic posts `app.stopService(AiControlService)`.
  - If that runs before `onStartCommand` has called `startForeground`, Android crashes the app with "startForegroundService() did not then call startForeground()".
- **Remedy:** only call `stopService` externally once `serviceActive` is true (it's set after `startForeground`). Otherwise rely on the existing branch in `serviceStarted()` that stops the service when `!allowed`, or send an ordered `ACTION_STOP`.

**6. Medium-Low — Clients are registered asynchronously, so a client can run commands without appearing in the list or being blockable**
- **Where:** `AiControlRuntime.kt:349-352`, `363-370`; `AiLocalStateStore.kt:55`
- **Problem:** the 16-client cap is checked against a stale snapshot.
  - A burst of new clients exceeds it. The later `store.update` fails validation and the error is swallowed.
  - A disk-write failure has the same effect.
  - Either way the client keeps executing without ever being listed, and Block buttons only exist for listed clients.
- **Remedy:** save a new client on the control thread before admitting its first command. Refuse with `CLIENT_LIMIT` or `LOCAL_STATE_WRITE_FAILED` if that fails.

**7. Low-Medium — Import, reset, panic and re-pair erase the local block list**
- **Where:** `AiControlRuntime.kt:441`; `AiRelayTransport.kt:258`
- **Problem:** clients the user blocked regain access after re-pairing, even though a restore has nothing to do with client trust.
- **Remedy:** keep `blockedClients` across restore and re-pair, at least for the same owner. Clear it only on an explicit Forget, and say so in the UI.

**8. Low-Medium — Resource limits on restricted extraction**
- **Where:** `AiUrlResolver.kt:99-136`
- **Connection pools:** each URL scope builds a new `ConnectionPool()` that is never evicted, so idle sockets linger about 5 minutes. With up to 20 URLs per command, this risks file-descriptor and memory pressure in the player's process.
- **Cancellation:** in-flight calls aren't cancelled on stop, so the single command thread can stay busy about 15 s and delay the next handshake.
- **PoToken (unverified):** PoToken generation is registered at `App.kt:126`, but its implementation isn't in this snapshot. Its network traffic runs off the command thread, outside the scope's deadline and size limits. Please verify that path.
- **DNS64/NAT64:** `isPublic` rejects `64:ff9b::/96`, so extraction fails on DNS64/NAT64 networks.
- **Nits:** 6to4 (`2002::/16`) and Teredo addresses are allowed. `Proxy.NO_PROXY` bypasses any user-configured HTTP proxy, which is a privacy note.
- **Remedy:** call `evictAll()` and cancel in the scope's `finally`, or use one shared restricted client that reads the thread-local scope. Cancel the live call from `stopConnection`. Decide explicitly how the PoToken path is bounded.

**9. Low — The restore barrier doesn't cover Library initialization started by `accepted()`**
- **Where:** `AiControlRuntime.kt:289-306` vs `435-445`; `LibraryBackupRestore.kt:108-119`
- **Problem:** `LibraryRepository.initialize` runs on the command thread outside the access gate, and `beforeRestore()` doesn't wait for it. A delayed task can republish a Library session for the pre-restore database after `suspendForRestore()`.
- **Remedy:** run it under the gate with a policy-version recheck, or have `beforeRestore()` wait for a barrier task on the `commands` executor.

**10. Low — Visibility, outcome codes, panic latency**
- `AiControlService.kt:90-107`: on recent Android versions users can swipe away the foreground-service notification or revoke notifications, and the service keeps running invisibly. Add a delete intent that re-posts or stops, and re-check `areNotificationsEnabled` and the channel importance.
- `AiPlayerStartBridge.kt:54-57`: failures from revoked or expired checks are reported as `BACKGROUND_START_NOT_ALLOWED`.
- `AiPlaybackExecutor.kt:90-92`: errors reading saved media before any effect (e.g. `StreamType.valueOf`) become `OUTCOME_UNKNOWN`.
- `AiPlayerStartBridge.kt:95`: replaying an identical queue resumes it but reports `OUTCOME_UNKNOWN`. This is conservative; fine if documented.
- `AiControlLifecycle.kt:34-49`: panic can wait up to 30 s. Use a short bound, then delete keys and exit.
- `BackupRestoreSettingsFragment.java:175-187`, `211-217`: choosing an invalid ZIP still unpairs AI Control, because the disconnect runs before the ZIP is validated.

## Missing tests (highest value first)
1. Runtime interleavings through a test seam: run `beforeRestore()`, `forget()` or Stop between `change()`'s version check and its commit. Expect `allowed=false`, no relay `connect()`, `accepted()` refusing, and no old-epoch `enableGatewayFromLocalConsent`.
2. The LAN-fetch chain: a PeerTube-pattern URL on an unconfigured host and a Bandcamp custom domain are rejected before any fetch, and no NewPipe cookie reaches a non-first-party host.
3. `AiExtractionNetwork` against MockWebServer plus fake DNS:
   - redirect to a private IP or non-standard port;
   - mixed public/private DNS answers;
   - a body over 16 MiB, or a compression bomb;
   - `check()` failing mid-body;
   - pool eviction after the scope ends.
4. Relay robustness: a frame over 72 KiB, a non-UUID `request_id`, an 81-character `client_name`, an unknown scope. Expect a per-command refusal or a reconnect, with `enabled` still true.
5. PlayerService tickets:
   - a ticket that expires before Android dispatches it gives the dummy notification and a stop, with no player left;
   - a revoke or deadline between posting and dispatch leaves the queue untouched;
   - with a bound-only service (PlayQueueActivity), the service actually becomes started, or the command returns `NOT_EXECUTED`;
   - a `startForeground` failure leaves no Player behind.
6. AiControlService:
   - Start, then disable before `onStartCommand` runs: no crash;
   - swipe-away with and without PlayerService running;
   - notification dismissal.
7. A burst of 17 distinct clients, and a failed client-registration write: commands are refused or the client is listed, never silently executed.
8. `append_to_queue` on an ended or idle player with `playWhenReady=true` must not resume audible playback, as the doc claims.

## Checked and sound
- Gateway scopes intersected with the local standing scopes. Replays recheck the original command's scopes, and receipts are per client.
- Each receipt and its catalog changes share one Room transaction. The receipt lookup happens before any network access, so a retry neither refetches nor saves twice.
- No network work under Room or the access read lock. Lock order is access, then Room, and the main thread never waits on either.
- Tickets dispatched through Android are one-shot and rechecked at dispatch.
- The process-epoch fence and the clock-skew receipt fix are correct.
- Keystore keys are non-exportable. Local state lives in no-backup storage and fails closed, with Forget as the recovery path.
- The AI Control activity and service are not exported.
- `onTaskRemoved` keeps the original player behaviour when AI Control is off.

`PoTokenProviderImpl`, `ExitActivity` and the Cloudflare gateway were not in scope or not in the snapshot, so they weren't checked.
