import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import {test,expect} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
/** Actual scoped login/backend/database. No intercepted authorization, fake URL,
 * simulated successful private playback or model request. Exit state is a DB fixture. */
test('audio exit refuses authorization across refresh without hiding the retained grade',async({page})=>{
  test.skip(env.LIVE_E2E!=='1','requires independent actual audio-exit fixture');
  const id=env.PREPARED_AUDIO_EXIT_ASSESSMENT!,answer=env.PREPARED_AUDIO_EXIT_ANSWER!,state=env.PREPARED_AUDIO_EXIT_STATE!,account=env.PREPARED_AUDIO_EXIT_ACCOUNT!;
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13017'||!Number.isSafeInteger(Number(id))||!assertPreparedFixture(env,Number(id))||!/^[a-f0-9-]{36}$/.test(answer)||!['RETIRING','RETIRED'].includes(state)||!account?.startsWith('prepared-publication-')||!env.PREPARED_TEST_LOGIN_PASSWORD)throw new Error('Scoped independent audio-exit fixture required');
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(account);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const [login]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);
  expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);await expect(page).not.toHaveURL(/\/login/);
  const calls:string[]=[];page.on('request',r=>{if(new URL(r.url()).pathname.endsWith(`/answers/${answer}/audio`))calls.push(r.method());});
  for(let n=0;n<2;n++){
    if(n===0)await page.goto(`/prepared-review/${id}`);else await page.reload();
    await page.getByTestId('review-student').first().click();await page.locator(`[data-testid="review-answer"][data-answer-id="${answer}"]`).click();
    await expect(page.getByTestId('review-score')).toContainText('8.5 / 10');
    const audio=page.getByRole('region',{name:'原录音播放'}),button=audio.getByRole('button',{name:'获取原录音播放授权',exact:true});
    const [reply]=await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname.endsWith(`/answers/${answer}/audio`)),button.click()]);
    expect(reply.status()).toBe(state==='RETIRED'?410:409);expect(reply.headers()['cache-control']).toContain('no-store');
    expect(await reply.text()).not.toMatch(/Signature|objectKey|"url"/);
    await expect(audio.getByRole('alert')).toContainText(state==='RETIRED'?'原录音已按留存规则退出':'原录音正在执行留存清理');
    await expect(audio.locator('audio')).toHaveCount(0);await expect(button).toBeEnabled();
    await expect(page.getByTestId('review-score')).toContainText('8.5 / 10');
    await expect(page.getByRole('region',{name:'整场成绩汇总'}).getByTestId('review-summary-total')).toContainText('25.5');
    expect(calls).toHaveLength(n+1); // no automatic retry or new write
  }
  expect(calls).toEqual(['GET','GET']);await page.screenshot({path:`../.runtime/audio-exit-${id}-${state}.png`,fullPage:true});
});
