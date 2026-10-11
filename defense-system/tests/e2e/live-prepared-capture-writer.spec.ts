import {expect,Page,test,type BrowserContext} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env||{};
const password=env.PREPARED_TEST_LOGIN_PASSWORD;
let loginState:Awaited<ReturnType<BrowserContext['storageState']>>;
let identity:{studentId:number;deploymentId:string};
test.use({launchOptions:{args:['--autoplay-policy=no-user-gesture-required']}});

test.describe('native cross-tab writer + MediaRecorder + IndexedDB — synthetic audio foundations, NOT formal exam/physical device acceptance',()=>{
  test.beforeAll(async({browser})=>{
    if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!password)throw new Error('Only independent local prepared fixtures are authorized');
    const context=await browser.newContext({baseURL:env.E2E_BASE_URL}),page=await context.newPage();
    try{
      await page.goto('/login');await page.getByPlaceholder('请输入账号').fill('prepared_browser_student');await page.getByPlaceholder('请输入密码').fill(password);
      await page.getByRole('button',{name:/立即登录/}).click();await expect(page).toHaveURL(/studentDashboard$/);
      const response=await page.request.get('/dev-api/backend/prepared-interviews/8/preflight-context');expect(response.status()).toBe(200);
      const data=(await response.json()).data;expect(data.eligibility.phase).toBe('PAPER_NOT_PUBLISHED');
      identity={studentId:data.studentId,deploymentId:data.deploymentId};loginState=await context.storageState();
    }finally{await context.close();}
  });
  test.beforeEach(async({context})=>{
    // Reuse only this fixture's actual authenticated session in memory; do not reset rate limits or persist credentials.
    await context.addCookies(loginState.cookies);
    await context.addInitScript(origins=>{for(const item of origins.find(x=>x.origin===location.origin)?.localStorage??[])localStorage.setItem(item.name,item.value);},loginState.origins);
  });
  test('second tab cannot capture another answer/attempt; durable tail sealing releases the native writer',async({page,context})=>{
    const second=await context.newPage();await open(page);await open(second);expect(await begin(page)).toMatchObject({phase:'RECORDING'});await tone(page);
    expect(await begin(second)).toMatchObject({code:'ANOTHER_WRITER',tracks:['ended'],segments:0});
    const first=await page.evaluate(async()=>{const f=(window as any).__writerFixture;f.oscillator.frequency.value=880;f.marker=f.audio.currentTime;return f.scope;});
    await page.waitForFunction(()=>(window as any).__writerFixture.audio.currentTime-(window as any).__writerFixture.marker>=0.6);
    expect(await finish(page)).toMatchObject({phase:'SEALED',queuedChunks:0,reason:null});const saved=await read(page,first);
    expect(saved).toMatchObject({state:'SEALED',firstHz:expect.any(Number)});expect(saved.duration).toBeGreaterThan(1.5);expect(saved.firstHz).toBeCloseTo(440,-1);expect(saved.lastHz).toBeCloseTo(880,-1);
    expect(await begin(second)).toMatchObject({phase:'RECORDING'});await tone(second);expect((await finish(second)).phase).toBe('SEALED');
    expect((await read(second,first)).sha256).toBe(saved.sha256);
  });
  test('native writer remains busy during stopped-but-unconfirmed save; original queue retry preserves time and sound',async({page,context})=>{
    const second=await context.newPage();await open(page);await open(second);await begin(page);await tone(page);
    await page.evaluate(()=>{
      const f=(window as any).__writerFixture;f.originalAppend=f.journal.append.bind(f.journal);let failed=false;
      f.journal.append=(...args:unknown[])=>{if(!failed){failed=true;return Promise.reject(new Error('explicit write-failure injection'));}return f.originalAppend(...args);};
    });
    await page.waitForFunction(()=>(window as any).__writerFixture.capture.snapshot().phase==='LOCAL_SAVE_FAILED');
    const failed=await finish(page);expect(failed.phase).toBe('LOCAL_SAVE_FAILED');expect(failed.queuedChunks).toBeGreaterThan(0);
    expect(await begin(second)).toMatchObject({code:'ANOTHER_WRITER',tracks:['ended'],segments:0});
    const recovered=await page.evaluate(async()=>{const f=(window as any).__writerFixture;f.journal.append=f.originalAppend;return f.capture.retrySave();});
    expect(recovered).toMatchObject({phase:'INTERRUPTED',reason:'LOCAL_WRITE_FAILED',segmentId:failed.segmentId,endedAt:failed.endedAt,startedAt:failed.startedAt,queuedChunks:0});
    expect((await read(page)).duration).toBeGreaterThan(1.7);expect(await begin(second)).toMatchObject({phase:'RECORDING'});await tone(second);await finish(second);
  });
  test('closing a holding tab releases its native lock but never resets, deletes or claims recovery of the original answer',async({page,context})=>{
    const second=await context.newPage();await open(page);await open(second);await begin(page);await tone(page);
    const original=await page.evaluate(async()=>{const f=(window as any).__writerFixture;return {scope:f.scope,stored:await f.journal.read(f.scope,f.capture.snapshot().segmentId)};});
    expect(original.stored.segment.state).toBe('OPEN');expect(original.stored.chunks.length).toBeGreaterThan(0);
    const originalHashes=original.stored.chunks.map((c:{sha256:string})=>c.sha256);await page.close();
    // A fresh synthetic SDK fixture only. No formal start/resume/grant is sent, and the original OPEN segment is not overwritten.
    expect(await begin(second)).toMatchObject({phase:'RECORDING'});await tone(second);await finish(second);
    const retained=await second.evaluate(async({scope,id})=>{const f=(window as any).__writerFixture;const stored=await f.journal.read(scope,id);return {state:stored.segment.state,hashes:stored.chunks.map((c:any)=>c.sha256)};},{scope:original.scope,id:original.stored.segment.id});
    expect(retained.state).toBe('OPEN');expect(retained.hashes.slice(0,originalHashes.length)).toEqual(originalHashes);
  });
});
async function open(page:Page){await page.goto('/studentDashboard');await expect(page).toHaveURL(/studentDashboard$/);}
async function begin(page:Page){return page.evaluate(async(owner)=>{
  const old=(window as any).__writerFixture;if(old){if(old.capture)old.oscillator.stop();if(old.audio.state!=='closed')await old.audio.close();old.journal.close();}
  const journalPath='/src/prepared/localAudioJournal.ts',capturePath='/src/prepared/answerCapture.ts';
  const {PreparedAudioJournal}=await import(journalPath),{PreparedAnswerCapture}=await import(capturePath);
  const journal=new PreparedAudioJournal({origin:location.origin,...owner});
  const scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};
  const audio=new AudioContext();await audio.resume();const destination=audio.createMediaStreamDestination(),oscillator=audio.createOscillator();
  oscillator.frequency.value=440;oscillator.connect(destination);const stream=destination.stream;
  const f:any={journal,scope,audio,oscillator,stream};(window as any).__writerFixture=f;
  try{f.capture=await PreparedAnswerCapture.start({journal,scope,ordinal:1,stream});oscillator.start();f.toneAt=audio.currentTime;return f.capture.snapshot();}
  catch(error){oscillator.disconnect();await audio.close();return {code:(error as any).code,tracks:stream.getTracks().map(t=>t.readyState),segments:(await journal.list(scope)).length};}
},identity);}
async function tone(page:Page){await page.waitForFunction(()=>{const f=(window as any).__writerFixture;return f.audio.currentTime-f.toneAt>=1.2&&f.capture.snapshot().savedChunks>=1;});}
async function finish(page:Page){return page.evaluate(async()=>{const f=(window as any).__writerFixture;return f.capture.stop();});}
async function read(page:Page,scope?:any){return page.evaluate(async(requested)=>{
  const f=(window as any).__writerFixture,s=requested??f.scope,segment=(await f.journal.list(s))[0],stored=await f.journal.read(s,segment.id);
  const blob=new Blob(stored.chunks.map((c:any)=>c.blob),{type:segment.mimeType});const bytes=await blob.arrayBuffer();
  const sha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
  const decoded=await f.audio.decodeAudioData(bytes),values=decoded.getChannelData(0),rate=decoded.sampleRate;
  const frequency=(start:number,length:number)=>{let crossings=0;const begin=Math.floor(start*rate),end=Math.min(values.length,Math.floor((start+length)*rate));for(let i=begin+1;i<end;i++)if(values[i-1]<=0&&values[i]>0)crossings++;return crossings/((end-begin)/rate);};
  return {state:segment.state,sha256,duration:decoded.duration,firstHz:frequency(0.15,0.3),lastHz:frequency(decoded.duration-0.35,0.25)};
},scope);}
