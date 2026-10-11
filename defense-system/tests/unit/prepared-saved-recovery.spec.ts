// @vitest-environment node
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {IDBFactory} from 'fake-indexeddb';
import {PreparedAudioJournal,type AnswerScope} from '../../src/prepared/localAudioJournal';
import {PreparedAnswerCapture,type RecorderEnvironment} from '../../src/prepared/answerCapture';
import {acquireCaptureWriter,WriterError,type WriterEnvironment} from '../../src/prepared/captureWriter';
let journal:PreparedAudioJournal,factory:IDBFactory,scope:AnswerScope,id:string,held:Set<string>,environment:RecorderEnvironment;
const identity={origin:'http://127.0.0.1:13015',studentId:12,deploymentId:'prepared-local'};
beforeEach(async()=>{
  factory=new IDBFactory();journal=new PreparedAudioJournal(identity,factory);scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};id=crypto.randomUUID();held=new Set();
  const locks:WriterEnvironment={secure:true,request:async(key,_o,cb)=>{
    if(held.has(key))return cb(null);held.add(key);try{await cb({});}finally{held.delete(key);}
  }};
  environment={supports:vi.fn(),create:vi.fn(),now:()=>99999,writer:key=>acquireCaptureWriter(key,locks)};
  await journal.begin(scope,{id,ordinal:1,mimeType:'audio/webm',startedAt:1000});await journal.append(scope,id,0,new Blob(['original bytes'],{type:'audio/webm'}));
});
afterEach(()=>{journal.close();vi.useRealTimers();vi.restoreAllMocks();});
const recover=()=>PreparedAnswerCapture.recoverSaved({journal,scope,segmentId:id},environment);
describe('saved terminal recovery through actual journal and writer guard — controlled bytes, NOT server grant',()=>{
  it.each([null,'DEVICE_ENDED' as const])('recovers only the original durable terminal %s without microphone/encoder/clock access',async(reason)=>{
    await journal.prepareTerminal(scope,id,{expectedChunks:1,endedAt:2000,reason});const clock=vi.spyOn(environment,'now');
    expect(await recover()).toMatchObject({phase:reason?'INTERRUPTED':'SEALED',segmentId:id,startedAt:1000,endedAt:2000,reason,savedChunks:1,queuedChunks:0});
    expect(environment.create).not.toHaveBeenCalled();expect(environment.supports).not.toHaveBeenCalled();expect(clock).not.toHaveBeenCalled();await vi.waitFor(()=>expect(held.size).toBe(0));
  });
  it('an OPEN prefix remains recovery-required, not a false successful seal or new answer',async()=>{
    await expect(recover()).rejects.toMatchObject({code:'RECOVERY_REQUIRED'});expect((await journal.read(scope,id)).segment.state).toBe('OPEN');await vi.waitFor(()=>expect(held.size).toBe(0));
  });
  it('another active capture cannot be bypassed by recovery; no journal mutation occurs',async()=>{
    await journal.prepareTerminal(scope,id,{expectedChunks:1,endedAt:2000,reason:null});const key=await journal.captureWriterKey(scope);const lease=await environment.writer!(key);const replay=vi.spyOn(journal,'recoverTerminal');
    await expect(recover()).rejects.toMatchObject({code:'ANOTHER_WRITER'});expect(replay).not.toHaveBeenCalled();expect((await journal.list(scope))[0].state).toBe('OPEN');lease.release();
  });
  it('snapshots original scope and segment id before async acquisition and replay',async()=>{
    await journal.prepareTerminal(scope,id,{expectedChunks:1,endedAt:2000,reason:null});const original={...scope};const input={journal,scope,segmentId:id};const pending=PreparedAnswerCapture.recoverSaved(input,environment);
    scope.answerId=crypto.randomUUID();input.segmentId=crypto.randomUUID();expect(await pending).toMatchObject({phase:'SEALED',segmentId:id,endedAt:2000});
    expect((await journal.read(original,id)).segment.state).toBe('SEALED');expect(await journal.list(scope)).toEqual([]);
  });
  it('unavailable protection cannot access saved content or leak platform errors',async()=>{
    environment.writer=async()=>{throw new WriterError('UNAVAILABLE');};const replay=vi.spyOn(journal,'recoverTerminal');
    await expect(recover()).rejects.toMatchObject({code:'WRITER_UNAVAILABLE'});expect(replay).not.toHaveBeenCalled();
  });
  it('lost protection during restoration cannot announce successful exclusive recovery',async()=>{
    await journal.prepareTerminal(scope,id,{expectedChunks:1,endedAt:2000,reason:null});let lost!:()=>void;const release=vi.fn();
    environment.writer=async()=>({release,onLost:listener=>{lost=listener;return ()=>{};}});
    const original=journal.recoverTerminal.bind(journal);vi.spyOn(journal,'recoverTerminal').mockImplementationOnce(async(...args)=>{lost();return original(...args);});
    await expect(recover()).rejects.toMatchObject({code:'WRITER_UNAVAILABLE'});expect(release).toHaveBeenCalledTimes(1);
    expect((await journal.read(scope,id)).segment.endedAt).toBe(2000); // Original transaction may commit; query original state, never create another recording.
  });
  it('failed replay releases protection only after the original operation settles and returns bounded errors',async()=>{
    vi.spyOn(journal,'recoverTerminal').mockRejectedValueOnce(new Error('private details'));
    await expect(recover()).rejects.toMatchObject({code:'LOCAL_SAVE_FAILED',message:'本机保存失败，请保留原页面和已存内容'});await vi.waitFor(()=>expect(held.size).toBe(0));
    expect((await journal.list(scope))[0].state).toBe('OPEN');
  });
  it('a stalled original replay has a bounded result but retains protection until the original transaction drains',async()=>{
    await journal.prepareTerminal(scope,id,{expectedChunks:1,endedAt:2000,reason:null});const real=journal.recoverTerminal.bind(journal);let release!:()=>void;
    vi.spyOn(journal,'recoverTerminal').mockImplementationOnce(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    const pending=recover(),rejected=expect(pending).rejects.toMatchObject({code:'LOCAL_SAVE_FAILED'});await vi.waitFor(()=>expect(release).toBeTypeOf('function'));await rejected;
    expect(held.size).toBe(1);await expect(recover()).rejects.toMatchObject({code:'ANOTHER_WRITER'});
    release();await vi.waitFor(()=>expect(held.size).toBe(0));expect((await recover()).phase).toBe('SEALED');
  },10000);
  it('invalid commands fail before protection or content access',async()=>{
    const writer=vi.spyOn(environment,'writer');await expect(PreparedAnswerCapture.recoverSaved(null as never,environment)).rejects.toMatchObject({code:'INVALID_INPUT'});
    await expect(PreparedAnswerCapture.recoverSaved({journal,scope,segmentId:'not-uuid'},environment)).rejects.toMatchObject({code:'INVALID_INPUT'});expect(writer).not.toHaveBeenCalled();
  });
});
