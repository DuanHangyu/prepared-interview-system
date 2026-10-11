import request from '@/utils/request';
import type { PreparedDraft, PreparedQuestionInput } from './preparedPaper';

export type GenerationState = 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'NEEDS_REVIEW';
export type GenerationSummary = { id: string; state: GenerationState; inputVersion: number; errorCode: string | null; createdAt: string };
export type GenerationListing = { availability: { enabled: boolean; reason: string }; jobs: GenerationSummary[] };
export type GenerationView = GenerationSummary & { suggestions: PreparedQuestionInput[]; adoptedQuestionIds: string[] };
export type GenerationCommand = { expectedRevision: number; idempotencyKey: string; requirements: string; count: number; maxScore: number; answerSeconds: number };
export type AdoptGenerationCommand = { expectedRevision: number; idempotencyKey: string; questionIds: string[] };
type Response<T> = { data: T };
const path = (id: number) => `/backend/prepared-papers/${id}/generations`;
export function listGenerations(id: number) { return request<unknown, Response<GenerationListing>>({ url: path(id), method: 'get' }); }
export function getGeneration(id: number, job: string) { return request<unknown, Response<GenerationView>>({ url: `${path(id)}/${job}`, method: 'get' }); }
export function requestGeneration(id: number, data: GenerationCommand) { return request<unknown, Response<GenerationSummary>>({ url: path(id), method: 'post', data }); }
export function adoptGeneration(id: number, job: string, data: AdoptGenerationCommand) {
  return request<unknown, Response<{ draft: PreparedDraft; adoptedRevision: number }>>({ url: `${path(id)}/${job}/adopt`, method: 'post', data });
}
