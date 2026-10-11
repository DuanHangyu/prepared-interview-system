import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import {test,expect} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
test.beforeEach(({page})=>{
  const started=Date.now();
  page.on('requestfinished',request=>{
    const path=new URL(request.url()).pathname,operation=path.endsWith('/login')&&request.method()==='POST'?'login':path.endsWith('/user-detail')?'identity':/\/prepared-results\//.test(path)?'result-read':null;
    if(operation)console.info(JSON.stringify({journey:'own-current-result',stage:'request-finished',operation,elapsedMs:Date.now()-started,durationMs:Math.round(request.timing().responseEnd)}));
  });
  page.on('response',response=>{
    if(/\/prepared-results\//.test(new URL(response.url()).pathname))console.info(JSON.stringify({journey:'own-current-result',stage:'result-response',status:response.status(),elapsedMs:Date.now()-started}));
  });
});
/** Real authenticated read-only result journey. Original media storage upstream is an explicit
 * SDK fixture; no real cloud/model/physical-device or final-QA acceptance is claimed. */
test('own current results survive refresh and offline reads without new answers, model requests or private teacher content',async({page,context})=>{
  test.skip(env.LIVE_E2E!=='1','requires scoped independent fixture');
  const assessment=env.PREPARED_RESULT_ASSESSMENT!;
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13017'||!Number.isSafeInteger(Number(assessment))||!assertPreparedFixture(env,Number(assessment))||!env.PREPARED_RESULT_ACCOUNT?.startsWith('prepared-question-')||!env.PREPARED_TEST_LOGIN_PASSWORD)throw new Error('Scoped independent student result fixture required');
  const writes:string[]=[];page.on('request',r=>{if(r.method()!=='GET'&&/\/backend\/prepared-/.test(r.url()))writes.push(r.method());});
  await page.goto(`/login?redirect=/prepared-results/${assessment}`);await page.getByPlaceholder('请输入账号').fill(env.PREPARED_RESULT_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const [login]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);
  expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);
  // A suffix regexp also matched /login?redirect=/prepared-results/{id}, before
  // login had completed. Check the actual pathname, not a redirect query string.
  await expect(page).toHaveURL(url=>url.pathname===`/prepared-results/${assessment}`&&url.search==='');await expect(page.getByTestId('result-total')).toContainText('15.75 / 30');
  await expect(page.getByTestId('result-answer')).toHaveCount(3);await page.getByRole('button',{name:'查看第 1 题结果',exact:true}).click();
  await expect(page.getByTestId('result-detail')).toContainText('0 / 10');await expect(page.getByTestId('result-detail')).toContainText('暂无有效转写');
  await expect(page.getByTestId('result-detail')).not.toContainText('不应泄漏的教师标准');await expect(page.getByTestId('result-detail')).not.toContainText('教师内部评分理由');
  await page.reload();await expect(page.getByTestId('result-total')).toContainText('15.75 / 30');await expect(page.getByTestId('result-detail')).toHaveCount(0);
  await context.setOffline(true);await page.getByRole('button',{name:'刷新结果',exact:true}).click();await expect(page.getByTestId('result-total')).toHaveCount(0);await expect(page.locator('section[aria-label="预置面试结果"] [role="alert"]')).toBeVisible();
  await context.setOffline(false);await page.getByRole('button',{name:'刷新结果',exact:true}).click();await expect(page.getByTestId('result-total')).toContainText('15.75 / 30');expect(writes).toEqual([]);
});
