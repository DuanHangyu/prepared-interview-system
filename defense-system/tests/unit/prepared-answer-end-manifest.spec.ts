// @vitest-environment node
import {describe,expect,it} from 'vitest';
import {copyAnswerEndCommand,encodeAnswerEndManifest} from '@/prepared/answerEndManifest';
const segment={id:'00000000-0000-4000-8000-000000000003',ordinal:1,mimeType:'audio/webm',startedAt:1000,endedAt:2000,state:'SEALED',interruption:null,chunkCount:1,byteSize:3,chunks:[{index:0,byteSize:3,sha256:'a'.repeat(64)}]};
const valid=()=>({schemaVersion:1,segments:[structuredClone(segment)]});
const ids={idempotencyKey:'00000000-0000-4000-8000-000000000004',captureClientId:'00000000-0000-4000-8000-000000000005'};
describe('canonical end declaration mirrors backend metadata contract; not a byte or media proof',()=>{
  it('uses the backend lexical JSON field ordering and strips all non-contract fields',()=>{
    const json=encodeAnswerEndManifest({...valid(),serverSaved:true,segments:[{...segment,url:'https://private.invalid/secret',blob:'secret',chunks:[{...segment.chunks[0],blob:'secret'}]}]});
    expect(json).toBe('{"schemaVersion":1,"segments":[{"byteSize":3,"chunkCount":1,"chunks":[{"byteSize":3,"index":0,"sha256":"'+ 'a'.repeat(64)+'"}],"endedAt":2000,"id":"00000000-0000-4000-8000-000000000003","interruption":null,"mimeType":"audio/webm","ordinal":1,"startedAt":1000,"state":"SEALED"}]}');
    const command=copyAnswerEndCommand({...ids,manifest:valid(),serverSaved:true});expect(Object.keys(command).sort()).toEqual(['captureClientId','idempotencyKey','manifest']);
  });
  it('makes independent snapshots and preserves interrupted empty recording evidence, without treating it as SAVED',()=>{
    const manifest=valid();const command=copyAnswerEndCommand({...ids,manifest});manifest.segments[0].endedAt=3000;expect(command.manifest.segments[0].endedAt).toBe(2000);
    const fault={...segment,state:'INTERRUPTED',interruption:'CAPTURE_ERROR',chunkCount:0,byteSize:0,chunks:[]};expect(JSON.parse(encodeAnswerEndManifest({schemaVersion:1,segments:[fault]})).segments[0]).toMatchObject(fault);
  });
  it('rejects null, wrong version, empty or excessive segment collections and incomplete OPEN capture',()=>{
    for(const wrong of [null,{},[],{...valid(),schemaVersion:2},{schemaVersion:1,segments:[]},{schemaVersion:1,segments:Array(1001).fill(segment)}])expect(()=>encodeAnswerEndManifest(wrong)).toThrow();
    expect(()=>encodeAnswerEndManifest({schemaVersion:1,segments:[{...segment,state:'OPEN'}]})).toThrowError(expect.objectContaining({code:'MISSING_TERMINAL'}));
  });
  it('rejects duplicate ids, non-contiguous ordinals and overlapping timestamps',()=>{
    for(const second of [{...segment,ordinal:2},{...segment,id:crypto.randomUUID(),ordinal:3},{...segment,id:crypto.randomUUID(),ordinal:2,startedAt:1500,endedAt:3000}])expect(()=>encodeAnswerEndManifest({schemaVersion:1,segments:[segment,second]})).toThrow();
  });
  it('permits non-overlapping distinct interrupted containers and preserves gaps as evidence',()=>{
    const fault={...segment,state:'INTERRUPTED',interruption:'DEVICE_ENDED'};const next={...segment,id:crypto.randomUUID(),ordinal:2,mimeType:'audio/mp4',startedAt:3000,endedAt:5000};
    expect(JSON.parse(encodeAnswerEndManifest({schemaVersion:1,segments:[fault,next]})).segments.map((s:any)=>s.mimeType)).toEqual(['audio/webm','audio/mp4']);
  });
  it('rejects illegal scalar metadata, false complete empty recordings and invalid faults without leaking input',()=>{
    for(const update of [{id:'not-an-id'},{mimeType:'video/webm'},{startedAt:null},{startedAt:-1},{endedAt:999},{endedAt:NaN},{endedAt:8640000000000001},{state:'SAVED'},{interruption:'DEVICE_ENDED'},{state:'INTERRUPTED',interruption:null},{state:'INTERRUPTED',interruption:'SECRET_PAYLOAD'},{chunkCount:0,byteSize:0,chunks:[]},{chunkCount:2},{byteSize:4},{byteSize:-1}])expect(()=>encodeAnswerEndManifest({schemaVersion:1,segments:[{...segment,...update}]})).toThrowError('录音结束清单无效，请保留原内容');
  });
  it('rejects missing chunks, wrong chunk order, size and digest and disallows fake URL chunks',()=>{
    for(const chunk of [null,{...segment.chunks[0],index:1},{...segment.chunks[0],byteSize:0},{...segment.chunks[0],byteSize:2097153},{...segment.chunks[0],sha256:'A'.repeat(64)},{url:'https://private.invalid/secret'}])expect(()=>encodeAnswerEndManifest({schemaVersion:1,segments:[{...segment,chunks:[chunk]}]})).toThrow();
    expect(()=>encodeAnswerEndManifest({schemaVersion:1,segments:[{...segment,chunks:null}]})).toThrow();
  });
  it('enforces aggregate bytes and chunks across recorder boundaries, not only per segment',()=>{
    const chunks=Array.from({length:32},(_,index)=>({index,byteSize:2097152,sha256:'a'.repeat(64)}));
    const big={...segment,chunkCount:32,byteSize:67108864,chunks};const next={...segment,id:crypto.randomUUID(),ordinal:2,startedAt:2000,endedAt:3000};
    expect(()=>encodeAnswerEndManifest({schemaVersion:1,segments:[big,next]})).toThrow();
    const tiny=Array.from({length:6000},(_,index)=>({index,byteSize:1,sha256:'a'.repeat(64)}));const many={...segment,chunkCount:6000,byteSize:6000,chunks:tiny};
    expect(()=>encodeAnswerEndManifest({schemaVersion:1,segments:[many,{...many,id:next.id,ordinal:2,startedAt:2000,endedAt:3000}]})).toThrow();
  });
  it('validates both original command ids and never generates replacements for damaged stored commands',()=>{
    for(const command of [null,{}, {...ids,idempotencyKey:'bad',manifest:valid()},{...ids,captureClientId:'bad',manifest:valid()},{...ids,manifest:null}])expect(()=>copyAnswerEndCommand(command)).toThrow();
  });
});
