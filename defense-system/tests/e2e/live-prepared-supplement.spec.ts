import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import { test,expect } from '@playwright/test';
import { localDateTimeInput } from '../helpers/local-date-time-input';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
/** Actual login/cookies/HTTP/MySQL. Only network loss is injected; no successful
 * approval, receipt, storage or model response is mocked in this browser test. */
test('teacher original supplement survives offline, accepted response loss, refresh and later approval',async({page})=>{
  test.skip(env.LIVE_E2E!=='1','requires independent scoped approval fixture');
  const assessment=Number(env.PREPARED_SUPPLEMENT_ASSESSMENT),answer=env.PREPARED_SUPPLEMENT_ANSWER!,student=Number(env.PREPARED_SUPPLEMENT_STUDENT),attempt=env.PREPARED_SUPPLEMENT_ATTEMPT!;
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13017'||!Number.isSafeInteger(assessment)||!assertPreparedFixture(env,assessment)||!Number.isSafeInteger(student)||student<1||!env.PREPARED_SUPPLEMENT_ACCOUNT?.startsWith('prepared-publication-')||!env.PREPARED_TEST_LOGIN_PASSWORD||!/^[a-f0-9-]{36}$/.test(answer)||!/^[a-f0-9-]{36}$/.test(attempt))throw new Error('Independent supplement fixture required');
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(env.PREPARED_SUPPLEMENT_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const [login]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);
  expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);await expect(page).not.toHaveURL(/\/login/);
  const path=`/dev-api/backend/prepared-reviews/${assessment}/students/${student}/attempts/${attempt}/answers/${answer}/supplement`;
  const open=async()=>{await page.getByTestId('review-student').first().click();const [status]=await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname===path&&r.request().method()==='GET'),page.locator(`[data-testid="review-supplement"][data-answer-id="${answer}"]`).click()]);expect(status.status()).toBe(200);return (await status.json()).data;};
  await page.goto(`/prepared-review/${assessment}`);const before=await open();const panel=page.getByRole('region',{name:'原回答补传审批'});
  await expect(panel.getByText('已超期，等待审批',{exact:false})).toBeVisible();await expect(panel.getByRole('button',{name:'批准限时补传',exact:true})).toBeEnabled();
  // Deliberately exercise the zero-second boundary on every real-browser round.
  const deadlineMs=Math.floor(Date.parse(before.serverNow)/60000)*60000+3600000;
  const deadline=localDateTimeInput(deadlineMs);
  await panel.getByLabel('批准补传截止（本地时间）').fill(deadline);await panel.getByLabel('补传审批原因').fill('真实浏览器原请求恢复，不重录、不加时');
  const calls:string[]=[];page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname===path)calls.push(r.postData()!);});
  await page.context().setOffline(true);await panel.getByRole('button',{name:'批准限时补传',exact:true}).click();
  await expect(panel.getByRole('alert')).toBeVisible();await expect(panel.getByRole('button',{name:'恢复原补传审批请求',exact:true})).toBeEnabled();expect(calls).toHaveLength(1);
  await page.context().setOffline(false);
  const stillUnaccepted=await page.request.get(path);expect(stillUnaccepted.status()).toBe(200);expect((await stillUnaccepted.json()).data.latest).toBeNull();
  let discard=true,committed:Record<string,unknown>|undefined;
  await page.route(`**${path}`,async route=>{if(route.request().method()!=='POST'||!discard){await route.continue();return;}discard=false;const response=await route.fetch();expect(response.status()).toBe(200);const body=await response.json();expect(body.code).toBe(200);committed=body.data;await route.abort('failed');});
  await panel.getByRole('button',{name:'恢复原补传审批请求',exact:true}).click();await expect(panel.getByRole('alert')).toBeVisible();await expect(panel.getByRole('button',{name:'恢复原补传审批请求',exact:true})).toBeEnabled();expect(calls).toHaveLength(2);expect(calls[1]).toBe(calls[0]);expect(committed?.approvalRevision).toBe(1);
  // Another genuine decision must not be mistaken for acceptance of this pending command.
  const state=await page.request.get(path);expect(state.status()).toBe(200);const current=(await state.json()).data;
  const later=await page.request.post(path,{headers:{Origin:env.E2E_BASE_URL!},data:{idempotencyKey:await page.evaluate(()=>crypto.randomUUID()),expectedRevision:current.approvalRevision,expectedAttemptRevision:current.attemptRevision,manifestSha256:current.manifestSha256,uploadDeadline:new Date(Date.parse(current.serverNow)+7200000).toISOString(),reason:'后续实际审批仍保留原请求历史'}});
  expect(later.status()).toBe(200);expect((await later.json()).data.approvalRevision).toBe(2);
  await page.reload();const latest=await open();expect(latest.approvalRevision).toBe(2);await expect(panel.getByRole('button',{name:'恢复原补传审批请求',exact:true})).toBeEnabled();expect(calls).toHaveLength(2);
  await expect(panel.getByLabel('补传审批原因')).toHaveValue('真实浏览器原请求恢复，不重录、不加时');await expect(panel.getByLabel('补传审批原因')).toBeDisabled();
  await panel.getByRole('button',{name:'恢复原补传审批请求',exact:true}).click();await expect(panel.getByText('补传已批准，原时限和回答保留；不表示录音已保存或已提交',{exact:true})).toBeVisible();
  await expect(panel.getByRole('button',{name:'恢复原补传审批请求',exact:true})).toHaveCount(0);expect(calls).toHaveLength(3);expect(calls[2]).toBe(calls[0]);
  const after=await page.request.get(path);expect(after.status()).toBe(200);const verified=(await after.json()).data;expect(verified.approvalRevision).toBe(2);expect(verified.answerDeadline).toBe(before.answerDeadline);expect(verified.automaticDeadline).toBe(before.automaticDeadline);expect(verified.manifestSha256).toBe(before.manifestSha256);expect(verified.answerState).toBe('UPLOAD_PENDING');
  for(const width of [1024,1280,1920]){await page.setViewportSize({width,height:900});await expect.poll(async()=> (await panel.getByLabel('批准补传截止（本地时间）').boundingBox())?.height).toBeGreaterThanOrEqual(40);await expect.poll(async()=> (await panel.getByLabel('补传审批原因').boundingBox())?.width).toBeGreaterThanOrEqual(240);}
  await page.setViewportSize({width:1280,height:900});await page.screenshot({path:`../.runtime/teacher-supplement-${assessment}.png`,fullPage:true});
});
test('closed primary environment refuses actual approval and keeps original across refresh',async({page})=>{
  test.skip(env.LIVE_E2E!=='1','requires scoped independent approval fixture');
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13017'||!env.PREPARED_SUPPLEMENT_ACCOUNT?.startsWith('prepared-publication-'))throw new Error('Independent supplement fixture required');
  const assessment=Number(env.PREPARED_SUPPLEMENT_ASSESSMENT),answer=env.PREPARED_SUPPLEMENT_ANSWER!,student=Number(env.PREPARED_SUPPLEMENT_STUDENT),attempt=env.PREPARED_SUPPLEMENT_ATTEMPT!;
  await page.goto('http://127.0.0.1:13015/login');await page.getByPlaceholder('请输入账号').fill(env.PREPARED_SUPPLEMENT_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const [login]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);await expect(page).not.toHaveURL(/\/login/);
  const path=`/dev-api/backend/prepared-reviews/${assessment}/students/${student}/attempts/${attempt}/answers/${answer}/supplement`;
  const open=async()=>{await page.getByTestId('review-student').first().click();const [status]=await Promise.all([page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname===path),page.locator(`[data-testid="review-supplement"][data-answer-id="${answer}"]`).click()]);expect(status.status()).toBe(200);return (await status.json()).data;};
  await page.goto(`http://127.0.0.1:13015/prepared-review/${assessment}`);const before=await open(),panel=page.getByRole('region',{name:'原回答补传审批'});
  await expect(panel.getByRole('button',{name:'批准限时补传',exact:true})).toBeEnabled();
  // The closed-environment rejection is independent of whether a prior test approved.
  const latestMs=before.latest?Date.parse(before.latest.uploadDeadline):Date.parse(before.serverNow);
  const future=localDateTimeInput(Math.floor(Math.max(latestMs,Date.parse(before.serverNow))/1000)*1000+3600000);
  await panel.getByLabel('批准补传截止（本地时间）').fill(future);await panel.getByLabel('补传审批原因').fill('关闭环境只验证拒绝，不产生审批');
  const [rejected]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===path),panel.getByRole('button',{name:'批准限时补传',exact:true}).click()]);expect(rejected.status()).toBe(503);
  await expect(panel.getByRole('alert')).toContainText('正式作答流程尚未开放');await expect(panel.getByRole('button',{name:'恢复原补传审批请求',exact:true})).toBeEnabled();const original=rejected.request().postData();
  await page.reload();const refreshed=await open();expect(refreshed.approvalRevision).toBe(before.approvalRevision);await expect(panel.getByRole('button',{name:'恢复原补传审批请求',exact:true})).toBeEnabled();
  const [retry]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===path),panel.getByRole('button',{name:'恢复原补传审批请求',exact:true}).click()]);expect(retry.status()).toBe(503);expect(retry.request().postData()).toBe(original);
  await expect(panel.getByRole('button',{name:'恢复原补传审批请求',exact:true})).toBeEnabled();await expect(panel.getByText('补传已批准，原时限和回答保留；不表示录音已保存或已提交',{exact:true})).toHaveCount(0);
});
