# Operator recovery: inspection, guarded rebuild, and current live blocker

Status: the read-only inspector is usable and has been exercised against the dedicated live NewPipe bucket. The rebuild transaction is tested against real local D1. **Live apply and multipart abort are deliberately unavailable until an external stale-worker fence is implemented and verified. This is not completed disaster-recovery or release acceptance.**

## Why apply is blocked

A disabled API gate and a new external service generation stop newly admitted clients. They do not prove that an older invocation has stopped. An old request may already have passed authentication or claimed an R2 operation. Restoring D1 can resurrect its old device authority or operation state. A later R2 completion/deletion cannot be rolled back by a D1 transaction.

The Workers documentation gives HTTP invocations no fixed duration while a client remains connected. The separate runtime-update drain period is not a promise that deploying an application version drains every older invocation. Waiting an arbitrary interval, observing no multipart uploads, or supplying `--workers-fenced=true` is not proof. The CLI exposes no override.

The smallest prerequisite for live apply is an independently verifiable exclusive maintenance barrier covering all old and new D1/R2 mutations and surviving D1 rollback, or a provider-supported, verified termination/drain operation for every relevant invocation. The chosen mechanism needs race tests with a paused pre-maintenance request, including a late create, completion, deletion and previously admitted D1 write. Do not use D1 itself as the only authority if that D1 database will be rewound. This checkpoint adds no new infrastructure or paid service to pretend that barrier exists.

## Inspect existing archives

Run from `cloud/` with Node 24 and `npm ci`. Supply an exact scope JSON, outside Git:

```json
{
  "account": "ACCOUNT_ID",
  "worker": "newpipe-cloud-sync-v1",
  "database": "D1_DATABASE_UUID",
  "bucket": "newpipe-cloud-recovery-v1",
  "library": "EXACT_LIBRARY_ID",
  "generation": "NEW_EXTERNAL_SERVICE_GENERATION_UUID"
}
```

Credentials come only from environment variables:

- `NEWPIPE_CLOUDFLARE_API_TOKEN`: account-scoped Worker read and D1 read access.
- `NEWPIPE_R2_ACCESS_KEY_ID` and `NEWPIPE_R2_SECRET_ACCESS_KEY`: object-read S3 credentials restricted to the one recovery bucket. A short expiry is sufficient. No object-write permission is needed.

```sh
node operator/cli.mjs inspect --scope /private/scope.json --out /private/recovery-plan.json
node operator/cli.mjs verify --plan /private/recovery-plan.json
```

The output file is created exclusively with mode 0600 and includes catalog data and credential hashes; keep it private. Standard output contains only counts, a plan digest, and blocker codes. Existing output files are never overwritten. The digest detects changes; it is not an authentication signature or proof of a maintenance barrier.

The inspector reads the actual 100% deployed Worker version, its bindings and current settings, and checks that the deployment remains the same. It separately paginates completed objects and incomplete multipart uploads under exactly `recovery/<library>/`. A repeated/missing continuation, duplicate slot, malformed identity or fourth completed object fails inspection. An incomplete upload is named by exact key and upload ID in the private plan and blocks rebuilding. No upload or completed object is deleted.

Each completed object is read at the original 8 MiB part boundaries with an ETag condition. The inspector reconstructs the manifest and verifies its SHA-256 manifest digest, cloud-page container/root or ZIP boundaries, original scope, and metadata. ZIP checks do not validate SQLite or prove a usable native restore. Android must still prepare the archive before restoring it. S3 listing timestamps retain milliseconds; HTTP HeadObject timestamps have seconds, so those are compared at their shared precision while the exact listing timestamp remains in the plan and complete inventories are compared before and after verification.

The S3 interface does not expose the internal Workers R2 object-version value. Reconstructed rows retain an exact ETag, immutable key, metadata and verified manifest; the internal object-version field is null. This is explicitly different from claiming that an internal R2 version was recovered.

The plan includes exact, ordered, text-encoded D1 snapshots, preserving 64-bit counters. A table snapshot larger than 512 KiB blocks this bounded tool; it never silently truncates a catalog. All current library tables are checked, including accepted pages, staged operations, devices, grants and receipts.

## Rebuild transaction and current limitation

`operator/recovery.mjs:rebuildBatch` compiles a parameterized batch for an independently fenced executor. It compares every scoped snapshot inside the same D1 batch as the mutations. It revokes existing device/grant authority, invalidates staged work, preserves accepted catalog contents, completed receipts and unrelated libraries, reconstructs READY slots from verified physical bytes, and binds the recovery control row to the new generation. Archive ordering is original creation time followed by archive ID for ties. It never writes R2 or manufactures another copy.

Local tests execute this batch using D1's transactional `batch` API. Concurrent plans have one winner; an unchanged replay is refused by its pre-state guard, including after an unknown response, so it cannot apply twice. Do not infer a committed outcome from a timeout: compare the intended after-state under the eventual exclusive fence. There is currently no live executor and no durable operator receipt, so this is not a claim of completed live unknown-outcome recovery.

```sh
node operator/cli.mjs apply --plan /private/recovery-plan.json
```

always exits with `STALE_WORKER_FENCE_UNAVAILABLE` before contacting a service. It cannot be overridden by modifying a plan. Do not copy the compiled SQL into Wrangler as a workaround. Multipart abort likewise remains an explicit future fenced operation; inspect preserves the exact upload identity for that work.

## Time Travel procedure

1. Verify the exact Cloudflare account, database, Worker, bucket and library. Confirm that D1 may contain other libraries: Time Travel restores the whole database, not just this library.
2. Disable all three API gates outside D1 and choose a fresh external service generation. Do not reuse a generation from a previous index, device, archive or rollback. Verify the deployed version, not merely the checked-in configuration.
3. Export the current database and save its Time Travel bookmark outside that database. Keep local native exports too. Run and retain the private archive inspection.
4. **Stop here until the external maintenance barrier described above is proven.** A disable/redeploy alone does not authorize a live rollback or rebuild.
5. Under that barrier, obtain the exact target bookmark and execute the intended whole-database restore. Record the provider's undo bookmark and restore result outside D1. If restoration has an uncertain outcome, inspect the actual database before taking another action.
6. Reinspect all completed objects, all multipart uploads and the restored database. Resolve unknown multipart identities explicitly under the same barrier; never clear a slot because its lease expired. Recompute a fresh plan. Do not recreate, overwrite or delete a verified archive merely to repair its index.
7. Apply the guarded batch through the future fenced executor. Verify catalog contents, three-slot bounds, archive digests, device/grant revocation and unrelated libraries. Check that old device tokens, including revoked tokens resurrected by the rollback, are rejected.
8. Reenroll devices explicitly with new identities and authority. Reconciliation previews remain required. Reenable only the approved APIs after verification. No command in this tool reenrolls devices or reenables gates.

An undo bookmark restores D1 only. It does not undo R2 writes, restore deleted archives, restore the external generation, or make old device tokens safe. Keep the same external maintenance barrier and rerun physical reconciliation for an undo operation.

## Verification

```sh
npm run check
node --test test/operator-recovery.test.mjs
npm test
```

Tests cover real local D1/R2 reconstruction, preserved physical bytes and unrelated state, lost/rewound index, exact optimistic concurrency, revoked credentials, unknown multipart blocking, payload corruption, timestamp precision, stale plans, actual-version binding checks and independent S3 pagination boundaries. The local harness owns its runtime exclusively; it does not simulate that ownership as evidence for a Cloudflare production fence.

Official references:

- [D1 Time Travel](https://developers.cloudflare.com/d1/reference/time-travel/)
- [Workers limits and invocation duration](https://developers.cloudflare.com/workers/platform/limits/)
- [R2 S3 API compatibility](https://developers.cloudflare.com/r2/api/s3/api/)
- [R2 scoped API tokens](https://developers.cloudflare.com/r2/api/tokens/)
