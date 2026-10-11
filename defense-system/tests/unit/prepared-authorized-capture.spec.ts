// @vitest-environment node
import {IDBFactory} from 'fake-indexeddb';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {PreparedAudioJournal} from '@/prepared/localAudioJournal';
import {PreparedQuestionDeadline} from '@/prepared/questionDeadline';
import {startAuthorizedAnswerCapture} from '@/prepared/authorizedCapture';
import {acquireCaptureWriter} from '@/prepared/captureWriter';
import type {RecorderEnvironment} from '@/prepared/answerCapture';
import type {PreparedQuestionGrant} from '@/api/preparedAttempt';
class Track extends EventTarget{kind='audio';readyState='live';enabled=true;muted=false;stop=vi.fn(()=>{this.readyState='ended';});}
class Recorder extends EventTarget{
  state:RecordingState='inactive';mimeType='audio/webm;codecs=opus';
  start=vi.fn(()=>{this.state='recording';this.dispatchEvent(new Event('start'));});
  stop=vi.fn(()=>{this.state='inactive';this.dispatchEvent(Object.assign(new Event('dataavailable'),{data:new Blob(['original tail'],{type:this.mimeType})}));this.dispatchEvent(new Event('stop'));});
}
let journal:PreparedAudioJournal,track:Track,recorder:Recorder,env:RecorderEnvironment,scope:{assessmentId:number;attemptId:string;answerId:string},grant:PreparedQuestionGrant,elapsed:number,stream:MediaStream;
beforeEach(()=>{
  elapsed=0;journal=new PreparedAudioJournal({origin:'http://127.0.0.1:13015',studentId:12,deploymentId:'prepared-local'},new IDBFactory());
  scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};
  grant={...scope,paperVersionId:crypto.randomUUID(),questionId:crypto.randomUUID(),ordinal:1,body:'授权题',state:'RECORDING',answerSeconds:120,startedAt:'2026-10-05T00:00:00Z',answerDeadline:'2026-10-05T00:02:00Z',serverTime:'2026-10-05T00:01:59Z',mayRecord:true};
  track=new Track();recorder=new Recorder();stream={getTracks:()=>[track],getAudioTracks:()=>[track],getVideoTracks:()=>[]} as unknown as MediaStream;
  env={supports:()=>true,create:()=>recorder as unknown as MediaRecorder,now:()=>1000+elapsed,writer:key=>acquireCaptureWriter(key,{secure:true,request:async(_k,_o,cb)=>cb({})})};
});
afterEach(()=>{journal.close();vi.useRealTimers();});
const deadline=()=>new PreparedQuestionDeadline(grant,scope,{mono:0,wall:0},{mono:()=>elapsed,wall:()=>elapsed});
const start=(d=deadline())=>startAuthorizedAnswerCapture({journal,scope,ordinal:1,stream,deadline:d},env);
describe('authorized native capture timing — controlled recorder, not physical device acceptance',()=>{
  it('stops at the original deadline, seals original tail and does not start another recording',async()=>{
    const capture=await start();expect(capture.snapshot().phase).toBe('RECORDING');elapsed=1000;
    await vi.waitFor(()=>expect(recorder.stop).toHaveBeenCalledTimes(1));
    const result=await capture.stop();expect(result.phase).toBe('SEALED');
    expect(await (await journal.playable(scope,result.segmentId)).text()).toBe('original tail');expect(recorder.start).toHaveBeenCalledTimes(1);
  });
  it('an expired grant releases the microphone before any segment or recorder creation',async()=>{
    const d=deadline();elapsed=1000;await expect(start(d)).rejects.toMatchObject({code:'AUTHORIZATION_EXPIRED'});
    expect(recorder.start).not.toHaveBeenCalled();expect(track.stop).toHaveBeenCalled();expect(await journal.list(scope)).toEqual([]);
  });
  it('waiting for original local transaction consumes time and cannot start recording late',async()=>{
    const begin=journal.begin.bind(journal);let release!:()=>void;
    vi.spyOn(journal,'begin').mockImplementation(async(...args)=>{await new Promise<void>(r=>{release=r;});return begin(...args);});
    const starting=start();const failed=expect(starting).rejects.toBeDefined();
    await vi.waitFor(()=>expect(release).toBeTypeOf('function'));elapsed=1000;
    await vi.waitFor(()=>expect(track.stop).toHaveBeenCalled());release();await failed;
    expect(recorder.start).not.toHaveBeenCalled();expect((await journal.list(scope))[0].state).toBe('INTERRUPTED');
  });
  it('manual ending cancels the monitor while preserving one terminal command',async()=>{
    const capture=await start();const original=await capture.stop();elapsed=1000;
    await new Promise(r=>setTimeout(r,150));expect(await capture.stop()).toEqual(original);expect(recorder.stop).toHaveBeenCalledTimes(1);
  });
});
