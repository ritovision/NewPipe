# NewPipe AI Control gateway

Private, single-owner Cloudflare MCP gateway for independently paired NewPipe Android devices. The Worker is publicly reachable over HTTPS, but catalog and playback tools require OAuth authorization and device-scoped permission. NewPipe owns the Library and executes commands. No tunnel, public phone port, desktop relay or duplicate cloud Library is used.

## Deployment and one-time owner setup

Worker: `ritovision-newpipe-ai-control`

MCP resource: `https://ritovision-newpipe-ai-control.matthewjpellerito.workers.dev/mcp`

Owner setup: `https://ritovision-newpipe-ai-control.matthewjpellerito.workers.dev/setup`

The Worker and Durable Object bindings have been deployed. Its origin is configured, but every route deliberately returns `503 SERVER_NOT_CONFIGURED` until both authentication secrets are installed. This is not a working ChatGPT integration yet.

An attempted tool-mediated secret installation was blocked by OpenAI. The owner can perform the one-time installation in the secondary computer's WSL terminal:

```bash
python3 /home/rito/techstuff/ritoverse/newpipe-ai-control/gateway/scripts/configure-owner-secrets.py
```

The script uses the existing Wrangler login, reads the owner-only file `~/.local/share/newpipe-ai-control/owner-credentials.json`, and uploads only `OWNER_KEY_HASH` and `SESSION_SECRET`. It does not print their values. The raw owner key remains in that private file, outside this repository. Do not commit it, expose it in a screenshot, or place it in a URL.

After installation, initial owner setup and client OAuth consent use that owner key. The setup page can issue an expiring one-time pairing code, show authorized grants, revoke a device/client, and remove an emergency client block. This setup is separate from individual AI actions: once granted, catalog deletions execute directly without an extra approval page.

Before regular use, complete the live integration test and final review gate recorded in `../doc/architecture/ai-control-integration.md`.

## Android enrollment and ChatGPT connection

On the separate QA NewPipe installation, open **AI Control** from the main menu. Enter the gateway HTTPS origin, a friendly device name, and an owner-issued one-time pairing code. Select the local permission ceiling, pair, then explicitly start AI Control. A foreground-service notification provides Stop. The Android private key never leaves Keystore, and pairing/settings do not enter NewPipe backups.

Configure the private ChatGPT plugin/app with the MCP resource above and OAuth. OAuth discovery and dynamic client registration are implemented; consent includes only the requested scopes. Destructive scopes are off until deliberately granted. Local Android permissions and the OAuth grant must both allow an operation. Every device operation requires its exact device ID; discovery is the exception.

Do not install an AI QA APK over the existing `custom/dev` or stock/F-Droid installation. AI-era schema-10 exports cannot be imported into older schema-9 NewPipe builds. Keep pre-upgrade backups separate.

## Development and validation

```bash
cd gateway
npm ci
npm test
npm run deploy
```

`npm test` first runs TypeScript checking and creates a fresh Wrangler dry-run bundle, then runs Vitest/Miniflare tests against that bundle. It never silently tests an older `dist` output. Miniflare is the local Cloudflare JavaScript runtime, not an Android emulator; tests run without a desktop UI.

Tests cover OAuth/PKCE consent, browser-bound and purpose-separated proofs, grant scoping/revocation, proof-of-device-key pairing, revoked pairing retries, expected-error handling without Durable Object reset, device/client isolation, immutable mutation identities, explicit offline/no-replay, ambiguous disconnect outcomes, frame size limits, versioned client-policy propagation and fail-closed configuration.

## Data and outcome rules

Cloudflare keeps minimal account/device/grant metadata, transient presence and bounded request receipts. It does not maintain playlists or a replicated Library. Native catalog mutations and receipts commit atomically in one Room database; playback is process-scoped and cannot promise an atomic database/media effect. An uncertain dispatched outcome is `OUTCOME_UNKNOWN`; it is not automatically replayed.

Mutation callers keep both `request_id` and `issued_at_ms` unchanged for retries. Command-admission freshness is distinct from the bounded deadline created when the device accepts dispatch. An expired or disconnected command cannot become newly executable on reconnect. Historical results are bounded; missing evidence is not proof that an operation never happened. Query `get_command_result` for the same client/request instead of inventing a new action.

## Known boundaries

- No guarantee of permanent reachability through force-stop, reboot, Doze, revoked notifications or network loss. Presence expires; offline metadata and playback actions are not queued.
- The per-device receipt cap is shared by its authorized clients. At capacity, new writes fail closed; this is not per-client availability isolation.
- Trusted source-host restrictions apply to AI URL extraction/playback: supported first-party service domains and explicitly configured PeerTube instances. Arbitrary custom domains/private-network sources are rejected. Catalog organization of already-saved items remains available. Ordinary manual NewPipe playback is unchanged.
- Source-only reviewer verdicts are not evidence of real Cloudflare-to-phone or ChatGPT end-to-end execution. Those tests require completed owner setup.
