import {mount,flushPromises} from '@vue/test-utils';
import {beforeEach,afterEach,it,expect,vi} from 'vitest';
import PreparedQuestionAudio from '@/views/prepared/PreparedQuestionAudio.vue';
import {getPreparedQuestionAudio} from '@/api/preparedAttempt';
vi.mock('@/api/preparedAttempt',()=>({getPreparedQuestionAudio:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',answer='22222222-2222-4222-8222-222222222222',question='33333333-3333-4333-8333-333333333333',asset='44444444-4444-4444-8444-444444444444',client='55555555-5555-4555-8555-555555555555',version='66666666-6666-4666-8666-666666666666';
const props={studentId:8,assessmentId:21,attemptId:attempt,answerId:answer,questionId:question,paperVersionId:version,captureClientId:client,deploymentId:'prepared-fixture'};
const url=`https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/prepared/prepared-fixture/assessments/21/questions/${question}/audio/${asset}.wav?Signature=private`;
const data=()=>({...props,assetId:asset,url,serverTime:new Date().toISOString(),expiresAt:new Date(Date.now()+300000).toISOString(),sha256:'b'.repeat(64),durationMs:1000,byteSize:32044});
let wrappers:ReturnType<typeof mount>[]=[];
const render=()=>{const w=mount(PreparedQuestionAudio,{props});wrappers.push(w);return w;};
beforeEach(()=>{vi.useFakeTimers();vi.mocked(getPreparedQuestionAudio).mockReset();vi.mocked(getPreparedQuestionAudio).mockResolvedValue({data:data()});vi.spyOn(HTMLMediaElement.prototype,'pause').mockImplementation(()=>{});vi.spyOn(HTMLMediaElement.prototype,'load').mockImplementation(()=>{});});
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());vi.useRealTimers();});
it('requests only the authorized original question on demand and never persists or auto-plays the capability',async()=>{
  const storage=vi.spyOn(Storage.prototype,'setItem'),w=render();expect(getPreparedQuestionAudio).not.toHaveBeenCalled();await w.get('button').trigger('click');await flushPromises();
  expect(getPreparedQuestionAudio).toHaveBeenCalledWith(21,attempt,answer,client,expect.any(AbortSignal));expect(w.get('audio').attributes('src')).toBe(url);expect(w.get('audio').attributes('preload')).toBe('none');expect(w.get('audio').attributes('autoplay')).toBeUndefined();
  expect(storage).not.toHaveBeenCalled();expect(w.text()).not.toContain('Signature');expect(w.text()).toContain('不阻止录音');
});
it('storage failure, media failure and expiry allow fresh retry without changing the answer or blocking recording',async()=>{
  vi.mocked(getPreparedQuestionAudio).mockRejectedValueOnce(new Error('secret-provider-url'));const w=render();await w.get('button').trigger('click');await flushPromises();expect(w.find('audio').exists()).toBe(false);expect(w.text()).not.toContain('secret-provider-url');expect(w.text()).toContain('不阻止录音');
  await w.get('button').trigger('click');await flushPromises();await w.get('audio').trigger('error');expect(w.find('audio').exists()).toBe(false);
  await w.get('button').trigger('click');await flushPromises();await vi.advanceTimersByTimeAsync(300000);expect(w.find('audio').exists()).toBe(false);expect(w.text()).toContain('到期');expect(getPreparedQuestionAudio).toHaveBeenCalledTimes(3);
});
it('aborts, stops playback and discards late responses on question, client, deployment or identity changes',async()=>{
  const w=render();await w.get('button').trigger('click');await flushPromises();expect(w.find('audio').exists()).toBe(true);
  let resolve!:(v:{data:ReturnType<typeof data>})=>void;vi.mocked(getPreparedQuestionAudio).mockImplementationOnce(()=>new Promise(r=>resolve=r));await w.get('button').trigger('click');
  const signal=vi.mocked(getPreparedQuestionAudio).mock.calls[1][4]!;await w.setProps({studentId:9});expect(signal.aborted).toBe(true);resolve({data:data()});await flushPromises();expect(w.find('audio').exists()).toBe(false);
  expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();expect(HTMLMediaElement.prototype.load).toHaveBeenCalled();
});
it('rejects mismatched scope, draft/future assets, another deployment, unsupported proof and unsafe URLs',async()=>{
  const changes=[{studentId:9},{assessmentId:22},{attemptId:answer},{answerId:attempt},{questionId:answer},{paperVersionId:answer},{captureClientId:answer},{assetId:'bad'},{durationMs:0},{byteSize:0},{sha256:'bad'},{expiresAt:'bad'},{expiresAt:new Date().toISOString()},{expiresAt:new Date(Date.now()+301000).toISOString()},
    {url:url.replace('https:','http:')},{url:url.replace('prepared-interview-fixture.oss-cn-beijing.aliyuncs.com','evil.invalid')},{url:url.replace('/prepared-fixture/','/prepared-another/')},{url:url.replace(question,answer)},{url:url.replace(asset,answer)},
    {url:url.replace('https://','https://user@')},{url:url+'#fragment'},{url:url.replace('.com/','.com:8443/')},{url:url.split('?')[0]},{url:url+'x'.repeat(4096)},{url:url.replace('/audio/','/../audio/')},{url:url.replace('/audio/','/%61udio/')}];
  const w=render();for(const change of changes){vi.mocked(getPreparedQuestionAudio).mockResolvedValueOnce({data:{...data(),...change}});await w.get('button').trigger('click');await flushPromises();expect(w.find('audio').exists()).toBe(false);expect(w.text()).toContain('不阻止录音');}
});
it('uses the server lifetime less monotonic request latency and not the student computer clock',async()=>{
  let resolve!:(v:{data:ReturnType<typeof data>})=>void;const original=data();vi.mocked(getPreparedQuestionAudio).mockImplementationOnce(()=>new Promise(r=>resolve=r));const time=vi.spyOn(performance,'now').mockReturnValue(1000),w=render();await w.get('button').trigger('click');time.mockReturnValue(6000);vi.setSystemTime(Date.now()+86400000);resolve({data:original});await flushPromises();
  expect(w.find('audio').exists()).toBe(true);await vi.advanceTimersByTimeAsync(295000);expect(w.find('audio').exists()).toBe(false);
});
