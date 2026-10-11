import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import PreparedPreflightPage from '@/views/prepared/PreparedPreflightPage.vue';
import { getPreparedPreflightContext,type PreparedPreflightContext } from '@/api/preparedStudent';
import { runMicrophoneTrial } from '@/prepared/microphoneTrial';
import { requestPreparedDevice } from '@/prepared/deviceAccess';
import PreparedAttemptPanel from '@/views/prepared/PreparedAttemptPanel.vue';
import {ApiRequestError,ApiTransportError} from '@/utils/request';
vi.mock('@/api/preparedStudent', () => ({getPreparedPreflightContext: vi.fn()}));
vi.mock('@/prepared/microphoneTrial', () => ({runMicrophoneTrial: vi.fn()}));
vi.mock('@/prepared/deviceAccess', () => ({requestPreparedDevice: vi.fn()}));
const captureExit=vi.hoisted(()=>vi.fn());
vi.mock('@/views/prepared/PreparedAttemptPanel.vue', () => ({default:{name:'PreparedAttemptPanel',props:['context','consent','microphonePassed','identityPending'],emits:['capture-active'],methods:{stopForContextChange:captureExit},template:'<section aria-label="作答面板独立测试" />'}}));
let auth: {id: string; role: string}, route: {params: {assessmentId: string}};
vi.mock('@/stores/auth', () => ({useAuthStore: () => auth}));
vi.mock('vue-router', () => ({useRoute: () => route, useRouter: () => ({push: vi.fn()})}));
const context = (): {data:PreparedPreflightContext} => ({data: {studentId:12,deploymentId:'prepared-local',eligibility: {
  assessmentId:8,theme:'预检测试',questionCount:0,publicationReady:false,appointmentRequired:false,
  phase:'PAPER_NOT_PUBLISHED' as const,message:'试卷尚未发布',serverTime:'2026-10-04T20:00:00Z',appointmentStartsAt:null,latestFirstStartAt:null,
}}});
const button = (w: ReturnType<typeof mount>, text: string) => w.findAll('button').find(x=>x.text()===text)!;
const mounted: Array<ReturnType<typeof mount>> = [];
function render(){const w=mount(PreparedPreflightPage);mounted.push(w);return w;}
beforeEach(()=>{
  auth=reactive({id:'12',role:'STUDENT'});route=reactive({params:{assessmentId:'8'}});
  vi.mocked(getPreparedPreflightContext).mockReset().mockResolvedValue(context());
  vi.mocked(runMicrophoneTrial).mockReset();vi.mocked(requestPreparedDevice).mockReset();
  captureExit.mockReset();
  vi.stubGlobal('isSecureContext',true);vi.stubGlobal('indexedDB',{open:vi.fn()});
  vi.stubGlobal('MediaRecorder',{isTypeSupported:()=>true});vi.stubGlobal('AudioContext',function(){});
  Object.defineProperty(navigator,'mediaDevices',{configurable:true,value:{getUserMedia:vi.fn()}});
  Object.defineProperty(navigator,'locks',{configurable:true,value:{request:vi.fn()}});
  vi.spyOn(navigator,'userAgent','get').mockReturnValue('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0.0.0 Safari/537.36');
  Object.defineProperty(URL,'createObjectURL',{configurable:true,value:vi.fn(()=> 'blob:trial-preview')});
  Object.defineProperty(URL,'revokeObjectURL',{configurable:true,value:vi.fn()});
  vi.spyOn(HTMLMediaElement.prototype,'play').mockResolvedValue(undefined);vi.spyOn(HTMLMediaElement.prototype,'pause').mockImplementation(()=>{});
});
afterEach(()=>{for(const w of mounted.splice(0))w.unmount();vi.useRealTimers();vi.unstubAllGlobals();});
async function consent(w: ReturnType<typeof mount>){await w.find('[data-testid="recording-consent"]').setValue(true);}
async function formal(){
  const ready=context();ready.data.eligibility={...ready.data.eligibility,questionCount:3,publicationReady:true,phase:'READY_FOR_PREFLIGHT'};
  vi.mocked(getPreparedPreflightContext).mockResolvedValue({...ready,data:{...ready.data,workflowEnabled:true}});
  const w=render();await flushPromises();await consent(w);
  const panel=w.findComponent(PreparedAttemptPanel);panel.vm.$emit('capture-active',true);await flushPromises();
  return {w,panel};
}
describe('student preflight UI — controlled helpers, NOT physical device acceptance',()=>{
  it.each([
    ['unrelated local setting','prepared-ui-theme','local'],
    ['unrelated local command removal','prepared-other-command','local'],
    ['session-only marker','Admin-Token-Defense-System','session'],
    ['session-only clear',null,'session'],
  ] as const)('%s preserves the original panel, passed trial, consent and optional camera',async(_name,key,area)=>{
    vi.mocked(runMicrophoneTrial).mockResolvedValue({blob:new Blob(['controlled trial'],{type:'audio/webm'}),durationSeconds:6,signalDetected:true,peak:.2});
    const stop=vi.fn(),track=Object.assign(new EventTarget(),{stop});
    vi.mocked(requestPreparedDevice).mockResolvedValue({getTracks:()=>[track]} as never);
    const w=render();await flushPromises();await consent(w);
    await button(w,'开始麦克风试音').trigger('click');await flushPromises();await w.find('[data-testid="heard-confirmation"]').setValue(true);
    await button(w,'开启摄像头预览').trigger('click');await flushPromises();
    const video=w.get('video');Object.defineProperties(video.element,{videoWidth:{configurable:true,value:640},videoHeight:{configurable:true,value:480}});
    await video.trigger('loadeddata');await flushPromises();
    const original=w.findComponent(PreparedAttemptPanel).vm;original.$emit('capture-active',true);await flushPromises();
    const reads=vi.mocked(getPreparedPreflightContext).mock.calls.length;
    window.dispatchEvent(new StorageEvent('storage',{key,storageArea:area==='local'?window.localStorage:window.sessionStorage}));await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).vm).toBe(original);
    expect(w.findComponent(PreparedAttemptPanel).props('microphonePassed')).toBe(true);
    expect(w.find('[data-testid="recording-consent"]').element).toHaveProperty('checked',true);
    expect(w.get('audio').attributes('src')).toBe('blob:trial-preview');expect(URL.revokeObjectURL).not.toHaveBeenCalled();
    expect(w.text()).toContain('摄像头预览中，仅显示本机画面');expect(stop).not.toHaveBeenCalled();
    expect(getPreparedPreflightContext).toHaveBeenCalledTimes(reads);expect(runMicrophoneTrial).toHaveBeenCalledTimes(1);
  });
  it.each(['Admin-Token-Defense-System',null])('a local login marker change or clear (%s) still removes the authorized panel',async key=>{
    const {w}=await formal();window.dispatchEvent(new StorageEvent('storage',{key,storageArea:window.localStorage}));await flushPromises();
    expect(captureExit).toHaveBeenCalledTimes(1);expect(captureExit).toHaveBeenCalledWith('SESSION_CHANGED');
    expect(w.findComponent(PreparedAttemptPanel).exists()).toBe(false);
    expect(w.find('[data-testid="recording-consent"]').exists()).toBe(false);expect(w.text()).toContain('重新核验身份');
  });
  it.each([
    ()=>new ApiTransportError('connection unavailable','NETWORK'),
    ()=>new ApiTransportError('request timeout','TIMEOUT'),
    ()=>new ApiRequestError({status:502,code:502,message:'upstream unavailable'}),
    ()=>new ApiRequestError({status:503,code:500,message:'database unavailable'}),
    ()=>new ApiRequestError({status:504,code:504,message:'upstream timeout'}),
  ])('keeps the same original panel on a verified transient focus failure, closes new-start gates and recovers on fresh identity',async failure=>{
    const {w,panel}=await formal(),original=panel.vm;
    vi.mocked(getPreparedPreflightContext).mockRejectedValueOnce(failure());window.dispatchEvent(new Event('focus'));await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).vm).toBe(original);
    expect(w.find('[data-testid="recording-consent"]').element).toHaveProperty('checked',true);
    expect(w.text()).toContain('当前题仅在原时限内继续');expect(w.findComponent(PreparedAttemptPanel).props('identityPending')).toBe(true);
    expect(button(w,'开启摄像头预览').attributes('disabled')).toBeDefined();expect(runMicrophoneTrial).not.toHaveBeenCalled();
    await button(w,'重新核验服务端身份').trigger('click');await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).vm).toBe(original);expect(w.findComponent(PreparedAttemptPanel).props('identityPending')).toBe(false);
    expect(w.text()).not.toContain('暂时无法核验服务端身份');
    expect(captureExit).not.toHaveBeenCalled();
  });
  it('keeps a bounded identity-read timeout separate from invalid identity while preserving the original capture',async()=>{
    const {w,panel}=await formal(),original=panel.vm;vi.useFakeTimers();
    vi.mocked(getPreparedPreflightContext).mockImplementationOnce(()=>new Promise(()=>{}));
    window.dispatchEvent(new Event('focus'));await flushPromises();await vi.advanceTimersByTimeAsync(12000);await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).vm).toBe(original);expect(w.findComponent(PreparedAttemptPanel).props('identityPending')).toBe(true);
  });
  it('a bounded timeout remains a timeout when the transport rejects cancellation synchronously during abort',async()=>{
    const {w,panel}=await formal(),original=panel.vm;vi.useFakeTimers();
    vi.mocked(getPreparedPreflightContext).mockImplementationOnce((_id,signal)=>new Promise((_resolve,reject)=>signal!.addEventListener('abort',()=>reject(new ApiTransportError('canceled','CANCELLED')),{once:true})));
    window.dispatchEvent(new Event('focus'));await flushPromises();await vi.advanceTimersByTimeAsync(12000);await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).vm).toBe(original);expect(w.findComponent(PreparedAttemptPanel).props('identityPending')).toBe(true);
  });
  it('does not let a cancelled older identity read erase the newer verification or interrupt the current panel',async()=>{
    const {w,panel}=await formal(),original=panel.vm;
    vi.mocked(getPreparedPreflightContext).mockImplementationOnce((_id,signal)=>new Promise((_resolve,reject)=>signal!.addEventListener('abort',()=>reject(new ApiTransportError('canceled','CANCELLED')),{once:true})));
    window.dispatchEvent(new Event('focus'));await flushPromises();window.dispatchEvent(new Event('focus'));await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).vm).toBe(original);expect(w.text()).not.toContain('身份或考核核验失败');
    expect(w.findComponent(PreparedAttemptPanel).props('identityPending')).toBe(false);
  });
  it.each([
    ()=>new ApiRequestError({status:401,code:401,message:'session expired'}),
    ()=>new ApiRequestError({status:403,code:403,message:'not assigned'}),
    ()=>new ApiRequestError({status:503,code:401,message:'identity rejected'}),
    ()=>new ApiRequestError({status:503,code:403,message:'permission rejected'}),
    ()=>new ApiRequestError({status:500,code:500,message:'unclassified server failure'}),
    ()=>new ApiTransportError('unknown setup','UNKNOWN'),
    ()=>new ApiTransportError('current cancellation','CANCELLED'),
    ()=>new Error('Network Error'),
  ])('still fails closed for a definitive identity rejection, unknown error or current cancellation',async failure=>{
    const {w}=await formal();vi.mocked(getPreparedPreflightContext).mockRejectedValueOnce(failure());
    window.dispatchEvent(new Event('focus'));await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).exists()).toBe(false);expect(w.text()).toContain('身份或考核核验失败');
    expect(captureExit).toHaveBeenCalledWith('SESSION_CHANGED');
  });
  it('never preserves an original context for a different server owner or deployment',async()=>{
    const {w}=await formal();vi.mocked(getPreparedPreflightContext).mockResolvedValueOnce({...context(),data:{...context().data,deploymentId:'prepared-other'}});
    window.dispatchEvent(new Event('focus'));await flushPromises();expect(w.findComponent(PreparedAttemptPanel).exists()).toBe(false);
  });
  it('retains a passed original microphone check during transient verification and still revokes it on account change',async()=>{
    vi.mocked(runMicrophoneTrial).mockResolvedValue({blob:new Blob(['controlled trial'],{type:'audio/webm'}),durationSeconds:6,signalDetected:true,peak:.2});
    const w=render();await flushPromises();await consent(w);await button(w,'开始麦克风试音').trigger('click');await flushPromises();
    await w.find('[data-testid="heard-confirmation"]').setValue(true);const original=w.findComponent(PreparedAttemptPanel).vm;
    w.findComponent(PreparedAttemptPanel).vm.$emit('capture-active',true);await flushPromises();
    vi.mocked(getPreparedPreflightContext).mockRejectedValueOnce(new ApiTransportError('offline','NETWORK'));
    window.dispatchEvent(new Event('focus'));await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).vm).toBe(original);expect(w.findComponent(PreparedAttemptPanel).props('microphonePassed')).toBe(true);
    expect(w.get('audio').attributes('src')).toBe('blob:trial-preview');expect(URL.revokeObjectURL).not.toHaveBeenCalled();
    auth.id='99';await flushPromises();expect(w.findComponent(PreparedAttemptPanel).exists()).toBe(false);expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:trial-preview');
  });
  it('a late invalid response from a superseded read cannot erase the newer verified original owner',async()=>{
    const {w,panel}=await formal(),original=panel.vm;let release!:(value:any)=>void;
    vi.mocked(getPreparedPreflightContext).mockImplementationOnce(()=>new Promise(resolve=>{release=resolve;}));
    window.dispatchEvent(new Event('focus'));await flushPromises();window.dispatchEvent(new Event('focus'));await flushPromises();
    release({...context(),data:{...context().data,studentId:99}});await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).vm).toBe(original);expect(w.findComponent(PreparedAttemptPanel).props('identityPending')).toBe(false);
  });
  it('a transient camera identity check cannot request a new device or leave its connecting state hanging',async()=>{
    const {w,panel}=await formal(),original=panel.vm;vi.mocked(getPreparedPreflightContext).mockRejectedValueOnce(new ApiTransportError('offline','NETWORK'));
    await button(w,'开启摄像头预览').trigger('click');await flushPromises();
    expect(w.findComponent(PreparedAttemptPanel).vm).toBe(original);expect(requestPreparedDevice).not.toHaveBeenCalled();
    expect(w.text()).not.toContain('正在等待摄像头授权或画面');expect(w.find('video').exists()).toBe(false);
  });
  it('reads a fresh assigned identity, makes no recording on mount and requires consent',async()=>{
    const w=render();await flushPromises();expect(getPreparedPreflightContext).toHaveBeenCalledWith(8,expect.any(AbortSignal));
    expect(w.text()).toContain('试卷尚未发布');expect(w.text()).toContain('不是正式答题');
    expect(button(w,'开始麦克风试音').attributes('disabled')).toBeDefined();expect(runMicrophoneTrial).not.toHaveBeenCalled();
    await consent(w);expect(button(w,'开始麦克风试音').attributes('disabled')).toBeUndefined();expect(w.text()).not.toContain('考试已开始');
  });
  it('requires saved and decoded trial plus student playback confirmation, never grants formal start',async()=>{
    vi.mocked(runMicrophoneTrial).mockImplementation(async(_id,_signal,progress)=>{progress({phase:'RECORDING',secondsRemaining:6,savedChunks:1});return {blob:new Blob(['controlled trial'],{type:'audio/webm'}),durationSeconds:6,signalDetected:true,peak:.2};});
    const w=render();await flushPromises();await consent(w);await button(w,'开始麦克风试音').trigger('click');await flushPromises();
    expect(w.find('audio').attributes('src')).toBe('blob:trial-preview');expect(w.text()).toContain('检测到声音');
    expect(w.text()).not.toContain('麦克风与本机保存检查通过');
    await w.find('[data-testid="heard-confirmation"]').setValue(true);expect(w.text()).toContain('麦克风与本机保存检查通过');expect(w.text()).toContain('正式开考尚未开放');
    w.unmount();expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:trial-preview');
  });
  it('silent media and failed trials cannot be reported as passed',async()=>{
    vi.mocked(runMicrophoneTrial).mockResolvedValue({blob:new Blob(['silence'],{type:'audio/webm'}),durationSeconds:6,signalDetected:false,peak:0});
    const w=render();await flushPromises();await consent(w);await button(w,'开始麦克风试音').trigger('click');await flushPromises();
    expect(w.text()).toContain('未检测到足够声音');expect(w.find('[data-testid="heard-confirmation"]').exists()).toBe(false);
    vi.mocked(runMicrophoneTrial).mockRejectedValue(Object.assign(new Error('secret'),{code:'STORAGE_FAILED'}));
    await button(w,'开始麦克风试音').trigger('click');await flushPromises();expect(w.text()).toContain('本机保存检查失败');expect(w.text()).not.toContain('secret');expect(w.text()).not.toContain('麦克风与本机保存检查通过');
  });
  it('camera rejection does not reset a passed microphone trial and states preview-only privacy',async()=>{
    vi.mocked(runMicrophoneTrial).mockResolvedValue({blob:new Blob(['trial'],{type:'audio/webm'}),durationSeconds:6,signalDetected:true,peak:.1});
    vi.mocked(requestPreparedDevice).mockRejectedValue(Object.assign(new Error('secret camera id'),{code:'PERMISSION_DENIED'}));
    const w=render();await flushPromises();await consent(w);await button(w,'开始麦克风试音').trigger('click');await flushPromises();await w.find('[data-testid="heard-confirmation"]').setValue(true);
    await button(w,'开启摄像头预览').trigger('click');await flushPromises();expect(w.text()).toContain('摄像头权限被拒绝');expect(w.text()).toContain('麦克风与本机保存检查通过');expect(w.text()).toContain('不上传、不分析');
  });
  it('camera requires actual visible media dimensions and closes on page exit',async()=>{
    const stop=vi.fn();vi.mocked(requestPreparedDevice).mockResolvedValue({getTracks:()=>[{stop}]} as never);
    const w=render();await flushPromises();await consent(w);await button(w,'开启摄像头预览').trigger('click');await flushPromises();
    const video=w.find('video');Object.defineProperty(video.element,'videoWidth',{configurable:true,value:640});await video.trigger('loadeddata');await flushPromises();
    expect(w.text()).not.toContain('摄像头预览中');
    Object.defineProperty(video.element,'videoHeight',{configurable:true,value:480});await video.trigger('loadeddata');await flushPromises();
    expect(w.text()).toContain('摄像头预览中');window.dispatchEvent(new Event('pagehide'));await flushPromises();expect(stop).toHaveBeenCalled();expect(w.text()).not.toContain('摄像头预览中');
    expect(captureExit).toHaveBeenCalledWith('PAGE_REFRESH');
  });
  it('explicitly signals page exit before returning to the list, rather than an identity stop',async()=>{
    const {w}=await formal();await button(w,'返回考核列表').trigger('click');await flushPromises();
    expect(captureExit).toHaveBeenCalledTimes(1);expect(captureExit).toHaveBeenCalledWith('PAGE_REFRESH');
    expect(w.findComponent(PreparedAttemptPanel).exists()).toBe(false);
  });
  it('cancels a pending trial and discards late helper results after account switch',async()=>{
    let resolve!: (value:any)=>void;vi.mocked(runMicrophoneTrial).mockImplementation(()=>new Promise(r=>{resolve=r;}));
    const w=render();await flushPromises();await consent(w);await button(w,'开始麦克风试音').trigger('click');await flushPromises();
    const signal=vi.mocked(runMicrophoneTrial).mock.calls[0][1];auth.id='99';await flushPromises();expect(signal.aborted).toBe(true);
    resolve({blob:new Blob(['old owner']),durationSeconds:6,signalDetected:true,peak:.1});await flushPromises();
    expect(w.text()).toContain('身份已变化');expect(w.find('audio').exists()).toBe(false);expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
  it('a camera track ending after preview removes the live indicator and releases the stream',async()=>{
    const stop=vi.fn();const track=Object.assign(new EventTarget(),{stop});vi.mocked(requestPreparedDevice).mockResolvedValue({getTracks:()=>[track]} as never);
    const w=render();await flushPromises();await consent(w);await button(w,'开启摄像头预览').trigger('click');await flushPromises();
    const video=w.find('video');Object.defineProperties(video.element,{videoWidth:{configurable:true,value:640},videoHeight:{configurable:true,value:480}});
    await video.trigger('loadeddata');await flushPromises();expect(w.text()).toContain('摄像头预览中');track.dispatchEvent(new Event('ended'));await flushPromises();
    expect(w.text()).toContain('摄像头预览中断');expect(w.text()).not.toContain('摄像头预览中，仅显示本机画面');expect(stop).toHaveBeenCalled();
  });
  it('rejects unassigned, mismatched and invalid contexts instead of using local defaults',async()=>{
    vi.mocked(getPreparedPreflightContext).mockResolvedValue({...context(),data:{...context().data,studentId:99}});
    const w=render();await flushPromises();expect(w.text()).toContain('身份或考核核验失败');expect(w.find('[data-testid="recording-consent"]').exists()).toBe(false);
    route.params.assessmentId='0';await flushPromises();expect(getPreparedPreflightContext).toHaveBeenCalledTimes(1);expect(w.text()).toContain('考核标识无效');
  });
  it('unsupported browser has an actionable warning and cannot request devices',async()=>{
    vi.stubGlobal('isSecureContext',false);const w=render();await flushPromises();expect(w.text()).toContain('Windows');expect(w.text()).toContain('Chrome');await consent(w);
    expect(button(w,'开始麦克风试音').attributes('disabled')).toBeDefined();expect(requestPreparedDevice).not.toHaveBeenCalled();
  });
  it('missing native writer protection cannot request a microphone or claim trial success',async()=>{
    Object.defineProperty(navigator,'locks',{configurable:true,value:undefined});
    const w=render();await flushPromises();await consent(w);
    expect(button(w,'开始麦克风试音').attributes('disabled')).toBeDefined();expect(w.text()).toContain('本地录音独占保护');
    expect(requestPreparedDevice).not.toHaveBeenCalled();expect(runMicrophoneTrial).not.toHaveBeenCalled();
  });
  it('bounded context timeout and reload cannot publish a late identity',async()=>{
    vi.useFakeTimers();let resolve!:(value:any)=>void;vi.mocked(getPreparedPreflightContext).mockImplementation(()=>new Promise(r=>{resolve=r;}));
    const w=render();await flushPromises();await vi.advanceTimersByTimeAsync(12000);expect(w.text()).toContain('身份核验超时');
    resolve(context());await flushPromises();expect(w.find('[data-testid="recording-consent"]').exists()).toBe(false);
    vi.mocked(getPreparedPreflightContext).mockResolvedValue(context());await button(w,'重新核验身份').trigger('click');await flushPromises();expect(w.find('[data-testid="recording-consent"]').exists()).toBe(true);
  });
});
