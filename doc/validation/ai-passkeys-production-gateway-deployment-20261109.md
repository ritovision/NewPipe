# NewPipe AI Control — production gateway deployment (November 9, 2026 EST)

**Deployment status:** Live at **100% production traffic**. This record concerns only the AI Control Cloudflare Worker, **not** the Android app or the separate Cloud & Sync service.

## Identity and release
- Worker: `ritovision-newpipe-ai-control`
- Production origin: `https://ritovision-newpipe-ai-control.matthewjpellerito.workers.dev`
- Source branch: `feature/ai-passkeys-20261109`; source commit: `06992e64ee8dc1a5ddb661515e2242193e3815a4` (working tree clean).
- Candidate local bundle `gateway/dist/index.js` SHA-256: `95a9b826d20062e0b1d6e475f9f4f98cf295d2d5cc33474c3c877a325e22abf4`.
- Former live version (100%): `75645ddd-3e81-4c9e-b4d0-a04f517a9b4c` (October 17).
- Uploaded and activated version (100%): `b0367d23-7723-42e7-bfd2-988dc1e693ee`.
- New deployment ID: `b4d25c5f-610b-4415-8885-131055f1d345` at `2026-11-10T03:04:02.028225Z` (November 9 EST).
- Wrangler `4.158.0`, with an **upload-first / inspect / activate** procedure. All traffic deployed to the compatible new source in a single version (no split with legacy auth).

## Exact binding and runtime preservation

A read-only `wrangler versions view --json` comparison of the old live version and the new uploaded version, **before traffic activation**, verified all nine binding names, types and available identities preserved:

| Binding | Type | Verified identifier or invariant |
| --- | --- | --- |
| `ACCOUNT` | Durable Object (`ControlAccount`) | Namespace `707adaedd59e4e50be750895eb33d947` unchanged |
| `DEVICES` | Durable Object (`DeviceRoom`) | Namespace `fa622e1122eb4f26b65c6f7aea1eecc4` unchanged |
| `OAUTH_KV` | KV | Namespace `03c831c33fd24e37ba3db17ad02fc5e2` unchanged |
| `AUTH_LIMITER` | Rate limiter | Namespace `470116071` unchanged |
| `CONTROL_LIMITER` | Rate limiter | Namespace `470116072` unchanged |
| `OWNER_ID` | Plain-text binding | Same existing owner ID (intentionally not duplicated here) |
| `PUBLIC_ORIGIN` | Plain-text binding | Same production hostname above |
| `OWNER_KEY_HASH` | Secret binding | Present in both versions; **value not read or rotated** |
| `SESSION_SECRET` | Secret binding | Present in both versions; **value not read or rotated** |

Runtime unchanged: migration tag `v1`, compatibility date `2026-08-25`, flags `nodejs_compat` and `global_fetch_strictly_public`. No new DO migration, namespace reset, secret-provisioning script, or data-clearing command was used.

## Test evidence and post-deployment checks

- Before activation: exact-source gateway TypeScript/build/Miniflare suite **162/162 passed** (9 test files), full `npm audit --json` **zero vulnerabilities** on the build machine.
- After activation: Cloudflare deployment listing confirmed the new version at **100%**.
- Public production requests succeeded with HTTP 200: `/health`, `/setup` (new passkey login + emergency recovery UI), `/auth/passkeys.js`, `/.well-known/oauth-authorization-server`, `/.well-known/oauth-protected-resource/mcp`.
- No NewPipe ChatGPT plugin action was invoked **after the user's instruction to stop calling it**; the post-deployment checks use Cloudflare CLI and direct public HTTP only.
- No real production owner-passkey enrollment or ChatGPT OAuth refresh was attempted as part of this record. The staging Pixel/passkey/ChatGPT tests are independently documented.

Protected operator logs and the full downloaded Cloudflare version/deployment metadata (which should stay outside source) reside under `~/.local/state/newpipe-passkeys-20261109/production-preflight-20261109/` on the secondary machine.

## Fallback and remaining release gates

**Do not blindly roll back to `75645ddd`** once the new auth state has been used: the older code does not understand persisted passkey owner sessions or browser pairing, and legacy signed cookies have different revocation semantics. No previously deployed state-compatible rollback artifact exists. The operational fallback is **controlled maintenance or state-aware fix-forward**; see `../architecture/ai-control-passkey-rollout.md`. This does not claim a fix-forward artifact beyond the active verified version.

**Separate Android installation remains unperformed in this gateway deployment.** On Pixel 7, the relevant production package is `org.schabi.newpipe.ritoprod`, version `0.29.1-ritoprod.cloud20261022` and versionCode `1015`. The installed APK has the dedicated certificate `CN=RitoVision NewPipe Production` SHA-256 `a8706c87a71e030a42fdade9f424fb541336fb2b1c54f1875e7294e94a4f8a74`, **not** the secondary debug certificate. A protected P12 signing key on the primary machine was independently confirmed to have that same public certificate. Do not install any unsigned or debug-signed APK or uninstall the existing package. A versionCode-increasing, precisely signed release candidate and data-preserving upgrade checks remain distinct from the gateway deployment.

Next user-present step: register and test a **separate production-hostname passkey** using physical Pixel verification and the existing protected production owner credential. Do not reuse the staging-only passkey/RP ID.
