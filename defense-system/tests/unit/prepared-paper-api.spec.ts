import { beforeEach, expect, it, vi } from 'vitest';
import request from '@/utils/request';
import { getPreparedDraft, savePreparedDraft, approvePreparedQuestions } from '@/api/preparedPaper';

vi.mock('@/utils/request', () => ({ default: vi.fn() }));
beforeEach(() => { vi.mocked(request).mockReset(); });

it('reads only the specified prepared draft', async () => {
  vi.mocked(request).mockResolvedValue({ code: 200, data: { assessmentId: 21, revision: 0, questions: [] } });
  await getPreparedDraft(21);
  expect(request).toHaveBeenCalledWith({ url: '/backend/prepared-papers/21', method: 'get' });
});

it('saves the teacher supplied version and question list without optimistic server approval', async () => {
  const command = { expectedRevision: 2, questions: [] };
  vi.mocked(request).mockResolvedValue({ code: 200, data: { assessmentId: 21, revision: 3, questions: [] } });
  await savePreparedDraft(21, command);
  expect(request).toHaveBeenCalledWith({ url: '/backend/prepared-papers/21', method: 'put', data: command });
});

it('explicit review names only selected questions and keeps failures rejected', async () => {
  const command = { expectedRevision: 2, questionIds: ['11111111-1111-4111-8111-111111111111'] };
  vi.mocked(request).mockRejectedValue(new Error('version conflict'));
  await expect(approvePreparedQuestions(21, command)).rejects.toThrow('version conflict');
  expect(request).toHaveBeenCalledWith({ url: '/backend/prepared-papers/21/approve', method: 'post', data: command });
});
