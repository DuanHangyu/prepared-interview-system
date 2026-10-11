/** Metadata declaration only. Real bytes/media/cloud receipts must be verified separately. */
export type AnswerEndChunk={index:number;byteSize:number;sha256:string};
export type AnswerEndSegment={id:string;ordinal:number;mimeType:string;startedAt:number;endedAt:number;state:'SEALED'|'INTERRUPTED';interruption:string|null;chunkCount:number;byteSize:number;chunks:AnswerEndChunk[]};
export type AnswerEndManifest={schemaVersion:1;segments:AnswerEndSegment[]};
export type AnswerEndCommand={idempotencyKey:string;captureClientId:string;manifest:AnswerEndManifest};
export class AnswerEndManifestError extends Error {
  constructor(public readonly code:'INVALID'|'MISSING_TERMINAL'='INVALID'){super('录音结束清单无效，请保留原内容');this.name='AnswerEndManifestError';}
}
const MIMES=new Set(['audio/webm;codecs=opus','audio/webm','audio/ogg;codecs=opus','audio/ogg','audio/mp4']);
const FAULTS=new Set(['PAGE_REFRESH','DEVICE_ENDED','CAPTURE_ERROR','LOCAL_WRITE_FAILED','SESSION_CHANGED']);
const uuid=(value:unknown)=>typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
const integer=(value:unknown,min:number,max:number):value is number=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=min&&value<=max;
const requireValue=(valid:boolean):void=>{if(!valid)throw new AnswerEndManifestError();};
/** Lexical object-key ordering matches PreparedJobInput.json. Copy only contract fields, never blobs or URLs. */
export function encodeAnswerEndManifest(input:unknown):string {
  const value=input as AnswerEndManifest;
  requireValue(Boolean(value)&&value.schemaVersion===1&&Array.isArray(value.segments)&&value.segments.length>0&&value.segments.length<=1000);
  const ids=new Set<string>();let bytes=0,chunks=0,priorEnd=-1;
  const segments=value.segments.map((s,index)=>{
    requireValue(Boolean(s)&&uuid(s.id)&&!ids.has(s.id)&&s.ordinal===index+1);ids.add(s.id);
    requireValue(MIMES.has(s.mimeType)&&integer(s.startedAt,0,8640000000000000)&&integer(s.endedAt,0,8640000000000000)&&s.endedAt>=s.startedAt&&s.startedAt>=priorEnd);priorEnd=s.endedAt;
    requireValue(Array.isArray(s.chunks)&&integer(s.chunkCount,0,10000)&&s.chunkCount===s.chunks.length&&integer(s.byteSize,0,67108864));
    let segmentBytes=0;
    const items=s.chunks.map((c,i)=>{
      requireValue(Boolean(c)&&c.index===i&&integer(c.byteSize,1,2097152)&&typeof c.sha256==='string'&&/^[a-f0-9]{64}$/.test(c.sha256)&&++chunks<=10000);segmentBytes+=c.byteSize;
      return {byteSize:c.byteSize,index:c.index,sha256:c.sha256};
    });
    requireValue(segmentBytes===s.byteSize&&(bytes+=segmentBytes)<=67108864);
    if((s.state as string)==='OPEN')throw new AnswerEndManifestError('MISSING_TERMINAL');
    requireValue(s.state==='SEALED'?s.interruption===null&&s.chunkCount>0:s.state==='INTERRUPTED'&&typeof s.interruption==='string'&&FAULTS.has(s.interruption));
    return {byteSize:s.byteSize,chunkCount:s.chunkCount,chunks:items,endedAt:s.endedAt,id:s.id,interruption:s.interruption,mimeType:s.mimeType,ordinal:s.ordinal,startedAt:s.startedAt,state:s.state};
  });
  const json=JSON.stringify({schemaVersion:1,segments});requireValue(new TextEncoder().encode(json).byteLength<=2097152);return json;
}
/** Original identifiers are mandatory; damaged commands never silently get a new id. */
export function copyAnswerEndCommand(input:unknown):AnswerEndCommand {
  const value=input as AnswerEndCommand;requireValue(Boolean(value)&&uuid(value.idempotencyKey)&&uuid(value.captureClientId));
  const idempotencyKey=value.idempotencyKey,captureClientId=value.captureClientId;
  return {idempotencyKey,captureClientId,manifest:JSON.parse(encodeAnswerEndManifest(value.manifest)) as AnswerEndManifest};
}
