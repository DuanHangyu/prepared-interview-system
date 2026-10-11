import type { ConfirmReviewCommand,RegradeReviewCommand,AdjustReviewCommand } from '@/api/preparedReview';
export type ReviewScope={actorId:number;assessmentId:number;attemptId:string;answerId:string};
export type ReviewIntent={kind:'confirm';command:ConfirmReviewCommand}|{kind:'regrade';command:RegradeReviewCommand}|{kind:'adjust';command:AdjustReviewCommand};
const fail:()=>never=()=>{throw new Error('原评阅请求保存或核验失败，请保留当前页面，不要重复创建请求');};
const check=(valid:boolean)=>{if(!valid)fail();};
const uuid=(value:unknown)=>check(typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value));
const positive=(value:number)=>check(Number.isSafeInteger(value)&&value>0&&value<=2147483647);
function copy(input:unknown):ReviewIntent{
  check(!!input&&typeof input==='object');const value=input as ReviewIntent;
  check(Object.keys(value).sort().join(',')==='command,kind'&&(value.kind==='confirm'||value.kind==='regrade'||value.kind==='adjust'));
  check(!!value.command&&typeof value.command==='object');const c=value.command;
  check(Object.keys(c).sort().join(',')===(value.kind==='confirm'?'expectedRevision,idempotencyKey,reason,scoreId':value.kind==='adjust'?'expectedRevision,idempotencyKey,reason,score':'expectedRevision,idempotencyKey,reason,transcriptId'));
  uuid(c.idempotencyKey);check(Number.isSafeInteger(c.expectedRevision)&&c.expectedRevision>=0);
  check(typeof c.reason==='string'&&!!c.reason.trim()&&c.reason.length<=1000);
  if(value.kind==='confirm'){uuid(value.command.scoreId);return {kind:'confirm',command:{...value.command}};}
  if(value.kind==='adjust'){
    const score=value.command.score;check(typeof score==='number'&&Number.isFinite(score)&&score>=0&&score<=1000000&&/^\d+(?:\.\d{1,2})?$/.test(String(score))&&c.expectedRevision<Number.MAX_SAFE_INTEGER);
    return {kind:'adjust',command:{...value.command}};
  }
  uuid(value.command.transcriptId);return {kind:'regrade',command:{...value.command}};
}
/** Session-local intent only. Never stores student transcripts/audio, tokens or provider inputs.
 * Restoring requires a freshly authorized server identity; it never auto-sends or changes keys. */
export class PreparedReviewJournal{
  private readonly key:string;
  constructor(scope:ReviewScope,private readonly storage:Storage=globalThis.sessionStorage){
    check(!!scope&&typeof scope==='object');positive(scope.actorId);positive(scope.assessmentId);uuid(scope.attemptId);uuid(scope.answerId);
    this.key=JSON.stringify(['prepared-review-v1',scope.actorId,scope.assessmentId,scope.attemptId,scope.answerId]);
  }
  read():ReviewIntent|null{
    try{const raw=this.storage.getItem(this.key);if(raw===null)return null;check(raw.length<=16384);return copy(JSON.parse(raw));}catch{fail();}
  }
  prepare(kind:'confirm'|'regrade',revision:number,resultId:string,reason:string):ReviewIntent{
    const existing=this.read();if(existing)return existing;
    const command={idempotencyKey:crypto.randomUUID(),expectedRevision:revision,reason};
    const intent=copy(kind==='confirm'?{kind,command:{...command,scoreId:resultId}}:{kind,command:{...command,transcriptId:resultId}});
    return this.persist(intent);
  }
  prepareAdjust(revision:number,score:number,reason:string):ReviewIntent{
    const existing=this.read();if(existing)return existing;
    return this.persist(copy({kind:'adjust',command:{idempotencyKey:crypto.randomUUID(),expectedRevision:revision,score,reason}}));
  }
  private persist(intent:ReviewIntent):ReviewIntent{
    try{this.storage.setItem(this.key,JSON.stringify(intent));check(JSON.stringify(this.read())===JSON.stringify(intent));return copy(intent);}catch{fail();}
  }
  clear(original:ReviewIntent){
    check(JSON.stringify(this.read())===JSON.stringify(copy(original)));
    try{this.storage.removeItem(this.key);check(this.read()===null);}catch{fail();}
  }
}
