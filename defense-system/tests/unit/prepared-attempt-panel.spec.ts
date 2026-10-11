import {mount,flushPromises} from '@vue/test-utils';
import {reactive} from 'vue';
import {IDBFactory} from 'fake-indexeddb';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import PreparedAttemptPanel from '@/views/prepared/PreparedAttemptPanel.vue';
import * as api from '@/api/preparedAttempt';
import {getPreparedPreflightContext} from '@/api/preparedStudent';
import {requestPreparedDevice} from '@/prepared/deviceAccess';
import {startAuthorizedAnswerCapture} from '@/prepared/authorizedCapture';
import {PreparedAnswerCapture,type RecorderEnvironment} from '@/prepared/answerCapture';
import {PreparedJourneyJournal} from '@/prepared/journeyJournal';
import {PreparedAudioJournal} from '@/prepared/localAudioJournal';
import {PreparedUploadJournal} from '@/prepared/uploadJournal';
import {PreparedBackgroundUpload} from '@/prepared/backgroundUpload';
import {encodeAnswerEndManifest,type AnswerEndCommand} from '@/prepared/answerEndManifest';
const nodeCryptoModule='node:crypto';
vi.mock('@/api/preparedAttempt',()=>({getCurrentPreparedAttempt:vi.fn(),getCurrentPreparedQuestion:vi.fn(),startPreparedAttempt:vi.fn(),startPreparedQuestion:vi.fn(),endPreparedAnswer:vi.fn(),getPreparedQuestionAudio:vi.fn()}));
vi.mock('@/api/preparedStudent',()=>({getPreparedPreflightContext:vi.fn()}));
vi.mock('@/prepared/deviceAccess',()=>({requestPreparedDevice:vi.fn()}));
vi.mock('@/prepared/authorizedCapture',()=>({startAuthorizedAnswerCapture:vi.fn()}));
let auth:{id:string;role:string};vi.mock('@/stores/auth',()=>({useAuthStore:()=>auth}));
const uploadMocks=vi.hoisted(()=>({authority:vi.fn(),prepare:vi.fn(),status:vi.fn(),upload:vi.fn(),window:vi.fn(),mediaStatus:vi.fn(),queueMedia:vi.fn(),submissionStatus:vi.fn(),submitAttempt:vi.fn()}));
vi.mock('@/api/preparedUpload',async original=>{const actual=await original<typeof import('@/api/preparedUpload')>();return {...actual,PreparedUploadTransport:class{authority=uploadMocks.authority;prepare=uploadMocks.prepare;status=uploadMocks.status;upload=uploadMocks.upload;window=uploadMocks.window;mediaStatus=uploadMocks.mediaStatus;queueMedia=uploadMocks.queueMedia;submissionStatus=uploadMocks.submissionStatus;submitAttempt=uploadMocks.submitAttempt;}};});
const context=()=>({studentId:12,deploymentId:'prepared-local',workflowEnabled:true,eligibility:{assessmentId:8,theme:'固定题面试',questionCount:3,publicationReady:true,appointmentRequired:false,phase:'READY_FOR_PREFLIGHT' as const,message:'可以预检',serverTime:new Date().toISOString(),appointmentStartsAt:null,latestFirstStartAt:null}});
const attempt=()=>({id:'00000000-0000-4000-8000-000000000001',assessmentId:8,recordId:21,attemptNo:1,paperVersionId:'00000000-0000-4000-8000-000000000002',state:'IN_PROGRESS' as const,revision:0,startedAt:new Date().toISOString(),answeringEndedAt:null,supplementalUploadDeadline:null});
const grant=()=>({...attempt(),attemptId:attempt().id,answerId:'00000000-0000-4000-8000-000000000003',questionId:'00000000-0000-4000-8000-000000000004',ordinal:1,body:'请介绍学习经历',answerSeconds:120,state:'RECORDING' as const,startedAt:new Date().toISOString(),answerDeadline:new Date(Date.now()+120000).toISOString(),serverTime:new Date().toISOString(),mayRecord:true});
let factory:IDBFactory,stop:ReturnType<typeof vi.fn>;const mounted:Array<ReturnType<typeof mount>>=[];
function render(props={context:context(),consent:true,microphonePassed:true}){const w=mount(PreparedAttemptPanel,{props});mounted.push(w);return w;}
const button=(w:ReturnType<typeof mount>,text:string)=>w.findAll('button').find(b=>b.text()===text)!;
async function ready(w:ReturnType<typeof mount>){await flushPromises();await vi.waitFor(()=>expect(button(w,'重新核验原作答').attributes('disabled')).toBeUndefined());}
async function originals(){const j=new PreparedJourneyJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},8,factory);const s=await j.prepareStart();await j.bindAttempt({attemptId:attempt().id,paperVersionId:attempt().paperVersionId});const q=await j.prepareQuestion(1);j.close();return {s,q};}
beforeEach(async()=>{
  const {webcrypto}=await import(nodeCryptoModule);
  const bufferModule='node:buffer';const {Blob}=await import(bufferModule);vi.stubGlobal('Blob',Blob);
  vi.stubGlobal('isSecureContext',true);
  factory=new IDBFactory();vi.stubGlobal('indexedDB',factory);vi.stubGlobal('crypto',webcrypto);auth=reactive({id:'12',role:'STUDENT'});
  Object.values(uploadMocks).forEach(m=>m.mockReset());uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:false});
  uploadMocks.window.mockImplementation(async(a:any)=>{const j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);const original=await j.recoverAnswerEnd(a);j.close();return {studentId:12,assessmentId:8,attemptId:a.attemptId,answerId:a.answerId,paperVersionId:attempt().paperVersionId,manifestSha256:(await matchingReceipt(original)).manifestSha256,answerDeadline:grant().answerDeadline,answeringEndedAt:null,automaticDeadline:null,serverNow:new Date().toISOString(),phase:'ANSWERING',mayUploadOriginal:true,approvalRevision:0,approvedAt:null,uploadDeadline:null};});
  uploadMocks.mediaStatus.mockImplementation(async(a:any)=>({answerId:a.answerId,manifestSha256:null,jobId:null,phase:'NOT_QUEUED',serverSaved:false,revision:0,durationMs:null,verifiedAt:null}));
  uploadMocks.submissionStatus.mockResolvedValue({attemptId:attempt().id,paperVersionId:attempt().paperVersionId,revision:3,phase:'WAITING_ANSWERS',submitted:false,pending:[{ordinal:1,answerId:grant().answerId,phase:'UPLOAD_PENDING'}],receipt:null});
  vi.mocked(getPreparedPreflightContext).mockReset().mockResolvedValue({data:context()});
  vi.mocked(api.getCurrentPreparedAttempt).mockReset().mockResolvedValue({data:null});vi.mocked(api.getCurrentPreparedQuestion).mockReset().mockResolvedValue({data:null});
  vi.mocked(api.startPreparedAttempt).mockReset().mockResolvedValue({data:attempt()});
  vi.mocked(api.startPreparedQuestion).mockReset().mockImplementation(async()=>{const value=grant();value.answerDeadline=new Date(Date.parse(value.startedAt)+120000).toISOString();return {data:value};});
  vi.mocked(api.endPreparedAnswer).mockReset();vi.mocked(startAuthorizedAnswerCapture).mockReset();vi.mocked(requestPreparedDevice).mockReset();
  stop=vi.fn();vi.mocked(requestPreparedDevice).mockResolvedValue({getTracks:()=>[{stop}]} as never);
  vi.spyOn(navigator,'onLine','get').mockReturnValue(true);
  Object.defineProperty(navigator,'locks',{configurable:true,value:{request:async(_key:string,_options:unknown,callback:(lock:object)=>Promise<void>)=>callback({})}});
});

// Attempt-pointer recovery with actual journal modules but controlled HTTP replies.
// No real retake is authorized, no physical microphone or cloud service is used.
describe('background transport stays bound to the original verified attempt',()=>{
  const identity=()=>({origin:location.origin,studentId:12,deploymentId:'prepared-local'});
  const reply=(i:any)=>({bundleId:i.bundleId,answerId:i.answer.answerId,commandSha256:i.commandSha256,byteSize:i.command.byteSize,sha256:i.command.sha256,confirmed:true,
    receipts:i.command.chunks.map((c:any)=>({...c,answerId:i.answer.answerId,segmentId:i.command.segmentId,confirmed:true,confirmedAt:new Date().toISOString()}))});
  async function originalUpload(){
    const {q}=await originals(),scope=await stored(),audio=new PreparedAudioJournal(identity(),factory),journal=new PreparedUploadJournal(identity(),8,factory);
    const answer={...scope,captureClientId:q.captureClientId,ordinal:1};await journal.track(answer);
    const end=await audio.prepareAnswerEnd(scope,{captureClientId:q.captureClientId,idempotencyKey:q.endKey});
    audio.close();journal.close();return {answer,end};
  }
  it('no server current attempt means no sender, history scan or historical upload on reconnect',async()=>{
    const original=await originalUpload(),run=vi.spyOn(PreparedBackgroundUpload.prototype,'run');
    uploadMocks.authority.mockResolvedValue({...identity(),workflowEnabled:true});
    const w=render();await ready(w);window.dispatchEvent(new Event('online'));await flushPromises();
    expect(run).not.toHaveBeenCalled();expect(uploadMocks.authority).not.toHaveBeenCalled();
    expect(uploadMocks.prepare).not.toHaveBeenCalled();expect(uploadMocks.upload).not.toHaveBeenCalled();expect(uploadMocks.mediaStatus).not.toHaveBeenCalled();
    expect(w.find('[data-testid="upload-status"]').exists()).toBe(false);
    const audio=new PreparedAudioJournal(identity(),factory);try{expect(await audio.recoverAnswerEnd(original.answer)).toEqual(original.end);}finally{audio.close();}
    expect(api.startPreparedAttempt).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
  });
  it.each(['missing-current','different-current'] as const)('a %s pointer aborts old IO and cannot display its late byte receipt',async change=>{
    const original=await originalUpload();uploadMocks.authority.mockResolvedValue({...identity(),workflowEnabled:true});
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'UPLOAD_PENDING',mayRecord:false}});
    let done!:(r:any)=>void;uploadMocks.prepare.mockImplementationOnce(()=>new Promise(resolve=>{done=resolve;}));
    const w=render();await ready(w);await vi.waitFor(()=>expect(uploadMocks.prepare).toHaveBeenCalledTimes(1));
    const [command,signal]=uploadMocks.prepare.mock.calls[0];
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:change==='missing-current'?null:{...attempt(),id:crypto.randomUUID(),attemptNo:2}});
    await button(w,'重新核验原作答').trigger('click');await ready(w);expect(signal.aborted).toBe(true);
    done(reply(command));await flushPromises();window.dispatchEvent(new Event('online'));await flushPromises();
    expect(w.find('[data-testid="upload-status"]').exists()).toBe(false);expect(uploadMocks.prepare).toHaveBeenCalledTimes(1);
    expect(uploadMocks.upload).not.toHaveBeenCalled();expect(uploadMocks.mediaStatus).not.toHaveBeenCalled();
    const audio=new PreparedAudioJournal(identity(),factory),journal=new PreparedUploadJournal(identity(),8,factory);
    try{expect(await audio.recoverAnswerEnd(original.answer)).toEqual(original.end);expect(await journal.intents(original.answer)).toEqual([command]);}finally{audio.close();journal.close();}
    expect(api.startPreparedAttempt).not.toHaveBeenCalled();expect(api.startPreparedQuestion).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
    if(change==='different-current')expect(w.text()).toContain('原作答操作未完成');
  });
  it('a late media SAVED reply cannot survive removal of its current-attempt pointer',async()=>{
    const original=await originalUpload();uploadMocks.authority.mockResolvedValue({...identity(),workflowEnabled:true});uploadMocks.prepare.mockImplementation(async i=>reply(i));
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'UPLOAD_PENDING',mayRecord:false}});
    let done!:(r:any)=>void;uploadMocks.mediaStatus.mockImplementationOnce(()=>new Promise(resolve=>{done=resolve;}));
    const w=render();await ready(w);await vi.waitFor(()=>expect(uploadMocks.mediaStatus).toHaveBeenCalledTimes(1));const [answer,signal]=uploadMocks.mediaStatus.mock.calls[0];
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:null});await button(w,'重新核验原作答').trigger('click');await ready(w);expect(signal.aborted).toBe(true);
    done({answerId:answer.answerId,manifestSha256:(await matchingReceipt(original.end)).manifestSha256,jobId:crypto.randomUUID(),phase:'SAVED',serverSaved:true,revision:2,durationMs:1000,verifiedAt:new Date().toISOString()});await flushPromises();
    expect(w.find('[data-testid="media-status"]').exists()).toBe(false);expect(w.find('[data-testid="upload-status"]').exists()).toBe(false);expect(uploadMocks.queueMedia).not.toHaveBeenCalled();
    const audio=new PreparedAudioJournal(identity(),factory);try{expect(await audio.recoverAnswerEnd(original.answer)).toEqual(original.end);}finally{audio.close();}
  });
});
function endReceipt(patch:Partial<api.PreparedAnswerEndReceipt>={}):api.PreparedAnswerEndReceipt{return {
  answerId:grant().answerId,manifestSha256:'a'.repeat(64),declarationState:'UPLOAD_PENDING',revision:1,effectiveEndedAt:new Date().toISOString(),acceptedAt:new Date().toISOString(),
  reviewReasons:[],serverSaved:false,attemptState:'IN_PROGRESS',attemptRevision:1,answeringEndedAt:null,supplementalUploadDeadline:null,...patch};}
async function matchingReceipt(command:AnswerEndCommand,patch:Partial<api.PreparedAnswerEndReceipt>={}){
  const manifestSha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(encodeAnswerEndManifest(command.manifest)))),b=>b.toString(16).padStart(2,'0')).join('');return endReceipt({manifestSha256,...patch});
}
async function stored(state:'OPEN'|'SEALED'|'INTERRUPTED'='SEALED'){
  const audio=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory),scope={assessmentId:8,attemptId:attempt().id,answerId:grant().answerId},id=crypto.randomUUID();
  await audio.begin(scope,{id,ordinal:1,mimeType:'audio/webm',startedAt:Date.now()});await audio.append(scope,id,0,new Blob(['controlled original bytes'],{type:'audio/webm'}));
  if(state!=='OPEN'){const endedAt=Date.now();await audio.prepareTerminal(scope,id,{expectedChunks:1,endedAt,reason:state==='INTERRUPTED'?'DEVICE_ENDED':null});
    if(state==='SEALED')await audio.seal(scope,id,1,endedAt);else await audio.interrupt(scope,id,endedAt,'DEVICE_ENDED');}audio.close();return scope;
}
function controlledCapture(failedStop=false){
  let last:any;
  vi.mocked(startAuthorizedAnswerCapture).mockImplementation(async input=>{
    const id=crypto.randomUUID(),startedAt=Date.now(),listeners=new Set<(v:any)=>void>();let phase='RECORDING',endedAt:number|null=null;
    await input.journal.begin(input.scope,{id,ordinal:input.ordinal,mimeType:'audio/webm',startedAt});await input.journal.append(input.scope,id,0,new Blob(['controlled prefix'],{type:'audio/webm'}));
    const value=()=>({phase,segmentId:id,startedAt,endedAt,reason:null,savedChunks:1,savedBytes:17,queuedChunks:0,queuedBytes:0});
    const notify=()=>listeners.forEach(l=>l(value()));
    const seal=async()=>{endedAt=Date.now();await input.journal.prepareTerminal(input.scope,id,{expectedChunks:1,endedAt,reason:null});await input.journal.seal(input.scope,id,1,endedAt);phase='SEALED';notify();return value();};
    last={snapshot:value,subscribe:(listener:(v:any)=>void)=>{listeners.add(listener);listener(value());return ()=>listeners.delete(listener);},
      stop:vi.fn(async()=>{input.stream.getTracks().forEach(t=>t.stop());if(failedStop){phase='LOCAL_SAVE_FAILED';notify();return value();}return seal();}),
      retrySave:vi.fn(seal),interrupt:vi.fn(async()=>{input.stream.getTracks().forEach(t=>t.stop());phase='INTERRUPTED';notify();return value();})};
    return last;
  });return ()=>last;
}
async function active(w:ReturnType<typeof mount>){await ready(w);await button(w,'开考并开始第一题').trigger('click');await vi.waitFor(()=>expect(w.text()).toContain('正在录音，本机持续保存'));await vi.waitFor(()=>expect(button(w,'结束本题并保存').attributes('disabled')).toBeUndefined());}
afterEach(()=>{mounted.splice(0).forEach(w=>w.unmount());vi.unstubAllGlobals();});
describe('student original journey UI — controlled transport and media, not real full-flow acceptance',()=>{
  it('restore overlaps independent read-only requests without reading original journals before verified identity',async()=>{
    const before=await originals();await stored('INTERRUPTED');
    let verifyIdentity!:(value:{data:ReturnType<typeof context>})=>void;
    vi.mocked(getPreparedPreflightContext).mockImplementationOnce(()=>new Promise(resolve=>{verifyIdentity=resolve;}));
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});
    vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:grant()});
    const open=vi.spyOn(factory,'open'),w=render();await flushPromises();
    // These are separately server-authorized GETs. Their network waits must not
    // accumulate, but an early attempt response is NOT local-read authority.
    expect(getPreparedPreflightContext).toHaveBeenCalledTimes(1);
    expect(api.getCurrentPreparedAttempt).toHaveBeenCalledTimes(1);
    expect(open).not.toHaveBeenCalled();expect(api.getCurrentPreparedQuestion).not.toHaveBeenCalled();
    expect(w.find('[data-testid="authorized-question"]').exists()).toBe(false);
    verifyIdentity({data:context()});await ready(w);
    expect(w.get('[data-testid="authorized-question"]').text()).toBe(grant().body);
    expect(await originals()).toEqual(before);expect(requestPreparedDevice).not.toHaveBeenCalled();
    expect(api.startPreparedAttempt).not.toHaveBeenCalled();expect(api.startPreparedQuestion).not.toHaveBeenCalled();
  });
  it.each(['rejected','different-student','unmounted','local-owner-changed'] as const)(
    'restore ignores a late read-only attempt after %s identity validation',async failure=>{
      await originals();await stored('INTERRUPTED');
      let verifyIdentity!:(value:{data:ReturnType<typeof context>})=>void,rejectIdentity!:(error:Error)=>void,
        resolveAttempt!:(value:{data:ReturnType<typeof attempt>})=>void;
      vi.mocked(getPreparedPreflightContext).mockImplementationOnce(()=>new Promise((resolve,reject)=>{verifyIdentity=resolve;rejectIdentity=reject;}));
      vi.mocked(api.getCurrentPreparedAttempt).mockImplementationOnce(()=>new Promise(resolve=>{resolveAttempt=resolve;}));
      const open=vi.spyOn(factory,'open'),w=render();await flushPromises();
      expect(api.getCurrentPreparedAttempt).toHaveBeenCalledTimes(1);
      const signal=vi.mocked(api.getCurrentPreparedAttempt).mock.calls[0][1]!;
      if(failure==='unmounted')w.unmount();
      if(failure==='local-owner-changed'){auth.id='13';await flushPromises();}
      if(failure==='rejected')rejectIdentity(new Error('identity unavailable'));
      else verifyIdentity({data:{...context(),studentId:failure==='different-student'?13:12}});
      await flushPromises();expect(signal.aborted).toBe(true);
      resolveAttempt({data:attempt()});await flushPromises();
      expect(open).not.toHaveBeenCalled();expect(api.getCurrentPreparedQuestion).not.toHaveBeenCalled();
      expect(requestPreparedDevice).not.toHaveBeenCalled();expect(api.startPreparedAttempt).not.toHaveBeenCalled();
      expect(api.startPreparedQuestion).not.toHaveBeenCalled();expect(api.endPreparedAnswer).not.toHaveBeenCalled();
    });
  it.each(['Admin-Token-Defense-System',null])('the outer page signals the exact login stop before tearing down the panel (%s)',async key=>{
    let w:ReturnType<typeof render>;
    const parentExit=()=>{w.vm.stopForContextChange('SESSION_CHANGED');w.unmount();};
    // The preflight parent already owns its listener before the async context mounts this child.
    // A native callback checkpoint can flush that parent's render before a later child listener.
    window.addEventListener('storage',parentExit);
    try{
      const local=controlledCapture();w=render();await active(w);
      expect(typeof w.vm.stopForContextChange).toBe('function');
      window.dispatchEvent(new StorageEvent('storage',{key,storageArea:window.localStorage}));await flushPromises();
      expect(local().interrupt).toHaveBeenCalledTimes(1);expect(local().interrupt).toHaveBeenCalledWith('SESSION_CHANGED');
      expect(stop).toHaveBeenCalled();expect(api.endPreparedAnswer).not.toHaveBeenCalled();
    }finally{window.removeEventListener('storage',parentExit);}
  });
  it.each([
    ['unrelated local setting','prepared-ui-theme','local'],
    ['unrelated local command removal','prepared-other-command','local'],
    ['session-only marker','Admin-Token-Defense-System','session'],
    ['session-only clear',null,'session'],
  ] as const)('%s cannot interrupt native capture ownership or discard the original question',async(_name,key,area)=>{
    const local=controlledCapture(),w=render();await active(w);
    const question=w.get('[data-testid="authorized-question"]').text(),original=await originals();
    const scope={assessmentId:8,attemptId:attempt().id,answerId:grant().answerId};
    const journal=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    const before=await journal.read(scope,local().snapshot().segmentId);
    window.dispatchEvent(new StorageEvent('storage',{key,storageArea:area==='local'?window.localStorage:window.sessionStorage}));
    await flushPromises();
    expect(local().interrupt).not.toHaveBeenCalled();expect(stop).not.toHaveBeenCalled();
    expect(w.get('[data-testid="authorized-question"]').text()).toBe(question);
    expect(w.get('[data-testid="capture-status"]').text()).toContain('正在录音');
    expect(await journal.read(scope,before.segment.id)).toEqual(before);journal.close();
    expect(await originals()).toEqual(original);expect(api.startPreparedAttempt).toHaveBeenCalledTimes(1);
    expect(api.startPreparedQuestion).toHaveBeenCalledTimes(1);expect(api.endPreparedAnswer).not.toHaveBeenCalled();
  });
  it.each(['Admin-Token-Defense-System',null])('a local login marker change or clear (%s) still interrupts without deleting the original bytes',async key=>{
    const local=controlledCapture(),w=render();await active(w);
    const scope={assessmentId:8,attemptId:attempt().id,answerId:grant().answerId};
    const journal=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    const before=await journal.read(scope,local().snapshot().segmentId);
    window.dispatchEvent(new StorageEvent('storage',{key,storageArea:window.localStorage}));await flushPromises();
    expect(local().interrupt).toHaveBeenCalledWith('SESSION_CHANGED');expect(stop).toHaveBeenCalled();
    expect(w.find('[data-testid="authorized-question"]').exists()).toBe(false);
    expect(await journal.read(scope,before.segment.id)).toEqual(before);journal.close();
    expect(api.endPreparedAnswer).not.toHaveBeenCalled();
  });
  it('pending server identity blocks a new capture but cannot interrupt the original local recording or prevent its local end',async()=>{
    const last=controlledCapture(),w=render();await ready(w);await w.setProps({identityPending:true});
    expect(button(w,'开考并开始第一题').attributes('disabled')).toBeDefined();
    await button(w,'开考并开始第一题').trigger('click');expect(requestPreparedDevice).not.toHaveBeenCalled();expect(api.startPreparedAttempt).not.toHaveBeenCalled();
    await w.setProps({identityPending:false});await active(w);await w.setProps({identityPending:true});
    expect(last().interrupt).not.toHaveBeenCalled();expect(button(w,'结束本题并保存').attributes('disabled')).toBeUndefined();
    vi.mocked(getPreparedPreflightContext).mockRejectedValueOnce(new Error('still offline'));
    await button(w,'结束本题并保存').trigger('click');await vi.waitFor(()=>expect(last().stop).toHaveBeenCalled());
    await vi.waitFor(()=>expect(w.text()).toContain('原录音本机封存'));expect(last().interrupt).not.toHaveBeenCalled();
  });
  it('same-owner context refresh during device recheck preserves restored original question and fault recovery',async()=>{
    await originals();await stored('INTERRUPTED');vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:grant()});
    const w=render();await ready(w);const before=w.get('[data-testid="authorized-question"]').text();
    await w.setProps({context:{...context(),eligibility:{...context().eligibility,phase:'HISTORY_REQUIRES_REVIEW',message:'请核验原尝试'}}});await flushPromises();
    expect(w.get('[data-testid="authorized-question"]').text()).toBe(before);expect(button(w,'故障恢复：保留原内容继续录音')).toBeDefined();
    expect(w.text()).not.toContain('页面或身份已变化');expect(api.startPreparedAttempt).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
  });
  it('same-owner context refresh during optional camera checks cannot interrupt ongoing recording',async()=>{
    const last=controlledCapture(),w=render();await active(w);const before=w.get('[data-testid="authorized-question"]').text();
    await w.setProps({context:{...context(),eligibility:{...context().eligibility,message:'现行身份核验通过'}}});await flushPromises();
    expect(last().interrupt).not.toHaveBeenCalled();expect(stop).not.toHaveBeenCalled();expect(w.get('[data-testid="authorized-question"]').text()).toBe(before);expect(w.get('[data-testid="capture-status"]').text()).toContain('正在录音');
  });
  it('continues a reliably interrupted original answer with the same keys, fixed deadline and retained prefix',async()=>{
    const {q:original}=await originals(),scope=await stored('INTERRUPTED'),fixed=grant();fixed.answerDeadline=new Date(Date.parse(fixed.startedAt)+120000).toISOString();
    const j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory),before=await j.read(scope,(await j.list(scope))[0].id);j.close();
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:fixed});vi.mocked(api.startPreparedQuestion).mockResolvedValue({data:fixed});controlledCapture();
    const w=render();await ready(w);expect(requestPreparedDevice).not.toHaveBeenCalled();
    await button(w,'故障恢复：保留原内容继续录音').trigger('click');await vi.waitFor(()=>expect(button(w,'结束本题并保存').attributes('disabled')).toBeUndefined());
    expect(api.startPreparedQuestion).toHaveBeenCalledWith(8,attempt().id,original,expect.any(AbortSignal));expect(api.startPreparedAttempt).not.toHaveBeenCalled();
    expect(startAuthorizedAnswerCapture).toHaveBeenCalledWith(expect.objectContaining({scope,ordinal:2}));
    expect(w.text()).toContain(fixed.answerDeadline);expect(w.text()).toContain('采集间断仍需教师核验');
    const check=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);expect(await check.read(scope,before.segment.id)).toEqual(before);check.close();
  });
  it('a local whole-answer end declaration cannot be reopened by the fault-recovery button after a lost HTTP response',async()=>{
    const {q:original}=await originals(),scope=await stored('INTERRUPTED'),j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    const command=await j.prepareAnswerEnd(scope,{captureClientId:original.captureClientId,idempotencyKey:original.endKey});j.close();
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:grant()});
    const w=render();await ready(w);expect(button(w,'故障恢复：保留原内容继续录音')).toBeUndefined();expect(requestPreparedDevice).not.toHaveBeenCalled();
    const check=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);expect(await check.recoverAnswerEnd(scope)).toEqual(command);check.close();
  });
  it.each(['deadline','answer','question','body'] as const)('rejects a changed original %s on continuation replay before capturing any new bytes',async field=>{
    await originals();await stored('INTERRUPTED');const fixed=grant();fixed.answerDeadline=new Date(Date.parse(fixed.startedAt)+120000).toISOString();
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:fixed});
    const changed={...fixed};if(field==='deadline'){changed.startedAt=new Date(Date.parse(fixed.startedAt)+1000).toISOString();changed.answerDeadline=new Date(Date.parse(fixed.answerDeadline)+1000).toISOString();}
    if(field==='answer')changed.answerId=crypto.randomUUID();if(field==='question')changed.questionId=crypto.randomUUID();if(field==='body')changed.body='substituted';
    vi.mocked(api.startPreparedQuestion).mockResolvedValue({data:changed});const w=render();await ready(w);
    await button(w,'故障恢复：保留原内容继续录音').trigger('click');await ready(w);expect(startAuthorizedAnswerCapture).not.toHaveBeenCalled();expect(stop).toHaveBeenCalled();
  });
  it.each(['permission','deadline','offline','consent','microphone','closed'] as const)('fault continuation still requires original %s conditions',async reason=>{
    await originals();await stored('INTERRUPTED');const q=grant();q.answerDeadline=new Date(Date.parse(q.startedAt)+120000).toISOString();
    if(reason==='permission')q.mayRecord=false;if(reason==='deadline'){q.startedAt=new Date(Date.now()-121000).toISOString();q.answerDeadline=new Date(Date.parse(q.startedAt)+120000).toISOString();}
    if(reason==='offline')vi.spyOn(navigator,'onLine','get').mockReturnValue(false);
    const props={context:context(),consent:reason!=='consent',microphonePassed:reason!=='microphone'};
    if(reason==='closed'){props.context.workflowEnabled=false;vi.mocked(getPreparedPreflightContext).mockResolvedValue({data:props.context});}
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:q});const w=render(props);await ready(w);
    expect(button(w,'故障恢复：保留原内容继续录音').attributes('disabled')).toBeDefined();expect(requestPreparedDevice).not.toHaveBeenCalled();expect(api.startPreparedQuestion).not.toHaveBeenCalled();
  });
  it('shows unverified whole-media state and immediate offline recovery while the first background reply is still pending',async()=>{
    await originals();await stored();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'UPLOAD_PENDING',mayRecord:false}});
    uploadMocks.authority.mockImplementation(()=>new Promise(()=>{}));const w=render();await ready(w);
    expect(w.get('[data-testid="media-status"]').text()).toContain('尚未核验');expect(w.get('[data-testid="media-status"]').text()).not.toContain('已可靠保存 0');
    vi.spyOn(navigator,'onLine','get').mockReturnValue(false);window.dispatchEvent(new Event('offline'));await flushPromises();expect(w.get('[data-testid="media-status"]').text()).toContain('断网等待恢复');
    expect(api.startPreparedQuestion).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
  });
  it('keeps original recording and timing live while a separate prepared broadcast request fails',async()=>{
    controlledCapture();vi.mocked(api.getPreparedQuestionAudio).mockRejectedValue(new Error('cloud-unavailable'));
    const w=render();await active(w);const original=w.get('[data-testid="authorized-question"]').text();await button(w,'获取题目播报／重播').trigger('click');await flushPromises();
    expect(w.text()).toContain('不阻止录音');expect(w.get('[data-testid="capture-status"]').text()).toContain('正在录音');expect(w.get('[data-testid="authorized-question"]').text()).toBe(original);
    expect(stop).not.toHaveBeenCalled();expect(api.endPreparedAnswer).not.toHaveBeenCalled();expect(api.startPreparedQuestion).toHaveBeenCalledTimes(1);expect(w.text()).not.toContain('cloud-unavailable');
  });
  it('shows per-answer teacher deadline and resumes only original bytes without reopening microphone or shifting the question limit',async()=>{
    const ids=await originals(),scope=await stored(),j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    const ending=await j.prepareAnswerEnd(scope,{captureClientId:ids.q.captureClientId,idempotencyKey:ids.q.endKey});j.close();const hash=(await matchingReceipt(ending)).manifestSha256;
    const q={...grant(),state:'UPLOAD_PENDING' as const,mayRecord:false,answerDeadline:'2026-10-03T00:02:00Z'},a={...attempt(),state:'REQUIRES_REVIEW' as const,revision:4,answeringEndedAt:'2026-10-03T00:01:00Z',supplementalUploadDeadline:'2026-10-04T00:01:00Z'};
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:a});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:q});uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:true});
    const initial={studentId:12,assessmentId:8,attemptId:a.id,answerId:q.answerId,paperVersionId:a.paperVersionId,manifestSha256:hash,answerDeadline:q.answerDeadline,answeringEndedAt:a.answeringEndedAt,automaticDeadline:a.supplementalUploadDeadline,serverNow:'2026-10-04T01:00:00Z',phase:'AWAITING_APPROVAL',mayUploadOriginal:false,approvalRevision:0,approvedAt:null,uploadDeadline:a.supplementalUploadDeadline};
    uploadMocks.window.mockResolvedValue(initial);const w=render();await ready(w);await vi.waitFor(()=>expect(w.find('[data-testid="upload-window"]').text()).toContain('等待教师批准补传'));expect(uploadMocks.upload).not.toHaveBeenCalled();
    vi.spyOn(navigator,'onLine','get').mockReturnValue(false);window.dispatchEvent(new Event('offline'));await flushPromises();
    expect(w.get('[data-testid="upload-status"]').text()).toContain('网络中断');expect(w.get('[data-testid="media-status"]').text()).toContain('断网等待恢复');
    expect(requestPreparedDevice).not.toHaveBeenCalled();expect(w.get('[data-testid="upload-window"]').text()).toContain('等待教师批准补传');
    vi.spyOn(navigator,'onLine','get').mockReturnValue(true);window.dispatchEvent(new Event('online'));await flushPromises();
    uploadMocks.window.mockResolvedValue({...initial,phase:'APPROVED',mayUploadOriginal:true,approvalRevision:1,approvedAt:'2026-10-04T00:02:00Z',uploadDeadline:'2026-10-04T02:00:00Z'});
    const result=(i:any,confirmed:boolean)=>({bundleId:i.bundleId,answerId:i.answer.answerId,commandSha256:i.commandSha256,byteSize:i.command.byteSize,sha256:i.command.sha256,confirmed,receipts:confirmed?i.command.chunks.map((c:any)=>({...c,answerId:i.answer.answerId,segmentId:i.command.segmentId,confirmed:true,confirmedAt:'2026-10-04T01:00:00Z'})):[]});
    const {UploadTransportError}=await import('@/api/preparedUpload');uploadMocks.status.mockRejectedValueOnce(new UploadTransportError(404));uploadMocks.prepare.mockImplementation(async i=>result(i,false));uploadMocks.upload.mockImplementation(async i=>result(i,true));window.dispatchEvent(new Event('online'));
    await vi.waitFor(()=>expect(w.get('[data-testid="upload-window"]').text()).toContain('2026-10-04T02:00:00Z'));await vi.waitFor(()=>expect(uploadMocks.upload).toHaveBeenCalledTimes(1));
    expect(w.text()).toContain('本题原截止：2026-10-03T00:02:00Z');expect(w.text()).toContain('批准仅用于上传原回答，不增加录音时间');expect(w.text()).not.toContain('整场已可靠提交');
    expect(requestPreparedDevice).not.toHaveBeenCalled();expect(api.startPreparedAttempt).not.toHaveBeenCalled();expect(api.startPreparedQuestion).not.toHaveBeenCalled();expect(api.endPreparedAnswer).not.toHaveBeenCalled();
    const check=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);expect(await check.recoverAnswerEnd(scope)).toEqual(ending);check.close();
  });
  it.each(['current','unmounted','identity-changed'] as const)('a reconnect during an original upload-window read retains one bounded follow-up only for the %s owner',async owner=>{
    const ids=await originals(),scope=await stored(),j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    const ending=await j.prepareAnswerEnd(scope,{captureClientId:ids.q.captureClientId,idempotencyKey:ids.q.endKey});j.close();const hash=(await matchingReceipt(ending)).manifestSha256;
    const q={...grant(),state:'UPLOAD_PENDING' as const,mayRecord:false,answerDeadline:'2026-10-03T00:02:00Z'},a={...attempt(),state:'REQUIRES_REVIEW' as const,revision:4,answeringEndedAt:'2026-10-03T00:01:00Z',supplementalUploadDeadline:'2026-10-04T00:01:00Z'};
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:a});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:q});
    uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:true});
    const pending={studentId:12,assessmentId:8,attemptId:a.id,answerId:q.answerId,paperVersionId:a.paperVersionId,manifestSha256:hash,answerDeadline:q.answerDeadline,answeringEndedAt:a.answeringEndedAt,automaticDeadline:a.supplementalUploadDeadline,serverNow:'2026-10-04T01:00:00Z',phase:'AWAITING_APPROVAL',mayUploadOriginal:false,approvalRevision:0,approvedAt:null,uploadDeadline:a.supplementalUploadDeadline};
    let release!:(value:unknown)=>void;uploadMocks.window.mockImplementationOnce(()=>new Promise(resolve=>{release=resolve;}));
    uploadMocks.window.mockResolvedValue({...pending,phase:'APPROVED',mayUploadOriginal:true,approvalRevision:1,approvedAt:'2026-10-04T00:02:00Z',uploadDeadline:'2026-10-04T02:00:00Z'});
    const result=(i:any,confirmed:boolean)=>({bundleId:i.bundleId,answerId:i.answer.answerId,commandSha256:i.commandSha256,byteSize:i.command.byteSize,sha256:i.command.sha256,confirmed,receipts:confirmed?i.command.chunks.map((c:any)=>({...c,answerId:i.answer.answerId,segmentId:i.command.segmentId,confirmed:true,confirmedAt:'2026-10-04T01:00:00Z'})):[]});
    uploadMocks.status.mockImplementation(async i=>result(i,false));uploadMocks.prepare.mockImplementation(async i=>result(i,false));uploadMocks.upload.mockImplementation(async i=>result(i,true));
    const w=render();await ready(w);await vi.waitFor(()=>expect(uploadMocks.window).toHaveBeenCalledTimes(1));
    for(let n=0;n<3;n++)window.dispatchEvent(new Event('online'));
    expect(uploadMocks.window).toHaveBeenCalledTimes(1);expect(uploadMocks.upload).not.toHaveBeenCalled();
    if(owner==='unmounted')w.unmount();if(owner==='identity-changed'){auth.id='13';await flushPromises();}
    release(pending);await flushPromises();
    if(owner==='current'){
      await vi.waitFor(()=>expect(w.get('[data-testid="upload-window"]').text()).toContain('2026-10-04T02:00:00Z'));
      await vi.waitFor(()=>expect(uploadMocks.upload).toHaveBeenCalledTimes(1));expect(uploadMocks.window).toHaveBeenCalledTimes(2);
      expect(w.text()).toContain(q.answerDeadline);expect(requestPreparedDevice).not.toHaveBeenCalled();
    }else{expect(uploadMocks.window).toHaveBeenCalledTimes(1);expect(uploadMocks.upload).not.toHaveBeenCalled();}
    expect(api.startPreparedAttempt).not.toHaveBeenCalled();expect(api.startPreparedQuestion).not.toHaveBeenCalled();
  });
  it.each(['current','unmounted','identity-changed'] as const)('a reconnect during original media status retains one follow-up only for the %s owner',async owner=>{
    const ids=await originals(),scope=await stored(),j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    const ending=await j.prepareAnswerEnd(scope,{captureClientId:ids.q.captureClientId,idempotencyKey:ids.q.endKey});j.close();const hash=(await matchingReceipt(ending)).manifestSha256;
    const q={...grant(),state:'UPLOAD_PENDING' as const,mayRecord:false},a={...attempt(),state:'REQUIRES_REVIEW' as const,revision:4,answeringEndedAt:new Date().toISOString(),supplementalUploadDeadline:new Date(Date.now()+86400000).toISOString()};
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:a});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:q});
    // Upload writes stay closed. The media observer still reads an already queued
    // original's real state; SAVED is an explicit fixture, not a cloud integration.
    const queued={answerId:q.answerId,manifestSha256:hash,jobId:crypto.randomUUID(),phase:'RUNNING',serverSaved:false,revision:1,durationMs:null,verifiedAt:null};
    let release!:(value:unknown)=>void;uploadMocks.mediaStatus.mockImplementationOnce(()=>new Promise(resolve=>{release=resolve;}));
    uploadMocks.mediaStatus.mockResolvedValue({...queued,phase:'SAVED',serverSaved:true,revision:2,durationMs:1000,verifiedAt:new Date().toISOString()});
    const w=render();await ready(w);await vi.waitFor(()=>expect(uploadMocks.mediaStatus).toHaveBeenCalledTimes(1));
    for(let n=0;n<3;n++)window.dispatchEvent(new Event('online'));
    expect(uploadMocks.mediaStatus).toHaveBeenCalledTimes(1);if(owner==='unmounted')w.unmount();if(owner==='identity-changed'){auth.id='13';await flushPromises();}
    release(queued);await flushPromises();
    if(owner==='current'){await vi.waitFor(()=>expect(w.get('[data-testid="media-status"]').text()).toContain('已可靠保存 1 / 3 题'));expect(uploadMocks.mediaStatus).toHaveBeenCalledTimes(2);}
    else expect(uploadMocks.mediaStatus).toHaveBeenCalledTimes(1);
    expect(uploadMocks.upload).not.toHaveBeenCalled();expect(uploadMocks.queueMedia).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
  });
  it('can recover a unique authorized final server receipt without local original keys, model wait or starting another recorder',async()=>{
    const a={...attempt(),state:'SUBMITTED' as const,revision:4,answeringEndedAt:new Date().toISOString(),supplementalUploadDeadline:new Date(Date.now()+86400000).toISOString()};
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:a});uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:false});
    uploadMocks.submissionStatus.mockResolvedValue({attemptId:a.id,paperVersionId:a.paperVersionId,revision:4,phase:'SUBMITTED',submitted:true,pending:[],receipt:{id:grant().questionId,attemptId:a.id,paperVersionId:a.paperVersionId,revision:4,answerCount:3,submittedAt:a.answeringEndedAt}});
    const w=render();await ready(w);await vi.waitFor(()=>expect(w.get('[data-testid="submission-status"]').text()).toContain('整场已可靠提交'));
    expect(w.text()).toContain(grant().questionId);expect(requestPreparedDevice).not.toHaveBeenCalled();expect(uploadMocks.submitAttempt).not.toHaveBeenCalled();expect(api.getCurrentPreparedQuestion).not.toHaveBeenCalled();
  });
  it('missing whole answers disables final submission and shows the server missing original question without fake completion',async()=>{
    await originals();await stored();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:{...attempt(),state:'UPLOAD_PENDING',revision:3,answeringEndedAt:new Date().toISOString(),supplementalUploadDeadline:new Date(Date.now()+86400000).toISOString()}});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'UPLOAD_PENDING',mayRecord:false}});
    uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:true});const w=render();await ready(w);
    await vi.waitFor(()=>expect(w.get('[data-testid="submission-status"]').text()).toContain('第 1 题'));
    expect(button(w,'确认提交整场面试').attributes('disabled')).toBeDefined();expect(w.text()).not.toContain('整场已可靠提交');expect(uploadMocks.submitAttempt).not.toHaveBeenCalled();
  });
  it('a pending identity read cannot permit final submission from an earlier ready snapshot',async()=>{
    await originals();await stored();const a={...attempt(),state:'UPLOAD_PENDING' as const,revision:3,answeringEndedAt:new Date().toISOString(),supplementalUploadDeadline:new Date(Date.now()+86400000).toISOString()};
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:a});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'SAVED',mayRecord:false}});
    uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:true});
    uploadMocks.submissionStatus.mockResolvedValue({attemptId:a.id,paperVersionId:a.paperVersionId,revision:3,phase:'READY',submitted:false,pending:[],receipt:null});
    const w=render();await ready(w);await vi.waitFor(()=>expect(button(w,'确认提交整场面试').attributes('disabled')).toBeUndefined());
    await w.setProps({identityPending:true});expect(button(w,'确认提交整场面试').attributes('disabled')).toBeDefined();
    await button(w,'确认提交整场面试').trigger('click');expect(uploadMocks.submitAttempt).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
    await w.setProps({identityPending:false});expect(button(w,'确认提交整场面试').attributes('disabled')).toBeUndefined();
  });
  it.each(['duplicate-answer','not-started-with-answer'])('rejects %s in a final-status response without changing or re-recording local audio',async invalid=>{
    await originals();const original=await stored();
    const a={...attempt(),state:'UPLOAD_PENDING' as const,revision:3,answeringEndedAt:new Date().toISOString(),supplementalUploadDeadline:new Date(Date.now()+86400000).toISOString()};
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:a});
    vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'UPLOAD_PENDING',mayRecord:false}});
    uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:true});
    const pending=invalid==='duplicate-answer'?[{ordinal:1,answerId:grant().answerId,phase:'UPLOAD_PENDING'},
      {ordinal:2,answerId:grant().answerId,phase:'QUEUED'}]:[{ordinal:1,answerId:grant().answerId,phase:'NOT_STARTED'}];
    uploadMocks.submissionStatus.mockResolvedValue({attemptId:a.id,paperVersionId:a.paperVersionId,revision:3,phase:'WAITING_ANSWERS',submitted:false,pending,receipt:null});
    const w=render();await ready(w);
    await vi.waitFor(()=>expect(w.get('[data-testid="submission-status"]').text()).toContain('提交状态尚未确认'));
    expect(button(w,'确认提交整场面试').attributes('disabled')).toBeDefined();
    expect(w.text()).not.toContain('整场已可靠提交');expect(uploadMocks.submitAttempt).not.toHaveBeenCalled();
    expect(requestPreparedDevice).not.toHaveBeenCalled();expect(startAuthorizedAnswerCapture).not.toHaveBeenCalled();
    const audio=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    try{expect(await audio.list(original)).toMatchObject([{state:'SEALED',chunkCount:1}]);}finally{audio.close();}
  });
  it('final status identifies original queued media awaiting approval and never enables submission from the grant alone',async()=>{
    await originals();await stored();const a={...attempt(),state:'REQUIRES_REVIEW' as const,revision:3,answeringEndedAt:new Date().toISOString(),supplementalUploadDeadline:new Date(Date.now()+86400000).toISOString()};
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:a});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'UPLOAD_PENDING',mayRecord:false}});
    uploadMocks.submissionStatus.mockResolvedValue({attemptId:a.id,paperVersionId:a.paperVersionId,revision:3,phase:'REQUIRES_REVIEW',submitted:false,pending:[{ordinal:1,answerId:grant().answerId,phase:'WAITING_APPROVAL'}],receipt:null});
    const w=render();await ready(w);await vi.waitFor(()=>expect(w.get('[data-testid="submission-status"]').text()).toContain('等待教师批准原录音核验'));
    expect(button(w,'确认提交整场面试').attributes('disabled')).toBeDefined();expect(uploadMocks.submitAttempt).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
  });
  it('persists the original final command before HTTP; lost response is queried on retry without another key or recording',async()=>{
    await originals();await stored();const a={...attempt(),state:'UPLOAD_PENDING' as const,revision:3,answeringEndedAt:new Date().toISOString(),supplementalUploadDeadline:new Date(Date.now()+86400000).toISOString()};vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:a});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'SAVED',mayRecord:false}});
    uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:true});const current={attemptId:a.id,paperVersionId:a.paperVersionId,revision:3,phase:'READY',submitted:false,pending:[],receipt:null};uploadMocks.submissionStatus.mockResolvedValue(current);
    uploadMocks.submitAttempt.mockImplementation(async(_assessment,_attempt,command)=>{const j=new PreparedJourneyJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},8,factory);expect(await j.readSubmission({attemptId:a.id,paperVersionId:a.paperVersionId})).toMatchObject(command);j.close();throw new Error('lost response');});
    const w=render();await ready(w);await vi.waitFor(()=>expect(button(w,'确认提交整场面试').attributes('disabled')).toBeUndefined());await button(w,'确认提交整场面试').trigger('click');await ready(w);
    expect(uploadMocks.submitAttempt).toHaveBeenCalledTimes(1);const key=uploadMocks.submitAttempt.mock.calls[0][2].idempotencyKey;
    uploadMocks.submissionStatus.mockResolvedValue({...current,revision:4,phase:'SUBMITTED',submitted:true,receipt:{id:grant().questionId,attemptId:a.id,paperVersionId:a.paperVersionId,revision:4,answerCount:3,submittedAt:a.answeringEndedAt}});
    await button(w,'确认提交整场面试').trigger('click');await ready(w);expect(uploadMocks.submitAttempt).toHaveBeenCalledTimes(1);expect(w.text()).toContain('整场已可靠提交');
    const j=new PreparedJourneyJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},8,factory);expect((await j.readSubmission({attemptId:a.id,paperVersionId:a.paperVersionId}))!.idempotencyKey).toBe(key);j.close();expect(requestPreparedDevice).not.toHaveBeenCalled();
  });
  it('shows independently verified whole-media saving without claiming final submission or deleting original audio',async()=>{
    uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:true});
    const reply=(i:any)=>({bundleId:i.bundleId,answerId:i.answer.answerId,commandSha256:i.commandSha256,byteSize:i.command.byteSize,sha256:i.command.sha256,confirmed:true,
      receipts:i.command.chunks.map((c:any)=>({...c,answerId:i.answer.answerId,segmentId:i.command.segmentId,confirmed:true,confirmedAt:new Date().toISOString()}))});
    uploadMocks.prepare.mockImplementation(async i=>reply(i));
    uploadMocks.mediaStatus.mockImplementation(async a=>{const j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory),cmd=await j.recoverAnswerEnd(a);j.close();
      return {answerId:a.answerId,manifestSha256:(await matchingReceipt(cmd)).manifestSha256,jobId:crypto.randomUUID(),phase:'SAVED',serverSaved:true,revision:2,durationMs:1000,verifiedAt:new Date().toISOString()};});
    controlledCapture();const w=render();await active(w);vi.mocked(api.endPreparedAnswer).mockImplementation(async(_a,_b,_c,cmd)=>({data:await matchingReceipt(cmd)}));
    await button(w,'结束本题并保存').trigger('click');await ready(w);
    await vi.waitFor(()=>expect(w.find('[data-testid="media-status"]').text()).toContain('整题录音已保存'));
    expect(w.text()).toContain('不是最终提交');expect(uploadMocks.queueMedia).not.toHaveBeenCalled();expect(button(w,'授权并开始下一题').exists()).toBe(true);
    const j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);expect((await j.list({assessmentId:8,attemptId:attempt().id,answerId:grant().answerId}))[0].chunkCount).toBe(1);j.close();
  });
  it('can enter the next authorized question after restoring a SAVED current answer',async()=>{
    await originals();await stored();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'SAVED',mayRecord:false}});
    const w=render();await ready(w);expect(button(w,'授权并开始下一题')).toBeDefined();expect(startAuthorizedAnswerCapture).not.toHaveBeenCalled();
  });
  it('automatically sends the sealed original answer in the background, distinguishes byte receipt from submission, and preserves local audio',async()=>{
    uploadMocks.authority.mockResolvedValue({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:true});
    const result=(i:any,confirmed:boolean)=>({bundleId:i.bundleId,answerId:i.answer.answerId,commandSha256:i.commandSha256,byteSize:i.command.byteSize,sha256:i.command.sha256,confirmed,
      receipts:confirmed?i.command.chunks.map((c:any)=>({...c,answerId:i.answer.answerId,segmentId:i.command.segmentId,confirmed:true,confirmedAt:new Date().toISOString()})):[]});
    uploadMocks.prepare.mockImplementation(async i=>result(i,false));uploadMocks.upload.mockImplementation(async i=>result(i,true));
    controlledCapture();const w=render();await active(w);vi.mocked(api.endPreparedAnswer).mockImplementation(async(_a,_b,_c,cmd)=>({data:await matchingReceipt(cmd)}));
    await button(w,'结束本题并保存').trigger('click');
    await vi.waitFor(()=>expect(uploadMocks.upload).toHaveBeenCalledTimes(1));await vi.waitFor(()=>expect(w.get('[data-testid="upload-status"]').text()).toContain('云端字节已确认'));
    expect(w.text()).toContain('不是整题保存或最终提交');expect(button(w,'授权并开始下一题').exists()).toBe(true);
    const j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    expect((await j.list({assessmentId:8,attemptId:attempt().id,answerId:grant().answerId}))[0].state).toBe('SEALED');j.close();
  });
  it('mount only restores read-only state; default closed gate and incomplete preflight forbid start',async()=>{
    vi.mocked(getPreparedPreflightContext).mockResolvedValue({data:{...context(),workflowEnabled:false}});
    const w=render({context:{...context(),workflowEnabled:false},consent:true,microphonePassed:true});await ready(w);
    expect(button(w,'开考并开始第一题').attributes('disabled')).toBeDefined();expect(api.startPreparedAttempt).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
    await w.setProps({context:context(),microphonePassed:false});expect(button(w,'开考并开始第一题').attributes('disabled')).toBeDefined();
  });
  it('persists original start and question intents BEFORE each corresponding request',async()=>{
    vi.mocked(api.startPreparedAttempt).mockImplementation(async(_id,command)=>{const j=new PreparedJourneyJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},8,factory);expect((await j.readStart())!.idempotencyKey).toBe(command.idempotencyKey);j.close();return {data:attempt()};});
    vi.mocked(api.startPreparedQuestion).mockImplementation(async(_id,_a,command)=>{const j=new PreparedJourneyJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},8,factory);expect(await j.readQuestion(1)).toMatchObject(command);j.close();const g=grant();g.answerDeadline=new Date(Date.parse(g.startedAt)+120000).toISOString();return {data:g};});
    vi.mocked(startAuthorizedAnswerCapture).mockRejectedValue(new Error('controlled unavailable recorder'));
    const w=render();await ready(w);await button(w,'开考并开始第一题').trigger('click');await flushPromises();
    await vi.waitFor(()=>expect(startAuthorizedAnswerCapture).toHaveBeenCalledTimes(1));
    expect(w.text()).toContain('请介绍学习经历');expect(w.text()).not.toContain('已提交');expect(stop).toHaveBeenCalled();
  });
  it('failed start retries the same durable key, never a new capture client',async()=>{
    vi.mocked(api.startPreparedAttempt).mockRejectedValue(new Error('lost response'));
    const w=render();await ready(w);await button(w,'开考并开始第一题').trigger('click');await vi.waitFor(()=>expect(api.startPreparedAttempt).toHaveBeenCalledTimes(1));await ready(w);
    const first=vi.mocked(api.startPreparedAttempt).mock.calls[0][1];
    await button(w,'开考并开始第一题').trigger('click');await vi.waitFor(()=>expect(api.startPreparedAttempt).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.startPreparedAttempt).mock.calls[1][1]).toEqual(first);
  });
  it('restores the original authorized question without creating another attempt or microphone',async()=>{
    await originals();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:grant()});
    const w=render();await ready(w);expect(w.text()).toContain('请介绍学习经历');expect(api.startPreparedAttempt).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
    expect(button(w,'恢复当前题采集').exists()).toBe(true);
  });
  it('existing server attempt with missing original local identifiers never regenerates them',async()=>{
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});const w=render();await ready(w);
    expect(w.text()).toContain('缺少原作答标识');expect(api.startPreparedAttempt).not.toHaveBeenCalled();
    const j=new PreparedJourneyJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},8,factory);expect(await j.readStart()).toBeNull();j.close();
  });
  it('offline cannot authorize a next or first question',async()=>{
    vi.spyOn(navigator,'onLine','get').mockReturnValue(false);const w=render();await ready(w);
    expect(button(w,'开考并开始第一题').attributes('disabled')).toBeDefined();expect(w.text()).toContain('断网仅当前题');expect(api.startPreparedQuestion).not.toHaveBeenCalled();
  });
  it('server identity mismatch is not allowed to open or display another account local state',async()=>{
    await originals();const open=vi.spyOn(factory,'open');
    vi.mocked(getPreparedPreflightContext).mockResolvedValue({data:{...context(),studentId:99}});const w=render();await ready(w);
    expect(w.text()).toContain('身份');expect(open).not.toHaveBeenCalled();expect(api.getCurrentPreparedQuestion).not.toHaveBeenCalled();
    expect(api.getCurrentPreparedAttempt).toHaveBeenCalledTimes(1);expect(vi.mocked(api.getCurrentPreparedAttempt).mock.calls[0][1]!.aborted).toBe(true);
    expect(w.text()).not.toContain('请介绍学习经历');expect(requestPreparedDevice).not.toHaveBeenCalled();
  });
  it('foreign-client or expired current grant cannot show a live recording permission',async()=>{
    await originals();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),mayRecord:false}});
    const w=render();await ready(w);expect(button(w,'恢复当前题采集').attributes('disabled')).toBeDefined();expect(w.text()).toContain('原时限');expect(startAuthorizedAnswerCapture).not.toHaveBeenCalled();
  });
  it('ends original locally before declaration and clearly distinguishes it from cloud receipt',async()=>{
    controlledCapture();vi.mocked(api.endPreparedAnswer).mockImplementation(async(_a,_t,_q,cmd)=>{
      const j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
      expect(await j.recoverAnswerEnd({assessmentId:8,attemptId:attempt().id,answerId:grant().answerId})).toEqual(cmd);j.close();return {data:await matchingReceipt(cmd)};});
    const w=render();await active(w);await button(w,'结束本题并保存').trigger('click');await ready(w);
    expect(w.text()).toContain('原结束信息已登记');expect(w.text()).toContain('不是云端录音已保存');expect(button(w,'授权并开始下一题')).toBeDefined();
  });
  it('offline ending persists the original request and does not send or authorize another question',async()=>{
    controlledCapture();const w=render();await active(w);vi.spyOn(navigator,'onLine','get').mockReturnValue(false);window.dispatchEvent(new Event('offline'));
    await button(w,'结束本题并保存').trigger('click');await ready(w);expect(w.text()).toContain('原结束请求已本机保存');expect(api.endPreparedAnswer).not.toHaveBeenCalled();expect(api.startPreparedQuestion).toHaveBeenCalledTimes(1);
    const j=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);expect((await j.recoverAnswerEnd({assessmentId:8,attemptId:attempt().id,answerId:grant().answerId})).manifest.segments).toHaveLength(1);j.close();
  });
  it('lost end response retries the exact same fixed manifest and original key',async()=>{
    controlledCapture();vi.mocked(api.endPreparedAnswer).mockRejectedValue(new Error('lost response'));
    const w=render();await active(w);await button(w,'结束本题并保存').trigger('click');await ready(w);
    const first=vi.mocked(api.endPreparedAnswer).mock.calls[0][3];vi.mocked(api.endPreparedAnswer).mockImplementation(async(_a,_t,_q,cmd)=>({data:await matchingReceipt(cmd)}));
    await button(w,'登记本题原结束信息').trigger('click');await ready(w);expect(vi.mocked(api.endPreparedAnswer).mock.calls[1][3]).toEqual(first);
  });
  it('restored sealed original audio can register without any new capture',async()=>{
    await originals();await stored();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:grant()});vi.mocked(api.endPreparedAnswer).mockImplementation(async(_a,_t,_q,cmd)=>({data:await matchingReceipt(cmd)}));
    const w=render();await ready(w);expect(w.text()).toContain('本机原录音');await button(w,'登记本题原结束信息').trigger('click');await ready(w);
    expect(api.endPreparedAnswer).toHaveBeenCalledTimes(1);expect(startAuthorizedAnswerCapture).not.toHaveBeenCalled();
  });
  it('an unended original prefix is not invented into a complete answer or restarted',async()=>{
    await originals();await stored('OPEN');vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:grant()});
    const w=render();await ready(w);expect(w.text()).toContain('原录音尚无可靠结束依据');expect(button(w,'登记本题原结束信息')).toBeUndefined();expect(button(w,'恢复当前题采集')).toBeUndefined();expect(startAuthorizedAnswerCapture).not.toHaveBeenCalled();
  });
  it('an interrupted closed segment remains a review declaration, never a successful complete recording',async()=>{
    await originals();await stored('INTERRUPTED');vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:grant()});
    vi.mocked(api.endPreparedAnswer).mockImplementation(async(_a,_t,_q,cmd)=>({data:await matchingReceipt(cmd,{declarationState:'REQUIRES_REVIEW',attemptState:'REQUIRES_REVIEW',reviewReasons:['CAPTURE_INTERRUPTED']})}));
    const w=render();await ready(w);await button(w,'登记本题原结束信息').trigger('click');await ready(w);expect(w.text()).toContain('REQUIRES_REVIEW');expect(button(w,'授权并开始下一题')).toBeUndefined();
  });
  it('invalid byte-saved assertion or mismatched receipt is rejected without a next-question success',async()=>{
    controlledCapture();vi.mocked(api.endPreparedAnswer).mockResolvedValue({data:{...endReceipt(),serverSaved:true} as never});
    const w=render();await active(w);await button(w,'结束本题并保存').trigger('click');await ready(w);expect(button(w,'授权并开始下一题')).toBeUndefined();expect(w.text()).toContain('原作答操作未完成');
  });
  it('a well-formed receipt digest for different metadata cannot acknowledge this original declaration',async()=>{
    controlledCapture();vi.mocked(api.endPreparedAnswer).mockResolvedValue({data:endReceipt({manifestSha256:'b'.repeat(64)})});
    const w=render();await active(w);await button(w,'结束本题并保存').trigger('click');await ready(w);
    expect(button(w,'授权并开始下一题')).toBeUndefined();expect(w.text()).toContain('原作答操作未完成');
  });
  it('a local save failure only offers original-save retry, not a second capture',async()=>{
    const local=controlledCapture(true),w=render();await active(w);await button(w,'结束本题并保存').trigger('click');await ready(w);
    expect(w.text()).toContain('本机保存失败');expect(api.endPreparedAnswer).not.toHaveBeenCalled();await button(w,'重试保存原录音').trigger('click');await ready(w);
    expect(local().retrySave).toHaveBeenCalledTimes(1);expect(startAuthorizedAnswerCapture).toHaveBeenCalledTimes(1);expect(w.text()).toContain('原录音本机封存');
  });
  it('identity switch stops the original microphone and removes the previous account question',async()=>{
    const local=controlledCapture(),w=render();await active(w);auth.id='99';await flushPromises();
    expect(local().interrupt).toHaveBeenCalledWith('SESSION_CHANGED');expect(stop).toHaveBeenCalled();expect(w.find('[data-testid="authorized-question"]').exists()).toBe(false);expect(api.endPreparedAnswer).not.toHaveBeenCalled();
  });
  it('withdrawn consent interrupts original recording and clears the live indicator',async()=>{
    const local=controlledCapture(),w=render();await active(w);await w.setProps({consent:false});await flushPromises();
    expect(local().interrupt).toHaveBeenCalled();expect(w.find('[data-testid="capture-status"]').exists()).toBe(false);
  });
  it('a browser pagehide marks original page interruption without any submission claim',async()=>{
    const local=controlledCapture(),w=render();await active(w);window.dispatchEvent(new Event('pagehide'));await flushPromises();
    expect(local().interrupt).toHaveBeenCalledWith('PAGE_REFRESH');expect(api.endPreparedAnswer).not.toHaveBeenCalled();
  });
  it('metadata timeout is bounded and late identity results cannot create a new attempt',async()=>{
    let resolve!:(v:any)=>void;vi.mocked(getPreparedPreflightContext).mockImplementation(()=>new Promise(r=>{resolve=r;}));vi.useFakeTimers();
    try{
      const open=vi.spyOn(factory,'open'),w=render();await flushPromises();await vi.advanceTimersByTimeAsync(30001);
      expect(w.text()).toContain('等待超时');resolve({data:context()});await flushPromises();
      expect(api.getCurrentPreparedAttempt).toHaveBeenCalledTimes(1);expect(vi.mocked(api.getCurrentPreparedAttempt).mock.calls[0][1]!.aborted).toBe(true);
      expect(open).not.toHaveBeenCalled();expect(api.getCurrentPreparedQuestion).not.toHaveBeenCalled();
      expect(api.startPreparedAttempt).not.toHaveBeenCalled();expect(api.startPreparedQuestion).not.toHaveBeenCalled();expect(requestPreparedDevice).not.toHaveBeenCalled();
    }finally{vi.useRealTimers();}
  });
  it('a changed server paper cannot be rebound to the persisted original attempt',async()=>{
    await originals();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:{...attempt(),paperVersionId:crypto.randomUUID()}});const w=render();await ready(w);
    expect(api.getCurrentPreparedQuestion).not.toHaveBeenCalled();expect(w.text()).toContain('原作答操作未完成');
  });
  it('a terminal server attempt does not request a new recording',async()=>{
    await originals();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:{...attempt(),state:'REQUIRES_REVIEW'}});
    const w=render();await ready(w);expect(button(w,'开始第一题')).toBeUndefined();expect(requestPreparedDevice).not.toHaveBeenCalled();
  });
  it('pending original upload authorizes only the next fixed ordinal with a separately persisted key',async()=>{
    await originals();await stored();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'UPLOAD_PENDING',mayRecord:false}});
    vi.mocked(api.startPreparedQuestion).mockImplementation(async(_a,_t,cmd)=>{expect(cmd.ordinal).toBe(2);const g={...grant(),ordinal:2,answerId:crypto.randomUUID()};g.answerDeadline=new Date(Date.parse(g.startedAt)+120000).toISOString();return {data:g};});
    controlledCapture();const w=render();await ready(w);await button(w,'授权并开始下一题').trigger('click');await vi.waitFor(()=>expect(w.text()).toContain('正在录音，本机持续保存'));expect(w.text()).toContain('第 2 题');expect(api.startPreparedAttempt).not.toHaveBeenCalled();
  });
  it('all-ended metadata displays the fixed backfill deadline but no final success receipt',async()=>{
    const a={...attempt(),state:'UPLOAD_PENDING' as const,answeringEndedAt:'2026-10-05T00:00:00Z',supplementalUploadDeadline:'2026-10-06T00:00:00Z'};
    await originals();vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:a});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:{...grant(),state:'UPLOAD_PENDING',mayRecord:false}});
    const w=render();await ready(w);expect(w.text()).toContain('2026-10-06T00:00:00Z');expect(w.text()).toContain('结束信息不是交卷成功');expect(w.text()).not.toContain('整场已可靠提交');expect(button(w,'授权并开始下一题')).toBeUndefined();
  });
});

describe('late original capture notifications after explicit scope recheck',()=>{
  // Real preservation/capture modules, controlled recorder/HTTP and memory IndexedDB.
  // Deliberately varied pointer responses are defensive fixtures, not actual retake authorization.
  class PendingTrack extends EventTarget {
    readonly kind='audio';readyState='live';enabled=true;muted=false;stop=vi.fn(()=>{this.readyState='ended';});
  }
  class PendingRecorder extends EventTarget {
    state:RecordingState='inactive';mimeType='audio/webm;codecs=opus';
    start=vi.fn(()=>{this.state='recording';this.dispatchEvent(new Event('start'));});
    stop=vi.fn(()=>{this.state='inactive';this.dispatchEvent(new Event('stop'));});
    data(text:string){this.dispatchEvent(Object.assign(new Event('dataavailable'),{data:new Blob([text],{type:this.mimeType})}));}
  }
  it.each(['missing-current','different-current','same-original'] as const)('%s cannot misattribute a delayed original write; same original remains recoverable',async change=>{
    const track=new PendingTrack(),recorder=new PendingRecorder(),stream={getTracks:()=>[track],getAudioTracks:()=>[track],getVideoTracks:()=>[]} as unknown as MediaStream;
    const environment:RecorderEnvironment={supports:()=>true,create:()=>recorder as unknown as MediaRecorder,now:()=>Date.now(),writer:async()=>({release:vi.fn(),onLost:()=>()=>{}})};
    let local!:PreparedAnswerCapture;
    vi.mocked(requestPreparedDevice).mockResolvedValue(stream);
    vi.mocked(startAuthorizedAnswerCapture).mockImplementation(async input=>{
      local=await PreparedAnswerCapture.start({journal:input.journal,scope:input.scope,ordinal:input.ordinal,stream:input.stream},environment);return local;
    });
    const originalGrant=grant();originalGrant.answerDeadline=new Date(Date.parse(originalGrant.startedAt)+120000).toISOString();
    vi.mocked(api.startPreparedQuestion).mockResolvedValue({data:originalGrant});
    const w=render();await active(w);const scope={assessmentId:8,attemptId:originalGrant.attemptId,answerId:originalGrant.answerId};
    const before=await originals(),realAppend=PreparedAudioJournal.prototype.append;let commit!:()=>Promise<void>;
    const append=vi.spyOn(PreparedAudioJournal.prototype,'append').mockImplementationOnce(function(this:PreparedAudioJournal,...args:Parameters<PreparedAudioJournal['append']>){
      const journal=this;return new Promise<void>((resolve,reject)=>{commit=async()=>{try{await realAppend.apply(journal,args);resolve();}catch(error){reject(error);throw error;}};});
    });
    vi.useFakeTimers({toFake:['setTimeout','clearTimeout']});
    try{
      recorder.data('retained delayed original bytes');expect(append).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(5000);await flushPromises();expect(local.snapshot().phase).toBe('LOCAL_SAVE_FAILED');
    }finally{vi.useRealTimers();}
    expect(button(w,'重试保存原录音')).toBeDefined();expect(track.stop).toHaveBeenCalledTimes(1);
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:change==='missing-current'?null:change==='different-current'?{...attempt(),id:crypto.randomUUID(),attemptNo:2}:attempt()});
    vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:originalGrant});
    await button(w,'重新核验原作答').trigger('click');await ready(w);
    if(change==='same-original')expect(button(w,'重试保存原录音')).toBeDefined();else expect(button(w,'重试保存原录音')).toBeUndefined();
    const signals=w.emitted('capture-active')?.length??0;
    await commit();await vi.waitFor(()=>expect(local.snapshot().savedChunks).toBe(1));await flushPromises();
    if(change==='same-original'){
      expect(button(w,'重试保存原录音')).toBeDefined();expect(w.get('[data-testid="capture-status"]').text()).toContain('已事务保存 1 片');
      expect(w.get('[data-testid="authorized-question"]').text()).toBe(originalGrant.body);
    }else{
      expect(button(w,'重试保存原录音')).toBeUndefined();expect(w.find('[data-testid="capture-status"]').exists()).toBe(false);
      expect(w.find('[data-testid="authorized-question"]').exists()).toBe(false);expect(w.emitted('capture-active')?.length??0).toBe(signals);
    }
    const journal=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    try{const saved=await journal.read(scope,local.snapshot().segmentId);expect(saved.segment).toMatchObject({state:'OPEN',chunkCount:1});
      expect(await saved.chunks[0].blob.text()).toBe('retained delayed original bytes');}finally{journal.close();}
    expect(await originals()).toEqual(before);expect(api.startPreparedAttempt).toHaveBeenCalledTimes(1);expect(api.startPreparedQuestion).toHaveBeenCalledTimes(1);
    expect(requestPreparedDevice).toHaveBeenCalledTimes(1);expect(api.endPreparedAnswer).not.toHaveBeenCalled();expect(uploadMocks.submitAttempt).not.toHaveBeenCalled();
  });
});

describe('same original capture retains save recovery across a read-only recheck',()=>{
  class RetainedTrack extends EventTarget {
    readonly kind='audio';readyState='live';enabled=true;muted=false;stop=vi.fn(()=>{this.readyState='ended';});
  }
  class RetainedRecorder extends EventTarget {
    state:RecordingState='inactive';mimeType='audio/webm;codecs=opus';
    start=vi.fn(()=>{this.state='recording';this.dispatchEvent(new Event('start'));});
    stop=vi.fn(()=>{this.state='inactive';this.dispatchEvent(new Event('stop'));});
    data(text:string){this.dispatchEvent(Object.assign(new Event('dataavailable'),{data:new Blob([text],{type:this.mimeType})}));}
  }
  it.each(['before-recheck','during-question-lookup'] as const)('original write completes %s without hiding original-save retry or creating a new answer',async timing=>{
    const track=new RetainedTrack(),recorder=new RetainedRecorder(),stream={getTracks:()=>[track],getAudioTracks:()=>[track],getVideoTracks:()=>[]} as unknown as MediaStream;
    const environment:RecorderEnvironment={supports:()=>true,create:()=>recorder as unknown as MediaRecorder,now:()=>Date.now(),writer:async()=>({release:vi.fn(),onLost:()=>()=>{}})};
    let local!:PreparedAnswerCapture;
    vi.mocked(requestPreparedDevice).mockResolvedValue(stream);
    vi.mocked(startAuthorizedAnswerCapture).mockImplementation(async input=>{
      local=await PreparedAnswerCapture.start({journal:input.journal,scope:input.scope,ordinal:input.ordinal,stream:input.stream},environment);return local;
    });
    const originalGrant=grant();originalGrant.answerDeadline=new Date(Date.parse(originalGrant.startedAt)+120000).toISOString();
    vi.mocked(api.startPreparedQuestion).mockResolvedValue({data:originalGrant});
    const w=render();await active(w);const scope={assessmentId:8,attemptId:originalGrant.attemptId,answerId:originalGrant.answerId},before=await originals();
    const realAppend=PreparedAudioJournal.prototype.append;let commit!:()=>Promise<void>;
    vi.spyOn(PreparedAudioJournal.prototype,'append').mockImplementationOnce(function(this:PreparedAudioJournal,...args:Parameters<PreparedAudioJournal['append']>){
      const journal=this;return new Promise<void>((resolve,reject)=>{commit=async()=>{try{await realAppend.apply(journal,args);resolve();}catch(error){reject(error);throw error;}};});
    });
    vi.useFakeTimers({toFake:['setTimeout','clearTimeout']});
    try{recorder.data('original retained recovery bytes');await vi.advanceTimersByTimeAsync(5000);await flushPromises();
      expect(local.snapshot().phase).toBe('LOCAL_SAVE_FAILED');}finally{vi.useRealTimers();}
    expect(button(w,'重试保存原录音')).toBeDefined();const originalSegmentId=local.snapshot().segmentId;
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});
    let resolveQuestion!:(value:{data:ReturnType<typeof grant>})=>void;
    vi.mocked(api.getCurrentPreparedQuestion).mockImplementationOnce(()=>new Promise(resolve=>{resolveQuestion=resolve;}));
    if(timing==='before-recheck'){await commit();await vi.waitFor(()=>expect(local.snapshot().savedChunks).toBe(1));}
    await button(w,'重新核验原作答').trigger('click');await vi.waitFor(()=>expect(api.getCurrentPreparedQuestion).toHaveBeenCalledTimes(2));
    expect(w.find('[data-testid="authorized-question"]').exists()).toBe(false);
    if(timing==='during-question-lookup'){await commit();await vi.waitFor(()=>expect(local.snapshot().savedChunks).toBe(1));await flushPromises();}
    resolveQuestion({data:originalGrant});await ready(w);
    expect(button(w,'重试保存原录音')).toBeDefined();expect(w.get('[data-testid="capture-status"]').text()).toContain('已事务保存 1 片');
    expect(w.get('[data-testid="authorized-question"]').text()).toBe(originalGrant.body);
    expect(w.text()).toContain(originalGrant.answerDeadline);expect(w.text()).toContain('原录音尚无可靠结束依据');
    expect(button(w,'登记本题原结束信息')).toBeUndefined();expect(button(w,'故障恢复：保留原内容继续录音')).toBeUndefined();
    expect(button(w,'授权并开始下一题')).toBeUndefined();
    await button(w,'重试保存原录音').trigger('click');await ready(w);
    expect(local.snapshot()).toMatchObject({phase:'INTERRUPTED',segmentId:originalSegmentId,reason:'LOCAL_WRITE_FAILED',savedChunks:1,queuedChunks:0});
    expect(w.text()).toContain('采集有间断');expect(w.text()).not.toContain('整场已可靠提交');
    const journal=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    try{const saved=await journal.read(scope,originalSegmentId);expect(saved.segment).toMatchObject({state:'INTERRUPTED',chunkCount:1,interruption:'LOCAL_WRITE_FAILED'});
      expect(await saved.chunks[0].blob.text()).toBe('original retained recovery bytes');}finally{journal.close();}
    expect(await originals()).toEqual(before);expect(track.stop).toHaveBeenCalledTimes(1);expect(recorder.start).toHaveBeenCalledTimes(1);expect(recorder.stop).toHaveBeenCalledTimes(1);
    expect(requestPreparedDevice).toHaveBeenCalledTimes(1);expect(startAuthorizedAnswerCapture).toHaveBeenCalledTimes(1);expect(api.startPreparedAttempt).toHaveBeenCalledTimes(1);expect(api.startPreparedQuestion).toHaveBeenCalledTimes(1);
    expect(api.endPreparedAnswer).not.toHaveBeenCalled();expect(uploadMocks.submitAttempt).not.toHaveBeenCalled();
  });
  it('same-page save recovery keeps the original writer rather than reacquiring or stealing its lock',async()=>{
    const held=new Set<string>(),lockRequests=vi.fn(async(key:string,_options:unknown,callback:(lock:object|null)=>Promise<void>|void)=>{
      if(held.has(key))return callback(null);held.add(key);try{return await callback({});}finally{held.delete(key);}
    });
    Object.defineProperty(navigator,'locks',{configurable:true,value:{request:lockRequests}});
    const track=new RetainedTrack(),recorder=new RetainedRecorder(),stream={getTracks:()=>[track],getAudioTracks:()=>[track],getVideoTracks:()=>[]} as unknown as MediaStream;
    // Use the production writer lease; only the native lock manager/recorder/HTTP
    // are controlled. No lock stealing, a second device or cloud operation.
    const environment:RecorderEnvironment={supports:()=>true,create:()=>recorder as unknown as MediaRecorder,now:()=>Date.now()};
    let local!:PreparedAnswerCapture;
    vi.mocked(requestPreparedDevice).mockResolvedValue(stream);
    vi.mocked(startAuthorizedAnswerCapture).mockImplementation(async input=>{
      local=await PreparedAnswerCapture.start({journal:input.journal,scope:input.scope,ordinal:input.ordinal,stream:input.stream},environment);return local;
    });
    const originalGrant=grant();originalGrant.answerDeadline=new Date(Date.parse(originalGrant.startedAt)+120000).toISOString();
    vi.mocked(api.startPreparedQuestion).mockResolvedValue({data:originalGrant});
    const w=render();await active(w);recorder.data('original saved prefix');await vi.waitFor(()=>expect(local.snapshot().savedChunks).toBe(1));
    vi.spyOn(PreparedAudioJournal.prototype,'append').mockRejectedValueOnce(new Error('controlled original tail write failure'));
    recorder.data('original retained tail');await vi.waitFor(()=>expect(local.snapshot().phase).toBe('LOCAL_SAVE_FAILED'));await flushPromises();
    const before=await originals(),originalSegmentId=local.snapshot().segmentId,recover=vi.spyOn(PreparedAnswerCapture,'recoverSaved');
    expect(held.size).toBe(1);expect(lockRequests).toHaveBeenCalledTimes(1);
    vi.mocked(api.getCurrentPreparedAttempt).mockResolvedValue({data:attempt()});vi.mocked(api.getCurrentPreparedQuestion).mockResolvedValue({data:originalGrant});
    await button(w,'重新核验原作答').trigger('click');await ready(w);
    expect(recover).not.toHaveBeenCalled();expect(lockRequests).toHaveBeenCalledTimes(1);expect(held.size).toBe(1);
    expect(w.text()).not.toContain('另一个页面持有');expect(button(w,'重试保存原录音')).toBeDefined();
    await button(w,'重试保存原录音').trigger('click');await ready(w);await vi.waitFor(()=>expect(held.size).toBe(0));
    expect(local.snapshot()).toMatchObject({phase:'INTERRUPTED',segmentId:originalSegmentId,savedChunks:2,queuedChunks:0,reason:'LOCAL_WRITE_FAILED'});
    const journal=new PreparedAudioJournal({origin:location.origin,studentId:12,deploymentId:'prepared-local'},factory);
    try{const saved=await journal.read({assessmentId:8,attemptId:originalGrant.attemptId,answerId:originalGrant.answerId},originalSegmentId);
      expect(saved.segment).toMatchObject({state:'INTERRUPTED',chunkCount:2,interruption:'LOCAL_WRITE_FAILED'});
      expect(await Promise.all(saved.chunks.map(chunk=>chunk.blob.text()))).toEqual(['original saved prefix','original retained tail']);}finally{journal.close();}
    expect(await originals()).toEqual(before);expect(track.stop).toHaveBeenCalledTimes(1);expect(recorder.start).toHaveBeenCalledTimes(1);expect(recorder.stop).toHaveBeenCalledTimes(1);
    expect(requestPreparedDevice).toHaveBeenCalledTimes(1);expect(startAuthorizedAnswerCapture).toHaveBeenCalledTimes(1);
    expect(api.startPreparedAttempt).toHaveBeenCalledTimes(1);expect(api.startPreparedQuestion).toHaveBeenCalledTimes(1);
    expect(api.endPreparedAnswer).not.toHaveBeenCalled();expect(uploadMocks.submitAttempt).not.toHaveBeenCalled();
  });
});
