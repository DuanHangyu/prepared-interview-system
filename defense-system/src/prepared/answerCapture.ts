import {LocalAudioError,type AnswerScope,type Interruption,type PreparedAudioJournal} from './localAudioJournal';
import {acquireCaptureWriter,WriterError,type WriterLease} from './captureWriter';
import type {AnswerEndCommand} from './answerEndManifest';

type Journal=Pick<PreparedAudioJournal,'begin'|'append'|'seal'|'interrupt'|'captureWriterKey'|'prepareTerminal'|'verifyContinuation'>;
type CaptureCode='INVALID_INPUT'|'UNSUPPORTED'|'DEVICE_UNAVAILABLE'|'START_FAILED'|'LOCAL_SAVE_FAILED'|'ALREADY_RECORDING'|'NOT_STOPPED'|'ANOTHER_WRITER'|'WRITER_UNAVAILABLE'|'RECOVERY_REQUIRED';
const messages:Record<CaptureCode,string>={INVALID_INPUT:'录音参数无效',UNSUPPORTED:'此浏览器无法使用受支持的录音格式',DEVICE_UNAVAILABLE:'麦克风不可用，请先完成设备检查',START_FAILED:'录音未正常开始，原内容已保留',LOCAL_SAVE_FAILED:'本机保存失败，请保留原页面和已存内容',ALREADY_RECORDING:'此回答已有录音分段，请先恢复原状态',NOT_STOPPED:'采集尚未结束，不能重新保存或重置回答',ANOTHER_WRITER:'此考核已在另一个页面录音，请保留原页面',WRITER_UNAVAILABLE:'本地录音独占保护不可用，请保留原页面',RECOVERY_REQUIRED:'原录音尚无可靠结束依据，请保留已有内容并按故障流程恢复'};
export class CaptureError extends Error {
  constructor(public readonly code:CaptureCode){super(messages[code]);this.name='CaptureError';}
}
export type CaptureSnapshot={
  phase:'RECORDING'|'STOPPING'|'SEALED'|'INTERRUPTED'|'LOCAL_SAVE_FAILED';
  segmentId:string;startedAt:number;endedAt:number|null;reason:Interruption|null;
  savedChunks:number;savedBytes:number;queuedChunks:number;queuedBytes:number;
};
export type RecorderEnvironment={supports:(mime:string)=>boolean;create:(stream:MediaStream,options:MediaRecorderOptions)=>MediaRecorder;now:()=>number;writer?:(key:string)=>Promise<WriterLease>};
type StartInput={journal:Journal;scope:AnswerScope;ordinal:number;stream:MediaStream};
type EndingJournal=Pick<PreparedAudioJournal,'captureWriterKey'|'prepareAnswerEnd'|'recoverAnswerEnd'>;
const MIMES=['audio/webm;codecs=opus','audio/ogg;codecs=opus','audio/webm','audio/ogg','audio/mp4'];
const MAX_CHUNK=2*1024*1024,MAX_PENDING=8*1024*1024,MAX_PENDING_COUNT=32;
const DEFAULT_ENV:RecorderEnvironment={
  supports:mime=>typeof MediaRecorder!=='undefined'&&MediaRecorder.isTypeSupported(mime),
  create:(stream,options)=>new MediaRecorder(stream,options),now:()=>Date.now(),
};
async function bounded<T>(operation:Promise<T>,code:CaptureCode):Promise<T>{
  let timer:ReturnType<typeof setTimeout>|undefined;
  try{return await Promise.race([operation,new Promise<never>((_,reject)=>{timer=setTimeout(()=>reject(new CaptureError(code)),5000);})]);}
  finally{if(timer!==undefined)clearTimeout(timer);}
}
async function writerFor(journal:Pick<PreparedAudioJournal,'captureWriterKey'>,scope:AnswerScope,environment:Pick<RecorderEnvironment,'writer'>):Promise<WriterLease>{
  try{
    const key=await bounded(journal.captureWriterKey(scope),'WRITER_UNAVAILABLE');
    return await (environment.writer??acquireCaptureWriter)(key);
  }catch(error){throw new CaptureError(error instanceof WriterError&&error.code==='BUSY'?'ANOTHER_WRITER':'WRITER_UNAVAILABLE');}
}

/** Local capture only. Caller must authorize the original answer/time/identity and owns the supplied audio-only stream.
 * No upload, deletion, question advancement, new-answer retry, camera or server-success assertion lives here. */
export class PreparedAnswerCapture {
  private phase:CaptureSnapshot['phase']='STOPPING';
  private reason:Interruption|null=null;
  private endedAt:number|null=null;
  private savedChunks=0;private savedBytes=0;private queuedBytes=0;
  private queue:Array<{index:number;blob:Blob}>=[];
  private received=0;private blocked=false;private initializing=true;private stopRequested=false;private stopObserved=false;private released=false;
  private draining:Promise<void>|undefined;private finalizing:Promise<CaptureSnapshot>|undefined;private retrying:Promise<CaptureSnapshot>|undefined;
  private timer:ReturnType<typeof setTimeout>|undefined;
  private pendingStart:{resolve:()=>void;reject:(error:CaptureError)=>void}|undefined;
  private nativeInvoked=false;
  private terminalCommand:{reason:Interruption|null;endedAt:number;count:number}|undefined;
  private terminalSaving:Promise<unknown>|undefined;
  private initialPreservation:Promise<boolean>|undefined;
  private writerReleased=false;private unsubscribeWriter:(()=>void)|undefined;
  private listeners=new Set<(value:CaptureSnapshot)=>void>();
  private resolveCompletion!:(value:CaptureSnapshot)=>void;
  private readonly completion=new Promise<CaptureSnapshot>(resolve=>{this.resolveCompletion=resolve;});
  private readonly segmentId=crypto.randomUUID();
  private constructor(private readonly journal:Journal,private readonly scope:AnswerScope,private readonly ordinal:number,
    private readonly stream:MediaStream,private readonly tracks:MediaStreamTrack[],private readonly recorder:MediaRecorder,
    private readonly mimeType:string,private readonly startedAt:number,private readonly environment:RecorderEnvironment,private readonly writer:WriterLease){
    recorder.addEventListener('start',this.onStart);recorder.addEventListener('dataavailable',this.onData);recorder.addEventListener('stop',this.onStop);recorder.addEventListener('error',this.onError);
    for(const track of tracks){track.addEventListener('ended',this.onEnded);track.addEventListener('mute',this.onMuted);}
    this.unsubscribeWriter=writer.onLost(this.onError);
  }
  static async start(input:StartInput,environment:RecorderEnvironment=DEFAULT_ENV):Promise<PreparedAnswerCapture>{
    if(!input||!input.journal||!input.scope||!input.stream||typeof input.stream.getTracks!=='function')throw new CaptureError('INVALID_INPUT');
    const {journal,stream,ordinal}=input;const scope={assessmentId:input.scope.assessmentId,attemptId:input.scope.attemptId,answerId:input.scope.answerId};
    const all=stream.getTracks(),tracks=stream.getAudioTracks();
    const release=()=>{for(const track of all)track.stop();};
    if(tracks.length!==1||stream.getVideoTracks().length||tracks.some(t=>t.readyState!=='live'||!t.enabled||t.muted)){
      release();throw new CaptureError('DEVICE_UNAVAILABLE');
    }
    let writer:WriterLease;
    try{writer=await writerFor(journal,scope,environment);}catch(error){release();throw error;}
    if(tracks.some(t=>t.readyState!=='live'||!t.enabled||t.muted)){
      writer.release();release();throw new CaptureError('DEVICE_UNAVAILABLE');
    }
    let recorder:MediaRecorder,mimeType:string,startedAt:number;
    try{
      const mime=MIMES.find(value=>environment.supports(value));if(!mime)throw new CaptureError('UNSUPPORTED');
      recorder=environment.create(stream,{mimeType:mime,audioBitsPerSecond:64000});mimeType=recorder.mimeType;
      if(!MIMES.includes(mimeType)||recorder.state!=='inactive')throw new CaptureError('UNSUPPORTED');
      startedAt=environment.now();if(!Number.isSafeInteger(startedAt)||startedAt<0||startedAt>8640000000000000)throw new CaptureError('INVALID_INPUT');
    }catch(error){writer.release();release();throw error instanceof CaptureError?error:new CaptureError('UNSUPPORTED');}
    let capture:PreparedAnswerCapture;
    try{capture=new PreparedAnswerCapture(journal,scope,ordinal,stream,tracks,recorder,mimeType,startedAt,environment,writer);}
    catch{writer.release();release();throw new CaptureError('UNSUPPORTED');}
    try{await capture.prepare();}
    catch(error){
      capture.cleanup();
      // Never release while an original begin/late annotation is still in flight. Failed annotation retains ownership until context exit.
      void (capture.initialPreservation??Promise.resolve(true)).then(preserved=>{if(preserved)capture.releaseWriter();});
      throw new CaptureError(error instanceof LocalAudioError&&error.code==='CONFLICT'?'ALREADY_RECORDING':'LOCAL_SAVE_FAILED');
    }
    capture.initializing=false;
    if(capture.stopRequested||tracks.some(t=>t.readyState!=='live'||!t.enabled||t.muted)){
      capture.reason??='DEVICE_ENDED';capture.observeStop();await capture.completion;throw new CaptureError('DEVICE_UNAVAILABLE');
    }
    try{
      await capture.nativeStart();
      if(capture.stopRequested)throw new CaptureError('START_FAILED');
    }catch{
      capture.reason??='CAPTURE_ERROR';capture.requestStop();if(!capture.nativeInvoked)capture.observeStop();await capture.completion;throw new CaptureError('START_FAILED');
    }
    capture.phase='RECORDING';capture.notify();return capture;
  }
  /** Persist the original whole-answer ending before sending it. No HTTP, device, new recording or cloud claim. */
  static async prepareEnding(input:{journal:EndingJournal;scope:AnswerScope;idempotencyKey:string;captureClientId:string},
      environment:Pick<RecorderEnvironment,'writer'>=DEFAULT_ENV):Promise<AnswerEndCommand>{
    if(!input?.journal||!input.scope)throw new CaptureError('INVALID_INPUT');
    const {journal}=input,scope={assessmentId:input.scope.assessmentId,attemptId:input.scope.attemptId,answerId:input.scope.answerId};
    const ids={idempotencyKey:input.idempotencyKey,captureClientId:input.captureClientId};
    return this.withEndingWriter(journal,scope,environment,()=>journal.prepareAnswerEnd(scope,ids));
  }
  /** Freshly authenticated caller restores the original command; never generates replacement identifiers. */
  static async recoverEnding(input:{journal:EndingJournal;scope:AnswerScope},
      environment:Pick<RecorderEnvironment,'writer'>=DEFAULT_ENV):Promise<AnswerEndCommand>{
    if(!input?.journal||!input.scope)throw new CaptureError('INVALID_INPUT');
    const {journal}=input,scope={assessmentId:input.scope.assessmentId,attemptId:input.scope.attemptId,answerId:input.scope.answerId};
    return this.withEndingWriter(journal,scope,environment,()=>journal.recoverAnswerEnd(scope));
  }
  private static async withEndingWriter(journal:EndingJournal,scope:AnswerScope,environment:Pick<RecorderEnvironment,'writer'>,
      operation:()=>Promise<AnswerEndCommand>):Promise<AnswerEndCommand>{
    const writer=await writerFor(journal,scope,environment);let lost=false;const unsubscribe=writer.onLost(()=>{lost=true;});
    const preserving=Promise.resolve().then(()=>{if(lost)throw new CaptureError('WRITER_UNAVAILABLE');return operation();})
      .finally(()=>{unsubscribe();writer.release();});
    try{const command=await bounded(preserving,'LOCAL_SAVE_FAILED');if(lost)throw new CaptureError('WRITER_UNAVAILABLE');return command;}
    catch(error){
      if(error instanceof CaptureError)throw error;
      throw new CaptureError(error instanceof LocalAudioError&&['MISSING_TERMINAL','MISSING_ANSWER_END'].includes(error.code)?'RECOVERY_REQUIRED':'LOCAL_SAVE_FAILED');
    }
    // An observation timeout does not release an in-flight original transaction. Its finally owns release.
  }
  /** Same-owner saved command recovery only; caller must freshly verify the original server identity/scope. No microphone, new recording or server-success assertion. */
  static async recoverSaved(input:{journal:Pick<PreparedAudioJournal,'captureWriterKey'|'recoverTerminal'>;scope:AnswerScope;segmentId:string},
      environment:Pick<RecorderEnvironment,'writer'>=DEFAULT_ENV):Promise<CaptureSnapshot>{
    if(!input?.journal||!input.scope||typeof input.segmentId!=='string'||!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(input.segmentId))throw new CaptureError('INVALID_INPUT');
    const {journal,segmentId}=input,scope={assessmentId:input.scope.assessmentId,attemptId:input.scope.attemptId,answerId:input.scope.answerId};
    const writer=await writerFor(journal,scope,environment);let lost=false;const unsubscribe=writer.onLost(()=>{lost=true;});
    const restoring=Promise.resolve().then(()=>{if(lost)throw new CaptureError('WRITER_UNAVAILABLE');return journal.recoverTerminal(scope,segmentId);})
      .finally(()=>{unsubscribe();writer.release();});
    try{
      const segment=await bounded(restoring,'LOCAL_SAVE_FAILED');
      if(lost)throw new CaptureError('WRITER_UNAVAILABLE');
      if(segment.state==='OPEN')throw new CaptureError('RECOVERY_REQUIRED');
      return {phase:segment.state,segmentId:segment.id,startedAt:segment.startedAt,endedAt:segment.endedAt,reason:segment.interruption,
        savedChunks:segment.chunkCount,savedBytes:segment.byteSize,queuedChunks:0,queuedBytes:0};
    }catch(error){
      if(error instanceof CaptureError)throw error;
      throw new CaptureError(error instanceof LocalAudioError&&error.code==='MISSING_TERMINAL'?'RECOVERY_REQUIRED':'LOCAL_SAVE_FAILED');
    }
    // Timeout does not cancel the original transaction. Its own finally releases protection only after it drains.
  }
  private async prepare(){
    let expired=false;
    const creating=(async()=>{
      if(this.ordinal>1)await this.journal.verifyContinuation(this.scope,this.ordinal,this.startedAt);
      // Read-only verification may finish after its observation timeout. Never create a late continuation.
      if(expired)throw new LocalAudioError('CONFLICT');
      return this.journal.begin(this.scope,{id:this.segmentId,ordinal:this.ordinal,mimeType:this.mimeType,startedAt:this.startedAt});
    })();
    // A timed-out transaction may still commit. Preserve and annotate that exact segment, never start a late recorder.
    this.initialPreservation=creating.then(async()=>{
      if(expired){
        try{await this.journal.interrupt(this.scope,this.segmentId,this.endedAt??this.startedAt,'LOCAL_WRITE_FAILED');}
        catch{return false;}
      }
      return true;
    },()=>true);
    try{await this.withDeadline(creating);}
    catch(error){expired=true;this.endedAt=this.captureTime();throw error;}
  }
  private nativeStart(){
    return new Promise<void>((resolve,reject)=>{
      const timer=setTimeout(()=>this.pendingStart?.reject(new CaptureError('START_FAILED')),5000);
      this.pendingStart={resolve:()=>{clearTimeout(timer);this.pendingStart=undefined;resolve();},reject:error=>{clearTimeout(timer);this.pendingStart=undefined;reject(error);}};
      try{
        this.recorder.start(1000);this.nativeInvoked=this.recorder.state==='recording';
        if(!this.nativeInvoked)this.pendingStart?.reject(new CaptureError('START_FAILED'));
      }catch{this.pendingStart?.reject(new CaptureError('START_FAILED'));}
    });
  }
  private async withDeadline<T>(operation:Promise<T>):Promise<T>{
    let timer:ReturnType<typeof setTimeout>|undefined;
    const timeout=new Promise<never>((_,reject)=>{timer=setTimeout(()=>reject(new CaptureError('LOCAL_SAVE_FAILED')),5000);});
    try{return await Promise.race([operation,timeout]);}finally{if(timer!==undefined)clearTimeout(timer);}
  }
  snapshot():CaptureSnapshot{
    return {phase:this.phase,segmentId:this.segmentId,startedAt:this.startedAt,endedAt:this.endedAt,reason:this.reason,
      savedChunks:this.savedChunks,savedBytes:this.savedBytes,queuedChunks:this.queue.length,queuedBytes:this.queuedBytes};
  }
  subscribe(listener:(value:CaptureSnapshot)=>void){this.listeners.add(listener);this.notify();return ()=>this.listeners.delete(listener);}
  private notify(){for(const listener of Array.from(this.listeners)){try{listener(this.snapshot());}catch{/* UI observers cannot break preservation; do not log private capture state. */}}}
  stop():Promise<CaptureSnapshot>{this.requestStop();return this.completion;}
  interrupt(reason:Interruption):Promise<CaptureSnapshot>{
    if(!['PAGE_REFRESH','DEVICE_ENDED','CAPTURE_ERROR','LOCAL_WRITE_FAILED','SESSION_CHANGED'].includes(reason))throw new CaptureError('INVALID_INPUT');
    if(!this.stopObserved)this.reason??=reason;this.requestStop();return this.completion;
  }
  private requestStop(){
    if(this.stopObserved)return;
    if(!this.stopRequested){this.stopRequested=true;this.endedAt=this.captureTime();}
    this.phase=this.blocked?'LOCAL_SAVE_FAILED':'STOPPING';this.notify();
    if(this.initializing)return;
    if(this.timer===undefined)this.timer=setTimeout(()=>{this.reason??='CAPTURE_ERROR';this.observeStop();},5000);
    if(this.recorder.state!=='inactive'){
      try{this.recorder.stop();}catch{this.reason??='CAPTURE_ERROR';this.observeStop();}
    }
    this.releaseTracks();
  }
  private captureTime(){try{const now=this.environment.now();return Number.isSafeInteger(now)&&now>=0&&now<=8640000000000000?Math.max(now,this.startedAt):this.startedAt;}catch{return this.startedAt;}}
  private releaseTracks(){
    if(this.released)return;this.released=true;
    for(const track of this.tracks){track.removeEventListener('ended',this.onEnded);track.removeEventListener('mute',this.onMuted);}
    for(const track of this.stream.getTracks())track.stop();
  }
  private cleanup(){
    if(this.timer!==undefined)clearTimeout(this.timer);this.timer=undefined;this.releaseTracks();
    this.recorder.removeEventListener('start',this.onStart);this.recorder.removeEventListener('dataavailable',this.onData);this.recorder.removeEventListener('stop',this.onStop);this.recorder.removeEventListener('error',this.onError);
  }
  private releaseWriter(){
    if(this.writerReleased)return;this.writerReleased=true;this.unsubscribeWriter?.();this.unsubscribeWriter=undefined;this.writer.release();
  }
  private onStart=()=>{if(this.recorder.state==='recording'&&this.recorder.mimeType===this.mimeType)this.pendingStart?.resolve();else this.pendingStart?.reject(new CaptureError('START_FAILED'));};
  private onEnded=()=>{this.reason??='DEVICE_ENDED';this.pendingStart?.reject(new CaptureError('START_FAILED'));this.requestStop();};
  private onMuted=()=>{this.reason??='CAPTURE_ERROR';this.pendingStart?.reject(new CaptureError('START_FAILED'));this.requestStop();};
  private onError=()=>{if(!this.stopObserved){this.reason??='CAPTURE_ERROR';this.pendingStart?.reject(new CaptureError('START_FAILED'));this.requestStop();}};
  private onStop=()=>this.observeStop();
  private observeStop(){
    if(this.stopObserved)return;
    this.pendingStart?.reject(new CaptureError('START_FAILED'));
    if(!this.stopRequested){this.reason??=this.tracks.some(t=>t.readyState==='ended')?'DEVICE_ENDED':'CAPTURE_ERROR';this.endedAt=this.captureTime();}
    this.stopRequested=true;this.stopObserved=true;this.phase=this.blocked?'LOCAL_SAVE_FAILED':'STOPPING';this.cleanup();this.notify();
    void this.complete();
  }
  private onData=(event:Event)=>{
    if(this.stopObserved||this.initializing)return;
    const blob=(event as BlobEvent).data;
    if(!(blob instanceof Blob)){this.reason??='CAPTURE_ERROR';this.requestStop();return;}
    if(blob.size===0)return;
    if(blob.type&&blob.type!==this.mimeType){this.reason??='CAPTURE_ERROR';this.requestStop();return;}
    const count=Math.ceil(blob.size/MAX_CHUNK);
    if(this.queuedBytes+blob.size>MAX_PENDING||this.queue.length+count>MAX_PENDING_COUNT){
      this.reason??='LOCAL_WRITE_FAILED';this.requestStop();return;
    }
    // A native timeslice is not a size guarantee. Split one recorder's bytes without rewriting its container.
    for(let offset=0;offset<blob.size;offset+=MAX_CHUNK){
      this.queue.push({index:this.received++,blob:blob.slice(offset,Math.min(offset+MAX_CHUNK,blob.size),blob.type)});
    }
    this.queuedBytes+=blob.size;this.notify();this.drain();
  };
  private drain(){
    if(this.draining||this.blocked||!this.queue.length)return;
    this.draining=(async()=>{
      while(this.queue.length&&!this.blocked){
        const next=this.queue[0];
        const watchdog=setTimeout(()=>{
          this.blocked=true;this.reason??='LOCAL_WRITE_FAILED';this.phase='LOCAL_SAVE_FAILED';this.requestStop();this.notify();
          if(this.stopObserved)this.resolveCompletion(this.snapshot());
        },5000);
        try{
          await this.journal.append(this.scope,this.segmentId,next.index,next.blob);
          this.queue.shift();this.queuedBytes-=next.blob.size;this.savedChunks++;this.savedBytes+=next.blob.size;this.notify();
        }catch{
          this.blocked=true;this.reason??='LOCAL_WRITE_FAILED';this.phase='LOCAL_SAVE_FAILED';this.requestStop();this.notify();
        }finally{clearTimeout(watchdog);}
      }
    })().finally(()=>{this.draining=undefined;});
  }
  private async complete(){
    if(this.blocked){this.phase='LOCAL_SAVE_FAILED';this.resolveCompletion(this.snapshot());return;}
    this.resolveCompletion(await this.finalize());
  }
  private finalize():Promise<CaptureSnapshot>{
    if(this.finalizing)return this.finalizing;
    this.finalizing=(async()=>{
      await this.draining;
      if(this.blocked||this.queue.length){this.phase='LOCAL_SAVE_FAILED';this.notify();return this.snapshot();}
      if(this.savedChunks===0)this.reason??='CAPTURE_ERROR';
      this.terminalCommand??={reason:this.reason,endedAt:this.endedAt??this.captureTime(),count:this.savedChunks};
      const command=this.terminalCommand;
      try{
        this.terminalSaving=this.journal.prepareTerminal(this.scope,this.segmentId,{expectedChunks:command.count,endedAt:command.endedAt,reason:command.reason})
          .then(()=>command.reason?this.journal.interrupt(this.scope,this.segmentId,command.endedAt,command.reason):this.journal.seal(this.scope,this.segmentId,command.count,command.endedAt))
          .finally(()=>{this.terminalSaving=undefined;});
        await this.withDeadline(this.terminalSaving);this.releaseWriter();this.reason=command.reason;this.phase=command.reason?'INTERRUPTED':'SEALED';
      }catch{this.reason??='LOCAL_WRITE_FAILED';this.phase='LOCAL_SAVE_FAILED';}
      this.notify();return this.snapshot();
    })().finally(()=>{this.finalizing=undefined;});
    return this.finalizing;
  }
  /** Retries only retained original bytes/metadata after capture stopped; never obtains a device or extends time. */
  retrySave():Promise<CaptureSnapshot>{
    if(!this.stopObserved)return Promise.reject(new CaptureError('NOT_STOPPED'));
    if(this.retrying)return this.retrying;
    this.retrying=(async()=>{
      await this.completion;
      if(this.phase!=='LOCAL_SAVE_FAILED')return this.snapshot();
      if(this.draining||this.terminalSaving)return this.snapshot();
      await this.finalizing;
      this.blocked=false;this.drain();return this.finalize();
    })().finally(()=>{this.retrying=undefined;});
    return this.retrying;
  }
}
