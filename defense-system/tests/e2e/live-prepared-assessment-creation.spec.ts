import { expect, Page, test } from '@playwright/test';
import { assertPreparedFixture } from '../../../scripts/assert-owned-browser-fixture.mjs';

const env = (globalThis as typeof globalThis & { process?: { env?: Record<string, string | undefined> } }).process?.env || {};
const roles = ['TEACHER', 'INVITED', 'UNRELATED', 'STUDENT', 'OTHERSTUDENT', 'ADMIN'] as const;
type Actor = typeof roles[number];
const theme = env.PREPARED_CREATION_THEME || '';
const schoolClass = env.PREPARED_CREATION_CLASS || '';
const id = (actor: Actor) => Number(env[`PREPARED_CREATION_${actor}_ID`]);
const account = (actor: Actor) => env[`PREPARED_CREATION_${actor}_ACCOUNT`] || '';
const origin = 'http://127.0.0.1:13015';
const headers = { Origin: origin };

test.beforeAll(() => {
  if (env.E2E_BASE_URL !== origin || !env.PREPARED_TEST_LOGIN_PASSWORD || !env.PREPARED_VALIDATION_BROWSER_DESCRIPTOR
    || !theme.startsWith('预置创建分配联调-') || !schoolClass.startsWith('创建分配班-')) throw Error('OWNED_CREATION_FIXTURE_REQUIRED');
  for (const actor of roles) {
    assertPreparedFixture(env, id(actor));
    if (!account(actor).startsWith('prepared-create-')) throw Error('OWNED_CREATION_ACCOUNT_REQUIRED');
  }
  if (new Set(roles.map(id)).size !== roles.length) throw Error('DISTINCT_CREATION_IDENTITIES_REQUIRED');
});

test('real teacher creates fixed-question assessment, assigns students and checks six role boundaries', async ({ browser }) => {
  test.setTimeout(60000); // New full six-role workflow; HTTP remains bounded to eight seconds below.
  let assessment = 0;
  const teacher = await browser.newContext();
  try {
    const page = await teacher.newPage();
    await login(page, 'TEACHER', '/teacherDashboard');
    await page.getByRole('navigation', { name: '教师端导航' }).getByRole('button', { name: '考核设置', exact: true }).click();
    await page.getByRole('button').filter({ hasText: /^新增$/ }).click();
    const dialog = page.getByRole('dialog', { name: '新增预置面试考核' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('每题回答时限在“准备题目”中逐题设置');
    await expect(dialog).toContainText('保存考核设置不会发布试卷');
    for (const label of ['是否答辩', '是否提问', '是否追问', '答辩时长（秒）']) {
      await expect(dialog.getByText(label, { exact: true })).toHaveCount(0);
    }
    await dialog.getByLabel('考核主题', { exact: true }).fill(theme);
    await dialog.getByLabel('考核要求', { exact: true }).fill('逐题回答；故障恢复不算重答');
    await dialog.getByLabel(/^考核标准/).fill('三题合计30分，评分要求由教师逐题审核');
    await dialog.getByLabel('考核总分', { exact: true }).fill('30');
    await dialog.getByLabel('及格分', { exact: true }).fill('18');
    await dialog.getByLabel('预置题目数量', { exact: true }).fill('3');
    await dialog.getByLabel('是否需要预约', { exact: true }).click();

    await dialog.getByLabel('选择班级/学生', { exact: true }).click();
    const classNode = page.locator('.ant-select-tree-treenode').filter({ hasText: schoolClass });
    await classNode.locator('.ant-select-tree-switcher').click();
    const studentNode = page.locator('.ant-select-tree-treenode').filter({ hasText: schoolClass }).filter({ has: page.locator('.ant-select-tree-switcher-noop') });
    await expect(studentNode).toHaveCount(1);
    await studentNode.locator('.ant-select-tree-checkbox').click();
    await dialog.getByLabel('考核主题', { exact: true }).click(); // Close the tree before teacher selection.
    await dialog.getByLabel('邀请教师', { exact: true }).click(); // Multiple-select search becomes editable only after opening/focus.
    await dialog.getByLabel('邀请教师', { exact: true }).fill(account('INVITED'));
    await page.getByTitle(account('INVITED'), { exact: true }).click();
    await dialog.getByLabel('考核主题', { exact: true }).click();
    const saved = page.waitForResponse(r => r.url().endsWith('/backend/assessment/create') && r.request().method() === 'POST', { timeout: 8000 });
    await dialog.getByRole('button', { name: /^(确定|确 定|OK)$/ }).click();
    const response = await saved;
    expect(response.status()).toBe(200);
    expect((await response.json()).code).toBe(200);
    await expect(dialog).toHaveCount(0);
    const card = page.locator('article.setting-card').filter({ hasText: theme });
    await expect(card).toHaveCount(1);
    await expect(card).toContainText('逐题设置（准备题目）');
    await card.getByRole('button', { name: '准备题目', exact: true }).click();
    await expect(page).toHaveURL(/\/prepared-paper\/[1-9][0-9]*$/);
    assessment = Number(page.url().match(/\/prepared-paper\/([1-9][0-9]*)$/)![1]);
    for (let ordinal = 1; ordinal <= 3; ordinal++) {
      await page.getByRole('button', { name: '新增题目', exact: true }).click();
      const question = page.getByTestId('prepared-question').nth(ordinal - 1);
      await question.getByLabel('题目正文', { exact: true }).fill(`创建分配第 ${ordinal} 题`);
      await question.getByLabel('评分要求（仅教师可见）', { exact: true }).fill(`仅管理教师可见的第 ${ordinal} 题标准`);
      await question.getByLabel('分值', { exact: true }).fill('10');
      await question.getByLabel('回答时限（秒）', { exact: true }).fill(String(60 * ordinal));
    }
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('草稿已保存');
    for (let ordinal = 0; ordinal < 3; ordinal++) {
      const question = page.getByTestId('prepared-question').nth(ordinal);
      await question.getByRole('button', { name: '审核此题', exact: true }).click();
      await expect(question.getByText('已审核', { exact: true })).toBeVisible();
    }
    await page.reload();
    await expect(page.getByTestId('prepared-question')).toHaveCount(3);
    for (let ordinal = 0; ordinal < 3; ordinal++) {
      await expect(page.getByTestId('prepared-question').nth(ordinal).getByLabel('回答时限（秒）', { exact: true })).toHaveValue(String(60 * (ordinal + 1)));
    }
    const publication = await get(page, `/backend/prepared-papers/${assessment}/publications`);
    expect(publication.status()).toBe(200);
    expect((await publication.json()).data.activeVersion).toBeNull();
  } finally { await teacher.close(); }

  expect(assessment).toBeGreaterThan(0);
  for (const actor of ['INVITED', 'UNRELATED', 'ADMIN', 'STUDENT', 'OTHERSTUDENT'] as const) {
    const context = await browser.newContext();
    try {
      const page = await context.newPage();
      const route = actor === 'ADMIN' ? '/adminDashboard' : actor === 'STUDENT' || actor === 'OTHERSTUDENT' ? '/studentDashboard' : '/teacherDashboard';
      await login(page, actor, route);
      if (actor === 'INVITED' || actor === 'UNRELATED') {
        const listed = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/dev-api/backend/assessment/list', { timeout: 8000 });
        await page.getByRole('navigation', { name: '教师端导航' }).getByRole('button', { name: '考核设置', exact: true }).click();
        expect((await listed).status()).toBe(200);
        await expect(page.locator('article.setting-card').filter({ hasText: theme })).toHaveCount(actor === 'INVITED' ? 1 : 0);
        if (actor === 'UNRELATED') await expect(page.getByText('暂无数据', { exact: true })).toBeVisible();
      }
      if (actor === 'STUDENT' || actor === 'OTHERSTUDENT') {
        await page.getByRole('navigation', { name: '学生端导航' }).getByRole('button').filter({ hasText: '我的考核' }).click();
        if (actor === 'STUDENT') {
          await expect(page.getByRole('heading', { name: theme, exact: true })).toBeVisible();
          await page.getByRole('button', { name: /面试预检/ }).first().click();
          await expect(page).toHaveURL(new RegExp(`/prepared-interview/${assessment}/preflight$`));
          await expect(page.getByRole('heading', { name: '面试预检', exact: true })).toBeVisible();
          await expect(page.getByRole('heading', { name: theme, exact: true })).toBeVisible();
          await expect(page.getByRole('button', { name: '开考并开始第一题', exact: true })).toBeDisabled();
          await expect(page.getByTestId('authorized-question')).toHaveCount(0);
        } else {
          await expect(page.getByText('当前没有需要处理的考核任务，新的安排会在这里展示。', { exact: true })).toBeVisible();
          await expect(page.getByRole('heading', { name: theme, exact: true })).toHaveCount(0);
        }
      }
      const draft = await get(page, `/backend/prepared-papers/${assessment}`);
      if (actor === 'INVITED' || actor === 'ADMIN') {
        expect(draft.status()).toBe(200);
        expect((await draft.json()).data.questions).toHaveLength(3);
        const listed = await post(page, '/backend/assessment/list', { page: 1, size: 20, theme });
        expect(listed.status()).toBe(200);
        expect((await listed.json()).data.records.map((row: { id: number }) => row.id)).toEqual([assessment]);
      } else {
        expect(draft.status()).toBe(403);
        const denied = await page.request.put(`/dev-api/backend/prepared-papers/${assessment}`, {
          data: { expectedRevision: 0, questions: [] }, headers, timeout: 8000,
        });
        expect(denied.status()).toBe(403);
        const eligibility = await get(page, `/backend/prepared-interviews/${assessment}/eligibility`);
        expect(eligibility.status()).toBe(actor === 'STUDENT' ? 200 : 403);
        if (actor === 'STUDENT') {
          const data = (await eligibility.json()).data;
          expect(data.phase).toBe('PAPER_NOT_PUBLISHED');
          expect(data.publicationReady).toBe(false);
          for (const key of ['questions', 'rubric', 'audio', 'objectKey', 'paperVersionId']) expect(data).not.toHaveProperty(key);
        }
        if (actor === 'UNRELATED') {
          const listed = await post(page, '/backend/assessment/list', { page: 1, size: 20, theme });
          expect(listed.status()).toBe(200); expect((await listed.json()).data.records).toEqual([]);
        } else {
          const listed = await post(page, '/front/student-assessment/todo-list', { page: 1, size: 20, theme });
          expect(listed.status()).toBe(200);
          expect((await listed.json()).data.records.map((row: { id: number }) => row.id)).toEqual(actor === 'STUDENT' ? [assessment] : []);
          expect((await post(page, '/backend/assessment/list', { page: 1, size: 20, theme })).status()).toBe(403);
        }
      }
      expect((await post(page, '/logout', {})).status()).toBe(200);
    } finally { await context.close(); }
  }
});

async function login(page: Page, actor: Actor, expected: string) {
  await page.goto('/login');
  await page.getByPlaceholder('请输入账号').fill(account(actor));
  await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const logging = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/dev-api/login', { timeout: 8000 });
  await page.getByRole('button', { name: /立即登录/ }).click();
  const response = await logging;
  expect(response.status()).toBe(200);
  expect((await response.json()).code).toBe(200);
  await expect(page).toHaveURL(new RegExp(`${expected}$`));
  const identity = await get(page, '/user-detail');
  expect(identity.status()).toBe(200);
  expect((await identity.json()).data.id).toBe(id(actor));
}
const get = (page: Page, route: string) => page.request.get('/dev-api' + route, { timeout: 8000 });
const post = (page: Page, route: string, data: unknown) => page.request.post('/dev-api' + route, { headers, data, timeout: 8000 });
