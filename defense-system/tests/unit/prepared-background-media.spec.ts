// @vitest-environment node
// Original IndexedDB commands with controlled media replies, NOT real cloud/device acceptance.
import {IDBFactory} from 'fake-indexeddb';
import {afterEach,beforeEach,it,expect,vi} from 'vitest';
import {PreparedAudioJournal} from '@/prepared/localAudioJournal';
import {PreparedUploadJournal} from '@/prepared/uploadJournal';
import {PreparedBackgroundMedia,verifyAnswerMediaSummary} from '@/prepared/backgroundMedia';
import {encodeAnswerEndManifest} from '@/prepared/answerEndManifest';
import {uploadHash} from '@/prepared/uploadBundle';
import {UploadTransportError} from '@/api/preparedUpload';
const owner={origin:'http://127.0.0.1:13015',studentId:12,deploymentId:'prepared-local'};
let factory:IDBFactory,audio:PreparedAudioJournal,journal:PreparedUploadJournal,answer:any,ending:any,hash:string,media:PreparedBackgroundMedia,authority:any,transport:any,confirmed:any;
const job=crypto.randomUUID();
function response(phase='NOT_QUEUED'){return {answerId:answer.answerId,manifestSha256:hash,jobId:phase==='NOT_QUEUED'?null:job,phase,serverSaved:phase==='SAVED',revision:phase==='SAVED'?2:1,durationMs:phase==='SAVED'?1000:null,verifiedAt:phase==='SAVED'?'2026-10-05T00:00:00Z':null};}
function fresh(){return new PreparedBackgroundMedia({owner,attemptId:answer.attemptId,audio,journal,authority,transport,bytesConfirmed:confirmed,online:()=>true,random:()=>0});}
beforeEach(async()=>{
  factory=new IDBFactory();audio=new PreparedAudioJournal(owner,factory);journal=new PreparedUploadJournal(owner,8,factory);
  answer={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID(),captureClientId:crypto.randomUUID(),ordinal:1};await journal.track(answer);
  const id=crypto.randomUUID();await audio.begin(answer,{id,ordinal:1,mimeType:'audio/webm',startedAt:1000});await audio.append(answer,id,0,new Blob(['original']));await audio.seal(answer,id,1,2000);
  ending=await audio.prepareAnswerEnd(answer,{idempotencyKey:crypto.randomUUID(),captureClientId:answer.captureClientId});hash=await uploadHash(encodeAnswerEndManifest(ending.manifest));
  authority=vi.fn(async()=>({...owner,workflowEnabled:true}));confirmed=vi.fn(async()=>true);
  transport={mediaStatus:vi.fn(async()=>response()),queueMedia:vi.fn(async()=>response('QUEUED'))};media=fresh();
  transport.window=vi.fn(async(a:any)=>({studentId:12,assessmentId:a.assessmentId,attemptId:a.attemptId,answerId:a.answerId,paperVersionId:'12345678-1234-1234-1234-123456789abc',manifestSha256:hash,answerDeadline:'2026-10-03T00:02:00Z',answeringEndedAt:'2026-10-03T00:01:00Z',automaticDeadline:'2026-10-04T00:01:00Z',serverNow:'2026-10-04T01:00:00Z',phase:'APPROVED',mayUploadOriginal:true,approvalRevision:1,approvedAt:'2026-10-04T00:02:00Z',uploadDeadline:'2026-10-04T02:00:00Z'}));
});
afterEach(()=>{media.close();audio.close();journal.close();});
it.each(['NOT_QUEUED','WAITING_APPROVAL'])('shows a waiting receipt immediately after an original %s request instead of claiming verification is running',async phase=>{
  transport.mediaStatus.mockResolvedValue(response(phase));transport.queueMedia.mockResolvedValueOnce(response('WAITING_APPROVAL'));
  expect((await media.run()).phase).toBe('WAITING_APPROVAL');expect(media.receipt(answer.answerId)).toBeUndefined();
  expect(transport.queueMedia.mock.calls[0].slice(0,2)).toEqual([answer,hash]);
  transport.mediaStatus.mockResolvedValue(response('WAITING_APPROVAL'));
  transport.window.mockResolvedValueOnce({...await transport.window.getMockImplementation()!(answer),phase:'AWAITING_APPROVAL',mayUploadOriginal:false,approvalRevision:0,approvedAt:null,uploadDeadline:'2026-10-04T00:01:00Z'});
  expect((await media.run()).phase).toBe('WAITING_APPROVAL');expect(transport.queueMedia).toHaveBeenCalledTimes(1);
  expect((await media.run()).phase).toBe('VERIFYING');expect(transport.queueMedia).toHaveBeenCalledTimes(2);
  expect(transport.queueMedia.mock.calls[1].slice(0,2)).toEqual([answer,hash]);expect(await audio.recoverAnswerEnd(answer)).toEqual(ending);
  expect(media.receipt(answer.answerId)).toBeUndefined();
});
it('a queued original waiting for approval survives refresh and resumes only its original job command after fresh approval',async()=>{
  transport.mediaStatus.mockResolvedValue(response('WAITING_APPROVAL'));
  transport.window.mockResolvedValueOnce({...await transport.window.getMockImplementation()!(answer),phase:'AWAITING_APPROVAL',mayUploadOriginal:false,approvalRevision:0,approvedAt:null,uploadDeadline:'2026-10-04T00:01:00Z'});
  expect((await media.run()).phase).toBe('WAITING_APPROVAL');expect(transport.queueMedia).not.toHaveBeenCalled();expect(confirmed).not.toHaveBeenCalled();
  media.close();media=fresh();expect((await media.run()).phase).toBe('VERIFYING');
  expect(transport.queueMedia.mock.calls[0].slice(0,2)).toEqual([answer,hash]);expect(await audio.recoverAnswerEnd(answer)).toEqual(ending);
  expect(media.receipt(answer.answerId)).toBeUndefined();
  transport.mediaStatus.mockResolvedValue(response('SAVED'));expect((await media.run()).phase).toBe('SAVED');expect(media.receipt(answer.answerId)?.jobId).toBe(job);
});
it('waiting queued media cannot resume from another manifest, revoked identity or a false approval',async()=>{
  transport.mediaStatus.mockResolvedValue(response('WAITING_APPROVAL'));
  transport.window.mockResolvedValueOnce({...await transport.window.getMockImplementation()!(answer),manifestSha256:'b'.repeat(64)});
  expect((await media.run()).phase).toBe('NEEDS_REVIEW');expect(transport.queueMedia).not.toHaveBeenCalled();
  media.close();media=fresh();transport.window.mockImplementationOnce(async()=>{authority.mockResolvedValue({...owner,studentId:13,workflowEnabled:true});return await transport.window.getMockImplementation()!(answer);});
  expect((await media.run()).phase).toBe('IDENTITY_REQUIRED');expect(transport.queueMedia).not.toHaveBeenCalled();
  media.close();authority.mockResolvedValue({...owner,workflowEnabled:true});media=fresh();
  transport.window.mockResolvedValueOnce({...await transport.window.getMockImplementation()!(answer),phase:'AUTOMATIC_WINDOW',approvalRevision:0,approvedAt:null,serverNow:'2026-10-03T01:00:00Z',uploadDeadline:'2026-10-04T00:01:00Z'});
  expect((await media.run()).phase).toBe('NEEDS_REVIEW');expect(transport.queueMedia).not.toHaveBeenCalled();expect(media.receipt(answer.answerId)).toBeUndefined();
});
it('a queued approval expiring during resume returns to waiting while generic damage remains manual',async()=>{
  transport.mediaStatus.mockResolvedValue(response('WAITING_APPROVAL'));transport.queueMedia.mockRejectedValueOnce(new UploadTransportError(409,0,'UPLOAD_WINDOW_EXPIRED'));
  expect((await media.run()).phase).toBe('WAITING_APPROVAL');expect(transport.queueMedia).toHaveBeenCalledTimes(1);
  transport.queueMedia.mockRejectedValueOnce(new UploadTransportError(409));expect((await media.run()).phase).toBe('NEEDS_REVIEW');
  expect(transport.queueMedia).toHaveBeenCalledTimes(2);expect((await media.run()).phase).toBe('NEEDS_REVIEW');expect(transport.queueMedia).toHaveBeenCalledTimes(2);
  expect(await audio.recoverAnswerEnd(answer)).toEqual(ending);
});
it('expired queue writes wait for fresh original-window approval rather than permanently marking media damaged',async()=>{
  transport.queueMedia.mockRejectedValueOnce(new UploadTransportError(409,0,'UPLOAD_WINDOW_EXPIRED'));expect((await media.run()).phase).toBe('WAITING_APPROVAL');
  const original=await audio.recoverAnswerEnd(answer);transport.window.mockImplementationOnce(async(a:any)=>({...await transport.window.getMockImplementation()!(a),phase:'AWAITING_APPROVAL',mayUploadOriginal:false,approvalRevision:0,approvedAt:null,uploadDeadline:'2026-10-04T00:01:00Z'}));
  expect((await media.run()).phase).toBe('WAITING_APPROVAL');expect(transport.queueMedia).toHaveBeenCalledTimes(1);
  expect((await media.run()).phase).toBe('VERIFYING');expect(transport.queueMedia).toHaveBeenCalledTimes(2);expect(transport.queueMedia.mock.calls[1].slice(0,2)).toEqual([answer,hash]);expect(await audio.recoverAnswerEnd(answer)).toEqual(original);
});
it('queues only the durable original manifest after exact byte acknowledgements, without audio deletion or recording',async()=>{
  transport.queueMedia.mockImplementation(async(a:any,digest:string)=>{expect(a).toEqual(answer);expect(digest).toBe(hash);expect(await audio.recoverAnswerEnd(answer)).toEqual(ending);return response('QUEUED');});
  expect((await media.run()).phase).toBe('VERIFYING');expect(transport.queueMedia).toHaveBeenCalledTimes(1);
  transport.mediaStatus.mockResolvedValue(response('SAVED'));expect((await media.run()).phase).toBe('SAVED');expect(media.receipt(answer.answerId)?.serverSaved).toBe(true);
  expect((await audio.list(answer))[0]).toMatchObject({state:'SEALED',chunkCount:1});expect(await journal.intents(answer)).toEqual([]);
});
it('distinguishes unregistered end, pending bytes, disabled verification and real saved state',async()=>{
  transport.mediaStatus.mockResolvedValueOnce({...response(),manifestSha256:null});expect((await media.run()).phase).toBe('WAITING_END');expect(transport.queueMedia).not.toHaveBeenCalled();
  confirmed.mockResolvedValueOnce(false);expect((await media.run()).phase).toBe('WAITING_BYTES');expect(transport.queueMedia).not.toHaveBeenCalled();
  authority.mockResolvedValue({...owner,workflowEnabled:false});expect((await media.run()).phase).toBe('CLOSED');expect(transport.queueMedia).not.toHaveBeenCalled();
  transport.mediaStatus.mockResolvedValue(response('SAVED'));expect((await media.run()).phase).toBe('SAVED');
});
it('recovers the same original media job after response loss and reload instead of creating replacement recordings or manifests',async()=>{
  transport.queueMedia.mockRejectedValueOnce(new UploadTransportError(0));expect((await media.run()).phase).toBe('WAITING');media.close();media=fresh();
  transport.mediaStatus.mockResolvedValue(response('RUNNING'));expect((await media.run()).phase).toBe('VERIFYING');expect(transport.queueMedia).toHaveBeenCalledTimes(1);
  transport.mediaStatus.mockResolvedValue(response('SAVED'));expect((await media.run()).phase).toBe('SAVED');
});
it('rejects false/mismatched saved replies and discards late receipts after identity changes',async()=>{
  transport.mediaStatus.mockResolvedValueOnce({...response('SAVED'),manifestSha256:'b'.repeat(64)});expect((await media.run()).phase).toBe('NEEDS_REVIEW');expect(media.receipt(answer.answerId)).toBeUndefined();
  media.close();media=fresh();transport.mediaStatus.mockImplementation(async()=>{authority.mockResolvedValue({...owner,studentId:13,workflowEnabled:true});return response('SAVED');});
  expect((await media.run()).phase).toBe('IDENTITY_REQUIRED');expect(media.receipt(answer.answerId)).toBeUndefined();
});
it('permits only one in-flight operation and observes backoff/aborts without changing original bytes',async()=>{
  let done!:(v:any)=>void;transport.mediaStatus.mockImplementationOnce(()=>new Promise(resolve=>{done=resolve;}));const work=media.run();
  await vi.waitFor(()=>expect(transport.mediaStatus).toHaveBeenCalledTimes(1));expect((await media.run()).phase).toBe('VERIFYING');media.close();done(response('SAVED'));
  expect((await work).phase).toBe('CLOSED');expect(media.receipt(answer.answerId)).toBeUndefined();media=fresh();
  transport.mediaStatus.mockRejectedValueOnce(new UploadTransportError(503,20));const waiting=await media.run();expect(waiting.phase).toBe('WAITING');expect(waiting.retryAt!-Date.now()).toBeGreaterThan(19000);
  const calls=transport.mediaStatus.mock.calls.length;expect((await media.run()).phase).toBe('WAITING');expect(transport.mediaStatus).toHaveBeenCalledTimes(calls);
});
it('cannot confuse byte acknowledgements, missing jobs, timestamps or duration with whole-media saving',()=>{
  expect(verifyAnswerMediaSummary(answer,hash,response('SAVED')).serverSaved).toBe(true);
  for(const change of [{phase:'SUCCEEDED'},{answerId:crypto.randomUUID()},{jobId:null},{serverSaved:false},{durationMs:0},{durationMs:1800001},{verifiedAt:null},{verifiedAt:'bad'},{revision:0},{phase:'QUEUED'}])
    expect(()=>verifyAnswerMediaSummary(answer,hash,{...response('SAVED'),...change})).toThrow();
  for(const phase of ['NOT_QUEUED','QUEUED','RUNNING','RETRY_WAIT','NEEDS_REVIEW','WAITING_APPROVAL'])expect(verifyAnswerMediaSummary(answer,hash,response(phase)).serverSaved).toBe(false);
});
it('does not send offline or invent a missing original ending, and clears cached receipts after revoked identity',async()=>{
  media.close();media=new PreparedBackgroundMedia({owner,attemptId:answer.attemptId,audio,journal,authority,transport,bytesConfirmed:confirmed,online:()=>false});
  expect((await media.run()).phase).toBe('OFFLINE');expect(authority).not.toHaveBeenCalled();media.close();media=fresh();
  vi.spyOn(audio,'recoverAnswerEnd').mockRejectedValueOnce(new (await import('@/prepared/localAudioJournal')).LocalAudioError('MISSING_ANSWER_END'));
  expect((await media.run()).phase).toBe('LOCAL_ONLY');expect(transport.mediaStatus).not.toHaveBeenCalled();
  transport.mediaStatus.mockResolvedValue(response('SAVED'));expect((await media.run()).phase).toBe('SAVED');
  authority.mockRejectedValueOnce(new UploadTransportError(403));expect((await media.run()).phase).toBe('IDENTITY_REQUIRED');expect(media.receipt(answer.answerId)).toBeUndefined();
});
it('one damaged original answer needs review without starving another original answer of the same attempt',async()=>{
  const next={...answer,answerId:crypto.randomUUID(),ordinal:2},id=crypto.randomUUID();await journal.track(next);
  await audio.begin(next,{id,ordinal:1,mimeType:'audio/webm',startedAt:1000});await audio.append(next,id,0,new Blob(['second']));await audio.seal(next,id,1,2000);
  const end=await audio.prepareAnswerEnd(next,{captureClientId:answer.captureClientId,idempotencyKey:crypto.randomUUID()}),digest=await uploadHash(encodeAnswerEndManifest(end.manifest));
  const recover=audio.recoverAnswerEnd.bind(audio);vi.spyOn(audio,'recoverAnswerEnd').mockImplementation(async a=>{if(a.answerId===answer.answerId)throw new (await import('@/prepared/localAudioJournal')).LocalAudioError('INTEGRITY');return recover(a);});
  expect((await media.run()).phase).toBe('NEEDS_REVIEW');
  transport.mediaStatus.mockImplementation(async (a:typeof answer)=>({...response('SAVED'),answerId:a.answerId,manifestSha256:digest,jobId:crypto.randomUUID()}));
  expect((await media.run()).phase).toBe('SAVED');expect(media.receipt(next.answerId)?.serverSaved).toBe(true);expect(media.receipt(answer.answerId)).toBeUndefined();
  expect((await audio.list(answer))[0].byteSize).toBeGreaterThan(0);expect((await audio.list(next))[0].byteSize).toBeGreaterThan(0);
});
it('retained answers from an earlier attempt do not exhaust the current attempt question limit or send historical media commands',async()=>{
  // Fifty is the existing per-paper limit, not a lifetime limit for this student/assessment.
  // These are retained synthetic records, not proof that an actual retake has been authorized.
  const historicalAttempt=crypto.randomUUID(),historicalClient=crypto.randomUUID();
  for(let ordinal=1;ordinal<=50;ordinal++)await journal.track({...answer,attemptId:historicalAttempt,answerId:crypto.randomUUID(),captureClientId:historicalClient,ordinal});
  const db=await new Promise<IDBDatabase>((resolve,reject)=>{const r=factory.open('prepared-interview-upload-v1',1);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
  const retained=()=>new Promise<unknown[]>((resolve,reject)=>{const tx=db.transaction('answers','readonly'),request=tx.objectStore('answers').getAll();let rows:unknown[]=[];
    request.onsuccess=()=>{rows=request.result;};tx.oncomplete=()=>resolve(rows);tx.onabort=()=>reject(tx.error);});
  try{
    const before=await retained();expect(before).toHaveLength(51);
    expect((await media.run()).phase).toBe('VERIFYING');
    expect(transport.mediaStatus).toHaveBeenCalledExactlyOnceWith(answer,expect.any(AbortSignal));
    expect(transport.queueMedia).toHaveBeenCalledExactlyOnceWith(answer,hash,expect.any(AbortSignal));
    expect(await retained()).toEqual(before);expect(await audio.recoverAnswerEnd(answer)).toEqual(ending);
    expect(media.receipt(answer.answerId)).toBeUndefined();
  }finally{db.close();}
});
