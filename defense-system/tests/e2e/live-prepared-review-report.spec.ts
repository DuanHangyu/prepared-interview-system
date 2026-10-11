import {assertPreparedFixture} from '../../../scripts/assert-owned-browser-fixture.mjs';
import {test,expect} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
/** Actual manager login, original DB report and XLSX bytes; no fabricated success.
 * Media, grading and historical pointer fixtures remain synthetic, not final QA. */
for(const mode of ['current','historical'])test(`${mode} manager report repeats download, refresh and failed/late reads safely`,async({page,context})=>{
  const assessment=Number(env.PREPARED_REVIEW_ASSESSMENT),attempt=env.PREPARED_REVIEW_OLD_ATTEMPT,downloadPath=env.PREPARED_REVIEW_REPORT_DOWNLOAD;
  if(env.LIVE_E2E!=='1'||env.E2E_BASE_URL!=='http://127.0.0.1:13019'||!Number.isSafeInteger(assessment)||!assertPreparedFixture(env,assessment)
    ||!env.PREPARED_REVIEW_ACCOUNT?.startsWith('prepared-publication-')||!env.PREPARED_TEST_LOGIN_PASSWORD||!attempt?.match(/^[a-f0-9-]{36}$/)
    ||!downloadPath?.startsWith('/Users/duanhangyu/Code/prepared-interview-system/.runtime/review-report-browser-')||!downloadPath.endsWith('/report.xlsx'))throw Error('Private owned actual teacher report fixture required');
  test.setTimeout(60000);const writes:string[]=[];let downloads=0,release:(()=>void)|undefined;
  const base=`**/prepared-reviews/${assessment}/attempts/${attempt}`,reportRoute=base+'/report?*',exportRoute=base+'/export.xlsx?*';
  const summary=page.getByRole('region',{name:'整场成绩汇总'}),report=summary.getByTestId('review-report');
  page.on('download',()=>downloads++);
  const select=async()=>{
    if(mode==='current')await page.getByTestId('review-student').first().click();
    else{if(!env.PREPARED_REVIEW_ACTIVE_ATTEMPT||env.PREPARED_REVIEW_ACTIVE_ATTEMPT===attempt)throw Error('Separate history pointer required');await page.getByTestId('review-attempt-history').first().click();await page.locator(`[data-testid="review-attempt"][data-attempt-id="${attempt}"]`).click();}
    await expect(summary.getByTestId('review-summary-total')).toContainText('15.75 / 30');await expect(summary).toContainText(mode==='current'?'当前尝试':'历史尝试，只读');await expect(summary).toContainText('尚未开放');
  };
  const read=async()=>{await summary.getByRole('button',{name:'查看完整报告',exact:true}).click();await expect(report.locator('article')).toHaveCount(3);await expect(report).toContainText('暂无有效转写');await expect(report).not.toContainText('不应泄漏的教师标准');await expect(report).not.toContainText('真实 HTTP 人工核验测试依据');};
  try{
    await page.goto(`/login?redirect=/prepared-review/${assessment}`);await page.getByPlaceholder('请输入账号').fill(env.PREPARED_REVIEW_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
    const login=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login'));await page.getByRole('button',{name:/立即登录/}).click();expect((await login).status()).toBe(200);await expect(page).toHaveURL(u=>u.pathname===`/prepared-review/${assessment}`);
    page.on('request',r=>{if(!['GET','HEAD','OPTIONS'].includes(r.method())&&r.url().includes('/backend/prepared-'))writes.push(new URL(r.url()).pathname);});
    await select();await read();const saved=page.waitForEvent('download');await summary.getByRole('button',{name:'导出原尝试报告',exact:true}).click();const file=await saved;
    expect(file.suggestedFilename()).toBe(`prepared-review-${assessment}-${attempt}.xlsx`);expect(await file.failure()).toBeNull();await file.saveAs(downloadPath);await expect(summary.getByRole('button',{name:'导出原尝试报告',exact:true})).toBeEnabled();
    await page.reload();await select();await expect(report).toHaveCount(0);await read();
    await context.setOffline(true);await summary.getByRole('button',{name:'刷新整场成绩',exact:true}).click();await expect(report).toHaveCount(0);await expect(summary.getByTestId('review-summary-total')).toHaveCount(0);await expect(summary.getByRole('alert')).toBeVisible();
    await context.setOffline(false);await summary.getByRole('button',{name:'刷新整场成绩',exact:true}).click();await expect(summary.getByTestId('review-summary-total')).toContainText('15.75 / 30');await read();
    await page.route(reportRoute,r=>r.abort('failed'));await summary.getByRole('button',{name:'查看完整报告',exact:true}).click();await expect(report).toHaveCount(0);await expect(summary.getByRole('alert')).toBeVisible();await page.unroute(reportRoute);await read();
    await page.route(exportRoute,r=>r.abort('failed'));await summary.getByRole('button',{name:'导出原尝试报告',exact:true}).click();await expect(report).toHaveCount(0);await expect(summary.getByRole('alert')).toBeVisible();await page.unroute(exportRoute);
    let received=false,finished=false;const held=new Promise<void>(r=>{release=r;});
    await page.route(reportRoute,async route=>{const response=await route.fetch({timeout:10000});expect(response.status()).toBe(200);received=true;await held;
      try{await route.fulfill({response});}catch(error){if(!/closed|cancelled|canceled|intercept/i.test(String(error)))throw error;}finally{finished=true;}});
    await summary.getByRole('button',{name:'查看完整报告',exact:true}).click();await expect.poll(()=>received).toBe(true);await summary.getByRole('button',{name:'刷新整场成绩',exact:true}).click();await expect(summary.getByTestId('review-summary-total')).toContainText('15.75 / 30');release?.();await expect.poll(()=>finished).toBe(true);await expect(report).toHaveCount(0);
    await page.unroute(reportRoute);await read();expect(downloads).toBe(1);expect(writes).toEqual([]);
    if(mode==='historical'){await page.locator(`[data-testid="review-attempt"][data-attempt-id="${env.PREPARED_REVIEW_ACTIVE_ATTEMPT}"]`).click();await expect(report).toHaveCount(0);await expect(summary.getByTestId('review-summary-total')).toHaveCount(0);await expect(summary.getByRole('button',{name:'导出原尝试报告',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'保存人工评分',exact:true})).toHaveCount(0);}
  }finally{release?.();await context.setOffline(false);await page.unrouteAll({behavior:'wait'});}
});
