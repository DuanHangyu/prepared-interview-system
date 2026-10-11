import { beforeEach, it, expect, vi } from 'vitest';
import request from '@/utils/request';
import { listGenerations, getGeneration, requestGeneration, adoptGeneration } from '@/api/preparedGeneration';
vi.mock('@/utils/request', () => ({ default: vi.fn() }));
beforeEach(() => vi.mocked(request).mockReset());
it('uses the scoped durable task and adoption contracts without a live model call in the UI', async () => {
  vi.mocked(request).mockResolvedValue({ data: {} });
  await listGenerations(21); await getGeneration(21, 'job');
  const command = { expectedRevision: 1, idempotencyKey: 'key', requirements: '要求', count: 3, maxScore: 10, answerSeconds: 120 };
  const adopt = { expectedRevision: 1, idempotencyKey: 'adopt', questionIds: ['q'] };
  await requestGeneration(21, command); await adoptGeneration(21, 'job', adopt);
  expect(vi.mocked(request).mock.calls.map(c => c[0])).toEqual([
    { url: '/backend/prepared-papers/21/generations', method: 'get' },
    { url: '/backend/prepared-papers/21/generations/job', method: 'get' },
    { url: '/backend/prepared-papers/21/generations', method: 'post', data: command },
    { url: '/backend/prepared-papers/21/generations/job/adopt', method: 'post', data: adopt },
  ]);
});
