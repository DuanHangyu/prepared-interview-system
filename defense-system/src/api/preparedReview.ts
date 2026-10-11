import request from '@/utils/request';
import type {AxiosResponse} from 'axios';
import type {StudentResultAnswer} from './preparedResult';
import type { PublicationView } from './preparedPublication';
export type ReviewTask={state:string;errorCode:string|null};
export type ReviewRow={recordId:number;studentId:number;studentName:string;attemptId:string|null;state:string};
export type ReviewRoster={actorId:number;assessmentId:number;theme:string;rows:ReviewRow[];nextRecordId:number|null};
export type ReviewSummaryGrade={answerId:string;ordinal:number;maxScore:number;score:number;scoringSource:'MODEL'|'TEACHER';version:number;confirmed:boolean};
export type ReviewSummary={actorId:number;assessmentId:number;studentId:number;studentName:string|null;theme:string;attemptId:string;attemptNo:number;current:boolean;showResult:boolean;
  state:'READY'|'PROCESSING'|'IN_PROGRESS'|'UPLOAD_PENDING'|'REQUIRES_REVIEW'|'CANCELLED';resultVersion:string|null;completedAt:string|null;totalScore:number|null;totalMaxScore:number|null;passScore:number|null;answers:ReviewSummaryGrade[]};
/** Shared answer content shape only; authorization remains the manager's original attempt. */
export type ReviewReport={schemaVersion:1;summary:ReviewSummary;answers:StudentResultAnswer[]};
export type ReviewAttempt={id:string;attemptNo:number;state:string;startedAt:string;submittedAt:string|null;current:boolean};
export type ReviewAttemptsPage={actorId:number;assessmentId:number;studentId:number;recordId:number;activeAttemptId:string|null;attempts:ReviewAttempt[];nextAttemptNo:number|null};
export type ReviewInterview={actorId:number;assessmentId:number;studentId:number;studentName:string;attemptId:string;attemptNo:number;state:string;submitted:boolean;submittedAt:string|null;
  answers:{answerId:string;ordinal:number;state:string;transcription:ReviewTask;scoring:ReviewTask}[];current:boolean};
export type ReviewTranscript={schemaVersion:number;provider:string;model:string;audioSha256:string;durationMs:number;sentences:{startMs:number;endMs:number;text:string}[]};
export type ReviewScore={schemaVersion:number;questionId:string;transcriptId:string;transcriptSha256:string;maxScore:number;score:number;feedback:string;evidence:{sentenceOrdinal:number;quote:string}[]};
export type ReviewHistory={id:string;kind:string;version:number;completedAt:string;provider:string;model:string;score:ReviewScore|null};
export type ReviewManualScore={id:string;answerId:string;version:number;revision:number;questionId:string;maxScore:number;score:number;reason:string;recordedBy:number;recordedAt:string;audioSha256:string;transcriptId:string|null;scoreId:string|null;previousManualScoreId:string|null};
export type ReviewManualPage={current:ReviewManualScore|null;history:ReviewManualScore[];nextManualScoreId:string|null};
export type ReviewAnswer={actorId:number;answerId:string;revision:number;confirmed:boolean;
  question:Pick<PublicationView['snapshot']['questions'][number],'questionId'|'source'|'body'|'ordinal'|'maxScore'|'answerSeconds'|'rubric'>;
  transcriptId:string|null;transcript:ReviewTranscript|null;scoreId:string|null;score:ReviewScore|null;history:ReviewHistory[];nextResultId:string|null;manualScore?:ReviewManualScore|null};
export type ConfirmReviewCommand={idempotencyKey:string;expectedRevision:number;scoreId:string;reason:string};
export type RegradeReviewCommand={idempotencyKey:string;expectedRevision:number;transcriptId:string;reason:string};
export type AdjustReviewCommand={idempotencyKey:string;expectedRevision:number;score:number;reason:string};
export type AdjustReviewReceipt={answerId:string;revision:number;manualScoreId:string};
export type ConfirmReviewReceipt={answerId:string;revision:number;scoreId:string};
export type RegradeReviewReceipt={jobId:string;answerId:string;revision:number;transcriptId:string};
export type ReviewAudio={actorId:number;assessmentId:number;studentId:number;attemptId:string;answerId:string;url:string;expiresAt:string;serverTime:string;sha256:string;byteSize:number;durationMs:number};
export type ReviewEvidenceDeclaration={manifestSha256:string;state:string;revision:number;effectiveEndedAt:string;acceptedAt:string;reviewReasons:string[];byteSize:number;chunkCount:number;segmentCount:number};
export type ReviewEvidenceSegment={id:string;ordinal:number;mimeType:string;state:string;interruption:string|null;declaredStartedAt:number;declaredEndedAt:number;chunkCount:number;byteSize:number};
export type ReviewEvidence={actorId:number;assessmentId:number;studentId:number;attemptId:string;answerId:string;current:boolean;attemptState:string;attemptRevision:number;answerState:string;answerRevision:number;
  question:Pick<ReviewAnswer['question'],'questionId'|'ordinal'|'body'|'answerSeconds'|'maxScore'|'rubric'>;startedAt:string;answerDeadline:string;
  declaration:ReviewEvidenceDeclaration|null;segments:ReviewEvidenceSegment[];nextSegmentOrdinal:number|null};
type Response<T>={data:T};
const base=(assessment:number)=>`/backend/prepared-reviews/${assessment}`;
const answerPath=(assessment:number,attempt:string,answer:string)=>`${base(assessment)}/attempts/${attempt}/answers/${answer}`;
export function getReviewRoster(assessment:number,afterRecordId=0){return request<unknown,Response<ReviewRoster>>({url:base(assessment),method:'get',params:{afterRecordId,size:20}});}
export function getReviewAttempts(assessment:number,student:number,afterAttemptNo=0){return request<unknown,Response<ReviewAttemptsPage>>({url:`${base(assessment)}/students/${student}/attempts`,method:'get',params:{afterAttemptNo,size:10}});}
export function getReviewInterview(assessment:number,attempt:string){return request<unknown,Response<ReviewInterview>>({url:`${base(assessment)}/attempts/${attempt}`,method:'get'});}
export function getReviewSummary(assessment:number,attempt:string,signal?:AbortSignal){
  const url=reviewReadPath(assessment,attempt);
  return request<unknown,Response<ReviewSummary>>({url:`${url}/summary`,method:'get',timeout:10000,signal});
}
function reviewReadPath(assessment:number,attempt:string){
  if(!Number.isSafeInteger(assessment)||assessment<1||assessment>2147483647||typeof attempt!=='string'||!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(attempt))throw new Error('原评阅考核或尝试标识无效');
  return `${base(assessment)}/attempts/${attempt}`;
}
function reviewReportPath(assessment:number,attempt:string,resultVersion:string,suffix:string){
  const url=reviewReadPath(assessment,attempt);
  if(typeof resultVersion!=='string'||!/^[a-f0-9]{64}$/.test(resultVersion))throw new Error('原整场成绩版本无效');
  return `${url}/${suffix}`;
}
export function getReviewReport(assessment:number,attempt:string,resultVersion:string,signal?:AbortSignal){
  return request<unknown,Response<ReviewReport>>({url:reviewReportPath(assessment,attempt,resultVersion,'report'),method:'get',params:{resultVersion},timeout:10000,signal});
}
export function exportReviewReport(assessment:number,attempt:string,resultVersion:string,signal?:AbortSignal){
  return request<unknown,AxiosResponse<Blob>>({url:reviewReportPath(assessment,attempt,resultVersion,'export.xlsx'),method:'get',params:{resultVersion},responseType:'blob',timeout:10000,signal});
}
export function getReviewAnswer(assessment:number,attempt:string,answer:string,afterResultId?:string){return request<unknown,Response<ReviewAnswer>>({url:answerPath(assessment,attempt,answer),method:'get',params:{afterResultId,size:10}});}
export function getReviewAudio(assessment:number,attempt:string,answer:string){return request<unknown,Response<ReviewAudio>>({url:`${answerPath(assessment,attempt,answer)}/audio`,method:'get'});}
export function getReviewEvidence(assessment:number,attempt:string,answer:string,afterSegmentOrdinal=0){return request<unknown,Response<ReviewEvidence>>({url:`${answerPath(assessment,attempt,answer)}/evidence`,method:'get',params:{afterSegmentOrdinal,size:20}});}
export function confirmReviewScore(assessment:number,attempt:string,answer:string,data:ConfirmReviewCommand){return request<unknown,Response<ConfirmReviewReceipt>>({url:`${answerPath(assessment,attempt,answer)}/confirm`,method:'post',data});}
export function requestReviewRegrade(assessment:number,attempt:string,answer:string,data:RegradeReviewCommand){return request<unknown,Response<RegradeReviewReceipt>>({url:`${answerPath(assessment,attempt,answer)}/regrade`,method:'post',data});}
export function adjustReviewScore(assessment:number,attempt:string,answer:string,data:AdjustReviewCommand){return request<unknown,Response<AdjustReviewReceipt>>({url:`${answerPath(assessment,attempt,answer)}/adjust`,method:'post',data});}
export function getReviewManualHistory(assessment:number,attempt:string,answer:string,afterManualScoreId?:string){return request<unknown,Response<ReviewManualPage>>({url:`${answerPath(assessment,attempt,answer)}/manual-history`,method:'get',params:{afterManualScoreId,size:10}});}
