#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
import {readFile,writeFile} from 'node:fs/promises';
import {parseArgs} from 'node:util';
import {makePlan,verifyPlan,OperatorError,validateScope,seal} from './recovery.mjs';
import {liveAdapters} from './adapters.mjs';
import {cloudflareClient,operatorClient} from './cloudflare.mjs';
import {RecoveryError} from '../src/recovery-format.ts';
const help=`NewPipe guarded recovery operator (Node 24)
  node operator/cli.mjs inspect --scope scope.json --out private-plan.json
  node operator/cli.mjs verify --plan private-plan.json
  node operator/cli.mjs close --scope scope.json --maintenance UUID
  node operator/cli.mjs state --scope scope.json
  node operator/cli.mjs bookmark --scope scope.json
  node operator/cli.mjs restore --scope scope.json --bookmark BOOKMARK --out private-action.json
  node operator/cli.mjs abort --plan private-plan.json --key KEY --upload ID --out private-action.json
  node operator/cli.mjs apply --plan private-plan.json
  node operator/cli.mjs status --scope scope.json --action DIGEST --kind apply|restore|abort

Legacy scopes are read-only. Fenced scopes add fence:{control,resource,operator}.
Only freshly provisioned isolated data DB/bucket pairs can be registered.
Credentials: NEWPIPE_CLOUDFLARE_API_TOKEN, NEWPIPE_R2_ACCESS_KEY_ID,
NEWPIPE_R2_SECRET_ACCESS_KEY; mutations also need NEWPIPE_OPERATOR_TOKEN.
Restore passes the Cloudflare token transiently to the exact account's operator;
the Worker never stores it. No automatic retries after an uncertain result.
Close is durable and has no timeout. There is no force, reset or reopen command.
No command reenrolls devices or enables APIs. See ../doc/architecture/cloud-operator-recovery.md.`;
const fromFile=async path=>JSON.parse(await readFile(path,'utf8'));
try{
 const {values:v,positionals}=parseArgs({allowPositionals:true,options:Object.fromEntries(
  ['scope','out','plan','maintenance','bookmark','key','upload','action','kind'].map(k=>[k,{type:'string'}]).concat([['help',{type:'boolean'}]]))});
 if(v.help)console.log(help);
 else{
  if(positionals.length!==1)throw new OperatorError('COMMAND_REQUIRED');
  const mode=positionals[0],plan=v.plan?await fromFile(v.plan):null;
  const scope=validateScope(plan?.body?.scope??(v.scope?await fromFile(v.scope):null));
  if(mode==='inspect'){
   if(!v.scope||!v.out||v.plan)throw new OperatorError('SCOPE_AND_NEW_OUTPUT_REQUIRED');
   const p=await makePlan(liveAdapters(scope),scope);
   await writeFile(v.out,JSON.stringify(p,null,2)+'\n',{flag:'wx',mode:0o600});
   console.log(JSON.stringify({digest:p.digest,archives:p.body.archives.length,bytes:p.body.archives.reduce((n,a)=>n+a.size,0),
    incompleteUploads:p.body.uploads.length,blockers:p.body.blockers}));
  }else if(mode==='verify'){
   if(!plan||v.scope||v.out)throw new OperatorError('PLAN_REQUIRED');
   await verifyPlan(liveAdapters(scope),plan);console.log(JSON.stringify({unchanged:true,digest:plan.digest,blockers:plan.body.blockers}));
  }else{
   if(!scope.fence)throw new OperatorError('STALE_WORKER_FENCE_UNAVAILABLE');
   const cloudflareToken=process.env.NEWPIPE_CLOUDFLARE_API_TOKEN,cf=cloudflareClient(scope,cloudflareToken);
   if(mode==='bookmark'){console.log(JSON.stringify(await cf.bookmark()));}
   else{
    const authority=await cf.authority(),call=operatorClient(authority.operator.origin,process.env.NEWPIPE_OPERATOR_TOKEN);
    let result;
    if(mode==='close'){
     if(!v.maintenance)throw new OperatorError('MAINTENANCE_ID_REQUIRED');
     result=await call('/maintenance/close',{scope,maintenance:v.maintenance});
    }else if(mode==='state')result=await call('/maintenance/state',{scope});
    else if(mode==='status'){
     if(!v.action||!['apply','restore','abort'].includes(v.kind))throw new OperatorError('ACTION_AND_KIND_REQUIRED');
     result=await call('/recovery/status',{scope,action:v.action,kind:v.kind,cloudflareToken});
    }else if(mode==='apply'){
     if(!plan)throw new OperatorError('PLAN_REQUIRED');
     // Server revalidates exact SQL/bytes and fence before claiming. Status on a
     // replay resolves an existing receipt without resubmitting the batch.
     result=await call('/recovery/apply',{plan,cloudflareToken,r2Credentials:{accessKeyId:process.env.NEWPIPE_R2_ACCESS_KEY_ID,secretAccessKey:process.env.NEWPIPE_R2_SECRET_ACCESS_KEY}});
    }else if(mode==='restore'||mode==='abort'){
     if(!v.out)throw new OperatorError('NEW_PRIVATE_ACTION_FILE_REQUIRED');
     const f=await cf.fence();
     const intent=mode==='restore'?{kind:mode,maintenance:f.maintenance_id,revision:f.revision,bookmark:v.bookmark}:
      {kind:mode,maintenance:f.maintenance_id,revision:f.revision,key:v.key,uploadId:v.upload};
     if(mode==='restore'&&!v.bookmark)throw new OperatorError('EXACT_BOOKMARK_REQUIRED');
     if(mode==='abort'){
      if(!plan||!plan.body.uploads.some(u=>u.key===v.key&&u.uploadId===v.upload))throw new OperatorError('UPLOAD_NOT_IN_PLAN');
      await verifyPlan(liveAdapters(scope),plan);
     }
     const action=seal(intent);
     // Save the exact retry/status identity before a possibly ambiguous call.
     await writeFile(v.out,JSON.stringify({scope,action,intent},null,2)+'\n',{flag:'wx',mode:0o600});
     result=await call('/recovery/'+mode,{scope,cloudflareToken,...intent});
    }else throw new OperatorError('UNKNOWN_COMMAND');
    console.log(JSON.stringify(result));
   }
  }
 }
}catch(e){
 console.error(JSON.stringify({error:e instanceof OperatorError||e instanceof RecoveryError?e.code:'OPERATOR_REQUEST_FAILED'}));
 process.exitCode=1;
}
