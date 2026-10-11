import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import {test,expect} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{},assessment=Number(env.PREPARED_BROADCAST_ASSESSMENT_ID),port=Number(env.PREPARED_BROADCAST_HTTP_PORT);
const account=env.PREPARED_BROADCAST_STUDENT_ACCOUNT,password=env.PREPARED_TEST_LOGIN_PASSWORD;
// Only media transport is a synthetic native WAV fixture; authentication, published
// asset binding, permission checks and read authorization use the actual backend.
test.use({launchOptions:{args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']}});
test('real authorized prepared asset plays through a native browser player without blocking original capture',async({page,context})=>{
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!Number.isSafeInteger(assessment)||!assertPreparedFixture(env,assessment)||!Number.isSafeInteger(port)||port<1024||port===13014||port===13016||!account?.startsWith('prepared-broadcast-')||!password||!env.PREPARED_BROADCAST_WAVE_BASE64||!env.PREPARED_BROADCAST_OBJECT_KEY)throw new Error('Independent bounded broadcast fixture required');
  await context.grantPermissions(['microphone']);
  const bufferModule='node:buffer';const {Buffer}=await import(bufferModule);
  let pending=0,sequence=0;
  const began=Date.now(),inFlight=new Map<number,{operation:string;started:number}>();
  // Keep diagnostic output free of identities, answer content, object keys and
  // signed URLs. A full-run timeout must expose the phase and actual I/O timing.
  const stage=(value:string)=>console.info(JSON.stringify({journey:'student-question-broadcast',stage:value,elapsedMs:Date.now()-began,pending,
    inFlight:Array.from(inFlight.values()).map(request=>({operation:request.operation,elapsedMs:Date.now()-request.started}))}));
  // Shared real cookie/Redis identity already works through the normal login;
  // forward ONLY this assessment, not dashboard requests cancelled by navigation.
  await page.route(`**/dev-api/backend/prepared-interviews/${assessment}/**`,async route=>{
    const url=new URL(route.request().url()),tail=url.pathname.split('/').at(-1)??'',operation=`${route.request().method()} ${/^[a-z-]{1,40}$/.test(tail)?tail:'resource'}`;
    const request=++sequence,started=Date.now();let status:number|undefined;
    pending++;inFlight.set(request,{operation,started});
    try{url.host=`127.0.0.1:${port}`;url.pathname=url.pathname.replace(/^\/dev-api/,'');const response=await route.fetch({url:url.toString()});status=response.status();await route.fulfill({response});}
    finally{pending--;inFlight.delete(request);console.info(JSON.stringify({journey:'student-question-broadcast',operation,status,durationMs:Date.now()-started}));}
  });
  const media=`https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/${env.PREPARED_BROADCAST_OBJECT_KEY}`;
  await page.route(`${media}?*`,route=>route.fulfill({status:200,contentType:'audio/wav',body:Buffer.from(env.PREPARED_BROADCAST_WAVE_BASE64!,'base64'),headers:{'Cache-Control':'private, no-store','Access-Control-Allow-Origin':'http://127.0.0.1:13015'}}));
  try {
    stage('begin');await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(account);await page.getByPlaceholder('请输入密码').fill(password);
    const logging=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/login'));await page.getByRole('button',{name:/立即登录/}).click();expect((await logging).status()).toBe(200);await expect(page).toHaveURL(/studentDashboard$/);stage('login-complete');
    await page.goto(`/prepared-interview/${assessment}/preflight`);await page.getByTestId('recording-consent').check();await page.getByRole('button',{name:'开始麦克风试音',exact:true}).click();await expect(page.getByTestId('heard-confirmation')).toBeVisible({timeout:20000});await page.getByTestId('heard-confirmation').check();stage('preflight-complete');
    const starting=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/questions/start'));await page.getByRole('button',{name:'开考并开始第一题',exact:true}).click();const original=(await (await starting).json()).data;
    await expect(page.getByTestId('capture-status')).toContainText('正在录音');await expect(page.getByTestId('capture-status')).toContainText(/已事务保存 [1-9]/,{timeout:4000});stage('recording-saved');
    const broadcasting=page.waitForResponse(r=>r.request().method()==='GET'&&r.url().includes('/question-audio?'));await page.getByRole('button',{name:'获取题目播报／重播',exact:true}).click();const signed=await broadcasting;expect(signed.status()).toBe(200);const proof=(await signed.json()).data;
    expect(proof.answerId).toBe(original.answerId);expect(proof.questionId).toBe(original.questionId);expect(proof.paperVersionId).toBe(original.paperVersionId);
    const player=page.getByRole('region',{name:'题目语音播报'}).locator('audio');await expect(player).toBeVisible();expect(await player.getAttribute('src')).toBe(proof.url);
    await player.evaluate(async(element:HTMLAudioElement)=>{await element.play();});await expect.poll(()=>player.evaluate((e:HTMLAudioElement)=>e.duration)).toBeGreaterThan(0);stage('broadcast-playing');
    await expect(page.getByTestId('capture-status')).toContainText('正在录音');await expect(page.getByRole('region',{name:'题目语音播报'}).getByRole('alert')).toHaveCount(0);
    await expect(page.getByText(`本题原截止：${original.answerDeadline}`,{exact:false})).toBeVisible();
    const ending=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/end'));await page.getByRole('button',{name:'结束本题并保存',exact:true}).click();expect((await ending).status()).toBe(200);await expect(page.getByText(/本题原结束信息已登记/)).toBeVisible();stage('original-end-registered');
    await expect.poll(()=>pending).toBe(0);stage('before-reload');await page.reload();stage('reload-complete');await expect(page.getByTestId('authorized-question')).toBeVisible();await expect(page.getByRole('button',{name:'恢复当前题采集',exact:true})).toHaveCount(0);await expect(page.getByRole('region',{name:'题目语音播报'}).locator('audio')).toHaveCount(0);stage('original-restored');
    await expect.poll(()=>pending).toBe(0);stage('complete');
  }finally{stage('cleanup');await page.unrouteAll({behavior:'wait'});}
});
