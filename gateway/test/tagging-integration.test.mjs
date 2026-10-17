import {beforeAll,afterAll,describe,test,expect} from 'vitest';
import {randomUUID} from 'node:crypto';
import {harness} from './helpers.mjs';
import {TOOLS,TOOL_MAP} from '../src/tools.ts';
let h;
beforeAll(async()=>{h=await harness();},30000);
afterAll(async()=>{await h?.mf.dispose();});
const scopes=['mcp.connect','tags.read','tags.write','tags.delete','library.read'];
const capabilities=['list_tag_categories','list_tags','get_item_tags','search_tagged_library','create_tag_category','rename_tag_category','delete_tag_category','create_tag','update_tag','delete_tag','set_item_tags','get_command_result'];
const mutation=device=>({device_id:device,request_id:randomUUID(),issued_at_ms:Date.now()});

describe('optional device tagging integration',()=>{
 test('new tools declare narrow independent grants and preserve old tool semantics',()=>{
  expect(TOOLS.filter(t=>capabilities.includes(t.name))).toHaveLength(12);
  expect(TOOL_MAP.get('delete_tag_category').scopes({})).toEqual(['tags.delete']);
  expect(TOOL_MAP.get('delete_tag').scopes({})).toEqual(['tags.delete']);
  expect(TOOL_MAP.get('set_item_tags').scopes({})).toEqual(['tags.write','library.read']);
  expect(TOOL_MAP.get('remove_library_items').scopes({})).toEqual(['library.delete']);
 });
 test('schema rejects missing target, unsafe IDs, missing revisions, and oversized tag sets',()=>{
  const set=TOOL_MAP.get('set_item_tags').schema;
  const item={stream_id:1,service:'YouTube',url:'https://www.youtube.com/watch?v=synthetic'};
  const base={...mutation(randomUUID()),item,tag_ids:[1],expected_revision:0};
  expect(set.safeParse(base).success).toBe(true);
  expect(set.safeParse({...base,device_id:undefined}).success).toBe(false);
  expect(set.safeParse({...base,expected_revision:undefined}).success).toBe(false);
  expect(set.safeParse({...base,tag_ids:[1,1]}).success).toBe(false);
  expect(set.safeParse({...base,tag_ids:Array.from({length:501},(_,i)=>i+1)}).success).toBe(false);
 });
 test('existing OAuth grants never acquire tag access implicitly',async()=>{
  const grant=await h.oauth(['mcp.connect','library.read','library.write','library.delete']);
  const device=await h.pairedDevice();
  const result=await h.call(grant.access_token,'create_tag_category',{...mutation(device.id),name:'Must not be created'});
  expect(result.data.code).toBe('PERMISSION_DENIED');
 });
 test('older devices reject unimplemented tag capability without dispatching',async()=>{
  const grant=await h.oauth(scopes);
  const device=await h.pairedDevice();let dispatched=0;
  const ws=await h.connectDevice(device,()=>{dispatched++;return false;});
  try{
   const result=await h.call(grant.access_token,'list_tags',{device_id:device.id});
   expect(result.data.code).toBe('UNSUPPORTED_CAPABILITY');
   expect(dispatched).toBe(0);
  }finally{ws.close();}
 });
 test('device ceiling denies new tag permissions even with a granted OAuth scope',async()=>{
  const grant=await h.oauth(scopes);
  const device=await h.pairedDevice();let dispatched=0;
  const ws=await h.connectDevice(device,()=>{dispatched++;return false;},{capabilities,permissions:['library.read']});
  try{
   const result=await h.call(grant.access_token,'create_tag',{...mutation(device.id),name:'Denied'});
   expect(result.data.code).toBe('DEVICE_PERMISSION_DENIED');expect(dispatched).toBe(0);
  }finally{ws.close();}
 });
 test('authorized tag command reaches exactly the paired device and replay is deduplicated',async()=>{
  const grant=await h.oauth(scopes);
  const device=await h.pairedDevice();let frames=[];
  const ws=await h.connectDevice(device,(frame)=>{frames.push(frame);},{capabilities,permissions:scopes.slice(1)});
  try{
   const args={...mutation(device.id),category_id:1,expected_revision:7};
   const first=await h.call(grant.access_token,'delete_tag_category',args);
   expect(first.data.outcome).toBe('COMMITTED');
   const second=await h.call(grant.access_token,'delete_tag_category',args);
   expect(second.data.replayed).toBe(true);expect(frames).toHaveLength(1);
   expect(frames[0].request.device_id).toBe(device.id);
   expect(frames[0].request.command).toEqual({type:'delete_tag_category',category_id:1,expected_revision:7});
  }finally{ws.close();}
 });
});

describe('tag result fidelity and destructive metadata',()=>{
 test('creation replay and receipt lookup retain IDs and catalog revisions but never names',async()=>{
  const grant=await h.oauth(scopes);
  const device=await h.pairedDevice();let count=0;
  const ws=await h.connectDevice(device,(frame,socket)=>{
   count++;
   socket.send(JSON.stringify({type:'result',connection_id:frame.connection_id,client_id:frame.client_id,result:{request_id:frame.request.request_id,device_id:device.id,outcome:'COMMITTED',code:'OK',data:{tag_id:23,category_id:9,catalog_revision:17,name:'DO_NOT_PERSIST_PRIVATE_TAG_NAME'}}}));
   return false;
  },{capabilities,permissions:scopes.slice(1)});
  try{
   const args={...mutation(device.id),name:'temporary synthetic name',category_id:9};
   const first=await h.call(grant.access_token,'create_tag',args);
   expect(first.data.tag_id).toBeUndefined();
   expect(first.data.data.tag_id).toBe(23);
   const second=await h.call(grant.access_token,'create_tag',args);
   expect(second.data.replayed).toBe(true);
   expect(second.data.data).toEqual({tag_id:23,category_id:9,catalog_revision:17});
   const receipt=await h.call(grant.access_token,'get_command_result',{device_id:device.id,original_request_id:args.request_id});
   expect(receipt.data.data.tag_id??receipt.data.data.result?.tag_id).toBe(23);
   expect(JSON.stringify(receipt)).not.toContain('DO_NOT_PERSIST_PRIVATE_TAG_NAME');
   expect(count).toBe(1);
  }finally{ws.close();}
 });
 test('set-item replay preserves bounded tag IDs and revision',async()=>{
  const grant=await h.oauth(scopes);
  const device=await h.pairedDevice();
  const ws=await h.connectDevice(device,(frame,socket)=>{
   socket.send(JSON.stringify({type:'result',connection_id:frame.connection_id,client_id:frame.client_id,result:{request_id:frame.request.request_id,device_id:device.id,outcome:'COMMITTED',code:'OK',data:{tag_ids:[2,8,19],revision:12}}}));return false;
  },{capabilities,permissions:scopes.slice(1)});
  try{
   const args={...mutation(device.id),item:{stream_id:1,service:'YouTube',url:'https://www.youtube.com/watch?v=synthetic'},tag_ids:[2,8,19],expected_revision:11};
   await h.call(grant.access_token,'set_item_tags',args);
   const retry=await h.call(grant.access_token,'set_item_tags',args);
   expect(retry.data.data).toEqual({tag_ids:[2,8,19],revision:12});expect(retry.data.replayed).toBe(true);
  }finally{ws.close();}
 });
});


test('MCP accurately labels tag deletion and assignment replacement as destructive',async()=>{
 const grant=await h.oauth(scopes);
 const result=await h.rpc(grant.access_token,'tools/list',{});
 const entries=new Map(result.value.result.tools.map(t=>[t.name,t]));
 for(const name of ['delete_tag','delete_tag_category','set_item_tags']) expect(entries.get(name).annotations.destructiveHint).toBe(true);
 expect(entries.get('list_tags').annotations.destructiveHint).toBe(false);
});


test('predictable Android tag rejection stays rejected in the cached MCP reply',async()=>{
 const grant=await h.oauth(scopes);
 const device=await h.pairedDevice();let count=0;
 const ws=await h.connectDevice(device,(frame,socket)=>{
  count++;
  socket.send(JSON.stringify({type:'result',connection_id:frame.connection_id,client_id:frame.client_id,result:{request_id:frame.request.request_id,device_id:device.id,outcome:'REJECTED',code:'TAG_NAME_EXISTS',data:{}}}));return false;
 },{capabilities,permissions:scopes.slice(1)});
 try{
  const args={...mutation(device.id),name:'Duplicate'};
  const first=await h.call(grant.access_token,'create_tag',args);
  const retry=await h.call(grant.access_token,'create_tag',args);
  expect(first.data.outcome).toBe('REJECTED');expect(first.data.code).toBe('TAG_NAME_EXISTS');
  expect(retry.data.outcome).toBe('REJECTED');expect(retry.data.code).toBe('TAG_NAME_EXISTS');expect(count).toBe(1);
 }finally{ws.close();}
});

test('tag and category name schemas reject invisible format and control characters',()=>{
 const schema=TOOL_MAP.get('create_tag').schema;
 for(const name of ['Invisible\u200e','Misleading\u202e','Control\u0001']) expect(schema.safeParse({...mutation(randomUUID()),name}).success).toBe(false);
 expect(schema.safeParse({...mutation(randomUUID()),name:'Jazz 🎵'}).success).toBe(true);
});
