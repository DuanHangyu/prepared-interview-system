/** Immutable original request metadata, separate from audio storage. Caller must freshly verify the server identity.
 * No auth token, media, deletion, automatic takeover or replacement-key recovery belongs in this database. */
export type JourneyStart={idempotencyKey:string;captureClientId:string};
export type JourneyBinding={attemptId:string;paperVersionId:string};
export type JourneyQuestion=JourneyStart & JourneyBinding & {ordinal:number;endKey:string};
export type JourneySubmission=JourneyBinding & {idempotencyKey:string;expectedRevision:number};
type Kind='start'|'binding'|'question'|'submission';
type Value=JourneyStart|JourneyBinding|JourneyQuestion|JourneySubmission;
type Code='INVALID'|'UNAVAILABLE'|'CLOSED'|'WRITE_FAILED'|'MISSING_ORIGINAL'|'INTEGRITY'|'CONFLICT';
export class JourneyError extends Error {
  constructor(public readonly code:Code){super('原作答请求保存或核验失败，请保留本机内容，不要重新作答');this.name='JourneyError';}
}
type Row={key:string;value:unknown;sha256:string};
const check=(valid:boolean,code:Code='INVALID')=>{if(!valid)throw new JourneyError(code);};
const uuid=(v:unknown)=>check(typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v));
const number=(v:number,max:number)=>check(Number.isSafeInteger(v)&&v>0&&v<=max);
async function hash(key:string,value:unknown){
  try{return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify([key,value])))),b=>b.toString(16).padStart(2,'0')).join('');}
  catch{throw new JourneyError('WRITE_FAILED');}
}
function copy(kind:Kind,input:unknown):Value {
  check(!!input&&typeof input==='object','INTEGRITY');const v=input as JourneyQuestion;
  if(kind==='binding'){uuid(v.attemptId);uuid(v.paperVersionId);return {attemptId:v.attemptId,paperVersionId:v.paperVersionId};}
  if(kind==='submission'){const s=input as JourneySubmission;uuid(s.attemptId);uuid(s.paperVersionId);uuid(s.idempotencyKey);check(Number.isSafeInteger(s.expectedRevision)&&s.expectedRevision>=0);
    return {attemptId:s.attemptId,paperVersionId:s.paperVersionId,idempotencyKey:s.idempotencyKey,expectedRevision:s.expectedRevision};}
  uuid(v.idempotencyKey);uuid(v.captureClientId);
  if(kind==='start')return {idempotencyKey:v.idempotencyKey,captureClientId:v.captureClientId};
  uuid(v.attemptId);uuid(v.paperVersionId);uuid(v.endKey);number(v.ordinal,50);
  return {idempotencyKey:v.idempotencyKey,captureClientId:v.captureClientId,attemptId:v.attemptId,paperVersionId:v.paperVersionId,ordinal:v.ordinal,endKey:v.endKey};
}
export class PreparedJourneyJournal {
  private readonly namespace:string;
  private connection:Promise<IDBDatabase>|undefined;
  private db:IDBDatabase|undefined;
  private closed=false;
  constructor(identity:{origin:string;studentId:number;deploymentId:string},assessmentId:number,private readonly factory:IDBFactory=globalThis.indexedDB){
    check(!!identity&&typeof identity==='object');number(identity.studentId,2147483647);number(assessmentId,2147483647);
    check(typeof identity.deploymentId==='string'&&/^prepared-[a-z0-9][a-z0-9-]{0,70}$/.test(identity.deploymentId));
    try{const u=new URL(identity.origin);check(u.origin===identity.origin&&!u.username&&!u.password&&(u.protocol==='https:'||(u.protocol==='http:'&&['localhost','127.0.0.1','[::1]'].includes(u.hostname))));}
    catch{throw new JourneyError('INVALID');}
    check(!!factory&&typeof factory.open==='function','UNAVAILABLE');
    this.namespace=JSON.stringify([1,identity.deploymentId,identity.origin,identity.studentId,assessmentId]);
  }
  private openCheck(){check(!this.closed,'CLOSED');}
  private async database(){
    this.openCheck();
    this.connection??=new Promise<IDBDatabase>((resolve,reject)=>{
      let r:IDBOpenDBRequest,settled=false;
      try{r=this.factory.open('prepared-interview-journey-v1',1);}catch{reject(new JourneyError('UNAVAILABLE'));return;}
      r.onupgradeneeded=()=>r.result.createObjectStore('commands',{keyPath:'key'});
      r.onerror=r.onblocked=()=>{settled=true;reject(new JourneyError('UNAVAILABLE'));};
      r.onsuccess=()=>{const db=r.result;if(settled||this.closed){db.close();reject(new JourneyError('CLOSED'));return;}this.db=db;db.onversionchange=()=>this.close();resolve(db);};
    });
    try{const db=await this.connection;this.openCheck();return db;}catch(error){this.connection=undefined;throw error;}
  }
  private key(kind:string){this.openCheck();return JSON.stringify([this.namespace,kind]);}
  private async row(key:string,candidate?:Row):Promise<Row|null>{
    const db=await this.database();this.openCheck();
    return new Promise((resolve,reject)=>{
      let tx:IDBTransaction;
      try{tx=db.transaction('commands',candidate?'readwrite':'readonly',candidate?{durability:'strict'}:undefined);}catch{reject(new JourneyError('UNAVAILABLE'));return;}
      let result:Row|null=null;const store=tx.objectStore('commands');
      tx.onabort=()=>reject(new JourneyError('WRITE_FAILED'));
      tx.oncomplete=()=>this.closed?reject(new JourneyError('CLOSED')):resolve(result);
      try{const r=store.get(key);r.onsuccess=()=>{result=r.result??null;if(!result&&candidate){store.add(candidate);result=candidate;}};}
      catch{try{tx.abort();}catch{/* finished native transaction */}}
    });
  }
  private async verified(kind:Kind,key:string,row:Row|null){
    if(!row)return null;
    try{const value=copy(kind,row.value);check(row.key===key&&JSON.stringify(value)===JSON.stringify(row.value)&&/^[a-f0-9]{64}$/.test(row.sha256),'INTEGRITY');check(await hash(key,value)===row.sha256,'INTEGRITY');this.openCheck();return value;}
    catch(error){if(error instanceof JourneyError&&['CLOSED','WRITE_FAILED'].includes(error.code))throw error;throw new JourneyError('INTEGRITY');}
  }
  private async read(kind:Kind,key:string){return this.verified(kind,key,await this.row(key));}
  private async once(kind:Kind,key:string,input:unknown){
    const value=copy(kind,input),candidate={key,value,sha256:await hash(key,value)};
    return (await this.verified(kind,key,await this.row(key,candidate)))!;
  }
  async readStart():Promise<JourneyStart|null>{return this.read('start',this.key('start')) as Promise<JourneyStart|null>;}
  async prepareStart():Promise<JourneyStart>{
    const key=this.key('start'),existing=await this.read('start',key);if(existing)return existing as JourneyStart;
    return this.once('start',key,{idempotencyKey:crypto.randomUUID(),captureClientId:crypto.randomUUID()}) as Promise<JourneyStart>;
  }
  async readBinding():Promise<JourneyBinding|null>{return this.read('binding',this.key('binding')) as Promise<JourneyBinding|null>;}
  async bindAttempt(input:JourneyBinding):Promise<JourneyBinding>{
    const fixed=copy('binding',input) as JourneyBinding,key=this.key('binding');
    check(!!await this.readStart(),'MISSING_ORIGINAL');
    const saved=await this.once('binding',key,fixed) as JourneyBinding;
    check(saved.attemptId===fixed.attemptId&&saved.paperVersionId===fixed.paperVersionId,'CONFLICT');return saved;
  }
  async readQuestion(ordinal:number):Promise<JourneyQuestion|null>{number(ordinal,50);return this.read('question',this.key('question:'+ordinal)) as Promise<JourneyQuestion|null>;}
  async prepareQuestion(ordinal:number):Promise<JourneyQuestion>{
    number(ordinal,50);const start=await this.readStart(),binding=await this.readBinding();check(!!start&&!!binding,'MISSING_ORIGINAL');
    const key=this.key('question:'+ordinal),existing=await this.read('question',key);
    const saved=(existing??await this.once('question',key,{idempotencyKey:crypto.randomUUID(),captureClientId:start!.captureClientId,...binding!,ordinal,endKey:crypto.randomUUID()})) as JourneyQuestion;
    check(saved.ordinal===ordinal&&saved.captureClientId===start!.captureClientId&&saved.attemptId===binding!.attemptId&&saved.paperVersionId===binding!.paperVersionId,'CONFLICT');return saved;
  }
  async readSubmission(binding:JourneyBinding):Promise<JourneySubmission|null>{
    const fixed=copy('binding',binding) as JourneyBinding;
    const saved=await this.read('submission',this.key('submission:'+fixed.attemptId)) as JourneySubmission|null;
    if(saved)check(saved.attemptId===fixed.attemptId&&saved.paperVersionId===fixed.paperVersionId,'CONFLICT');return saved;
  }
  async prepareSubmission(binding:JourneyBinding,expectedRevision:number):Promise<JourneySubmission>{
    const fixed=copy('binding',binding) as JourneyBinding;check(Number.isSafeInteger(expectedRevision)&&expectedRevision>=0);
    const original=await this.readBinding();check(!!original&&!!await this.readStart(),'MISSING_ORIGINAL');
    check(original!.attemptId===fixed.attemptId&&original!.paperVersionId===fixed.paperVersionId,'CONFLICT');
    const existing=await this.readSubmission(fixed);if(existing)return existing;
    const saved=await this.once('submission',this.key('submission:'+fixed.attemptId),{...fixed,idempotencyKey:crypto.randomUUID(),expectedRevision}) as JourneySubmission;
    check(saved.attemptId===fixed.attemptId&&saved.paperVersionId===fixed.paperVersionId,'CONFLICT');return saved;
  }
  close(){this.closed=true;this.db?.close();this.db=undefined;this.connection=undefined;}
}
