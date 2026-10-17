# NewPipe AI Control: short reliability acceptance — independent Claude reviews

**Date:** 2026-10-17
**Branch:** `feature/ai-control`
**Final review verdict:** **PASS for the synthetic gateway/relay reliability acceptance only**. Actual Pixel sleep, Doze, radio handoff, user notification Stop and multihour soak remain outside scope.

Three rounds were run, preserving the earlier BLOCKs to make the review chain auditable. Claude was an independent reviewer and did not edit source, invoke tools, test or deploy. The final adjudication evaluated the fault-injection evidence as provided to it, not by directly executing it. Runnable test sources and logs are described in `../validation/ai-control-reliability-fast-acceptance-2026-10-17.md`.

## Initial reviewer — BLOCK

**Verdict: BLOCK.** There are no P0s, but four P1 assertions are weak enough that a broken implementation would still pass. Each fix is small, mostly tightening assertions, so this stays short acceptance and does not become a soak.

### P1

1. **Device revocation never checks the disconnect.** The test is named "revoking a device disconnects it", but nothing asserts that `ws` closed. It also lacks `try/finally`.
   - Fix: await a close event on `ws` with a code, or check that `get_device_status` returns offline or not-authorized.

2. **The receipt lookup after an uncertain send proves nothing.** `expect(receipt.data.code).not.toBe('OK')` passes on any failure, including `INVALID_ARGUMENT` from a misnamed `original_request_id`.
   - Fix: assert the exact expected code, such as `OUTCOME_UNKNOWN` or `RESULT_NOT_FOUND`.
   - The test also never models the phone committing before the close. Add a case where the handler commits, closes without acking, and then the phone reports the receipt on reconnect. Right now `sends===1` cements "never ask the phone", which may be the wrong design if the phone owns receipts.

3. **Socket replacement skips the stale-close race.** This is the classic bug: the old socket's late `close` marks the device offline even though a newer socket is live. The test closes `old` only in `finally`.
   - Fix: close `old`, then assert the device is still `online`, and that the next command reaches `newer`.
   - Also assert that `old` is closed by the server, and that a result frame sent from `old` is rejected. The name says "fences", but no fence is tested.

4. **The concurrency test has almost no real concurrency.** The synthetic handler acks synchronously, so the in-flight window is near zero.
   - Fix: delay the ack (about 200 ms) so the duplicate arrives mid-flight.
   - Assert the loser's exact outcome (replayed `COMMITTED` or `IN_PROGRESS`), not just `some(COMMITTED)`. A 500 on the second call currently passes.

### P2

- **Grant revocation is too loose.** It checks `tools/list` only. Also check `tools/call` (for example `create_playlist`) returns 401 and the phone receives nothing. For refresh, `status >= 400` would accept a 500; expect 400 with `invalid_grant`.
- **The stale-request test is mislabeled.** It covers only `create_playlist`, with no playback, catalog, or "offline" case. "Without scheduling late work" is checked instantly; wait past the skew window, then re-assert `sends===0`.
- **MCP resources are not covered.** Nothing tests `resources/list` or `resources/read` under a reduced scope, a revoked grant, or a revoked device.
- **Idempotency scoping is untested.** Two OAuth clients, or two devices, sharing one `request_id` should not cross-replay. A concurrent changed-payload race is also missing.
- **Device isolation is one-way.** Nothing tests socket A sending a result for B's command, or A claiming B's `device_id`.
- **Brittle coupling.** `'c_'+digest(...)` reimplements internal key derivation in the test.

### `ws.close()` as a Stop simulation

`ws.close()` sends a clean close frame. Real network loss is usually half-open TCP with no frame, so offline detection depends on heartbeat or ping timeout. **This can be simulated without hardware**: stop answering pings or drop frames, then assert offline within the configured timeout and no redispatch. Add one test. Otherwise "Android network loss" in the comment is overstated.

### Hardware or production only (state as untested, not as covered)

- Real NewPipe Stop and foreground-service teardown order, including whether a received command executes after Stop.
- Doze, process death, Wi-Fi to cellular handover, and real phone clock drift.
- Whether the real Android database commit and receipt persistence survive a crash between commit and ack.
- Production Durable Object eviction or WebSocket hibernation and edge reconnect behaviour, which Miniflare only approximates.

### Note on evidence

Three back-to-back 9/9 runs are not evidence for race-sensitive tests (concurrency, close ordering) while acks are synchronous. Fix P1 #3 and #4 first, then rerun the burst.

## Follow-up reviewer — BLOCK on stale-close test fidelity

# Verdict: **BLOCK**, on one remaining P1 (original finding #3)

Findings #1, #2 and #4 are resolved within what this harness can prove, with P2 residuals below. Finding #3 is not resolved. The test passes, but the code path it names never runs.

---

## P1: Stale-close fencing is never exercised (original #3, still open)

Here is the sequence in `replacing an active socket fences its late close…`:

1. `newer` connects, and the gateway closes `old` from the server side.
2. `eventually(() => old.readyState !== 1)` waits for `old` to reach CLOSING (2).
3. `old.close()` is called as the "stale close".

**Why step 3 does nothing.** Under the WHATWG WebSocket spec, `close()` on a socket that is already CLOSING or CLOSED does nothing. No close frame goes out, and no close event reaches the gateway. The `try/catch` around it hides that it was a no-op.

**Why the handshake doesn't help.** You've stated that Miniflare never finalizes the handshake for server-initiated closes. If so, the gateway's own close handler for `old` likely never fires either. So neither the server-side close event nor the client "stale close" reaches the fencing logic.

**What the assertion actually proves.** `online === true` passes whether fencing is correct, broken, or missing, because no close for the old connection arrives after replacement. Test 1 shows the problem is real. A client-initiated close while OPEN does propagate and does mark the device offline. That is exactly the effect this test claims to guard against, and this test never triggers it.

**Secondary issue.** `delay(25)` is too short to serve as a negative wait even if a close were delivered.

**What would close this P1** (none of these need hardware):
- Run this one test over a real loopback WebSocket (the Miniflare/workerd listening URL with a Node `ws`/undici client) instead of the in-memory pair.
  - Wait for `old`'s actual `close` event and assert its code/reason.
  - Then poll `get_device_status` for a bounded window and require `online === true` throughout.
  - This makes the gateway's real close handler fire for the replaced connection after the new one is registered, which is the real race.
- Or deliver a close for the old connection identity directly to the Durable Object (test-only hook or direct handler call) after replacement.
- Either way, add a negative control: with fencing removed or bypassed, this test must fail. Without that, the test is unfalsifiable.

Related P2: there is no test that a late frame carrying the old `connection_id` is rejected, such as a spoofed `result` for a request routed to `newer`. You can't send that through the in-memory `old` once it is CLOSING (sends are discarded), so it needs the same loopback or direct-injection approach.

---

## Original #1: Device revocation closes the socket (resolved with caveats)

**What is proven.** The client never calls `close()` before the check, so `readyState ∈ {2,3}` does show the Worker initiated a close on revocation. That is the right claim, and the test doesn't overstate it.

**What is not proven:**
- The close handshake completing, or the close code/reason.
- That the gateway dropped the socket from routing. `DEVICE_NOT_AUTHORIZED` with `sends === 0` is equally consistent with an authorization check short-circuiting before routing while a live socket stays registered.

**P2: WebSocket reconnect with the revoked device's existing credentials is not tested.** `/device/pair → 401` covers re-pairing, not re-attaching a socket with a credential that was already issued. A revoked phone reconnecting is the main way revocation fails in practice. Add `connectDevice(d)` after revocation and require rejection, with `get_device_status` and `list_devices` still showing it absent and offline. The loopback approach above would also let you assert the close code.

## Original #2: Commit-before-ack loss with receipt lookup (resolved)

- The client-side `socket.close()` after `mutations++` is a valid gateway-side emulation of "committed, ack never arrived".
- The repeat returns `CONNECTION_LOST` with no redispatch (`mutations === 1` against a handler that would count it).
- The lookup is forwarded once, with the correct `original_request_id`, and the response is relayed.

**Caveat.** The receipt is fabricated by the synthetic handler, so this proves gateway forwarding and no-replay. It does not prove the receipt is durable (see the hardware list).

**P2 residuals:**
- No test of the other branch, where the device reports the original as not found or not executed.
- No test of a mismatched `original_request_id` in the device's response being rejected.
- No test that client B cannot run `get_command_result` against client A's `request_id`. This ties into the UUID-scoping item below.

## Original #4: Identical write during a pending ack (resolved)

- `arrived` resolves only after real dispatch.
- The 250 ms hold puts the second call in a genuine in-flight window.
- `AWAITING_DEVICE_RECEIPT`, `sends === 1`, the cached replay and `REQUEST_ID_REUSED` are all asserted.

**Flake risk.** If the second RPC takes over 250 ms on a slow runner, it sees `COMMITTED` and the test fails loudly. That is a flake, not a false pass.

**P2 residual.** The window before the request is reserved is untested. Two identical calls fired together, before either is dispatched, would exercise check-then-reserve atomicity. That is safe if dedup lives in a single Durable Object with no non-storage `await` between check and reserve, and otherwise it is a real double-effect risk. `Promise.all([call(args), call(args)])` with `sends === 1` is cheap to add.

## The other P2s from the previous review

| Area | Status |
|---|---|
| Refresh revocation | Resolved: 401 on `tools/list` and `tools/call`, `invalid_grant`/400, no token issued. Minor: no check that another client's grant survives (over-revocation). |
| Stale playback | Resolved: 61 s returns `EXPIRED`, which is under the 300 s catalog window, so it does show a tighter playback window. Future skew is only tested on `create_playlist`. |
| Client UUID scoping | Resolved at the gateway: two commits with distinct `client_id`s, each replayed independently. Not proven: cross-client receipt lookup isolation (above), and Android-side receipt keying (below). |

---

## Hardware/real-environment gaps (correctly UNTESTED, not blocking)

- Real screen lock, Doze, App Standby buckets, and OEM background killers affecting the relay service.
- Real Android Stop, force-stop, process death and reboot. In particular, process death between the local commit and the ack, and whether the on-device receipt survives it so `get_command_result` can return `COMMITTED`.
- Whether the Android receipt store is keyed by `(client_id, request_id)` rather than `request_id` alone. The synthetic phone never dedupes, so the same-UUID test can't detect a phone that collapses two clients' writes.
- Cellular/Wi-Fi handoff, NAT rebinding, and real network loss without FIN.
- Production Cloudflare edge behavior: Durable Object eviction or hibernation during an in-flight request, and real close-handshake timing.
- Real device clock drift against the `issued_at_ms` windows.

**One item listed as hardware-only is partly testable now.** For half-open TCP, the gateway's detection logic (heartbeat/ping timeout leading to offline, then pending requests resolving to `OUTCOME_UNKNOWN`) can be tested synthetically with a device socket that stays open but stops answering. Only the real network's timing needs hardware. Treat it as a P2 gateway gap, not hardware-only.

## Final adjudication — PASS after local red/green fault injection

# Verdict: **PASS.** The P1 is closed.

This rests on the evidence as you reported it. I did not read the test or run anything, as instructed.

## Why the mutation result closes the P1

The P1 was that the late old-socket `webSocketClose` path might never run, so a PASS would prove nothing. The red/green control answers that directly:

- **The handler runs.** A mutant that only acts inside the old socket's close handler changed what the test saw. The replacement went to `readyState` 2 instead of 1 at line 79.
- **The assertions detect wrong fencing.** The test went red under the mutant and green on restored source.
- **The setup is real enough.** Miniflare loaded the rebuilt bundle, since the mutant's effect showed up. The path uses real loopback TCP and WebSocket frames.
- **The source restore is consistent.** The status snapshot shows no `gateway/src` changes, only README, package files and test files.

## Non-blocking follow-ups

1. **Close-ordering race.** The client sees its `close` event (1000 Replaced) before the server has necessarily finished `webSocketClose`. The mutant was caught at line 79 in your run, but on a faster or slower machine it could slip past. Make sure the presence poll and command check wait until server-side close handling is actually done, not just the client event. Running the mutant several times would show whether it is caught reliably.
2. **A second, subtler mutant.** Try one where the old close handler clears presence or routing by device ID instead of socket identity, while leaving the new socket open. Your presence and command assertions should catch it. A one-time red run would confirm that.
3. **Package changes.** `package.json` and `package-lock.json` are modified, probably to add a WebSocket client dependency. Confirm it is dev-only.

## What this does and does not prove

**Proven (gateway synthetic acceptance):** Given a clean close frame arriving late from a replaced socket, the DeviceRoom keeps the replacement open, marks it present, and routes commands only to it. This covers loopback in Miniflare, with a synthetic signed identity.

**Not tested (real device and network behavior):**

- **Android screen sleep and Doze.** Timers, heartbeats and network access are deferred or suspended. The socket may stall silently rather than close.
- **WiFi to cellular handoff.** The IP changes and a new TCP connection starts. The old socket usually never sends a close frame, so this depends on server heartbeat or idle timeout, not the close handler tested here.
- **Half-open TCP.** No FIN or RST arrives, so `webSocketClose` may fire minutes later or never. Cloudflare edge and hibernation timing differ from Miniflare.
- **Stop notification.** The foreground-service stop path is untested: whether the app sends a clean close, stops reconnecting, and leaves presence consistent.

These need on-device or deployed-edge validation. Label them as untested in the acceptance doc, not covered by this PASS.
