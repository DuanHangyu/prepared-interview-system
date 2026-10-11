/** Only a freshly authorized server response can establish final submission. Local media,
 * declaration, cached byte counts and model completion are deliberately not inputs. */
export type SubmissionScope={attemptId:string;paperVersionId:string;questionCount:number};
export type SubmissionReceipt={id:string;attemptId:string;paperVersionId:string;revision:number;answerCount:number;submittedAt:string};
export type SubmissionSummary={attemptId:string;paperVersionId:string;revision:number;phase:'WAITING_ANSWERS'|'READY'|'REQUIRES_REVIEW'|'SUBMITTED';submitted:boolean;
  pending:Array<{ordinal:number;answerId:string|null;phase:string}>;receipt:SubmissionReceipt|null};
const uuid=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
const check=(valid:boolean)=>{if(!valid)throw new Error('原提交回执尚未核验，请保留本机内容并查询原回执');};
const revision=(v:unknown)=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0;
export function verifySubmission(input:unknown,scope:SubmissionScope):SubmissionSummary {
  check(!!input&&typeof input==='object'&&uuid(scope.attemptId)&&uuid(scope.paperVersionId)&&Number.isSafeInteger(scope.questionCount)&&scope.questionCount>=1&&scope.questionCount<=50);
  const v=input as SubmissionSummary;
  check(v.attemptId===scope.attemptId&&v.paperVersionId===scope.paperVersionId&&revision(v.revision)&&['WAITING_ANSWERS','READY','REQUIRES_REVIEW','SUBMITTED'].includes(v.phase)&&typeof v.submitted==='boolean'&&Array.isArray(v.pending)&&v.pending.length<=scope.questionCount);
  const seen=new Set<number>(),answers=new Set<string>(),pending=v.pending.map(p=>{
    check(!!p&&Number.isSafeInteger(p.ordinal)&&p.ordinal>=1&&p.ordinal<=scope.questionCount&&!seen.has(p.ordinal)&&
      (p.answerId===null? p.phase==='NOT_STARTED':uuid(p.answerId)&&p.phase!=='NOT_STARTED'&&!answers.has(p.answerId))&&['NOT_STARTED','RECORDING','UPLOAD_PENDING','REQUIRES_REVIEW','NOT_QUEUED','QUEUED','RUNNING','RETRY_WAIT','WAITING_APPROVAL','NEEDS_REVIEW'].includes(p.phase));
    seen.add(p.ordinal);if(p.answerId!==null)answers.add(p.answerId);return {ordinal:p.ordinal,answerId:p.answerId,phase:p.phase};
  });
  let receipt:SubmissionReceipt|null=null;
  if(v.phase==='SUBMITTED'){
    const r=v.receipt;check(v.submitted===true&&pending.length===0&&!!r);check(uuid(r!.id)&&r!.attemptId===scope.attemptId&&r!.paperVersionId===scope.paperVersionId&&revision(r!.revision)&&r!.revision>0&&r!.revision===v.revision&&r!.answerCount===scope.questionCount&&typeof r!.submittedAt==='string'&&/^\d{4}-\d\d-\d\dT/.test(r!.submittedAt)&&Number.isFinite(Date.parse(r!.submittedAt)));
    receipt={id:r!.id,attemptId:r!.attemptId,paperVersionId:r!.paperVersionId,revision:r!.revision,answerCount:r!.answerCount,submittedAt:r!.submittedAt};
  }else check(v.submitted===false&&v.receipt===null&&(v.phase!=='READY'||pending.length===0)&&(v.phase!=='WAITING_ANSWERS'||pending.length>0));
  return {attemptId:v.attemptId,paperVersionId:v.paperVersionId,revision:v.revision,phase:v.phase,submitted:v.submitted,pending,receipt};
}
