import type { SupplementScope,SupplementCommand,SupplementReceipt,SupplementView } from '@/api/preparedSupplement';
const check=(valid:boolean):void=>{if(!valid)throw new Error('原补传审批范围、版本或回执无法核验，请保留原请求');};
const uuid=(v:unknown)=>check(typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v));
const positive=(v:unknown)=>check(typeof v==='number'&&Number.isSafeInteger(v)&&v>0&&v<=2147483647);
const revision=(v:unknown,increment=false)=>check(typeof v==='number'&&Number.isSafeInteger(v)&&v>=0&&(!increment||v<Number.MAX_SAFE_INTEGER));
const sha=(v:unknown)=>check(typeof v==='string'&&/^[a-f0-9]{64}$/.test(v));
function text(v:unknown,limit=1000){check(typeof v==='string'&&!!v.trim()&&v.length<=limit);const s=v as string;
  for(let i=0;i<s.length;i++){const c=s.charCodeAt(i);if(c>=0xd800&&c<=0xdbff){const next=s.charCodeAt(++i);check(next>=0xdc00&&next<=0xdfff);}else check(!(c>=0xdc00&&c<=0xdfff)&&(!(c<32||c>=127&&c<=159)||[9,10,13].includes(c)));}
}
function time(v:unknown){check(typeof v==='string'&&/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(v));const s=v as string,n=Date.parse(s);check(Number.isFinite(n));
  const normalized=s.replace(/(?:\.(\d{1,3}))?Z$/,(_all,fraction:string|undefined)=>'.'+(fraction??'').padEnd(3,'0')+'Z');check(new Date(n).toISOString()===normalized);return n;
}
export function checkSupplementScope(s:SupplementScope):SupplementScope{check(!!s&&typeof s==='object');positive(s.actorId);positive(s.studentId);positive(s.assessmentId);uuid(s.attemptId);uuid(s.answerId);return {actorId:s.actorId,studentId:s.studentId,assessmentId:s.assessmentId,attemptId:s.attemptId,answerId:s.answerId};}
export function copySupplementCommand(input:unknown):SupplementCommand{
  check(!!input&&typeof input==='object'&&!Array.isArray(input));const c=input as SupplementCommand;
  check(Object.keys(c).sort().join(',')==='expectedAttemptRevision,expectedRevision,idempotencyKey,manifestSha256,reason,uploadDeadline');uuid(c.idempotencyKey);revision(c.expectedRevision,true);revision(c.expectedAttemptRevision,true);sha(c.manifestSha256);text(c.reason);time(c.uploadDeadline);
  return {idempotencyKey:c.idempotencyKey,expectedRevision:c.expectedRevision,expectedAttemptRevision:c.expectedAttemptRevision,manifestSha256:c.manifestSha256,uploadDeadline:c.uploadDeadline,reason:c.reason};
}
function sameResource(a:SupplementScope,b:SupplementScope,actor=true){check(a.assessmentId===b.assessmentId&&a.studentId===b.studentId&&a.attemptId===b.attemptId&&a.answerId===b.answerId&&(!actor||a.actorId===b.actorId));}
function receiptProof(r:SupplementReceipt,s:SupplementScope){check(!!r&&typeof r==='object');checkSupplementScope(r);sameResource(r,s,false);uuid(r.id);uuid(r.paperVersionId);uuid(r.idempotencyKey);revision(r.approvalRevision);revision(r.attemptRevision);check(r.approvalRevision>0&&r.attemptRevision>0);sha(r.manifestSha256);text(r.reason);
  // A student may end before their original question deadline. The deadline remains
  // immutable, but cannot be ordered against the recognized overall answering end.
  time(r.answerDeadline);const ended=time(r.answeringEndedAt),automatic=time(r.automaticDeadline),approved=time(r.approvedAt),deadline=time(r.uploadDeadline);check(automatic===ended+86400000&&approved>=automatic&&deadline>approved);
}
export function checkSupplementView(input:unknown,s:SupplementScope):SupplementView{
  const v=input as SupplementView;
  checkSupplementScope(s);check(!!v&&typeof v==='object');checkSupplementScope(v);sameResource(v,s);revision(v.approvalRevision);revision(v.attemptRevision);text(v.answerState,40);time(v.answerDeadline);time(v.serverNow);
  check(['AUTOMATIC_WINDOW','AWAITING_APPROVAL','APPROVED','EXPIRED','COMPLETE','NOT_ELIGIBLE'].includes(v.phase)&&typeof v.mayApprove==='boolean');
  if(v.manifestSha256!==null)sha(v.manifestSha256);if(v.automaticDeadline!==null)time(v.automaticDeadline);if(v.eligibilityIssue!==null)text(v.eligibilityIssue);
  if(v.latest!==null){receiptProof(v.latest,s);check(v.latest.approvalRevision===v.approvalRevision&&v.latest.attemptRevision<=v.attemptRevision&&v.latest.manifestSha256===v.manifestSha256&&time(v.latest.answerDeadline)===time(v.answerDeadline)&&v.latest.automaticDeadline===v.automaticDeadline);}
  else check(v.approvalRevision===0);
  if(v.mayApprove)check(v.eligibilityIssue===null&&v.manifestSha256!==null&&v.automaticDeadline!==null&&time(v.serverNow)>=time(v.automaticDeadline)&&v.answerState==='UPLOAD_PENDING'&&['AWAITING_APPROVAL','APPROVED','EXPIRED'].includes(v.phase));
  else check(v.eligibilityIssue!==null);
  return v;
}
export function checkSupplementReceipt(r:SupplementReceipt,s:SupplementScope,original:SupplementCommand,v:SupplementView):SupplementReceipt{
  const c=copySupplementCommand(original);checkSupplementView(v,s);receiptProof(r,s);sameResource(r,s);
  check(r.approvalRevision===c.expectedRevision+1&&r.attemptRevision===c.expectedAttemptRevision+1&&r.idempotencyKey===c.idempotencyKey&&r.manifestSha256===c.manifestSha256&&r.reason===c.reason&&time(r.uploadDeadline)===time(c.uploadDeadline)&&time(r.answerDeadline)===time(v.answerDeadline)&&r.automaticDeadline===v.automaticDeadline);return r;
}
/** Native local datetime input, explicitly chosen by a teacher. Reject invalid/DST-normalized values. */
export function localDeadlineToUtc(input:string){check(typeof input==='string'&&/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(input));const d=new Date(input);check(Number.isFinite(d.getTime()));
  const pad=(n:number)=>String(n).padStart(2,'0'),local=`${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;check(local===(input.length===16?input+':00':input));const utc=d.toISOString();time(utc);return utc;
}
export function utcDeadlineToLocal(input:string){time(input);const d=new Date(input),pad=(n:number)=>String(n).padStart(2,'0');return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;}
