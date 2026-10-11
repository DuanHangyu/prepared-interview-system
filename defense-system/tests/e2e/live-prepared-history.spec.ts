import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import { test,expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
let readTimings:number[]=[];
test.beforeEach(({page})=>{
  readTimings=[];
  page.on('requestfinished',request=>{
    const pathname=new URL(request.url()).pathname;
    if(request.method()!=='GET'||!pathname.includes('/backend/prepared-reviews/')||pathname.endsWith('/audio'))return;
    const timing=request.timing(),elapsed=timing.responseEnd;
    if(Number.isFinite(elapsed)&&elapsed>=0)readTimings.push(Math.round(elapsed));
  });
});
test.afterEach(({},info)=>{
  const sorted=[...readTimings].sort((a,b)=>a-b);
  console.info(JSON.stringify({journey:'historical-original-review',stage:'read-network-timings',status:info.status,
    count:sorted.length,p95Ms:sorted.length?sorted[Math.ceil(sorted.length*.95)-1]:null,maxMs:sorted.at(-1)??null}));
});
/** Actual cookie/HTTP/DB history. New anchors are explicit fixtures, NOT retake QA;
 * native original playback uses an explicitly substituted private-cloud capability. */
test('teacher historical original stays read-only across bounded pages, playback and refresh',async({page})=>{
  test.skip(env.LIVE_E2E!=='1','requires independent historical review fixture');
  const started=Date.now(),checkpoint=(stage:string)=>console.info(JSON.stringify({journey:'historical-original-review',stage,elapsedMs:Date.now()-started}));
  // Timings contain only stage names and local durations, never credentials, URLs or
  // result content. Keep the original deadline so a slow journey still fails visibly.
  const assessment=env.PREPARED_REVIEW_ASSESSMENT!,answer=env.PREPARED_REVIEW_ANSWER!,old=env.PREPARED_REVIEW_OLD_ATTEMPT!,active=env.PREPARED_REVIEW_ACTIVE_ATTEMPT!;
  const uuid=(v:string)=>/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!Number.isSafeInteger(Number(assessment))||!assertPreparedFixture(env,Number(assessment))||!env.PREPARED_REVIEW_ACCOUNT?.startsWith('prepared-publication-')||!env.PREPARED_TEST_LOGIN_PASSWORD||![answer,old,active].every(uuid))throw new Error('Scoped independent history fixture required');
  const writes:string[]=[];page.on('request',request=>{if(request.method()==='POST'&&request.url().includes('/prepared-reviews/'))writes.push(request.url());});
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(env.PREPARED_REVIEW_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const [login]=await Promise.all([page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);
  expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);await expect(page).not.toHaveURL(/\/login/);checkpoint('logged-in');
  await page.goto(`/prepared-review/${assessment}`);
  const openHistory=async()=>{await page.getByTestId('review-attempt-history').first().click();await expect(page.getByTestId('review-attempt')).toHaveCount(10);};
  const current=()=>page.locator(`[data-testid="review-attempt"][data-attempt-id="${active}"]`);
  const originalAttempt=()=>page.locator(`[data-testid="review-attempt"][data-attempt-id="${old}"]`);
  const target=()=>page.locator(`[data-testid="review-answer"][data-answer-id="${answer}"]`);
  await openHistory();checkpoint('first-history-page');await current().click();await expect(page.getByRole('heading',{name:/第 12 次 · 当前尝试/})).toBeVisible();await expect(page.getByText('原录音尚未完整提交',{exact:false})).toBeVisible();
  await page.getByRole('button',{name:'更多尝试历史',exact:true}).click();await expect(page.getByTestId('review-attempt')).toHaveCount(12);await expect(page.getByRole('heading',{name:/第 12 次 · 当前尝试/})).toBeVisible();
  const readOriginal=async()=>{await originalAttempt().click();await expect(page.getByRole('heading',{name:/第 1 次 · 历史尝试，只读/})).toBeVisible();await target().click();await expect(page.getByRole('heading',{name:'冻结评分标准'})).toBeVisible();
    await expect(page.getByTestId('review-score')).toContainText('8.75 / 10');await expect(page.getByTestId('review-score')).toContainText('人工评分');
    for(const name of ['保存人工评分','确认当前评分','请求重评','恢复原人工评分请求'])await expect(page.getByRole('button',{name,exact:true})).toHaveCount(0);
    await expect(page.getByLabel(/^人工分数/)).toHaveCount(0);await page.getByRole('button',{name:'查看人工评分历史',exact:true}).click();await expect(page.getByText(/人工评分 v1/)).toContainText('8.75 / 10');};
  await readOriginal();checkpoint('historical-answer-verified');
  const original=page.locator('section[aria-label="原录音播放"]'),authorize=()=>original.getByRole('button',{name:'获取原录音播放授权',exact:true});
  await authorize().click();await expect(original.getByRole('alert')).toContainText('原录音播放授权暂不可用');await expect(original.locator('audio')).toHaveCount(0);
  const file=env.PREPARED_REVIEW_AUDIO_FILE!,scope=JSON.parse(env.PREPARED_REVIEW_AUDIO_SCOPE!);
  if(!/^\/(?:private\/)?var\/folders\//.test(file)||!file.endsWith(`browser-original-${answer}.wav`)||scope.attemptId!==old||scope.answerId!==answer||scope.assessmentId!==Number(assessment))throw new Error('Original historical native media required');
  const encoded=readFileSync(file,'base64');const hash=await page.evaluate(async encoded=>{const bytes=Uint8Array.from(atob(encoded),c=>c.charCodeAt(0));return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),c=>c.toString(16).padStart(2,'0')).join('');},encoded);expect(hash).toBe(scope.sha256);
  let requests=0;
  await page.route(`**/prepared-reviews/${assessment}/attempts/${old}/answers/${answer}/audio`,async route=>{requests++;const {objectKey,...publicScope}=scope,issuedAt=Date.now();
    await route.fulfill({status:200,contentType:'application/json',headers:{'Cache-Control':'private, no-store'},body:JSON.stringify({code:200,data:{...publicScope,url:`https://prepared-fixture.oss-cn-beijing.aliyuncs.com/${objectKey}?Signature=explicit-history-${requests}`,serverTime:new Date(issuedAt).toISOString(),expiresAt:new Date(issuedAt+300000).toISOString()}})});});
  await page.route('https://prepared-fixture.oss-cn-beijing.aliyuncs.com/**',async route=>{expect(route.request().headers()['referer']).toBeUndefined();await route.fulfill({status:200,contentType:'audio/wav',headers:{'Access-Control-Allow-Origin':'http://127.0.0.1:13015','Cache-Control':'private, no-store'},path:file});});
  await authorize().click();
  const played=await original.locator('audio').evaluate(async element=>{
    const audio=element as HTMLAudioElement;let errorName:string|null=null;
    try{await audio.play();}catch(error){errorName=error instanceof Error?error.name:'UnknownPlaybackError';}
    return {errorName,readyState:audio.readyState,networkState:audio.networkState,mediaErrorCode:audio.error?.code??null,duration:audio.duration,currentTime:audio.currentTime,paused:audio.paused};
  });
  console.info(JSON.stringify({journey:'historical-original-review',stage:'native-play-start',...played}));expect(played.errorName).toBeNull();expect(played.mediaErrorCode).toBeNull();
  await expect.poll(()=>original.locator('audio').evaluate(element=>(element as HTMLAudioElement).ended)).toBe(true);
  expect(await original.locator('audio').evaluate(element=>(element as HTMLAudioElement).duration)).toBeCloseTo(scope.durationMs/1000,1);checkpoint('native-original-played');
  await current().click();await expect(page.locator('audio')).toHaveCount(0);expect(requests).toBe(1);await readOriginal();expect(requests).toBe(1);
  await page.reload();checkpoint('reloaded');await openHistory();checkpoint('reloaded-history-page');await page.getByRole('button',{name:'更多尝试历史',exact:true}).click();await readOriginal();checkpoint('reloaded-answer-verified');
  // The historical fixture has one scored answer, not a complete whole result.
  // Keep its honest PROCESSING state while excluding every additional loader.
  const summary=page.getByRole('region',{name:'整场成绩汇总'});
  expect(writes).toHaveLength(0);await expect(page.getByRole('status')).toHaveCount(1);await expect(summary.getByRole('status')).toHaveText('成绩尚未全部就绪；不以旧版整数成绩或部分题目的成绩补成总分。');
  await expect(summary.getByTestId('review-summary-total')).toHaveCount(0);await expect(summary.getByRole('button',{name:'刷新整场成绩',exact:true})).toBeEnabled();await expect(page.getByRole('alert')).toHaveCount(0);
  await page.screenshot({path:`../.runtime/historical-review-${assessment}.png`,fullPage:true});
});
