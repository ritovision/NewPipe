# NewPipe AI Control — Short reliability acceptance (2026-10-17)

**Branch:** `feature/ai-control` (QA candidate; not yet merged)
**Intent:** Close the fast, repeatable, nondestructive portion of v1 reliability validation. The long-running session/soak test is explicitly deferred.

## Harness and boundaries

- Runner: `cd gateway && npm run qa:reliability` on BUILD-ONLY NotCodex-2. This uses TypeScript check, Wrangler **dry-run**, and Vitest/Miniflare with ephemeral Durable Objects, OAuth KV, signed synthetic Android WebSockets, and fake OAuth clients. The harness never issues mutations to the deployed Cloudflare Worker or any actual Android Library. Two additional tests create a real TCP WebSocket listener bound only to `127.0.0.1` and use independent synthetic Android identities to test actual close-handshake timing.
- The run is an integration simulation of the **real gateway implementation**, not a genuine Android OS, modem, battery saver or ChatGPT UI. `ws.close()` models a clean loss of the Android relay socket at the gateway boundary; it does not invoke Android's `AiControlService` Stop action or reproduce a half-open TCP failure. For in-memory sockets, Miniflare may keep server-initiated closes at `CLOSING` (readyState 2). The separate **real localhost WebSocket** integration test verifies that the previous socket actually receives a close event (`1000 Replaced`) **after** the new authenticated connection is ready, and that the replacement remains online and exclusively receives commands. This still does not emulate a half-open TCP link.
- The short suite complements prior isolated Android instrumentation and the user's demonstrated real ChatGPT→Cloudflare→Pixel playlist and playback interactions. It does not replace physical Android mobility/Doze tests.

## Executed evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| Live-to-offline presence transition and reconnect | PASS, simulated | `reliability-acceptance.test.mjs` disconnect test |
| Commands first received offline never replay on rejoin | PASS, simulated | Persisted receipt tombstone + new-session retest |
| Commit followed by lost acknowledgement reports `OUTCOME_UNKNOWN` | PASS, simulated | Mid-flight link close; original UUID not redispatched |
| Explicit device receipt lookup reconciles committed command after reconnect | PASS, simulated | `get_command_result` handled by fake signed Android client |
| Replacing active WebSocket fences old connection | PASS, **real loopback WebSocket** | Old client receives `1000 Replaced` after new device is ready; new stays online, old receives zero commands |
| Concurrent identical mutation at-most-one dispatch | PASS, simulated | Forced 250ms in-flight acknowledgement; second gets `AWAITING_DEVICE_RECEIPT` |
| Reusing request ID with altered argument rejected | PASS, simulated | `REQUEST_ID_REUSED` |
| Revoked OAuth grant invalidates access and refresh credentials | PASS, simulated | Read/tools return 401, refresh returns 400 `invalid_grant` |
| Device revocation rejects dispatch and repeat pairing | PASS, simulated and **real loopback** | Actual close `1008 Revoked`; old correctly signed identity denied fresh challenge |
| Narrow OAuth scopes block unauthorized writes and deletes | PASS, simulated | Read-only scope against fully capable fake phone |
| Too-old and future-dated writes refused before dispatch | PASS, simulated | `EXPIRED`, `CLOCK_SKEW`, old UUID tombstone |
| Two devices never receive each other's commands | PASS, simulated | Explicit device_id, one device offline |
| Two OAuth clients sharing a UUID do not cross-replay receipts | PASS, simulated | Two client IDs produce independent authenticated dispatches |
| All gateway tests together | **45/45 PASS** | `~/.cache/newpipe-ai-control/reliability-final-all-20261017.log` |
| Strengthened short acceptance burst | **12/12 PASS in 3 consecutive runs** | `~/.cache/newpipe-ai-control/reliability-final-repeat-{1,2,3}-20261017.log` |
| Gateway TypeScript and Worker packaging | PASS | `npm test` / Wrangler dry-run (no live deploy) |
| Android JVM test reports | **290/290 pass**, Gradle task UP-TO-DATE | Verified 34 XML files, 0 failures, 0 errors, 0 skipped |
| Android style checks and APK builds | PASS, Gradle up-to-date | `~/.cache/newpipe-ai-control/reliability-native-jvm-20261017.log` |
| ChatGPT remote client current presence probe | Observed **offline** at test time | Read-only `list_devices` on connected NewPipe plugin; not a pass/fail for gateway simulation |

**No data clears, physical phone mutations, real client revocations, or new Cloudflare deployments were performed.** No Android QA APK installation occurred in this acceptance session.

## Stale-close fault injection (test validity)

An independent Claude Opus review correctly noted that a client seeing close code
`1000 Replaced` does not **by itself** prove the server's old-WebSocket-close
callback ran before the test finished. To demonstrate that the regression
really detects the stale-close bug, a controlled temporary mutation was made
**only** in the local copy of `gateway/src/device.ts`:

```ts
// DELIBERATE TEST-ONLY FAULT inside DeviceRoom.webSocketClose(ws):
for(const other of this.ctx.getWebSockets())
  if(other!==ws) this.safeClose(other,1000,'QA-FAULT-INJECTION');
```

- A Wrangler **dry-run** bundled the intentionally broken local gateway.
- Running only the `old-device late WebSocket close` network test returned an
  **expected RED**: new socket `readyState` was `2` (CLOSING) rather than `1`
  (OPEN), at the exact replacement-socket assertion.
- The Python fault-injection script restored the original `device.ts` contents
  byte-for-byte in `finally`, and rebuilt the original Worker bundle.
- `git diff --exit-code -- gateway/src/device.ts gateway/src/index.ts gateway/src/auth.ts`
  found **no production source changes**. The restored `npm test` suite
  returned **45/45 GREEN**.
- Evidence: `~/.cache/newpipe-ai-control/reliability-stale-close-negative-control-20261017.log`,
  `~/.cache/newpipe-ai-control/reliability-stale-close-original-rebuild-20261017.log`,
  and `~/.cache/newpipe-ai-control/reliability-final-restored-green-20261017.log`.

The deliberate mutation was never deployed to Cloudflare or a phone. This
red/green test proves the simulated stale-close callback is exercised and the
network test is sensitive to a bad implementation; it does **not** validate
real network handoff or Android power management.

## Independent review closure

- Claude Opus initial short-harness review: BLOCK (weak receipt and concurrency assertions, missing close race).
- After strengthening gateway receipts, in-flight duplicate concurrency, revocation and scope tests, Claude follow-up: BLOCK **only** on whether the stale close callback had truly executed.
- The local deliberate fault-injection run turned the specific stale-close test RED, then a byte-for-byte source restore + original bundle rebuilt it GREEN (45/45).
- Claude final read-only adjudication based on this documented red/green evidence: **PASS**, synthetic gateway reliability P1 closed. Full review history: `../reviews/ai-control-reliability-fast-claude-2026-10-17.md`.

This verdict is limited to short synthetic gateway/relay acceptance, not a complete physical Android or production-edge reliability guarantee.

## Hardware-only gates not claimed complete

1. **Android screen lock / Doze / manufacturer power management:** The foreground service may be stopped/throttled despite successful virtual WebSocket heartbeat; needs real Pixel screen-off observation. A two-minute screen-lock check is *not* a full Doze soak.
2. **Wi-Fi ↔ cellular switch:** The gateway's socket-loss/reconnect behaviour is covered, but Android's actual mobile radio/network callbacks and network-specific reconnect are not. Requires device network handoff.
3. **Real Stop/Start and notification lifecycle:** The synthetic close tests gateway loss semantics; true Android Stop/Start action and visible notification require device test. Prior isolated Android lifecycle instrumentation covered start/stop races but is not equivalent to this exact live user flow.
4. **Half-open TCP / missing heartbeat pong:** A clean WebSocket close and a real local close handshake are tested, but dropping frames without either side receiving a close is not. Real Android heartbeat expiry at 70 seconds and gateway presence expiry at 90 seconds were inspected but not exercised in this fast acceptance.
5. **Force-stop / reboot / battery saver / multihour durability:** Not run. Explicitly omitted from the short acceptance. Per product spec, foreground-service connectivity is not guaranteed through force-stop or reboot.
6. **ChatGPT host reconnect and consent UI:** The gateway OAuth refresh/revoke flows are tested independently with a synthetic client. Real ChatGPT app token persistence/UI state is platform-controlled and not conclusively validated by this harness.

### Short optional owner-facing phone spot check (nondestructive)

When the phone is available, on the separate `org.schabi.newpipe.debug.featureaicontrol` QA app only:

- Enable AI Control using its app screen. Confirm foreground notification and `list_devices` online in ChatGPT. Do **not** test with stock/custom real NewPipe or change grants.
- Lock the phone briefly and run a read-only `get_device_status` / `get_now_playing`; distinguish simple screen-lock from deep Doze.
- While AI Control is running, switch Wi-Fi to cellular, observe temporary offline/automatic reconnect, and make a **new read-only query**. Repeat switch back. Avoid remote writes during an uncertain network transition.
- Tap **Stop AI Control** from the QA foreground notification, then check `list_devices` offline and confirm a subsequent read is rejected rather than queued. Manually Start again from the AI Control screen and verify device presence returns without re-pairing.
- **Do not revoke a real ChatGPT grant or globally delete Library items as part of this spot check.** Grant/device revocation is already validated with ephemeral identities.

### Testing location / execution decision

- NotCodex-2 remains **BUILD ONLY**; no emulators, GUIs or phone operations there.
- Primary NotCodex at acceptance time had no Android emulator binary or AVD. Wireless ADB to the Pixel was `offline`. Bootstrapping an entire emulator for a fast gate was not justified and would not replicate real Doze/cellular behaviour anyway.
- The ChatGPT plugin is optional for exercising the test harness, but useful for the final **read-only live phone spot check**. A custom OAuth/MCP test client can also exercise these endpoints without ChatGPT. The final network socket tests use `ws@8.21.0` as an explicit development dependency, on a loopback-only disposable port.

## Decision

**Automated short reliability acceptance: PASS for all covered gateway/relay paths.**

**Final physical background-reliability acceptance: PENDING / optionally waived by the owner**, because it could not be executed with a reachable Android target in this session. A long soak is separately deferred at the owner's request. Do not label hardware sleep, cellular handoff or Android service Stop as verified based on simulated WebSocket closes.
