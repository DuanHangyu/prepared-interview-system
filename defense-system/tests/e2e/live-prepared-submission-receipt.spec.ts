import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import {test,expect} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
test.beforeEach(({page})=>{
  const started=Date.now();
  const operation=(url:string)=>{const path=new URL(url).pathname;return path.endsWith('/login')?'login':path.endsWith('/user-detail')?'identity':/\/prepared-interviews\//.test(path)?'original-read':null;};
  page.on('request',r=>{const name=operation(r.url());if(name)console.info(JSON.stringify({journey:'submission-receipt',stage:'request-started',operation:name,method:r.method(),elapsedMs:Date.now()-started}));});
  page.on('response',r=>{const name=operation(r.url());if(name)console.info(JSON.stringify({journey:'submission-receipt',stage:'response',operation:name,method:r.request().method(),status:r.status(),elapsedMs:Date.now()-started}));});
  page.on('requestfinished',r=>{const name=operation(r.url());if(name)console.info(JSON.stringify({journey:'submission-receipt',stage:'request-finished',operation:name,method:r.method(),elapsedMs:Date.now()-started,durationMs:Math.round(r.timing().responseEnd)}));});
  page.on('requestfailed',r=>{const name=operation(r.url());if(name)console.info(JSON.stringify({journey:'submission-receipt',stage:'request-failed',operation:name,method:r.method(),elapsedMs:Date.now()-started}));});
  // Deliberately no URL, request/response body, account, password or console text.
  page.on('pageerror',()=>console.info(JSON.stringify({journey:'submission-receipt',stage:'page-error',elapsedMs:Date.now()-started})));
});
/** Real session/MySQL final receipt, with native codec + explicitly labelled private SDK
 * fixture upstream. This verifies receipt recovery, NOT actual cloud/physical recording QA. */
test('final receipt: real read-only recovery and refresh never require new local keys or model completion',async({page})=>{
  test.skip(env.LIVE_E2E!=='1','requires explicitly scoped local integration fixture');
  const assessment=env.PREPARED_SUBMISSION_ASSESSMENT!,receipt=env.PREPARED_SUBMISSION_RECEIPT!;
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!Number.isSafeInteger(Number(assessment))||!assertPreparedFixture(env,Number(assessment))||!env.PREPARED_SUBMISSION_ACCOUNT?.startsWith('prepared-question-')||!env.PREPARED_TEST_LOGIN_PASSWORD||!/^[a-f0-9-]{36}$/.test(receipt))throw new Error('Scoped independent receipt fixture required');
  const requests:string[]=[];page.on('request',r=>{if(r.method()!=='GET'&&r.url().includes('/prepared-interviews/'))requests.push(r.url());});
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(env.PREPARED_SUBMISSION_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const [login]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);
  expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);
  await expect(page).not.toHaveURL(/\/login/);await page.goto(`/prepared-interview/${assessment}/preflight`);
  await expect(page.getByTestId('submission-status')).toContainText('整场已可靠提交');await expect(page.getByTestId('submission-status')).toContainText(receipt);
  await expect(page.getByRole('button',{name:'确认提交整场面试',exact:true})).toHaveCount(0);expect(requests).toEqual([]);
  await page.reload();await expect(page.getByTestId('submission-status')).toContainText(receipt);expect(requests).toEqual([]);
  await page.screenshot({path:`../.runtime/submission-receipt-${assessment}.png`,fullPage:true});
});
