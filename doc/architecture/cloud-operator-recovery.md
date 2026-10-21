# Operator recovery: external maintenance and guarded apply

Status: an external request barrier and an authenticated operator executor are implemented. Local tests cover paused writes and uncertain outcomes. Live validation and its exact scope are recorded in [fenced recovery evidence](../validation/cloud-fence-20261020/README.md). This is not whole-feature release approval. Production remains disabled.

## Authority that survives a data restore

The public Worker requires a **separate CONTROL D1 database** and an exact `FENCE_RESOURCE`. Before handing storage capabilities to an authenticated request, it atomically creates a durable ticket only if that resource is OPEN and its library/service generation matches. All D1 execution and R2 mutation methods are wrapped. Finishing the request closes its captured capabilities before removing the ticket.

A ticket is removed only when no storage calls remain pending and every mutation has a definite result. The exact D1 assertion-rollback error is a definite failed transaction; arbitrary errors and ambiguous R2 results are not. If a Worker dies, a storage call loses its response, or detached work is still pending, the ticket remains. There is no expiry, lease reclamation, force-clear, or claim that elapsed time proves completion.

Maintenance atomically changes OPEN to DRAINING, preventing further admission. It can change DRAINING to HELD only in an external control transaction that finds zero tickets. A paused pre-maintenance request can finish under its existing ticket, but the data database cannot be restored or rebuilt until it has finished. A request that wakes after its capabilities have closed cannot start another storage call. Read-only response streaming does not retain write authority.

CONTROL is an independent failure domain for this procedure: **never restore, replace, delete, or edit its tickets alongside the data database**. Restoring CONTROL would destroy the proof. A damaged/unavailable control database or an unresolved ticket means stop in-place recovery. Waiting longer does not authorize bypassing it. Recovering availability from that state needs a separately designed resource migration or a provider-proven resolution; this tool does not manufacture one.

This trades availability for safety on ambiguous failures. It is not a general guarantee that every crashed invocation can eventually be drained.

## Legacy deployment boundary

Earlier Workers never acquired these tickets. Their existing database and bucket therefore remain **inspection-only**, even after upgrading code or disabling the API. A new control row cannot retroactively fence them.

`operator/provision.mjs` provisions fresh data and control databases, a fresh bucket, and new Worker names. It rejects existing names and writes creation responses to a private checkpoint before registering the resource. It has no adopt-existing-resource option. The data pair must be used only by fenced public code and the authenticated operator; administrative writes outside this protocol invalidate its exclusivity assumptions.

The original `newpipe-cloud-sync-v1` / `newpipe-cloud-recovery-v1` QA resources and their three original archives were not migrated by this work. The checked-in legacy Wrangler config remains disabled. Merely setting its API flags true will not make the new entry point usable without valid external authority.

## Provision an isolated candidate

Use Node 24, `npm ci`, and an account-scoped Cloudflare token with the necessary resource-creation permissions:

```sh
node operator/provision.mjs --account ACCOUNT_ID \
  --prefix newpipe-UNIQUE_NAME --out /private/new-candidate/provision.json
```

The script checks database headroom against the Free account database limit. It never changes a plan or billing settings, and does not deploy or enable APIs. A failed or uncertain create is retained in the checkpoint; inspect those exact resource names before retrying. Do not adopt them by editing provenance fields.

The generated `application.jsonc` and `operator.jsonc` are explicit configurations for separate Workers. Review their bindings. Deploy only the intended candidate, and set a random `OPERATOR_TOKEN` secret on the operator Worker. Without that secret, all operator requests fail closed. Both Workers need the same exact DB, CONTROL, RECOVERY, and FENCE_RESOURCE bindings. CONTROL must differ from DB. The operator uses `nodejs_compat`. Keep the generated XML-parser alias: Workers has no browser `DOMParser`, so the bundle uses the SDK's own pure-JavaScript parser. The API and S3 adapters use manual redirect handling and reject redirect responses; neither forwards credentials to a redirected host. Both adapters have real workerd regression tests.

No production activation, device enrollment, credential migration, or API enablement is performed by the recovery CLI.

## Exact scope and credentials

Legacy scope JSON has exactly `account,worker,database,bucket,library,generation`. For a fenced resource it also contains:

```json
{
  "fence": {
    "control": "SEPARATE_CONTROL_DATABASE_UUID",
    "resource": "FRESH_RESOURCE_UUID",
    "operator": "newpipe-EXACT_OPERATOR_WORKER"
  }
}
```

The top-level generation is the **new** external generation for recovery, different from the existing device/index/archive authority. The operator deployment's SCOPE must match exactly.

Credentials are supplied only through environment variables:

- `NEWPIPE_CLOUDFLARE_API_TOKEN`: Worker/D1 read for inspection; D1 write for Time Travel.
- `NEWPIPE_R2_ACCESS_KEY_ID` and `NEWPIPE_R2_SECRET_ACCESS_KEY`: object-read S3 credentials restricted to the exact recovery bucket.
- `NEWPIPE_OPERATOR_TOKEN`: the separate operator Worker secret, required for maintenance and execution.

The operator's address is derived from the authenticated Cloudflare account subdomain and verified deployment. Apply sends the read-only S3 credentials and Cloudflare token transiently to that authenticated operator so it can independently reinspect the exact resource. Restore sends the Cloudflare token transiently for the exact data-DB restore call. These credentials are never written into plans, action records or receipts and must not be logged. Observability remains disabled in the generated configurations.

## Maintenance and recovery sequence

1. Verify exact account, fresh-resource provenance, both deployed Workers, binding IDs, library, and that no independent administrative writer uses this pair. Keep native exports.
2. Save a current database export and Time Travel bookmark privately. Time Travel restores the **whole data database**, not just one library.
3. Close maintenance using an explicit persistent maintenance UUID:

```sh
node operator/cli.mjs close --scope /private/scope.json --maintenance UUID
node operator/cli.mjs state --scope /private/scope.json
```

Repeat close with the **same** UUID to promote DRAINING after tickets finish. HELD requires zero tickets. A competing UUID is rejected. Unknown tickets do not expire.

4. Disable all three public API gates and deploy the fresh service generation, keeping the same bindings. Verify actual deployed versions/settings. The gate change is additional admission protection; it is not the exclusive barrier.
5. For an intended rollback, use an exact saved bookmark:

```sh
node operator/cli.mjs bookmark --scope /private/scope.json
node operator/cli.mjs restore --scope /private/scope.json \
  --bookmark EXACT_BOOKMARK --out /private/new-restore-action.json
```

An exclusive external action is claimed before the provider call. The saved intent binds the maintenance UUID and revision, so a delayed request that has not yet claimed its action cannot overtake a later operation. The provider's returned bookmark and undo bookmark are retained outside the restored database. A lost/invalid restore response leaves RUNNING and blocks further restore/apply. Do not infer completion merely because visible data resembles the target. Undo is another restore under the same barrier and has the same uncertain-outcome rule.

6. Reinspect physical objects, multipart uploads, and the resulting database:

```sh
node operator/cli.mjs inspect --scope /private/scope.json --out /private/new-plan.json
node operator/cli.mjs verify --plan /private/new-plan.json
```

Plans are new, exclusive mode-0600 files containing catalog data and credential hashes; keep them private. The digest detects changes, not malicious operator access. Standard inspect output contains only counts, digest and blocker codes.

Inspection verifies the actual 100% active deployments and current settings, then rechecks deployment identity. It separately paginates ListObjectsV2 and ListMultipartUploads under exactly `recovery/<library>/`. Duplicate slots, a fourth completed object, malformed identities, or missing/repeated cursors fail closed.

Every completed object is read with an ETag precondition at its original 8 MiB part boundaries. Inspection verifies metadata scope, chunk SHA-256 digests, manifest digest, and cloud-page container/root or ZIP boundaries. ZIP boundaries do not validate SQLite; Android still must prepare a native archive before restoration. S3 listing milliseconds and HTTP HeadObject seconds are compared at their shared precision, while the precise listing timestamp is preserved.

The S3 API does not expose the internal Workers R2 version. Rebuilt rows retain the exact ETag, key, metadata and verified manifest; `object_version` is null.

7. If incomplete multipart uploads are present, inspect their exact key/upload ID and abort them individually under the same external barrier:

```sh
node operator/cli.mjs abort --plan /private/new-plan.json \
  --key EXACT_KEY --upload EXACT_UPLOAD_ID --out /private/new-abort-action.json
```

Only an upload named in the current verified plan is accepted by the CLI. The server aborts that exact key/ID; it does not delete a completed archive. An uncertain abort retains the exclusive action. Reinspect afterward; an old plan is stale after every claimed operator action.

8. Apply a fresh plan with zero blockers:

```sh
node operator/cli.mjs apply --plan /private/new-plan.json
```

The server independently regenerates the plan from actual storage, including both S3 inventories. It never trusts SQL copied into a resealed file. It compares the external fence revision, then exclusively claims this plan's action. D1 receives one transactional batch containing exact pre-state guards, the rebuild, and a durable receipt.

The batch preserves accepted catalog contents, terminal history/receipts and unrelated libraries; revokes old device/grant authority; invalidates staged work; and reconstructs READY slots from verified physical bytes. It does not modify R2. Archive ordering is original creation time, then archive ID. Table snapshots preserve 64-bit counters as text; each table is bounded to 512 KiB. The HTTP plan is additionally bounded to 2 MiB. Larger recovery plans fail closed.

9. If a reply is lost, query the recorded action:

```sh
node operator/cli.mjs status --scope /private/scope.json --action DIGEST --kind apply
```

A matching transactional receipt proves a completed rebuild and resolves its external RUNNING action. No receipt means unknown, not failed: the batch is never automatically resubmitted, and Time Travel is blocked. A completed action followed by a later operator action is reported SUPERSEDED; its historical result is not evidence of the current database state.

10. Verify archive digests, catalog contents, slot bounds, all old credentials and any unrelated library. Reopening is a separate, explicit action after the latest successful rebuild.

## Reopening a recovered generation

Keep all public API flags false while preparing and executing reopening. The operator requires exclusive HELD maintenance with zero tickets and no unknown action; a matching receipt for the latest completed rebuild; a valid initialized accepted catalog; verified READY archives matching physical R2 bytes; and revoked, non-writable old devices with no active upload, backup or replacement authority. It independently repeats these checks immediately before execution.

```sh
node operator/cli.mjs prepare-reopen --scope /private/scope.json --devices 2 --out /private/enrollment.json
node operator/cli.mjs reopen --plan /private/enrollment.json
node operator/cli.mjs status --scope /private/scope.json --maintenance MAINTENANCE_UUID --action DIGEST --kind reopen
```

The enrollment file contains both a sealed plan and new plaintext device credentials. It is created exclusively with mode 0600; never commit it, include it in logs, or export it with native backups. The default credentials are read-only; `--write` explicitly grants catalog writes to the generated cohort. One to sixteen fresh devices are supported. The server accepts only token hashes, rejects reused device IDs/hashes, and leaves every old credential revoked. Reopening does not grant protected replacement authority.

The operator first claims a non-expiring external action. One data transaction verifies the exact pre-state, inserts fresh devices and writes a reopening receipt. Only that receipt permits a second transaction to mark the external fence OPEN and the action DONE atomically. There is no remaining data effect after OPEN. A lost reply is resolved from the receipts; the enrollment batch is never automatically submitted twice. If no data receipt exists, the action stays RUNNING and maintenance stays closed. Use the original maintenance UUID for status after reopening, because OPEN clears the active maintenance field.

Public API deployment flags remain false after this command. Any subsequent activation is a separate deployment decision using the recovered generation. Devices must reconnect using their new tokens, remain sync-off, and inspect a fresh catalog preview before enabling sync. Android now provides **Reconnect after cloud recovery** for an existing connection: it accepts only a fresh device in a different service generation of the same library/origin, saves a local safety backup, keeps old pending catalog/recovery/replacement files privately, and preserves local catalog IDs and content. A durable encrypted transition record permits **Finish reconnecting** after interruption. Normal cloud actions stay blocked until that transition completes; sync remains off afterward. The nine reconnection cases and five real process-kill/restart boundaries have now executed successfully on a Windows-hosted Android emulator; see [process-death evidence](../validation/cloud-reconnect-process-death-20261021/README.md). Live multi-device coverage remains separate; see the [Android reconnection checkpoint](../validation/cloud-reconnect-20261021/README.md). A later maintenance cycle needs a new maintenance UUID and a new generation; prior action results then report SUPERSEDED.

### Existing QA schema upgrade

New provisioning includes data migration `0005_reopen_receipts.sql` and external-control migration `0002_reopen.sql`. For a pair created by the earlier raw-schema provisioner, do not blindly run the complete migration directory: the old schema was not recorded in Wrangler's migration table. First disable all APIs and prove HELD with zero tickets/running actions. Save the action history and a data export/bookmark privately. Use a private Wrangler config with the exact database bindings, upgrade-only directories containing just these two new files, and a distinct migration table. Apply each whole migration transactionally using `d1 migrations apply --remote`, CONTROL first, then DB; verify action row ordering, results and resource ownership before deploying the new operator. Never restore CONTROL with the data database. After a data Time Travel that predates migration 0005, reapply that data migration under HELD maintenance before preparing reopening; the operator refuses a missing or incompatible receipt table before claiming any action.

The control migration itself rejects open/draining resources, pending tickets, unknown actions and unmappable historical actions. A failed migration must roll back in full; never execute its statements individually on existing resources. The [reopening checkpoint](../validation/cloud-reopen-20261021/README.md) records the actual isolated upgrade and HTTP verification.

## Verification and operational limits

```sh
npm run check
node --test test/fence.test.mjs test/operator-recovery.test.mjs
npm test
```

Local tests use actual D1/R2 bindings and pause create, part upload, complete, abort, delete and catalog execution. They also cover detached work, ambiguous effects, data-only rewind, admission/close races, competing operator claims, corrupt/resealed plans, lost rebuild replies and superseded receipts. These are local boundary tests; live evidence identifies separately what was exercised at Cloudflare.

Request tickets now carry durable daily reservations and include CONTROL accounting costs. The [budget and housekeeping runbook](cloud-budgets-v1.md) describes the additional transactional CONTROL 0003 / DATA 0006 upgrade and explicit policy seed required before deploying this candidate. Deployed CPU/scale acceptance and broader multi-device/offline acceptance remain release gates. The operator's bounds are intentionally small and do not establish production-scale recovery.

References: [D1 transactional batch](https://developers.cloudflare.com/d1/worker-api/d1-database/), [primary-read behavior](https://developers.cloudflare.com/d1/best-practices/read-replication/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), [Time Travel](https://developers.cloudflare.com/d1/reference/time-travel/), [R2 S3 compatibility](https://developers.cloudflare.com/r2/api/s3/api/), [R2 tokens](https://developers.cloudflare.com/r2/api/tokens/).
