// SPDX-License-Identifier: GPL-3.0-or-later
// Executed black-box proof that fresh provisioning writes one consistent
// service-generation identity across the external fence, app, operator, and scope.
// Global fetch is replaced *before* the provision CLI loads; no Cloudflare calls.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {spawnSync} from 'node:child_process';

const mock = `
const data='11111111-1111-4111-8111-111111111111';
const control='22222222-2222-4222-8222-222222222222';
const seen=[];
globalThis.fetch=async(url,options={})=>{
 const path=new URL(url).pathname.replace(/^\\/client\\/v4\\/accounts\\/[0-9a-f]{32}/,'');
 const method=options.method??'GET';
 let result;
 if(method==='GET' && path==='/d1/database')result=[];
 else if(method==='GET' && path==='/r2/buckets')result={buckets:[]};
 else if(method==='GET' && path==='/workers/scripts')result=[];
 else if(method==='GET' && path==='/workers/subdomain')result={subdomain:'example-test'};
 else if(method==='POST' && path==='/d1/database'){
  const b=JSON.parse(options.body);const isControl=b.name.endsWith('-ctl');
  result={uuid:isControl?control:data,name:b.name};
 }else if(method==='POST' && path==='/r2/buckets'){
  const b=JSON.parse(options.body);result={name:b.name,creation_date:'2026-10-22T00:00:00Z'};
 }else if(method==='POST' && /^\\/d1\\/database\\/[0-9a-f-]{36}\\/query$/.test(path)){
  const b=JSON.parse(options.body);
  if(b.sql.startsWith('INSERT INTO cloud_fence('))seen.push({kind:'fence',generation:b.params[4]});
  result=[{success:true,results:[],meta:{rows_read:0,rows_written:1,size_after:16384}}];
 }else throw new Error('Unrecognized fake Cloudflare API call: '+method+' '+path);
 return new Response(JSON.stringify({success:true,result}),{status:200,headers:{'content-type':'application/json'}});
};
process.on('exit',()=>require('node:fs').writeFileSync(process.env.PROVISION_FAKE_LOG,JSON.stringify(seen)));
`;

test('fresh provisioner uses the identical service generation for scope, operator, app and external fence',async()=>{
 const sandbox=await mkdtemp(join(tmpdir(),'newpipe-provision-identity-'));
 try{
  const shim=join(sandbox,'mock.cjs'),ledger=join(sandbox,'mock-log.json'),output=join(sandbox,'private','provision.json');
  await mkdir(join(sandbox,'private'));
  await writeFile(shim,mock);
  const cwd=resolve(import.meta.dirname,'..');
  const result=spawnSync(process.execPath,['--require',shim,resolve(cwd,'operator/provision.mjs'),
   '--account','a'.repeat(32),'--prefix','newpipe-provision-local-qa','--out',output],{
   cwd,env:{...process.env,NEWPIPE_CLOUDFLARE_API_TOKEN:'fake-local-test-token',PROVISION_FAKE_LOG:ledger},
   encoding:'utf8',timeout:20000});
  assert.equal(result.status,0,`Fake-only CLI should succeed: ${result.stderr.slice(-900)}`);
  const read=async(name)=>JSON.parse(await readFile(join(sandbox,'private',name),'utf8'));
  const p=await read('provision.json'),scope=await read('scope.json'),app=await read('application.jsonc'),operator=await read('operator.jsonc');
  const records=JSON.parse(await readFile(ledger,'utf8'));
  assert.deepEqual(p.scope,scope);
  assert.equal(scope.generation,p.activeGeneration);
  assert.equal(app.vars.SERVICE_GENERATION,scope.generation);
  assert.equal(JSON.parse(operator.vars.SCOPE).generation,scope.generation);
  assert.deepEqual(records,[{kind:'fence',generation:scope.generation}]);
  assert.notEqual(scope.database,scope.fence.control,'DATA and CONTROL must be separate');
  assert.equal(app.vars.ENABLE_CATALOG_API,'false');
  assert.equal(app.vars.ENABLE_RECOVERY_API,'false');
  assert.equal(app.vars.ENABLE_HOUSEKEEPING,'false');
  assert.equal(app.vars.ENABLE_REPLACEMENT_API,'false');
 }finally{await rm(sandbox,{recursive:true,force:true});}
});
