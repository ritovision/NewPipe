// SPDX-License-Identifier: GPL-3.0-or-later
// Synthetic, fresh-resource QA only. Never use this fixture against a user library.
import {readFile,writeFile} from 'node:fs/promises';
import {randomBytes,randomUUID} from 'node:crypto';
import {resolve} from 'node:path';
import {fingerprint} from '../src/catalog.ts';
import {encodePages,changedPages,pageManifest,sha256,byteLength} from '../src/pages.ts';
import {makeUpload} from '../src/service.ts';
import {bytesDigest,cloudArchiveHeader,cloudArchivePage} from '../src/recovery-format.ts';
const dir=process.argv[2],record=JSON.parse(await readFile(resolve(dir,'provision.json'),'utf8')),s=record.scope;
if(!s.library.startsWith('newpipe-fence-qa-')||!s.worker.startsWith('newpipe-fence-qa-')||record.steps.at(-1)?.step!=='registered-fresh-resources')throw new Error('SYNTHETIC_FRESH_RESOURCES_REQUIRED');
const cf=async(path,payload)=>{
 const r=await fetch('https://api.cloudflare.com/client/v4/accounts/'+s.account+path,{method:payload===undefined?'GET':'POST',
  headers:{Authorization:'Bearer '+process.env.NEWPIPE_CLOUDFLARE_API_TOKEN,'Content-Type':'application/json'},...(payload===undefined?{}:{body:JSON.stringify(payload)}),signal:AbortSignal.timeout(30000)});
 const j=await r.json();if(!r.ok||!j.success)throw new Error('FIXTURE_PROVIDER_REJECTED');return j.result;
};
const sql=async(query,params=[])=>{const r=await cf('/d1/database/'+s.database+'/query',{sql:query,params});if(!r[0]?.success)throw new Error('FIXTURE_SQL_FAILED');return r[0].results;};
const fixtures=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
const empty=fixtures.catalogs.find(c=>c.name==='empty').catalog,catalog=fixtures.catalogs.find(c=>c.name==='base').catalog;
const device='fence-qa-'+randomUUID(),secret=randomBytes(32).toString('hex'),token=device+'.'+secret;
await writeFile(resolve(dir,'device.json'),JSON.stringify({device,token})+'\n',{flag:'wx',mode:0o600});
if((await sql('SELECT COUNT(*) n FROM cloud_head'))[0].n!==0)throw new Error('EMPTY_FRESH_DB_REQUIRED');
await sql('INSERT INTO cloud_head VALUES(?,?,0,0,?)',[s.library,randomUUID(),await fingerprint(empty)]);
await sql('INSERT INTO cloud_catalog_info VALUES(?,0,0,0,?)',[s.library,await pageManifest([])]);
await sql('INSERT INTO cloud_devices VALUES(?,?,?,?,1,0,1,0)',[s.library,device,await sha256(secret),record.activeGeneration]);
await sql('INSERT INTO cloud_backup_control VALUES(?,?,0,1,0)',[s.library,record.activeGeneration]);
const call=async(path,data={},headers={})=>{
 const binary=data instanceof Uint8Array;
 const r=await fetch(record.origin+path,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':binary?'application/octet-stream':'application/json',...headers},
  body:binary?data:JSON.stringify(data),signal:AbortSignal.timeout(30000)});
 const j=await r.json();if(!r.ok)throw new Error('FIXTURE_HTTP_'+r.status+'_'+j.error);return j;
};
const initial=await call('/v1/head'),pages=await encodePages(catalog);
const upload=await makeUpload('initialize','1',initial.head,await fingerprint(catalog),changedPages([],pages));
await call('/v1/uploads/start',upload.header);
for(let i=0;i<upload.chunks.length;i++)await call('/v1/uploads/chunk',{sequence:'1',index:i,changes:upload.chunks[i]});
const ref={sequence:'1',digest:upload.header.digest};
await call('/v1/uploads/seal',ref);await call('/v1/uploads/publish',ref);
const status=await call('/v1/head'),source={head:status.head,pages:status.pages,bytes:status.bytes,manifest:status.manifest};
const data=Buffer.from(cloudArchiveHeader(source)+pages.map(cloudArchivePage).join(''));
const manifest={version:1,kind:'cloud-catalog',bytes:data.length,source,native:null,producerValidation:'catalog-pages-v1',parts:[{bytes:data.length,sha256:await bytesDigest(data)}]};
const archives=[];
for(let n=1;n<=3;n++){
 const preview=await call('/v1/recovery/preview',{manifest});
 let operation=await call('/v1/recovery/reserve',{approvalId:preview.approvalId,sequence:String(n)});
 for(let step=0;operation.state!=='DONE'&&step<12;step++){
  const ref={sequence:operation.sequence,archiveId:operation.archiveId};
  if(operation.state==='UPLOADING'){
   if(operation.nextPart===1)operation=await call('/v1/recovery/part',data,{'Content-Length':String(data.length),'X-Recovery-Sequence':ref.sequence,'X-Recovery-Archive':ref.archiveId,'X-Recovery-Part':'1'});
   else operation=await call('/v1/recovery/complete',ref);
  }else operation=await call('/v1/recovery/advance',ref);
 }
 if(operation.state!=='DONE')throw new Error('BACKUP_NOT_DONE');archives.push({archive:operation.archiveId,bytes:data.length});
}
const bookmark=await cf('/d1/database/'+s.database+'/time_travel/bookmark');
const evidence={scope:s,device,head:status.head,archives,bookmark:bookmark.bookmark,createdAt:new Date().toISOString()};
await writeFile(resolve(dir,'fixture-evidence.json'),JSON.stringify(evidence,null,2)+'\n',{flag:'wx',mode:0o600});
console.log(JSON.stringify({fixtureComplete:true,archives:archives.length,bytes:archives.reduce((n,a)=>n+a.bytes,0),bookmark:bookmark.bookmark}));
