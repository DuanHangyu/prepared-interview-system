import { expect, test } from '@playwright/test';
const env = (globalThis as typeof globalThis & { process?: { env?: Record<string, string | undefined> } }).process?.env || {};
const password = env.PREPARED_TEST_LOGIN_PASSWORD;
const assessment = Number(env.PREPARED_BROWSER_ASSESSMENT_ID);

test('real browser publication checks block unprepared audio without losing teacher edits', async ({ page }) => {
  if (env.E2E_BASE_URL !== 'http://127.0.0.1:13015' || !password || assessment !== 8) {
    throw new Error('Only the dedicated prepared local browser fixture is authorized');
  }
  await page.goto('/login'); await page.getByPlaceholder('请输入账号').fill('prepared_browser_teacher');
  await page.getByPlaceholder('请输入密码').fill(password); await page.getByRole('button', { name: /立即登录/ }).click();
  await expect(page).toHaveURL(/teacherDashboard$/);
  const endpoint = `/dev-api/backend/prepared-papers/${assessment}`;
  const draft = (await (await page.request.get(endpoint)).json()).data;
  expect((await page.request.put(endpoint, { data: { expectedRevision: draft.revision, questions: [] }, headers: { Origin: 'http://127.0.0.1:13015' } })).status()).toBe(200);
  await page.goto(`/prepared-paper/${assessment}`);
  const panel = page.getByRole('region', { name: '播报与发布检查' });
  await expect(panel.getByText('尚未添加题目', { exact: true })).toBeVisible();
  await expect(panel.getByRole('button', { name: '发布固定试卷', exact: true })).toBeDisabled();
  for (let i = 0; i < 3; i++) {
    await page.getByRole('button', { name: '新增题目', exact: true }).click();
    await page.getByTestId('prepared-question').nth(i).getByLabel('题目正文', { exact: true }).fill(`发布检查题 ${i + 1}`);
    await page.getByTestId('prepared-question').nth(i).getByLabel('评分要求（仅教师可见）', { exact: true }).fill('依据清楚');
  }
  const audio=page.getByRole('region',{name:'预制题目播报'});
  await expect(audio.getByText(/播报尚未启用/)).toBeVisible();
  await expect(audio.getByRole('button',{name:'准备本题播报',exact:true})).toHaveCount(3);
  for(let i=0;i<3;i++)await expect(audio.getByRole('button',{name:'准备本题播报',exact:true}).nth(i)).toBeDisabled();
  const audioState=await page.request.get(`${endpoint}/audio`);expect(audioState.status()).toBe(200);
  expect((await audioState.json()).data.availability.enabled).toBe(false);
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(panel.getByText('考核设置：3 题，总分 30，通过分数 18。', { exact: true })).toBeVisible();
  for (let i = 0; i < 3; i++) {
    await page.getByTestId('prepared-question').nth(i).getByRole('button', { name: '审核此题', exact: true }).click();
    await expect(page.getByTestId('prepared-question').nth(i).getByText('已审核', { exact: true })).toBeVisible();
  }
  await expect(panel.getByText('第 1 题：请先生成并保存本题播报', { exact: true })).toBeVisible();
  await expect(panel.getByRole('button', { name: '发布固定试卷', exact: true })).toBeDisabled();
  const saved = (await (await page.request.get(endpoint)).json()).data;
  const rejected = await page.request.post(`${endpoint}/publications`, { data: { expectedRevision: saved.revision, idempotencyKey: crypto.randomUUID(), expectedContentSha256: 'a'.repeat(64) }, headers: { Origin: 'http://127.0.0.1:13015' } });
  expect(rejected.status()).toBe(409);
  expect((await (await page.request.get(`${endpoint}/publications`)).json()).data.versions).toHaveLength(0);
  await page.getByTestId('question-body').nth(0).fill('检查失败也保留的未保存修改');
  // Actual transport failure, no successful response mock.
  await page.route(`**${endpoint}/publications`, route => route.abort('connectionfailed'));
  await panel.getByRole('button', { name: '刷新发布检查', exact: true }).click();
  await expect(panel.getByRole('alert')).toBeVisible();
  await expect(page.getByTestId('question-body').nth(0)).toHaveValue('检查失败也保留的未保存修改');
  await expect(panel.getByRole('button', { name: '发布固定试卷', exact: true })).toBeDisabled();
  await page.unroute(`**${endpoint}/publications`);
  await panel.getByRole('button', { name: '刷新发布检查', exact: true }).click();
  await expect(panel.getByRole('alert')).toHaveCount(0);
  await expect(page.getByTestId('question-body').nth(0)).toHaveValue('检查失败也保留的未保存修改');
  await page.route(`**${endpoint}/audio`,route=>route.abort('connectionfailed'));
  await audio.getByRole('button',{name:'刷新播报状态',exact:true}).click();await expect(audio.getByRole('alert')).toBeVisible();
  await expect(page.getByTestId('question-body').nth(0)).toHaveValue('检查失败也保留的未保存修改');
  await page.unroute(`**${endpoint}/audio`);await audio.getByRole('button',{name:'刷新播报状态',exact:true}).click();
  await expect(audio.getByRole('alert')).toHaveCount(0);
});
