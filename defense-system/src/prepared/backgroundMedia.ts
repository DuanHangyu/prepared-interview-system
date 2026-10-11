import {PreparedAudioJournal,LocalAudioError} from './localAudioJournal';
import {PreparedUploadJournal} from './uploadJournal';
import {encodeAnswerEndManifest,type AnswerEndManifest} from './answerEndManifest';
import {copyUploadAnswer,uploadHash,uploadCheck,UploadError,type UploadOwner,type UploadAnswer} from './uploadBundle';
import {PreparedUploadTransport,UploadTransportError} from '@/api/preparedUpload';
import {verifyUploadWindow} from './uploadWindow';
export type AnswerMediaSummary={answerId:string;manifestSha256:string|null;jobId:string|null;phase:'NOT_QUEUED'|'QUEUED'|'RUNNING'|'RETRY_WAIT'|'WAITING_APPROVAL'|'NEEDS_REVIEW'|'SAVED';serverSaved:boolean;revision:number;durationMs:number|null;verifiedAt:string|null};
export type MediaProgress={phase:'LOCAL_ONLY'|'OFFLINE'|'CLOSED'|'WAITING_END'|'WAITING_BYTES'|'VERIFYING'|'SAVED'|'WAITING'|'WAITING_APPROVAL'|'IDENTITY_REQUIRED'|'NEEDS_REVIEW';savedAnswers:number;answerId?:string;retryAt?:number};
const uuid=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
/** Only current authenticated whole-media receipts, never a byte acknowledgement or final submission. */
export function verifyAnswerMediaSummary(answer:UploadAnswer,hash:string,value:unknown):AnswerMediaSummary {
  const a=copyUploadAnswer(answer),r=value as AnswerMediaSummary;
  uploadCheck(/^[a-f0-9]{64}$/.test(hash)&&!!r&&r.answerId===a.answerId&&['NOT_QUEUED','QUEUED','RUNNING','RETRY_WAIT','WAITING_APPROVAL','NEEDS_REVIEW','SAVED'].includes(r.phase)
    &&typeof r.serverSaved==='boolean'&&Number.isSafeInteger(r.revision)&&r.revision>=0,'INTEGRITY');
  uploadCheck(r.manifestSha256===hash||(r.phase==='NOT_QUEUED'&&r.manifestSha256===null),'INTEGRITY');
  uploadCheck(r.phase==='NOT_QUEUED'?r.jobId===null:uuid(r.jobId)&&r.revision>=1,'INTEGRITY');
  if(r.phase==='SAVED')uploadCheck(r.serverSaved===true&&r.revision>=2&&Number.isSafeInteger(r.durationMs)&&r.durationMs!>0&&r.durationMs!<=1800000
    &&typeof r.verifiedAt==='string'&&/^\d{4}-\d{2}-\d{2}T/.test(r.verifiedAt)&&Number.isFinite(Date.parse(r.verifiedAt)),'INTEGRITY');
  else uploadCheck(r.serverSaved===false&&r.durationMs===null&&r.verifiedAt===null,'INTEGRITY');
  return {answerId:r.answerId,manifestSha256:r.manifestSha256,jobId:r.jobId,phase:r.phase,serverSaved:r.serverSaved,revision:r.revision,durationMs:r.durationMs,verifiedAt:r.verifiedAt};
}
type Options={owner:UploadOwner;attemptId:string;audio:PreparedAudioJournal;journal:PreparedUploadJournal;
  authority:(signal:AbortSignal)=>Promise<UploadOwner & {workflowEnabled:boolean}>;
  bytesConfirmed:(answer:UploadAnswer,manifest:AnswerEndManifest)=>Promise<boolean>;
  transport?:Pick<PreparedUploadTransport,'mediaStatus'|'queueMedia'|'window'>;online?:()=>boolean;random?:()=>number;now?:()=>number};
/** One original answer per cycle. No recording, local SAVED flag, deletion, replacement command,
 * final submission or model call. Refresh re-reads original metadata and queries the server first. */
export class PreparedBackgroundMedia {
  readonly attemptId:string;
  private readonly owner:UploadOwner;private readonly transport;
  private readonly originals=new Map<string,{hash:string;manifest:AnswerEndManifest}>();
  private readonly saved=new Map<string,AnswerMediaSummary>();private readonly review=new Set<string>();
  private readonly waiting=new Set<string>();
  private active?:AbortController;private closed=false;private cursor=0;private retryAt=0;private failures=0;
  constructor(private readonly options:Options){uploadCheck(uuid(options.attemptId));this.attemptId=options.attemptId;this.owner={...options.owner};this.transport=options.transport??new PreparedUploadTransport();}
  receipt(answerId:string){const r=this.saved.get(answerId);return r?{...r}:undefined;}
  private now(){return (this.options.now??Date.now)();}
  private progress(phase:MediaProgress['phase'],answerId?:string):MediaProgress{return {phase,savedAnswers:this.saved.size,...(answerId?{answerId}:{}),...(this.retryAt?{retryAt:this.retryAt}:{})};}
  private live(c:AbortController){uploadCheck(!this.closed&&!c.signal.aborted,'CLOSED');}
  private async verify(c:AbortController){this.live(c);const fresh=await this.options.authority(c.signal);this.live(c);
    if(!fresh||fresh.origin!==this.owner.origin||fresh.studentId!==this.owner.studentId||fresh.deploymentId!==this.owner.deploymentId)throw new UploadTransportError(401);return fresh.workflowEnabled===true;
  }
  async run():Promise<MediaProgress>{
    if(this.closed)return this.progress('CLOSED');if(this.active)return this.progress('VERIFYING');
    if(!(this.options.online??(()=>navigator.onLine!==false))())return this.progress('OFFLINE');if(this.now()<this.retryAt)return this.progress('WAITING');
    const c=new AbortController();this.active=c;let selected:UploadAnswer|undefined;
    try {
      const enabled=await this.verify(c),answers=await this.options.journal.answers(this.attemptId);this.live(c);const cursor=this.cursor;
      for(let offset=0;offset<answers.length;offset++) {
        const index=(cursor+offset)%answers.length,a=answers[index];if(this.saved.has(a.answerId)||this.review.has(a.answerId))continue;
        selected=a;this.cursor=(index+1)%answers.length;
        let original=this.originals.get(a.answerId);
        if(!original){
          const rows=await this.options.audio.list(a);this.live(c);if(!rows.length||rows.some(s=>s.state==='OPEN'))continue;
          let end;try{end=await this.options.audio.recoverAnswerEnd(a);}catch(e){if(e instanceof LocalAudioError&&e.code==='MISSING_ANSWER_END')continue;throw e;}
          this.live(c);uploadCheck(end.captureClientId===a.captureClientId,'INTEGRITY');
          original={manifest:end.manifest,hash:await uploadHash(encodeAnswerEndManifest(end.manifest))};this.live(c);this.originals.set(a.answerId,original);
        }
        if(original.manifest.segments.some(s=>s.state!=='SEALED')){this.review.add(a.answerId);return this.progress('NEEDS_REVIEW',a.answerId);}
        let r=verifyAnswerMediaSummary(a,original.hash,await this.transport.mediaStatus(a,c.signal));this.live(c);await this.verify(c);
        if(r.phase==='NOT_QUEUED'||r.phase==='WAITING_APPROVAL') {
          if(r.manifestSha256===null)return this.progress('WAITING_END',a.answerId);
          if(!enabled)return this.progress('CLOSED',a.answerId);
          if(r.phase==='WAITING_APPROVAL'||this.waiting.has(a.answerId)){
            const raw=await this.transport.window(a,c.signal);this.live(c);if(!await this.verify(c))return this.progress('CLOSED',a.answerId);
            const window=verifyUploadWindow(raw,this.owner.studentId,a,original.hash);
            if(!window.mayUploadOriginal){if(window.phase==='NOT_ELIGIBLE'){this.waiting.delete(a.answerId);this.review.add(a.answerId);return this.progress('NEEDS_REVIEW',a.answerId);}return this.progress('WAITING_APPROVAL',a.answerId);}
            // An already-expired queued job needs an actual teacher approval. A clock
            // rollback or an unrelated automatic window cannot revive that original task.
            uploadCheck(r.phase!=='WAITING_APPROVAL'||window.phase==='APPROVED','INTEGRITY');
            this.waiting.delete(a.answerId);
          }
          const ready=await this.options.bytesConfirmed(a,original.manifest);this.live(c);if(!ready)return this.progress('WAITING_BYTES',a.answerId);
          if(!await this.verify(c))return this.progress('CLOSED',a.answerId);
          r=verifyAnswerMediaSummary(a,original.hash,await this.transport.queueMedia(a,original.hash,c.signal));this.live(c);await this.verify(c);
        }
        this.failures=0;this.retryAt=0;
        if(r.phase==='WAITING_APPROVAL'){this.waiting.add(a.answerId);return this.progress('WAITING_APPROVAL',a.answerId);}
        if(r.phase==='SAVED'){this.saved.set(a.answerId,r);return this.progress('SAVED',a.answerId);}
        if(r.phase==='NEEDS_REVIEW'){this.review.add(a.answerId);return this.progress('NEEDS_REVIEW',a.answerId);}
        return this.progress('VERIFYING',a.answerId);
      }
      return this.progress(this.review.size?'NEEDS_REVIEW':this.saved.size?'SAVED':'LOCAL_ONLY');
    }catch(e){
      if(this.closed||c.signal.aborted)return this.progress('CLOSED');
      if(e instanceof UploadTransportError&&[401,403].includes(e.status)){this.saved.clear();return this.progress('IDENTITY_REQUIRED');}
      if(e instanceof UploadTransportError&&e.status===409&&e.reasonCode==='UPLOAD_WINDOW_EXPIRED'&&selected){this.waiting.add(selected.answerId);return this.progress('WAITING_APPROVAL',selected.answerId);}
      if(e instanceof UploadError&&['INVALID','INTEGRITY','CONFLICT'].includes(e.code)||e instanceof LocalAudioError&&['INTEGRITY','CONFLICT','LIMIT','MISSING_TERMINAL'].includes(e.code)||e instanceof UploadTransportError&&[400,409,413,415].includes(e.status)){
        if(selected)this.review.add(selected.answerId);return this.progress('NEEDS_REVIEW',selected?.answerId);
      }
      this.failures=Math.min(this.failures+1,8);const jitter=1+Math.max(0,Math.min(1,(this.options.random??Math.random)()));
      this.retryAt=this.now()+Math.max(Math.min(60000,2000*2**(this.failures-1))*jitter,e instanceof UploadTransportError?e.retryAfter*1000:0);return this.progress('WAITING',selected?.answerId);
    }finally{if(this.active===c)this.active=undefined;}
  }
  close(){this.closed=true;this.active?.abort();this.saved.clear();this.originals.clear();this.review.clear();this.waiting.clear();}
}
