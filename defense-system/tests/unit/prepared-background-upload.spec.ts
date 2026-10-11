// @vitest-environment node
// Controlled HTTP receipts only; never a real OSS or whole-media success claim.
import {IDBFactory} from 'fake-indexeddb';
import {afterEach,beforeEach,it,expect,vi} from 'vitest';
import {PreparedUploadJournal} from '@/prepared/uploadJournal';
import {PreparedAudioJournal} from '@/prepared/localAudioJournal';
import {PreparedBackgroundUpload} from '@/prepared/backgroundUpload';
import {UploadTransportError} from '@/api/preparedUpload';
import {uploadHash} from '@/prepared/uploadBundle';
import {encodeAnswerEndManifest} from '@/prepared/answerEndManifest';
const owner={origin:'http://127.0.0.1:13015',studentId:12,deploymentId:'prepared-local'};
let answer:any,segment:any,audio:PreparedAudioJournal,journal:PreparedUploadJournal,sender:PreparedBackgroundUpload,authority:any,transport:any;
const lock=async(_key:string,_signal:AbortSignal,body:()=>Promise<any>)=>body();
function reply(i:any,confirmed:boolean){return {bundleId:i.bundleId,answerId:i.answer.answerId,commandSha256:i.commandSha256,byteSize:i.command.byteSize,sha256:i.command.sha256,confirmed,
  receipts:confirmed?i.command.chunks.map((c:any)=>({...c,answerId:i.answer.answerId,segmentId:i.command.segmentId,confirmed:true,confirmedAt:'2026-10-05T00:00:00Z'})):[]};}
beforeEach(async()=>{
  const factory=new IDBFactory();audio=new PreparedAudioJournal(owner,factory);journal=new PreparedUploadJournal(owner,8,factory);
  answer={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID(),captureClientId:crypto.randomUUID(),ordinal:1};
  segment={id:crypto.randomUUID(),ordinal:1,mimeType:'audio/webm',startedAt:1000};
  await journal.track(answer);await audio.begin(answer,segment);
  authority=vi.fn(async()=>({...owner,workflowEnabled:true}));
  transport={prepare:vi.fn(async(i:any)=>reply(i,false)),status:vi.fn(async(i:any)=>reply(i,false)),upload:vi.fn(async(i:any)=>reply(i,true))};
  transport.window=vi.fn(async(a:any)=>windowReply(a,'APPROVED'));
  sender=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:answer.attemptId,audio,journal,authority,transport,withLock:lock,online:()=>true,random:()=>0});
});
afterEach(()=>{sender.close();audio.close();journal.close();});
async function bytes(count=3,sealed=true){for(let index=0;index<count;index++)await audio.append(answer,segment.id,index,new Blob([`original-${index}`],{type:segment.mimeType}));if(sealed)await audio.seal(answer,segment.id,count,2000);}
async function windowReply(a:any,phase='APPROVED'){
  const end=await audio.recoverAnswerEnd(a);return {studentId:owner.studentId,assessmentId:a.assessmentId,attemptId:a.attemptId,answerId:a.answerId,paperVersionId:'12345678-1234-1234-1234-123456789abc',
    manifestSha256:await uploadHash(encodeAnswerEndManifest(end.manifest)),answerDeadline:'2026-10-03T00:02:00Z',answeringEndedAt:'2026-10-03T00:01:00Z',automaticDeadline:'2026-10-04T00:01:00Z',serverNow:'2026-10-04T01:00:00Z',
    phase,mayUploadOriginal:phase==='APPROVED',approvalRevision:phase==='AWAITING_APPROVAL'?0:1,approvedAt:phase==='AWAITING_APPROVAL'?null:'2026-10-04T00:02:00Z',uploadDeadline:phase==='AWAITING_APPROVAL'?'2026-10-04T00:01:00Z':'2026-10-04T02:00:00Z'};
}
it('waits without sending expired bytes then resumes the identical original intent after fresh approval',async()=>{
  await bytes();const end=await audio.prepareAnswerEnd(answer,{captureClientId:answer.captureClientId,idempotencyKey:crypto.randomUUID()});
  transport.window.mockImplementation((a:any)=>windowReply(a,'AWAITING_APPROVAL'));
  const waiting=await sender.run();expect(waiting.phase).toBe('WAITING_APPROVAL');expect(transport.prepare).not.toHaveBeenCalled();expect(transport.upload).not.toHaveBeenCalled();
  const original=(await journal.intents(answer))[0];expect(waiting.windows?.[0]).toMatchObject({answerId:answer.answerId,phase:'AWAITING_APPROVAL',ordinal:1});
  transport.window.mockImplementation((a:any)=>windowReply(a,'APPROVED'));transport.status.mockRejectedValueOnce(new UploadTransportError(404));
  const resumed=await sender.run();expect(resumed.phase).toBe('BYTES_CONFIRMED');expect(resumed.windows?.[0]).toMatchObject({phase:'APPROVED',uploadDeadline:'2026-10-04T02:00:00Z'});
  expect(transport.prepare).toHaveBeenCalledWith(original,expect.any(AbortSignal));expect(transport.upload.mock.calls[0][0]).toEqual(original);expect(await journal.intents(answer)).toEqual([original]);expect(await audio.recoverAnswerEnd(answer)).toEqual(end);
});
it('expiry between query and actual upload is recoverable but content conflicts stay stopped',async()=>{
  await bytes();await audio.prepareAnswerEnd(answer,{captureClientId:answer.captureClientId,idempotencyKey:crypto.randomUUID()});
  transport.upload.mockRejectedValueOnce(new UploadTransportError(409,0,'UPLOAD_WINDOW_EXPIRED'));
  expect((await sender.run()).phase).toBe('WAITING_APPROVAL');const original=(await journal.intents(answer))[0];expect((await sender.run()).phase).toBe('BYTES_CONFIRMED');expect(transport.upload.mock.calls[1][0]).toEqual(original);
  sender.close();sender=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:answer.attemptId,audio,journal,authority,transport,withLock:lock,online:()=>true});
  transport.upload.mockRejectedValueOnce(new UploadTransportError(409));expect((await sender.run()).phase).toBe('NEEDS_REVIEW');const count=transport.upload.mock.calls.length;
  expect((await sender.run()).phase).toBe('NEEDS_REVIEW');expect(transport.upload).toHaveBeenCalledTimes(count);expect((await audio.list(answer))[0].chunkCount).toBe(3);
});
it('an expired or damaged answer does not starve another original answer with its own approval',async()=>{
  await bytes();await audio.prepareAnswerEnd(answer,{captureClientId:answer.captureClientId,idempotencyKey:crypto.randomUUID()});
  const next={...answer,answerId:crypto.randomUUID(),ordinal:2},id=crypto.randomUUID();await journal.track(next);await audio.begin(next,{id,ordinal:1,mimeType:'audio/webm',startedAt:1000});await audio.append(next,id,0,new Blob(['next original']));await audio.seal(next,id,1,2000);await audio.prepareAnswerEnd(next,{captureClientId:next.captureClientId,idempotencyKey:crypto.randomUUID()});
  transport.window.mockImplementation((a:any)=>windowReply(a,a.answerId===answer.answerId?'AWAITING_APPROVAL':'APPROVED'));
  expect((await sender.run()).phase).toBe('WAITING_APPROVAL');expect((await sender.run()).phase).toBe('BYTES_CONFIRMED');expect(transport.upload.mock.calls[0][0].answer).toEqual(next);
});
it('discards mismatched window or late approval after authority changes and preserves original bytes',async()=>{
  await bytes();await audio.prepareAnswerEnd(answer,{captureClientId:answer.captureClientId,idempotencyKey:crypto.randomUUID()});
  transport.window.mockImplementation(async(a:any)=>({...await windowReply(a),manifestSha256:'b'.repeat(64)}));expect((await sender.run()).phase).toBe('NEEDS_REVIEW');expect(transport.upload).not.toHaveBeenCalled();
  sender.close();sender=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:answer.attemptId,audio,journal,authority,transport,withLock:lock,online:()=>true});
  transport.window.mockImplementation(async(a:any)=>{const r=await windowReply(a);authority.mockResolvedValue({...owner,studentId:13,workflowEnabled:true});return r;});expect((await sender.run()).phase).toBe('IDENTITY_REQUIRED');expect(transport.upload).not.toHaveBeenCalled();expect((await audio.list(answer))[0].chunkCount).toBe(3);
});
it('uploads a sealed tail without blocking local recording or end-state, checking original bytes and durable intent first',async()=>{
  await bytes();transport.prepare.mockImplementation(async(i:any)=>{expect(await journal.intents(answer)).toEqual([i]);return reply(i,false);});
  transport.upload.mockImplementation(async(i:any,blob:Blob)=>{expect(await blob.text()).toBe('original-0original-1original-2');return reply(i,true);});
  expect((await sender.run()).phase).toBe('BYTES_CONFIRMED');expect(transport.upload).toHaveBeenCalledTimes(1);
  expect((await audio.list(answer))[0]).toMatchObject({state:'SEALED',chunkCount:3});
});
it('retains original grouping after ambiguous reply and refresh, queries first, and skips re-upload only on exact server receipt',async()=>{
  await bytes();transport.upload.mockRejectedValueOnce(new UploadTransportError(0));
  expect((await sender.run()).phase).toBe('WAITING');const first=(await journal.intents(answer))[0];
  sender.close();transport.status.mockImplementation(async(i:any)=>reply(i,true));
  sender=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:answer.attemptId,audio,journal,authority,transport,withLock:lock,online:()=>true});
  expect((await sender.run()).phase).toBe('BYTES_CONFIRMED');expect(transport.status).toHaveBeenCalledWith(first,expect.any(AbortSignal));
  expect(transport.upload).toHaveBeenCalledTimes(1);expect(await journal.intents(answer)).toEqual([first]);
});
it('batches committed OPEN prefixes, leaves short OPEN tails local and processes previously authorized answers after moving next',async()=>{
  await bytes(3,false);expect((await sender.run()).phase).toBe('LOCAL_ONLY');expect(transport.prepare).not.toHaveBeenCalled();
  for(let index=3;index<15;index++)await audio.append(answer,segment.id,index,new Blob([`original-${index}`],{type:segment.mimeType}));
  const next={...answer,answerId:crypto.randomUUID(),ordinal:2};await journal.track(next);
  expect((await sender.run()).phase).toBe('BYTES_CONFIRMED');expect(transport.upload.mock.calls[0][0].answer.answerId).toBe(answer.answerId);
  expect((await audio.list(answer))[0].state).toBe('OPEN');
});
it('does not send while offline/closed, never accepts late identity responses or invalid partial receipts',async()=>{
  await bytes();authority.mockResolvedValueOnce({...owner,workflowEnabled:false});expect((await sender.run()).phase).toBe('CLOSED');expect(transport.prepare).not.toHaveBeenCalled();
  authority.mockResolvedValueOnce({...owner,studentId:13,workflowEnabled:true});expect((await sender.run()).phase).toBe('IDENTITY_REQUIRED');expect(transport.prepare).not.toHaveBeenCalled();
  sender.close();sender=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:answer.attemptId,audio,journal,authority,transport,withLock:lock,online:()=>false});
  expect((await sender.run()).phase).toBe('OFFLINE');expect(transport.upload).not.toHaveBeenCalled();
});
it('honors retry hints/backoff, permits one in-flight group, and keeps collecting while an upload stalls',async()=>{
  await bytes(15,false);let done!:(v:any)=>void;transport.upload.mockImplementation((i:any)=>new Promise(resolve=>{done=()=>resolve(reply(i,true));}));
  const work=sender.run();await vi.waitFor(()=>expect(transport.upload).toHaveBeenCalledTimes(1));
  await audio.append(answer,segment.id,15,new Blob(['still recording'],{type:segment.mimeType}));expect((await sender.run()).phase).toBe('SENDING');
  done(null);expect((await work).phase).toBe('BYTES_CONFIRMED');expect((await audio.list(answer))[0].chunkCount).toBe(16);
  transport.prepare.mockRejectedValueOnce(new UploadTransportError(503,20));await audio.seal(answer,segment.id,16,2000);
  expect((await sender.run()).retryAt! - Date.now()).toBeGreaterThanOrEqual(19000);const calls=transport.prepare.mock.calls.length;
  expect((await sender.run()).phase).toBe('WAITING');expect(transport.prepare.mock.calls.length).toBe(calls);
});
it('rejects false whole-batch receipts without deleting any original audio',async()=>{
  await bytes();transport.upload.mockImplementation(async(i:any)=>({...reply(i,true),receipts:[]}));
  expect((await sender.run()).phase).toBe('NEEDS_REVIEW');expect((await audio.list(answer))[0].byteSize).toBeGreaterThan(0);
});
it('recovers a persisted but never accepted original registration only after authorized 404, not 403',async()=>{
  await bytes();transport.prepare.mockRejectedValueOnce(new UploadTransportError(0));await sender.run();const first=(await journal.intents(answer))[0];sender.close();
  sender=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:answer.attemptId,audio,journal,authority,transport,withLock:lock,online:()=>true});
  transport.status.mockRejectedValueOnce(new UploadTransportError(404));expect((await sender.run()).phase).toBe('BYTES_CONFIRMED');expect(transport.prepare.mock.calls[1][0]).toEqual(first);
  sender.close();sender=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:answer.attemptId,audio,journal,authority,transport,withLock:lock,online:()=>true});
  transport.status.mockRejectedValueOnce(new UploadTransportError(403));const count=transport.prepare.mock.calls.length;
  expect((await sender.run()).phase).toBe('IDENTITY_REQUIRED');expect(transport.prepare.mock.calls.length).toBe(count);
});
it('discards an apparently successful late receipt when identity changes during IO',async()=>{
  await bytes();transport.upload.mockImplementation(async(i:any)=>{authority.mockResolvedValue({...owner,studentId:13,workflowEnabled:true});return reply(i,true);});
  expect((await sender.run()).phase).toBe('IDENTITY_REQUIRED');expect((await sender.run()).confirmedChunks).toBe(0);
  expect((await journal.intents(answer)).length).toBe(1);
});
it('uses an independent non-stealing native lock, fails closed on unavailable locks, and closes pending work',async()=>{
  await bytes();sender.close();vi.stubGlobal('isSecureContext',true);
  const request=vi.fn(async(_key:string,options:unknown,callback:any)=>{expect(options).toEqual({mode:'exclusive',ifAvailable:true});return callback({});});
  vi.stubGlobal('navigator',{locks:{request},onLine:true});sender=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:answer.attemptId,audio,journal,authority,transport});
  expect((await sender.run()).phase).toBe('BYTES_CONFIRMED');expect(request.mock.calls[0][0]).not.toBe(await audio.captureWriterKey(answer));
  sender.close();expect((await sender.run()).phase).toBe('CLOSED');vi.unstubAllGlobals();
});
it('limits delayed large events to 8 MiB by reducing a group before registration and keeps the remainder unchanged',async()=>{
  const blob=new Blob([new Uint8Array(2097152)],{type:'audio/webm'});for(let n=0;n<5;n++)await audio.append(answer,segment.id,n,blob);await audio.seal(answer,segment.id,5,2000);
  expect((await sender.run()).phase).toBe('BYTES_CONFIRMED');const first=(await journal.intents(answer))[0];expect(first.command.byteSize).toBeLessThanOrEqual(8388608);expect(first.command.chunks).toHaveLength(2);
  expect((await sender.run()).phase).toBe('BYTES_CONFIRMED');expect((await audio.list(answer))[0].chunkCount).toBe(5);
});
it('reports corrupt or missing original local bytes as review, not an endless network retry loop',async()=>{
  await bytes();vi.spyOn(audio,'readUploadWindow').mockRejectedValueOnce(new (await import('@/prepared/localAudioJournal')).LocalAudioError('INTEGRITY'));
  expect((await sender.run()).phase).toBe('NEEDS_REVIEW');expect(transport.prepare).not.toHaveBeenCalled();
  expect((await sender.run()).phase).toBe('NEEDS_REVIEW');expect((await audio.list(answer))[0].chunkCount).toBe(3);
});
it('requires exact confirmed original chunk coverage before permitting whole-media verification',async()=>{
  await bytes();const end=await audio.prepareAnswerEnd(answer,{captureClientId:answer.captureClientId,idempotencyKey:crypto.randomUUID()});
  expect(await sender.isConfirmed(answer,end.manifest)).toBe(false);await sender.run();expect(await sender.isConfirmed(answer,end.manifest)).toBe(true);
  const altered=structuredClone(end.manifest);altered.segments[0].chunks[0].sha256='a'.repeat(64);
  await expect(sender.isConfirmed(answer,altered)).rejects.toMatchObject({code:'INTEGRITY'});
  sender.close();expect(await sender.isConfirmed(answer,end.manifest)).toBe(false);
});
it('selects only the caller-bound current attempt and never sends retained historical bytes or counts their receipt',async()=>{
  // Synthetic local scopes plus controlled transport, not actual retake authorization or cloud acceptance.
  sender.close();audio.close();journal.close();const factory=new IDBFactory();
  audio=new PreparedAudioJournal(owner,factory);journal=new PreparedUploadJournal(owner,8,factory);
  answer={...answer,attemptId:'ffffffff-ffff-4fff-8fff-ffffffffffff',answerId:crypto.randomUUID(),ordinal:1};
  segment={...segment,id:crypto.randomUUID()};await journal.track(answer);await audio.begin(answer,segment);await bytes();
  const currentEnd=await audio.prepareAnswerEnd(answer,{captureClientId:answer.captureClientId,idempotencyKey:crypto.randomUUID()});
  const historical={...answer,attemptId:'00000000-0000-4000-8000-000000000111',answerId:crypto.randomUUID(),captureClientId:crypto.randomUUID()},oldSegment={...segment,id:crypto.randomUUID()};
  await journal.track(historical);await audio.begin(historical,oldSegment);await audio.append(historical,oldSegment.id,0,new Blob(['retained historical bytes']));await audio.seal(historical,oldSegment.id,1,2000);
  const historicalEnd=await audio.prepareAnswerEnd(historical,{captureClientId:historical.captureClientId,idempotencyKey:crypto.randomUUID()});
  const before=await audio.readUploadWindow(historical,oldSegment.id,[0]);
  sender=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:answer.attemptId,audio,journal,authority,transport,withLock:lock,online:()=>true,random:()=>0});
  const progress=await sender.run();expect(progress.phase).toBe('BYTES_CONFIRMED');
  expect(transport.upload.mock.calls[0][0].answer).toEqual(answer);
  expect(await transport.upload.mock.calls[0][1].text()).toBe('original-0original-1original-2');
  expect(progress.confirmedChunks).toBe(3);expect(await sender.isConfirmed(answer,currentEnd.manifest)).toBe(true);
  expect(await sender.isConfirmed(historical,historicalEnd.manifest)).toBe(false);
  expect(await audio.readUploadWindow(historical,oldSegment.id,[0])).toEqual(before);expect(await audio.recoverAnswerEnd(historical)).toEqual(historicalEnd);
  expect(await journal.intents(historical)).toEqual([]);
});
it.each([undefined,null,'not-an-attempt'])('rejects missing or invalid attempt binding %s before creating any upload work',invalid=>{
  let unwanted:PreparedBackgroundUpload|undefined;
  try{
    expect(()=>{unwanted=new PreparedBackgroundUpload({owner,assessmentId:8,attemptId:invalid as never,audio,journal,authority,transport,withLock:lock,online:()=>true});}).toThrow();
    expect(authority).not.toHaveBeenCalled();expect(transport.prepare).not.toHaveBeenCalled();expect(transport.status).not.toHaveBeenCalled();expect(transport.upload).not.toHaveBeenCalled();
  }finally{unwanted?.close();}
});
