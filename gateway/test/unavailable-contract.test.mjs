import {describe,test,expect} from 'vitest';
import {randomUUID} from 'node:crypto';
import {TOOL_MAP} from '../src/tools.ts';

const mutation=()=>({device_id:randomUUID(),request_id:randomUUID(),issued_at_ms:Date.now()});
const item={stream_id:4,service:'YouTube',url:'https://www.youtube.com/watch?v=synthetic'};
const revision='a'.repeat(64);

describe('Unavailable playlist optional AI capability contract',()=>{
 test('the new operations use existing precise grants, never a global admin scope',()=>{
  expect(TOOL_MAP.get('mark_unavailable').scopes({})).toEqual(['playlist.write']);
  expect(TOOL_MAP.get('mark_available').scopes({})).toEqual(['playlist.write']);
  expect(TOOL_MAP.get('clear_unavailable').scopes({})).toEqual(['playlist.write']);
  expect(TOOL_MAP.get('remove_all_unavailable').scopes({})).toEqual(['library.delete']);
  expect(TOOL_MAP.get('remove_library_items').scopes({})).toEqual(['library.delete']);
 });
 test('marking is bounded, exact and device-addressed',()=>{
  for(const name of ['mark_unavailable','mark_available']){
   const schema=TOOL_MAP.get(name).schema;
   expect(schema.safeParse({...mutation(),items:[item]}).success).toBe(true);
   expect(schema.safeParse({...mutation(),items:[]}).success).toBe(false);
   expect(schema.safeParse({...mutation(),items:Array(501).fill(item)}).success).toBe(false);
   expect(schema.safeParse({...mutation(),items:[{...item,stream_id:0}]}).success).toBe(false);
   expect(schema.safeParse({...mutation(),items:[item],playlist_id:100}).success).toBe(false);
   expect(schema.safeParse({...mutation(),items:[item],device_id:undefined}).success).toBe(false);
  }
 });
 test('whole-list operations contain no AI-supplied stream IDs or alternate playlist targets',()=>{
  for(const name of ['clear_unavailable','remove_all_unavailable']){
   const schema=TOOL_MAP.get(name).schema;
   expect(schema.safeParse(mutation()).success).toBe(name === 'clear_unavailable');
   expect(schema.safeParse({...mutation(),expected_revision:null}).success).toBe(false);
   expect(schema.safeParse({...mutation(),expected_revision:revision}).success).toBe(true);
   expect(schema.safeParse({...mutation(),expected_revision:'stale'}).success).toBe(false);
   expect(schema.safeParse({...mutation(),playlist_id:88}).success).toBe(false);
   expect(schema.safeParse({...mutation(),items:[item]}).success).toBe(false);
   expect(schema.safeParse({device_id:randomUUID()}).success).toBe(false);
  }
 });
});
