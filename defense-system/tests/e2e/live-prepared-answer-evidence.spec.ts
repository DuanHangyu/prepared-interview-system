import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import { test,expect } from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
/** Actual teacher cookies, HTTP and independent DB declaration. Synthetic manifest/assets
 * are explicitly NOT microphone, cloud audio or completed manual exception disposition QA. */
test('teacher reads original interruption evidence across pagination, offline retry and refresh without writes',async({page})=>{
  test.skip(env.LIVE_E2E!=='1','requires independent declaration evidence fixture');
  const assessment=Number(env.PREPARED_EVIDENCE_ASSESSMENT),attempt=env.PREPARED_EVIDENCE_ATTEMPT!,answer=env.PREPARED_EVIDENCE_ANSWER!,hash=env.PREPARED_EVIDENCE_HASH!;
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13017'||!Number.isSafeInteger(assessment)||!assertPreparedFixture(env,assessment)||!env.PREPARED_EVIDENCE_ACCOUNT?.startsWith('prepared-publication-')
    ||!env.PREPARED_TEST_LOGIN_PASSWORD||!/^[a-f0-9-]{36}$/.test(attempt)||!/^[a-f0-9-]{36}$/.test(answer)||!/^[a-f0-9]{64}$/.test(hash))throw new Error('Independent declaration evidence fixture required');
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(env.PREPARED_EVIDENCE_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const [login]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);
  expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);await expect(page).not.toHaveURL(/\/login/);
  const writes:string[]=[];page.on('request',r=>{if(new URL(r.url()).pathname.includes('/backend/prepared-')&&!['GET','HEAD'].includes(r.method()))writes.push(r.method());});
  const path=`/dev-api/backend/prepared-reviews/${assessment}/attempts/${attempt}/answers/${answer}/evidence`;
  const open=async()=>{await page.getByTestId('review-student').first().click();await expect(page.locator(`[data-testid="review-answer"][data-answer-id="${answer}"]`)).toBeDisabled();
    const [response]=await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname===path&&r.request().method()==='GET'),page.locator(`[data-testid="review-evidence"][data-answer-id="${answer}"]`).click()]);
    expect(response.status()).toBe(200);expect(response.headers()['cache-control']).toContain('no-store');const data=(await response.json()).data;expect(data.declaration.manifestSha256).toBe(hash);return data;};
  await page.goto(`/prepared-review/${assessment}`);const original=await open(),panel=page.getByRole('region',{name:'原结束声明证据'});
  await expect(panel.getByText('录音采集有间断，需人工核验',{exact:true})).toBeVisible();await expect(panel.getByTestId('evidence-segment')).toHaveCount(20);
  await expect(panel).toContainText('不能证明离线录制时间');await expect(panel.getByRole('button',{name:'更多原录音分段',exact:true})).toBeEnabled();
  await panel.getByRole('button',{name:'更多原录音分段',exact:true}).click();await expect(panel.getByTestId('evidence-segment')).toHaveCount(25);
  await expect(panel.getByRole('button',{name:'更多原录音分段',exact:true})).toHaveCount(0);await expect(page.locator('#review-manual-score')).toHaveCount(0);
  await page.context().setOffline(true);await panel.getByRole('button',{name:'刷新原结束声明',exact:true}).click();await expect(panel.getByRole('alert')).toBeVisible();
  await expect(panel.getByTestId('evidence-segment')).toHaveCount(0);await page.context().setOffline(false);
  await panel.getByRole('button',{name:'刷新原结束声明',exact:true}).click();await expect(panel.getByTestId('evidence-segment')).toHaveCount(20);
  await page.reload();const recovered=await open();expect(recovered.declaration).toEqual(original.declaration);expect(recovered.startedAt).toBe(original.startedAt);expect(recovered.answerDeadline).toBe(original.answerDeadline);
  expect(recovered.answerState).toBe('REQUIRES_REVIEW');await expect(panel.getByTestId('evidence-segment')).toHaveCount(20);expect(writes).toEqual([]);
  await page.screenshot({path:`../.runtime/teacher-evidence-${assessment}.png`,fullPage:true});
});
