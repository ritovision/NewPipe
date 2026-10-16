# Independent review — preserved raw response

Source-only review; reviewer did not code. Author corrections are recorded separately.

## Verdict: BLOCK

I found no direct authorization bypass in the source. Still, findings 1–5 should be fixed before the endpoint is exposed:
- Anyone on the internet can reset both Durable Objects (one route needs no secret at all; the other needs only a device ID that any connected client can list).
- Commands whose outcome is uncertain are not reported as uncertain, so the model may run them twice.
- A device that was just revoked can reinstate itself.
- The consent page grants delete permissions by default.

I read all of `src/`, `test/`, `accepted-spec.md`, `wrangler.jsonc` and `package*.json`. `node_modules` and `dist` aren't in the snapshot, so library behavior is listed separately in section B rather than verified.

### A. Verified source findings (ordered)

1. **High: unauthenticated callers can reset Durable Objects.** Cloudflare documents that an exception thrown inside a `blockConcurrencyWhile` callback terminates and resets the object. The outer `try/catch` does not prevent that.
   - **`src/account.ts:30-40`:** `/device/pair` with any self-signed key and a random 24-character code throws at `:32` and resets the account object. No secret is needed.
   - **`src/device.ts:76-79`:** `/device/connect` with a known device ID and a bogus nonce throws at `:79` and resets that device's object.
     - All in-flight commands are lost: callers get transport errors, their receipts stay `AWAITING_DEVICE_RECEIPT`, and late replies are discarded.
     - The phone's socket may also drop.
     - Device IDs are visible to any client with `mcp.connect` through `list_devices`.
   - **Same pattern elsewhere:**
     - Throws at `device.ts:33`, `:61` and `:108` (the last is currently unreachable) and at `account.ts:34`, `:38`, `:47`.
     - Unguarded `close()` loops at `device.ts:42` and `:81`.
     - The receipt-cleanup alarm (finding 8).
   - **Cross-object calls inside the critical section:** `account.ts:37` and `:48` wait on the device object while blocking. That stalls the `/list` lookup every tool call depends on, and hitting the 30 s timeout also resets the object.
   - **Remedy:**
     - Never throw out of the callback: return a result value and respond after it finishes.
     - Check the nonce and signature outside the block, then re-check and consume the challenge inside it.
     - Move cross-object calls out of the block by recording the device as pending first and marking it active afterwards.

2. **High: failures after a command is accepted aren't reported as `OUTCOME_UNKNOWN`.** At `src/mcp.ts:49-52`, if the device object throws or is aborted (a reset from finding 1, a deploy, an eviction) or returns a non-JSON body, the client gets `INVALID_OR_UNAVAILABLE_REQUEST`. The model can read that as "not executed" and retry with a fresh `request_id`, running the command twice (for example a duplicate `create_playlist` or a restarted `play_items`).
   - **Remedy:** for write tools, map any exception, non-JSON or 5xx response to `OUTCOME_UNKNOWN` with the `request_id`, and say to retry with the same ID or use `get_command_result`. Report "not executed" only for explicit rejections that happen before the command is accepted.

3. **High (correctness): the 20 s window from the model-supplied `issued_at_ms` will make real writes fail, and the failures are misreported.** Locations: `src/device.ts:118-119` and `:135-136`, `src/tools.ts:10`, `src/mcp.ts:21` and `:42`.
   - The gap between the model reading `server_time_ms` and sending the write (model reasoning, plus any ChatGPT confirmation prompt) commonly exceeds 20 s. Every write is annotated `destructiveHint:true`, which makes such prompts more likely.
   - A first-seen stale request is answered `OUTCOME_UNKNOWN` and saved as a tombstone, even though having no receipt within the 24 h retention proves it was never accepted.
   - The server instructions then forbid retrying with a fresh ID, so the model is stuck.
   - **Remedy:**
     - Return `NOT_EXECUTED` for first-seen requests whose timestamp is still within retention.
     - Accept an older `issued_at_ms` (minutes, still far below retention; shorter for playback), and compute the phone's `expires_at_ms` from the time the gateway accepts the command.
     - Set `destructiveHint` only on genuinely destructive tools.

4. **Medium-High: a pairing retry un-revokes a device.** At `src/account.ts:32-39` and `src/device.ts:31-35`, re-posting the original signed pairing request within the code's 5-minute lifetime takes the "identical retry" branch and writes `revoked:false` back into both objects. A wrong or lost phone revoked right after pairing can reinstate itself.
   - **Remedy:**
     - Make a retry return the earlier result without writing anything.
     - Never overwrite a revoked registration, and invalidate pairing codes bound to a device when it is revoked.
     - Require a new code plus a new device ID and key to re-enroll.

5. **Medium: consent grants every scope by default, including deletion, regardless of what the client requested.** `src/auth.ts:44` renders all scopes pre-checked and `:53` grants whatever is submitted. This contradicts the spec (line 100: `library.delete` is "off by default until explicitly granted"). A single phished consent through an attacker-registered client gets full deletion power.
   - **Remedy:**
     - Show only the scopes the client requested that you also support.
     - Leave `library.delete` and `playlist.delete` unchecked by default.
     - Consider allowlisting redirect hosts such as ChatGPT's callback.

   This is a one-time grant default, not a per-action confirmation.

6. **Medium: revoking a client is incomplete and can permanently lock out a legitimate client.** Locations: `src/account.ts:53-62`, `src/auth.ts:84` and `:97`, `src/mcp.ts:13`.
   - There is no list of clients or grants. The owner has to compute `c_<sha256(client_id)>` by hand.
   - Access and refresh tokens stay valid; only tool calls are blocked.
   - Blocks are permanent with no way to undo them, and capped at 128. A client that uses the same `client_id` every time stays blocked even after you approve it again.
   - **Remedy:** list and revoke grants through the provider's helpers, show clients on `/setup`, and keep a reversible block list for emergencies only.

7. **Medium: presence never goes stale.** At `src/device.ts:17`, `:49-50` and `:115-120`, "online" just means an open socket that has sent its hello. The automatic ping/pong replies never update `seenAt`. A dead connection the server hasn't noticed still shows online. Commands sent into it wait 21 s and return `OUTCOME_UNKNOWN` instead of failing promptly with `NOT_EXECUTED` (spec lines 19 and 112).
   - **Remedy:** compute liveness from the later of `seenAt` and `ctx.getWebSocketAutoResponseTimestamp(ws)` with a time limit. Apply it in both presence and dispatch, and close stale sockets.

8. **Medium: receipt cleanup likely breaks under load.** At `src/device.ts:196-203`, a single `storage.delete(expired)` call with more than 128 keys exceeds the documented batch limit and throws inside the block. That resets the object on every alarm retry, and cleanup then stops.
   - Receipts pile up until `receipt_count` reaches 10,000. From then on every write to that device returns `RECEIPT_CAPACITY` (`:112-113`).
   - Capacity is shared by all clients and also consumed by commands the gateway itself rejects, so one client can exhaust it.
   - **Remedy:** store receipts with `ctx.storage.sql` (indexed time column, range delete, real count), or delete in chunks of at most 128. Add per-client quotas, and make the alarm unable to throw out of the block.

9. **Medium-Low (latent): the hello can exceed the 2 KiB socket attachment limit.** `src/device.ts:157-162` allows about 5.5 KB (64×64-character capabilities plus 16×64 permissions) and stores unrecognized fields as-is. When `serializeAttachment` throws, the socket is closed as invalid and the phone reconnects in a loop. Today's ~25 capabilities fit, but versioned or tag capabilities will grow past it.
   - **Remedy:** store only the validated fields and keep the total well under 2 KiB, or keep the hello in storage under the connection ID.

10. **Medium-Low: `get_command_result` needs no permission and isn't tied to the calling client at the gateway.** Its scope list at `src/tools.ts:36` is empty, so the checks at `mcp.ts:46`, `device.ts:97` and `device.ts:122` all pass. Any client with only `mcp.connect` can look up other clients' results, and isolation relies entirely on the Android side.
    - **Remedy:** require a device scope (ideally the original command's), and only forward when the caller's own receipt `r:<callerClientId>:<id>` exists; answer from that receipt first.

11. **Low-Medium: batched requests can multiply calls past the per-client rate limit.** `src/mcp.ts:20` counts HTTP requests, but one 64 KiB POST could carry hundreds of tool calls if the SDK still accepts JSON-RPC arrays (see B3).
    - **Remedy:** reject array bodies before the transport, or count each call.

12. **Low: device revoke ignores the device object's reply.** At `src/account.ts:48-49`, a failed revoke still marks the account entry revoked while the device stays connectable. Separately, `/pair` writes the account entry last (`:39`), so a failure partway through can leave a registered device that `/setup` can't show or revoke.
    - **Remedy:** check the reply, make revoke safe to retry, and write the account entry first as pending.

13. **Low: no way to pass a preview digest.** The strict schema at `src/tools.ts:35` has no optional expected-state digest, so the spec's "reject stale preview" rule (line 104) can't be expressed. Add one and forward it to the phone.

14. **Low: hardening.**
    - **URLs:** any scheme or host is accepted (`tools.ts:6`, `:37`, `:38`, `:42`), including `file:`, `intent:` and IP literals. Restrict to `https:` with public hostnames.
    - **Retry matching:** `canonical()` sorts keys with `localeCompare` (`shared.ts:80`). A runtime change across deploys could make identical retries look like a reused ID; sort by plain code-unit order instead.
    - **Late replies:** a real result that arrives after the timeout is discarded (`device.ts:170-171`). Optionally use it to upgrade an `OUTCOME_UNKNOWN` receipt from the same connection.
    - **Spoofable names:** device and client names allow invisible bidirectional/formatting characters (`account.ts:24`). The client's self-chosen name is forwarded to the phone (`device.ts:144`). Strip those characters (Unicode category `Cf`).
    - **Device cap:** revoked devices still count toward the 16-device limit (`account.ts:34`).
    - **Unneeded exposure:** `connection_id` is returned to clients (`device.ts:50`).
    - **Owner key:** it is the only factor for admin and consent, behind an approximate per-IP limit (`index.ts:51-52`). Require a randomly generated key and add a global back-off on failed attempts.

### B. Library and platform assumptions I couldn't verify

The installed packages aren't in the snapshot, and OAuth Provider v1 is newer than my knowledge.

1. **workers-oauth-provider 1.2.3**
   - Whether the consent helpers bind the handle to the cookie and client and allow only one use. The tests cover only the success path and same-handle reuse.
   - Whether `AuthorizationError.redirectTo` is only ever an already-validated redirect.
   - The shape of `ctx.auth.scope`, and whether it reflects narrowed refresh-token scopes.
   - Whether `completeAuthorization` issues the scopes the owner chose or intersects them with what the client requested. If ChatGPT requests only what the 401 challenge advertises (possibly just `mcp.connect`), every device tool would return `PERMISSION_DENIED`.
   - Whether the options `requiredScopes`, `allowTokenExchangeGrant`, `clientIdMetadataDocumentEnabled`, `cookiePrefix`, `resourceMetadata` and `scopesSupported` are real. The wrangler/esbuild bundle doesn't type-check, so a wrong name would be silently ignored. Run `tsc --noEmit` and test that token exchange is rejected.
   - Size, timeout and redirect limits when it fetches client metadata documents.
   - Whether expiry of a client registration after 7 days kills live grants, which could break ChatGPT weekly.
   - Whether it reflects any `Origin` in CORS headers; the gate at `index.ts:43-45` covers this today.
2. **Cloudflare runtime** (documented as of my knowledge; confirm for your compatibility date and for SQLite-backed objects):
   - An exception in `blockConcurrencyWhile` resets the object, and the block has a 30 s timeout.
   - `serializeAttachment` is limited to 2,048 bytes.
   - Batch storage get/put/delete is limited to 128 keys.
   - Deploys reset objects and disconnect sockets.
   - Outgoing `ws.send` is held until the receipt write is durable. The no-replay design depends on this.
   - Automatic ping/pong replies don't wake the object or update the attachment.
   - Calling `close()` on a socket that is already closing may throw.
   - The rate-limit binding is approximate and per-location.
3. **MCP SDK 1.32.1**
   - Whether stateless JSON mode resolves `handleRequest` only after all responses are ready, given `server.close()` runs in `waitUntil`.
   - Whether batched arrays are still accepted (finding 11).
   - Whether a zod v4 strict object used as an input schema produces `additionalProperties:false`.
4. **Android behavior the gateway relies on**
   - Enforce `expires_at_ms` using a clock offset from `server_time_ms`.
   - De-duplicate on client ID plus request ID with a fingerprint.
   - Limit `get_command_result` lookups to the calling client.
   - Validate URLs, and treat the client name as untrusted.

### C. Tests

The test helper loads a prebuilt `dist/index.js` (`test/helpers.mjs:10`), and `npm test` has no build step (`package.json:8`). The 11 passes therefore may not reflect this snapshot. Add a pretest build and a `tsc --noEmit` gate.

Missing tests:
- reset attempts through `/device/pair` and `/device/connect`
- revoke followed by a pairing retry
- consent without the cookie, with someone else's cookie, and with plain (non-S256) PKCE
- an expired first-seen write
- the device object failing mid-command
- a stale socket
- more than 128 expiring receipts
- an oversized hello
- `get_command_result` across clients
- batched requests

### D. Sound as written (keep)

- **Routing and input:**
  - Internal device-object routes can't be reached from outside.
  - The `Origin` check and the request-body size limits run before any parsing.
- **Device keys and signatures:**
  - Strict signature decoding and P-256 key import.
  - The pair, challenge and connect messages each have their own prefix and include the origin.
  - Pairing codes are 144-bit, stored hashed, and bound to the first device that uses them.
  - Connect nonces are single-use and last 30 s.
  - Frames from replaced connections are rejected.
- **Receipts and no-replay:**
  - The receipt is saved before the command is sent.
  - Offline and expired commands are recorded as tombstones and never sent later.
  - Reusing a request ID for a different command is rejected.
  - Stored receipt data is limited to an allowlist.
- **Web pages:**
  - HTML is escaped, with a CSP that blocks framing.
  - The admin cookie is `__Host-`, `SameSite=Strict`, with a CSRF nonce.
- **Configuration and permissions:**
  - Missing configuration fails closed.
  - Scopes are checked in both the Worker and the device object, on top of the phone's own permission limit.
