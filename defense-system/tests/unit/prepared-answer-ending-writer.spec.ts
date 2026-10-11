// @vitest-environment node
import {afterEach,describe,expect,it,vi} from 'vitest';
import {PreparedAnswerCapture} from '@/prepared/answerCapture';
import {LocalAudioError} from '@/prepared/localAudioJournal';
import {WriterError} from '@/prepared/captureWriter';
const scope={assessmentId:8,attemptId:'00000000-0000-4000-8000-000000000001',answerId:'00000000-0000-4000-8000-000000000002'};
const ids={idempotencyKey:'00000000-0000-4000-8000-000000000004',captureClientId:'00000000-0000-4000-8000-000000000005'};
const result={...ids,manifest:{schemaVersion:1 as const,segments:[]}}; // Explicit metadata stub, not a valid real recording.
function fixture(){let lose:()=>void=()=>{};const release=vi.fn(),unsubscribe=vi.fn();const writer=vi.fn(async()=>({release,onLost:(callback:()=>void)=>{lose=callback;return unsubscribe;}}));
  const journal={captureWriterKey:vi.fn(async()=>('prepared-capture-v1:'+'a'.repeat(64))),prepareAnswerEnd:vi.fn(async()=>result),recoverAnswerEnd:vi.fn(async()=>result)};
  return {journal,writer,release,unsubscribe,lose:()=>lose()};}
afterEach(()=>{vi.useRealTimers();vi.restoreAllMocks();});
describe('ending coordination acquires original assessment writer; never a device or cloud receipt',()=>{
  it('holds protection through durable prepare and returns original command only after local completion',async()=>{
    const f=fixture();expect(await PreparedAnswerCapture.prepareEnding({journal:f.journal,scope,...ids},{writer:f.writer})).toEqual(result);
    expect(f.journal.prepareAnswerEnd).toHaveBeenCalledWith(scope,ids);expect(f.release).toHaveBeenCalledTimes(1);expect(f.unsubscribe).toHaveBeenCalledTimes(1);
  });
  it('recover obtains the same writer and replays original intent without generating ids or starting recording',async()=>{
    const f=fixture();expect(await PreparedAnswerCapture.recoverEnding({journal:f.journal,scope},{writer:f.writer})).toEqual(result);
    expect(f.journal.captureWriterKey).toHaveBeenCalledWith(scope);expect(f.journal.prepareAnswerEnd).not.toHaveBeenCalled();expect(f.journal.recoverAnswerEnd).toHaveBeenCalledWith(scope);
  });
  it('busy or unavailable writer prevents all ending reads/writes',async()=>{
    for(const code of ['BUSY','UNSUPPORTED'] as const){const f=fixture();f.writer.mockRejectedValue(new WriterError(code));
      await expect(PreparedAnswerCapture.prepareEnding({journal:f.journal,scope,...ids},{writer:f.writer})).rejects.toMatchObject({code:code==='BUSY'?'ANOTHER_WRITER':'WRITER_UNAVAILABLE'});
      expect(f.journal.prepareAnswerEnd).not.toHaveBeenCalled();expect(f.journal.recoverAnswerEnd).not.toHaveBeenCalled();}
  });
  it('missing terminal or missing original end intent reports recovery required rather than inventing completion',async()=>{
    for(const code of ['MISSING_TERMINAL','MISSING_ANSWER_END'] as const){const f=fixture();f.journal.recoverAnswerEnd.mockRejectedValue(new LocalAudioError(code));
      await expect(PreparedAnswerCapture.recoverEnding({journal:f.journal,scope},{writer:f.writer})).rejects.toMatchObject({code:'RECOVERY_REQUIRED'});expect(f.release).toHaveBeenCalledTimes(1);}
  });
  it('write failure or integrity failure is preservation failure, never a success command',async()=>{
    for(const code of ['WRITE_FAILED','INTEGRITY'] as const){const f=fixture();f.journal.prepareAnswerEnd.mockRejectedValue(new LocalAudioError(code));
      await expect(PreparedAnswerCapture.prepareEnding({journal:f.journal,scope,...ids},{writer:f.writer})).rejects.toMatchObject({code:'LOCAL_SAVE_FAILED'});expect(f.release).toHaveBeenCalledTimes(1);}
  });
  it('lost protection before the operation prevents local access, even if lease acquisition nominally succeeded',async()=>{
    const f=fixture();f.writer.mockResolvedValue({release:f.release,onLost:callback=>{callback();return f.unsubscribe;}});
    await expect(PreparedAnswerCapture.recoverEnding({journal:f.journal,scope},{writer:f.writer})).rejects.toMatchObject({code:'WRITER_UNAVAILABLE'});expect(f.journal.recoverAnswerEnd).not.toHaveBeenCalled();expect(f.release).toHaveBeenCalledTimes(1);
  });
  it('lost protection while saving preserves the original operation but never returns it as authorized success',async()=>{
    const f=fixture();let done!:(value:typeof result)=>void;f.journal.prepareAnswerEnd.mockImplementation(()=>new Promise(resolve=>{done=resolve;}));
    const pending=PreparedAnswerCapture.prepareEnding({journal:f.journal,scope,...ids},{writer:f.writer});await vi.waitFor(()=>expect(f.journal.prepareAnswerEnd).toHaveBeenCalled());f.lose();done(result);
    await expect(pending).rejects.toMatchObject({code:'WRITER_UNAVAILABLE'});expect(f.release).toHaveBeenCalledTimes(1);
  });
  it('timeout reports unsaved/unknown but holds writer until the original transaction really drains',async()=>{
    vi.useFakeTimers();const f=fixture();let done!:(value:typeof result)=>void;f.journal.prepareAnswerEnd.mockImplementation(()=>new Promise(resolve=>{done=resolve;}));
    const pending=PreparedAnswerCapture.prepareEnding({journal:f.journal,scope,...ids},{writer:f.writer});const checked=expect(pending).rejects.toMatchObject({code:'LOCAL_SAVE_FAILED'});
    await vi.advanceTimersByTimeAsync(5001);await checked;expect(f.release).not.toHaveBeenCalled();done(result);await vi.advanceTimersByTimeAsync(0);expect(f.release).toHaveBeenCalledTimes(1);
  });
  it('caller mutations during writer acquisition cannot change original scope or identifiers',async()=>{
    const f=fixture();const input={journal:f.journal,scope:{...scope},...ids};let open!:(value:Awaited<ReturnType<typeof f.writer>>)=>void;f.writer.mockImplementation(()=>new Promise(resolve=>{open=resolve;}));
    const pending=PreparedAnswerCapture.prepareEnding(input,{writer:f.writer});await vi.waitFor(()=>expect(f.writer).toHaveBeenCalled());input.scope.answerId=crypto.randomUUID();input.idempotencyKey=crypto.randomUUID();input.captureClientId=crypto.randomUUID();
    open({release:f.release,onLost:()=>f.unsubscribe});await pending;expect(f.journal.prepareAnswerEnd).toHaveBeenCalledWith(scope,ids);
  });
  it('invalid structural inputs fail without unbounded waiting or journal access',async()=>{
    const f=fixture();await expect(PreparedAnswerCapture.prepareEnding(null as never,{writer:f.writer})).rejects.toMatchObject({code:'INVALID_INPUT'});
    await expect(PreparedAnswerCapture.recoverEnding({journal:null,scope} as never,{writer:f.writer})).rejects.toMatchObject({code:'INVALID_INPUT'});expect(f.writer).not.toHaveBeenCalled();
  });
});
