import type {AnswerScope,Chunk} from './localAudioJournal';
export type UploadOwner={origin:string;studentId:number;deploymentId:string};
export type UploadAnswer=AnswerScope & {captureClientId:string;ordinal:number};
export type BundleCommand={captureClientId:string;segmentId:string;segmentOrdinal:number;mimeType:string;chunks:Array<{index:number;byteSize:number;sha256:string}>;byteSize:number;sha256:string};
export type UploadIntent={answer:UploadAnswer;bundleId:string;commandSha256:string;command:BundleCommand};
export type BundleReceipt={answerId:string;segmentId:string;index:number;byteSize:number;sha256:string;confirmed:true;confirmedAt:string};
export type BundleSummary={bundleId:string;answerId:string;commandSha256:string;byteSize:number;sha256:string;confirmed:boolean;receipts:BundleReceipt[]};
type Code='INVALID'|'INTEGRITY'|'CONFLICT'|'MISSING_ORIGINAL'|'UNAVAILABLE'|'WRITE_FAILED'|'CLOSED';
export class UploadError extends Error {constructor(public readonly code:Code){super('原录音上传信息未确认，请保留本机内容，不要重新回答');this.name='UploadError';}}
export const uploadCheck=(value:unknown,code:Code='INVALID'):void=>{if(!value)throw new UploadError(code);};
const uuid=(v:unknown)=>uploadCheck(typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v));
export const uploadHash=async(value:Blob|string)=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',typeof value==='string'?new TextEncoder().encode(value):await value.arrayBuffer())),b=>b.toString(16).padStart(2,'0')).join('');
export function copyUploadAnswer(value:UploadAnswer):UploadAnswer {
  uploadCheck(!!value);uploadCheck(Number.isSafeInteger(value.assessmentId)&&value.assessmentId>0&&value.assessmentId<=2147483647);
  uuid(value.attemptId);uuid(value.answerId);uuid(value.captureClientId);uploadCheck(Number.isSafeInteger(value.ordinal)&&value.ordinal>=1&&value.ordinal<=50);
  return {assessmentId:value.assessmentId,attemptId:value.attemptId,answerId:value.answerId,captureClientId:value.captureClientId,ordinal:value.ordinal};
}
/** Matches backend lexical JSON, not insertion-order serialization of arbitrary caller objects. */
export function copyBundleCommand(v:BundleCommand):BundleCommand {
  uploadCheck(!!v);uuid(v.captureClientId);uuid(v.segmentId);uploadCheck(Number.isSafeInteger(v.segmentOrdinal)&&v.segmentOrdinal>=1&&v.segmentOrdinal<=1000);
  uploadCheck(['audio/webm;codecs=opus','audio/webm','audio/ogg;codecs=opus','audio/ogg','audio/mp4'].includes(v.mimeType));
  uploadCheck(Array.isArray(v.chunks)&&v.chunks.length>=1&&v.chunks.length<=32);let prior=-1,bytes=0;
  const chunks=v.chunks.map(c=>{uploadCheck(!!c&&Number.isSafeInteger(c.index)&&c.index>prior&&c.index<=9999);prior=c.index;
    uploadCheck(Number.isSafeInteger(c.byteSize)&&c.byteSize>=1&&c.byteSize<=2097152&&/^[a-f0-9]{64}$/.test(c.sha256));bytes+=c.byteSize;
    return {byteSize:c.byteSize,index:c.index,sha256:c.sha256};});
  uploadCheck(bytes<=8388608&&bytes===v.byteSize&&/^[a-f0-9]{64}$/.test(v.sha256));
  return {byteSize:v.byteSize,captureClientId:v.captureClientId,chunks,mimeType:v.mimeType,segmentId:v.segmentId,segmentOrdinal:v.segmentOrdinal,sha256:v.sha256};
}
export async function fixedUploadIntent(studentId:number,answer:UploadAnswer,command:BundleCommand):Promise<UploadIntent>{
  uploadCheck(Number.isSafeInteger(studentId)&&studentId>0&&studentId<=2147483647);
  const a=copyUploadAnswer(answer),c=copyBundleCommand(command);uploadCheck(a.captureClientId===c.captureClientId,'CONFLICT');
  const json=JSON.stringify(c),commandSha256=await uploadHash(json),bundleId=await uploadHash(`prepared-answer-bundle-v1\n${studentId}\n${a.assessmentId}\n${a.attemptId}\n${a.answerId}\n${json}`);
  return {answer:a,bundleId,commandSha256,command:c};
}
export async function makeUploadIntent(studentId:number,answer:UploadAnswer,segment:{id:string;ordinal:number;mimeType:string},chunks:Chunk[]):Promise<UploadIntent>{
  // Snapshot before async byte inspection; bounded original chunks remain in the unchanged audio database.
  const a=copyUploadAnswer(answer),s={...segment},fixed=chunks.map(c=>({...c}));uploadCheck(fixed.length>0&&fixed.length<=32);
  uploadCheck(fixed.reduce((n,c)=>n+c.byteSize,0)<=8388608);
  for(const c of fixed)uploadCheck(c.blob instanceof Blob&&c.blob.size===c.byteSize&&await uploadHash(c.blob)===c.sha256,'INTEGRITY');
  const blob=new Blob(fixed.map(c=>c.blob));
  return fixedUploadIntent(studentId,a,{captureClientId:a.captureClientId,segmentId:s.id,segmentOrdinal:s.ordinal,mimeType:s.mimeType,
    chunks:fixed.map(({index,byteSize,sha256})=>({index,byteSize,sha256})),byteSize:blob.size,sha256:await uploadHash(blob)});
}
export function verifyBundleSummary(i:UploadIntent,value:unknown):BundleSummary {
  const r=value as BundleSummary,c=i.command;uploadCheck(!!r&&r.bundleId===i.bundleId&&r.answerId===i.answer.answerId&&r.commandSha256===i.commandSha256&&r.byteSize===c.byteSize&&r.sha256===c.sha256&&typeof r.confirmed==='boolean'&&Array.isArray(r.receipts),'INTEGRITY');
  uploadCheck(r.confirmed?r.receipts.length===c.chunks.length:r.receipts.length===0,'INTEGRITY');let timestamp:string|undefined;
  const receipts=r.receipts.map((v,index)=>{const original=c.chunks[index];
    uploadCheck(!!v&&v.answerId===i.answer.answerId&&v.segmentId===c.segmentId&&v.index===original.index&&v.byteSize===original.byteSize&&v.sha256===original.sha256&&v.confirmed===true&&typeof v.confirmedAt==='string'&&/^\d{4}-\d{2}-\d{2}T/.test(v.confirmedAt)&&Number.isFinite(Date.parse(v.confirmedAt)),'INTEGRITY');
    timestamp??=v.confirmedAt;uploadCheck(timestamp===v.confirmedAt,'INTEGRITY');
    return {answerId:v.answerId,segmentId:v.segmentId,index:v.index,byteSize:v.byteSize,sha256:v.sha256,confirmed:true as const,confirmedAt:v.confirmedAt};});
  return {bundleId:r.bundleId,answerId:r.answerId,commandSha256:r.commandSha256,byteSize:r.byteSize,sha256:r.sha256,confirmed:r.confirmed,receipts};
}
