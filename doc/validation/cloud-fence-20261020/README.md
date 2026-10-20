# External maintenance and live recovery checkpoint — 2026-10-20

Status: **the isolated operator recovery checkpoint passed**. Production remains disabled. This does not approve whole-feature release, legacy-resource recovery, or reopening recovered resources.

This commit extends parent `9466f9670`. Implementation, runtime debugging and review were performed directly in this session without another coding agent. No Android code was changed or Android acceptance rerun.

## Executed checks

- `npm run check` passed.
- Full backend `npm test`: **304 passed, zero failed, zero skipped**. The preceding candidate had one stale redirect assertion; it was updated to match the actual Worker runtime and the full suite rerun.
- Twelve external-fence tests cover paused catalog execution and R2 create/part/complete/abort/delete, missing authority, data-only rewind, admission/close races, detached work, ambiguous storage responses, definite transaction rollback and competing operator claims.
- Twenty-one operator tests cover archive verification, exact pre-state transactions, unrelated-library preservation, forged/resealed plans, exclusive execution, unknown outcomes, receipt resolution, superseded actions, authenticated abort, uncertain restore and delayed unclaimed restore intents.
- Two adapter tests execute production code in workerd. They verify Cloudflare response/redirect handling and the bundled S3 XML inventories, HEAD metadata and exact ETag/range reads. Redirects never forward credentials to another host.

The live attempt exposed two runtime differences from Node: Workers rejected Fetch's `redirect: 'error'`, and the browser XML parser required unavailable `DOMParser`. Both were fixed before any apply action was claimed. The operator now uses manual redirects and the SDK's own pure-JavaScript XML parser. The generated operator config includes the required alias.

## Isolated live sequence

New data/control D1 databases, a new R2 bucket and two new Workers were created for this test. The public fixture initialized one small synthetic catalog and produced **three cloud-catalog archives, 4,995 bytes total**, through the ordinary authenticated API. No native user library or physical phone participated.

| Boundary | Observed result |
| --- | --- |
| Maintenance close | External CONTROL reached HELD with zero request tickets. A valid new request returned 503 `MAINTENANCE_REQUIRED`. |
| Deliberate index loss | After a private export/bookmark and HELD proof, exactly three synthetic slot rows were removed. Physical objects were untouched. |
| First guarded apply | Independent inspection found all three archives, no multipart uploads and no blockers. Apply committed the rebuild and receipt; three READY slots returned, catalog/head/pages/accepted receipts stayed identical, and the old device became revoked/non-writable. |
| Real D1 Time Travel | The authenticated operator restored the saved pre-loss bookmark. The first data receipt disappeared and the old device row became active again, proving a real rewind. CONTROL stayed HELD at revision 4 with zero tickets/running actions. |
| Restored credential | Calling production authentication against the actual restored database accepted the saved token only with its old generation; the deployed new external generation rejected it. Public APIs stayed disabled. |
| Second guarded apply | A newly inspected plan committed. All three READY slots and the same physical archive metadata/digests survived; the old device was revoked/non-writable again. The current data receipt identifies this second apply. |
| Historical status | The first apply action reports SUPERSEDED after Time Travel and the second apply, rather than claiming its old result is current. |
| Closeout | CONTROL remains HELD at revision 5, with zero tickets/running actions. All three public API gates are false. The public endpoint returns 503 `CLOUD_API_DISABLED`. |

The exact sanitized resource IDs, active versions, plans, action outcomes, archive identities/digests and fence states are in [checkpoint.json](checkpoint.json). Full plans, data export, device credentials, operator secret and provider credentials stay private. The S3 credential is object-read only, restricted to the new bucket, and expires October 21. No plan upgrade or paid activation was made.

The final inspection reports `FRESH_EXTERNAL_GENERATION_REQUIRED`: this is expected after the rebuild has installed that generation in the backup-control row. It prevents a new recovery plan from treating the already-used generation as fresh. Completion is established by the recorded transactional receipt and final state checks.

The original `newpipe-cloud-sync-v1` / `newpipe-cloud-recovery-v1` pair and its three historical archives were not modified by this checkpoint. Those resources lack admission provenance and remain inspection-only.

## Scope and remaining limits

The paused-effect tests use actual local D1/R2 bindings with deliberate suspension at each storage boundary. A live partial-body probe was buffered before Worker admission and therefore did **not** produce a live paused-write proof. Live coverage here is admission rejection, missing-index reconstruction, actual Time Travel, generation rejection, receipt history and preservation of all three physical archives.

Unknown/crashed requests retain non-expiring tickets. Unknown operator effects retain exclusive actions. This can block in-place recovery indefinitely; there is no force-clear, expiry or reopen command. CONTROL must never be restored alongside the data database. Legacy requests cannot be retroactively fenced.

This small synthetic run does not establish production-scale recovery or CPU/cost acceptance. Reopening/enrollment, durable daily budgets, housekeeping, broader multi-device/offline/UI/scale acceptance and final independent whole-feature review remain release gates. No custom/dev merge or production activation is implied.

See [the operator runbook](../../architecture/cloud-operator-recovery.md) for provisioning, exact commands, credential scope and uncertain-outcome handling. The synthetic archive producer is [live-fence-fixture.mjs](../../../cloud/test-support/live-fence-fixture.mjs); it rejects non-QA/non-fresh resource records.
