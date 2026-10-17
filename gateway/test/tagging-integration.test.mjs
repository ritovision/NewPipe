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
