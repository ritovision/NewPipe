// SPDX-License-Identifier: GPL-3.0-or-later
import {FetchHttpHandler} from '@smithy/fetch-http-handler';
import {S3Client,ListObjectsV2Command,ListMultipartUploadsCommand,HeadObjectCommand,GetObjectCommand} from '@aws-sdk/client-s3';
import {cloudflareClient} from './cloudflare.mjs';
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
export {cloudflareClient} from './cloudflare.mjs';
export function recoveryStore(scope,credentials) {
  validateScope(scope);
  const {accessKeyId,secretAccessKey}=credentials??{};
  if(!accessKeyId||!secretAccessKey) fail('SCOPED_R2_CREDENTIALS_REQUIRED');
  const client=new S3Client({region:'auto',endpoint:'https://'+scope.account+'.r2.cloudflarestorage.com',
    credentials:{accessKeyId,secretAccessKey},requestHandler:new FetchHttpHandler({requestTimeout:30000,customFetch:globalThis.fetch.bind(globalThis),requestInit:()=>({redirect:'manual'})}),maxAttempts:2,requestChecksumCalculation:'WHEN_REQUIRED',responseChecksumValidation:'WHEN_REQUIRED'});
  return s3Store(client,scope.bucket);
}
export function liveAdapters(scope,env=process.env) {
  return {...cloudflareClient(scope,env.NEWPIPE_CLOUDFLARE_API_TOKEN),store:recoveryStore(scope,
    {accessKeyId:env.NEWPIPE_R2_ACCESS_KEY_ID,secretAccessKey:env.NEWPIPE_R2_SECRET_ACCESS_KEY})};
}
