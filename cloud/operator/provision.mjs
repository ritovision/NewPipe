#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Fresh resources only: there is intentionally no "adopt existing storage" path.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {parseArgs} from 'node:util';
import {operatorAliases} from './build-aliases.mjs';
import {OperatorError,fail,validateScope} from './recovery.mjs';
const {values:v}=parseArgs({options:{account:{type:'string'},prefix:{type:'string'},out:{type:'string'}}});
try{
 if(!/^[0-9a-f]{32}$/.test(v.account??'')||!/^newpipe-[a-z0-9-]{3,39}$/.test(v.prefix??'')||!v.out)fail('ACCOUNT_NEWPIPE_PREFIX_AND_NEW_OUTPUT_REQUIRED');
 const token=process.env.NEWPIPE_CLOUDFLARE_API_TOKEN;if(!token)fail('CLOUDFLARE_TOKEN_REQUIRED');
 const base='https://api.cloudflare.com/client/v4/accounts/'+v.account;
 const call=async(path,body)=>{
  const r=await fetch(base+path,{method:body===undefined?'GET':'POST',redirect:'error',
   headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},
   ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});
  if(!r.ok)fail('PROVISION_HTTP_'+r.status);
  const j=await r.json();if(!j.success)fail('PROVISION_RESPONSE_FAILED');return j.result;
 };
 const databases=await call('/d1/database?per_page=100');
 if(!Array.isArray(databases)||databases.length>8)fail('FREE_DATABASE_HEADROOM_REQUIRED');
 const buckets=await call('/r2/buckets?per_page=1000');
 if(!Array.isArray(buckets.buckets)||buckets.buckets.length>=1000)fail('BUCKET_INVENTORY_UNVERIFIED');
 const names={data:v.prefix,control:v.prefix+'-ctl',bucket:v.prefix,worker:v.prefix,operator:v.prefix+'-op'};
 if(databases.some(d=>d.name===names.data||d.name===names.control)||buckets.buckets.some(b=>b.name===names.bucket))fail('FRESH_NAMES_REQUIRED');
 const scripts=await call('/workers/scripts');
 if(!Array.isArray(scripts)||scripts.some(s=>s.id===names.worker||s.id===names.operator))fail('FRESH_WORKERS_REQUIRED');
 const sub=(await call('/workers/subdomain')).subdomain;
 if(typeof sub!=='string'||!/^[a-z0-9-]+$/.test(sub))fail('WORKERS_SUBDOMAIN_UNVERIFIED');
 const record={format:1,account:v.account,names,createdAt:new Date().toISOString(),steps:[],activeGeneration:randomUUID()};
 await mkdir(dirname(resolve(v.out)),{recursive:true,mode:0o700});
 await writeFile(v.out,JSON.stringify(record,null,2)+'\n',{flag:'wx',mode:0o600});
 const checkpoint=async(step,result)=>{record.steps.push({step,result});await writeFile(v.out,JSON.stringify(record,null,2)+'\n',{mode:0o600});};
 const data=await call('/d1/database',{name:names.data});await checkpoint('create-data',{uuid:data.uuid,name:data.name});
 const control=await call('/d1/database',{name:names.control});await checkpoint('create-control',{uuid:control.uuid,name:control.name});
 const bucket=await call('/r2/buckets',{name:names.bucket});await checkpoint('create-bucket',{name:bucket.name,creation_date:bucket.creation_date});
 if(data.name!==names.data||control.name!==names.control||bucket.name!==names.bucket)fail('CREATION_SCOPE_MISMATCH');
 const scope=validateScope({account:v.account,worker:names.worker,database:data.uuid,bucket:names.bucket,
  library:'newpipe-fence-qa-'+randomUUID(),generation:randomUUID(),fence:{control:control.uuid,resource:randomUUID(),operator:names.operator}});
 const sql=async(id,statement,params=[])=>{
  const r=await call('/d1/database/'+id+'/query',{sql:statement,params});
  if(!Array.isArray(r)||r.length!==1||!r[0].success)fail('PROVISION_SCHEMA_FAILED');return r[0].results;
 };
 const root=dirname(dirname(fileURLToPath(import.meta.url)));
 for(const [id,files] of [[data.uuid,['migrations/0001_catalog_service.sql','migrations/0002_recovery.sql','migrations/0003_replacement.sql','migrations/0004_operator_receipts.sql','migrations/0005_reopen_receipts.sql','migrations/0006_housekeeping.sql']],
  [control.uuid,['control-migrations/0001_fence.sql','control-migrations/0002_reopen.sql']]]){
  for(const file of files){
   const statements=(await readFile(resolve(root,file),'utf8')).replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
   for(const statement of statements)await sql(id,statement);
   await checkpoint('schema',{database:id,file});
  }
 }
 await sql(control.uuid,"INSERT INTO cloud_fence(resource_id,database_id,bucket_name,library_id,service_generation,provenance,state,created_at) VALUES(?,?,?,?,?,'fresh-resources-v1','OPEN',?)",
  [scope.fence.resource,scope.database,scope.bucket,scope.library,record.activeGeneration,Date.now()]);
 record.scope=scope;record.origin='https://'+names.worker+'.'+sub+'.workers.dev';
 record.operatorOrigin='https://'+names.operator+'.'+sub+'.workers.dev';
 const common={account_id:scope.account,compatibility_date:'2026-10-19',workers_dev:true,preview_urls:false,
  d1_databases:[{binding:'DB',database_name:names.data,database_id:scope.database,migrations_dir:resolve(root,'migrations')},
   {binding:'CONTROL',database_name:names.control,database_id:scope.fence.control,migrations_dir:resolve(root,'control-migrations')}],
  r2_buckets:[{binding:'RECOVERY',bucket_name:scope.bucket}],observability:{enabled:false}};
 const application={...common,name:names.worker,main:resolve(root,'src/index.ts'),
  vars:{PUBLIC_ORIGIN:record.origin,LIBRARY_ID:scope.library,SERVICE_GENERATION:record.activeGeneration,FENCE_RESOURCE:scope.fence.resource,
   ENABLE_CATALOG_API:'false',ENABLE_RECOVERY_API:'false',ENABLE_REPLACEMENT_API:'false',MAX_RECOVERY_BYTES:'1048576',MAX_CATALOG_BYTES:'65536',MAX_CATALOG_PAGES:'8'},
  ratelimits:[{name:'REQUEST_LIMITER',namespace_id:'2026102002',simple:{limit:120,period:60}}]};
 const operator={...common,name:names.operator,main:resolve(root,'operator/worker.mjs'),compatibility_flags:['nodejs_compat'],alias:operatorAliases,
  vars:{PUBLIC_ORIGIN:record.operatorOrigin,FENCE_RESOURCE:scope.fence.resource,SCOPE:JSON.stringify(scope)}};
 for(const [name,value]of [['scope.json',scope],['application.jsonc',application],['operator.jsonc',operator]])
  await writeFile(resolve(dirname(v.out),name),JSON.stringify(value,null,2)+'\n',{flag:'wx',mode:0o600});
 await checkpoint('registered-fresh-resources',{resource:scope.fence.resource});
 console.log(JSON.stringify({provisioned:true,worker:names.worker,operator:names.operator,apisEnabled:false}));
}catch(e){console.error(JSON.stringify({error:e instanceof OperatorError?e.code:'PROVISION_OUTCOME_UNKNOWN'}));process.exitCode=1;}
