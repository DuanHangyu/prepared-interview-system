import {PreparedAudioJournal,LocalAudioError,type Segment} from './localAudioJournal';
import {PreparedUploadJournal} from './uploadJournal';
import {makeUploadIntent,uploadCheck,uploadHash,UploadError,verifyBundleSummary,type UploadOwner,type UploadIntent,type UploadAnswer} from './uploadBundle';
import {acquireCaptureWriter} from './captureWriter';
import {PreparedUploadTransport,UploadTransportError} from '@/api/preparedUpload';
import {encodeAnswerEndManifest,type AnswerEndManifest} from './answerEndManifest';
import {verifyUploadWindow,type UploadWindow} from './uploadWindow';
export type UploadProgress={phase:'LOCAL_ONLY'|'OFFLINE'|'CLOSED'|'SENDING'|'BYTES_CONFIRMED'|'WAITING'|'WAITING_APPROVAL'|'IDENTITY_REQUIRED'|'NEEDS_REVIEW';confirmedChunks:number;confirmedBytes:number;retryAt?:number;windows?:Array<UploadWindow & {ordinal:number}>};
type Options={owner:UploadOwner;assessmentId:number;attemptId:string;audio:PreparedAudioJournal;journal:PreparedUploadJournal;authority:(signal:AbortSignal)=>Promise<UploadOwner & {workflowEnabled:boolean}>;
  transport?:Pick<PreparedUploadTransport,'prepare'|'status'|'upload'|'window'>;online?:()=>boolean;random?:()=>number;now?:()=>number;
  withLock?:<T>(key:string,signal:AbortSignal,body:()=>Promise<T>)=>Promise<T>};
/** One bounded original group per cycle, independent of recorder writes and end/next-question metadata. */
export class PreparedBackgroundUpload {
  readonly attemptId:string;
  private readonly transport;private readonly owner:UploadOwner;private readonly confirmed=new Map<string,{chunks:number;bytes:number}>();
  private active?:AbortController;private closed=false;private failureCount=0;private retryAt=0;private halted=false;private cursor=0;
  private readonly review=new Set<string>();private readonly waiting=new Set<string>();
  private readonly windows=new Map<string,UploadWindow & {ordinal:number}>();private readonly originals=new Map<string,string>();
  constructor(private readonly options:Options){
    uploadCheck(typeof options.attemptId==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(options.attemptId));
    this.attemptId=options.attemptId;this.owner={...options.owner};this.transport=options.transport??new PreparedUploadTransport();
  }
  private now(){return (this.options.now??Date.now)();}
  private state(phase:UploadProgress['phase']):UploadProgress{return {phase,confirmedChunks:Array.from(this.confirmed.values()).reduce((n,v)=>n+v.chunks,0),confirmedBytes:Array.from(this.confirmed.values()).reduce((n,v)=>n+v.bytes,0),...(this.retryAt?{retryAt:this.retryAt}:{}),...(this.windows.size?{windows:Array.from(this.windows.values(),v=>({...v}))}:{})};}
  private live(c:AbortController){uploadCheck(!this.closed&&!c.signal.aborted,'CLOSED');}
  private async verify(c:AbortController){this.live(c);const fresh=await this.options.authority(c.signal);this.live(c);
    if(!fresh||fresh.origin!==this.owner.origin||fresh.studentId!==this.owner.studentId||fresh.deploymentId!==this.owner.deploymentId)throw new UploadTransportError(401);
    return fresh.workflowEnabled===true;
  }
  private async lock<T>(c:AbortController,body:()=>Promise<T>){const key=await this.options.journal.writerKey();this.live(c);
    if(this.options.withLock)return this.options.withLock(key,c.signal,body);
    const lease=await acquireCaptureWriter(key,undefined,c.signal),unsubscribe=lease.onLost(()=>c.abort());
    try{this.live(c);return await body();}finally{unsubscribe();lease.release();}
  }
  private async plan(answer:UploadAnswer,segment:Segment,used:Set<number>):Promise<UploadIntent|undefined>{
    uploadCheck(Number.isSafeInteger(segment.chunkCount)&&segment.chunkCount>=0&&segment.chunkCount<=10000&&['OPEN','SEALED','INTERRUPTED'].includes(segment.state),'INTEGRITY');
    const indices=[];for(let index=0;index<segment.chunkCount&&indices.length<32;index++)if(!used.has(index))indices.push(index);
    if(!indices.length||(segment.state==='OPEN'&&indices.length<15))return;
    // A delayed native event may be larger: reduce only BEFORE any original intent has been registered.
    for(;;){try{const window=await this.options.audio.readUploadWindow(answer,segment.id,indices);return await makeUploadIntent(this.owner.studentId,answer,window.segment,window.chunks);}
      catch(e){if((e as {code?:string}).code!=='LIMIT'||indices.length<=1)throw e;indices.splice(Math.max(1,Math.floor(indices.length/2)));}}
  }
  private async bytes(i:UploadIntent){const read=await this.options.audio.readUploadWindow(i.answer,i.command.segmentId,i.command.chunks.map(c=>c.index));
    uploadCheck(read.segment.ordinal===i.command.segmentOrdinal&&read.segment.mimeType===i.command.mimeType&&JSON.stringify(read.chunks.map(({index,byteSize,sha256})=>({byteSize,index,sha256})))===JSON.stringify(i.command.chunks),'INTEGRITY');
    const blob=new Blob(read.chunks.map(c=>c.blob));uploadCheck(blob.size===i.command.byteSize&&await uploadHash(blob)===i.command.sha256,'INTEGRITY');return blob;
  }
  async run():Promise<UploadProgress>{
    if(this.closed)return this.state('CLOSED');if(this.active)return this.state('SENDING');if(this.halted)return this.state('NEEDS_REVIEW');
    if(!(this.options.online??(()=>navigator.onLine!==false))())return this.state('OFFLINE');if(this.now()<this.retryAt)return this.state('WAITING');
    const c=new AbortController();this.active=c;let selectedAnswer:UploadAnswer|undefined;
    try{
      if(!await this.verify(c))return this.state('CLOSED');
      return await this.lock(c,async()=>{
        const answers=await this.options.journal.answers(this.attemptId);this.live(c);let selected:UploadIntent|undefined,isNew=false;
        for(let offset=0;offset<answers.length;offset++){
          const position=(this.cursor+offset)%answers.length,a=answers[position],intents=await this.options.journal.intents(a);this.live(c);
          if(this.review.has(a.answerId))continue;selectedAnswer=a;
          selected=intents.find(i=>!this.confirmed.has(i.bundleId));
          if(!selected){const segments=await this.options.audio.list(a);this.live(c);uploadCheck(segments.length<=1000,'INTEGRITY');
            for(const segment of segments){const used=new Set(intents.filter(i=>i.command.segmentId===segment.id).flatMap(i=>i.command.chunks.map(v=>v.index)));
              selected=await this.plan(a,segment,used);this.live(c);if(selected){selected=await this.options.journal.reserve(selected);this.live(c);isNew=true;break;}}
          }
          if(selected){this.cursor=(position+1)%answers.length;break;}
        }
        if(!selected)return this.state(this.review.size?'NEEDS_REVIEW':this.waiting.size?'WAITING_APPROVAL':'LOCAL_ONLY');const i=selected;
        if(!await this.verify(c))return this.state('CLOSED');
        let originalHash=this.originals.get(i.answer.answerId);
        if(!originalHash){
          const rows=await this.options.audio.list(i.answer);this.live(c);
          if(rows.some(s=>s.state==='OPEN')){uploadCheck(!this.waiting.has(i.answer.answerId),'MISSING_ORIGINAL');}
          else try{const end=await this.options.audio.recoverAnswerEnd(i.answer);this.live(c);uploadCheck(end.captureClientId===i.answer.captureClientId,'INTEGRITY');
            originalHash=await uploadHash(encodeAnswerEndManifest(end.manifest));this.live(c);this.originals.set(i.answer.answerId,originalHash);
          }catch(e){if(!(e instanceof LocalAudioError)||e.code!=='MISSING_ANSWER_END')throw e;if(this.waiting.has(i.answer.answerId))throw new UploadError('MISSING_ORIGINAL');}
        }
        if(originalHash){
          const rawWindow=await this.transport.window(i.answer,c.signal);this.live(c);if(!await this.verify(c))return this.state('CLOSED');
          const window=verifyUploadWindow(rawWindow,this.owner.studentId,i.answer,originalHash);this.windows.set(i.answer.answerId,{...window,ordinal:i.answer.ordinal});
          if(!window.mayUploadOriginal){if(window.phase==='NOT_ELIGIBLE'){this.review.add(i.answer.answerId);this.waiting.delete(i.answer.answerId);return this.state('NEEDS_REVIEW');}
            this.waiting.add(i.answer.answerId);return this.state('WAITING_APPROVAL');}
          this.waiting.delete(i.answer.answerId);
        }
        // Every reopening re-queries the original server batch. Only this session's exact verified receipts skip IO.
        let raw:unknown;
        if(isNew)raw=await this.transport.prepare(i,c.signal);
        else{try{raw=await this.transport.status(i,c.signal);}catch(e){if(!(e instanceof UploadTransportError)||e.status!==404)throw e;
          if(!await this.verify(c))return this.state('CLOSED');raw=await this.transport.prepare(i,c.signal);}}
        this.live(c);if(!await this.verify(c))return this.state('CLOSED');let summary=verifyBundleSummary(i,raw);
        if(!summary.confirmed){const blob=await this.bytes(i);this.live(c);if(!await this.verify(c))return this.state('CLOSED');
          raw=await this.transport.upload(i,blob,c.signal);this.live(c);if(!await this.verify(c))return this.state('CLOSED');summary=verifyBundleSummary(i,raw);}
        uploadCheck(summary.confirmed,'INTEGRITY');this.confirmed.set(i.bundleId,{chunks:i.command.chunks.length,bytes:i.command.byteSize});this.failureCount=0;this.retryAt=0;
        return this.state('BYTES_CONFIRMED');
      });
    }catch(e){
      if(this.closed||c.signal.aborted)return this.state('CLOSED');
      if(e instanceof UploadTransportError&&[401,403].includes(e.status)){this.confirmed.clear();this.windows.clear();return this.state('IDENTITY_REQUIRED');}
      if(e instanceof UploadTransportError&&e.status===409&&e.reasonCode==='UPLOAD_WINDOW_EXPIRED'&&selectedAnswer){this.waiting.add(selectedAnswer.answerId);return this.state('WAITING_APPROVAL');}
      if(e instanceof UploadError&&['INVALID','INTEGRITY','CONFLICT','MISSING_ORIGINAL'].includes(e.code)||e instanceof LocalAudioError&&['INVALID','INTEGRITY','CONFLICT','NOT_FOUND','LIMIT'].includes(e.code)||e instanceof UploadTransportError&&[400,409,413,415].includes(e.status)){
        if(selectedAnswer){this.review.add(selectedAnswer.answerId);this.waiting.delete(selectedAnswer.answerId);}else this.halted=true;return this.state('NEEDS_REVIEW');}
      this.failureCount=Math.min(this.failureCount+1,8);const jitter=1+Math.max(0,Math.min(1,(this.options.random??Math.random)()));
      const backoff=Math.min(60000,2000*2**(this.failureCount-1))*jitter,hint=e instanceof UploadTransportError?e.retryAfter*1000:0;
      this.retryAt=this.now()+Math.max(backoff,hint);return this.state('WAITING');
    }finally{if(this.active===c)this.active=undefined;}
  }
  /** This session's exact authenticated byte receipts, not inferred from local bytes or HTTP 200.
   * Refresh must rebuild this evidence through original batch queries before queuing media work. */
  async isConfirmed(answer:UploadAnswer,manifest:AnswerEndManifest):Promise<boolean>{
    if(this.closed||answer.attemptId!==this.attemptId||answer.assessmentId!==this.options.assessmentId)return false;
    const fixed=JSON.parse(encodeAnswerEndManifest(manifest)) as AnswerEndManifest;
    if(!fixed.segments.length||fixed.segments.some(s=>s.state!=='SEALED'))return false;
    const intents=await this.options.journal.intents(answer);if(this.closed)return false;
    const expected=new Map(fixed.segments.flatMap(s=>s.chunks.map(c=>[`${s.id}:${c.index}`,{...c,ordinal:s.ordinal,mime:s.mimeType}] as const))),seen=new Set<string>();
    for(const i of intents){
      if(!this.confirmed.has(i.bundleId))return false;
      for(const c of i.command.chunks){const key=`${i.command.segmentId}:${c.index}` as const,original=expected.get(key);
        uploadCheck(original&&!seen.has(key)&&original.ordinal===i.command.segmentOrdinal&&original.mime===i.command.mimeType&&original.byteSize===c.byteSize&&original.sha256===c.sha256,'INTEGRITY');seen.add(key);}
    }
    return seen.size>0&&seen.size===expected.size;
  }
  close(){this.closed=true;this.active?.abort();this.confirmed.clear();this.windows.clear();this.originals.clear();this.waiting.clear();this.review.clear();}
}
