import {beforeEach,expect,it,vi} from 'vitest';
import request from '@/utils/request';
import {startPreparedAttempt,getCurrentPreparedAttempt,startPreparedQuestion,getCurrentPreparedQuestion,endPreparedAnswer,getPreparedQuestionAudio} from '@/api/preparedAttempt';
import type {AnswerEndCommand} from '@/prepared/answerEndManifest';
import type {AxiosRequestConfig} from 'axios';
vi.mock('@/utils/request',()=>({default:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',answer='22222222-2222-4222-8222-222222222222',client='33333333-3333-4333-8333-333333333333',key='44444444-4444-4444-8444-444444444444';
const ending=():AnswerEndCommand=>({idempotencyKey:key,captureClientId:client,manifest:{schemaVersion:1,segments:[{id:answer,ordinal:1,mimeType:'audio/webm',startedAt:1,endedAt:2,state:'SEALED',interruption:null,chunkCount:1,byteSize:1,chunks:[{index:0,byteSize:1,sha256:'a'.repeat(64)}]}]}});
beforeEach(()=>{vi.mocked(request).mockReset();vi.mocked(request).mockResolvedValue({data:null});});
it('reads the exact original question audio with bounded cancellable GET and no supplied asset, identity, or new recording command',async()=>{
  const signal=new AbortController().signal;await getPreparedQuestionAudio(8,attempt,answer,client,signal);
  expect(request).toHaveBeenCalledWith({url:`/backend/prepared-interviews/8/attempts/${attempt}/answers/${answer}/question-audio`,method:'get',params:{captureClientId:client},timeout:10000,signal});
  vi.mocked(request).mockClear();for(const invoke of [()=>getPreparedQuestionAudio(0,attempt,answer,client),()=>getPreparedQuestionAudio(8,'../other',answer,client),()=>getPreparedQuestionAudio(8,attempt,'future',client),()=>getPreparedQuestionAudio(8,attempt,answer,'invalid')])expect(invoke).toThrow();expect(request).not.toHaveBeenCalled();
});
it('starts with an explicit original key and bounded cancellable same-origin HTTP rather than WebSocket',async()=>{
  const signal=new AbortController().signal;await startPreparedAttempt(8,{idempotencyKey:key},signal);
  expect(request).toHaveBeenCalledWith({url:'/backend/prepared-interviews/8/attempts',method:'post',data:{idempotencyKey:key},timeout:10000,signal});
});
it('queries the current attempt without mutating or guessing a next question',async()=>{
  await getCurrentPreparedAttempt(8);await getCurrentPreparedQuestion(8,attempt,client);
  expect(request).toHaveBeenNthCalledWith(1,{url:'/backend/prepared-interviews/8/attempts/current',method:'get',timeout:10000,signal:undefined});
  expect(request).toHaveBeenNthCalledWith(2,{url:`/backend/prepared-interviews/8/attempts/${attempt}/questions/current`,method:'get',params:{captureClientId:client},timeout:10000,signal:undefined});
});
it('whitelists original question fields and snapshots the input without forwarding identity or a replacement key',async()=>{
  const command={idempotencyKey:key,captureClientId:client,ordinal:1,studentId:99};await startPreparedQuestion(8,attempt,command);command.ordinal=2;
  expect(vi.mocked(request).mock.calls[0][0]).toEqual({url:`/backend/prepared-interviews/8/attempts/${attempt}/questions/start`,method:'post',data:{idempotencyKey:key,captureClientId:client,ordinal:1},timeout:10000,signal:undefined});
});
it('replays the exact ending identifiers and metadata without blobs, URLs, identities or local-command mutation',async()=>{
  const command=ending();Object.assign(command,{studentId:99,blob:new Blob(['private']),url:'https://untrusted.invalid'});
  for(let i=0;i<100;i++)await endPreparedAnswer(8,attempt,answer,command);
  command.manifest.segments[0].chunks[0].sha256='b'.repeat(64);
  for(const [call] of vi.mocked(request).mock.calls){const config=call as AxiosRequestConfig;expect(config.data).toEqual(ending());expect(config.data).not.toBe(command);}
  expect(request).toHaveBeenCalledTimes(100);
});
it('does not turn declaration confirmation into reliable cloud save, submission or a fallback success',async()=>{
  const receipt={serverSaved:false,declarationState:'UPLOAD_PENDING'};vi.mocked(request).mockResolvedValue({data:receipt});
  expect((await endPreparedAnswer(8,attempt,answer,ending())).data).toEqual(receipt);
  vi.mocked(request).mockRejectedValue(new Error('response lost'));
  await expect(endPreparedAnswer(8,attempt,answer,ending())).rejects.toThrow('response lost');expect(request).toHaveBeenCalledTimes(2);
});
it('rejects invalid scopes, ordinals and damaged original keys before any request',async()=>{
  const calls:Array<()=>unknown>=[()=>startPreparedAttempt(0,{idempotencyKey:key}),()=>startPreparedAttempt(8,{idempotencyKey:'new-key'}),()=>getCurrentPreparedAttempt(NaN),()=>getCurrentPreparedQuestion(8,'../other',client),()=>getCurrentPreparedQuestion(8,attempt,'client'),()=>startPreparedQuestion(8,attempt,{idempotencyKey:key,captureClientId:client,ordinal:0}),()=>startPreparedQuestion(8,attempt,{idempotencyKey:key,captureClientId:client,ordinal:51}),()=>startPreparedQuestion(8,attempt,{idempotencyKey:key,captureClientId:'client',ordinal:1}),()=>endPreparedAnswer(8,attempt,'other',ending()),()=>endPreparedAnswer(8,attempt,answer,{...ending(),idempotencyKey:'lost'})];
  for(const call of calls)await expect(Promise.resolve().then(call)).rejects.toThrow();expect(request).not.toHaveBeenCalled();
});
