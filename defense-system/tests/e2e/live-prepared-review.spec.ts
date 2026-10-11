import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import { test,expect,type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
/** Real cookies/HTTP/DB plus native original receipt; score/storage upstream are explicit
 * doubles. A real received POST response is discarded once to verify persisted intent. */
async function openOriginal(page:Page){
  test.skip(env.LIVE_E2E!=='1','requires scoped independent teacher review fixture');
  const assessment=env.PREPARED_REVIEW_ASSESSMENT!,answer=env.PREPARED_REVIEW_ANSWER!;
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!assertPreparedFixture(env,Number(assessment))||!Number.isSafeInteger(Number(assessment))||!env.PREPARED_REVIEW_ACCOUNT?.startsWith('prepared-publication-')||!env.PREPARED_TEST_LOGIN_PASSWORD||!/^[a-f0-9-]{36}$/.test(answer))throw new Error('Independent teacher fixture required');
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(env.PREPARED_REVIEW_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  // Prove the actual login response before evaluating SPA navigation. A cold password
  // check is not a route failure; keep the navigation assertion's original deadline.
  const [login]=await Promise.all([page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);
  expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);await expect(page).not.toHaveURL(/\/login/);
  await page.goto(`/prepared-review/${assessment}`);await page.getByTestId('review-student').first().click();await expect(page.getByText('原录音已完整提交',{exact:false})).toBeVisible();
  const target=()=>page.locator(`[data-testid="review-answer"][data-answer-id="${answer}"]`);
  await target().click();await expect(page.getByRole('heading',{name:'冻结评分标准'})).toBeVisible();
  await expect(page.getByTestId('review-score')).toContainText('8.5 / 10');
  return {assessment,answer,target};
}
// Playback and persisted grading are distinct business journeys. Neither increases the
// original 30-second scenario budget; no grading request is needed to test playback.
test('teacher original playback preserves grade across transport failure and expiry',async({page})=>{
  const {assessment,answer,target}=await openOriginal(page);
  // Genuine scoped HTTP must fail closed with storage disabled. The following successful
  // signature/transport is explicitly local test substitution, NOT real cloud/physical QA.
  const original=page.locator('section[aria-label="原录音播放"]'),authorize=()=>original.getByRole('button',{name:'获取原录音播放授权',exact:true});
  await authorize().click();await expect(original.getByRole('alert')).toContainText('原录音播放授权暂不可用');await expect(original.locator('audio')).toHaveCount(0);
  const file=env.PREPARED_REVIEW_AUDIO_FILE!,scope=JSON.parse(env.PREPARED_REVIEW_AUDIO_SCOPE!);
  if(!/^\/(?:private\/)?var\/folders\//.test(file)||!file.endsWith(`browser-original-${answer}.wav`)||scope.answerId!==answer||scope.assessmentId!==Number(assessment))throw new Error('Scoped native original fixture required');
  const encoded=readFileSync(file,'base64'),bytes=Uint8Array.from(atob(encoded),c=>c.charCodeAt(0));
  const hash=await page.evaluate(async encoded=>{const content=Uint8Array.from(atob(encoded),c=>c.charCodeAt(0));return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',content)),c=>c.toString(16).padStart(2,'0')).join('');},encoded);
  expect(hash).toBe(scope.sha256);expect(bytes.byteLength).toBe(scope.byteSize);
  let audioRequests=0,failTransport=false,shortExpiry=false;
  await page.route('**/prepared-reviews/**/answers/*/audio',async route=>{audioRequests++;const {objectKey,...publicScope}=scope,issuedAt=Date.now();
    // A single clock read binds both ends of the capability. Two reads can cross a
    // millisecond and exceed the real five-minute limit, which the UI must reject.
    await route.fulfill({status:200,contentType:'application/json',headers:{'Cache-Control':'private, no-store'},body:JSON.stringify({code:200,data:{...publicScope,url:`https://prepared-fixture.oss-cn-beijing.aliyuncs.com/${objectKey}?Signature=explicit-local-fixture-${audioRequests}`,serverTime:new Date(issuedAt).toISOString(),expiresAt:new Date(issuedAt+(shortExpiry?2000:300000)).toISOString()}})});});
  await page.route('https://prepared-fixture.oss-cn-beijing.aliyuncs.com/**',async route=>{expect(route.request().headers()['referer']).toBeUndefined();
    if(failTransport){failTransport=false;await route.abort('failed');}else await route.fulfill({status:200,contentType:'audio/wav',headers:{'Access-Control-Allow-Origin':'http://127.0.0.1:13015','Cache-Control':'private, no-store'},path:file});});
  await authorize().click();await expect(original.locator('audio')).toHaveCount(1);
  await original.locator('audio').evaluate(element=>{void (element as HTMLAudioElement).play().catch(()=>{});});
  await expect.poll(()=>original.locator('audio').evaluate(element=>({time:(element as HTMLAudioElement).currentTime,ready:(element as HTMLAudioElement).readyState,error:(element as HTMLAudioElement).error?.code}))).toMatchObject({ready:4});
  await expect.poll(()=>original.locator('audio').evaluate(element=>(element as HTMLAudioElement).currentTime)).toBeGreaterThan(0);
  await expect.poll(()=>original.locator('audio').evaluate(element=>(element as HTMLAudioElement).ended)).toBe(true);
  expect(await original.locator('audio').evaluate(element=>(element as HTMLAudioElement).duration)).toBeCloseTo(scope.durationMs/1000,1);
  // A real browser media fetch fails once, then a fresh capability recovers the same bytes.
  failTransport=true;await authorize().click();await original.locator('audio').evaluate(element=>{void (element as HTMLAudioElement).play().catch(()=>{/* Expected injected transport failure. */});});
  await expect(original.getByRole('alert')).toContainText('播放中断');await expect(original.locator('audio')).toHaveCount(0);await expect(page.getByTestId('review-score')).toContainText('8.5 / 10');
  shortExpiry=true;await authorize().click();await expect(original.locator('audio')).toHaveCount(1);await expect(original.getByRole('alert')).toContainText('授权已到期');await expect(original.locator('audio')).toHaveCount(0);expect(audioRequests).toBe(3);
  await target().click();await expect(original.locator('audio')).toHaveCount(0);expect(audioRequests).toBe(3);
  await expect(page.getByTestId('review-score')).toContainText('8.5 / 10');
  await page.screenshot({path:`../.runtime/teacher-review-playback-${assessment}.png`,fullPage:true});
});
test('teacher grading preserves original confirm and manual commands across refresh',async({page})=>{
  const {assessment,target}=await openOriginal(page);
  const calls:string[]=[];let discard=true;
  await page.route('**/prepared-reviews/**/confirm',async route=>{calls.push(route.request().postData()!);if(discard){discard=false;await route.fetch();await route.abort('failed');}else await route.continue();});
  await page.getByLabel('核分或重评依据').fill('浏览器恢复原请求核验');await page.getByRole('button',{name:'确认当前评分',exact:true}).click();
  await expect(page.getByRole('button',{name:'恢复原核分请求',exact:true})).toBeEnabled();await page.reload();await page.getByTestId('review-student').first().click();
  // Match the original answer by the actual GET binding rather than assume Worker claim ordinal.
  await target().click();
  await expect(page.getByRole('button',{name:'恢复原核分请求',exact:true})).toBeVisible();expect(calls).toHaveLength(1);
  await page.getByRole('button',{name:'恢复原核分请求',exact:true}).click();await expect(page.getByTestId('review-score')).toContainText('已确认评分');expect(calls).toHaveLength(2);expect(calls[1]).toBe(calls[0]);
  await expect(page.getByRole('button',{name:'恢复原核分请求',exact:true})).toHaveCount(0);
  // Only this answer has a grade. The aggregate's PROCESSING message is a
  // business state, not a stuck loader; require it and forbid a fabricated total.
  const summary=page.getByRole('region',{name:'整场成绩汇总'});
  await expect(page.getByRole('status')).toHaveCount(1);await expect(summary.getByRole('status')).toHaveText('成绩尚未全部就绪；不以旧版整数成绩或部分题目的成绩补成总分。');
  await expect(summary.getByTestId('review-summary-total')).toHaveCount(0);await expect(summary.getByRole('button',{name:'刷新整场成绩',exact:true})).toBeEnabled();await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.getByRole('heading',{name:'冻结评分标准'})).toBeVisible();
  await expect(page.getByTestId('review-score')).toContainText('已确认评分');
  const manualCalls:string[]=[];let discardManual=true;
  // Existing px-to-rem and root font scaling must not make the grade field too small.
  // Assert actual rendered dimensions rather than CSS source values at desktop widths.
  const originalViewport=page.viewportSize()!;
  for(const width of [1280,1024,1920]){
    await page.setViewportSize({width,height:900});
    await expect.poll(async()=> (await page.getByLabel(/^人工分数/).boundingBox())?.height, {message:`manual grade target height at ${width}px`}).toBeGreaterThanOrEqual(40);
    await expect.poll(async()=> (await page.getByLabel(/^人工分数/).boundingBox())?.width, {message:`manual grade target width at ${width}px`}).toBeGreaterThanOrEqual(200);
  }
  await page.setViewportSize(originalViewport);
  await page.route('**/prepared-reviews/**/adjust',async route=>{manualCalls.push(route.request().postData()!);if(discardManual){discardManual=false;const response=await route.fetch();expect(response.status()).toBe(200);await route.abort('failed');}else await route.continue();});
  await page.getByLabel(/^人工分数/).fill('8.75');await page.getByLabel('核分或重评依据').fill('浏览器人工听审后原评分依据');await page.getByRole('button',{name:'保存人工评分',exact:true}).click();
  await expect(page.getByRole('button',{name:'恢复原人工评分请求',exact:true})).toBeVisible();await page.reload();await page.getByTestId('review-student').first().click();await target().click();
  await expect(page.getByTestId('review-score')).toContainText('8.75 / 10');await expect(page.getByTestId('review-score')).toContainText('人工评分');expect(manualCalls).toHaveLength(1);
  await expect(page.getByLabel(/^人工分数/)).toHaveValue('8.75');await page.getByRole('button',{name:'恢复原人工评分请求',exact:true}).click();
  await expect(page.getByRole('button',{name:'恢复原人工评分请求',exact:true})).toHaveCount(0);expect(manualCalls).toHaveLength(2);expect(manualCalls[1]).toBe(manualCalls[0]);
  await page.getByLabel(/^人工分数/).fill('9');await page.getByLabel('核分或重评依据').fill('第二次人工评分，保留旧版本');await page.getByRole('button',{name:'保存人工评分',exact:true}).click();
  await expect(page.getByTestId('review-score')).toContainText('9 / 10');await expect(page.getByRole('button',{name:'确认当前评分',exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'查看人工评分历史',exact:true}).click();await expect(page.getByText(/人工评分 v1/)).toContainText('8.75 / 10');await expect(page.getByText(/人工评分 v2/)).toContainText('9 / 10');
  expect(manualCalls).toHaveLength(3);await expect(page.getByRole('status')).toHaveCount(1);await expect(summary.getByRole('status')).toHaveText('成绩尚未全部就绪；不以旧版整数成绩或部分题目的成绩补成总分。');
  await expect(summary.getByTestId('review-summary-total')).toHaveCount(0);await expect(summary.getByRole('button',{name:'刷新整场成绩',exact:true})).toBeEnabled();await expect(page.getByRole('alert')).toHaveCount(0);
  await page.screenshot({path:`../.runtime/teacher-review-${assessment}.png`,fullPage:true});
});
