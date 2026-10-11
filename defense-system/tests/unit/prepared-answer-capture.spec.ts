// @vitest-environment node
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {IDBFactory} from 'fake-indexeddb';
import {LocalAudioError,PreparedAudioJournal,type AnswerScope} from '../../src/prepared/localAudioJournal';
import {PreparedAnswerCapture,type RecorderEnvironment} from '../../src/prepared/answerCapture';
import {acquireCaptureWriter,type WriterEnvironment} from '../../src/prepared/captureWriter';

class Track extends EventTarget {
  readonly kind='audio';readyState='live';enabled=true;muted=false;stop=vi.fn(()=>{this.readyState='ended';});
}
class Recorder extends EventTarget {
  state:RecordingState='inactive';mimeType='audio/webm;codecs=opus';start=vi.fn(()=>{this.state='recording';this.dispatchEvent(new Event('start'));});
  stop=vi.fn(()=>{this.state='inactive';});
  data(text:string|Blob){this.dispatchEvent(Object.assign(new Event('dataavailable'),{data:typeof text==='string'?new Blob([text],{type:this.mimeType}):text}));}
  ended(){this.state='inactive';this.dispatchEvent(new Event('stop'));}
}
let journal:PreparedAudioJournal,scope:AnswerScope,track:Track,stream:MediaStream,recorder:Recorder,environment:RecorderEnvironment,now:number,held:Set<string>;
function audioStream(owned:Track){return {getTracks:()=>[owned],getAudioTracks:()=>[owned],getVideoTracks:()=>[]} as unknown as MediaStream;}
beforeEach(()=>{
  journal=new PreparedAudioJournal({origin:'http://127.0.0.1:13015',deploymentId:'prepared-local',studentId:12},new IDBFactory());
  scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};track=new Track();recorder=new Recorder();now=1000;
  stream=audioStream(track);
  held=new Set();
  const writerEnvironment:WriterEnvironment={secure:true,request:async(key,_options,callback)=>{
    if(held.has(key))return callback(null);held.add(key);try{await callback({});}finally{held.delete(key);}
  }};
  environment={supports:()=>true,create:()=>recorder as unknown as MediaRecorder,now:()=>now,writer:key=>acquireCaptureWriter(key,writerEnvironment)};
});
afterEach(()=>{journal.close();vi.useRealTimers();});
const start=()=>PreparedAnswerCapture.start({journal,scope,ordinal:1,stream},environment);
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));};
async function waitSaved(capture:PreparedAnswerCapture,count:number){await vi.waitFor(()=>expect(capture.snapshot().savedChunks).toBe(count));}

describe('answer capture — controlled recorder events, NOT physical microphone acceptance',()=>{
  it('checks durable fault evidence under the original writer before starting a second native segment',async()=>{
    const original=await start();recorder.data('original prefix');await waitSaved(original,1);now=1500;
    const interrupted=original.interrupt('DEVICE_ENDED');recorder.data('original tail');recorder.ended();await interrupted;
    const before=await journal.read(scope,original.snapshot().segmentId);track=new Track();recorder=new Recorder();now=1600;
    const realVerify=journal.verifyContinuation.bind(journal),verify=vi.spyOn(journal,'verifyContinuation').mockImplementation(async(...args)=>{
      expect(held.size).toBe(1);expect(recorder.start).not.toHaveBeenCalled();
      return realVerify(...args);
    });
    const continued=await PreparedAnswerCapture.start({journal,scope,ordinal:2,stream:audioStream(track)},environment);
    expect(verify).toHaveBeenCalledWith(scope,2,1600);recorder.data('continuation');await waitSaved(continued,1);now=2000;
    const ending=continued.stop();recorder.data('new tail');recorder.ended();expect((await ending).phase).toBe('SEALED');
    expect(await journal.read(scope,before.segment.id)).toEqual(before);
    expect((await journal.list(scope)).map(s=>[s.ordinal,s.state])).toEqual([[1,'INTERRUPTED'],[2,'SEALED']]);
  });
  it('timed-out fault verification cannot create a late segment, restart the recorder or release an in-flight original writer',async()=>{
    const first=await start(),interrupted=first.interrupt('DEVICE_ENDED');recorder.ended();await interrupted;
    track=new Track();recorder=new Recorder();now=2000;let release!:()=>void;
    const verify=vi.spyOn(journal,'verifyContinuation').mockImplementationOnce(()=>new Promise<void>(resolve=>{release=resolve;}));
    vi.useFakeTimers({toFake:['setTimeout','clearTimeout']});
    const starting=PreparedAnswerCapture.start({journal,scope,ordinal:2,stream:audioStream(track)},environment);const rejected=expect(starting).rejects.toMatchObject({code:'LOCAL_SAVE_FAILED'});
    await vi.waitFor(()=>expect(verify).toHaveBeenCalledTimes(1));await vi.advanceTimersByTimeAsync(5000);await rejected;
    expect(held.size).toBe(1);expect(recorder.start).not.toHaveBeenCalled();expect((await journal.list(scope))).toHaveLength(1);
    vi.useRealTimers();release();await vi.waitFor(()=>expect(held.size).toBe(0));expect((await journal.list(scope))).toHaveLength(1);expect(track.stop).toHaveBeenCalledTimes(1);
  });
  it('starts only after local segment commit and snapshots command scope before asynchronous work',async()=>{
    const real=journal.begin.bind(journal);let release!:()=>void;
    vi.spyOn(journal,'begin').mockImplementation(async(s,input)=>{await new Promise<void>(r=>{release=r;});return real(s,input);});
    const original={...scope};const starting=start();scope.answerId=crypto.randomUUID();
    expect(recorder.start).not.toHaveBeenCalled();await vi.waitFor(()=>expect(release).toBeTypeOf('function'));release();const capture=await starting;
    expect(recorder.start).toHaveBeenCalledWith(1000);expect(capture.snapshot().phase).toBe('RECORDING');
    recorder.data('prefix');await waitSaved(capture,1);now=2000;
    const completion=capture.stop();recorder.data('tail');recorder.ended();expect((await completion).phase).toBe('SEALED');
    expect(await (await journal.playable(original,capture.snapshot().segmentId)).text()).toBe('prefixtail');
    expect(await journal.list(scope)).toEqual([]);
  });
  it('waits for final data event AND every commit before sealing, and repeated stop uses the original finish',async()=>{
    const capture=await start();recorder.data('prefix');await waitSaved(capture,1);
    const real=journal.append.bind(journal);let release!:()=>void;
    vi.spyOn(journal,'append').mockImplementation(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    now=2000;let settled=false;const completion=capture.stop().then(result=>{settled=true;return result;});
    now=9999;expect(capture.stop()).toBe(capture.stop());recorder.data('tail');recorder.ended();await flush();
    expect(settled).toBe(false);expect((await journal.list(scope))[0].state).toBe('OPEN');release();
    expect(await completion).toMatchObject({phase:'SEALED',savedChunks:2,queuedChunks:0,endedAt:2000});
    expect(recorder.stop).toHaveBeenCalledTimes(1);expect(track.stop).toHaveBeenCalledTimes(1);
    expect(await (await journal.playable(scope,capture.snapshot().segmentId)).text()).toBe('prefixtail');
  });
  it('serializes event writes, including a zero-byte event, without replacing original bytes',async()=>{
    const capture=await start();recorder.data(new Blob([]));recorder.data('A');recorder.data('B');recorder.data('C');
    const completion=capture.stop();recorder.data('D');recorder.ended();await completion;
    const result=await journal.read(scope,capture.snapshot().segmentId);expect(result.chunks.map(c=>c.index)).toEqual([0,1,2,3]);
    expect(await (await journal.playable(scope,result.segment.id)).text()).toBe('ABCD');
  });
  it('empty capture is interrupted evidence, never a complete answer',async()=>{
    const capture=await start();const completion=capture.stop();recorder.data(new Blob([]));recorder.ended();
    expect(await completion).toMatchObject({phase:'INTERRUPTED',reason:'CAPTURE_ERROR',savedChunks:0});
    expect((await journal.list(scope))[0]).toMatchObject({state:'INTERRUPTED',chunkCount:0});
  });
  it.each(['ended','mute'])('device %s stops capture while preserving its committed prefix and final event',async(event)=>{
    const capture=await start();recorder.data('prefix');await waitSaved(capture,1);now=2000;
    track.dispatchEvent(new Event(event));expect(capture.snapshot().phase).not.toBe('RECORDING');
    recorder.data('tail');recorder.ended();const result=await capture.stop();
    expect(result).toMatchObject({phase:'INTERRUPTED',savedChunks:2,reason:event==='ended'?'DEVICE_ENDED':'CAPTURE_ERROR'});
    expect((await journal.read(scope,result.segmentId)).chunks).toHaveLength(2);
    await expect(journal.playable(scope,result.segmentId)).rejects.toMatchObject({code:'CONFLICT'});
  });
  it('unexpected stop and recorder error are faults, not successful answers',async()=>{
    const capture=await start();recorder.data('prefix');await waitSaved(capture,1);recorder.dispatchEvent(new Event('error'));
    recorder.data('tail');recorder.ended();expect(await capture.stop()).toMatchObject({phase:'INTERRUPTED',reason:'CAPTURE_ERROR'});
  });
  it('natural device loss without ended notification is still not silently sealed',async()=>{
    const capture=await start();recorder.data('prefix');track.stop();recorder.ended();
    expect(await capture.stop()).toMatchObject({phase:'INTERRUPTED',reason:'DEVICE_ENDED'});
  });
  it('local save failure retains the original bounded queue and retries bytes, not recording',async()=>{
    const capture=await start();recorder.data('prefix');await waitSaved(capture,1);
    vi.spyOn(journal,'append').mockRejectedValueOnce(new LocalAudioError('WRITE_FAILED'));
    recorder.data('original tail');await vi.waitFor(()=>expect(capture.snapshot().phase).toBe('LOCAL_SAVE_FAILED'));
    recorder.data('last event');recorder.ended();const failed=await capture.stop();
    expect(failed).toMatchObject({phase:'LOCAL_SAVE_FAILED',reason:'LOCAL_WRITE_FAILED',savedChunks:1,queuedChunks:2});
    expect((await journal.list(scope))[0]).toMatchObject({state:'OPEN',chunkCount:1});
    now=99999;const [a,b]=await Promise.all([capture.retrySave(),capture.retrySave()]);expect(a).toEqual(b);
    expect(a).toMatchObject({phase:'INTERRUPTED',savedChunks:3,queuedChunks:0,endedAt:1000});
    expect(recorder.start).toHaveBeenCalledTimes(1);
    const saved=await journal.read(scope,a.segmentId);expect(await new Blob(saved.chunks.map(c=>c.blob)).text()).toBe('prefixoriginal taillast event');
  });
  it('metadata commit failure is not announced as complete and retries the exact original seal command',async()=>{
    const capture=await start();recorder.data('audio');await waitSaved(capture,1);
    vi.spyOn(journal,'seal').mockRejectedValueOnce(new LocalAudioError('WRITE_FAILED'));
    now=2000;const completion=capture.stop();recorder.ended();
    expect(await completion).toMatchObject({phase:'LOCAL_SAVE_FAILED',savedChunks:1});
    expect(await capture.retrySave()).toMatchObject({phase:'SEALED',reason:null,endedAt:2000});
    expect((await journal.list(scope))[0].state).toBe('SEALED');
  });
  it('does not drain into a different owner handle when the original journal closes',async()=>{
    const capture=await start();recorder.data('prefix');await waitSaved(capture,1);journal.close();recorder.data('tail');
    await vi.waitFor(()=>expect(capture.snapshot().phase).toBe('LOCAL_SAVE_FAILED'));recorder.ended();
    expect(await capture.stop()).toMatchObject({phase:'LOCAL_SAVE_FAILED',savedChunks:1,queuedChunks:1});
    expect(await capture.retrySave()).toMatchObject({phase:'LOCAL_SAVE_FAILED'});
  });
  it('session change uses interruption, releases audio and does not provide deletion or restart operations',async()=>{
    const capture=await start();recorder.data('prefix');await waitSaved(capture,1);
    const completion=capture.interrupt('SESSION_CHANGED');recorder.data('tail');recorder.ended();
    expect(await completion).toMatchObject({phase:'INTERRUPTED',reason:'SESSION_CHANGED'});
    expect((capture as unknown as {restart?:unknown;delete?:unknown}).restart).toBeUndefined();
    expect((capture as unknown as {delete?:unknown}).delete).toBeUndefined();
  });
  it('caps pending bytes and preserves the accepted prefix instead of growing memory without bound',async()=>{
    const capture=await start();const real=journal.append.bind(journal);let release!:()=>void;
    vi.spyOn(journal,'append').mockImplementationOnce(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    const blob=new Blob([new Uint8Array(2*1024*1024)],{type:recorder.mimeType});
    for(let i=0;i<5;i++)recorder.data(blob);
    expect(capture.snapshot()).toMatchObject({reason:'LOCAL_WRITE_FAILED',queuedChunks:4,queuedBytes:8*1024*1024});
    recorder.ended();release();expect(await capture.stop()).toMatchObject({phase:'INTERRUPTED',savedChunks:4,queuedChunks:0});
    expect((await journal.read(scope,capture.snapshot().segmentId)).segment.byteSize).toBe(8*1024*1024);
  });
  it('caps queued tiny chunks and rejects overlarge or wrongly labeled events without falsely completing',async()=>{
    const capture=await start();let release!:()=>void;const real=journal.append.bind(journal);
    vi.spyOn(journal,'append').mockImplementationOnce(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    for(let i=0;i<33;i++)recorder.data('x');expect(capture.snapshot().queuedChunks).toBe(32);
    recorder.ended();release();expect((await capture.stop()).phase).toBe('INTERRUPTED');
  });
  it.each([new Blob([new Uint8Array(8*1024*1024+1)],{type:'audio/webm;codecs=opus'}),new Blob(['not audio'],{type:'video/webm'})])('invalid event is reported and not written',async(blob)=>{
    const capture=await start();recorder.data(blob);recorder.ended();expect((await capture.stop()).phase).toBe('INTERRUPTED');
    expect((await journal.list(scope))[0].chunkCount).toBe(0);
  });
  it('fails closed if the recorder fails to send stop; late events cannot turn a timed out recording into success',async()=>{
    const capture=await start();vi.useFakeTimers({toFake:['setTimeout','clearTimeout']});const completion=capture.stop();await vi.advanceTimersByTimeAsync(5000);
    expect(await completion).toMatchObject({phase:'INTERRUPTED',reason:'CAPTURE_ERROR'});
    recorder.data('late');recorder.ended();expect((await journal.list(scope))[0]).toMatchObject({state:'INTERRUPTED',chunkCount:0});
  });
  it('stop exception and asynchronous repeated events do not leave fake recording indicators or devices on',async()=>{
    const capture=await start();recorder.data('audio');await waitSaved(capture,1);recorder.stop.mockImplementationOnce(()=>{throw new Error('private device information');});
    expect(await capture.stop()).toMatchObject({phase:'INTERRUPTED',reason:'CAPTURE_ERROR'});recorder.dispatchEvent(new Event('error'));track.dispatchEvent(new Event('ended'));
    expect(track.stop).toHaveBeenCalledTimes(1);
  });
  it('progress observers cannot break persistence and receive isolated metadata, not Blob data',async()=>{
    const capture=await start();const snapshots:unknown[]=[];const unsubscribe=capture.subscribe(value=>{snapshots.push(value);throw new Error('observer');});
    recorder.data('audio');await waitSaved(capture,1);unsubscribe();const completion=capture.stop();recorder.ended();await completion;
    expect(snapshots.length).toBeGreaterThan(0);expect(JSON.stringify(snapshots)).not.toContain('audio');
    const snapshot=capture.snapshot();snapshot.savedChunks=999;expect(capture.snapshot().savedChunks).toBe(1);
  });
  it('retry while still recording is not a voluntary reset command',async()=>{
    const capture=await start();await expect(capture.retrySave()).rejects.toMatchObject({code:'NOT_STOPPED'});
    const completion=capture.stop();recorder.ended();await completion;
  });
  it('unsupported codec or missing usable audio fails boundedly and releases the owned stream',async()=>{
    environment.supports=()=>false;await expect(start()).rejects.toMatchObject({code:'UNSUPPORTED'});expect(track.stop).toHaveBeenCalledTimes(1);
    track=new Track();stream=audioStream(track);track.enabled=false;await expect(start()).rejects.toMatchObject({code:'DEVICE_UNAVAILABLE'});expect(track.stop).toHaveBeenCalledTimes(1);
  });
  it('never records camera tracks supplied alongside audio',async()=>{
    const camera=new Track();stream={getTracks:()=>[track,camera],getAudioTracks:()=>[track],getVideoTracks:()=>[camera]} as unknown as MediaStream;
    await expect(start()).rejects.toMatchObject({code:'DEVICE_UNAVAILABLE'});expect(recorder.start).not.toHaveBeenCalled();expect(camera.stop).toHaveBeenCalledTimes(1);
  });
  it('constructor and start exceptions are bounded and leave original segment interruption evidence',async()=>{
    environment.create=()=>{throw new Error('private hardware name');};await expect(start()).rejects.toMatchObject({code:'UNSUPPORTED'});
    track=new Track();stream=audioStream(track);environment.create=()=>recorder as unknown as MediaRecorder;recorder.start.mockImplementation(()=>{throw new Error('private hardware');});
    await expect(start()).rejects.toMatchObject({code:'START_FAILED'});expect((await journal.list(scope))[0].state).toBe('INTERRUPTED');
  });
  it('local begin conflict cannot open another recorder; the existing answer is untouched',async()=>{
    await journal.begin(scope,{id:crypto.randomUUID(),ordinal:1,mimeType:recorder.mimeType,startedAt:1000});
    await expect(start()).rejects.toMatchObject({code:'ALREADY_RECORDING'});expect(recorder.start).not.toHaveBeenCalled();expect((await journal.list(scope))[0].state).toBe('OPEN');
  });
  it('track end during the initial local transaction cannot start an inactive device',async()=>{
    const real=journal.begin.bind(journal);let release!:()=>void;
    vi.spyOn(journal,'begin').mockImplementation(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    const creating=start();await vi.waitFor(()=>expect(release).toBeTypeOf('function'));track.readyState='ended';track.dispatchEvent(new Event('ended'));release();
    await expect(creating).rejects.toMatchObject({code:'DEVICE_UNAVAILABLE'});expect(recorder.start).not.toHaveBeenCalled();expect((await journal.list(scope))[0].state).toBe('INTERRUPTED');
  });
  it('unexpected recorder stop with a still live track is capture-error evidence',async()=>{
    const capture=await start();recorder.data('prefix');recorder.ended();expect(await capture.stop()).toMatchObject({phase:'INTERRUPTED',reason:'CAPTURE_ERROR'});
  });
  it('successful completed captures are not re-saved or reopened by recovery requests',async()=>{
    const capture=await start();recorder.data('audio');const completion=capture.stop();recorder.ended();const result=await completion;
    expect(await capture.retrySave()).toEqual(result);expect(await capture.retrySave()).toEqual(result);
    expect(recorder.start).toHaveBeenCalledTimes(1);
  });
  it('malformed commands, unsupported actual codec and invalid local times never leak native errors',async()=>{
    await expect(PreparedAnswerCapture.start(null as never,environment)).rejects.toMatchObject({code:'INVALID_INPUT'});
    await expect(PreparedAnswerCapture.start({...{journal,scope,ordinal:1,stream},stream:{} as never},environment)).rejects.toMatchObject({code:'INVALID_INPUT'});
    environment.now=()=>NaN;await expect(start()).rejects.toMatchObject({code:'INVALID_INPUT'});
    track=new Track();stream=audioStream(track);environment.now=()=>1000;recorder.mimeType='video/webm';await expect(start()).rejects.toMatchObject({code:'UNSUPPORTED'});
  });
  it('does not report recording if native start returns without an active recorder',async()=>{
    recorder.start.mockImplementation(()=>{recorder.state='inactive';});await expect(start()).rejects.toMatchObject({code:'START_FAILED'});
    expect((await journal.list(scope))[0].state).toBe('INTERRUPTED');
  });
  it('begin write failure does not start audio or leave owned device resources on',async()=>{
    vi.spyOn(journal,'begin').mockRejectedValueOnce(new LocalAudioError('WRITE_FAILED'));
    await expect(start()).rejects.toMatchObject({code:'LOCAL_SAVE_FAILED'});expect(track.stop).toHaveBeenCalledTimes(1);expect(recorder.start).not.toHaveBeenCalled();
  });
  it('malformed event and invalid interruption reason cannot be treated as normal audio',async()=>{
    const capture=await start();expect(()=>capture.interrupt('DELETE_AND_RESTART' as never)).toThrowError(expect.objectContaining({code:'INVALID_INPUT'}));
    recorder.dispatchEvent(new Event('dataavailable'));recorder.ended();expect(await capture.stop()).toMatchObject({phase:'INTERRUPTED',reason:'CAPTURE_ERROR'});
  });
  it('wall-clock jumps never reset the original start time or become a server authorization',async()=>{
    const capture=await start();recorder.data('audio');now=-999;const completion=capture.stop();recorder.ended();
    expect(await completion).toMatchObject({startedAt:1000,endedAt:1000});
  });
  it('a stuck save has a bounded unconfirmed result; late commit may drain but cannot silently become successful',async()=>{
    const capture=await start();const real=journal.append.bind(journal);let release!:()=>void;
    const append=vi.spyOn(journal,'append').mockImplementationOnce(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    vi.useFakeTimers({toFake:['setTimeout','clearTimeout']});recorder.data('original queued audio');
    const completion=capture.stop();recorder.ended();await vi.advanceTimersByTimeAsync(5000);
    expect(await completion).toMatchObject({phase:'LOCAL_SAVE_FAILED',savedChunks:0,queuedChunks:1,reason:'LOCAL_WRITE_FAILED'});
    expect(await capture.retrySave()).toMatchObject({phase:'LOCAL_SAVE_FAILED',queuedChunks:1});expect(append).toHaveBeenCalledTimes(1);
    vi.useRealTimers();release();await waitSaved(capture,1);
    expect(await capture.retrySave()).toMatchObject({phase:'INTERRUPTED',savedChunks:1,queuedChunks:0,endedAt:1000});
    expect((await journal.list(scope))[0].state).toBe('INTERRUPTED');
  });
  it('initial local storage stall releases the owned device and marks a late-created original segment interrupted',async()=>{
    const real=journal.begin.bind(journal);let release!:()=>void;
    vi.spyOn(journal,'begin').mockImplementationOnce(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    const creating=start();const rejection=expect(creating).rejects.toMatchObject({code:'LOCAL_SAVE_FAILED'});
    await vi.waitFor(()=>expect(release).toBeTypeOf('function'));
    await rejection;expect(track.stop).toHaveBeenCalledTimes(1);expect(recorder.start).not.toHaveBeenCalled();
    release();await vi.waitFor(async()=>expect((await journal.list(scope))[0].state).toBe('INTERRUPTED'));
  },10000);
  it('an unavailable diagnostic clock does not break stop and does not replace the original time',async()=>{
    const capture=await start();recorder.data('audio');environment.now=()=>{throw new Error('private clock');};
    const completion=capture.stop();recorder.ended();expect(await completion).toMatchObject({phase:'SEALED',startedAt:1000,endedAt:1000});
  });
  it('synchronous recorder state is not actual startup confirmation; waits for the native start event',async()=>{
    recorder.start.mockImplementation(()=>{recorder.state='recording';});let resolved=false;
    const creating=start().then(value=>{resolved=true;return value;});await vi.waitFor(()=>expect(recorder.start).toHaveBeenCalledTimes(1));
    await flush();expect(resolved).toBe(false);recorder.dispatchEvent(new Event('start'));const capture=await creating;
    recorder.data('audio');const completion=capture.stop();recorder.ended();expect((await completion).phase).toBe('SEALED');
  });
  it('native startup has a bounded timeout instead of falsely returning a recording instance',async()=>{
    recorder.start.mockImplementation(()=>{recorder.state='recording';});const creating=start().then(()=>({code:'UNEXPECTED_SUCCESS'}),(error:unknown)=>error);await vi.waitFor(()=>expect(recorder.start).toHaveBeenCalledTimes(1));
    await vi.waitFor(()=>expect(recorder.stop).toHaveBeenCalledTimes(1),{timeout:6000});recorder.data('retained startup tail');recorder.ended();expect(await creating).toMatchObject({code:'START_FAILED'});
    expect((await journal.list(scope))[0].state).toBe('INTERRUPTED');
  },10000);
  it('a stalled metadata confirmation does not block forever or replace the original seal with interruption',async()=>{
    const capture=await start();recorder.data('audio');await waitSaved(capture,1);const real=journal.seal.bind(journal);let release!:()=>void;
    const seal=vi.spyOn(journal,'seal').mockImplementationOnce(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    now=2000;const completion=capture.stop();recorder.ended();
    await vi.waitFor(()=>expect(release).toBeTypeOf('function'));expect(await completion).toMatchObject({phase:'LOCAL_SAVE_FAILED',savedChunks:1,queuedChunks:0});
    expect(await capture.retrySave()).toMatchObject({phase:'LOCAL_SAVE_FAILED'});expect(seal).toHaveBeenCalledTimes(1);
    release();await vi.waitFor(async()=>expect((await journal.list(scope))[0].state).toBe('SEALED'));
    expect(await capture.retrySave()).toMatchObject({phase:'SEALED',reason:null,endedAt:2000});
  },10000);
  it('a delayed native Blob is split into bounded application chunks without dropping bytes or inventing a new recorder',async()=>{
    const capture=await start();const blob=new Blob([new Uint8Array(2*1024*1024),'real tail bytes'],{type:recorder.mimeType});
    recorder.data(blob);const completion=capture.stop();recorder.ended();expect(await completion).toMatchObject({phase:'SEALED',savedChunks:2,savedBytes:blob.size});
    const result=await journal.read(scope,capture.snapshot().segmentId);expect(result.chunks.map(c=>c.byteSize)).toEqual([2*1024*1024,15]);
    const original=await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()),stored=await crypto.subtle.digest('SHA-256',await (await journal.playable(scope,result.segment.id)).arrayBuffer());
    expect(new Uint8Array(stored)).toEqual(new Uint8Array(original));expect(recorder.start).toHaveBeenCalledTimes(1);
  });
  it('secure random-ID failure cannot leave an owned microphone on or expose native details',async()=>{
    vi.spyOn(crypto,'randomUUID').mockImplementationOnce(()=>{throw new Error('native private details');});
    await expect(start()).rejects.toMatchObject({code:'UNSUPPORTED'});expect(track.stop).toHaveBeenCalledTimes(1);expect(recorder.start).not.toHaveBeenCalled();
  });
});

function otherCapture(){
  const owned=new Track(),other=new Recorder(),otherScope={...scope,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};
  const create=vi.fn(()=>other as unknown as MediaRecorder);
  return {track:owned,recorder:other,scope:otherScope,create,
    start:()=>PreparedAnswerCapture.start({journal,scope:otherScope,ordinal:1,stream:audioStream(owned)},{...environment,create})};
}
describe('writer lifetime integrated with the original capture journal — controlled recorder events',()=>{
  it('persists the final original command before metadata commit and preserves it after save failure',async()=>{
    const capture=await start();recorder.data('audio');await waitSaved(capture,1);const prepare=vi.spyOn(journal,'prepareTerminal');
    vi.spyOn(journal,'seal').mockRejectedValueOnce(new LocalAudioError('WRITE_FAILED'));now=2000;const done=capture.stop();recorder.ended();
    expect((await done).phase).toBe('LOCAL_SAVE_FAILED');expect(prepare).toHaveBeenCalledWith(scope,capture.snapshot().segmentId,{expectedChunks:1,endedAt:2000,reason:null});
    expect((await capture.retrySave()).phase).toBe('SEALED');expect((await journal.recoverTerminal(scope,capture.snapshot().segmentId)).endedAt).toBe(2000);
  });
  it('simultaneous local starts have exactly one native recorder and journal winner',async()=>{
    const a=otherCapture(),b=otherCapture();
    const result=await Promise.all([a.start().then(capture=>({capture}),error=>({code:error.code})),b.start().then(capture=>({capture}),error=>({code:error.code}))]);
    expect(result.map(x=>'code' in x?x.code:'RECORDING').sort()).toEqual(['ANOTHER_WRITER','RECORDING']);
    expect(a.create.mock.calls.length+b.create.mock.calls.length).toBe(1);
    for(let i=0;i<result.length;i++){const winner=result[i];if('capture' in winner){const r=i===0?a.recorder:b.recorder;r.data('winner');const done=winner.capture.stop();r.ended();await done;}}
    await vi.waitFor(()=>expect(held.size).toBe(0));
  });
  it('a device lost during writer acquisition cannot create a recorder or initial journal evidence',async()=>{
    const original=environment.writer!;let release!:()=>void;
    environment.writer=async key=>{const lease=await original(key);await new Promise<void>(r=>{release=r;});return lease;};
    const create=vi.spyOn(environment,'create'),begin=vi.spyOn(journal,'begin');const pending=start();
    const rejected=expect(pending).rejects.toMatchObject({code:'DEVICE_UNAVAILABLE'});await vi.waitFor(()=>expect(release).toBeTypeOf('function'));
    track.readyState='ended';release();await rejected;expect(create).not.toHaveBeenCalled();expect(begin).not.toHaveBeenCalled();await vi.waitFor(()=>expect(held.size).toBe(0));
  });
  it('a stuck key digest has a bounded failure and cannot leave its owned microphone active',async()=>{
    vi.spyOn(journal,'captureWriterKey').mockImplementationOnce(()=>new Promise(()=>{}));vi.useFakeTimers({toFake:['setTimeout','clearTimeout']});
    const pending=start(),rejected=expect(pending).rejects.toMatchObject({code:'WRITER_UNAVAILABLE'});await vi.advanceTimersByTimeAsync(5000);await rejected;
    expect(track.stop).toHaveBeenCalledTimes(1);expect(recorder.start).not.toHaveBeenCalled();expect(held.size).toBe(0);
  });
  it('blocks different answers/attempts of the same assessment before recorder construction or journal begin',async()=>{
    const capture=await start(),other=otherCapture();const begin=vi.spyOn(journal,'begin');
    await expect(other.start()).rejects.toMatchObject({code:'ANOTHER_WRITER'});
    expect(other.create).not.toHaveBeenCalled();expect(begin).not.toHaveBeenCalled();expect(other.track.stop).toHaveBeenCalledTimes(1);
    recorder.data('original');const completion=capture.stop();recorder.ended();await completion;
    expect(await (await journal.playable(scope,capture.snapshot().segmentId)).text()).toBe('original');
  });
  it('holds through final native data and a delayed commit, releasing only after durable sealing',async()=>{
    const capture=await start();const real=journal.append.bind(journal);let release!:()=>void;
    vi.spyOn(journal,'append').mockImplementationOnce(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    const completion=capture.stop();recorder.data('tail');recorder.ended();
    await expect(otherCapture().start()).rejects.toMatchObject({code:'ANOTHER_WRITER'});expect(held.size).toBe(1);
    release();expect((await completion).phase).toBe('SEALED');await vi.waitFor(()=>expect(held.size).toBe(0));
    const other=otherCapture(),next=await other.start();other.recorder.data('new local fixture');const done=next.stop();other.recorder.ended();await done;
  });
  it('keeps exclusive ownership after save failure, until original bytes and interruption commit succeed',async()=>{
    const capture=await start();vi.spyOn(journal,'append').mockRejectedValueOnce(new LocalAudioError('WRITE_FAILED'));
    recorder.data('retained original');await vi.waitFor(()=>expect(capture.snapshot().phase).toBe('LOCAL_SAVE_FAILED'));recorder.ended();
    expect((await capture.stop()).phase).toBe('LOCAL_SAVE_FAILED');await expect(otherCapture().start()).rejects.toMatchObject({code:'ANOTHER_WRITER'});
    now=9999;expect(await capture.retrySave()).toMatchObject({phase:'INTERRUPTED',endedAt:1000,savedChunks:1});await vi.waitFor(()=>expect(held.size).toBe(0));
    expect(recorder.start).toHaveBeenCalledTimes(1);
  });
  it('retains ownership for an unconfirmed original seal and releases after exact-command recovery',async()=>{
    const capture=await start();recorder.data('audio');await waitSaved(capture,1);vi.spyOn(journal,'seal').mockRejectedValueOnce(new LocalAudioError('WRITE_FAILED'));
    const done=capture.stop();recorder.ended();expect((await done).phase).toBe('LOCAL_SAVE_FAILED');await expect(otherCapture().start()).rejects.toMatchObject({code:'ANOTHER_WRITER'});
    expect((await capture.retrySave()).phase).toBe('SEALED');await vi.waitFor(()=>expect(held.size).toBe(0));
  });
  it('a timed-out begin cannot free ownership until late original creation and annotation have drained',async()=>{
    const real=journal.begin.bind(journal);let release!:()=>void;
    vi.spyOn(journal,'begin').mockImplementationOnce(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    const creating=start();const rejection=expect(creating).rejects.toMatchObject({code:'LOCAL_SAVE_FAILED'});
    await vi.waitFor(()=>expect(release).toBeTypeOf('function'));await rejection;
    expect(held.size).toBe(1);await expect(otherCapture().start()).rejects.toMatchObject({code:'ANOTHER_WRITER'});
    release();await vi.waitFor(async()=>expect((await journal.list(scope))[0].state).toBe('INTERRUPTED'));await vi.waitFor(()=>expect(held.size).toBe(0));
    expect(recorder.start).not.toHaveBeenCalled();
  },10000);
  it('constructor failure releases a granted writer as well as its owned device',async()=>{
    environment.create=()=>{throw new Error('private');};await expect(start()).rejects.toMatchObject({code:'UNSUPPORTED'});
    await vi.waitFor(()=>expect(held.size).toBe(0));expect(track.stop).toHaveBeenCalledTimes(1);
  });
  it('writer acquisition failure is bounded and cannot construct a recorder or create local answer evidence',async()=>{
    environment.writer=async()=>{throw new Error('private');};const create=vi.spyOn(environment,'create');
    await expect(start()).rejects.toMatchObject({code:'WRITER_UNAVAILABLE'});expect(create).not.toHaveBeenCalled();expect(track.stop).toHaveBeenCalledTimes(1);expect(await journal.list(scope)).toEqual([]);
  });
  it('lost native ownership stops physical capture and preserves the original prefix and tail as fault evidence',async()=>{
    let lost!:()=>void;const release=vi.fn();environment.writer=async()=>({release,onLost:listener=>{lost=listener;return ()=>{};}});
    const capture=await start();recorder.data('prefix');await waitSaved(capture,1);now=2000;lost();
    expect(track.stop).toHaveBeenCalledTimes(1);recorder.data('tail');recorder.ended();expect(await capture.stop()).toMatchObject({phase:'INTERRUPTED',reason:'CAPTURE_ERROR',endedAt:2000,savedChunks:2});
    expect(release).toHaveBeenCalledTimes(1);expect(recorder.start).toHaveBeenCalledTimes(1);
  });
});
