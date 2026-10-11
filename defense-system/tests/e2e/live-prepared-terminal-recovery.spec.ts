import {expect,test,type BrowserContext,type Page} from '@playwright/test';

const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env||{};
const password=env.PREPARED_TEST_LOGIN_PASSWORD;
let loginState:Awaited<ReturnType<BrowserContext['storageState']>>;
let owner:{studentId:number;deploymentId:string};
type Scope={assessmentId:number;attemptId:string;answerId:string};
test.use({launchOptions:{args:['--autoplay-policy=no-user-gesture-required']}});

test.describe('native saved terminal replay after refresh — synthetic audio foundation, NOT formal interview or physical device acceptance',()=>{
  test.beforeAll(async({browser})=>{
    if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!password)throw new Error('Only independent local prepared fixtures are authorized');
    const context=await browser.newContext({baseURL:env.E2E_BASE_URL}),page=await context.newPage();
    try{
      await page.goto('/login');await page.getByPlaceholder('请输入账号').fill('prepared_browser_student');
      await page.getByPlaceholder('请输入密码').fill(password);await page.getByRole('button',{name:/立即登录/}).click();
      await expect(page).toHaveURL(/studentDashboard$/);owner=await currentOwner(page);loginState=await context.storageState();
    }finally{await context.close();}
  });
  test.beforeEach(async({context})=>{
    await context.addCookies(loginState.cookies);
    await context.addInitScript(origins=>{for(const item of origins.find(x=>x.origin===location.origin)?.localStorage??[])localStorage.setItem(item.name,item.value);},loginState.origins);
  });

  test('durable original stop command survives refresh; replay keeps all bytes, original time and final tone without a new recorder',async({page})=>{
    await start(page);await firstTone(page);await lastTone(page);await failMetadata(page,'seal');
    const failed=await stop(page);expect(failed.phase).toBe('LOCAL_SAVE_FAILED');expect(failed.queuedChunks).toBe(0);
    const scope=await localScope(page),before=await evidence(page,scope);expect(before.state).toBe('OPEN');expect(before.lastHz).toBeCloseTo(880,-1);
    await page.reload();await initializeRecovery(page);
    const recovered=await recover(page,scope,failed.segmentId);
    expect(recovered).toMatchObject({phase:'SEALED',segmentId:failed.segmentId,startedAt:failed.startedAt,endedAt:failed.endedAt,reason:null,queuedChunks:0});
    const after=await evidence(page,scope);expect(after).toMatchObject({state:'SEALED',sha256:before.sha256,hashes:before.hashes,startedAt:before.startedAt,endedAt:failed.endedAt});
    expect(after.duration).toBeGreaterThan(1.5);expect(after.firstHz).toBeCloseTo(440,-1);expect(after.lastHz).toBeCloseTo(880,-1);
    expect(await deviceAccess(page)).toEqual({recorders:0,devices:0});
    expect(await recover(page,scope,failed.segmentId)).toEqual(recovered);expect((await evidence(page,scope)).sha256).toBe(before.sha256);
  });

  test('another tab cannot replay a held original terminal; refresh releases ownership without resetting the answer',async({page,context})=>{
    await start(page);await firstTone(page);await failMetadata(page,'seal');const failed=await stop(page);const scope=await localScope(page);
    const second=await context.newPage();await second.goto('/studentDashboard');await initializeRecovery(second);
    expect(await recover(second,scope,failed.segmentId)).toEqual({code:'ANOTHER_WRITER'});
    expect((await evidence(second,scope)).state).toBe('OPEN');expect(await deviceAccess(second)).toEqual({recorders:0,devices:0});
    await page.reload();await initializeRecovery(page);
    expect(await recover(second,scope,failed.segmentId)).toMatchObject({phase:'SEALED',startedAt:failed.startedAt,endedAt:failed.endedAt});
    const after=await evidence(second,scope);expect((await evidence(page,scope)).sha256).toBe(after.sha256);
  });

  test('refresh during capture keeps the durable prefix but cannot invent a complete ending or silently restart recording',async({page})=>{
    await start(page);await firstTone(page);const scope=await localScope(page),before=await evidence(page,scope,false);
    expect(before.state).toBe('OPEN');expect(before.hashes.length).toBeGreaterThan(0);expect(before.endedAt).toBeNull();
    await page.reload();await initializeRecovery(page);
    const journalResult=await page.evaluate(async({scope,id})=>{const f=(window as any).__terminalFixture;try{const segment=await f.journal.recoverTerminal(scope,id);return {state:segment.state};}catch(error){return {code:(error as any).code,name:(error as any).name};}},{scope,id:before.id});
    expect(journalResult).toEqual({code:'MISSING_TERMINAL',name:'LocalAudioError'});
    expect(await recover(page,scope,before.id)).toEqual({code:'RECOVERY_REQUIRED'});
    const retained=await evidence(page,scope,false);expect(retained.state).toBe('OPEN');expect(retained.endedAt).toBeNull();
    expect(retained.hashes.slice(0,before.hashes.length)).toEqual(before.hashes);expect(await deviceAccess(page)).toEqual({recorders:0,devices:0});
  });

  test('a durable interrupted command retains the original fault and bytes after refresh, never reports a complete answer',async({page})=>{
    await start(page);await firstTone(page);await lastTone(page);await failMetadata(page,'interrupt');
    const failed=await page.evaluate(async()=>{const f=(window as any).__terminalFixture;return f.capture.interrupt('DEVICE_ENDED');});
    expect(failed).toMatchObject({phase:'LOCAL_SAVE_FAILED',reason:'DEVICE_ENDED',queuedChunks:0});
    const scope=await localScope(page),before=await evidence(page,scope);await page.reload();await initializeRecovery(page);
    expect(await recover(page,scope,failed.segmentId)).toMatchObject({phase:'INTERRUPTED',reason:'DEVICE_ENDED',startedAt:failed.startedAt,endedAt:failed.endedAt});
    expect(await evidence(page,scope)).toMatchObject({state:'INTERRUPTED',sha256:before.sha256,hashes:before.hashes,endedAt:failed.endedAt});
    expect(await deviceAccess(page)).toEqual({recorders:0,devices:0});
  });
});

async function currentOwner(page:Page){
  const response=await page.request.get('/dev-api/backend/prepared-interviews/8/preflight-context');expect(response.status()).toBe(200);
  const data=(await response.json()).data;expect(data.eligibility.phase).toBe('PAPER_NOT_PUBLISHED');
  return {studentId:data.studentId as number,deploymentId:data.deploymentId as string};
}
async function start(page:Page){
  await page.goto('/studentDashboard');expect(await currentOwner(page)).toEqual(owner);
  const journalPath=await journalDependency(page);
  await page.evaluate(async({identity,journalPath})=>{
    const capturePath='/src/prepared/answerCapture.ts';
    const {PreparedAudioJournal}=await import(journalPath),{PreparedAnswerCapture}=await import(capturePath);
    const journal=new PreparedAudioJournal({...identity,origin:location.origin});
    // Random LOCAL SDK scopes are not server attempts, authorizations or submission receipts.
    const scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};
    const audio=new AudioContext();await audio.resume();const destination=audio.createMediaStreamDestination(),oscillator=audio.createOscillator();
    oscillator.frequency.value=440;oscillator.connect(destination);
    const capture=await PreparedAnswerCapture.start({journal,scope,ordinal:1,stream:destination.stream});
    oscillator.start();(window as any).__terminalFixture={journal,scope,audio,oscillator,capture,toneAt:audio.currentTime};
  },{identity:owner,journalPath});
}
async function firstTone(page:Page){await page.waitForFunction(()=>{const f=(window as any).__terminalFixture;return f.audio.currentTime-f.toneAt>=1.2&&f.capture.snapshot().savedChunks>=1;});}
async function lastTone(page:Page){
  await page.evaluate(()=>{const f=(window as any).__terminalFixture;f.marker=f.audio.currentTime;f.oscillator.frequency.value=880;});
  await page.waitForFunction(()=>{const f=(window as any).__terminalFixture;return f.audio.currentTime-f.marker>=0.6;});
}
async function failMetadata(page:Page,method:'seal'|'interrupt'){
  await page.evaluate(method=>{(window as any).__terminalFixture.journal[method]=()=>Promise.reject(new Error('Explicit metadata failure after original tail persistence'));},method);
}
async function stop(page:Page){return page.evaluate(async()=>{const f=(window as any).__terminalFixture;return f.capture.stop();});}
async function localScope(page:Page):Promise<Scope>{return page.evaluate(()=>(window as any).__terminalFixture.scope);}
async function initializeRecovery(page:Page){
  const identity=await currentOwner(page);expect(identity).toEqual(owner);
  const journalPath=await journalDependency(page);
  await page.evaluate(async({identity,journalPath})=>{
    const {PreparedAudioJournal}=await import(journalPath);
    const f:any={journal:new PreparedAudioJournal({...identity,origin:location.origin}),audio:new AudioContext(),recorders:0,devices:0};
    const Native=MediaRecorder;
    (window as any).MediaRecorder=class extends Native {constructor(stream:MediaStream,options:MediaRecorderOptions){f.recorders++;super(stream,options);}};
    const original=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia=(constraints)=>{f.devices++;return original(constraints);};
    (window as any).__terminalFixture=f;
  },{identity,journalPath});
}
async function journalDependency(page:Page){
  // Follow the application's actual Vite graph. Importing the same source again without its HMR
  // version creates a separate Error class and is not how the production UI imports this module.
  const response=await page.request.get('/src/prepared/answerCapture.ts');expect(response.status()).toBe(200);
  const path=(await response.text()).match(/from ["']([^"']*localAudioJournal\.ts[^"']*)["']/)?.[1];
  expect(path).toMatch(/^\/src\/prepared\/localAudioJournal\.ts(?:\?t=\d+)?$/);return path!;
}
async function recover(page:Page,scope:Scope,segmentId:string){return page.evaluate(async({scope,segmentId})=>{
  const capturePath='/src/prepared/answerCapture.ts';const {PreparedAnswerCapture}=await import(capturePath),f=(window as any).__terminalFixture;
  try{return await PreparedAnswerCapture.recoverSaved({journal:f.journal,scope,segmentId});}catch(error){return {code:(error as any).code};}
},{scope,segmentId});}
async function deviceAccess(page:Page){return page.evaluate(()=>{const f=(window as any).__terminalFixture;return {recorders:f.recorders,devices:f.devices};});}
async function evidence(page:Page,scope:Scope,decode=true){return page.evaluate(async({scope,decode})=>{
  const f=(window as any).__terminalFixture,segment=(await f.journal.list(scope))[0],stored=await f.journal.read(scope,segment.id);
  const bytes=await new Blob(stored.chunks.map((c:any)=>c.blob),{type:segment.mimeType}).arrayBuffer();
  const sha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
  const result={id:segment.id,state:segment.state,startedAt:segment.startedAt,endedAt:segment.endedAt,sha256,hashes:stored.chunks.map((c:any)=>c.sha256),duration:0,firstHz:0,lastHz:0};
  if(!decode)return result;const decoded=await f.audio.decodeAudioData(bytes),values=decoded.getChannelData(0),rate=decoded.sampleRate;
  const frequency=(start:number,length:number)=>{let crossings=0;const begin=Math.floor(start*rate),end=Math.min(values.length,Math.floor((start+length)*rate));for(let i=begin+1;i<end;i++)if(values[i-1]<=0&&values[i]>0)crossings++;return crossings/((end-begin)/rate);};
  return {...result,duration:decoded.duration,firstHz:frequency(0.15,0.3),lastHz:frequency(decoded.duration-0.35,0.25)};
},{scope,decode});}
