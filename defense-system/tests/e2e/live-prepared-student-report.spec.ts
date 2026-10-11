import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import {test,expect,type Route} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
/** Real login/result/DB/download bytes. Original media and grading fixtures are synthetic,
 * not actual cloud/model/physical-device or Exhaustive final-QA acceptance. */
test('native own report download is version-bound and survives refresh and offline query recovery',async({page,context})=>{
  const assessment=Number(env.PREPARED_REPORT_ASSESSMENT),port=Number(env.PREPARED_REPORT_HTTP_PORT),downloadPath=env.PREPARED_REPORT_DOWNLOAD;
  if(env.LIVE_E2E!=='1'||env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!Number.isSafeInteger(assessment)||!assertPreparedFixture(env,assessment)||!Number.isSafeInteger(port)||port<1024
    ||[13014,13016].includes(port)||!env.PREPARED_REPORT_ACCOUNT?.startsWith('prepared-question-')||!env.PREPARED_TEST_LOGIN_PASSWORD
    ||!downloadPath?.startsWith('/Users/duanhangyu/Code/prepared-interview-system/.runtime/student-report-browser-')||!downloadPath.endsWith('/report.xlsx'))throw new Error('Private independent real report fixture required');
  test.setTimeout(60000);let pending=0,offline=false;const began=Date.now(),writes:string[]=[];
  const forward=async(route:Route)=>{
    // route.fetch uses the runner's API transport, not the offline browser transport.
    // Stop the scoped proxy while offline instead of accidentally fulfilling a real response.
    if(offline){await route.abort('internetdisconnected');return;}
    const target=new URL(route.request().url());target.host=`127.0.0.1:${port}`;target.pathname=target.pathname.replace(/^\/dev-api/,'');pending++;
    try{const response=await route.fetch({url:target.toString(),timeout:10000});await route.fulfill({response});}
    finally{pending--;}
  };
  await page.route('**/dev-api/login',forward);await page.route('**/dev-api/user-detail',forward);
  await page.route(new RegExp(`^http://127\\.0\\.0\\.1:13015/dev-api/backend/prepared-results/${assessment}(?:/|\\?|$)`),forward);
  page.on('request',r=>{if(r.method()!=='GET'&&/\/backend\/prepared-/.test(r.url()))writes.push(r.method());});
  const stage=(label:string)=>console.info(JSON.stringify({journey:'own-complete-report',stage:label,elapsedMs:Date.now()-began,pending}));
  try {
    await page.goto(`/login?redirect=/prepared-results/${assessment}`);await page.getByPlaceholder('请输入账号').fill(env.PREPARED_REPORT_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
    const login=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login'));await page.getByRole('button',{name:/立即登录/}).click();expect((await login).status()).toBe(200);
    await expect(page).toHaveURL(u=>u.pathname===`/prepared-results/${assessment}`&&u.search==='');stage('authenticated');
    await expect(page.getByTestId('result-total')).toContainText('15.75 / 30');await page.getByRole('button',{name:'查看完整报告',exact:true}).click();
    await expect(page.getByTestId('result-report-answer')).toHaveCount(3);await expect(page.getByTestId('result-report')).toContainText('暂无有效转写');
    await expect(page.getByTestId('result-report')).not.toContainText('教师内部评分理由');await expect(page.getByTestId('result-report')).not.toContainText('不应泄漏的教师标准');stage('complete-report');
    const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'导出本人报告',exact:true}).click();const file=await downloaded;
    expect(file.suggestedFilename()).toMatch(new RegExp(`^prepared-result-${assessment}-[a-f0-9-]{36}\\.xlsx$`));expect(await file.failure()).toBeNull();await file.saveAs(downloadPath);stage('download-complete');
    await expect.poll(()=>pending).toBe(0);await page.reload();await expect(page.getByTestId('result-total')).toContainText('15.75 / 30');await expect(page.getByTestId('result-report')).toHaveCount(0);
    await page.getByRole('button',{name:'查看完整报告',exact:true}).click();await expect(page.getByTestId('result-report-answer')).toHaveCount(3);
    await expect.poll(()=>pending).toBe(0);offline=true;await context.setOffline(true);await page.getByRole('button',{name:'刷新结果',exact:true}).click();
    await expect(page.getByTestId('result-report')).toHaveCount(0);await expect(page.getByTestId('result-total')).toHaveCount(0);await expect(page.locator('section[aria-label="预置面试结果"] [role="alert"]')).toBeVisible();
    offline=false;await context.setOffline(false);await page.getByRole('button',{name:'刷新结果',exact:true}).click();await expect(page.getByTestId('result-total')).toContainText('15.75 / 30');
    await page.getByRole('button',{name:'查看完整报告',exact:true}).click();await expect(page.getByTestId('result-report-answer')).toHaveCount(3);expect(writes).toEqual([]);stage('restored');
  }finally{offline=false;await context.setOffline(false);await page.unrouteAll({behavior:'wait'});}
});
