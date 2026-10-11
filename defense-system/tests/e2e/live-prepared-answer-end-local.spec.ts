import {expect,test,type BrowserContext,type Page} from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env||{},password=env.PREPARED_TEST_LOGIN_PASSWORD;
let state:Awaited<ReturnType<BrowserContext['storageState']>>,owner:{studentId:number;deploymentId:string};
type Scope={assessmentId:number;attemptId:string;answerId:string};
type Ids={idempotencyKey:string;captureClientId:string};
test.use({launchOptions:{args:['--autoplay-policy=no-user-gesture-required']}});
test.describe('native whole-answer end preservation — synthetic audio SDK, not formal interview or cloud acceptance',()=>{
  test.beforeAll(async({browser})=>{
    if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!password)throw new Error('Independent local prepared fixture required');
    const context=await browser.newContext({baseURL:env.E2E_BASE_URL}),page=await context.newPage();
    try{await page.goto('/login');await page.getByPlaceholder('请输入账号').fill('prepared_browser_student');await page.getByPlaceholder('请输入密码').fill(password);await page.getByRole('button',{name:/立即登录/}).click();await expect(page).toHaveURL(/studentDashboard$/);owner=await currentOwner(page);state=await context.storageState();}finally{await context.close();}
  });
  test.beforeEach(async({context})=>{await context.addCookies(state.cookies);await context.addInitScript(origins=>{for(const item of origins.find(x=>x.origin===location.origin)?.localStorage??[])localStorage.setItem(item.name,item.value);},state.origins);});

  test('offline end preparation and refresh replay retain exact original ids, timestamps, bytes and final tone',async({page,context})=>{
    await capture(page);const {scope,ids}=await savedScope(page);const before=await evidence(page,scope);await context.setOffline(true);
    const original=await prepare(page,scope,ids);expect(original).toMatchObject({...ids,manifest:{schemaVersion:1}});expect(JSON.stringify(original)).not.toMatch(/serverSaved|objectKey|url|blob|namespace/);
    await context.setOffline(false);await page.reload();await restoreContext(page);
    const recovered=await recover(page,scope);expect(recovered).toEqual(original);expect(await evidence(page,scope)).toEqual(before);expect(await devices(page)).toEqual({recorders:0,devices:0});
    expect(before.duration).toBeGreaterThan(1.5);expect(before.firstHz).toBeCloseTo(440,-1);expect(before.lastHz).toBeCloseTo(880,-1);
  });
  test('interrupted original answer stays interrupted and an end intent blocks a new recovery segment after reload',async({page})=>{
    await capture(page,'DEVICE_ENDED');const {scope,ids}=await savedScope(page);const original=await prepare(page,scope,ids),before=await evidence(page,scope);
    expect(original.manifest.segments[0]).toMatchObject({state:'INTERRUPTED',interruption:'DEVICE_ENDED'});
    await page.reload();await restoreContext(page);expect(await recover(page,scope)).toEqual(original);
    const rejected=await page.evaluate(async scope=>{const f=(window as any).__endFixture;try{await f.journal.begin(scope,{id:crypto.randomUUID(),ordinal:2,mimeType:'audio/webm',startedAt:Date.now()});return {accepted:true};}catch(e){return {code:(e as any).code};}},scope);
    expect(rejected).toEqual({code:'CONFLICT'});expect(await evidence(page,scope)).toEqual(before);expect(await devices(page)).toEqual({recorders:0,devices:0});
  });
  test('refresh during recording cannot invent a segment ending or whole-answer command from saved prefix bytes',async({page})=>{
    await capture(page,'OPEN');const {scope,ids}=await savedScope(page),before=await evidence(page,scope,false);expect(before.state).toBe('OPEN');
    expect(await prepare(page,scope,ids)).toEqual({code:'ANOTHER_WRITER'});
    await page.reload();await restoreContext(page);expect(await recover(page,scope)).toEqual({code:'RECOVERY_REQUIRED'});
    expect(await prepare(page,scope,ids)).toEqual({code:'RECOVERY_REQUIRED'});const after=await evidence(page,scope,false);expect(after.hashes.slice(0,before.hashes.length)).toEqual(before.hashes);expect(after.endedAt).toBeNull();expect(await devices(page)).toEqual({recorders:0,devices:0});
  });
  test('another tab cannot seal an answer while original capture owns the assessment writer; after stop it uses original bytes',async({page,context})=>{
    await capture(page,'OPEN');const {scope,ids}=await savedScope(page),second=await context.newPage();await second.goto('/studentDashboard');await restoreContext(second);
    expect(await prepare(second,scope,ids)).toEqual({code:'ANOTHER_WRITER'});
    const stopped=await page.evaluate(async()=>{const f=(window as any).__endFixture;return f.capture.stop();});expect(stopped.phase).toBe('SEALED');
    const original=await prepare(second,scope,ids);expect(await recover(page,scope)).toEqual(original);expect(await evidence(second,scope)).toEqual(await evidence(page,scope));expect(await devices(second)).toEqual({recorders:0,devices:0});await second.close();
  });
  test('native transaction abort preserves audio and original retry; a lost local response is recovered without new recording',async({page})=>{
    await capture(page);const {scope,ids}=await savedScope(page),before=await evidence(page,scope);
    await page.evaluate(()=>{const native=IDBDatabase.prototype.transaction;let injected=false;IDBDatabase.prototype.transaction=function(...args:any[]){const tx=(native as any).apply(this,args);if(!injected&&args[1]==='readwrite'){injected=true;IDBDatabase.prototype.transaction=native;queueMicrotask(()=>tx.abort());}return tx;};});
    expect(await prepare(page,scope,ids)).toEqual({code:'LOCAL_SAVE_FAILED'});expect(await recover(page,scope)).toEqual({code:'RECOVERY_REQUIRED'});expect(await evidence(page,scope)).toEqual(before);
    // Invoke persistence, deliberately discard its return value, then reload as if acknowledgement was lost.
    await page.evaluate(async({scope,ids})=>{const f=(window as any).__endFixture;await f.Capture.prepareEnding({journal:f.journal,scope,...ids});},{scope,ids});
    await page.reload();await restoreContext(page);const recovered=await recover(page,scope);expect(recovered).toMatchObject(ids);expect(await prepare(page,scope,ids)).toEqual(recovered);expect(await evidence(page,scope)).toEqual(before);expect(await devices(page)).toEqual({recorders:0,devices:0});
  });
  test('a different local account cannot read original answer or ending, and restoring the same account does not delete it',async({page})=>{
    await capture(page);const {scope,ids}=await savedScope(page),original=await prepare(page,scope,ids),before=await evidence(page,scope);
    const isolated=await page.evaluate(async({scope,identity})=>{const f=(window as any).__endFixture,other=new f.Journal({...identity,origin:location.origin,studentId:identity.studentId+1});try{await other.recoverAnswerEnd(scope);return {accepted:true};}catch(e){return {code:(e as any).code};}finally{other.close();}},{scope,identity:owner});
    expect(isolated).toEqual({code:'NOT_FOUND'});expect(await recover(page,scope)).toEqual(original);expect(await evidence(page,scope)).toEqual(before);
  });
});
async function currentOwner(page:Page){const response=await page.request.get('/dev-api/backend/prepared-interviews/8/preflight-context');expect(response.status()).toBe(200);const data=(await response.json()).data;expect(data.eligibility.phase).toBe('PAPER_NOT_PUBLISHED');return {studentId:data.studentId as number,deploymentId:data.deploymentId as string};}
async function modules(page:Page){const response=await page.request.get('/src/prepared/answerCapture.ts');expect(response.status()).toBe(200);const path=(await response.text()).match(/from ["']([^"']*localAudioJournal\.ts[^"']*)["']/)?.[1];expect(path).toMatch(/^\/src\/prepared\/localAudioJournal\.ts(?:\?t=\d+)?$/);return path!;}
async function capture(page:Page,fault?:'DEVICE_ENDED'|'OPEN'){
  await page.goto('/studentDashboard');expect(await currentOwner(page)).toEqual(owner);const journalPath=await modules(page);
  await page.evaluate(async({identity,journalPath})=>{
    const capturePath='/src/prepared/answerCapture.ts';
    const {PreparedAudioJournal:Journal}=await import(journalPath),{PreparedAnswerCapture:Capture}=await import(capturePath);
    const journal=new Journal({...identity,origin:location.origin}),scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()},ids={idempotencyKey:crypto.randomUUID(),captureClientId:crypto.randomUUID()};
    // Random local SDK scopes are not server attempts, question authorization or submission receipts.
    const audio=new AudioContext();await audio.resume();const dest=audio.createMediaStreamDestination(),osc=audio.createOscillator();osc.frequency.value=440;osc.connect(dest);
    const capture=await Capture.start({journal,scope,ordinal:1,stream:dest.stream});osc.start();(window as any).__endFixture={journal,scope,ids,Journal,Capture,audio,osc,capture,at:audio.currentTime,recorders:1,devices:0};
  },{identity:owner,journalPath});
  await page.waitForFunction(()=>{const f=(window as any).__endFixture;return f.audio.currentTime-f.at>=1.2&&f.capture.snapshot().savedChunks>=1;});
  if(fault==='OPEN')return;
  await page.evaluate(()=>{const f=(window as any).__endFixture;f.osc.frequency.value=880;f.lastAt=f.audio.currentTime;});
  await page.waitForFunction(()=>{const f=(window as any).__endFixture;return f.audio.currentTime-f.lastAt>=0.6;});
  const stopped=await page.evaluate(async fault=>{const f=(window as any).__endFixture;return fault?f.capture.interrupt(fault):f.capture.stop();},fault);
  expect(stopped.phase).toBe(fault?'INTERRUPTED':'SEALED');expect(stopped.queuedChunks).toBe(0);
}
async function restoreContext(page:Page){expect(await currentOwner(page)).toEqual(owner);const journalPath=await modules(page);
  await page.evaluate(async({identity,journalPath})=>{
    const capturePath='/src/prepared/answerCapture.ts';
    const {PreparedAudioJournal:Journal}=await import(journalPath),{PreparedAnswerCapture:Capture}=await import(capturePath);
    const f:any={journal:new Journal({...identity,origin:location.origin}),Journal,Capture,audio:new AudioContext(),recorders:0,devices:0};
    const Native=MediaRecorder;(window as any).MediaRecorder=class extends Native{constructor(stream:MediaStream,options:MediaRecorderOptions){f.recorders++;super(stream,options);}};
    const native=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);navigator.mediaDevices.getUserMedia=constraints=>{f.devices++;return native(constraints);};(window as any).__endFixture=f;
  },{identity:owner,journalPath});
}
async function savedScope(page:Page):Promise<{scope:Scope;ids:Ids}>{return page.evaluate(()=>{const f=(window as any).__endFixture;return {scope:f.scope,ids:f.ids};});}
async function prepare(page:Page,scope:Scope,ids:Ids){return page.evaluate(async({scope,ids})=>{const f=(window as any).__endFixture;try{return await f.Capture.prepareEnding({journal:f.journal,scope,...ids});}catch(e){return {code:(e as any).code};}},{scope,ids});}
async function recover(page:Page,scope:Scope){return page.evaluate(async scope=>{const f=(window as any).__endFixture;try{return await f.Capture.recoverEnding({journal:f.journal,scope});}catch(e){return {code:(e as any).code};}},scope);}
async function devices(page:Page){return page.evaluate(()=>{const f=(window as any).__endFixture;return {recorders:f.recorders,devices:f.devices};});}
async function evidence(page:Page,scope:Scope,decode=true){return page.evaluate(async({scope,decode})=>{
  const f=(window as any).__endFixture,segment=(await f.journal.list(scope))[0],stored=await f.journal.read(scope,segment.id),bytes=await new Blob(stored.chunks.map((c:any)=>c.blob),{type:segment.mimeType}).arrayBuffer();
  const sha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
  const result={state:segment.state,startedAt:segment.startedAt,endedAt:segment.endedAt,hashes:stored.chunks.map((c:any)=>c.sha256),sha256,duration:0,firstHz:0,lastHz:0};
  if(!decode)return result;const decoded=await f.audio.decodeAudioData(bytes),values=decoded.getChannelData(0),rate=decoded.sampleRate;
  const hz=(start:number,len:number)=>{let n=0;const a=Math.floor(start*rate),b=Math.min(values.length,Math.floor((start+len)*rate));for(let i=a+1;i<b;i++)if(values[i-1]<=0&&values[i]>0)n++;return n/((b-a)/rate);};
  return {...result,duration:decoded.duration,firstHz:hz(.15,.3),lastHz:hz(decoded.duration-.35,.25)};
},{scope,decode});}
