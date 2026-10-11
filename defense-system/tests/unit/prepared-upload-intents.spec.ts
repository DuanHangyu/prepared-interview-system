// @vitest-environment node
// Synthetic bytes + IndexedDB substitute, NOT cloud or whole-interview acceptance.
import {IDBFactory} from 'fake-indexeddb';
import {beforeEach,afterEach,it,expect} from 'vitest';
import {PreparedUploadJournal} from '@/prepared/uploadJournal';
import {makeUploadIntent,verifyBundleSummary} from '@/prepared/uploadBundle';
const owner={origin:'http://127.0.0.1:13015',studentId:12,deploymentId:'prepared-local'};
const answer={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID(),captureClientId:crypto.randomUUID(),ordinal:1};
let factory:IDBFactory,journal:PreparedUploadJournal;
const hex=(bytes:ArrayBuffer)=>Array.from(new Uint8Array(bytes),b=>b.toString(16).padStart(2,'0')).join('');
beforeEach(()=>{factory=new IDBFactory();journal=new PreparedUploadJournal(owner,8,factory);});
afterEach(()=>journal.close());
async function intent(indices=[0,1]){
  const segment={id:'00000000-0000-4000-8000-000000000003',ordinal:1,mimeType:'audio/webm'};
  const chunks=await Promise.all(indices.map(async index=>{const blob=new Blob([`original-${index}`]);return {index,blob,byteSize:blob.size,sha256:hex(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()))};}));
  return makeUploadIntent(owner.studentId,answer,segment,chunks);
}
it('tracks authorized answers and fixes the exact original batch before any network request, preserving it on reopen',async()=>{
  await journal.track(answer);const first=await intent();expect(await journal.reserve(first)).toEqual(first);
  journal.close();journal=new PreparedUploadJournal(owner,8,factory);
  expect(await journal.answers(answer.attemptId)).toEqual([answer]);expect(await journal.intents(answer)).toEqual([first]);
  expect(await journal.reserve(first)).toEqual(first);expect('delete' in journal).toBe(false);
});
it('atomically refuses regrouping and same-index different contents without leaving a replacement batch',async()=>{
  await journal.track(answer);const original=await intent();await journal.reserve(original);
  await expect(journal.reserve(await intent([1,2]))).rejects.toMatchObject({code:'CONFLICT'});
  expect(await journal.intents(answer)).toEqual([original]);
  expect(await journal.reserve(await intent([2]))).toMatchObject({command:{chunks:[{index:2}]}});
});
it('rejects untracked or changed original answer/client and isolates every owner namespace',async()=>{
  const original=await intent();await expect(journal.reserve(original)).rejects.toMatchObject({code:'MISSING_ORIGINAL'});
  await journal.track(answer);await journal.reserve(original);
  await expect(journal.track({...answer,captureClientId:crypto.randomUUID()})).rejects.toMatchObject({code:'CONFLICT'});
  await expect(journal.track({...answer,answerId:crypto.randomUUID()})).rejects.toMatchObject({code:'CONFLICT'});
  for(const identity of [{...owner,studentId:13},{...owner,deploymentId:'prepared-other'},{...owner,origin:'https://school.example'}]){
    const other=new PreparedUploadJournal(identity,8,factory);expect(await other.answers(answer.attemptId)).toEqual([]);other.close();
  }
  journal.close();await expect(journal.answers(answer.attemptId)).rejects.toMatchObject({code:'CLOSED'});
});
it('uses lexical canonical JSON and validates the exact entire receipt, never one-piece or media-SAVED claims',async()=>{
  const original=await intent();const command=original.command;
  const json=JSON.stringify({byteSize:command.byteSize,captureClientId:answer.captureClientId,chunks:command.chunks.map(c=>({byteSize:c.byteSize,index:c.index,sha256:c.sha256})),mimeType:'audio/webm',segmentId:command.segmentId,segmentOrdinal:1,sha256:command.sha256});
  const textHash=async(text:string)=>hex(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text)));
  expect(original.commandSha256).toBe(await textHash(json));
  expect(original.bundleId).toBe(await textHash(`prepared-answer-bundle-v1\n12\n8\n${answer.attemptId}\n${answer.answerId}\n${json}`));
  const summary={bundleId:original.bundleId,answerId:answer.answerId,commandSha256:original.commandSha256,byteSize:command.byteSize,sha256:command.sha256,confirmed:true,
    receipts:command.chunks.map(c=>({...c,answerId:answer.answerId,segmentId:command.segmentId,confirmed:true,confirmedAt:'2026-10-05T00:00:00Z'}))};
  expect(verifyBundleSummary(original,summary).confirmed).toBe(true);
  for(const bad of [{...summary,answerId:crypto.randomUUID()},{...summary,receipts:summary.receipts.slice(1)},{...summary,confirmed:false},{...summary,byteSize:1},
    {...summary,receipts:[summary.receipts[0],{...summary.receipts[1],confirmedAt:'2026-10-05T00:00:01Z'}]}])expect(()=>verifyBundleSummary(original,bad)).toThrow();
});
it('does not append upload intents to a damaged tracked-answer witness',async()=>{
  await journal.track(answer);const db=await new Promise<IDBDatabase>(resolve=>{const r=factory.open('prepared-interview-upload-v1',1);r.onsuccess=()=>resolve(r.result);});
  await new Promise<void>(resolve=>{const tx=db.transaction('answers','readwrite'),s=tx.objectStore('answers'),r=s.getAll();r.onsuccess=()=>{const row=r.result[0];row.sha256='a'.repeat(64);s.put(row);};tx.oncomplete=()=>resolve();});db.close();
  await expect(journal.reserve(await intent())).rejects.toMatchObject({code:'INTEGRITY'});
  expect(await journal.intents(answer)).toEqual([]);
});
