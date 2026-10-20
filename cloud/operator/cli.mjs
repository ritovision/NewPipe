#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
import {readFile,writeFile} from 'node:fs/promises';
import {parseArgs} from 'node:util';
import {makePlan,verifyPlan,refuseLiveApply,OperatorError,validateScope} from './recovery.mjs';
import {liveAdapters} from './adapters.mjs';
import {RecoveryError} from '../src/recovery-format.ts';

const help = `NewPipe recovery inspector (Node 24)
  node operator/cli.mjs inspect --scope scope.json --out private-plan.json
  node operator/cli.mjs verify --plan private-plan.json
  node operator/cli.mjs apply --plan private-plan.json

Scope JSON has exactly: account, worker, database, bucket, library, generation.
Credentials: NEWPIPE_CLOUDFLARE_API_TOKEN (Worker read + D1 read),
NEWPIPE_R2_ACCESS_KEY_ID and NEWPIPE_R2_SECRET_ACCESS_KEY (one bucket, object read).
Inspect reads actual deployed bindings, D1 and every archive; output is private.
Verify rereads the same authorities and bytes and rejects a stale plan.
Apply currently refuses: an external stale-worker fence is not implemented.
No command deploys, enrolls, executes Time Travel, writes D1, deletes objects or aborts uploads.
See ../doc/architecture/cloud-operator-recovery.md.`;
try {
  const {values,positionals}=parseArgs({allowPositionals:true,options:{scope:{type:'string'},out:{type:'string'},plan:{type:'string'},help:{type:'boolean'}}});
  if(values.help) { console.log(help); }
  else {
    if(positionals.length!==1) throw new OperatorError('COMMAND_REQUIRED');
    const mode=positionals[0];
    if(mode==='apply') refuseLiveApply();
    if(mode==='inspect') {
      if(!values.scope||!values.out||values.plan) throw new OperatorError('SCOPE_AND_NEW_OUTPUT_REQUIRED');
      const scope=validateScope(JSON.parse(await readFile(values.scope,'utf8')));
      const plan=await makePlan(liveAdapters(scope),scope);
      await writeFile(values.out,JSON.stringify(plan,null,2)+'\n',{flag:'wx',mode:0o600});
      console.log(JSON.stringify({digest:plan.digest,archives:plan.body.archives.length,
        bytes:plan.body.archives.reduce((n,a)=>n+a.size,0),incompleteUploads:plan.body.uploads.length,blockers:plan.body.blockers}));
    } else if(mode==='verify') {
      if(!values.plan||values.scope||values.out) throw new OperatorError('PLAN_REQUIRED');
      const plan=JSON.parse(await readFile(values.plan,'utf8'));
      await verifyPlan(liveAdapters(validateScope(plan.body?.scope)),plan);
      console.log(JSON.stringify({unchanged:true,digest:plan.digest,blockers:plan.body.blockers}));
    } else throw new OperatorError('UNKNOWN_COMMAND');
  }
} catch(e) {
  // No raw provider message, request body, archive metadata or credentials in logs.
  console.error(JSON.stringify({error:e instanceof OperatorError||e instanceof RecoveryError?e.code:'OPERATOR_INSPECTION_FAILED'}));
  process.exitCode=1;
}
