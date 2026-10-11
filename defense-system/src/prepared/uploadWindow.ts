import {copyUploadAnswer,uploadCheck,type UploadAnswer} from './uploadBundle';
export type UploadWindow={studentId:number;assessmentId:number;attemptId:string;answerId:string;paperVersionId:string;manifestSha256:string|null;
  answerDeadline:string;answeringEndedAt:string|null;automaticDeadline:string|null;serverNow:string;
  phase:'ANSWERING'|'AUTOMATIC_WINDOW'|'AWAITING_APPROVAL'|'APPROVED'|'EXPIRED'|'NOT_ELIGIBLE';mayUploadOriginal:boolean;approvalRevision:number;approvedAt:string|null;uploadDeadline:string|null};
const check=(v:unknown)=>uploadCheck(v,'INTEGRITY');
const uuid=(v:unknown)=>check(typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v));
function time(v:unknown){check(typeof v==='string'&&/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(v));const s=v as string,n=Date.parse(s);check(Number.isFinite(n));
  const canonical=s.replace(/(?:\.(\d{1,3}))?Z$/,(_all,f:string|undefined)=>'.'+(f??'').padEnd(3,'0')+'Z');check(new Date(n).toISOString()===canonical);return n;
}
/** A window never proves media saving. Bind a recovery query to the exact locally sealed manifest. */
export function verifyUploadWindow(input:unknown,studentId:number,answer:UploadAnswer,manifestSha256?:string,paperVersionId?:string):UploadWindow{
  const a=copyUploadAnswer(answer),r=input as UploadWindow;check(Number.isSafeInteger(studentId)&&studentId>0&&studentId<=2147483647);
  check(!!r&&typeof r==='object'&&!Array.isArray(r)&&r.studentId===studentId&&r.assessmentId===a.assessmentId&&r.attemptId===a.attemptId&&r.answerId===a.answerId);uuid(r.paperVersionId);
  if(paperVersionId!==undefined)check(r.paperVersionId===paperVersionId);
  check(r.manifestSha256===null||typeof r.manifestSha256==='string'&&/^[a-f0-9]{64}$/.test(r.manifestSha256));if(manifestSha256!==undefined)check(r.manifestSha256===manifestSha256);
  time(r.answerDeadline);const observed=time(r.serverNow);check(Number.isSafeInteger(r.approvalRevision)&&r.approvalRevision>=0&&typeof r.mayUploadOriginal==='boolean');
  check(['ANSWERING','AUTOMATIC_WINDOW','AWAITING_APPROVAL','APPROVED','EXPIRED','NOT_ELIGIBLE'].includes(r.phase));
  check(r.mayUploadOriginal===['ANSWERING','AUTOMATIC_WINDOW','APPROVED'].includes(r.phase));
  if(r.answeringEndedAt===null){check(r.automaticDeadline===null&&r.uploadDeadline===null&&r.approvalRevision===0&&r.approvedAt===null&&['ANSWERING','NOT_ELIGIBLE'].includes(r.phase));}
  else{
    const ended=time(r.answeringEndedAt),automatic=time(r.automaticDeadline),deadline=time(r.uploadDeadline);check(automatic===ended+86400000&&r.manifestSha256!==null&&r.phase!=='ANSWERING');
    if(r.approvalRevision===0){check(r.approvedAt===null&&deadline===automatic&&!['APPROVED','EXPIRED'].includes(r.phase));}
    else{const approved=time(r.approvedAt);check(approved>=automatic&&deadline>approved);}
    if(r.phase==='AUTOMATIC_WINDOW')check(observed<automatic&&r.approvalRevision===0);
    if(r.phase==='AWAITING_APPROVAL')check(observed>=automatic&&r.approvalRevision===0);
    if(r.phase==='APPROVED')check(r.approvalRevision>0&&observed>=time(r.approvedAt)&&observed<deadline);
    if(r.phase==='EXPIRED')check(r.approvalRevision>0&&observed>=deadline);
  }
  return {studentId:r.studentId,assessmentId:r.assessmentId,attemptId:r.attemptId,answerId:r.answerId,paperVersionId:r.paperVersionId,manifestSha256:r.manifestSha256,
    answerDeadline:r.answerDeadline,answeringEndedAt:r.answeringEndedAt,automaticDeadline:r.automaticDeadline,serverNow:r.serverNow,phase:r.phase,mayUploadOriginal:r.mayUploadOriginal,
    approvalRevision:r.approvalRevision,approvedAt:r.approvedAt,uploadDeadline:r.uploadDeadline};
}
