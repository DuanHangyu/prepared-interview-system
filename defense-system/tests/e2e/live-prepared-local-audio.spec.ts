import { expect, Page, test, type BrowserContext } from '@playwright/test';
const env=(globalThis as typeof globalThis & {process?:{env?:Record<string,string|undefined>}}).process?.env||{};
const password=env.PREPARED_TEST_LOGIN_PASSWORD;
let state:Awaited<ReturnType<BrowserContext['storageState']>>,studentId:number;

test.describe('native IndexedDB journal — synthetic bytes, NOT physical-device or full interview acceptance',()=>{
  test.beforeAll(async({browser})=>{
    if(env.E2E_BASE_URL!=='http://127.0.0.1:13015'||!password)throw new Error('Only the independent prepared local browser environment is authorized');
    const context=await browser.newContext({baseURL:env.E2E_BASE_URL}),page=await context.newPage();
    try {
      await page.goto('/login');await page.getByPlaceholder('请输入账号').fill('prepared_browser_student');await page.getByPlaceholder('请输入密码').fill(password);
      const response=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/login'));
      await page.getByRole('button',{name:/立即登录/}).click();const logged=await response;
      // Clear disposable test password even when login is rejected, before any failure artifacts are captured.
      if(logged.status()!==200){await page.getByPlaceholder('请输入密码').fill('');throw new Error('Native-journal fixture login was rejected; do not disable account rate protection');}
      await expect(page).toHaveURL(/studentDashboard$/);const detail=await page.request.get('/dev-api/user-detail');expect(detail.status()).toBe(200);
      const owner=(await detail.json()).data;expect(owner.role).toBe('STUDENT');studentId=Number(owner.id);state=await context.storageState();
    }finally{await context.close();}
  });
  test.beforeEach(async({context})=>{
    await context.addCookies(state.cookies);await context.addInitScript(origins=>{for(const item of origins.find(o=>o.origin===location.origin)?.localStorage??[])localStorage.setItem(item.name,item.value);},state.origins);
  });
  test('committed ordered bytes and sealing survive page reload without resetting original metadata',async({page})=>{
    const studentId=await login(page);const ids={attemptId:crypto.randomUUID(),answerId:crypto.randomUUID(),segmentId:crypto.randomUUID()};
    const before=await page.evaluate(async({studentId,ids})=>{
      const modulePath='/src/prepared/localAudioJournal.ts';const {PreparedAudioJournal}=await import(modulePath);
      const journal=new PreparedAudioJournal({origin:location.origin,studentId,deploymentId:'prepared-local'});
      const scope={assessmentId:8,attemptId:ids.attemptId,answerId:ids.answerId};
      const segment={id:ids.segmentId,ordinal:1,mimeType:'audio/webm;codecs=opus',startedAt:1000};
      const mutable={...segment};const starting=journal.begin(scope,mutable);
      mutable.id=crypto.randomUUID();mutable.ordinal=2;mutable.startedAt=999999;mutable.mimeType='video/webm';
      const captured=await starting;if(captured.id!==segment.id||captured.startedAt!==segment.startedAt)throw new Error('Original segment metadata was not retained');
      await journal.append(scope,segment.id,0,new Blob(['synthetic prefix'],{type:segment.mimeType}));
      await journal.append(scope,segment.id,1,new Blob(['synthetic tail'],{type:segment.mimeType}));
      const sealed=await journal.seal(scope,segment.id,2,2000);journal.close();return sealed;
    },{studentId,ids});
    await page.reload();
    const after=await page.evaluate(async({studentId,ids})=>{
      const modulePath='/src/prepared/localAudioJournal.ts';const {PreparedAudioJournal}=await import(modulePath);
      const journal=new PreparedAudioJournal({origin:location.origin,studentId,deploymentId:'prepared-local'});
      const scope={assessmentId:8,attemptId:ids.attemptId,answerId:ids.answerId};
      const segment=(await journal.list(scope))[0];const text=await (await journal.playable(scope,ids.segmentId)).text();journal.close();return {segment,text};
    },{studentId,ids});
    expect(after.segment).toEqual(before);expect(after.text).toBe('synthetic prefixsynthetic tail');
    expect(before).toMatchObject({state:'SEALED',chunkCount:2,startedAt:1000,endedAt:2000});
  });
  test('same-sequence conflicts and owner/deployment/attempt namespaces never overwrite prior bytes',async({page})=>{
    const studentId=await login(page);
    const result=await page.evaluate(async(studentId)=>{
      const modulePath='/src/prepared/localAudioJournal.ts';const {PreparedAudioJournal}=await import(modulePath);
      const identity={origin:location.origin,studentId,deploymentId:'prepared-local'};
      const journal=new PreparedAudioJournal(identity);const scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};
      const segment={id:crypto.randomUUID(),ordinal:1,mimeType:'audio/webm;codecs=opus',startedAt:1000};
      await journal.begin(scope,segment);await journal.append(scope,segment.id,0,new Blob(['original synthetic bytes'],{type:segment.mimeType}));
      let conflict='';try{await journal.append(scope,segment.id,0,new Blob(['different bytes'],{type:segment.mimeType}));}catch(error){conflict=(error as {code:string}).code;}
      await journal.seal(scope,segment.id,1,2000);
      const otherOwner=new PreparedAudioJournal({...identity,studentId:studentId+100000});
      const otherDeployment=new PreparedAudioJournal({...identity,deploymentId:'prepared-other-test'});
      const ownerCount=(await otherOwner.list(scope)).length, deploymentCount=(await otherDeployment.list(scope)).length;
      const otherAttemptCount=(await journal.list({...scope,attemptId:crypto.randomUUID()})).length;
      const text=await (await journal.playable(scope,segment.id)).text();journal.close();otherOwner.close();otherDeployment.close();
      return {conflict,ownerCount,deploymentCount,otherAttemptCount,text};
    },studentId);
    expect(result).toEqual({conflict:'CONFLICT',ownerCount:0,deploymentCount:0,otherAttemptCount:0,text:'original synthetic bytes'});
  });
  test('real browser transaction abort preserves committed prefix and cannot falsely seal a missing tail',async({page})=>{
    const studentId=await login(page);
    const result=await page.evaluate(async(studentId)=>{
      const modulePath='/src/prepared/localAudioJournal.ts';const {PreparedAudioJournal}=await import(modulePath);
      const journal=new PreparedAudioJournal({origin:location.origin,studentId,deploymentId:'prepared-local'});
      const scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};
      const segment={id:crypto.randomUUID(),ordinal:1,mimeType:'audio/webm;codecs=opus',startedAt:1000};
      await journal.begin(scope,segment);await journal.append(scope,segment.id,0,new Blob(['durable synthetic prefix'],{type:segment.mimeType}));
      const original=IDBDatabase.prototype.transaction;let writeError='',sealError='';
      IDBDatabase.prototype.transaction=new Proxy(original,{apply(target,receiver,args){const tx=Reflect.apply(target,receiver,args) as IDBTransaction;if(args[1]==='readwrite')queueMicrotask(()=>tx.abort());return tx;}});
      try{await journal.append(scope,segment.id,1,new Blob(['uncommitted tail'],{type:segment.mimeType}));}catch(error){writeError=(error as {code:string}).code;}finally{IDBDatabase.prototype.transaction=original;}
      try{await journal.seal(scope,segment.id,2,2000);}catch(error){sealError=(error as {code:string}).code;}
      const stored=await journal.read(scope,segment.id),text=await stored.chunks[0].blob.text();journal.close();
      return {writeError,sealError,count:stored.segment.chunkCount,state:stored.segment.state,text};
    },studentId);
    expect(result).toEqual({writeError:'WRITE_FAILED',sealError:'CONFLICT',count:1,state:'OPEN',text:'durable synthetic prefix'});
  });
  test('native bounded missing-chunk reads stay independent of recording and preserve exact bytes after offline and reload',async({page,context})=>{
    const studentId=await login(page),ids={attemptId:crypto.randomUUID(),answerId:crypto.randomUUID(),segmentId:crypto.randomUUID()};
    const before=await page.evaluate(async({studentId,ids})=>{
      const modulePath='/src/prepared/localAudioJournal.ts';const {PreparedAudioJournal}=await import(modulePath);
      const journal=new PreparedAudioJournal({origin:location.origin,studentId,deploymentId:'prepared-local'});
      const scope={assessmentId:8,attemptId:ids.attemptId,answerId:ids.answerId};
      await journal.begin(scope,{id:ids.segmentId,ordinal:1,mimeType:'audio/webm',startedAt:1000});
      for(let index=0;index<4;index++)await journal.append(scope,ids.segmentId,index,new Blob([`synthetic-original-${index}`],{type:'audio/webm'}));
      (window as any).__uploadReadFixture={journal,scope};const stored=await journal.readUploadWindow(scope,ids.segmentId,[1,3]);
      return {segment:stored.segment,hashes:stored.chunks.map((c:any)=>c.sha256),text:await new Blob(stored.chunks.map((c:any)=>c.blob)).text()};
    },{studentId,ids});
    expect(before.segment).toMatchObject({state:'OPEN',chunkCount:4,endedAt:null});await context.setOffline(true);
    const offline=await page.evaluate(async segmentId=>{
      const f=(window as any).__uploadReadFixture,original=IDBObjectStore.prototype.getAll;
      IDBObjectStore.prototype.getAll=function(){throw new Error('upload must not read the whole recording');};
      try{const stored=await f.journal.readUploadWindow(f.scope,segmentId,[1,3]);return {segment:stored.segment,hashes:stored.chunks.map((c:any)=>c.sha256),text:await new Blob(stored.chunks.map((c:any)=>c.blob)).text()};}
      finally{IDBObjectStore.prototype.getAll=original;f.journal.close();}
    },ids.segmentId);expect(offline).toEqual(before);
    await context.setOffline(false);await page.reload();
    const restored=await page.evaluate(async({studentId,ids})=>{
      const modulePath='/src/prepared/localAudioJournal.ts';const {PreparedAudioJournal}=await import(modulePath);
      const identity={origin:location.origin,studentId,deploymentId:'prepared-local'},journal=new PreparedAudioJournal(identity);
      const scope={assessmentId:8,attemptId:ids.attemptId,answerId:ids.answerId},stored=await journal.readUploadWindow(scope,ids.segmentId,[1,3]);
      const other=new PreparedAudioJournal({...identity,studentId:studentId+100000});let foreign='';
      try{await other.readUploadWindow(scope,ids.segmentId,[1]);}catch(e){foreign=(e as any).code;}finally{other.close();journal.close();}
      return {evidence:{segment:stored.segment,hashes:stored.chunks.map((c:any)=>c.sha256),text:await new Blob(stored.chunks.map((c:any)=>c.blob)).text()},foreign};
    },{studentId,ids});expect(restored.evidence).toEqual(before);expect(restored.foreign).toBe('NOT_FOUND');
  });
  test('native original upload intents survive offline and reload, cannot regroup linked chunks, and retain prior-question work',async({page,context})=>{
    const studentId=await login(page),ids={attemptId:crypto.randomUUID(),answerId:crypto.randomUUID(),segmentId:crypto.randomUUID(),captureClientId:crypto.randomUUID(),nextId:crypto.randomUUID()};
    const original=await page.evaluate(async({studentId,ids})=>{
      const ap='/src/prepared/localAudioJournal.ts',up='/src/prepared/uploadJournal.ts',bp='/src/prepared/uploadBundle.ts';
      const {PreparedAudioJournal}=await import(/* @vite-ignore */ap),{PreparedUploadJournal}=await import(/* @vite-ignore */up),{makeUploadIntent}=await import(/* @vite-ignore */bp);
      const owner={origin:location.origin,studentId,deploymentId:'prepared-local'},a={assessmentId:8,attemptId:ids.attemptId,answerId:ids.answerId,captureClientId:ids.captureClientId,ordinal:1};
      const audio=new PreparedAudioJournal(owner),uploads=new PreparedUploadJournal(owner,8);await uploads.track(a);
      await audio.begin(a,{id:ids.segmentId,ordinal:1,mimeType:'audio/webm',startedAt:1000});
      for(let n=0;n<3;n++)await audio.append(a,ids.segmentId,n,new Blob([`original-${n}`],{type:'audio/webm'}));
      const selected=await audio.readUploadWindow(a,ids.segmentId,[0,1]),i=await makeUploadIntent(studentId,a,selected.segment,selected.chunks);
      await uploads.reserve(i);await uploads.track({...a,answerId:ids.nextId,ordinal:2});audio.close();uploads.close();return i;
    },{studentId,ids});
    await context.setOffline(true);
    const offline=await page.evaluate(async({studentId,ids})=>{const path='/src/prepared/uploadJournal.ts',{PreparedUploadJournal}=await import(/* @vite-ignore */path),j=new PreparedUploadJournal({origin:location.origin,studentId,deploymentId:'prepared-local'},8);
      try{const a=await j.answers(ids.attemptId);return {count:a.length,intents:await j.intents(a[0])};}finally{j.close();}},{studentId,ids});
    expect(offline).toEqual({count:2,intents:[original]});await context.setOffline(false);await page.reload();
    const reopened=await page.evaluate(async({studentId,ids})=>{
      const ap='/src/prepared/localAudioJournal.ts',up='/src/prepared/uploadJournal.ts',bp='/src/prepared/uploadBundle.ts';
      const {PreparedAudioJournal}=await import(/* @vite-ignore */ap),{PreparedUploadJournal}=await import(/* @vite-ignore */up),{makeUploadIntent}=await import(/* @vite-ignore */bp);
      const owner={origin:location.origin,studentId,deploymentId:'prepared-local'},uploads=new PreparedUploadJournal(owner,8),audio=new PreparedAudioJournal(owner);
      try{const a=(await uploads.answers(ids.attemptId))[0],selected=await audio.readUploadWindow(a,ids.segmentId,[1,2]);let conflict='';
        try{await uploads.reserve(await makeUploadIntent(studentId,a,selected.segment,selected.chunks));}catch(e){conflict=(e as {code:string}).code;}
        const foreign=new PreparedUploadJournal({...owner,studentId:studentId+100000},8);const count=(await foreign.answers(ids.attemptId)).length;foreign.close();
        return {conflict,foreign:count,intents:await uploads.intents(a),chunks:(await audio.list(a))[0].chunkCount,independentLock:await uploads.writerKey()!==await audio.captureWriterKey(a)};
      }finally{uploads.close();audio.close();}
    },{studentId,ids});expect(reopened).toEqual({conflict:'CONFLICT',foreign:0,intents:[original],chunks:3,independentLock:true});
  });
});
async function login(page:Page){
  await page.goto('/studentDashboard');
  await expect(page).toHaveURL(/studentDashboard$/);
  const response=await page.request.get('/dev-api/user-detail');expect(response.status()).toBe(200);
  const identity=(await response.json()).data;expect(identity.role).toBe('STUDENT');expect(Number(identity.id)).toBe(studentId);return studentId;
}
