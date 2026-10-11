import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import { test,expect } from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
/** Actual teacher login/list/review routes and native original receipt fixture. Upstream
 * media/model are doubles, not cloud/model/device/capacity/final QA. No paid IO. */
test('teacher records distinguish prepared originals from legacy zero and route to actual review',async({page,browser})=>{
  test.skip(env.LIVE_E2E!=='1','requires independent original-record fixture');
  const assessment=Number(env.PREPARED_RECORD_ASSESSMENT),record=Number(env.PREPARED_RECORD_ID),legacy=Number(env.PREPARED_LEGACY_RECORD_ID);
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||![assessment,record,legacy].every(n=>Number.isSafeInteger(n)&&assertPreparedFixture(env,n))||!env.PREPARED_REVIEW_ACCOUNT?.startsWith('prepared-publication-')||!env.PREPARED_TEST_LOGIN_PASSWORD)throw new Error('Independent record fixture required');
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(env.PREPARED_REVIEW_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  // Wait for the actual authentication response, not a five-second assumption about
  // password verification. The route assertion and overall scenario deadline stay unchanged.
  const [login]=await Promise.all([page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);
  expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);await expect(page).not.toHaveURL(/\/login/);
  await page.getByRole('button',{name:'考核记录',exact:true}).click();
  const preparedRow=page.locator(`tr[data-row-key="${record}"]`),legacyRow=page.locator(`tr[data-row-key="${legacy}"]`);
  await expect(preparedRow).toContainText('待评阅');await expect(preparedRow.getByText('在预置评阅页查看',{exact:true})).toHaveCount(2);
  await expect(preparedRow.getByRole('button',{name:'查看详情',exact:true})).toHaveCount(0);await expect(preparedRow.getByRole('button',{name:/重\s*考/})).toHaveCount(0);await expect(preparedRow.getByRole('checkbox')).toBeDisabled();
  await expect(legacyRow.getByRole('button',{name:'查看详情',exact:true})).toBeVisible();await expect(legacyRow.getByRole('checkbox')).toBeEnabled();await expect(legacyRow).toContainText('0分');
  const action=preparedRow.getByRole('button',{name:'预置面试评阅',exact:true});await expect(action).toBeInViewport();
  await page.screenshot({path:`../.runtime/prepared-records-${assessment}.png`,fullPage:true});
  await action.click();await expect(page).toHaveURL(new RegExp(`/prepared-review/${assessment}$`));
  await page.getByTestId('review-student').first().click();await expect(page.getByText('原录音已完整提交',{exact:false})).toBeVisible();await page.getByTestId('review-answer').first().click();await expect(page.getByText('尚无有效评分。后台未完成或需要教师处理，不代表零分。',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'确认当前评分',exact:true})).toBeDisabled();
  await page.goto(`/studyDetail?id=${record}`);const error=page.getByTestId('legacy-report-error');await expect(error).toContainText('预置考核不能使用旧答辩流程');await expect(page.getByText('暂无报告内容',{exact:true})).toHaveCount(0);await expect(page.locator('.detail-score-pill')).toHaveCount(0);await page.getByRole('button',{name:'重新读取报告',exact:true}).click();await expect(error).toContainText('预置考核不能使用旧答辩流程');
  if(!env.PREPARED_RECORD_STUDENT_ACCOUNT?.startsWith('prepared-question-'))throw new Error('Independent student fixture required');
  const context=await browser.newContext();try{const student=await context.newPage();await student.goto(env.E2E_BASE_URL+'/login');await student.getByPlaceholder('请输入账号').fill(env.PREPARED_RECORD_STUDENT_ACCOUNT);await student.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
    const [studentLogin]=await Promise.all([student.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname.endsWith('/login')),student.getByRole('button',{name:/立即登录/}).click()]);
    expect(studentLogin.status()).toBe(200);expect((await studentLogin.json()).code).toBe(200);await expect(student).not.toHaveURL(/\/login/);await student.goto(env.E2E_BASE_URL+`/studyDetail?id=${assessment}`);await expect(student.getByTestId('legacy-report-error')).toContainText('预置考核不能使用旧答辩流程');await expect(student.getByText('教师设置不允许查看',{exact:true})).toHaveCount(0);await expect(student.locator('.detail-score-pill')).toHaveCount(0);await student.screenshot({path:`../.runtime/prepared-student-report-boundary-${assessment}.png`,fullPage:true});}finally{await context.close();}
});
