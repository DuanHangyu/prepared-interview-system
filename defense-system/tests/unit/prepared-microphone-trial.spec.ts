// @vitest-environment node
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {IDBFactory} from 'fake-indexeddb';
import {inspectTrialAudio,runMicrophoneTrial,type TrialEnvironment} from '../../src/prepared/microphoneTrial';
import {PreparedAudioJournal,LocalAudioError} from '../../src/prepared/localAudioJournal';
import {PreparedAnswerCapture} from '../../src/prepared/answerCapture';
const identity={origin:'http://127.0.0.1:13015',deploymentId:'prepared-local',studentId:12,assessmentId:8};
let environment:TrialEnvironment,controller:AbortController,stop:ReturnType<typeof vi.fn>,captureStop:ReturnType<typeof vi.fn<() => Promise<any>>>;
beforeEach(()=>{
  controller=new AbortController();stop=vi.fn();const stream={getTracks:()=>[{stop}]} as unknown as MediaStream;
  captureStop=vi.fn<() => Promise<any>>();
  environment={factory:new IDBFactory(),acquire:async()=>stream,inspect:async()=>({durationSeconds:6,signalDetected:true,peak:0.1}),
    capture:async(input)=>{
      const id=crypto.randomUUID();await input.journal.begin(input.scope,{id,ordinal:1,mimeType:'audio/webm;codecs=opus',startedAt:1000});
      await input.journal.append(input.scope,id,0,new Blob(['synthetic trial bytes'],{type:'audio/webm;codecs=opus'}));
      captureStop.mockImplementation(async()=>{await input.journal.seal(input.scope,id,1,7000);return {phase:'SEALED',segmentId:id} as never;});
      return {stop:captureStop,interrupt:vi.fn(async()=>({phase:'INTERRUPTED'} as never)),snapshot:()=>({segmentId:id,phase:'RECORDING',savedChunks:1} as never)};
    }};
});
afterEach(()=>{vi.useRealTimers();vi.unstubAllGlobals();});
async function finish<T>(result:Promise<T>){await vi.waitFor(()=>expect(captureStop).not.toHaveBeenCalled());await vi.advanceTimersByTimeAsync(6000);return result;}
describe('disposable microphone trial — substituted source/decoder, NOT physical device acceptance',()=>{
  it('checks actual local persisted bytes and returns a preview, then deletes only trial scratch',async()=>{
    vi.useFakeTimers({toFake:['setTimeout','clearTimeout','setInterval','clearInterval']});const progress=vi.fn();
    const result=runMicrophoneTrial(identity,controller.signal,progress,environment);await vi.advanceTimersByTimeAsync(0);
    await vi.waitFor(()=>expect(progress).toHaveBeenCalledWith(expect.objectContaining({phase:'RECORDING'})));
    const trial=await finish(result);expect(await trial.blob.text()).toBe('synthetic trial bytes');expect(trial.signalDetected).toBe(true);expect(stop).toHaveBeenCalled();
    const db=await new Promise<IDBDatabase>((resolve,reject)=>{const r=environment.factory!.open('prepared-interview-preflight-v1',1);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
    const count=await new Promise<number>(resolve=>{const r=db.transaction('segments').objectStore('segments').count();r.onsuccess=()=>resolve(r.result);});db.close();expect(count).toBe(0);
  });
  it('permission failure retains a bounded device error and cannot become an approved trial',async()=>{
    environment.acquire=async()=>{throw Object.assign(new Error('拒绝'),{name:'DeviceAccessError',code:'PERMISSION_DENIED'});};
    await expect(runMicrophoneTrial(identity,controller.signal,()=>{},environment)).rejects.toMatchObject({code:'PERMISSION_DENIED'});
  });
  it('cancellation while recording closes devices and cannot publish a late preview',async()=>{
    vi.useFakeTimers({toFake:['setTimeout','clearTimeout','setInterval','clearInterval']});const progress=vi.fn();
    const result=runMicrophoneTrial(identity,controller.signal,progress,environment);const rejection=expect(result).rejects.toMatchObject({code:'CANCELLED'});
    await vi.waitFor(()=>expect(progress).toHaveBeenCalledWith(expect.objectContaining({phase:'RECORDING'})));controller.abort();await rejection;expect(stop).toHaveBeenCalled();
  });
  it('invalid identity or assessment never asks for permission',async()=>{
    const acquire=vi.fn(environment.acquire);environment.acquire=acquire;
    await expect(runMicrophoneTrial({...identity,assessmentId:0},controller.signal,()=>{},environment)).rejects.toMatchObject({code:'INVALID'});expect(acquire).not.toHaveBeenCalled();
  });
  it('abort before startup does not touch devices or storage',async()=>{
    controller.abort();const acquire=vi.fn(environment.acquire);environment.acquire=acquire;
    await expect(runMicrophoneTrial(identity,controller.signal,()=>{},environment)).rejects.toMatchObject({code:'CANCELLED'});expect(acquire).not.toHaveBeenCalled();
  });
  it('invalid local owner and unavailable storage fail before device access',async()=>{
    const acquire=vi.fn(environment.acquire);environment.acquire=acquire;
    await expect(runMicrophoneTrial({...identity,studentId:0},controller.signal,()=>{},environment)).rejects.toMatchObject({code:'INVALID'});
    environment.factory=undefined;vi.stubGlobal('indexedDB',undefined);
    await expect(runMicrophoneTrial(identity,controller.signal,()=>{},environment)).rejects.toMatchObject({code:'STORAGE_FAILED'});expect(acquire).not.toHaveBeenCalled();
  });
  it('cancellation of unresolved permission closes a late acquired stream',async()=>{
    let allow!:(stream:MediaStream)=>void;environment.acquire=()=>new Promise(resolve=>{allow=resolve;});
    const result=runMicrophoneTrial(identity,controller.signal,()=>{},environment);const rejection=expect(result).rejects.toMatchObject({code:'CANCELLED'});
    controller.abort();await rejection;allow({getTracks:()=>[{stop}]} as never);await Promise.resolve();expect(stop).toHaveBeenCalled();
  });
  it('late capture startup cannot survive cancellation or produce a preview',async()=>{
    let started!:(capture:any)=>void;const interrupt=vi.fn(async()=>({phase:'INTERRUPTED'}));
    environment.capture=()=>new Promise(resolve=>{started=resolve;});const progress=vi.fn();
    const result=runMicrophoneTrial(identity,controller.signal,progress,environment);const rejected=expect(result).rejects.toMatchObject({code:'CANCELLED'});
    await vi.waitFor(()=>expect(started).toBeTypeOf('function'));controller.abort();await rejected;started({interrupt});await Promise.resolve();expect(interrupt).toHaveBeenCalled();expect(stop).toHaveBeenCalled();
  });
  it.each(['INTERRUPTED','LOCAL_SAVE_FAILED'])('abnormal stopped capture %s is never a complete trial',async(phase)=>{
    vi.useFakeTimers({toFake:['setTimeout','clearTimeout','setInterval','clearInterval']});const capturing=environment.capture!;
    environment.capture=async(input)=>{const c=await capturing(input);return {...c,stop:async()=>({phase} as never)};};
    const progress=vi.fn();const result=runMicrophoneTrial(identity,controller.signal,progress,environment);
    const rejected=expect(result).rejects.toMatchObject({code:phase==='LOCAL_SAVE_FAILED'?'STORAGE_FAILED':'CAPTURE_FAILED'});
    await vi.waitFor(()=>expect(progress).toHaveBeenCalledWith(expect.objectContaining({phase:'RECORDING'})));await vi.advanceTimersByTimeAsync(6000);await rejected;
  });
  it.each([new LocalAudioError('WRITE_FAILED'),new Error('private native path')])('capture startup errors are bounded: %s',async(error)=>{
    environment.capture=async()=>{throw error;};await expect(runMicrophoneTrial(identity,controller.signal,()=>{throw new Error('UI reporter failure');},environment)).rejects.toMatchObject({code:error instanceof LocalAudioError?'STORAGE_FAILED':'CAPTURE_FAILED'});expect(stop).toHaveBeenCalled();
  });
  it('scratch cleanup failure is bounded and never returns a successful trial',async()=>{
    vi.spyOn(PreparedAudioJournal.prototype,'discardPreflight').mockRejectedValueOnce(new Error('private storage path'));
    environment.acquire=async()=>{throw new Error('device');};
    await expect(runMicrophoneTrial(identity,controller.signal,()=>{},environment)).rejects.toMatchObject({code:'STORAGE_FAILED'});
  });
  it('default device/capture/decode wiring uses native adapters — controlled native boundaries, NOT real media',async()=>{
    vi.useFakeTimers({toFake:['setTimeout','clearTimeout','setInterval','clearInterval']});
    const nativeStream={getTracks:()=>[{stop}],getAudioTracks:()=>[{kind:'audio',readyState:'live',enabled:true}],getVideoTracks:()=>[]} as unknown as MediaStream;
    vi.stubGlobal('isSecureContext',true);vi.stubGlobal('navigator',{mediaDevices:{getUserMedia:vi.fn(async()=>nativeStream)}});
    vi.stubGlobal('AudioContext',function(){return {decodeAudioData:async()=>decoded(),close:async()=>{}};});
    const capturing=environment.capture!;vi.spyOn(PreparedAnswerCapture,'start').mockImplementation(async(input)=>await capturing(input) as PreparedAnswerCapture);
    const progress=vi.fn();const result=runMicrophoneTrial(identity,controller.signal,progress,{factory:environment.factory});
    await vi.waitFor(()=>expect(progress).toHaveBeenCalledWith(expect.objectContaining({phase:'RECORDING'})));await vi.advanceTimersByTimeAsync(6000);
    expect(await result).toMatchObject({signalDetected:true});expect(PreparedAnswerCapture.start).toHaveBeenCalled();expect(stop).toHaveBeenCalled();
  });
  it('unavailable native stream release is bounded during cancellation',async()=>{
    environment.acquire=async()=>({getTracks:()=>{throw new Error('device disappeared');}} as never);
    environment.capture=async()=>{controller.abort();throw new Error('native');};
    await expect(runMicrophoneTrial(identity,controller.signal,()=>{},environment)).rejects.toMatchObject({code:'CANCELLED'});
  });
});

function decoded(overrides:Partial<AudioBuffer>={}){return {duration:6,sampleRate:48000,numberOfChannels:1,length:12,getChannelData:()=>new Float32Array([0,0.1,0.2,-0.1,0,0]),...overrides} as AudioBuffer;}
describe('trial decode and sound check',()=>{
  it('requires a full decodable container and detects sound without claiming speech recognition',async()=>{
    const close=vi.fn(async()=>{});const decoder={decodeAudioData:vi.fn(async()=>decoded()),close};
    vi.stubGlobal('AudioContext',function(){return decoder;});
    expect(await inspectTrialAudio(new Blob(['synthetic container'],{type:'audio/webm;codecs=opus'}))).toMatchObject({durationSeconds:6,signalDetected:true,peak:expect.any(Number)});expect(close).toHaveBeenCalled();
  });
  it('silent decodable audio is not mistaken for working human voice',async()=>{
    const decoder={decodeAudioData:async()=>decoded({getChannelData:()=>new Float32Array(10)}),close:async()=>{}};
    expect(await inspectTrialAudio(new Blob(['test'],{type:'audio/webm'}),()=>decoder as never)).toMatchObject({signalDetected:false,peak:0});
  });
  it.each([{duration:0.5},{duration:30},{sampleRate:1},{numberOfChannels:8},{getChannelData:()=>new Float32Array([NaN])}])('invalid decoded media is refused with resources released: %s',async(value)=>{
    const close=vi.fn(async()=>{});const decoder={decodeAudioData:async()=>decoded(value),close};
    await expect(inspectTrialAudio(new Blob(['test'],{type:'audio/webm'}),()=>decoder as never)).rejects.toMatchObject({code:'DECODE_FAILED'});expect(close).toHaveBeenCalled();
  });
  it('empty, overlarge or wrong media is refused before a decoder is opened',async()=>{
    const create=vi.fn();for(const blob of [new Blob([]),new Blob(['data'],{type:'video/webm'}),new Blob([new Uint8Array(1024*1024+1)],{type:'audio/webm'})])
      await expect(inspectTrialAudio(blob,create)).rejects.toMatchObject({code:'DECODE_FAILED'});expect(create).not.toHaveBeenCalled();
  });
  it('unavailable decoder and native decode errors do not expose raw device details',async()=>{
    await expect(inspectTrialAudio(new Blob(['test'],{type:'audio/webm'}),()=>{throw new Error('private');})).rejects.toMatchObject({code:'DECODE_FAILED'});
    await expect(inspectTrialAudio(new Blob(['test'],{type:'audio/webm'}),()=>({decodeAudioData:async()=>{throw new Error('private');},close:async()=>{}} as never))).rejects.toMatchObject({code:'DECODE_FAILED'});
  });
});
