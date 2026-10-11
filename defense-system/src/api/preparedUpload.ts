import {copyBundleCommand,copyUploadAnswer,uploadCheck,type UploadIntent,type UploadOwner,type UploadAnswer} from '@/prepared/uploadBundle';
/** Quiet dedicated transport: no global logout/modal, automatic resend or recording-state mutation. */
export class UploadTransportError extends Error {
  constructor(public readonly status:number,public readonly retryAfter=0,public readonly reasonCode?:'UPLOAD_WINDOW_EXPIRED'){super('后台上传暂未确认，本机原录音保留');this.name='UploadTransportError';}
}
export class PreparedUploadTransport {
  private base(){const base=import.meta.env.VUE_APP_BASE_API||'/dev-api';uploadCheck(typeof base==='string'&&/^\/(?!\/)[a-zA-Z0-9/_-]*$/.test(base));return base.replace(/\/$/,'');}
  private path(i:UploadIntent){
    const a=copyUploadAnswer(i.answer);uploadCheck(/^[a-f0-9]{64}$/.test(i.bundleId));
    return `${this.base()}/backend/prepared-interviews/${a.assessmentId}/attempts/${a.attemptId}/answers/${a.answerId}/upload-bundles`;
  }
  private async request(url:string,method:string,body:Blob|string|undefined,signal:AbortSignal,timeout:number):Promise<unknown>{
    const controller=new AbortController(),abort=()=>controller.abort();signal.addEventListener('abort',abort,{once:true});if(signal.aborted)abort();
    const timer=setTimeout(abort,timeout);let reader:ReadableStreamDefaultReader<Uint8Array>|undefined;
    try{
      const r=await fetch(url,{method,body,signal:controller.signal,credentials:'same-origin',redirect:'error',cache:'no-store',headers:{Accept:'application/json',...(body!==undefined?{'Content-Type':body instanceof Blob?'application/octet-stream':'application/json'}:{})}});
      const seconds=Number(r.headers.get('retry-after')),retry=Number.isFinite(seconds)&&seconds>0?Math.min(seconds,3600):0;
      const jsonType=r.headers.get('content-type')?.toLowerCase().includes('application/json');
      const readJson=async():Promise<{code?:number;data?:unknown}>=>{
        if(!r.body)throw new UploadTransportError(502);reader=r.body.getReader();let size=0;const pieces:Uint8Array[]=[];
        for(;;){const part=await reader.read();if(part.done)break;size+=part.value.length;if(size>65536)throw new UploadTransportError(502);pieces.push(part.value);}
        const bytes=new Uint8Array(size);let offset=0;for(const p of pieces){bytes.set(p,offset);offset+=p.length;}
        try{return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));}catch{throw new UploadTransportError(502);}
      };
      if(!r.ok){let reason:'UPLOAD_WINDOW_EXPIRED'|undefined;
        if(r.status===409&&jsonType)try{const error=await readJson(),data=error?.data as {reasonCode?:unknown}|undefined;
          if(error?.code===409&&data&&typeof data==='object'&&!Array.isArray(data)&&Object.keys(data).join(',')==='reasonCode'&&data.reasonCode==='UPLOAD_WINDOW_EXPIRED')reason='UPLOAD_WINDOW_EXPIRED';
        }catch{/* Malformed or oversized errors never grant recovery permission. Keep the HTTP conflict. */}
        throw new UploadTransportError(r.status,retry,reason);
      }
      uploadCheck(jsonType,'INTEGRITY');const json=await readJson();
      if(!json||typeof json!=='object'||json.code!==200||!Object.prototype.hasOwnProperty.call(json,'data'))throw new UploadTransportError(typeof json?.code==='number'&&json.code>=400&&json.code<=599?json.code:502,retry);
      if(controller.signal.aborted)throw new UploadTransportError(0);return json.data;
    }catch(e){if(e instanceof UploadTransportError)throw e;throw new UploadTransportError(e instanceof Error&&e.name==='UploadError'?502:0);}
    finally{clearTimeout(timer);signal.removeEventListener('abort',abort);void reader?.cancel().catch(()=>{});}
  }
  prepare(i:UploadIntent,signal:AbortSignal){return this.request(this.path(i),'POST',JSON.stringify(copyBundleCommand(i.command)),signal,10000);}
  status(i:UploadIntent,signal:AbortSignal){return this.request(`${this.path(i)}/${i.bundleId}`,'GET',undefined,signal,10000);}
  upload(i:UploadIntent,blob:Blob,signal:AbortSignal){uploadCheck(blob instanceof Blob&&blob.size===i.command.byteSize&&blob.size<=8388608);return this.request(`${this.path(i)}/${i.bundleId}`,'PUT',blob,signal,120000);}
  window(answer:UploadAnswer,signal:AbortSignal){const a=copyUploadAnswer(answer);return this.request(`${this.base()}/backend/prepared-interviews/${a.assessmentId}/attempts/${a.attemptId}/answers/${a.answerId}/upload-window?captureClientId=${a.captureClientId}`,'GET',undefined,signal,10000);}
  private mediaPath(answer:UploadAnswer){const a=copyUploadAnswer(answer);return `${this.base()}/backend/prepared-interviews/${a.assessmentId}/attempts/${a.attemptId}/answers/${a.answerId}/media`;}
  mediaStatus(answer:UploadAnswer,signal:AbortSignal){return this.request(this.mediaPath(answer),'GET',undefined,signal,10000);}
  queueMedia(answer:UploadAnswer,manifestSha256:string,signal:AbortSignal){const a=copyUploadAnswer(answer);uploadCheck(/^[a-f0-9]{64}$/.test(manifestSha256));
    return this.request(this.mediaPath(a),'POST',JSON.stringify({captureClientId:a.captureClientId,manifestSha256}),signal,10000);}
  private submissionPath(assessmentId:number,attemptId:string){uploadCheck(Number.isSafeInteger(assessmentId)&&assessmentId>0&&assessmentId<=2147483647&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(attemptId));
    return `${this.base()}/backend/prepared-interviews/${assessmentId}/attempts/${attemptId}/submission`;}
  submissionStatus(assessmentId:number,attemptId:string,signal:AbortSignal){return this.request(this.submissionPath(assessmentId,attemptId),'GET',undefined,signal,10000);}
  submitAttempt(assessmentId:number,attemptId:string,command:{idempotencyKey:string;expectedRevision:number},signal:AbortSignal){
    const path=this.submissionPath(assessmentId,attemptId);uploadCheck(!!command&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(command.idempotencyKey)&&Number.isSafeInteger(command.expectedRevision)&&command.expectedRevision>=0);
    return this.request(path,'POST',JSON.stringify({idempotencyKey:command.idempotencyKey,expectedRevision:command.expectedRevision}),signal,10000);}
  async authority(assessmentId:number,signal:AbortSignal):Promise<UploadOwner & {workflowEnabled:boolean}>{
    uploadCheck(Number.isSafeInteger(assessmentId)&&assessmentId>0&&assessmentId<=2147483647);
    const user=await this.request(`${this.base()}/user-detail`,'GET',undefined,signal,10000) as {id?:number|string;role?:string};
    if(!user||user.role!=='STUDENT'||!Number.isSafeInteger(Number(user.id))||Number(user.id)<=0)throw new UploadTransportError(401);
    const c=await this.request(`${this.base()}/backend/prepared-interviews/${assessmentId}/preflight-context`,'GET',undefined,signal,10000) as {studentId?:number;deploymentId?:string;workflowEnabled?:boolean;eligibility?:{assessmentId?:number}};
    if(!c||c.studentId!==Number(user.id)||!/^prepared-[a-z0-9][a-z0-9-]{0,70}$/.test(c.deploymentId??'')||c.eligibility?.assessmentId!==assessmentId)throw new UploadTransportError(401);
    return {origin:location.origin,studentId:c.studentId,deploymentId:c.deploymentId!,workflowEnabled:c.workflowEnabled===true};
  }
}
