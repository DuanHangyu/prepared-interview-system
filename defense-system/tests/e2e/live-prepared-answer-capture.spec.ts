import {expect,Page,test} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env||{};
const password=env.PREPARED_TEST_LOGIN_PASSWORD;
test.use({launchOptions:{args:['--autoplay-policy=no-user-gesture-required']}});

test.describe('native MediaRecorder + IndexedDB — synthetic WebAudio source, NOT physical microphone or interview acceptance',()=>{
  test.beforeAll(()=>{if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!password)throw new Error('Only the independent local prepared browser environment is authorized');});
  test('offline capture preserves a changed final tone and waits for actual tail encoding before sealing',async({page,context})=>{
    await boot(page);await firstTone(page);
    await context.setOffline(true);
    await page.evaluate(()=>{const f=fixture();f.marker=f.audioContext.currentTime;f.oscillator.frequency.value=880;});
    // A defined test stimulus, not an arbitrary wait for an application response.
    await page.waitForFunction(()=>fixture().audioContext.currentTime-fixture().marker>=0.6);
    const result=await page.evaluate(async()=>{const f=fixture();return f.capture.stop();});
    expect(result).toMatchObject({phase:'SEALED',queuedChunks:0,reason:null});expect(result.savedChunks).toBeGreaterThanOrEqual(2);
    const decoded=await decode(page);expect(decoded.duration).toBeGreaterThan(1.5);expect(decoded.firstHz).toBeCloseTo(440,-1);expect(decoded.lastHz).toBeCloseTo(880,-1);
    await context.setOffline(false);await dispose(page);
  });
  test('actual stream end retains encoded evidence and is marked interrupted, never a complete answer',async({page})=>{
    await boot(page);await firstTone(page);
    await page.evaluate(()=>fixture().stream.getAudioTracks()[0].stop());
    await page.waitForFunction(()=>fixture().capture.snapshot().phase==='INTERRUPTED');
    const result=await page.evaluate(()=>fixture().capture.stop());expect(result).toMatchObject({phase:'INTERRUPTED',reason:'DEVICE_ENDED',queuedChunks:0});
    const decoded=await decode(page);expect(decoded.duration).toBeGreaterThan(0.8);expect(decoded.firstHz).toBeCloseTo(440,-1);
    await dispose(page);
  });
  test('injected write failure preserves original native-encoded queue; retry saves it without a new recorder',async({page})=>{
    await boot(page);await firstTone(page);const savedBefore=await page.evaluate(()=>fixture().capture.snapshot().savedChunks);
    await page.evaluate(()=>{
      const f=fixture();f.originalAppend=f.journal.append.bind(f.journal);let failed=false;
      f.journal.append=(...args:unknown[])=>{if(!failed){failed=true;return Promise.reject(new Error('injected local write failure'));}return f.originalAppend(...args);};
    });
    await page.waitForFunction(()=>fixture().capture.snapshot().phase==='LOCAL_SAVE_FAILED');
    const before=await page.evaluate(()=>fixture().capture.stop());expect(before.savedChunks).toBe(savedBefore);expect(before.queuedChunks).toBeGreaterThan(0);
    const after=await page.evaluate(async()=>{const f=fixture();f.journal.append=f.originalAppend;return f.capture.retrySave();});
    expect(after).toMatchObject({phase:'INTERRUPTED',reason:'LOCAL_WRITE_FAILED',queuedChunks:0,segmentId:before.segmentId,startedAt:before.startedAt,endedAt:before.endedAt});
    expect(after.savedChunks).toBeGreaterThan(before.savedChunks);expect((await decode(page)).duration).toBeGreaterThan(1.7);
    await dispose(page);
  });
});

// These functions also execute inside the page; repeat the typed accessor there rather than depending on a Node closure.
function fixture():any{return (window as any).__preparedCaptureTest;}
async function boot(page:Page){
  await page.goto('/login');await page.getByPlaceholder('请输入账号').fill('prepared_browser_student');
  await page.getByPlaceholder('请输入密码').fill(password||'');await page.getByRole('button',{name:/立即登录/}).click();await expect(page).toHaveURL(/studentDashboard$/);
  const profile=await page.request.get('/dev-api/user-detail');expect(profile.status()).toBe(200);const identity=(await profile.json()).data;expect(identity.role).toBe('STUDENT');
  // Define the accessor in the page because Playwright serializes functions without their Node lexical scope.
  await page.evaluate(()=>{(window as any).fixture=()=> (window as any).__preparedCaptureTest;});
  await page.evaluate(async(studentId)=>{
    const journalPath='/src/prepared/localAudioJournal.ts',capturePath='/src/prepared/answerCapture.ts';
    const {PreparedAudioJournal}=await import(journalPath),{PreparedAnswerCapture}=await import(capturePath);
    const journal=new PreparedAudioJournal({origin:location.origin,studentId,deploymentId:'prepared-local'});
    const scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};
    const audioContext=new AudioContext();await audioContext.resume();const destination=audioContext.createMediaStreamDestination();
    const oscillator=audioContext.createOscillator();oscillator.frequency.value=440;oscillator.connect(destination);
    const stream=destination.stream;const capture=await PreparedAnswerCapture.start({journal,scope,ordinal:1,stream});
    oscillator.start();const firstToneAt=audioContext.currentTime;
    (window as any).__preparedCaptureTest={journal,scope,audioContext,oscillator,stream,capture,firstToneAt};
  },Number(identity.id));
}
async function firstTone(page:Page){
  // Test 1.2 seconds of a known tone AFTER native encoder start, not a possibly header-only first Blob.
  await page.waitForFunction(()=>fixture().audioContext.currentTime-fixture().firstToneAt>=1.2&&fixture().capture.snapshot().savedChunks>=1);
}
async function decode(page:Page){return page.evaluate(async()=>{
  const f=fixture();const stored=await f.journal.read(f.scope,f.capture.snapshot().segmentId);
  const blob=new Blob(stored.chunks.map((c:any)=>c.blob),{type:stored.segment.mimeType});
  const decoded=await f.audioContext.decodeAudioData(await blob.arrayBuffer());const values=decoded.getChannelData(0),rate=decoded.sampleRate;
  function frequency(from:number,length:number){let crossings=0;const begin=Math.floor(from*rate),end=Math.min(values.length,Math.floor((from+length)*rate));
    for(let index=begin+1;index<end;index++)if(values[index-1]<=0&&values[index]>0)crossings++;return crossings/((end-begin)/rate);}
  return {duration:decoded.duration,firstHz:frequency(0.15,0.3),lastHz:frequency(decoded.duration-0.35,0.25),byteSize:blob.size,chunkCount:stored.chunks.length};
});}
async function dispose(page:Page){await page.evaluate(async()=>{const f=fixture();f.oscillator.stop();await f.audioContext.close();f.journal.close();delete (window as any).__preparedCaptureTest;delete (window as any).fixture;});}
