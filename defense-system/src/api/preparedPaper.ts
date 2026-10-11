import request from '@/utils/request';

export type PreparedQuestionInput = {
  questionId: string;
  body: string;
  maxScore: number;
  answerSeconds: number;
  rubric: string;
};

export type PreparedQuestion = PreparedQuestionInput & {
  source: 'MANUAL' | 'AI';
  ordinal: number;
  approved: boolean;
  audioState: 'PENDING' | 'GENERATING' | 'READY' | 'FAILED' | 'STALE';
  audioObjectKey: string | null;
};

export type PreparedDraft = {
  assessmentId: number;
  revision: number;
  questions: PreparedQuestion[];
};

type Response<T> = { data: T; code?: number; message?: string };
export type SavePreparedDraft = { expectedRevision: number; questions: PreparedQuestionInput[] };
export type ApprovePreparedQuestions = { expectedRevision: number; questionIds: string[] };

export function getPreparedDraft(assessmentId: number) {
  return request<unknown, Response<PreparedDraft>>({ url: `/backend/prepared-papers/${assessmentId}`, method: 'get' });
}

export function savePreparedDraft(assessmentId: number, data: SavePreparedDraft) {
  return request<unknown, Response<PreparedDraft>>({ url: `/backend/prepared-papers/${assessmentId}`, method: 'put', data });
}

export function approvePreparedQuestions(assessmentId: number, data: ApprovePreparedQuestions) {
  return request<unknown, Response<PreparedDraft>>({ url: `/backend/prepared-papers/${assessmentId}/approve`, method: 'post', data });
}
