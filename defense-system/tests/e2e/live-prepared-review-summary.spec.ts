import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import {test,expect,type Page} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
async function login(page:Page){
  test.skip(env.LIVE_E2E!=='1','requires actual scoped summary fixtures');
  const uuid=(v:string|undefined)=>!!v&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13019'||!Number.isSafeInteger(Number(env.PREPARED_REVIEW_ASSESSMENT))||!assertPreparedFixture(env,Number(env.PREPARED_REVIEW_ASSESSMENT))||!env.PREPARED_REVIEW_ACCOUNT?.startsWith('prepared-publication-')||!env.PREPARED_TEST_LOGIN_PASSWORD||!uuid(env.PREPARED_REVIEW_OLD_ATTEMPT)||!uuid(env.PREPARED_REVIEW_ANSWER))throw new Error('Independent actual summary fixture required');
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(env.PREPARED_REVIEW_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const [response]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);expect(response.status()).toBe(200);expect((await response.json()).code).toBe(200);await expect(page).toHaveURL(url=>url.pathname!=='/login');
  await page.goto(`/prepared-review/${env.PREPARED_REVIEW_ASSESSMENT}`);
}
test('current summary follows actual grading and clears failed reads before recovery',async({page})=>{
  await login(page);const writes:string[]=[];page.on('request',r=>{if(!['GET','HEAD','OPTIONS'].includes(r.method())&&r.url().includes('/prepared-'))writes.push(new URL(r.url()).pathname);});
  await page.getByTestId('review-student').first().click();const summary=page.getByRole('region',{name:'整场成绩汇总'});await expect(summary.getByTestId('review-summary-total')).toContainText('15.75 / 30');await expect(summary).toContainText('第 1 题：0 / 10');
  await page.locator(`[data-testid="review-answer"][data-answer-id="${env.PREPARED_REVIEW_ANSWER}"]`).click();await page.getByLabel(/^人工分数/).fill('1.25');await page.getByLabel('核分或重评依据').fill('整场汇总真实页面人工调整验证');await page.getByRole('button',{name:'保存人工评分',exact:true}).click();await expect(summary.getByTestId('review-summary-total')).toContainText('17 / 30');
  // Only a transport-failure injection, never a fabricated successful API result.
  const route=`**/prepared-reviews/${env.PREPARED_REVIEW_ASSESSMENT}/attempts/${env.PREPARED_REVIEW_OLD_ATTEMPT}/summary`;await page.route(route,r=>r.abort('failed'));await summary.getByRole('button',{name:'刷新整场成绩',exact:true}).click();await expect(summary.getByRole('alert')).toBeVisible();await expect(summary.getByTestId('review-summary-total')).toHaveCount(0);await expect(summary.getByRole('button',{name:'刷新整场成绩',exact:true})).toBeEnabled();
  await page.unroute(route);await summary.getByRole('button',{name:'刷新整场成绩',exact:true}).click();await expect(summary.getByTestId('review-summary-total')).toContainText('17 / 30');
  await page.reload();await page.getByTestId('review-student').first().click();await expect(summary.getByTestId('review-summary-total')).toContainText('17 / 30');expect(writes).toEqual([expect.stringMatching(/\/answers\/[a-f0-9-]+\/adjust$/)]);await expect(summary.getByRole('status')).toHaveCount(0);
});
test('historical summary remains read only and never fills the new attempt with old totals',async({page})=>{
  await login(page);if(!env.PREPARED_REVIEW_ACTIVE_ATTEMPT||env.PREPARED_REVIEW_ACTIVE_ATTEMPT===env.PREPARED_REVIEW_OLD_ATTEMPT)throw new Error('Separate current history anchor fixture required');
  const writes:string[]=[];page.on('request',r=>{if(!['GET','HEAD','OPTIONS'].includes(r.method())&&r.url().includes('/prepared-'))writes.push(new URL(r.url()).pathname);});
  const summary=page.getByRole('region',{name:'整场成绩汇总'}),old=()=>page.locator(`[data-testid="review-attempt"][data-attempt-id="${env.PREPARED_REVIEW_OLD_ATTEMPT}"]`),current=()=>page.locator(`[data-testid="review-attempt"][data-attempt-id="${env.PREPARED_REVIEW_ACTIVE_ATTEMPT}"]`);
  await page.getByTestId('review-attempt-history').first().click();await old().click();await expect(summary).toContainText('历史尝试，只读');await expect(summary.getByTestId('review-summary-total')).toContainText('17 / 30');
  await page.locator(`[data-testid="review-answer"][data-answer-id="${env.PREPARED_REVIEW_ANSWER}"]`).click();await expect(page.getByRole('button',{name:'保存人工评分',exact:true})).toHaveCount(0);await expect(summary.getByTestId('review-summary-total')).toContainText('17 / 30');
  await current().click();await expect(summary).toContainText('原面试仍在进行');await expect(summary.getByTestId('review-summary-total')).toHaveCount(0);await old().click();await expect(summary.getByTestId('review-summary-total')).toContainText('17 / 30');
  await page.reload();await page.getByTestId('review-attempt-history').first().click();await old().click();await expect(summary.getByTestId('review-summary-total')).toContainText('17 / 30');expect(writes).toHaveLength(0);
});
