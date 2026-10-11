import { test, expect } from '@playwright/test';

const env = (globalThis as typeof globalThis & { process?: { env?: Record<string, string | undefined> } }).process?.env || {};
const assessment = Number(env.PREPARED_BROWSER_ASSESSMENT_ID);
const password = env.PREPARED_TEST_LOGIN_PASSWORD;
const jobs = (env.PREPARED_BROWSER_GENERATION_JOB_IDS || '').split(',');

test('real browser adopts explicitly synthetic suggestions, edits/reviews and preserves conflicts', async ({ page }, info) => {
  if (env.E2E_BASE_URL !== 'http://127.0.0.1:13015' || !password || assessment !== 8 || !jobs[info.repeatEachIndex]?.match(/^[a-f0-9-]{36}$/)) {
    throw new Error('Dedicated local fixtures and synthetic suggestion IDs required; never run against old or cloud data');
  }
  await page.goto('/login'); await page.getByPlaceholder('请输入账号').fill('prepared_browser_teacher');
  await page.getByPlaceholder('请输入密码').fill(password); await page.getByRole('button', { name: /立即登录/ }).click();
  await expect(page).toHaveURL(/teacherDashboard$/);
  const endpoint = `/dev-api/backend/prepared-papers/${assessment}`;
  const draft = (await (await page.request.get(endpoint)).json()).data;
  expect((await page.request.put(endpoint, { data: { expectedRevision: draft.revision, questions: [] }, headers: { Origin: 'http://127.0.0.1:13015' } })).status()).toBe(200);
  await page.goto(`/prepared-paper/${assessment}`);
  const panel = page.getByRole('region', { name: 'AI 辅助备题' });
  await expect(panel.getByText('独立凭据', { exact: false })).toBeVisible();
  await expect(panel.getByRole('button', { name: '生成建议', exact: true })).toBeDisabled();
  await panel.getByLabel('历史生成任务').selectOption(jobs[info.repeatEachIndex]);
  await expect(panel.getByText('自动化合成建议（非真实模型）', { exact: true })).toBeVisible();
  await expect(page.getByTestId('prepared-question')).toHaveCount(0);
  await panel.getByTestId('suggestion-choice').check();
  await page.getByRole('button', { name: '新增题目', exact: true }).click();
  await expect(panel.getByRole('button', { name: '采用所选建议', exact: true })).toBeDisabled();
  await page.getByTestId('question-body').fill('不能覆盖的手工题'); await page.getByTestId('question-rubric').fill('手工标准');
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('草稿已保存');
  // Actual concurrent database-backed write causes a version conflict, not a successful-response mock.
  const current = (await (await page.request.get(endpoint)).json()).data;
  expect((await page.request.put(endpoint, { data: { expectedRevision: current.revision, questions: current.questions }, headers: { Origin: 'http://127.0.0.1:13015' } })).status()).toBe(200);
  await panel.getByRole('button', { name: '采用所选建议', exact: true }).click();
  await expect(panel.getByRole('alert')).toContainText('冲突');
  await expect(page.getByTestId('question-body')).toHaveValue('不能覆盖的手工题');
  await page.getByRole('button', { name: '重新读取', exact: true }).click();
  await panel.getByLabel('历史生成任务').selectOption(jobs[info.repeatEachIndex]);
  await panel.getByTestId('suggestion-choice').check();
  await panel.getByRole('button', { name: '采用所选建议', exact: true }).click();
  await expect(page.getByTestId('prepared-question')).toHaveCount(2);
  const card = page.getByTestId('prepared-question').nth(1);
  await expect(card.getByText('待审核', { exact: true })).toBeVisible();
  await card.getByLabel('题目正文', { exact: true }).fill('教师已编辑的 AI 题目');
  await card.getByLabel('评分要求（仅教师可见）', { exact: true }).fill('教师核对后的标准');
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('草稿已保存');
  await card.getByRole('button', { name: '审核此题', exact: true }).click(); await expect(card.getByText('已审核', { exact: true })).toBeVisible();
  await page.reload(); await expect(page.getByTestId('prepared-question')).toHaveCount(2);
  await expect(page.getByTestId('question-body').nth(0)).toHaveValue('不能覆盖的手工题');
  await expect(page.getByTestId('question-body').nth(1)).toHaveValue('教师已编辑的 AI 题目');
  await expect(page.getByTestId('prepared-question').nth(1).getByText('已审核', { exact: true })).toBeVisible();
});
