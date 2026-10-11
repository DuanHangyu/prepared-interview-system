import { expect, it, vi } from 'vitest';
import request from '@/utils/request';
import { getPublicationStatus, getPublication, publishPreparedPaper } from '@/api/preparedPublication';
vi.mock('@/utils/request', () => ({ default: vi.fn().mockResolvedValue({ data: {} }) }));
it('uses assessment-scoped real publication endpoints and preserves the original command', async () => {
  const cmd = { expectedRevision: 2, idempotencyKey: '11111111-1111-4111-8111-111111111111', expectedContentSha256: 'a'.repeat(64) };
  await getPublicationStatus(21); await getPublication(21, cmd.idempotencyKey); await publishPreparedPaper(21, cmd);
  expect(vi.mocked(request).mock.calls.map(c => c[0])).toEqual([
    { url: '/backend/prepared-papers/21/publications', method: 'get' },
    { url: `/backend/prepared-papers/21/publications/${cmd.idempotencyKey}`, method: 'get' },
    { url: '/backend/prepared-papers/21/publications', method: 'post', data: cmd },
  ]);
});
