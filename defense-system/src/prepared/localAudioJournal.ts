import {copyAnswerEndCommand,encodeAnswerEndManifest,type AnswerEndCommand,type AnswerEndManifest} from './answerEndManifest';
/** Application-account isolation, not encryption or proof of offline capture time. Formal journals have no deletion or upload API; only disposable preflight scratch may be discarded. */
export type AnswerScope={assessmentId:number;attemptId:string;answerId:string};
export type SegmentInput={id:string;ordinal:number;mimeType:string;startedAt:number};
export type Interruption='PAGE_REFRESH'|'DEVICE_ENDED'|'CAPTURE_ERROR'|'LOCAL_WRITE_FAILED'|'SESSION_CHANGED';
export type Segment=SegmentInput & {state:'OPEN'|'SEALED'|'INTERRUPTED';chunkCount:number;byteSize:number;endedAt:number|null;interruption:Interruption|null};
export type Chunk={index:number;byteSize:number;sha256:string;blob:Blob};
export type TerminalCommand={expectedChunks:number;endedAt:number;reason:Interruption|null};
type StoredSegment=Segment & {namespace:string;terminal?:TerminalCommand;answerEnd?:AnswerEndCommand;answerEndSha256?:string};
type StoredEnding={command:AnswerEndCommand;sha256:string};
type StoredChunk=Chunk & {namespace:string;segmentId:string;mimeType:string};
type Code='INVALID'|'UNAVAILABLE'|'CONFLICT'|'NOT_FOUND'|'INTEGRITY'|'CLOSED'|'WRITE_FAILED'|'LIMIT'|'MISSING_TERMINAL'|'MISSING_ANSWER_END';
const messages:Record<Code,string>={INVALID:'本地录音参数无效',UNAVAILABLE:'本地录音存储不可用',CONFLICT:'原录音与此操作冲突，请保留原内容',NOT_FOUND:'本账号或本回答未找到录音',INTEGRITY:'本地录音完整性核验失败，请保留原内容',CLOSED:'本地录音句柄已关闭，请重新核验同账号身份',WRITE_FAILED:'本机保存失败，请保留已存内容并联系教师',LIMIT:'本地录音达到安全大小限制，请保留原内容',MISSING_TERMINAL:'原录音尚无可靠结束依据，请保留已有内容并按故障流程恢复',MISSING_ANSWER_END:'本题尚无可靠的原结束请求，请保留原内容'};
export class LocalAudioError extends Error {
  constructor(public readonly code:Code){super(messages[code]);this.name='LocalAudioError';}
}
const DB='prepared-interview-audio-v1';
const TRIAL_DB='prepared-interview-preflight-v1';
const MAX_CHUNK=2*1024*1024, MAX_SEGMENT=64*1024*1024;
const MIMES=new Set(['audio/webm;codecs=opus','audio/webm','audio/ogg;codecs=opus','audio/ogg','audio/mp4']);
const INTERRUPTIONS=new Set<Interruption>(['PAGE_REFRESH','DEVICE_ENDED','CAPTURE_ERROR','LOCAL_WRITE_FAILED','SESSION_CHANGED']);
const invalid=(valid:boolean,code:Code='INVALID')=>{if(!valid)throw new LocalAudioError(code);};
function integer(value:number,min:number,max:number){invalid(Number.isSafeInteger(value)&&value>=min&&value<=max);}
function uuid(value:string){invalid(typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value));}
function timestamp(value:number){integer(value,0,8640000000000000);}
async function digest(blob:Blob){
  try {
    const value=await crypto.subtle.digest('SHA-256',await blob.arrayBuffer());
    return Array.from(new Uint8Array(value),byte=>byte.toString(16).padStart(2,'0')).join('');
  } catch {throw new LocalAudioError('WRITE_FAILED');}
}
const publicSegment=({namespace:_,terminal:__,answerEnd:___,answerEndSha256:____,...value}:StoredSegment):Segment=>value;
const endingMetadata=(s:Omit<Segment,'interruption'> & {interruption:string|null})=>({id:s.id,ordinal:s.ordinal,mimeType:s.mimeType,startedAt:s.startedAt,state:s.state,chunkCount:s.chunkCount,byteSize:s.byteSize,endedAt:s.endedAt,interruption:s.interruption});
const sameEndingRows=(rows:Segment[],manifest:AnswerEndManifest)=>JSON.stringify(rows.map(endingMetadata))===JSON.stringify(manifest.segments.map(endingMetadata));
const endingBytes=(command:AnswerEndCommand)=>new Blob([JSON.stringify({captureClientId:command.captureClientId,idempotencyKey:command.idempotencyKey,manifest:JSON.parse(encodeAnswerEndManifest(command.manifest))})]);
function terminal(input:TerminalCommand):TerminalCommand {
  invalid(Boolean(input)&&typeof input==='object');
  const command={expectedChunks:input.expectedChunks,endedAt:input.endedAt,reason:input.reason};
  integer(command.expectedChunks,0,10000);timestamp(command.endedAt);
  invalid(command.reason===null||INTERRUPTIONS.has(command.reason));invalid(command.reason!==null||command.expectedChunks>0);
  return command;
}
function sameTerminal(a:TerminalCommand,b:TerminalCommand){return a.expectedChunks===b.expectedChunks&&a.endedAt===b.endedAt&&a.reason===b.reason;}
type Reader=<V>(request:IDBRequest<V>,callback:(value:V)=>void)=>void;

export class PreparedAudioJournal {
  private readonly origin:string;
  private readonly studentId:number;
  private readonly deploymentId:string;
  private connection:Promise<IDBDatabase>|undefined;
  private db:IDBDatabase|undefined;
  private closed=false;
  constructor(identity:{origin:string;studentId:number;deploymentId:string},private readonly factory:IDBFactory=globalThis.indexedDB,private readonly purpose:'answer'|'preflight'='answer'){
    invalid(purpose==='answer'||purpose==='preflight');
    invalid(Boolean(identity)&&typeof identity==='object');
    integer(identity.studentId,1,2147483647);
    invalid(typeof identity.deploymentId==='string'&&/^prepared-[a-z0-9][a-z0-9-]{0,70}$/.test(identity.deploymentId));
    this.deploymentId=identity.deploymentId;
    try {
      const source=new URL(identity.origin);
      invalid(source.origin===identity.origin&&!source.username&&!source.password);
      invalid(source.protocol==='https:'||(source.protocol==='http:'&&['localhost','127.0.0.1','[::1]'].includes(source.hostname)));
      this.origin=source.origin;this.studentId=identity.studentId;
    } catch {throw new LocalAudioError('INVALID');}
    invalid(Boolean(factory)&&typeof factory.open==='function','UNAVAILABLE');
  }
  private namespace(scope:AnswerScope){
    invalid(Boolean(scope)&&typeof scope==='object');
    this.assertOpen();integer(scope.assessmentId,1,2147483647);uuid(scope.attemptId);uuid(scope.answerId);
    return JSON.stringify([1,this.deploymentId,this.origin,this.studentId,scope.assessmentId,scope.attemptId,scope.answerId]);
  }
  private assertOpen(){invalid(!this.closed,'CLOSED');}
  /** Stable assessment-wide writer identity. Trial scratch cannot contend with or identify formal answers. */
  async captureWriterKey(scope:AnswerScope):Promise<string>{
    this.namespace(scope); // Validate all scope fields before asynchronous hashing; this does not authorize an answer.
    const parts:unknown[]=[1,this.purpose,this.deploymentId,this.origin,this.studentId,scope.assessmentId];
    if(this.purpose==='preflight')parts.push(scope.attemptId,scope.answerId);
    const key=await digest(new Blob([JSON.stringify(parts)]));this.assertOpen();
    return 'prepared-capture-v1:'+key;
  }
  private async database(){
    this.assertOpen();
    if(!this.connection){
      this.connection=new Promise<IDBDatabase>((resolve,reject)=>{
        let settled=false;
        let request:IDBOpenDBRequest;
        try {request=this.factory.open(this.purpose==='preflight'?TRIAL_DB:DB,1);}catch{reject(new LocalAudioError('UNAVAILABLE'));return;}
        request.onupgradeneeded=()=>{
          const db=request.result;
          const segments=db.createObjectStore('segments',{keyPath:['namespace','id']});
          segments.createIndex('answer','namespace');segments.createIndex('order',['namespace','ordinal'],{unique:true});
          const chunks=db.createObjectStore('chunks',{keyPath:['namespace','segmentId','index']});
          chunks.createIndex('segment',['namespace','segmentId']);
        };
        request.onerror=()=>{settled=true;reject(new LocalAudioError('UNAVAILABLE'));};
        request.onblocked=()=>{settled=true;reject(new LocalAudioError('UNAVAILABLE'));};
        request.onsuccess=()=>{
          const db=request.result;
          if(settled||this.closed){db.close();reject(new LocalAudioError('CLOSED'));return;}
          db.onversionchange=()=>this.close();this.db=db;resolve(db);
        };
      });
    }
    try {const db=await this.connection;this.assertOpen();return db;}
    catch(error){this.connection=undefined;throw error;}
  }
  /** Resolve only after transaction complete, never on request success. Close lets owned writes drain. */
  private async run<T>(mode:IDBTransactionMode,body:(segments:IDBObjectStore,chunks:IDBObjectStore,read:Reader,result:(value:T)=>void,fail:(code:Code)=>void)=>void):Promise<T>{
    const db=await this.database();this.assertOpen();
    return new Promise<T>((resolve,reject)=>{
      let tx:IDBTransaction;
      try {tx=db.transaction(['segments','chunks'],mode,mode==='readwrite'?{durability:'strict'}:undefined);}
      catch {reject(new LocalAudioError('UNAVAILABLE'));return;}
      let value:T;let failure:LocalAudioError|undefined;
      const fail=(code:Code)=>{failure??=new LocalAudioError(code);try{tx.abort();}catch{/* native abort may already have finished */}};
      const read:Reader=(request,callback)=>{request.onsuccess=()=>{
        try {callback(request.result);}catch(error){fail(error instanceof LocalAudioError?error.code:'WRITE_FAILED');}
      };};
      tx.onabort=()=>reject(failure??new LocalAudioError('WRITE_FAILED'));
      tx.oncomplete=()=>this.closed?reject(new LocalAudioError('CLOSED')):resolve(value);
      try {body(tx.objectStore('segments'),tx.objectStore('chunks'),read,result=>{value=result;},fail);}
      catch(error){fail(error instanceof LocalAudioError?error.code:'WRITE_FAILED');}
    });
  }
  async begin(scope:AnswerScope,input:SegmentInput):Promise<Segment>{
    invalid(Boolean(input)&&typeof input==='object');
    // Capture only validated command fields before any asynchronous connection or callback.
    input={id:input.id,ordinal:input.ordinal,mimeType:input.mimeType,startedAt:input.startedAt};
    const namespace=this.namespace(scope);uuid(input.id);integer(input.ordinal,1,1000);timestamp(input.startedAt);invalid(MIMES.has(input.mimeType));
    return this.run('readwrite',(segments,_,read,result,fail)=>{
      read<StoredSegment|undefined>(segments.get([namespace,input.id]),existing=>{
        if(existing){
          if(existing.ordinal!==input.ordinal||existing.mimeType!==input.mimeType||existing.startedAt!==input.startedAt){fail('CONFLICT');return;}
          result(publicSegment(existing));return;
        }
        read<StoredSegment[]>(segments.index('answer').getAll(namespace),prior=>{
          if(prior.some(s=>s.state==='OPEN'||s.state==='SEALED'||s.answerEnd!==undefined||s.answerEndSha256!==undefined)||input.ordinal!==Math.max(0,...prior.map(s=>s.ordinal))+1){fail('CONFLICT');return;}
          const created:StoredSegment={...input,namespace,state:'OPEN',chunkCount:0,byteSize:0,endedAt:null,interruption:null};
          segments.add(created);result(publicSegment(created));
        });
      });
    });
  }
  async append(scope:AnswerScope,id:string,index:number,blob:Blob):Promise<{index:number;byteSize:number;sha256:string;chunkCount:number}>{
    const namespace=this.namespace(scope);uuid(id);integer(index,0,9999);invalid(blob instanceof Blob&&blob.size>0);invalid(blob.size<=MAX_CHUNK,'LIMIT');
    const sha256=await digest(blob);this.assertOpen();
    return this.run('readwrite',(segments,chunks,read,result,fail)=>{
      read<StoredSegment|undefined>(segments.get([namespace,id]),segment=>{
        if(!segment){fail('NOT_FOUND');return;}
        if(blob.type&&blob.type!==segment.mimeType){fail('CONFLICT');return;}
        read<StoredChunk|undefined>(chunks.get([namespace,id,index]),existing=>{
          if(existing){
            if(existing.sha256!==sha256||existing.byteSize!==blob.size){fail('CONFLICT');return;}
            result({index,byteSize:existing.byteSize,sha256:existing.sha256,chunkCount:index+1});return;
          }
          if(segment.state!=='OPEN'||segment.terminal||index!==segment.chunkCount){fail('CONFLICT');return;}
          if(segment.byteSize+blob.size>MAX_SEGMENT){fail('LIMIT');return;}
          chunks.add({namespace,segmentId:id,index,byteSize:blob.size,sha256,blob,mimeType:segment.mimeType} satisfies StoredChunk);
          const next={...segment,chunkCount:segment.chunkCount+1,byteSize:segment.byteSize+blob.size};segments.put(next);
          result({index,byteSize:blob.size,sha256,chunkCount:next.chunkCount});
        });
      });
    });
  }
  async seal(scope:AnswerScope,id:string,expectedChunks:number,endedAt:number):Promise<Segment>{
    const namespace=this.namespace(scope);uuid(id);integer(expectedChunks,0,10000);timestamp(endedAt);
    return this.run('readwrite',(segments,_,read,result,fail)=>{
      read<StoredSegment|undefined>(segments.get([namespace,id]),segment=>{
        if(!segment){fail('NOT_FOUND');return;}
        if(endedAt<segment.startedAt){fail('INVALID');return;}
        if(segment.terminal&&!sameTerminal(segment.terminal,{expectedChunks,endedAt,reason:null})){fail('CONFLICT');return;}
        if(segment.state==='SEALED'){
          if(segment.chunkCount!==expectedChunks||segment.endedAt!==endedAt){fail('CONFLICT');return;}
          result(publicSegment(segment));return;
        }
        if(segment.state!=='OPEN'||expectedChunks<1||expectedChunks!==segment.chunkCount){fail('CONFLICT');return;}
        const sealed:StoredSegment={...segment,state:'SEALED',endedAt};segments.put(sealed);result(publicSegment(sealed));
      });
    });
  }
  async interrupt(scope:AnswerScope,id:string,endedAt:number,reason:Interruption):Promise<Segment>{
    const namespace=this.namespace(scope);uuid(id);timestamp(endedAt);invalid(INTERRUPTIONS.has(reason));
    return this.run('readwrite',(segments,_,read,result,fail)=>{
      read<StoredSegment|undefined>(segments.get([namespace,id]),segment=>{
        if(!segment){fail('NOT_FOUND');return;}
        if(endedAt<segment.startedAt){fail('INVALID');return;}
        if(segment.terminal&&!sameTerminal(segment.terminal,{expectedChunks:segment.chunkCount,endedAt,reason})){fail('CONFLICT');return;}
        if(segment.state==='SEALED'){fail('CONFLICT');return;}
        if(segment.state==='INTERRUPTED'){
          if(segment.endedAt!==endedAt||segment.interruption!==reason){fail('CONFLICT');return;}
          result(publicSegment(segment));return;
        }
        const interrupted:StoredSegment={...segment,state:'INTERRUPTED',endedAt,interruption:reason};segments.put(interrupted);result(publicSegment(interrupted));
      });
    });
  }
  async list(scope:AnswerScope):Promise<Segment[]>{
    const namespace=this.namespace(scope);
    return this.run('readonly',(segments,_,read,result)=>read<StoredSegment[]>(segments.index('answer').getAll(namespace),rows=>result(rows.sort((a,b)=>a.ordinal-b.ordinal).map(publicSegment))));
  }
  /** Presence only, not validation or server receipt. Even a damaged ending marker closes new capture. */
  async hasAnswerEnd(scope:AnswerScope):Promise<boolean>{
    const namespace=this.namespace(scope);invalid(this.purpose==='answer');
    return this.run('readonly',(segments,_,read,result)=>read<StoredSegment[]>(segments.index('answer').getAll(namespace),rows=>
      result(rows.some(row=>row.answerEnd!==undefined||row.answerEndSha256!==undefined))));
  }
  /** Caller holds the assessment writer and fresh original server authorization. Read-only, no invented terminal.
   * Recheck every durable prefix in <=2 MiB windows, rather than buffering a whole answer before continuation. */
  async verifyContinuation(scope:AnswerScope,nextOrdinal:number,startedAt:number):Promise<void>{
    const namespace=this.namespace(scope);invalid(this.purpose==='answer');integer(nextOrdinal,2,1000);timestamp(startedAt);
    const fixed={assessmentId:scope.assessmentId,attemptId:scope.attemptId,answerId:scope.answerId};
    const readRows=()=>this.run<StoredSegment[]>('readonly',(segments,_,read,result)=>read<StoredSegment[]>(segments.index('answer').getAll(namespace),rows=>result(rows.sort((a,b)=>a.ordinal-b.ordinal))));
    const rows=await readRows();invalid(rows.length>0&&rows.length+1===nextOrdinal,'CONFLICT');
    let declaredBytes=0,declaredChunks=0,priorEnd=-1;
    for(let index=0;index<rows.length;index++){const row=rows[index];
      invalid(row.state==='INTERRUPTED'&&Boolean(row.terminal)&&row.answerEnd===undefined&&row.answerEndSha256===undefined,'CONFLICT');
      try{
        uuid(row.id);integer(row.ordinal,1,1000);timestamp(row.startedAt);timestamp(row.endedAt!);integer(row.chunkCount,0,10000);integer(row.byteSize,0,MAX_SEGMENT);
        invalid(row.ordinal===index+1&&MIMES.has(row.mimeType)&&INTERRUPTIONS.has(row.interruption!)&&row.endedAt!>=row.startedAt&&row.startedAt>=priorEnd);
        const command=terminal(row.terminal!);invalid(command.expectedChunks===row.chunkCount&&command.endedAt===row.endedAt&&command.reason===row.interruption);
      }catch{throw new LocalAudioError('INTEGRITY');}
      priorEnd=row.endedAt!;declaredBytes+=row.byteSize;declaredChunks+=row.chunkCount;
      invalid(declaredBytes<MAX_SEGMENT&&declaredChunks<10000,'LIMIT');
    }
    invalid(startedAt>=priorEnd,'CONFLICT');
    for(const row of rows){let bytes=0;
      for(let index=0;index<row.chunkCount;index++){
        const window=await this.readUploadWindow(fixed,row.id,[index]);
        invalid(JSON.stringify(window.segment)===JSON.stringify(publicSegment(row)),'CONFLICT');bytes+=window.chunks[0].byteSize;
      }
      invalid(bytes===row.byteSize,'INTEGRITY');
    }
    invalid(JSON.stringify(await readRows())===JSON.stringify(rows),'CONFLICT');this.assertOpen();
  }
  /** Persist after the native stop event and byte queue drain. This freezes bytes; it is not yet terminal-state confirmation. */
  async prepareTerminal(scope:AnswerScope,id:string,input:TerminalCommand):Promise<TerminalCommand>{
    const namespace=this.namespace(scope);uuid(id);const command=terminal(input);
    const fixedScope={assessmentId:scope.assessmentId,attemptId:scope.attemptId,answerId:scope.answerId};
    await this.read(fixedScope,id); // Verify durable bytes before creating recoverable ending evidence.
    return this.run('readwrite',(segments,_,read,result,fail)=>{
      read<StoredSegment|undefined>(segments.get([namespace,id]),segment=>{
        if(!segment){fail('NOT_FOUND');return;}
        if(segment.terminal){
          if(!sameTerminal(segment.terminal,command)){fail('CONFLICT');return;}
          result({...command});return;
        }
        if(command.endedAt<segment.startedAt){fail('INVALID');return;}
        if(segment.state!=='OPEN'||segment.chunkCount!==command.expectedChunks){fail('CONFLICT');return;}
        segments.put({...segment,terminal:command});result({...command});
      });
    });
  }
  private verifyTerminalEvidence(segment:Segment,command:TerminalCommand|undefined):void{
    try{
      integer(segment.ordinal,1,1000);timestamp(segment.startedAt);integer(segment.chunkCount,0,10000);integer(segment.byteSize,0,MAX_SEGMENT);
      invalid(['OPEN','SEALED','INTERRUPTED'].includes(segment.state));invalid(MIMES.has(segment.mimeType));
      if(segment.state!=='OPEN'){
        invalid(segment.endedAt!==null);timestamp(segment.endedAt!);invalid(segment.endedAt!>=segment.startedAt);
        invalid(segment.state==='SEALED'?segment.interruption===null&&segment.chunkCount>0:INTERRUPTIONS.has(segment.interruption!));
      }
      if(command){terminal(command);invalid(command.expectedChunks===segment.chunkCount&&command.endedAt>=segment.startedAt);
        if(segment.state!=='OPEN')invalid(command.endedAt===segment.endedAt&&command.reason===segment.interruption);
      }
    }catch{throw new LocalAudioError('INTEGRITY');}
  }
  /** Closed metadata only, not byte verification. Formal ending checks bytes afterwards and rechecks this evidence before committing. */
  private async verifyClosedTerminal(namespace:string,id:string):Promise<void>{
    return this.run<void>('readonly',(segments,_,read,result,fail)=>{
      read<StoredSegment|undefined>(segments.get([namespace,id]),segment=>{
        if(!segment){fail('NOT_FOUND');return;}
        this.verifyTerminalEvidence(publicSegment(segment),segment.terminal);
        if(segment.state==='OPEN'){fail('MISSING_TERMINAL');return;}
        result(undefined);
      });
    });
  }
  /** Original command only, never infer completion from an OPEN prefix. Caller must hold the assessment writer and verify identity. */
  async recoverTerminal(scope:AnswerScope,id:string):Promise<Segment>{
    const namespace=this.namespace(scope);uuid(id);
    const fixedScope={assessmentId:scope.assessmentId,attemptId:scope.attemptId,answerId:scope.answerId};
    const stored=await this.read(fixedScope,id);
    const command=await this.run<TerminalCommand|undefined>('readonly',(segments,_,read,result,fail)=>{
      read<StoredSegment|undefined>(segments.get([namespace,id]),segment=>{if(!segment){fail('NOT_FOUND');return;}result(segment.terminal);});
    });
    const segment=stored.segment;
    this.verifyTerminalEvidence(segment,command);
    if(segment.state!=='OPEN')return segment; // Backward-compatible terminal evidence, no invented command or new capture.
    invalid(Boolean(command),'MISSING_TERMINAL');
    return command!.reason?this.interrupt(fixedScope,id,command!.endedAt,command!.reason):this.seal(fixedScope,id,command!.expectedChunks,command!.endedAt);
  }
  async read(scope:AnswerScope,id:string):Promise<{segment:Segment;chunks:Chunk[]}>{
    const namespace=this.namespace(scope);uuid(id);
    const result=await this.run<{segment:StoredSegment;chunks:StoredChunk[]}>('readonly',(segments,chunks,read,result,fail)=>{
      read<StoredSegment|undefined>(segments.get([namespace,id]),segment=>{
        if(!segment){fail('NOT_FOUND');return;}
        read<StoredChunk[]>(chunks.index('segment').getAll([namespace,id]),rows=>result({segment,chunks:rows.sort((a,b)=>a.index-b.index)}));
      });
    });
    invalid(result.chunks.length===result.segment.chunkCount,'INTEGRITY');let bytes=0;
    for(let index=0;index<result.chunks.length;index++){
      const chunk=result.chunks[index];invalid(chunk.index===index&&chunk.blob instanceof Blob&&chunk.byteSize===chunk.blob.size&&chunk.mimeType===result.segment.mimeType,'INTEGRITY');
      invalid(chunk.byteSize>0&&chunk.byteSize<=MAX_CHUNK,'INTEGRITY');
      invalid(await digest(chunk.blob)===chunk.sha256,'INTEGRITY');this.assertOpen();bytes+=chunk.byteSize;
    }
    invalid(bytes===result.segment.byteSize&&bytes<=MAX_SEGMENT,'INTEGRITY');this.assertOpen();
    return {segment:publicSegment(result.segment),chunks:result.chunks.map(({index,byteSize,sha256,blob})=>({index,byteSize,sha256,blob}))};
  }
  /** Independent bounded upload reader. No capture lock, whole-segment getAll, mutation or saved-state claim.
   * Reads only already committed original indices; appending a later tail cannot replace these bytes. */
  async readUploadWindow(scope:AnswerScope,id:string,selection:readonly number[]):Promise<{segment:Segment;chunks:Chunk[]}>{
    const namespace=this.namespace(scope);uuid(id);
    invalid(Array.isArray(selection)&&selection.length>0&&selection.length<=32);
    const indices=[...selection];let prior=-1;
    for(const index of indices){integer(index,0,9999);invalid(index>prior);prior=index;}
    const result=await this.run<{segment:StoredSegment;chunks:StoredChunk[]}>('readonly',(segments,chunks,read,result,fail)=>{
      read<StoredSegment|undefined>(segments.get([namespace,id]),segment=>{
        if(!segment){fail('NOT_FOUND');return;}
        integer(segment.chunkCount,0,10000);integer(segment.byteSize,0,MAX_SEGMENT);invalid(MIMES.has(segment.mimeType),'INTEGRITY');
        if(indices.some(index=>index>=segment.chunkCount)){fail('INVALID');return;}
        const selected:StoredChunk[]=[];let bytes=0;
        const next=(position:number)=>read<StoredChunk|undefined>(chunks.get([namespace,id,indices[position]]),chunk=>{
          if(!chunk||chunk.namespace!==namespace||chunk.segmentId!==id||chunk.index!==indices[position]||chunk.mimeType!==segment.mimeType
            ||!(chunk.blob instanceof Blob)||chunk.byteSize!==chunk.blob.size||chunk.byteSize<1||chunk.byteSize>MAX_CHUNK){fail('INTEGRITY');return;}
          bytes+=chunk.byteSize;if(bytes>8*1024*1024){fail('LIMIT');return;}
          selected.push(chunk);if(position+1<indices.length)next(position+1);else result({segment,chunks:selected});
        });next(0);
      });
    });
    for(const chunk of result.chunks){invalid(await digest(chunk.blob)===chunk.sha256,'INTEGRITY');this.assertOpen();}
    return {segment:publicSegment(result.segment),chunks:result.chunks.map(({index,byteSize,sha256,blob})=>({index,byteSize,sha256,blob}))};
  }
  /** Assemble one completed recorder only; the caller must still decode/verify actual media before claiming playback. */
  async playable(scope:AnswerScope,id:string):Promise<Blob>{
    const result=await this.read(scope,id);invalid(result.segment.state==='SEALED'&&result.chunks.length>0,'CONFLICT');
    return new Blob(result.chunks.map(c=>c.blob),{type:result.segment.mimeType});
  }
  private async endingManifest(scope:AnswerScope):Promise<AnswerEndManifest>{
    const namespace=this.namespace(scope);
    const rows=await this.list(scope);invalid(rows.length>0,'NOT_FOUND');invalid(rows.length<=1000,'LIMIT');
    let declaredBytes=0,declaredChunks=0;
    for(const row of rows){
      invalid(Number.isSafeInteger(row.byteSize)&&row.byteSize>=0&&row.byteSize<=MAX_SEGMENT&&Number.isSafeInteger(row.chunkCount)&&row.chunkCount>=0&&row.chunkCount<=10000,'INTEGRITY');
      declaredBytes+=row.byteSize;declaredChunks+=row.chunkCount;invalid(declaredBytes<=MAX_SEGMENT&&declaredChunks<=10000,'LIMIT');
    }
    const segments=[];
    for(const row of rows){
      // Do not infer ending from a prefix, even when a segment-level terminal request is still pending.
      invalid(row.state!=='OPEN','MISSING_TERMINAL');await this.verifyClosedTerminal(namespace,row.id);
      const stored=await this.read(scope,row.id);
      segments.push({...stored.segment,chunks:stored.chunks.map(({index,byteSize,sha256})=>({index,byteSize,sha256}))});
    }
    try{return JSON.parse(encodeAnswerEndManifest({schemaVersion:1,segments})) as AnswerEndManifest;}
    catch{throw new LocalAudioError('INTEGRITY');}
  }
  private verifyEnding(rows:StoredSegment[],manifest:AnswerEndManifest):StoredEnding|undefined {
    // Media was verified after the initial terminal check. Recheck metadata in
    // the current transaction; a late damaged terminal must not become an ending.
    for(const row of rows)this.verifyTerminalEvidence(publicSegment(row),row.terminal);
    invalid(rows.slice(1).every(s=>s.answerEnd===undefined&&s.answerEndSha256===undefined),'INTEGRITY');
    const stored=rows[0]?.answerEnd,sha256=rows[0]?.answerEndSha256;if(stored===undefined){invalid(sha256===undefined,'INTEGRITY');return;}
    try{const original=copyAnswerEndCommand(stored);invalid(typeof sha256==='string'&&/^[a-f0-9]{64}$/.test(sha256)&&encodeAnswerEndManifest(original.manifest)===encodeAnswerEndManifest(manifest),'INTEGRITY');return {command:original,sha256:sha256!};}
    catch{throw new LocalAudioError('INTEGRITY');}
  }
  private async readEnding(namespace:string,manifest:AnswerEndManifest):Promise<StoredEnding|undefined>{
    const stored=await this.run<StoredEnding|undefined>('readonly',(segments,_,read,result,fail)=>{
      read<StoredSegment[]>(segments.index('answer').getAll(namespace),rows=>{
        rows.sort((a,b)=>a.ordinal-b.ordinal);if(!sameEndingRows(rows,manifest)){fail('CONFLICT');return;}result(this.verifyEnding(rows,manifest));
      });
    });
    if(stored){invalid(await digest(endingBytes(stored.command))===stored.sha256,'INTEGRITY');this.assertOpen();}return stored;
  }
  /** Caller holds assessment writer and freshly verifies server identity/scope. Persist BEFORE any HTTP end request.
   * Optional immutable metadata on the first original segment: no database upgrade, erase or media rewrite. */
  async prepareAnswerEnd(scope:AnswerScope,input:{idempotencyKey:string;captureClientId:string}):Promise<AnswerEndCommand>{
    const namespace=this.namespace(scope);invalid(this.purpose==='answer');invalid(Boolean(input)&&typeof input==='object');
    const ids={idempotencyKey:input.idempotencyKey,captureClientId:input.captureClientId};uuid(ids.idempotencyKey);uuid(ids.captureClientId);
    const fixedScope={assessmentId:scope.assessmentId,attemptId:scope.attemptId,answerId:scope.answerId};
    const manifest=await this.endingManifest(fixedScope);
    await this.readEnding(namespace,manifest); // Check original identifiers too; syntactically valid corruption is not a new command.
    const proposed=copyAnswerEndCommand({...ids,manifest}),sha256=await digest(endingBytes(proposed));this.assertOpen();
    return this.run<AnswerEndCommand>('readwrite',(segments,_,read,result,fail)=>{
      read<StoredSegment[]>(segments.index('answer').getAll(namespace),rows=>{
        rows.sort((a,b)=>a.ordinal-b.ordinal);
        if(!sameEndingRows(rows,manifest)){fail('CONFLICT');return;}
        const original=this.verifyEnding(rows,manifest);
        if(original){
          if(original.command.idempotencyKey!==ids.idempotencyKey||original.command.captureClientId!==ids.captureClientId){fail('CONFLICT');return;}
          if(original.sha256!==sha256){fail('INTEGRITY');return;}result(original.command);return;
        }
        segments.put({...rows[0],answerEnd:proposed,answerEndSha256:sha256});result(copyAnswerEndCommand(proposed));
      });
    });
  }
  /** Restore exact original ids and byte-verified manifest. No device, new recording, new key or server success. */
  async recoverAnswerEnd(scope:AnswerScope):Promise<AnswerEndCommand>{
    const namespace=this.namespace(scope);invalid(this.purpose==='answer');const fixedScope={assessmentId:scope.assessmentId,attemptId:scope.attemptId,answerId:scope.answerId};
    const manifest=await this.endingManifest(fixedScope);
    const checked=await this.readEnding(namespace,manifest);invalid(Boolean(checked),'MISSING_ANSWER_END');
    return this.run<AnswerEndCommand>('readonly',(segments,_,read,result,fail)=>{
      read<StoredSegment[]>(segments.index('answer').getAll(namespace),rows=>{
        rows.sort((a,b)=>a.ordinal-b.ordinal);
        if(!sameEndingRows(rows,manifest)){fail('CONFLICT');return;}
        const original=this.verifyEnding(rows,manifest);if(!original||original.sha256!==checked!.sha256||JSON.stringify(original.command)!==JSON.stringify(checked!.command)){fail('INTEGRITY');return;}result(original.command);
      });
    });
  }
  /** Explicitly disposable device-test scratch only. Default formal-answer handles cannot delete anything. */
  async discardPreflight(scope:AnswerScope):Promise<void>{
    invalid(this.purpose==='preflight');const namespace=this.namespace(scope);
    return this.run<void>('readwrite',(segments,chunks,read,result)=>{
      read<StoredSegment[]>(segments.index('answer').getAll(namespace),rows=>{
        for(const segment of rows){
          segments.delete([namespace,segment.id]);
          read<IDBValidKey[]>(chunks.index('segment').getAllKeys([namespace,segment.id]),keys=>{for(const key of keys)chunks.delete(key);});
        }
        result(undefined);
      });
    });
  }
  close(){this.closed=true;this.db?.close();this.db=undefined;}
}
