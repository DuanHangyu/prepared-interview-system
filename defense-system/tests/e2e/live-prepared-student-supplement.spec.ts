import {assertPreparedFixture} from "../../../scripts/assert-owned-browser-fixture.mjs";
import {test,expect} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env??{};
/** Actual student UI/cookies/HTTP/MySQL/IndexedDB and teacher approval. The initial
 * paper and five-byte original recording are explicit synthetic fixtures, NOT
 * device/audio/cloud acceptance. No successful receipt or approval is mocked. */
test('student waits, sees genuine approval, reuses original upload after offline and reload without recording',async({page,browser})=>{
  test.skip(env.LIVE_E2E!=='1','requires independent original supplement fixture');
  if(env.E2E_BASE_URL!=='http://127.0.0.1:13017'||!env.PREPARED_STUDENT_SUPPLEMENT_ACCOUNT?.startsWith('prepared-question-')||!env.PREPARED_STUDENT_SUPPLEMENT_TEACHER?.startsWith('prepared-publication-')||!env.PREPARED_TEST_LOGIN_PASSWORD)throw new Error('Independent scoped fixture required');
  const fixture=JSON.parse(env.PREPARED_STUDENT_SUPPLEMENT_FIXTURE!),assessment=fixture.assessmentId,answer=fixture.firstAnswerId,attempt=fixture.attemptId,student=fixture.studentId;
  if(!Number.isSafeInteger(assessment)||!assertPreparedFixture(env,assessment)||!Number.isSafeInteger(student)||student<1||fixture.questions.length!==3)throw new Error('Owned synthetic fixture required');
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill(env.PREPARED_STUDENT_SUPPLEMENT_ACCOUNT!);await page.getByPlaceholder('请输入密码').fill(env.PREPARED_TEST_LOGIN_PASSWORD!);
  const [login]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/login')),page.getByRole('button',{name:/立即登录/}).click()]);expect(login.status()).toBe(200);expect((await login.json()).code).toBe(200);await expect(page).toHaveURL(/studentDashboard$/);
  await page.evaluate(async f=>{
    const journeyName='/src/prepared/journeyJournal.ts',audioName='/src/prepared/localAudioJournal.ts',uploadName='/src/prepared/uploadJournal.ts';
    const {PreparedJourneyJournal}=await import(/* @vite-ignore */journeyName),{PreparedAudioJournal}=await import(/* @vite-ignore */audioName),{PreparedUploadJournal}=await import(/* @vite-ignore */uploadName);
    const owner={origin:location.origin,studentId:f.studentId,deploymentId:'prepared-local'},j=new PreparedJourneyJournal(owner,f.assessmentId);if(await j.readStart())throw new Error('Fixture must not overwrite original journey');j.close();
    const namespace=JSON.stringify([1,owner.deploymentId,owner.origin,owner.studentId,f.assessmentId]),rows:Array<{key:string;value:unknown;sha256:string}>=[];
    const add=async(kind:string,value:unknown)=>{const key=JSON.stringify([namespace,kind]),sha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify([key,value])))),b=>b.toString(16).padStart(2,'0')).join('');rows.push({key,value,sha256});};
    await add('start',{idempotencyKey:f.startKey,captureClientId:f.captureClientId});await add('binding',{attemptId:f.attemptId,paperVersionId:f.paperVersionId});
    for(const q of f.questions)await add('question:'+q.ordinal,{idempotencyKey:q.questionKey,captureClientId:f.captureClientId,attemptId:f.attemptId,paperVersionId:f.paperVersionId,ordinal:q.ordinal,endKey:q.endKey});
    const db=await new Promise<IDBDatabase>((resolve,reject)=>{const r=indexedDB.open('prepared-interview-journey-v1',1);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
    try{await new Promise<void>((resolve,reject)=>{const tx=db.transaction('commands','readwrite',{durability:'strict'});tx.oncomplete=()=>resolve();tx.onabort=()=>reject(tx.error);for(const row of rows)tx.objectStore('commands').add(row);});}finally{db.close();}
    const q=f.questions[0],s=f.firstManifest.segments[0],scope={assessmentId:f.assessmentId,attemptId:f.attemptId,answerId:q.answerId},audio=new PreparedAudioJournal(owner);
    try{await audio.begin(scope,{id:s.id,ordinal:s.ordinal,mimeType:s.mimeType,startedAt:s.startedAt});await audio.append(scope,s.id,0,new Blob([new Uint8Array([1,2,3,4,5])],{type:s.mimeType}));await audio.seal(scope,s.id,1,s.endedAt);await audio.prepareAnswerEnd(scope,{captureClientId:f.captureClientId,idempotencyKey:q.endKey});}finally{audio.close();}
    const uploads=new PreparedUploadJournal(owner,f.assessmentId);try{await uploads.track({...scope,captureClientId:f.captureClientId,ordinal:1});}finally{uploads.close();}
  },fixture);
  const path=`/dev-api/backend/prepared-interviews/${assessment}/attempts/${attempt}/answers/${answer}`,writes:string[]=[],newCapture:string[]=[];
  page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname===path+'/upload-bundles')writes.push(r.postData()!);if(r.method()==='POST'&&/\/(start|end)$/.test(new URL(r.url()).pathname))newCapture.push(r.url());});
  await page.goto(`/prepared-interview/${assessment}/preflight`);const window=page.getByTestId('upload-window');await expect(window).toContainText('等待教师批准补传');expect(writes).toEqual([]);
  const before=await page.request.get(path+`/upload-window?captureClientId=${fixture.captureClientId}`);expect(before.status()).toBe(200);const original=(await before.json()).data;
  // Delay one genuine original response until after the real teacher approval.
  // No response body/success is invented. Reconnect events while that read is
  // active must be coalesced and followed by a fresh authorized read.
  let held=false,releaseHeld!:()=>void;const heldRead=new Promise<void>(resolve=>{releaseHeld=resolve;});
  const windowRoute=`**${path}/upload-window?*`;
  await page.route(windowRoute,async route=>{
    if(held){await route.continue();return;}
    const response=await route.fetch();expect(response.status()).toBe(200);expect((await response.json()).data.phase).toBe('AWAITING_APPROVAL');held=true;
    await heldRead;await route.fulfill({response});
  });
  try{
  await page.evaluate(()=>globalThis.dispatchEvent(new Event('online')));await expect.poll(()=>held).toBe(true);
  await page.context().setOffline(true);await expect(page.getByTestId('upload-status')).toContainText('网络中断');
  const teacher=await browser.newContext({baseURL:env.E2E_BASE_URL});let approved:any;
  try{const login=await teacher.request.post('/dev-api/login',{headers:{Origin:env.E2E_BASE_URL!},data:{account:env.PREPARED_STUDENT_SUPPLEMENT_TEACHER,password:env.PREPARED_TEST_LOGIN_PASSWORD}});expect(login.status()).toBe(200);
    const teacherPath=`/dev-api/backend/prepared-reviews/${assessment}/students/${student}/attempts/${attempt}/answers/${answer}/supplement`,status=await teacher.request.get(teacherPath);expect(status.status()).toBe(200);const state=(await status.json()).data;
    const response=await teacher.request.post(teacherPath,{headers:{Origin:env.E2E_BASE_URL!},data:{idempotencyKey:await page.evaluate(()=>crypto.randomUUID()),expectedRevision:state.approvalRevision,expectedAttemptRevision:state.attemptRevision,manifestSha256:state.manifestSha256,uploadDeadline:new Date(Date.parse(state.serverNow)+3600000).toISOString(),reason:'真实学生浏览器恢复验证，只补传原内容'}});expect(response.status()).toBe(200);approved=(await response.json()).data;
  }finally{await teacher.close();}
  expect(writes).toEqual([]);await page.context().setOffline(false);await page.evaluate(()=>{for(let n=0;n<3;n++)globalThis.dispatchEvent(new Event('online'));});releaseHeld();
  await expect(window).toContainText(approved.uploadDeadline);await expect(window).toContainText('批准仅用于上传原回答，不增加录音时间');
  await expect.poll(()=>writes.length).toBeGreaterThan(0);const originalBody=writes[0];await expect(page.getByTestId('upload-status')).toContainText('已核对 0 片');await expect(page.getByText('整场已可靠提交',{exact:false})).toHaveCount(0);expect(newCapture).toEqual([]);
  const prepare=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===path+'/upload-bundles');await page.reload();const retry=await prepare;expect(retry.status()).toBe(503);expect(retry.request().postData()).toBe(originalBody);
  await expect(window).toContainText(approved.uploadDeadline);await expect(page.getByRole('button',{name:'恢复当前题采集',exact:true})).toHaveCount(0);expect(newCapture).toEqual([]);
  const after=await page.request.get(path+`/upload-window?captureClientId=${fixture.captureClientId}`);expect(after.status()).toBe(200);const current=(await after.json()).data;expect(current.answerDeadline).toBe(original.answerDeadline);expect(current.automaticDeadline).toBe(original.automaticDeadline);expect(current.manifestSha256).toBe(original.manifestSha256);expect(current.approvalRevision).toBe(1);
  await page.evaluate(async f=>{const name='/src/prepared/localAudioJournal.ts',manifestName='/src/prepared/answerEndManifest.ts',{PreparedAudioJournal}=await import(/* @vite-ignore */name),{encodeAnswerEndManifest}=await import(/* @vite-ignore */manifestName),audio=new PreparedAudioJournal({origin:location.origin,studentId:f.studentId,deploymentId:'prepared-local'});
    try{const a={assessmentId:f.assessmentId,attemptId:f.attemptId,answerId:f.firstAnswerId},end=await audio.recoverAnswerEnd(a);if(encodeAnswerEndManifest(end.manifest)!==encodeAnswerEndManifest(f.firstManifest))throw new Error('Original manifest must remain exact');const bytes=await audio.readUploadWindow(a,f.firstManifest.segments[0].id,[0]);if(Array.from(new Uint8Array(await bytes.chunks[0].blob.arrayBuffer())).join(',')!=='1,2,3,4,5')throw new Error('Original local bytes changed');}finally{audio.close();}
  },fixture);
  await page.screenshot({path:`../.runtime/student-supplement-${assessment}.png`,fullPage:true});
  }finally{releaseHeld();await page.unrouteAll({behavior:'wait'});}
});
