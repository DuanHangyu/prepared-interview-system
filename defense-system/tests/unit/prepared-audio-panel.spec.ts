import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Panel from '@/views/prepared/PreparedAudioPanel.vue';
import { getPreparedAudio, prepareQuestionAudio, recoverQuestionAudio, getAudioPreview, type AudioListing, type AudioView } from '@/api/preparedAudio';
vi.mock('@/api/preparedAudio', () => ({ getPreparedAudio: vi.fn(), prepareQuestionAudio: vi.fn(), recoverQuestionAudio: vi.fn(), getAudioPreview: vi.fn() }));
const q='11111111-1111-4111-8111-111111111111',asset='22222222-2222-4222-8222-222222222222';
const base: AudioView={ id:asset,questionId:q,bodySha256:'a'.repeat(64),jobId:'33333333-3333-4333-8333-333333333333',state:'GENERATING',partCount:2,completedParts:0,newPartCount:2,maximumNewCostCny:0.096,errorCode:null,assemblyRecoverable:false,byteSize:null,durationMs:null,createdAt:'2026-10-04T08:00:00Z' };
const listing: AudioListing={assessmentId:21,draftRevision:2,frozen:false,availability:{enabled:true,reason:'合成测试配置，非真实模型',confirmationSha256:'b'.repeat(64),model:'qwen3-tts-flash',voice:'Cherry',maximumPartCostCny:0.048},current:[],history:[]};
const props={assessmentId:21,revision:2,hasUnsavedChanges:false,parentBusy:false,questions:[{questionId:q,ordinal:1,body:'题目。'.repeat(220),approved:true}]};
let mounted: ReturnType<typeof mount>[]=[];
const render=()=>{const w=mount(Panel,{props});mounted.push(w);return w;};
const button=(w:ReturnType<typeof mount>,name:string)=>w.findAll('button').find(b=>b.text()===name)!;
beforeEach(()=>{vi.resetAllMocks();vi.useFakeTimers();vi.mocked(getPreparedAudio).mockResolvedValue({data:listing});
  vi.mocked(prepareQuestionAudio).mockResolvedValue({data:base});vi.mocked(recoverQuestionAudio).mockResolvedValue({data:{...base,newPartCount:0,maximumNewCostCny:0}});
  vi.mocked(getAudioPreview).mockResolvedValue({data:{url:'https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/prepared/audio.wav?Signature=fixture',expiresAt:'2026-10-04T08:05:00Z'}});
  vi.spyOn(window,'confirm').mockReturnValue(true);});
afterEach(()=>{mounted.forEach(w=>w.unmount());mounted=[];vi.useRealTimers();});
describe('teacher audio panel, synthetic API contracts only',()=>{
  it('honestly shows missing cloud prerequisites and never enables a paid command',async()=>{
    vi.mocked(getPreparedAudio).mockResolvedValue({data:{...listing,availability:{...listing.availability,enabled:false,reason:'独立计费未核验',confirmationSha256:null}}});
    const w=render();await flushPromises();expect(w.text()).toContain('独立计费未核验');expect(button(w,'准备本题播报').attributes('disabled')).toBeDefined();expect(prepareQuestionAudio).not.toHaveBeenCalled();
  });
  it('requires current saved reviewed text and confirms the conservative maximum before enqueueing',async()=>{
    const w=render();await flushPromises();await w.setProps({hasUnsavedChanges:true});expect(button(w,'准备本题播报').attributes('disabled')).toBeDefined();
    await w.setProps({hasUnsavedChanges:false});await button(w,'准备本题播报').trigger('click');await flushPromises();
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('费用上界'));expect(prepareQuestionAudio).toHaveBeenCalledWith(21,expect.objectContaining({expectedRevision:2,questionId:q,expectedConfigurationSha256:'b'.repeat(64)}));
    expect(w.text()).toContain('分段已保存 0 / 2');expect(w.emitted('busy')?.map(e=>e[0])).toContain(true);
  });
  it('response loss retries the exact original command instead of creating another paid task',async()=>{
    vi.mocked(prepareQuestionAudio).mockRejectedValueOnce(new Error('响应丢失'));const w=render();await flushPromises();await button(w,'准备本题播报').trigger('click');await flushPromises();
    await w.setProps({revision:3});await flushPromises();await button(w,'重试原播报命令').trigger('click');await flushPromises();
    expect(vi.mocked(prepareQuestionAudio).mock.calls[1]).toEqual(vi.mocked(prepareQuestionAudio).mock.calls[0]);expect(window.confirm).toHaveBeenCalledTimes(1);
  });
  it('successful parts are restored from the server and all-part failure offers zero-paid assembly recovery',async()=>{
    vi.mocked(getPreparedAudio).mockResolvedValue({data:{...listing,current:[{...base,state:'FAILED',completedParts:2,assemblyRecoverable:true,errorCode:'ASSEMBLY_SAVE_FAILED'}]}});
    const w=render();await flushPromises();expect(w.text()).toContain('不再调用语音模型');await button(w,'仅重试合成和保存').trigger('click');await flushPromises();
    expect(recoverQuestionAudio).toHaveBeenCalledWith(21,asset,expect.objectContaining({expectedRevision:2}));expect(prepareQuestionAudio).not.toHaveBeenCalled();
  });
  it('only READY assets request an authorized short-lived preview and unsafe URLs are rejected',async()=>{
    vi.mocked(getPreparedAudio).mockResolvedValue({data:{...listing,current:[{...base,state:'READY',completedParts:2,durationMs:2000}]}});
    const w=render();await flushPromises();await button(w,'试听本题播报').trigger('click');await flushPromises();expect(w.find('audio').attributes('src')).toContain('https://');
    vi.mocked(getAudioPreview).mockResolvedValueOnce({data:{url:'javascript:alert(1)',expiresAt:'2026-10-04T08:05:00Z'}});
    await button(w,'试听本题播报').trigger('click');await flushPromises();expect(w.find('audio').exists()).toBe(false);expect(w.text()).toContain('试听地址无效');
  });
  it('polling new server revision signals a saved-draft refresh without replacing dirty local text',async()=>{
    vi.mocked(getPreparedAudio).mockResolvedValueOnce({data:{...listing,current:[base]}}).mockResolvedValue({data:{...listing,draftRevision:3,current:[{...base,state:'READY'}]}});
    const w=render();await flushPromises();await w.setProps({hasUnsavedChanges:true});await vi.advanceTimersByTimeAsync(3000);await flushPromises();expect(w.emitted('revision-changed')).toBeUndefined();
    expect(w.text()).toContain('服务端草稿已更新');expect(prepareQuestionAudio).not.toHaveBeenCalled();
  });
  it('a failed or cross-assessment read remains an error, never a fabricated ready asset',async()=>{
    vi.mocked(getPreparedAudio).mockRejectedValueOnce(new Error('读取失败'));const w=render();await flushPromises();expect(w.text()).toContain('读取失败');
    vi.mocked(getPreparedAudio).mockResolvedValueOnce({data:{...listing,assessmentId:22}});await button(w,'刷新播报状态').trigger('click');await flushPromises();expect(w.text()).toContain('播报状态无效');expect(w.find('audio').exists()).toBe(false);
  });
});
