import request from '@/utils/request';
import {copyAnswerEndCommand, type AnswerEndCommand} from '@/prepared/answerEndManifest';

export type PreparedAttemptSummary = {
  id: string; recordId: number; assessmentId: number; attemptNo: number; paperVersionId: string;
  state: 'IN_PROGRESS'|'UPLOAD_PENDING'|'SUBMITTED'|'REQUIRES_REVIEW'|'CANCELLED';
  revision: number; startedAt: string; answeringEndedAt: string|null; supplementalUploadDeadline: string|null;
};
export type PreparedQuestionGrant = {
  assessmentId: number; attemptId: string; paperVersionId: string; answerId: string; questionId: string;
  ordinal: number; body: string; answerSeconds: number; state: 'RECORDING'|'UPLOAD_PENDING'|'SAVED'|'REQUIRES_REVIEW';
  startedAt: string; answerDeadline: string; serverTime: string; mayRecord: boolean;
};
/** Declaration-only receipt, never a byte-verification, final submission or scoring confirmation. */
export type PreparedAnswerEndReceipt = {
  answerId: string; manifestSha256: string; declarationState: 'UPLOAD_PENDING'|'REQUIRES_REVIEW'; revision: number;
  effectiveEndedAt: string; acceptedAt: string; reviewReasons: string[]; serverSaved: false;
  attemptState: PreparedAttemptSummary['state']; attemptRevision: number;
  answeringEndedAt: string|null; supplementalUploadDeadline: string|null;
};
export type PreparedQuestionStartCommand = {idempotencyKey: string; captureClientId: string; ordinal: number};
export type PreparedQuestionAudio = {
  studentId:number;assessmentId:number;attemptId:string;answerId:string;captureClientId:string;
  paperVersionId:string;questionId:string;assetId:string;url:string;expiresAt:string;serverTime:string;
  sha256:string;byteSize:number;durationMs:number;
};

function uuid(value: unknown): asserts value is string {
  if (typeof value !== 'string' || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)) {
    throw new Error('原作答命令标识无效，请保留原内容');
  }
}
function assessmentPath(assessmentId: number) {
  if (!Number.isSafeInteger(assessmentId) || assessmentId < 1) throw new Error('考核标识无效');
  return `/backend/prepared-interviews/${assessmentId}`;
}
function attemptPath(assessmentId: number, attemptId: string) {
  uuid(attemptId);
  return `${assessmentPath(assessmentId)}/attempts/${attemptId}`;
}

/** Caller persists its original key before calling. This transport never generates a replacement key or identity. */
export function startPreparedAttempt(assessmentId: number, command: {idempotencyKey: string}, signal?: AbortSignal) {
  const url = `${assessmentPath(assessmentId)}/attempts`;
  uuid(command?.idempotencyKey);
  return request<unknown, {data: PreparedAttemptSummary}>({url, method: 'post', data: {idempotencyKey: command.idempotencyKey}, timeout: 10000, signal});
}
export function getCurrentPreparedAttempt(assessmentId: number, signal?: AbortSignal) {
  return request<unknown, {data: PreparedAttemptSummary|null}>({url: `${assessmentPath(assessmentId)}/attempts/current`, method: 'get', timeout: 10000, signal});
}
export function startPreparedQuestion(assessmentId: number, attemptId: string, command: PreparedQuestionStartCommand, signal?: AbortSignal) {
  const url = `${attemptPath(assessmentId, attemptId)}/questions/start`;
  uuid(command?.idempotencyKey); uuid(command?.captureClientId);
  if (!Number.isSafeInteger(command.ordinal) || command.ordinal < 1 || command.ordinal > 50) throw new Error('题序无效');
  const data = {idempotencyKey: command.idempotencyKey, captureClientId: command.captureClientId, ordinal: command.ordinal};
  return request<unknown, {data: PreparedQuestionGrant}>({url, method: 'post', data, timeout: 10000, signal});
}
export function getCurrentPreparedQuestion(assessmentId: number, attemptId: string, captureClientId: string, signal?: AbortSignal) {
  const url = `${attemptPath(assessmentId, attemptId)}/questions/current`;
  uuid(captureClientId);
  return request<unknown, {data: PreparedQuestionGrant|null}>({url, method: 'get', params: {captureClientId}, timeout: 10000, signal});
}
export function endPreparedAnswer(assessmentId: number, attemptId: string, answerId: string, command: AnswerEndCommand, signal?: AbortSignal) {
  const path = attemptPath(assessmentId, attemptId);
  uuid(answerId);
  // Copy the bounded immutable contract, not a journal row, Blob, object URL or account supplied by the caller.
  const data = copyAnswerEndCommand(command);
  return request<unknown, {data: PreparedAnswerEndReceipt}>({url: `${path}/answers/${answerId}/end`, method: 'post', data, timeout: 10000, signal});
}
/** No start/recording command, arbitrary asset URL, student identity parameter or model request. */
export function getPreparedQuestionAudio(assessmentId:number,attemptId:string,answerId:string,captureClientId:string,signal?:AbortSignal){
  const path=attemptPath(assessmentId,attemptId);uuid(answerId);uuid(captureClientId);
  return request<unknown,{data:PreparedQuestionAudio}>({url:`${path}/answers/${answerId}/question-audio`,method:'get',params:{captureClientId},timeout:10000,signal});
}
