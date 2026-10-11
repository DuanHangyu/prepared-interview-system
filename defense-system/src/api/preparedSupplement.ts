import request from '@/utils/request';
import { checkSupplementScope,copySupplementCommand } from '@/prepared/supplementProof';
export type SupplementScope={actorId:number;studentId:number;assessmentId:number;attemptId:string;answerId:string};
export type SupplementCommand={idempotencyKey:string;expectedRevision:number;expectedAttemptRevision:number;manifestSha256:string;uploadDeadline:string;reason:string};
export type SupplementReceipt=SupplementScope & {id:string;paperVersionId:string;approvalRevision:number;attemptRevision:number;manifestSha256:string;answerDeadline:string;answeringEndedAt:string;automaticDeadline:string;approvedAt:string;uploadDeadline:string;reason:string;idempotencyKey:string};
export type SupplementView=SupplementScope & {approvalRevision:number;attemptRevision:number;answerState:string;manifestSha256:string|null;answerDeadline:string;automaticDeadline:string|null;serverNow:string;phase:'AUTOMATIC_WINDOW'|'AWAITING_APPROVAL'|'APPROVED'|'EXPIRED'|'COMPLETE'|'NOT_ELIGIBLE';mayApprove:boolean;eligibilityIssue:string|null;latest:SupplementReceipt|null};
const path=(input:SupplementScope)=>{const s=checkSupplementScope(input);return `/backend/prepared-reviews/${s.assessmentId}/students/${s.studentId}/attempts/${s.attemptId}/answers/${s.answerId}/supplement`;};
export async function getSupplementStatus(scope:SupplementScope){return request<unknown,{data:SupplementView}>({url:path(scope),method:'get'});}
export async function approveSupplement(scope:SupplementScope,command:SupplementCommand){return request<unknown,{data:SupplementReceipt}>({url:path(scope),method:'post',data:copySupplementCommand(command)});}
