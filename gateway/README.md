# NewPipe AI Control gateway

Private, single-owner Cloudflare MCP gateway for independently paired NewPipe Android devices. The Worker is publicly reachable over HTTPS, but catalog and playback tools require OAuth authorization and device-scoped permission. NewPipe owns the Library and executes commands. No tunnel, public phone port, desktop relay or duplicate cloud Library is used.

## Passkey source candidate (2026-11-09; not deployed)

The feature branch implements passkey-first owner login, one-time owner-key bootstrap/emergency recovery, revocable one-hour owner browser sessions, and explicit OAuth consent. The production gateway has **not** been replaced by this work. See [Phase B adjudication](../doc/reviews/ai-passkeys-phase-b-adjudication-20261109.md) and the [progress record](../doc/validation/ai-passkeys-progress-20261109.md) for remaining acceptance gates. Android no-copy pairing is now implemented in the same source candidate, with separate native/browser acceptance and live rollout gates. See the [pairing architecture](../doc/architecture/ai-control-browser-pairing.md).

After rollout, open `/setup`, use **First setup or emergency recovery** once with the protected existing owner key, and add a passkey. Returning owners use **Sign in with passkey**; an authenticated browser can review a new client's OAuth permissions without retyping the key. Passkeys authenticate the owner; successful login does not automatically approve any OAuth client. The browser/device handles fingerprint, PIN or supported unlock; no biometric data reaches this gateway.

Adding/removing passkeys requires fresh verification. Removing the last passkey additionally requires fresh recovery-key verification and explicit confirmation. Owner-session reset does not revoke already issued OAuth grants or paired devices: their existing revoke controls remain separate. Old signed admin cookies are intentionally not accepted by the new server-backed session format. Existing device identities and OAuth records are not rewritten.

Browser authentication uses pinned SimpleWebAuthn libraries, a local bundled script (no CDN), single-use challenges and explicit CSRF/origin checks. The passkey RP ID is the **exact configured gateway hostname**; changing it after enrollment requires a deliberate credential migration. `npm run check` and `npm run build` generate the ignored `.generated/` browser asset; tests always rebuild before execution.

## Deployment and owner recovery

Worker: `ritovision-newpipe-ai-control`

MCP resource: `https://ritovision-newpipe-ai-control.matthewjpellerito.workers.dev/mcp`

Owner setup: `https://ritovision-newpipe-ai-control.matthewjpellerito.workers.dev/setup`

**Current deployment status (2026-10-17):** The Worker, Durable Object bindings and owner authentication secrets have been configured. An authorized ChatGPT MCP client has reached the paired isolated QA Pixel via the live gateway. `SERVER_NOT_CONFIGURED` / HTTP 503 remains its intentional fail-closed behavior only if configuration secrets are missing; that is not the current normal deployment state. The October 17 source checks used only Wrangler **dry-run**, not a replacement production deployment.

**Fresh-gateway setup/disaster recovery only:** From inside this `gateway/` directory, the owner-secret script is:

```bash
python3 scripts/configure-owner-secrets.py
```

Do not rerun the script just to update NewPipe on Android. It changes only gateway secrets and is unnecessary for the already configured Worker.

The script uses the existing Wrangler login, reads the owner-only file `~/.local/share/newpipe-ai-control/owner-credentials.json`, and uploads only `OWNER_KEY_HASH` and `SESSION_SECRET`. It does not print their values. The raw owner key remains in that private file, outside this repository. Do not commit it, expose it in a screenshot, or place it in a URL.

In the legacy deployment, initial owner setup and client OAuth consent use that owner key. In this passkey source candidate it is reserved for first enrollment and emergency recovery; ordinary consent requires an authenticated owner session. The setup page can issue an expiring one-time pairing code, show authorized grants, revoke a device/client, and remove an emergency client block. This setup is separate from individual AI actions: once granted, catalog deletions execute directly without an extra approval page.

QA live integration and independent source reviews have been performed; see `../doc/validation/ai-control-v1-release-readiness-2026-10-17.md` for current evidence and limitations.

## State-aware rollout and rollback — required release gate

Read the [passkey rollout/rollback runbook](../doc/architecture/ai-control-passkey-rollout.md) before replacing the production Worker. A recorded old version is **not automatically a safe rollback target after new authentication/pairing state has been written**.

Keep the existing Worker name, exact `PUBLIC_ORIGIN`/passkey RP hostname, `OWNER_ID`, `OWNER_KEY_HASH`, `SESSION_SECRET`, OAuth KV namespace, actual ACCOUNT/DEVICES Durable Object namespaces and migration history. Do not copy staging IDs or credentials into production. The supported default fallback must understand `owner-auth:v1`, `browser-pairing:v1` and the pairing admission generation; source at `a80426b90` or a reviewed later revision has that protocol, but a Git commit is not evidence that a usable fallback version has been uploaded or tested. Prefer fix-forward until a state-aware deployed fallback is recorded and validated. Do not percentage-split legacy and new authentication code.

Returning to the legacy owner-key implementation is an explicit authentication/protocol downgrade, not a routine rollback. With the old signing secret, an unexpired old signed admin cookie can become valid again even after the new owner-session epoch was reset. Conversely, logging out under the old code does not revoke retained new-format sessions before roll-forward. An enrollment committed in ACCOUNT but not yet confirmed in DEVICES can also be stranded because the old Worker lacks its finish/recovery endpoint. Never clear credentials, grants, revocation tombstones or pairing state to make a downgrade appear successful. Downgrade requires a separately approved maintenance, session invalidation and enrollment reconciliation plan; see the runbook.

Cloudflare version rollback does not restore connected storage. Already-extended OAuth grants retain their recorded expiry under old code, although the old configuration stops sliding it; newly exchanged grants follow the old policy. [Cloudflare rollback documentation](https://developers.cloudflare.com/workers/versions-and-deployments/rollbacks/).

## Browser-mediated pairing source candidate

After compatible gateway and Android rollout, open **AI Control > Connect to AI**, review the device-local permissions, compare the displayed code with the browser, authenticate by passkey and explicitly approve. Return to NewPipe to complete the key-bound enrollment, then choose **Start AI Control**. Nothing is copied or typed as a pairing secret. Existing paired devices do not need to enroll again. Manual code entry remains an Advanced fallback.

The signed begin/status/finish exchange has a bounded public nonce preflight and independent owner/browser approval. Cancellation leaves active configuration intact; a lost response after a durable enrollment can be recovered without creating a new authorization. See [protocol and failure boundaries](../doc/architecture/ai-control-browser-pairing.md). The production service has not been changed by these source commits.

## Android enrollment and ChatGPT connection

On the separate QA app (or the verified and intentionally installed main `custom/dev` build), open **AI Control** from the main menu. Enter the gateway HTTPS origin, a friendly device name, and an owner-issued one-time pairing code. Select the local permission ceiling, pair, then explicitly start AI Control. A foreground-service notification provides Stop. The Android private key never leaves Keystore, and pairing/settings do not enter NewPipe backups.

Configure the private ChatGPT plugin/app with the MCP resource above and OAuth. OAuth discovery and dynamic client registration are implemented; consent includes only the requested scopes. Destructive scopes are off until deliberately granted. Local Android permissions and the OAuth grant must both allow an operation. Every device operation requires its exact device ID; discovery is the exception.

Do not install an AI QA APK over main `custom/dev` or stock/F-Droid NewPipe. The current RitoVision schema is `NP9/RV1` (`9001`). Legacy experimental fork-10 data is deliberately discarded, not migrated. See `../doc/architecture/ritovision-schema-versioning.md`.

## OAuth scope discovery and reauthorization

The protected MCP resource advertises `mcp.connect` and the available Library, playlist and playback scopes, so MCP clients such as ChatGPT can request meaningful access. Merely advertising a scope **does not grant it**: the owner must authenticate and select the requested permissions on the consent page. `library.delete` and `playlist.delete` are unchecked by default, and the Android permission ceiling applies independently. `offline_access` is an authorization-server scope, not an MCP protected-resource scope.

When these advertised scopes change, **an existing ChatGPT OAuth connection cannot gain them through token refresh**. Reconnect/reauthorize the custom plugin on ChatGPT web (or recreate it if the settings UI does not offer reauthorization). No Pixel re-pairing is necessary. Follow the owner-consent screen and select `playlist.delete` only if you specifically want ChatGPT to delete temporary ordinary playlists; selecting `library.delete` authorizes direct canonical Library removal across all playlists and should not be enabled casually.

## OAuth token lifetimes

Source configuration as of 2026-11-09 (**not deployed by this work**; the previous source configuration was a fixed seven-day grant, and the active production bundle was not inspected). Access tokens last 10 minutes. A new grant lasts 90 days; an accepted refresh moves its expiry 90 days forward. Ordinary MCP calls do not extend it. Refresh never widens consented scopes, and expired/revoked grants are not revived. There is no absolute maximum: explicit grant revocation ends access. Dynamically registered clients retain a one-year registration with best-effort renewal during use. An existing seven-day grant adopts the policy on a valid refresh before its old expiry (the provider rejects the final 60 seconds); otherwise reauthorization is required.

Provider retry semantics are important: the token presented by a successful refresh remains the previous token and can be reused repeatedly until another token is successfully presented or the grant expires/is revoked. This is not a one-retry-only guarantee. Grant renewal/rotation is stored before access-token issuance, so a later storage error can return a failed request after renewal has persisted. The Phase B follow-up tests cover repeated previous-token retries, validation rejections, grant/metadata snapshots, and recovery after a deliberately failed access-token write following persisted renewal. They do not claim atomic token issuance. See `../doc/validation/ai-passkeys-phase-b-20261109.md` and `../doc/validation/ai-passkeys-phase-d-20261109.md` for exact-source validation and the remaining live release gates.

## Short reliability acceptance

Run `npm run qa:reliability` inside `gateway/` to execute the synthetic,
ephemeral MCP/OAuth/Android-relay reliability gates. It uses a Wrangler **dry run**,
not a live deployment. Relay socket close/reconnect (not real Android Stop,
Doze or radio transitions), grant revocation, receipt idempotency, cross-device
isolation and offline rejection are exercised without touching any actual
phone or Library data. The normal `npm test` also
includes these cases. The current evidence and remaining hardware-only gates are
recorded in `../doc/validation/ai-control-reliability-fast-acceptance-2026-10-17.md`.

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
