// SPDX-License-Identifier: GPL-3.0-or-later
import {S3Client,ListObjectsV2Command,ListMultipartUploadsCommand,HeadObjectCommand,GetObjectCommand} from '@aws-sdk/client-s3';
import {fail,canonical,validateScope} from './recovery.mjs';

const cleanEtag = value => typeof value==='string' ? value.replace(/^"|"$/g,'') : null;
const date = value => value instanceof Date && Number.isFinite(value.valueOf()) ? value.toISOString() : null;
export function s3Store(client,bucket) {
  return {
    async list(kind,prefix,cursor) {
      if (kind==='objects') {
        const r=await client.send(new ListObjectsV2Command({Bucket:bucket,Prefix:prefix,MaxKeys:1000,
          ...(cursor===null?{}:{ContinuationToken:cursor})}));
        return {items:(r.Contents??[]).map(o=>({key:o.Key,etag:cleanEtag(o.ETag),size:o.Size,modified:date(o.LastModified)})),
          truncated:r.IsTruncated,next:r.IsTruncated?r.NextContinuationToken??null:null};
      }
      if (kind!=='uploads') fail('INVALID_INVENTORY_KIND');
      const r=await client.send(new ListMultipartUploadsCommand({Bucket:bucket,Prefix:prefix,MaxUploads:1000,
        ...(cursor===null?{}:{KeyMarker:cursor.key,UploadIdMarker:cursor.uploadId})}));
      return {items:(r.Uploads??[]).map(u=>({key:u.Key,uploadId:u.UploadId,initiated:date(u.Initiated)})),
        truncated:r.IsTruncated,next:r.IsTruncated&&r.NextKeyMarker&&r.NextUploadIdMarker
          ?{key:r.NextKeyMarker,uploadId:r.NextUploadIdMarker}:null};
    },
    async head(key) {
      const r=await client.send(new HeadObjectCommand({Bucket:bucket,Key:key}));
      return {key,etag:cleanEtag(r.ETag),size:r.ContentLength,modified:date(r.LastModified),metadata:r.Metadata?.recovery};
    },
    async read(key,offset,length,etag) {
      const r=await client.send(new GetObjectCommand({Bucket:bucket,Key:key,Range:`bytes=${offset}-${offset+length-1}`,IfMatch:'"'+etag+'"'}));
      if (r.ContentLength!==length || cleanEtag(r.ETag)!==etag || !r.Body ||
        !r.ContentRange?.startsWith(`bytes ${offset}-${offset+length-1}/`)) fail('OBJECT_RANGE_MISMATCH');
      // Bound before accumulating; Content-Length alone is not a memory bound.
      const output=new Uint8Array(length); let count=0;
      for await (const chunk of r.Body) {
        if (count+chunk.length>length) { r.Body.destroy?.(); fail('OBJECT_RANGE_OVERFLOW'); }
        output.set(chunk,count);count+=chunk.length;
      }
      if(count!==length) fail('TRUNCATED_OBJECT_RANGE');
      return output;
    }
  };
}
export function cloudflareClient(scope,token,fetcher=fetch) {
  validateScope(scope);
  if (typeof token!=='string'||!token) fail('CLOUDFLARE_TOKEN_REQUIRED');
  const base='https://api.cloudflare.com/client/v4/accounts/'+scope.account;
  const call=async(path,body)=>{
    const r=await fetcher(base+path,{method:body===undefined?'GET':'POST',redirect:'error',
      headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},
      ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});
    // Do not surface response text: provider errors may contain request data.
    if(!r.ok) fail('CLOUDFLARE_HTTP_'+r.status);
    const value=await r.json();
    if(value.success!==true||value.result==null) fail('CLOUDFLARE_RESPONSE_FAILED');
    return value.result;
  };
  const script='/workers/scripts/'+encodeURIComponent(scope.worker);
  async function authority() {
    const deploymentList=await call(script+'/deployments');
    const d=deploymentList?.deployments?.[0];
    if(!d||d.versions?.length!==1||d.versions[0].percentage!==100) fail('SPLIT_OR_MISSING_DEPLOYMENT');
    const v=await call(script+'/versions/'+encodeURIComponent(d.versions[0].version_id));
    const settings=await call(script+'/settings');
    const relevant=bindings=>{
      if(!Array.isArray(bindings)) fail('WORKER_BINDINGS_UNAVAILABLE');
      const names=['DB','RECOVERY','ENABLE_CATALOG_API','ENABLE_RECOVERY_API','ENABLE_REPLACEMENT_API','LIBRARY_ID','SERVICE_GENERATION'];
      const out={};
      for(const n of names){
        const found=bindings.filter(b=>b.name===n);
        if(found.length!==1) fail('WORKER_BINDING_MISSING_OR_DUPLICATE');
        const b=found[0];
        out[n]=n==='DB'?(b.type==='d1'?b.id:null):n==='RECOVERY'?(b.type==='r2_bucket'?b.bucket_name:null):(b.type==='plain_text'?b.text:null);
      }
      return out;
    };
    const b=relevant(v.resources?.bindings);
    if(v.id!==d.versions[0].version_id||canonical(b)!==canonical(relevant(settings.bindings))) fail('WORKER_SETTINGS_VERSION_MISMATCH');
    const latest=(await call(script+'/deployments'))?.deployments?.[0];
    if(canonical(latest)!==canonical(d)) fail('DEPLOYMENT_CHANGED');
    const {DB,RECOVERY,...vars}=b;
    return {deployment:d.id,version:v.id,database:DB,bucket:RECOVERY,vars};
  }
  const db={async query(sql,params){
    // This adapter deliberately offers reads only. No public mutation or SQL input.
    if(!sql.startsWith('SELECT ')&&!sql.startsWith('PRAGMA table_info(')) fail('READ_ONLY_ADAPTER');
    const r=await call('/d1/database/'+scope.database+'/query',{sql,params});
    if(!Array.isArray(r)||r.length!==1||r[0].success!==true||!Array.isArray(r[0].results)) fail('INCOMPLETE_D1_RESPONSE');
    return r[0].results;
  }};
  return {db,authority};
}
export function liveAdapters(scope,env=process.env) {
  validateScope(scope);
  const accessKeyId=env.NEWPIPE_R2_ACCESS_KEY_ID,secretAccessKey=env.NEWPIPE_R2_SECRET_ACCESS_KEY;
  if(!accessKeyId||!secretAccessKey) fail('SCOPED_R2_CREDENTIALS_REQUIRED');
  const client=new S3Client({region:'auto',endpoint:'https://'+scope.account+'.r2.cloudflarestorage.com',
    credentials:{accessKeyId,secretAccessKey},maxAttempts:2,requestChecksumCalculation:'WHEN_REQUIRED',responseChecksumValidation:'WHEN_REQUIRED'});
  return {...cloudflareClient(scope,env.NEWPIPE_CLOUDFLARE_API_TOKEN),store:s3Store(client,scope.bucket)};
}
