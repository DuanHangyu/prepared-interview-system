import { mount,flushPromises } from '@vue/test-utils';
import { beforeEach,afterEach,it,expect,vi } from 'vitest';
import PreparedOriginalAudio from '@/views/prepared/PreparedOriginalAudio.vue';
import { getReviewAudio } from '@/api/preparedReview';
vi.mock('@/api/preparedReview',()=>({getReviewAudio:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',answer='22222222-2222-4222-8222-222222222222';
const props={actorId:7,assessmentId:21,studentId:8,attemptId:attempt,answerId:answer};
const url=`https://prepared-fixture.oss-cn-beijing.aliyuncs.com/prepared/prepared-fixture/students/8/assessments/21/attempts/${attempt}/answers/${answer}/media/${'a'.repeat(64)}.wav?Signature=private`;
const data=()=>({...props,url,serverTime:new Date(Date.now()).toISOString(),expiresAt:new Date(Date.now()+300000).toISOString(),sha256:'b'.repeat(64),durationMs:1000,byteSize:32044});
let wrappers:ReturnType<typeof mount>[]=[];
const render=()=>{const w=mount(PreparedOriginalAudio,{props});wrappers.push(w);return w;};
beforeEach(()=>{vi.useFakeTimers();vi.mocked(getReviewAudio).mockReset();vi.mocked(getReviewAudio).mockResolvedValue({data:data()});vi.spyOn(HTMLMediaElement.prototype,'pause').mockImplementation(()=>{});vi.spyOn(HTMLMediaElement.prototype,'load').mockImplementation(()=>{});});
afterEach(()=>{wrappers.forEach(w=>w.unmount());wrappers=[];vi.useRealTimers();});
it('only requests exact original scope on demand and keeps the signed URL out of persistent storage',async()=>{
  const session=vi.spyOn(Storage.prototype,'setItem');const w=render();expect(getReviewAudio).not.toHaveBeenCalled();expect(w.find('audio').exists()).toBe(false);
  await w.find('button').trigger('click');await flushPromises();expect(getReviewAudio).toHaveBeenCalledWith(21,attempt,answer);
  expect(w.find('audio').attributes('src')).toBe(url);expect(w.find('audio').attributes('preload')).toBe('none');expect(w.find('audio').attributes('autoplay')).toBeUndefined();expect(session).not.toHaveBeenCalled();
  expect(w.text()).toContain('1 秒');expect(w.text()).not.toContain('Signature=');
});
it('retries transient authorization and playback failures without reanswering, resetting grades or exposing signatures',async()=>{
  vi.mocked(getReviewAudio).mockRejectedValueOnce(new Error('存储暂不可用'));const w=render();await w.find('button').trigger('click');await flushPromises();
  expect(w.text()).toContain('存储暂不可用');expect(w.find('audio').exists()).toBe(false);await w.find('button').trigger('click');await flushPromises();
  await w.find('audio').trigger('error');expect(w.text()).toContain('播放中断');expect(w.text()).toContain('不要求学生重答');expect(w.find('audio').exists()).toBe(false);
  await w.find('button').trigger('click');await flushPromises();expect(getReviewAudio).toHaveBeenCalledTimes(3);expect(w.find('audio').exists()).toBe(true);
});
it('expires the capability, stops the native player and requires fresh authorization rather than silently renewing',async()=>{
  const w=render();await w.find('button').trigger('click');await flushPromises();await vi.advanceTimersByTimeAsync(300000);
  expect(w.find('audio').exists()).toBe(false);expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();expect(HTMLMediaElement.prototype.load).toHaveBeenCalled();expect(w.text()).toContain('授权已到期');expect(getReviewAudio).toHaveBeenCalledTimes(1);
});
it('drops old playback immediately on scope change, ignores late old responses and stops playback on unmount',async()=>{
  const w=render();await w.find('button').trigger('click');await flushPromises();expect(w.find('audio').exists()).toBe(true);
  let resolve!:(v:{data:ReturnType<typeof data>})=>void;vi.mocked(getReviewAudio).mockImplementationOnce(()=>new Promise(r=>resolve=r));
  await w.find('button').trigger('click');await w.setProps({answerId:'33333333-3333-4333-8333-333333333333'});resolve({data:data()});await flushPromises();expect(w.find('audio').exists()).toBe(false);
  w.unmount();expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
});
it('refuses mismatched subjects, unsupported URLs, invalid proof and expired or unbounded capabilities',async()=>{
  const malformed=[{actorId:9},{assessmentId:22},{studentId:9},{attemptId:answer},{answerId:attempt},{durationMs:0},{byteSize:32013},{sha256:'bad'},
    {expiresAt:'bad'},{expiresAt:new Date(Date.now()).toISOString()},{expiresAt:new Date(Date.now()+300001).toISOString()},{expiresAt:new Date(Date.now()+301000).toISOString()},
    {url:url.replace('https:','http:')},{url:url.replace('prepared-fixture.oss-cn-beijing.aliyuncs.com','evil.invalid')},{url:url.replace('/students/8/','/students/9/')},
    {url:url.replace('https://','https://user@')},{url:url+'#fragment'},{url:url.replace('.com/','.com:8443/')},{url:url.split('?')[0]},
    {url:url+'x'.repeat(4096)},{url:url.replace('/media/','/chunks/')},{url:url.replace('prepared-fixture/students','../prepared-fixture/students')}];
  const w=render();for(const change of malformed){vi.mocked(getReviewAudio).mockResolvedValueOnce({data:{...data(),...change}});await w.find('button').trigger('click');await flushPromises();expect(w.find('audio').exists()).toBe(false);expect(w.text()).toContain('无法核验');}
});
it('accepts legitimate encoded signature parameters and rounded-up PCM duration without weakening object-path checks',async()=>{
  const w=render();vi.mocked(getReviewAudio).mockResolvedValueOnce({data:{...data(),url:url+'&x-oss-credential=fixture%2F20261005',byteSize:32014}});
  await w.find('button').trigger('click');await flushPromises();expect(w.find('audio').exists()).toBe(true);
});
it('uses server validity and monotonic elapsed time instead of trusting the teacher computer clock',async()=>{
  const original=data();vi.mocked(getReviewAudio).mockResolvedValueOnce({data:original});vi.setSystemTime(Date.now()+86400000);
  const w=render();await w.find('button').trigger('click');await flushPromises();expect(w.find('audio').exists()).toBe(true);
});
it('deducts monotonic request latency and never extends a delayed or already expired authorization',async()=>{
  let resolve!:(v:{data:ReturnType<typeof data>})=>void;const original=data();
  vi.mocked(getReviewAudio).mockImplementationOnce(()=>new Promise(r=>resolve=r));
  const time=vi.spyOn(performance,'now').mockReturnValue(1000),w=render();await w.find('button').trigger('click');
  time.mockReturnValue(6000);resolve({data:original});await flushPromises();expect(w.find('audio').exists()).toBe(true);
  await vi.advanceTimersByTimeAsync(294999);expect(w.find('audio').exists()).toBe(true);
  await vi.advanceTimersByTimeAsync(1);expect(w.find('audio').exists()).toBe(false);expect(w.text()).toContain('授权已到期');
  vi.mocked(getReviewAudio).mockImplementationOnce(()=>new Promise(r=>resolve=r));time.mockReturnValue(7000);
  await w.find('button').trigger('click');time.mockReturnValue(307000);resolve({data:original});await flushPromises();
  expect(w.find('audio').exists()).toBe(false);expect(w.text()).toContain('无法核验');expect(getReviewAudio).toHaveBeenCalledTimes(2);
});
