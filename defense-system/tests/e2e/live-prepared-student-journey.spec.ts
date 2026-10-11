import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import {expect,test as base,type Page} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
const assessment=Number(env.PREPARED_FORMAL_ASSESSMENT_ID),student=Number(env.PREPARED_FORMAL_STUDENT_ID),account=env.PREPARED_FORMAL_STUDENT_ACCOUNT,password=env.PREPARED_TEST_LOGIN_PASSWORD;
const namespace=`/backend/prepared-interviews/${assessment}`;
// Login, authorization and the six-second microphone trial are real setup,
// not time spent answering an authorized question. Give this setup its own
// unchanged 30-second ceiling; preserve the journey's 30-second ceiling and all
// per-operation/deadline assertions. This is NOT proof of production capacity.
const requireScopedFixture=()=>{
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||env.PREPARED_FORMAL_TEST_PORT!=='13016'||!Number.isSafeInteger(assessment)||!assertPreparedFixture(env,assessment)||!Number.isSafeInteger(student)||!account?.startsWith('prepared-question-')||!password)throw new Error('Independent scoped formal browser fixture required');
  return {account,password};
};
const test=base.extend<{preparedRunnerBootstrap:void;preparedOriginalReady:void}>({
  preparedRunnerBootstrap:[async({},use)=>{
    requireScopedFixture();
    const began=Date.now(),stage=(value:string)=>console.info(JSON.stringify({journey:'student-runner-bootstrap',stage:value,elapsedMs:Date.now()-began}));
    const bootstrapDelay=Number(env.PREPARED_FORMAL_BOOTSTRAP_DELAY_MS??0);
    if(![0,20000].includes(bootstrapDelay))throw new Error('Only the bounded independent bootstrap regression is permitted');
    // Test-runner scheduling injection, before any application request or device
    // work. No authorization, response, recording, or original clock is mocked.
    if(bootstrapDelay){stage('controlled-bootstrap-delay-start');await new Promise<void>(resolve=>setTimeout(resolve,bootstrapDelay));stage('controlled-bootstrap-delay-end');}
    await use();
  },{auto:true,timeout:30000}],
  preparedOriginalReady:[async({page,context,preparedRunnerBootstrap},use)=>{
    void preparedRunnerBootstrap;const {account:readyAccount,password:readyPassword}=requireScopedFixture();
    const began=Date.now(),stage=(value:string)=>console.info(JSON.stringify({journey:'student-original-setup',stage:value,elapsedMs:Date.now()-began}));stage('begin');
    await context.grantPermissions(['microphone','camera']);
    // Expose only the real stream for an explicitly labelled device-ended event injection.
    // Native encoding, final data events and durable storage remain real; this is not physical-device acceptance.
    await page.addInitScript(()=>{const original=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      navigator.mediaDevices.getUserMedia=async constraints=>{const stream=await original(constraints);if(constraints?.audio)(window as any).__preparedTestMicrophone=stream;return stream;};});
    // Forward to the actual explicitly enabled local HTTP instance. No fabricated response, model or storage success.
    await page.route(`**/dev-api${namespace}/**`,async route=>{
      const url=new URL(route.request().url());url.host='127.0.0.1:13016';url.pathname=url.pathname.replace(/^\/dev-api/,'');
      const response=await route.fetch({url:url.toString()});await route.fulfill({response});
    });
    await page.goto('/login');stage('login-loaded');await page.getByPlaceholder('请输入账号').fill(readyAccount);await page.getByPlaceholder('请输入密码').fill(readyPassword);
    const login=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/login'));
    await page.getByRole('button',{name:/立即登录/}).click();const logged=await login;
    if(logged.status()!==200){await page.getByPlaceholder('请输入密码').fill('');throw new Error('Scoped fixture login rejected; do not disable account protection');}
    await expect(page).toHaveURL(/studentDashboard$/);stage('login-complete');
    await page.goto(`/prepared-interview/${assessment}/preflight`);stage('preflight-loaded');await page.getByTestId('recording-consent').check();
    await page.getByRole('button',{name:'开始麦克风试音',exact:true}).click();stage('microphone-started');await expect(page.getByTestId('heard-confirmation')).toBeVisible({timeout:20000});await page.getByTestId('heard-confirmation').check();stage('microphone-confirmed');
    await page.evaluate(async()=>{const name='/src/prepared/localAudioJournal.ts';(window as any).__preparedJournalModule=await import(/* @vite-ignore */name);});
    await expect(page.getByRole('button',{name:'开考并开始第一题',exact:true})).toBeEnabled();stage('complete');
    await use();
  },{auto:true,timeout:30000}],
});
test.use({launchOptions:{args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required']}});
test.describe('formal original student journey — native virtual devices, synthetic paper proof, NO cloud receipt',()=>{
  // Do not dispose the shared request context while a real forwarded recovery request still owns a response.
  // Await actual handlers; never hide routing failures with ignoreErrors.
  test.afterEach(async({page})=>{await page.unrouteAll({behavior:'wait'});});
  test('storage-isolation: real cross-window settings cannot stop the original native recorder; shared login changes still do',async({page,context})=>{
    const starting=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/questions/start'));
    await page.getByRole('button',{name:'开考并开始第一题',exact:true}).click();const original=(await (await starting).json()).data;
    expect(original.answerSeconds).toBe(30);await expect(page.getByTestId('capture-status')).toContainText(/已事务保存 [1-9]/,{timeout:4000});
    const before=await originalMetadata(page),prefix=await nativeChunkProof(page,original);
    expect(before.audio).toHaveLength(1);expect(before.audio[0].state).toBe('OPEN');expect(prefix.length).toBeGreaterThan(0);
    await page.evaluate(()=>{(window as any).__preparedStorageEvents=[];
      window.addEventListener('storage',event=>(window as any).__preparedStorageEvents.push({key:event.key,local:event.storageArea===localStorage}));});
    const writer=await context.newPage();
    try{
      // A real same-origin window, without booting another student workflow or synthesizing StorageEvent.
      await writer.goto(new URL('/favicon.ico',page.url()).toString());
      expect(await page.evaluate(()=>document.visibilityState)).toBe('visible');
      await writer.evaluate(()=>{localStorage.setItem('prepared-ui-theme','compact');localStorage.removeItem('prepared-ui-theme');});
      await expect.poll(()=>page.evaluate(()=>(window as any).__preparedStorageEvents.filter((e:any)=>e.key==='prepared-ui-theme'&&e.local).length)).toBe(2);
      // Session storage is shared with same-tab frames, not a different top-level tab.
      // Exercise its real native events too; the login hint lives only in localStorage.
      await page.evaluate(()=>{const frame=document.createElement('iframe');frame.hidden=true;document.body.append(frame);
        const scratch=frame.contentWindow!.sessionStorage;scratch.setItem('Admin-Token-Defense-System','1');scratch.clear();frame.remove();});
      await expect.poll(()=>page.evaluate(()=>(window as any).__preparedStorageEvents.filter((e:any)=>!e.local&&(e.key==='Admin-Token-Defense-System'||e.key===null)).length)).toBe(2);
      await expect(page.getByTestId('capture-status')).toContainText('正在录音');await expect(page.getByTestId('authorized-question')).toHaveText('题目 1');
      await expect(page.getByTestId('recording-consent')).toBeChecked();await expect(page.getByTestId('heard-confirmation')).toBeChecked();
      await expect(page.getByText(`本题原截止：${original.answerDeadline}`,{exact:false})).toBeVisible();
      await expect.poll(async()=>{const rows=await originalMetadata(page);return rows.audio[0].chunkCount;}).toBeGreaterThan(before.audio[0].chunkCount);
      const during=await originalMetadata(page);expect(during.journey).toEqual(before.journey);expect(during.audio[0].id).toBe(before.audio[0].id);expect(during.audio[0].state).toBe('OPEN');
      expect((await nativeChunkProof(page,original)).slice(0,prefix.length)).toEqual(prefix);
      // The shared marker removal is still a stop signal. It must not erase the prefix or invent an end/submission.
      await writer.evaluate(()=>localStorage.removeItem('Admin-Token-Defense-System'));
      await expect(page.getByTestId('capture-status')).toHaveCount(0);await expect(page.getByTestId('recording-consent')).toHaveCount(0);
      await expect.poll(async()=>{const rows=await originalMetadata(page);return rows.audio[0].state;}).toBe('INTERRUPTED');
      const after=await originalMetadata(page);expect(after.journey).toEqual(before.journey);expect(after.audio).toHaveLength(1);
      expect(after.audio[0].interruption).toBe('SESSION_CHANGED');expect(after.audio[0].answerEnd).toBeUndefined();
      expect((await nativeChunkProof(page,original)).slice(0,prefix.length)).toEqual(prefix);await verifyNativeSegments(page);
      expect(await page.evaluate(()=>(window as any).__preparedTestMicrophone.getAudioTracks().every((t:MediaStreamTrack)=>t.readyState==='ended'))).toBe(true);
    }finally{await writer.close();}
  });
  test('fault-continuation: reliable device interruption can append to the same original answer without resetting time or hiding the gap',async({page})=>{
    const starting=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/questions/start'));
    await page.getByRole('button',{name:'开考并开始第一题',exact:true}).click();const original=(await (await starting).json()).data;
    expect(original.answerSeconds).toBe(30);await expect(page.getByTestId('capture-status')).toContainText(/已事务保存 [1-9]/,{timeout:4000});
    await page.evaluate(()=>{const stream=(window as any).__preparedTestMicrophone as MediaStream;if(!stream?.getAudioTracks().length)throw new Error('Native formal microphone must exist');stream.getAudioTracks()[0].dispatchEvent(new Event('ended'));});
    await expect(page.getByTestId('capture-status')).toContainText('采集有间断');
    await expect(page.getByRole('button',{name:'故障恢复：保留原内容继续录音',exact:true})).toBeEnabled();
    const before=await originalMetadata(page);expect(before.audio).toHaveLength(1);expect(before.audio[0].state).toBe('INTERRUPTED');expect(before.audio[0].terminal.reason).toBe('DEVICE_ENDED');expect(before.audio[0].answerEnd).toBeUndefined();
    await page.reload();await expect(page.getByTestId('authorized-question')).toHaveText('题目 1');
    await expect(page.getByRole('button',{name:'重新核验原作答',exact:true})).toBeEnabled();expect(await originalMetadata(page)).toEqual(before);
    await expect(page.getByTestId('capture-status')).toHaveCount(0);await page.getByTestId('recording-consent').check();
    await page.getByRole('button',{name:'开始麦克风试音',exact:true}).click();await expect(page.getByTestId('heard-confirmation')).toBeVisible({timeout:20000});await page.getByTestId('heard-confirmation').check();
    const replay=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/questions/start'));
    await page.getByRole('button',{name:'故障恢复：保留原内容继续录音',exact:true}).click();const resumed=(await (await replay).json()).data;
    for(const field of ['attemptId','answerId','questionId','paperVersionId','ordinal','body','startedAt','answerDeadline'])expect(resumed[field]).toEqual(original[field]);
    await expect(page.getByTestId('capture-status')).toContainText('正在录音');await expect.poll(async()=>{const m=await originalMetadata(page);return m.audio.find((s:any)=>s.ordinal===2)?.chunkCount??0;}).toBeGreaterThan(0);
    const during=await originalMetadata(page);expect(during.journey).toEqual(before.journey);expect(during.audio.find((s:any)=>s.ordinal===1)).toEqual(before.audio[0]);
    const ending=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/end'));await page.getByRole('button',{name:'结束本题并保存',exact:true}).click();const response=await ending;expect(response.status()).toBe(200);
    const receipt=(await response.json()).data;expect(receipt.declarationState).toBe('REQUIRES_REVIEW');expect(receipt.reviewReasons).toContain('CAPTURE_INTERRUPTED');expect(receipt.serverSaved).toBe(false);
    const after=await originalMetadata(page);expect(after.audio.map((s:any)=>[s.ordinal,s.state]).sort((a:any,b:any)=>a[0]-b[0])).toEqual([[1,'INTERRUPTED'],[2,'SEALED']]);
    await verifyNativeSegments(page);await expect(page.getByRole('button',{name:'故障恢复：保留原内容继续录音',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'授权并开始下一题',exact:true})).toHaveCount(0);
    await page.reload();await expect(page.getByTestId('authorized-question')).toHaveText('题目 1');await expect(page.getByRole('button',{name:'重新核验原作答',exact:true})).toBeEnabled();expect(await originalMetadata(page)).toEqual(after);
  });
  test('broadcast-failure: actual unavailable question audio does not stop native recording or alter its original deadline',async({page})=>{
    const starting=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/questions/start'));
    await page.getByRole('button',{name:'开考并开始第一题',exact:true}).click();const original=(await (await starting).json()).data;
    await expect(page.getByTestId('capture-status')).toContainText('正在录音');
    const broadcast=page.waitForResponse(r=>r.request().method()==='GET'&&r.url().includes('/question-audio?'));
    await page.getByRole('button',{name:'获取题目播报／重播',exact:true}).click();expect((await broadcast).status()).toBe(503);
    await expect(page.getByRole('region',{name:'题目语音播报'}).getByRole('alert')).toContainText('不阻止录音');
    await expect(page.getByTestId('capture-status')).toContainText('正在录音');await expect(page.getByTestId('capture-status')).toContainText(/已事务保存 [1-9]/,{timeout:4000});
    await expect(page.getByTestId('authorized-question')).toHaveText('题目 1');await expect(page.getByText(`本题原截止：${original.answerDeadline}`,{exact:false})).toBeVisible();
    const ending=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/end'));await page.getByRole('button',{name:'结束本题并保存',exact:true}).click();expect((await ending).status()).toBe(200);
    await verifyLocal(page,1);const before=await originalMetadata(page);await page.reload();await expect(page.getByTestId('authorized-question')).toHaveText('题目 1');await expect(page.getByRole('button',{name:'重新核验原作答',exact:true})).toBeEnabled();expect(await originalMetadata(page)).toEqual(before);
  });
  test('upload-recovery: real unavailable storage and lost original prepare do not block next question or erase native intents',async({page})=>{
    // Actual server/session/database, native virtual recording. No positive cloud receipt is fabricated.
    const uploadRoute=`**/dev-api${namespace}/attempts/**/answers/**/upload-bundles`;
    const originalBodies:string[]=[];let lost=false,originalAttempt='';
    await page.route(uploadRoute,async route=>{
      if(route.request().method()!=='POST'){await route.fallback();return;}
      originalBodies.push(route.request().postData()!);
      if(!lost){lost=true;await route.abort('connectionfailed');return;}
      await route.fallback();
    });
    for(let ordinal=1;ordinal<=2;ordinal++){
      if(ordinal===2)await expect(page.getByRole('button',{name:'授权并开始下一题',exact:true})).toBeEnabled();
      const starting=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/questions/start'));
      await page.getByRole('button',{name:ordinal===1?'开考并开始第一题':'授权并开始下一题',exact:true}).click();
      const authorized=(await (await starting).json()).data;expect(authorized.attemptId).toMatch(/^[a-f0-9-]{36}$/);
      if(originalAttempt)expect(authorized.attemptId).toBe(originalAttempt);originalAttempt=authorized.attemptId;
      await expect(page.getByTestId('capture-status')).toContainText(/已事务保存 [1-9]/,{timeout:4000});
      await page.getByRole('button',{name:'结束本题并保存',exact:true}).click();await expect(page.getByText(/本题原结束信息已登记/)).toBeVisible();
    }
    await expect.poll(()=>lost).toBe(true);
    const before=await page.evaluate(async({assessment,originalAttempt})=>{const name='/src/prepared/uploadJournal.ts',{PreparedUploadJournal}=await import(/* @vite-ignore */name);
      const own=await fetch('/dev-api/user-detail',{cache:'no-store'}).then(r=>r.json()),j=new PreparedUploadJournal({origin:location.origin,studentId:Number(own.data.id),deploymentId:'prepared-local'},assessment);
      try{const answers=await j.answers(originalAttempt),intents=[];for(const a of answers)intents.push(...await j.intents(a));return {answers,intents};}finally{j.close();}},{assessment,originalAttempt});
    expect(before.answers).toHaveLength(2);expect(before.intents.length).toBeGreaterThan(0);
    const first=before.intents.find((i:any)=>i.answer.ordinal===1);expect(first).toBeTruthy();expect(JSON.stringify(first.command)).toBe(originalBodies[0]);
    const missing=page.waitForResponse(r=>r.request().method()==='GET'&&r.url().endsWith(`/upload-bundles/${first.bundleId}`));
    const originalPrepare=page.waitForRequest(r=>r.method()==='POST'&&r.url().includes(`/answers/${first.answer.answerId}/upload-bundles`));
    await page.reload();expect((await missing).status()).toBe(404);expect((await originalPrepare).postData()).toBe(originalBodies[0]);
    await expect(page.getByTestId('upload-status')).toContainText('后台上传暂未确认');
    await expect(page.getByTestId('authorized-question')).toHaveText('题目 2');await expect(page.getByRole('button',{name:'授权并开始下一题',exact:true})).toBeVisible();
    await expect(page.getByTestId('upload-status')).toContainText('已核对 0 片');await verifyLocal(page,2);
    const reply=await page.request.get(`http://127.0.0.1:13016${namespace}/attempts/${first.answer.attemptId}/answers/${first.answer.answerId}/chunks`,{headers:{Origin:'http://127.0.0.1:13015'}});
    expect(reply.status()).toBe(200);expect((await reply.json()).data).toEqual([]);
  });
  test('full: three actual authorized native recordings persist original manifests and fixed 24h window',async({page})=>{
    const forbidden:string[]=[];page.on('websocket',ws=>{const u=new URL(ws.url());if(!(u.host==='127.0.0.1:13015'&&u.pathname==='/'&&u.searchParams.has('token')))forbidden.push(ws.url());});
    for(let ordinal=1;ordinal<=3;ordinal++){
      await page.getByRole('button',{name:ordinal===1?'开考并开始第一题':'授权并开始下一题',exact:true}).click();
      await expect(page.getByTestId('capture-status')).toContainText('正在录音');await expect(page.getByTestId('capture-status')).toContainText(/已事务保存 [1-9]/,{timeout:4000});
      await expect(page.getByTestId('authorized-question')).toHaveText(`题目 ${ordinal}`);
      const end=page.waitForResponse(r=>r.url().includes(`${namespace}/attempts/`)&&r.url().endsWith('/end')&&r.request().method()==='POST');
      await page.getByRole('button',{name:'结束本题并保存',exact:true}).click();const response=await end;expect(response.status()).toBe(200);
      const data=(await response.json()).data;expect(data.serverSaved).toBe(false);expect(data.declarationState).toBe('UPLOAD_PENDING');
      await expect(page.getByText(/本题原结束信息已登记/)).toBeVisible();await verifyLocal(page,ordinal);
      if(ordinal===3){expect(data.attemptState).toBe('UPLOAD_PENDING');expect(Date.parse(data.supplementalUploadDeadline)-Date.parse(data.answeringEndedAt)).toBe(24*60*60*1000);}
    }
    await expect(page.getByText(/全部题目的结束信息已登记；仍需核对完整收件并取得唯一提交回执/)).toBeVisible();await expect(page.getByRole('button',{name:'授权并开始下一题',exact:true})).toHaveCount(0);expect(forbidden).toEqual([]);
    await expect(page.getByTestId('media-status')).toContainText('当前尝试已可靠保存 0 / 3 题');
    const before=await originalMetadata(page);await page.reload();await expect(page.getByText(/全部题目的结束信息已登记；仍需核对完整收件并取得唯一提交回执/)).toBeVisible();await expect(page.getByRole('button',{name:'重新核验原作答',exact:true})).toBeEnabled();expect(await originalMetadata(page)).toEqual(before);
    await expect(page.getByTestId('media-status')).toContainText('当前尝试已可靠保存 0 / 3 题');
  });
  test('response-loss: server accepted end survives discarded response and refresh with the same original command',async({page})=>{
    await page.getByRole('button',{name:'开考并开始第一题',exact:true}).click();await expect(page.getByTestId('capture-status')).toContainText(/已事务保存 [1-9]/,{timeout:4000});
    let accepted:any,originalBody:string|undefined;
    const lost=`**/dev-api${namespace}/attempts/**/answers/**/end`;
    await page.route(lost,async route=>{originalBody=route.request().postData()??undefined;const u=new URL(route.request().url());u.host='127.0.0.1:13016';u.pathname=u.pathname.replace(/^\/dev-api/,'');
      const response=await route.fetch({url:u.toString()});expect(response.status()).toBe(200);accepted=(await response.json()).data;await route.abort('connectionfailed');});
    await page.getByRole('button',{name:'结束本题并保存',exact:true}).click();await expect(page.getByText(/原作答操作未完成/)).toBeVisible();expect(accepted.serverSaved).toBe(false);
    await verifyLocal(page,1);const before=await originalMetadata(page);await page.unroute(lost);await page.reload();
    await expect(page.getByText(/本机原录音/)).toBeVisible();await expect(page.getByRole('button',{name:'恢复当前题采集',exact:true})).toHaveCount(0);
    const replay=page.waitForRequest(r=>r.url().endsWith('/end')&&r.method()==='POST');
    await page.getByRole('button',{name:'登记本题原结束信息',exact:true}).click();expect((await replay).postData()).toBe(originalBody);
    await expect(page.getByText(/本题原结束信息已登记/)).toBeVisible();expect(await originalMetadata(page)).toEqual(before);
    const response=await page.request.get(`http://127.0.0.1:13016${namespace}/attempts/current`,{headers:{Origin:'http://127.0.0.1:13015'}});expect(response.status()).toBe(200);expect((await response.json()).data.state).toBe('IN_PROGRESS');
  });
  test('deadline: native recorder stops at the original per-question limit without a whole-exam expiry',async({page,context})=>{
    const started=Date.now(),stage=(value:string)=>console.info(JSON.stringify({journey:'student-original-deadline',stage:value,elapsedMs:Date.now()-started}));stage('preflight-complete');
    let original:any;page.on('response',async r=>{if(r.url().endsWith('/questions/start')&&r.status()===200)original=(await r.json()).data;});
    await page.getByRole('button',{name:'开考并开始第一题',exact:true}).click();await expect(page.getByTestId('capture-status')).toContainText('正在录音');
    stage('recording');
    // Only browser transport goes offline; capture and the original clock continue without a live connection.
    await context.setOffline(true);await expect(page.getByTestId('capture-status')).toContainText('原录音本机封存',{timeout:9000});
    stage('original-deadline-sealed');
    await expect(page.getByRole('button',{name:'结束本题并保存',exact:true})).toHaveCount(0);await verifyLocal(page,1);
    await context.setOffline(false);await page.getByRole('button',{name:'登记本题原结束信息',exact:true}).click();await expect(page.getByText(/本题原结束信息已登记/)).toBeVisible();const before=await originalMetadata(page);
    stage('original-end-registered');await page.reload();stage('reload-complete');await expect(page.getByTestId('authorized-question')).toHaveText('题目 1');await expect(page.getByRole('button',{name:'重新核验原作答',exact:true})).toBeEnabled();expect(await originalMetadata(page)).toEqual(before);stage('original-restored');
    const rows=await originalMetadata(page);expect(rows.audio[0].endedAt-rows.audio[0].startedAt).toBeLessThanOrEqual(5500);expect(original.answerSeconds).toBe(5);
    const a=await page.request.get(`http://127.0.0.1:13016${namespace}/attempts/current`,{headers:{Origin:'http://127.0.0.1:13015'}});expect((await a.json()).data.answeringEndedAt).toBeNull();
  });
});
async function nativeChunkProof(page:Page,answer:{attemptId:string;answerId:string}){
  return page.evaluate(async({assessment,student,answer})=>{
    const name='/src/prepared/localAudioJournal.ts',{PreparedAudioJournal}=await import(/* @vite-ignore */name);
    const journal=new PreparedAudioJournal({origin:location.origin,studentId:student,deploymentId:'prepared-local'});
    try{const scope={assessmentId:assessment,attemptId:answer.attemptId,answerId:answer.answerId},rows=await journal.list(scope);
      if(rows.length!==1)throw new Error('Exactly one original native segment required');
      const original=await journal.read(scope,rows[0].id);
      return original.chunks.map((c:any)=>({index:c.index,byteSize:c.byteSize,sha256:c.sha256}));
    }finally{journal.close();}
  },{assessment,student,answer});
}
async function originalMetadata(page:Page){return page.evaluate(async({assessment,student})=>{
  const read=async(name:string,store:string)=>{const db=await new Promise<IDBDatabase>((resolve,reject)=>{const r=indexedDB.open(name,1);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
    try{return await new Promise<any[]>((resolve,reject)=>{const r=db.transaction(store).objectStore(store).getAll();r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});}finally{db.close();}};
  const journey=(await read('prepared-interview-journey-v1','commands')).filter(r=>{const parts=JSON.parse(JSON.parse(r.key)[0]);return parts[3]===student&&parts[4]===assessment;}).map(r=>({key:r.key,sha256:r.sha256,value:r.value}));
  const audio=(await read('prepared-interview-audio-v1','segments')).filter(r=>{const parts=JSON.parse(r.namespace);return parts[3]===student&&parts[4]===assessment;}).map(({namespace,...r})=>r);
  return {journey,audio};
},{assessment,student});}
async function verifyLocal(page:Page,count:number){
  const metadata=await originalMetadata(page);expect(metadata.audio).toHaveLength(count);
  for(const row of metadata.audio){expect(row.state).toBe('SEALED');expect(row.chunkCount).toBeGreaterThan(0);expect(row.byteSize).toBeGreaterThan(0);}
  await verifyNativeSegments(page);
}
async function verifyNativeSegments(page:Page){
  await page.evaluate(async({assessment,student})=>{
    // Reload destroys page globals; inspect the reopened real database through a fresh module handle.
    const name='/src/prepared/localAudioJournal.ts',{PreparedAudioJournal}=await import(/* @vite-ignore */name);
    const journal=new PreparedAudioJournal({origin:location.origin,studentId:student,deploymentId:'prepared-local'});
    const db=await new Promise<IDBDatabase>(resolve=>{const r=indexedDB.open('prepared-interview-audio-v1',1);r.onsuccess=()=>resolve(r.result);});
    const rows=await new Promise<any[]>(resolve=>{const r=db.transaction('segments').objectStore('segments').getAll();r.onsuccess=()=>resolve(r.result);});db.close();
    try{for(const row of rows){const p=JSON.parse(row.namespace);if(p[3]!==student||p[4]!==assessment)continue;
      const result=await journal.read({assessmentId:assessment,attemptId:p[5],answerId:p[6]},row.id);
      const blob=new Blob(result.chunks.map((c:any)=>c.blob),{type:row.mimeType}),decoder=new AudioContext();
      try{const audio=await decoder.decodeAudioData(await blob.arrayBuffer());if(audio.duration<=0||audio.length<=0)throw new Error('Native recorded container must decode completely');}finally{await decoder.close();}
    }}finally{journal.close();}
  },{assessment,student});
}
