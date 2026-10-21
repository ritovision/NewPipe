// SPDX-License-Identifier: GPL-3.0-or-later
// Operator-only module. Never import into the public Worker.
import {createHash} from 'node:crypto';
import {RECOVERY_LIMITS as L, archiveId, readRecoveryManifest, recoveryDigest,
  bytesDigest, verifyCloudArchivePart, verifyZipStart, verifyZipEnd} from '../src/recovery-format.ts';

export class OperatorError extends Error {
  constructor(code) { super(code); this.name = 'OperatorError'; this.code = code; }
}
export const fail = code => { throw new OperatorError(code); };
export const canonical = value => JSON.stringify(value, (_, v) =>
  v && typeof v === 'object' && !Array.isArray(v)
    ? Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]])) : v);
export const seal = value => createHash('sha256').update(canonical(value)).digest('hex');
const identifier = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/;
export function validateScope(s) {
  if (!s || !['account,bucket,database,generation,library,worker','account,bucket,database,fence,generation,library,worker'].includes(Object.keys(s).sort().join(',')) ||
    Object.entries(s).some(([k,v]) => k!=='fence' && typeof v !== 'string') ||
    !/^[0-9a-f]{32}$/.test(s.account) || !archiveId.test(s.database) ||
    !archiveId.test(s.generation) || !identifier.test(s.library) ||
    !/^[a-z0-9][a-z0-9-]{0,62}$/.test(s.worker) ||
    !/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(s.bucket)) fail('INVALID_EXACT_SCOPE');
  if (s.fence && (Object.keys(s.fence).sort().join(',')!=='control,operator,resource' ||
    !archiveId.test(s.fence.control) || s.fence.control===s.database || !archiveId.test(s.fence.resource) ||
    !/^[a-z0-9][a-z0-9-]{0,62}$/.test(s.fence.operator) || s.fence.operator===s.worker)) fail('INVALID_FENCE_SCOPE');
  return s;
}
const keyInfo = (scope, key) => {
  const prefix = 'recovery/' + scope.library + '/';
  if (typeof key !== 'string' || !key.startsWith(prefix)) fail('OUTSIDE_LIBRARY_PREFIX');
  const pieces = key.slice(prefix.length).split('/');
  if (pieces.length !== 2 || !/^[0-2]$/.test(pieces[0]) || !archiveId.test(pieces[1])) fail('INVALID_RECOVERY_KEY');
  return {slot:Number(pieces[0]), archive:pieces[1]};
};
/** Enumerate both APIs completely. Never treat an unknown continuation as EOF. */
export async function inventory(store, scope) {
  validateScope(scope);
  const prefix = 'recovery/' + scope.library + '/';
  const objects = [], uploads = [];
  for (const kind of ['objects', 'uploads']) {
    let cursor = null;
    const cursors = new Set(), identities = new Set();
    for (let page = 0; ; page++) {
      if (page >= 1000) fail('INVENTORY_PAGE_LIMIT');
      const result = await store.list(kind, prefix, cursor);
      if (!result || !Array.isArray(result.items) || typeof result.truncated !== 'boolean') fail('INCOMPLETE_INVENTORY');
      for (const item of result.items) {
        keyInfo(scope, item.key);
        const id = kind === 'objects' ? item.key : canonical([item.key, item.uploadId]);
        if (kind === 'uploads' && (typeof item.uploadId !== 'string' || !item.uploadId)) fail('INVALID_UPLOAD_ID');
        if (identities.has(id)) fail('DUPLICATE_INVENTORY_ENTRY');
        identities.add(id);
        (kind === 'objects' ? objects : uploads).push(item);
        if (objects.length > L.slots) fail('TOO_MANY_PHYSICAL_ARCHIVES');
        if (uploads.length > 1000) fail('INVENTORY_UPLOAD_LIMIT');
      }
      if (!result.truncated) {
        if (result.next != null) fail('INCONSISTENT_INVENTORY_CURSOR');
        break;
      }
      if (result.next == null) fail('INCOMPLETE_INVENTORY');
      const next = canonical(result.next);
      if (next === canonical(cursor) || cursors.has(next)) fail('REPEATED_INVENTORY_CURSOR');
      cursors.add(next); cursor = result.next;
    }
  }
  const slots = objects.map(o => keyInfo(scope,o.key).slot);
  if (new Set(slots).size !== slots.length) fail('DUPLICATE_PHYSICAL_SLOT');
  return {objects:objects.sort((a,b)=>a.key.localeCompare(b.key)),
    uploads:uploads.sort((a,b)=>canonical(a).localeCompare(canonical(b)))};
}
function identity(o) {
  if (!o || typeof o.etag !== 'string' || !o.etag || !Number.isSafeInteger(o.size) ||
    o.size < 22 || o.size > L.bytes || typeof o.metadata !== 'string' ||
    Buffer.byteLength(o.metadata) > 1800 || typeof o.modified !== 'string' ||
    !Number.isFinite(Date.parse(o.modified))) fail('INVALID_OBJECT_IDENTITY');
  return {key:o.key,etag:o.etag,size:o.size,modified:o.modified,metadata:o.metadata};
}
const metaFields = ['format','archive','slot','kind','bytes','digest','source','native','library','service','device','created','checksum','producerValidation'].sort().join(',');
export async function verifyArchive(store, scope, listed) {
  const i = identity(await store.head(listed.key)), key = keyInfo(scope,i.key);
  // ListObjectsV2 preserves milliseconds; HTTP HeadObject Last-Modified has seconds.
  // Retain the precise listing timestamp too; complete before/after inventories must match.
  if (listed.etag !== i.etag || listed.size !== i.size ||
    Math.floor(Date.parse(listed.modified)/1000) !== Math.floor(Date.parse(i.modified)/1000)) fail('OBJECT_CHANGED_DURING_INVENTORY');
  let m; try { m = JSON.parse(i.metadata); } catch { fail('INVALID_RECOVERY_METADATA'); }
  if (!m || Object.keys(m).sort().join(',') !== metaFields ||
    m.format !== 1 || m.archive !== key.archive || m.slot !== key.slot ||
    m.library !== scope.library || m.bytes !== i.size ||
    typeof m.device !== 'string' || typeof m.service !== 'string' ||
    !identifier.test(m.device) || !identifier.test(m.service) || typeof m.created !== 'string' ||
    !/^(0|[1-9][0-9]{0,15})$/.test(m.created) || !Number.isSafeInteger(Number(m.created)) ||
    !/^[0-9a-f]{64}$/.test(m.digest) || m.checksum !== 'sha256-chunk-manifest-v1') fail('INVALID_RECOVERY_METADATA');
  const parts = []; let cloudState = null, tail = new Uint8Array();
  for (let offset = 0; offset < i.size; offset += L.partBytes) {
    const length = Math.min(L.partBytes, i.size-offset);
    const data = await store.read(i.key, offset, length, i.etag);
    if (!(data instanceof Uint8Array) || data.byteLength !== length) fail('TRUNCATED_OBJECT_RANGE');
    parts.push({bytes:length,sha256:await bytesDigest(data)});
    // Reuse the format reader before processing untrusted source/native fields.
    if (offset === 0) {
      const count = Math.ceil(i.size/L.partBytes);
      readRecoveryManifest({version:1,kind:m.kind,bytes:i.size,source:m.source,native:m.native,
        producerValidation:m.producerValidation,
        parts:Array.from({length:count},(_,n)=>({bytes:Math.min(L.partBytes,i.size-n*L.partBytes),sha256:'0'.repeat(64)}))});
    }
    if (m.kind === 'cloud-catalog') cloudState = await verifyCloudArchivePart(cloudState,data,m.source,offset+length===i.size);
    else {
      if (offset === 0) verifyZipStart(data);
      // ZIP EOCD may span the final 8 MiB boundary.
      const joined = new Uint8Array(tail.length+data.length);
      joined.set(tail); joined.set(data,tail.length);
      tail = joined.slice(Math.max(0,joined.length-65557));
    }
  }
  if (m.kind === 'device-zip') verifyZipEnd(tail,i.size);
  const manifest = readRecoveryManifest({version:1,kind:m.kind,bytes:i.size,source:m.source,native:m.native,
    producerValidation:m.producerValidation,parts});
  if (manifest.source && (manifest.source.head.library !== scope.library || manifest.source.head.serviceGeneration !== m.service)) fail('ARCHIVE_SOURCE_SCOPE_MISMATCH');
  if (await recoveryDigest(manifest) !== m.digest) fail('ARCHIVE_DIGEST_MISMATCH');
  if (canonical(identity(await store.head(i.key))) !== canonical(i)) fail('OBJECT_CHANGED_DURING_VERIFICATION');
  return {...i,listedModified:listed.modified,slot:key.slot,archive:m.archive,device:m.device,service:m.service,created:m.created,digest:m.digest,manifest};
}

// Compare exact serialized state inside the same D1 transaction as the rebuild.
// Numbers are serialized as strings by SQLite, avoiding JavaScript precision loss.
export const TABLES = [
  'cloud_head','cloud_devices','cloud_receipts','cloud_catalog_info','cloud_catalog_pages',
  'cloud_uploads','cloud_upload_chunks','cloud_upload_changes','cloud_backup_control',
  'cloud_backup_approvals','cloud_backup_operations','cloud_backup_slots','cloud_replace_grants',
  'cloud_replace_approvals','cloud_replace_attempts'
];
const quote = s => '"' + s.replaceAll('"','""') + '"';
export async function snapshot(db, library) {
  const result = [];
  // One schema query keeps the operator invocation within the Free D1 query cap.
  const schemas = await db.query('SELECT j.value AS table_name,p.* FROM json_each(?) j,pragma_table_info(j.value) p ORDER BY j.key,p.cid',[JSON.stringify(TABLES)]);
  for (const table of TABLES) {
    const schema = schemas.filter(x=>x.table_name===table);
    if (!schema.length || !schema.some(x=>x.name==='library_id')) fail('RECOVERY_SCHEMA_MISSING');
    const columns = schema.map(x=>x.name);
    if (columns.some(c=>!/^[a-z_]+$/.test(c))) fail('RECOVERY_SCHEMA_CHANGED');
    const order = schema.filter(x=>x.pk).sort((a,b)=>a.pk-b.pk).map(x=>quote(x.name)).join(',');
    if (!order) fail('RECOVERY_SCHEMA_CHANGED');
    const sql = 'SELECT json_group_array(json_array(' +
      columns.map(c=>'CAST('+quote(c)+' AS TEXT)').join(',') +
      ')) AS state FROM (SELECT * FROM '+quote(table)+' WHERE library_id=? ORDER BY '+order+')';
    const rows = await db.query(sql,[library]);
    const state = rows[0]?.state;
    if (typeof state !== 'string' || Buffer.byteLength(state)>512*1024) fail('SNAPSHOT_LIMIT');
    result.push({table,columns,sql,state});
  }
  return result;
}
export function authorityBlockers(authority, scope, requireDisabled = true) {
  const b = [], expected = {
    ...(requireDisabled?{ENABLE_CATALOG_API:'false',ENABLE_RECOVERY_API:'false',ENABLE_REPLACEMENT_API:'false'}:{}),
    LIBRARY_ID:scope.library,SERVICE_GENERATION:scope.generation
  };
  if (!authority || typeof authority.deployment !== 'string' || typeof authority.version !== 'string') return ['ACTIVE_DEPLOYMENT_UNVERIFIED'];
  for (const [k,v] of Object.entries(expected)) if (authority.vars?.[k] !== v) b.push('WORKER_SETTING_'+k);
  if (authority.database !== scope.database || authority.bucket !== scope.bucket) b.push('WORKER_BINDING_MISMATCH');
  if (scope.fence && (authority.control!==scope.fence.control || authority.vars?.FENCE_RESOURCE!==scope.fence.resource ||
    authority.operator?.database!==scope.database || authority.operator?.bucket!==scope.bucket ||
    authority.operator?.control!==scope.fence.control || authority.operator?.resource!==scope.fence.resource)) b.push('FENCE_BINDING_MISMATCH');
  return b;
}
export function fenceBlockers(f,scope) {
  if (!scope.fence || !f || f.state!=='HELD' || f.pending!==0 || f.running!==0 ||
    f.provenance!=='fresh-resources-v1' || f.resource_id!==scope.fence.resource ||
    f.database_id!==scope.database || f.bucket_name!==scope.bucket || f.library_id!==scope.library ||
    f.next_generation!==scope.generation || !archiveId.test(f.maintenance_id) || typeof f.revision!=='string') return ['STALE_WORKER_FENCE_UNAVAILABLE'];
  return [];
}
export async function inspect({store,db,authority,fence}, scope) {
  validateScope(scope);
  const before = await authority(), fenceBefore=scope.fence&&fence?await fence():null;
  const physical = await inventory(store,scope), archives=[];
  for (const o of physical.objects) archives.push(await verifyArchive(store,scope,o));
  const state = await snapshot(db,scope.library);
  if (canonical(await inventory(store,scope)) !== canonical(physical)) fail('INVENTORY_CHANGED');
  const after = await authority();
  if (canonical(after)!==canonical(before)) fail('DEPLOYMENT_CHANGED');
  const blockers = authorityBlockers(after,scope);
  if (physical.uploads.length) blockers.push('INCOMPLETE_MULTIPART_UPLOADS');
  const indexed = name => {
    const t=state.find(x=>x.table===name);
    return JSON.parse(t.state).map(row=>Object.fromEntries(t.columns.map((c,n)=>[c,row[n]])));
  };
  if (indexed('cloud_head').length !== 1) blockers.push('CATALOG_HEAD_MISSING');
  if (indexed('cloud_devices').some(d=>d.service_generation===scope.generation) ||
    indexed('cloud_backup_control').some(c=>c.service_generation===scope.generation) ||
    archives.some(a=>a.service===scope.generation)) blockers.push('FRESH_EXTERNAL_GENERATION_REQUIRED');
  const fenceAfter=scope.fence&&fence?await fence():null;
  if (canonical(fenceBefore)!==canonical(fenceAfter)) fail('MAINTENANCE_CHANGED');
  blockers.push(...fenceBlockers(fenceAfter,scope));
  return {format:1,scope,authority:after,...(scope.fence?{fence:fenceAfter}:{}),archives:archives.sort((a,b)=>Number(a.created)-Number(b.created)||a.archive.localeCompare(b.archive)),
    uploads:physical.uploads,snapshot:state,blockers:[...new Set(blockers)]};
}
export async function makePlan(adapters,scope) {
  const body = await inspect(adapters,scope);
  return {body,digest:seal(body)};
}
export async function verifyPlan(adapters,plan) {
  if (!plan?.body || plan.digest!==seal(plan.body)) fail('PLAN_SEAL_MISMATCH');
  const current=await makePlan(adapters,validateScope(plan.body.scope));
  if (current.digest!==plan.digest) fail('PLAN_STALE');
  return current;
}
/** Batch compiler, never an execution bypass. The authenticated executor regenerates
 * the full plan and claims the external fence before submitting this transaction.
 * Legacy blocked plans are compiled only by the exclusively owned local tests. */
export function rebuildBatch(plan) {
  if (!plan?.body || plan.digest!==seal(plan.body)) fail('PLAN_SEAL_MISMATCH');
  const p=plan.body, library=p.scope.library;
  if (p.blockers.some(x=>x!=='STALE_WORKER_FENCE_UNAVAILABLE')) fail('PLAN_BLOCKED');
  const statement = (sql,...params)=>({sql,params}), batch=[];
  // The assertion table is intentionally empty after each guarded batch.
  batch.push(statement('INSERT INTO cloud_commit_assertion(slot,ok) VALUES(1,CASE WHEN '+
    p.snapshot.map(s=>'(('+s.sql+')=?)').join(' AND ')+' THEN 1 ELSE 0 END)',
    ...p.snapshot.flatMap(s=>[library,s.state])));
  batch.push(statement('DELETE FROM cloud_commit_assertion WHERE slot=1'));
  batch.push(statement('UPDATE cloud_devices SET revoked=1,can_write=0 WHERE library_id=?',library));
  batch.push(statement('UPDATE cloud_replace_grants SET can_replace=0 WHERE library_id=?',library));
  for (const table of ['cloud_backup_approvals','cloud_replace_approvals','cloud_uploads']) batch.push(statement('DELETE FROM '+table+' WHERE library_id=?',library));
  batch.push(statement("UPDATE cloud_backup_operations SET state='CANCELED',error='OPERATOR_RECONCILED' WHERE library_id=? AND state NOT IN('DONE','CANCELED')",library));
  batch.push(statement("UPDATE cloud_replace_attempts SET state='CANCELED' WHERE library_id=? AND state IN('ACTIVE','STALE')",library));
  batch.push(statement('DELETE FROM cloud_backup_slots WHERE library_id=?',library));
  batch.push(statement('INSERT INTO cloud_backup_control(library_id,service_generation,revision,next_ordinal,last_replace_at) VALUES(?,?,1,?,0) ON CONFLICT(library_id) DO UPDATE SET service_generation=excluded.service_generation,revision=1,next_ordinal=excluded.next_ordinal,last_replace_at=0',library,p.scope.generation,p.archives.length+1));
  p.archives.forEach((a,i)=>batch.push(statement("INSERT INTO cloud_backup_slots(library_id,slot,archive_id,object_key,owner_device,source_service,manifest,digest,ordinal,created_at,state,object_etag,object_version,next_part,verify_part) VALUES(?,?,?,?,?,?,?,?,?,CAST(? AS INTEGER),'READY',?,NULL,?,?)",
    library,a.slot,a.archive,a.key,a.device,a.service,JSON.stringify(a.manifest),a.digest,i+1,a.created,a.etag,a.manifest.parts.length+1,a.manifest.parts.length+1)));
  return batch;
}
export function refuseLiveApply() { fail('STALE_WORKER_FENCE_UNAVAILABLE'); }
