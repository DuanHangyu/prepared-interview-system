import { expect, Page, test } from '@playwright/test';

const env = (globalThis as typeof globalThis & { process?: { env?: Record<string, string | undefined> } }).process?.env || {};
const password = env.PREPARED_TEST_LOGIN_PASSWORD;
const assessmentId = Number(env.PREPARED_BROWSER_ASSESSMENT_ID);
const endpoint = `/dev-api/backend/prepared-papers/${assessmentId}`;

test.describe('prepared paper: real local browser + sessions + database', () => {
  test.beforeAll(() => {
    if (env.E2E_BASE_URL !== 'http://127.0.0.1:13015' || !password || !Number.isSafeInteger(assessmentId) || assessmentId < 1) {
      throw new Error('Dedicated prepared local URL, fixture id and password are required; never run against the old system');
    }
  });

  test('teacher edits three questions, reviews, reorders, deletes and reloads the persisted draft', async ({ page }) => {
    await login(page, 'prepared_browser_teacher', '/teacherDashboard');
    await resetDraft(page);
    await page.getByRole('navigation', { name: '教师端导航' }).getByRole('button').filter({ hasText: '考核设置' }).click();
    await page.locator('article.setting-card').filter({ hasText: '预置题目 · 独立浏览器联调' })
      .getByRole('button', { name: '准备题目', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/prepared-paper/${assessmentId}$`));
    await expect(page.getByText('尚未添加题目。点击“新增题目”，填写第一道题。', { exact: true })).toBeVisible();
    for (let index = 0; index < 3; index++) {
      await page.getByRole('button', { name: '新增题目', exact: true }).click();
      const question = page.getByTestId('prepared-question').nth(index);
      await question.getByLabel('题目正文', { exact: true }).fill(`第${index + 1}道真实联调题目`);
      await question.getByLabel('评分要求（仅教师可见）', { exact: true }).fill(`第${index + 1}道评分要求`);
      await question.getByLabel('分值', { exact: true }).fill('10');
      await question.getByLabel('回答时限（秒）', { exact: true }).fill('120');
    }
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('草稿已保存');
    const saved = await readDraft(page);
    const ids = saved.questions.map((q: { questionId: string }) => q.questionId);
    for (let index = 0; index < 3; index++) {
      await page.getByTestId('prepared-question').nth(index).getByRole('button', { name: '审核此题', exact: true }).click();
      await expect(page.getByTestId('prepared-question').nth(index).getByText('已审核', { exact: true })).toBeVisible();
    }
    await page.getByTestId('prepared-question').nth(2).getByTestId('move-up').click();
    await page.getByTestId('prepared-question').nth(1).getByTestId('move-up').click();
    await page.getByTestId('prepared-question').nth(2).getByTestId('remove-question').click();
    const edited = page.getByTestId('prepared-question').nth(1);
    await edited.getByLabel('题目正文', { exact: true }).fill('第一道已修改，不应该仍显示已审核');
    await edited.getByLabel('分值', { exact: true }).fill('20');
    await edited.getByLabel('回答时限（秒）', { exact: true }).fill('180');
    await edited.getByLabel('评分要求（仅教师可见）', { exact: true }).fill('新版评分要求');
    await expect(edited.getByText('待审核', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('草稿已保存');
    await page.reload();
    await expect(page.getByTestId('prepared-question')).toHaveCount(2);
    await expect(page.getByTestId('question-body').nth(0)).toHaveValue('第3道真实联调题目');
    await expect(page.getByTestId('question-body').nth(1)).toHaveValue('第一道已修改，不应该仍显示已审核');
    await expect(page.getByTestId('prepared-question').nth(0).getByText('已审核', { exact: true })).toBeVisible();
    await expect(page.getByTestId('prepared-question').nth(1).getByText('待审核', { exact: true })).toBeVisible();
    const persisted = await readDraft(page);
    expect(persisted.questions.map((q: { questionId: string }) => q.questionId)).toEqual([ids[2], ids[0]]);
    expect(persisted.questions[1]).toMatchObject({ maxScore: 20, answerSeconds: 180, rubric: '新版评分要求', approved: false });
  });

  test('actual transport failure and stale revision preserve teacher edits and never report saved', async ({ page }) => {
    await login(page, 'prepared_browser_teacher', '/teacherDashboard');
    await resetDraft(page);
    await page.goto(`/prepared-paper/${assessmentId}`);
    await page.getByRole('button', { name: '新增题目', exact: true }).click();
    await page.getByTestId('question-body').fill('网络失败也不能丢失');
    await page.getByTestId('question-rubric').fill('评分规则');
    // Fault injection only: normal paths use the real backend; no successful response is mocked.
    await page.route(`**${endpoint}`, async route => {
      if (route.request().method() === 'PUT') await route.abort('connectionfailed');
      else await route.continue();
    });
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByTestId('question-body')).toHaveValue('网络失败也不能丢失');
    await expect(page.getByRole('status')).toHaveCount(0);
    await page.unroute(`**${endpoint}`);
    const current = await readDraft(page);
    const competing = await page.request.put(endpoint, {
      data: { expectedRevision: current.revision, questions: [] },
      headers: { Origin: 'http://127.0.0.1:13015' },
    });
    expect(competing.status()).toBe(200);
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('试卷已被修改');
    await expect(page.getByTestId('question-body')).toHaveValue('网络失败也不能丢失');
    await expect(page.getByRole('status')).toHaveCount(0);
    expect((await readDraft(page)).questions).toHaveLength(0);
  });

  for (const account of ['prepared_browser_student', 'prepared_browser_unrelated']) {
    test(`${account} cannot read or modify another teacher's draft`, async ({ page }) => {
      await login(page, account, account.endsWith('student') ? '/studentDashboard' : '/teacherDashboard');
      const eligibility=await page.request.get(`/dev-api/backend/prepared-interviews/${assessmentId}/eligibility`);
      if(account.endsWith('student')) {
        expect(eligibility.status()).toBe(200);
        expect(eligibility.headers()['cache-control']).toContain('no-store');
        const status=(await eligibility.json()).data;
        expect(status.phase).toBe('PAPER_NOT_PUBLISHED');
        expect(status.publicationReady).toBe(false);
        expect(status.questionCount).toBe(0);
        for(const field of ['questions','rubric','audio','objectKey','paperVersionId','attemptId','score'])expect(status).not.toHaveProperty(field);
      } else expect(eligibility.status()).toBe(403);
      const read = await page.request.get(endpoint);
      expect(read.status()).toBe(403);
      expect((await page.request.get(`${endpoint}/audio`)).status()).toBe(403);
      expect((await page.request.get(`${endpoint}/audio/${crypto.randomUUID()}/preview`)).status()).toBe(403);
      const write = await page.request.put(endpoint, {
        data: { expectedRevision: 0, questions: [] }, headers: { Origin: 'http://127.0.0.1:13015' },
      });
      expect(write.status()).toBe(403);
      await page.goto(`/prepared-paper/${assessmentId}`);
      await expect(page.getByTestId('question-body')).toHaveCount(0);
      if (account.endsWith('unrelated')) await expect(page.getByRole('alert')).toContainText('无权限');
    });
  }
});

async function login(page: Page, account: string, expected: string) {
  await page.goto('/login');
  await page.getByPlaceholder('请输入账号').fill(account);
  await page.getByPlaceholder('请输入密码').fill(password || '');
  await page.getByRole('button', { name: /立即登录/ }).click();
  await expect(page).toHaveURL(new RegExp(`${expected}$`));
}

async function readDraft(page: Page) {
  const response = await page.request.get(endpoint);
  expect(response.status()).toBe(200);
  return (await response.json()).data;
}

async function resetDraft(page: Page) {
  const current = await readDraft(page);
  const response = await page.request.put(endpoint, {
    data: { expectedRevision: current.revision, questions: [] }, headers: { Origin: 'http://127.0.0.1:13015' },
  });
  expect(response.status()).toBe(200);
}
