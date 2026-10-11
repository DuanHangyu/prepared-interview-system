import { expect, Page, test } from '@playwright/test';
const env = (globalThis as typeof globalThis & {process?: {env?: Record<string,string|undefined>}}).process?.env || {};
const password = env.PREPARED_TEST_LOGIN_PASSWORD;
const assessmentId = Number(env.PREPARED_BROWSER_ASSESSMENT_ID);
const endpoint = `/dev-api/backend/prepared-interviews/${assessmentId}/preflight-context`;
let loginState: Awaited<ReturnType<import('@playwright/test').BrowserContext['storageState']>>;
test.use({launchOptions:{args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required']}});
test.describe('real preflight UI + sessions + IndexedDB + native virtual devices — NOT physical device or full interview acceptance',()=>{
  test.beforeAll(async({browser})=>{
    if(env.E2E_BASE_URL !== 'http://127.0.0.1:13015' || !password || assessmentId !== 8) throw new Error('Only the independent prepared local fixtures are authorized');
    const context=await browser.newContext({baseURL:env.E2E_BASE_URL});const page=await context.newPage();
    try {
      await page.goto('/login');await page.getByPlaceholder('请输入账号').fill('prepared_browser_student');await page.getByPlaceholder('请输入密码').fill(password);
      await page.getByRole('button',{name:/立即登录/}).click();await expect(page).toHaveURL(/studentDashboard$/);
      loginState=await context.storageState();
    } finally {await context.close();}
  });
  test.beforeEach(async({context})=>{
    // Reuse only this fixture's authorized session in memory; never bypass or reset login rate limits.
    await context.addCookies(loginState.cookies);
    await context.addInitScript(origins=>{const state=origins.find(x=>x.origin===location.origin);for(const item of state?.localStorage??[])localStorage.setItem(item.name,item.value);},loginState.origins);
    await context.addInitScript(()=>{
      // Observe actual native streams for release assertions. This wrapper does NOT fabricate successful media.
      const native=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      (window as any).__preflightStreams=[];
      (window as any).__preflightDenyKinds=[];
      navigator.mediaDevices.getUserMedia=async constraints=>{
        // Explicit denied-permission fault injection; headless auto-approval is NOT real OS permission QA.
        if((constraints?.audio&&(window as any).__preflightDenyKinds.includes('microphone'))||(constraints?.video&&(window as any).__preflightDenyKinds.includes('camera')))
          throw new DOMException('Controlled denied-permission fault','NotAllowedError');
        const stream=await native(constraints);(window as any).__preflightStreams.push(stream);return stream;
      };
    });
  });
  test('consent, native microphone encoding, persisted decode, playback, camera and page-exit cleanup',async({page,context})=>{
    await context.grantPermissions(['microphone','camera']);
    const forbidden: string[]=[];page.on('websocket',ws=>{
      const url=new URL(ws.url());
      // Vite's dev-only hot reload socket is not an interview communication dependency.
      if(!(url.host==='127.0.0.1:13015'&&url.pathname==='/'&&url.searchParams.has('token')&&Array.from(url.searchParams.keys()).length===1))forbidden.push(ws.url());
    });
    page.on('request',request=>{if(request.method()!=='GET'&&request.url().includes('/dev-api/'))forbidden.push(request.url());});
    await enter(page);await page.getByRole('button',{name:'开始麦克风试音',exact:true}).click();
    await expect(page.getByText(/本机写入、校验及完整音频解码通过/)).toBeVisible({timeout:20000});
    await page.locator('audio').evaluate(async(audio:HTMLAudioElement)=>{await audio.play();});
    await expect.poll(()=>page.locator('audio').evaluate((audio:HTMLAudioElement)=>audio.currentTime)).toBeGreaterThan(0);
    await page.getByTestId('heard-confirmation').check();await expect(page.getByText('麦克风与本机保存检查通过（仅本次预检）。',{exact:true})).toBeVisible();
    await page.getByRole('button',{name:'开启摄像头预览',exact:true}).click();await expect(page.getByText('摄像头预览中，仅显示本机画面。',{exact:true})).toBeVisible();
    expect(await page.locator('video').evaluate((video:HTMLVideoElement)=>({width:video.videoWidth,height:video.videoHeight,tracks:(video.srcObject as MediaStream).getTracks().map(t=>t.kind)})))
      .toMatchObject({width:expect.any(Number),height:expect.any(Number),tracks:['video']});
    expect(await page.locator('video').evaluate((video:HTMLVideoElement)=>video.videoWidth*video.videoHeight)).toBeGreaterThan(0);
    expect(await scratchCount(page)).toBe(0);expect(forbidden).toEqual([]);
    await page.getByRole('button',{name:'返回考核列表',exact:true}).click();await expect(page).toHaveURL(/studentDashboard$/);
    expect(await page.evaluate(()=>(window as any).__preflightStreams.flatMap((s:MediaStream)=>s.getTracks().map(t=>t.readyState)))).toEqual(['ended','ended']);
  });
  test('injected permission denial is not a pass; camera failure cannot erase native virtual microphone success',async({page,context})=>{
    await enter(page);await page.evaluate(()=>{(window as any).__preflightDenyKinds=['microphone'];});await page.getByRole('button',{name:'开始麦克风试音',exact:true}).click();
    await expect(page.getByText('麦克风权限被拒绝，请在浏览器中允许麦克风后重试',{exact:true})).toBeVisible();
    await expect(page.getByTestId('heard-confirmation')).toHaveCount(0);
    await context.grantPermissions(['microphone']);await page.evaluate(()=>{(window as any).__preflightDenyKinds=['camera'];});await page.getByRole('button',{name:'开始麦克风试音',exact:true}).click();
    await expect(page.getByTestId('heard-confirmation')).toBeVisible({timeout:20000});await page.getByTestId('heard-confirmation').check();
    await page.getByRole('button',{name:'开启摄像头预览',exact:true}).click();await expect(page.getByText('摄像头权限被拒绝；不影响麦克风检查',{exact:true})).toBeVisible();
    await expect(page.getByText('麦克风与本机保存检查通过（仅本次预检）。',{exact:true})).toBeVisible();expect(await scratchCount(page)).toBe(0);
  });
  test('actual context transport failure, retry and cancellation never create a fake ready trial',async({page,context})=>{
    await context.grantPermissions(['microphone']);await page.route(`**${endpoint}`,route=>route.abort('connectionfailed'));
    await page.goto(`/prepared-interview/${assessmentId}/preflight`);await expect(page.getByText(/身份或考核核验失败/)).toBeVisible();
    await expect(page.getByTestId('recording-consent')).toHaveCount(0);
    await page.unroute(`**${endpoint}`);await page.getByRole('button',{name:'重新核验身份',exact:true}).click();await page.getByTestId('recording-consent').check();
    await page.getByRole('button',{name:'开始麦克风试音',exact:true}).click();await expect(page.getByText(/正在试音，还剩/)).toBeVisible();
    await page.getByRole('button',{name:'取消试音',exact:true}).click();await expect(page.locator('audio')).toHaveCount(0);
    await expect.poll(()=>page.evaluate(()=>(window as any).__preflightStreams.every((s:MediaStream)=>s.getTracks().every(t=>t.readyState==='ended')))).toBe(true);
    await page.reload();await expect(page.getByTestId('recording-consent')).not.toBeChecked();await expect(page.locator('audio')).toHaveCount(0);
    const response=await page.request.get(endpoint);expect(response.status()).toBe(200);expect(response.headers()['cache-control']).toContain('no-store');
    const data=(await response.json()).data;expect(data.deploymentId).toBe('prepared-local');expect(data.eligibility.phase).toBe('PAPER_NOT_PUBLISHED');
    for(const key of ['questions','rubric','score','objectKey','attemptId'])expect(data).not.toHaveProperty(key);
  });
});
async function enter(page:Page){
  await page.goto(`/prepared-interview/${assessmentId}/preflight`);await expect(page.getByRole('heading',{name:'面试预检',exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'开始麦克风试音',exact:true})).toBeDisabled();
  await page.getByTestId('recording-consent').check();await expect(page.getByRole('button',{name:'开始麦克风试音',exact:true})).toBeEnabled();
  await expect(page.getByRole('region',{name:'正式作答与原回答恢复',exact:true}).getByText(/正式开考尚未开放/)).toBeVisible();
}
async function scratchCount(page:Page){return page.evaluate(async()=>{
  const db=await new Promise<IDBDatabase>((resolve,reject)=>{const request=indexedDB.open('prepared-interview-preflight-v1',1);request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});
  try {return await new Promise<number>((resolve,reject)=>{const request=db.transaction('segments').objectStore('segments').count();request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});} finally {db.close();}
});}
