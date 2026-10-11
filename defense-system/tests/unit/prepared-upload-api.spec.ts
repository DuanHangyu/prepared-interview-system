// @vitest-environment node
import {afterEach,it,expect,vi} from 'vitest';
import {PreparedUploadTransport} from '@/api/preparedUpload';
const answer={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID(),captureClientId:crypto.randomUUID(),ordinal:1};
const intent={answer,bundleId:'a'.repeat(64),commandSha256:'b'.repeat(64),command:{captureClientId:answer.captureClientId,segmentId:crypto.randomUUID(),segmentOrdinal:1,mimeType:'audio/webm',chunks:[{index:0,byteSize:1,sha256:'c'.repeat(64)}],byteSize:1,sha256:'c'.repeat(64)}};
afterEach(()=>vi.unstubAllGlobals());
it('queries only the original client window without caller identity or a write command',async()=>{
  const fetcher=vi.fn(async()=>new Response('{"code":200,"data":null}',{headers:{'content-type':'application/json'}}));vi.stubGlobal('fetch',fetcher);
  const claimed={...answer,studentId:999};await new PreparedUploadTransport().window(claimed,new AbortController().signal);
  expect((fetcher.mock.calls[0] as any)[0]).toBe(`/dev-api/backend/prepared-interviews/8/attempts/${answer.attemptId}/answers/${answer.answerId}/upload-window?captureClientId=${answer.captureClientId}`);
  expect((fetcher.mock.calls[0] as any)[1]).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store'});expect((fetcher.mock.calls[0] as any)[1].body).toBeUndefined();
});
it('recognizes only a bounded exact 409 expiry marker, never translated messages or arbitrary conflict payloads',async()=>{
  const fetcher=vi.fn();vi.stubGlobal('fetch',fetcher);const t=new PreparedUploadTransport(),signal=new AbortController().signal;
  fetcher.mockResolvedValueOnce(new Response('{"code":409,"data":{"reasonCode":"UPLOAD_WINDOW_EXPIRED"}}',{status:409,headers:{'content-type':'application/json'}}));
  await expect(t.prepare(intent,signal)).rejects.toMatchObject({status:409,reasonCode:'UPLOAD_WINDOW_EXPIRED'});
  for(const [status,body,type] of [[409,'{"code":409,"message":"原回答补传窗口已结束"}','application/json'],[409,'{"code":409,"data":{"reasonCode":"OTHER_CONFLICT"}}','application/json'],[409,'{"code":200,"data":{"reasonCode":"UPLOAD_WINDOW_EXPIRED"}}','application/json'],[409,'{"code":409,"data":{"reasonCode":"UPLOAD_WINDOW_EXPIRED","mayRecord":true}}','application/json'],[403,'{"code":409,"data":{"reasonCode":"UPLOAD_WINDOW_EXPIRED"}}','application/json'],[409,'not json','application/json'],[409,'{"code":409,"data":{"reasonCode":"UPLOAD_WINDOW_EXPIRED"}}','text/html'],[409,'x'.repeat(65537),'application/json']] as const){
    fetcher.mockResolvedValueOnce(new Response(body,{status,headers:{'content-type':type}}));await expect(t.prepare(intent,signal)).rejects.toMatchObject({status,reasonCode:undefined});
  }
  expect(fetcher).toHaveBeenCalledTimes(9);
});
it('queries or replays an immutable final command without media, caller identity or guessed success',async()=>{
  const fetcher=vi.fn(async()=>new Response('{"code":200,"data":null}',{headers:{'content-type':'application/json'}}));vi.stubGlobal('fetch',fetcher);const t=new PreparedUploadTransport(),signal=new AbortController().signal;
  const command={idempotencyKey:crypto.randomUUID(),expectedRevision:3,studentId:999};await t.submissionStatus(8,answer.attemptId,signal);await t.submitAttempt(8,answer.attemptId,command,signal);
  expect((fetcher.mock.calls[0] as any)[0]).toBe(`/dev-api/backend/prepared-interviews/8/attempts/${answer.attemptId}/submission`);
  expect(JSON.parse((fetcher.mock.calls[1] as any)[1].body)).toEqual({idempotencyKey:command.idempotencyKey,expectedRevision:3});expect((fetcher.mock.calls[1] as any)[1]).toMatchObject({credentials:'same-origin',redirect:'error',cache:'no-store'});
  for(const call of [()=>t.submissionStatus(0,answer.attemptId,signal),()=>t.submissionStatus(8,'../foreign',signal),()=>t.submitAttempt(8,answer.attemptId,{...command,idempotencyKey:'new'},signal),()=>t.submitAttempt(8,answer.attemptId,{...command,expectedRevision:-1},signal)])expect(call).toThrow();expect(fetcher).toHaveBeenCalledTimes(2);
});
it('uses quiet same-origin bounded JSON/binary requests without redirects, global notifications, identities or replacement keys',async()=>{
  const fetcher=vi.fn(async()=>new Response(JSON.stringify({code:200,data:{confirmed:false}}),{headers:{'content-type':'application/json'}}));vi.stubGlobal('fetch',fetcher);
  const t=new PreparedUploadTransport(),signal=new AbortController().signal;
  await t.prepare(intent,signal);await t.upload(intent,new Blob(['x']),signal);await t.status(intent,signal);
  expect(fetcher.mock.calls.map((v:any)=>v[1].method)).toEqual(['POST','PUT','GET']);
  for(const call of fetcher.mock.calls as any[]){expect(call[0]).toMatch(/^\/dev-api\/backend\/prepared-interviews\/8\//);expect(call[1]).toMatchObject({credentials:'same-origin',redirect:'error',cache:'no-store'});}
  expect(JSON.parse((fetcher.mock.calls[0] as any)[1].body)).toEqual(intent.command);
  expect((fetcher.mock.calls[1] as any)[1].body).toBeInstanceOf(Blob);
});
it('distinguishes auth, busy and malformed replies and honors retry-after without auto-retrying requests',async()=>{
  const fetcher=vi.fn(async()=>new Response('{"code":503}',{status:503,headers:{'retry-after':'12','content-type':'application/json'}}));vi.stubGlobal('fetch',fetcher);
  const t=new PreparedUploadTransport(),signal=new AbortController().signal;
  await expect(t.prepare(intent,signal)).rejects.toMatchObject({status:503,retryAfter:12});expect(fetcher).toHaveBeenCalledTimes(1);
  fetcher.mockResolvedValueOnce(new Response('<html>bad gateway</html>'));await expect(t.status(intent,signal)).rejects.toMatchObject({status:502});
  fetcher.mockResolvedValueOnce(new Response('{"code":401}',{status:401}));await expect(t.status(intent,signal)).rejects.toMatchObject({status:401});
});
it('freshly checks actual role, student, deployment and assessment without using global notifications or caller IDs',async()=>{
  vi.stubGlobal('location',{origin:'http://127.0.0.1:13015'});
  const json=(data:unknown)=>new Response(JSON.stringify({code:200,data}),{headers:{'content-type':'application/json'}});
  const context={studentId:12,deploymentId:'prepared-local',eligibility:{assessmentId:8},workflowEnabled:true};
  const fetcher=vi.fn().mockResolvedValueOnce(json({id:'12',role:'STUDENT'})).mockResolvedValueOnce(json(context));vi.stubGlobal('fetch',fetcher);
  const t=new PreparedUploadTransport(),signal=new AbortController().signal;
  expect(await t.authority(8,signal)).toEqual({origin:location.origin,studentId:12,deploymentId:'prepared-local',workflowEnabled:true});
  for(const user of [{id:12,role:'TEACHER'},null,{id:'bad',role:'STUDENT'}]){fetcher.mockResolvedValueOnce(json(user));await expect(t.authority(8,signal)).rejects.toMatchObject({status:401});}
  for(const bad of [{...context,studentId:13},{...context,deploymentId:''},{...context,eligibility:{assessmentId:9}},null]){
    fetcher.mockResolvedValueOnce(json({id:12,role:'STUDENT'})).mockResolvedValueOnce(json(bad));await expect(t.authority(8,signal)).rejects.toMatchObject({status:401});}
  fetcher.mockResolvedValueOnce(json({id:12,role:'STUDENT'})).mockResolvedValueOnce(json({...context,workflowEnabled:undefined}));expect((await t.authority(8,signal)).workflowEnabled).toBe(false);
  await expect(t.authority(0,signal)).rejects.toThrow();
});
it('bounds actual reply bytes and rejects malformed envelopes, false success and unconfirmed network timeouts',async()=>{
  const fetcher=vi.fn();vi.stubGlobal('fetch',fetcher);const t=new PreparedUploadTransport(),signal=new AbortController().signal;
  for(const body of ['x'.repeat(65537),'not json','null','{"code":200}','{"code":503}','{"code":200,"data":null}']){
    fetcher.mockResolvedValueOnce(new Response(body,{headers:{'content-type':'application/json'}}));
    if(body.includes('data'))expect(await t.status(intent,signal)).toBeNull();else await expect(t.status(intent,signal)).rejects.toMatchObject({status:body==='{"code":503}'?503:502});
  }
  fetcher.mockRejectedValueOnce(new Error('vendor details never shown'));await expect(t.status(intent,signal)).rejects.toMatchObject({status:0});
  vi.useFakeTimers();fetcher.mockImplementationOnce((_url,options)=>new Promise((_,reject)=>options.signal.addEventListener('abort',()=>reject(new Error('aborted')),{once:true})));
  const work=t.status(intent,signal),checked=expect(work).rejects.toMatchObject({status:0});await vi.advanceTimersByTimeAsync(10000);await checked;vi.useRealTimers();
  const cancelled=new AbortController();cancelled.abort();fetcher.mockResolvedValueOnce(new Response('{"code":200,"data":null}',{headers:{'content-type':'application/json'}}));await expect(t.status(intent,cancelled.signal)).rejects.toMatchObject({status:0});
});
it('uses original bound media GET/POST without uploading bytes, caller identity or a new recording key',async()=>{
  const fetcher=vi.fn(async()=>new Response('{"code":200,"data":null}',{headers:{'content-type':'application/json'}}));vi.stubGlobal('fetch',fetcher);
  const t=new PreparedUploadTransport(),signal=new AbortController().signal;
  await t.mediaStatus(answer,signal);await t.queueMedia(answer,'a'.repeat(64),signal);
  expect(fetcher.mock.calls.map((v:any)=>v[1].method)).toEqual(['GET','POST']);
  expect((fetcher.mock.calls[0] as any)[0]).toBe(`/dev-api/backend/prepared-interviews/8/attempts/${answer.attemptId}/answers/${answer.answerId}/media`);
  expect(JSON.parse((fetcher.mock.calls[1] as any)[1].body)).toEqual({captureClientId:answer.captureClientId,manifestSha256:'a'.repeat(64)});
  expect(()=>t.queueMedia(answer,'bad',signal)).toThrow();expect(fetcher).toHaveBeenCalledTimes(2);
});
