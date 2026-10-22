# NewPipe Cloud & Sync v1 — requested limited production activation

Date: 2026-10-22. **Owner expressly requested production despite deferring scalability and certain final release acceptance gates.** This checkpoint does not certify those gates. Always distinguish the **backend** deployment from the final Android installation and actual device enrollment: see the current deployment ledger for which steps were verified, and never assume mobile enrollment merely because a server endpoint responds.

## Deployed independent production infrastructure

- `newpipe-prod-v1`: dedicated authenticated catalog/recovery/replacement Cloudflare Worker at `https://newpipe-prod-v1.ritorhymes.workers.dev`. New D1 `newpipe-prod-v1`, separate control D1 `newpipe-prod-v1-ctl`, R2 `newpipe-prod-v1`. No reuse of legacy, test, Music, or other app bindings.
- `newpipe-prod-v1-op`: independent authenticated maintenance/recovery operator, using `OPERATOR_TOKEN` stored as a **Cloudflare secret** (not in source or evidence).
- One fresh service generation, library ID and resource-specific `OPEN` external fence; separate production client-token hash is stored in D1 while plaintext enrollment material resides only in a private 0600 host file, outside Git.
- Source-controlled `cloud/wrangler.jsonc` is a fail-closed **disabled** profile bound to the **new** production resources. `cloud/wrangler.production.jsonc` explicitly enables catalog/recovery/protected replacement; `cloud/wrangler.production-operator.jsonc` deploys the separate operator. No automatic housekeeping Cron was deployed.
- **Admitted limits: 65,536 bytes/eight packed catalog pages, 1 MiB native/cloud recovery archive, 64 KiB per JSON request.** No increase above the actually tested Free Workers CPU profile; larger libraries can be correctly rejected without data loss. Unrestricted everyday scale is **not supported**. See [`cloud-production-scale-risk-20261022.md`](../../architecture/cloud-production-scale-risk-20261022.md).

## Evidence and limitations

The initial production DATA table was empty: no devices, heads or archives. The production initializer inserted a single empty `cloud_head` (`revision=0`, uninitialized), an empty `cloud_catalog_info`, one **dedicated** hashed device identity (not a shared QA token), and a recovery-control row. Real Cloudflare HTTPS then returned HTTP 200 with `initialized=false`, correct service and admission limits for that private device; recovery status HTTP 200 with zero archives; invalid and missing credentials received HTTP 401. No personal catalog was copied or initialized as part of this backend smoke.

Before activation, the public Worker returned `503 CLOUD_API_DISABLED`, while the separately secret-protected operator denied missing credentials (`403 AUTH_REJECTED`). Both Workers had matching DATA, CONTROL and R2 bindings and the operator's configured service scope matched the public Worker's generation. The local TypeScript check and backend suite ran **380/380 passed**, zero failures/skips/cancellations, including the newly added fake-Cloudflare **production provisioning identity regression**.

The previous isolated QA DATA+CONTROL pairs (October 21 and October 22), plus the earlier legacy D1 pair, were **permanently deleted at the owner's explicit direction without backups**. After provisioning the dedicated production pair, the Cloudflare Free account had **7/10 databases occupied**, with the remaining five belonging to other projects. Their R2 test buckets/Workers are not the production runtime.

**Known launch caveats:** real Android sync with native display metadata can exceed 64 KiB at ~600 items; full deployed CPU at realistic larger sizes, independent physical device/background/Doze tests, required provider-unknown and generation-reopen outcomes, and formal `OPEN → DRAINING → HELD` operator procedure are not final accepted releases. This production backend was enabled on owner instruction while preserving its strict per-request and loss-prevention limits. Do not claim the product supports 20,000 items in deployed production merely because local workerd tests did. Do not embed device tokens in an APK or place operator credentials in Git.

## Mobile release and enrollment

The Android app uses explicit manual connection, preview, user confirmation and encrypted per-installation credentials. A working production backend **does not connect any existing NewPipe installation automatically**. The original installed `org.schabi.newpipe.debug.customdev` APK was signed with a key **different from the current primary build-machine debug key**; replacing it with a nonmatching certificate would require uninstalling and losing local data, which was not attempted. Production packaging therefore must either reuse the original signing key with proven compatibility or use an isolated new application ID, with an explicit data-migration/import decision. Only an actual on-device connection and initialized catalog demonstrate complete end-to-end production activation.

No destructive migration, D1 reset or cross-project changes were performed to deploy the new backend.
