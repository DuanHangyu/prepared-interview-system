import request from '@/utils/request';

export type PublicationCommand = { expectedRevision: number; idempotencyKey: string; expectedContentSha256: string };
export type PublicationIssue = { code: string; questionId: string | null; ordinal: number | null; message: string };
export type PublicationRules = { theme: string; totalScore: number | null; passScore: number | null; questionCount: number | null; showResult: boolean };
export type PublicationSummary = { id: string; versionNo: number; draftRevision: number; contentSha256: string; publishedBy: number; publishedAt: string };
export type PublicationStatus = { assessmentId: number; draftRevision: number; rules: PublicationRules; publishable: boolean; frozen: boolean; preparedContentSha256: string | null;
  activeVersion: PublicationSummary | null; issues: PublicationIssue[]; versions: PublicationSummary[] };
export type PublicationAudio = { assetId: string; bodySha256: string; configSha256: string; contentSha256: string; byteSize: number; durationMs: number; mediaType: string };
export type PublicationView = { version: PublicationSummary; snapshot: { schemaVersion: number; assessmentId: number; rules: PublicationRules;
  questions: { questionId: string; source: string; body: string; ordinal: number; maxScore: number; answerSeconds: number; rubric: string; audio: PublicationAudio }[] } };
type Response<T> = { data: T };
const path = (id: number) => `/backend/prepared-papers/${id}/publications`;
export function getPublicationStatus(id: number) { return request<unknown, Response<PublicationStatus>>({ url: path(id), method: 'get' }); }
export function getPublication(id: number, version: string) { return request<unknown, Response<PublicationView>>({ url: `${path(id)}/${version}`, method: 'get' }); }
export function publishPreparedPaper(id: number, data: PublicationCommand) { return request<unknown, Response<PublicationView>>({ url: path(id), method: 'post', data }); }
