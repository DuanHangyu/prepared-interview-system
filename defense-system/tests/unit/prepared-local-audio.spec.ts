// @vitest-environment node
import { IDBFactory, IDBDatabase } from 'fake-indexeddb';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PreparedAudioJournal, type AnswerScope, type SegmentInput } from '@/prepared/localAudioJournal';

const origin='http://127.0.0.1:13015';
const identity={origin,studentId:12,deploymentId:'prepared-local'};
const scope:AnswerScope={assessmentId:8,attemptId:'00000000-0000-4000-8000-000000000001',answerId:'00000000-0000-4000-8000-000000000002'};
const first:SegmentInput={id:'00000000-0000-4000-8000-000000000003',ordinal:1,mimeType:'audio/webm;codecs=opus',startedAt:1000};
const data=(text='first',type=first.mimeType)=>new Blob([text],{type});
let factory:IDBFactory, journal:PreparedAudioJournal;
beforeEach(()=>{factory=new IDBFactory();journal=new PreparedAudioJournal(identity,factory);});
async function rawDatabase(){return new Promise<IDBDatabase>((resolve,reject)=>{const request=factory.open('prepared-interview-audio-v1',1);request.onsuccess=()=>resolve(request.result as IDBDatabase);request.onerror=()=>reject(request.error);});}
async function alter(store:string,change:(store:IDBObjectStore)=>void){const db=await rawDatabase();try{await new Promise<void>((resolve,reject)=>{const tx=db.transaction(store,'readwrite');tx.oncomplete=()=>resolve();tx.onabort=()=>reject(tx.error);change(tx.objectStore(store));});}finally{db.close();}}

describe('durable local audio journal — storage substitute, not microphone acceptance',()=>{
  async function interrupted(){
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data('original prefix'));
    await journal.prepareTerminal(scope,first.id,{expectedChunks:1,endedAt:2000,reason:'DEVICE_ENDED'});
    await journal.interrupt(scope,first.id,2000,'DEVICE_ENDED');
  }
  it('verifies only a fault continuation, using bounded original byte reads without changing any prior evidence',async()=>{
    await interrupted();const before=await journal.read(scope,first.id),windows=vi.spyOn(journal,'readUploadWindow');
    await expect(journal.verifyContinuation(scope,2,2100)).resolves.toBeUndefined();
    expect(windows).toHaveBeenCalledWith(scope,first.id,[0]);expect(await journal.read(scope,first.id)).toEqual(before);
    await expect(journal.verifyContinuation(scope,3,2100)).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.verifyContinuation(scope,2,1999)).rejects.toMatchObject({code:'CONFLICT'});
    for(const ordinal of [1,1001,NaN])await expect(journal.verifyContinuation(scope,ordinal,2100)).rejects.toMatchObject({code:'INVALID'});
  });
  it.each(['OPEN','SEALED','legacy interruption'] as const)('does not turn %s into permission to resume recording',async state=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data());
    if(state==='SEALED')await journal.seal(scope,first.id,1,2000);
    if(state==='legacy interruption')await journal.interrupt(scope,first.id,2000,'PAGE_REFRESH');
    const before=await journal.list(scope);
    await expect(journal.verifyContinuation(scope,2,2100)).rejects.toMatchObject({code:'CONFLICT'});
    expect(await journal.list(scope)).toEqual(before);
  });
  it('a fixed whole-answer ending forbids continuation even if its last segment was interrupted',async()=>{
    await interrupted();expect(await journal.hasAnswerEnd(scope)).toBe(false);const command=await journal.prepareAnswerEnd(scope,{idempotencyKey:crypto.randomUUID(),captureClientId:crypto.randomUUID()});
    expect(await journal.hasAnswerEnd(scope)).toBe(true);
    const reads=vi.spyOn(journal,'readUploadWindow');await expect(journal.verifyContinuation(scope,2,2100)).rejects.toMatchObject({code:'CONFLICT'});
    expect(reads).not.toHaveBeenCalled();expect(await journal.recoverAnswerEnd(scope)).toEqual(command);
  });
  it('damaged original bytes or terminal proof cannot be bypassed by a fresh recording segment',async()=>{
    await interrupted();const namespace=JSON.stringify([1,identity.deploymentId,origin,identity.studentId,scope.assessmentId,scope.attemptId,scope.answerId]);
    await alter('chunks',store=>{const request=store.get([namespace,first.id,0]);request.onsuccess=()=>store.put({...request.result,sha256:'f'.repeat(64)});});
    await expect(journal.verifyContinuation(scope,2,2100)).rejects.toMatchObject({code:'INTEGRITY'});
    await alter('segments',store=>{const request=store.get([namespace,first.id]);request.onsuccess=()=>store.put({...request.result,terminal:{expectedChunks:2,endedAt:2000,reason:'DEVICE_ENDED'}});});
    const reads=vi.spyOn(journal,'readUploadWindow');await expect(journal.verifyContinuation(scope,2,2100)).rejects.toMatchObject({code:'INTEGRITY'});expect(reads).not.toHaveBeenCalled();
  });
  it.each([
    {ordinal:2},{mimeType:'video/webm'},{startedAt:-1},{startedAt:2001},{endedAt:null},{chunkCount:-1},{byteSize:-1},
    {terminal:{expectedChunks:1,endedAt:2001,reason:'DEVICE_ENDED'}},{terminal:{expectedChunks:1,endedAt:2000,reason:null}},
    {terminal:{expectedChunks:1,endedAt:2000,reason:'DELETE'}},
  ])('rejects damaged fault metadata before reading any media: %j',async patch=>{
    await interrupted();const namespace=JSON.stringify([1,identity.deploymentId,origin,identity.studentId,scope.assessmentId,scope.attemptId,scope.answerId]);
    await alter('segments',store=>{const request=store.get([namespace,first.id]);request.onsuccess=()=>store.put({...request.result,...patch});});
    const reads=vi.spyOn(journal,'readUploadWindow');await expect(journal.verifyContinuation(scope,2,2100)).rejects.toMatchObject({code:'INTEGRITY'});expect(reads).not.toHaveBeenCalled();
  });
  it.each([{byteSize:67108864},{chunkCount:10000,terminal:{expectedChunks:10000,endedAt:2000,reason:'DEVICE_ENDED'}}])('enforces original whole-answer capacity before starting another segment: %j',async patch=>{
    await interrupted();const namespace=JSON.stringify([1,identity.deploymentId,origin,identity.studentId,scope.assessmentId,scope.attemptId,scope.answerId]);
    await alter('segments',store=>{const request=store.get([namespace,first.id]);request.onsuccess=()=>store.put({...request.result,...patch});});
    await expect(journal.verifyContinuation(scope,2,2100)).rejects.toMatchObject({code:'LIMIT'});
  });
  it('supports a reliable empty fault but never invents evidence for an unknown scope or disposable trial',async()=>{
    await journal.begin(scope,first);await journal.prepareTerminal(scope,first.id,{expectedChunks:0,endedAt:2000,reason:'CAPTURE_ERROR'});await journal.interrupt(scope,first.id,2000,'CAPTURE_ERROR');
    await expect(journal.verifyContinuation(scope,2,2100)).resolves.toBeUndefined();
    await expect(journal.verifyContinuation({...scope,answerId:crypto.randomUUID()},2,2100)).rejects.toMatchObject({code:'CONFLICT'});
    const trial=new PreparedAudioJournal(identity,factory,'preflight');await expect(trial.verifyContinuation(scope,2,2100)).rejects.toMatchObject({code:'INVALID'});trial.close();
  });
  it('keeps the original scope immutable and rejects metadata changes during a bounded prefix read',async()=>{
    await interrupted();const mutable={...scope},real=journal.readUploadWindow.bind(journal);
    const spy=vi.spyOn(journal,'readUploadWindow').mockImplementationOnce(async(...args)=>{mutable.answerId=crypto.randomUUID();return real(...args);});
    await expect(journal.verifyContinuation(mutable,2,2100)).resolves.toBeUndefined();expect(spy).toHaveBeenCalledWith(scope,first.id,[0]);
    spy.mockImplementationOnce(async(...args)=>{const value=await real(...args);return {...value,segment:{...value.segment,endedAt:2100}};});
    await expect(journal.verifyContinuation(scope,2,2100)).rejects.toMatchObject({code:'CONFLICT'});
  });
  it('does not deliver continuation permission when original terminal metadata changes after byte verification',async()=>{
    await interrupted();const namespace=JSON.stringify([1,identity.deploymentId,origin,identity.studentId,scope.assessmentId,scope.attemptId,scope.answerId]),real=journal.readUploadWindow.bind(journal);
    vi.spyOn(journal,'readUploadWindow').mockImplementationOnce(async(...args)=>{const value=await real(...args);
      await alter('segments',store=>{const request=store.get([namespace,first.id]);request.onsuccess=()=>store.put({...request.result,endedAt:2001,terminal:{expectedChunks:1,endedAt:2001,reason:'DEVICE_ENDED'}});});return value;});
    await expect(journal.verifyContinuation(scope,2,2100)).rejects.toMatchObject({code:'CONFLICT'});expect((await journal.list(scope))).toHaveLength(1);
  });
  it('preflight scratch is a separate database and clearing it never changes a formal answer',async()=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data('formal original'));await journal.seal(scope,first.id,1,2000);
    const trial=new PreparedAudioJournal(identity,factory,'preflight');
    expect((await trial.begin(scope,first)).state).toBe('OPEN');await trial.append(scope,first.id,0,data('trial only'));await trial.seal(scope,first.id,1,2000);
    expect(await (await trial.playable(scope,first.id)).text()).toBe('trial only');
    await trial.discardPreflight(scope);expect(await trial.list(scope)).toEqual([]);
    expect(await (await journal.playable(scope,first.id)).text()).toBe('formal original');trial.close();
  });
  it('formal handles have no accepted scratch-deletion command and invalid purposes are rejected',async()=>{
    await expect(journal.discardPreflight(scope)).rejects.toMatchObject({code:'INVALID'});
    expect(()=>new PreparedAudioJournal(identity,factory,'legacy' as never)).toThrowError(expect.objectContaining({code:'INVALID'}));
  });
  it('scratch cleanup removes only the original account and exact trial scope',async()=>{
    const trial=new PreparedAudioJournal(identity,factory,'preflight'),other=new PreparedAudioJournal({...identity,studentId:13},factory,'preflight');
    const otherScope={...scope,answerId:crypto.randomUUID()};
    for(const [handle,target] of [[trial,scope],[trial,otherScope],[other,scope]] as const){await handle.begin(target,first);await handle.append(target,first.id,0,data());}
    await trial.discardPreflight(scope);expect(await trial.list(scope)).toEqual([]);
    expect((await trial.read(otherScope,first.id)).chunks).toHaveLength(1);expect((await other.read(scope,first.id)).chunks).toHaveLength(1);
    trial.close();other.close();
  });
  it('commits each contiguous chunk before reporting saved and verifies sealed bytes after reopening',async()=>{
    expect((await journal.begin(scope,first)).chunkCount).toBe(0);
    const one=await journal.append(scope,first.id,0,data('first'));
    const two=await journal.append(scope,first.id,1,data('tail'));
    expect(one).toMatchObject({index:0,byteSize:5,chunkCount:1});expect(two.chunkCount).toBe(2);
    const sealed=await journal.seal(scope,first.id,2,2500);expect(sealed.state).toBe('SEALED');
    journal.close();journal=new PreparedAudioJournal(identity,factory);
    expect((await journal.read(scope,first.id)).chunks.map(c=>c.index)).toEqual([0,1]);
    expect(await (await journal.playable(scope,first.id)).text()).toBe('firsttail');
    expect((await journal.list(scope))[0]).toMatchObject({id:first.id,state:'SEALED',byteSize:9,chunkCount:2});
  });
  it('same-key identical retries return original evidence without resetting timestamps or counts',async()=>{
    await journal.begin(scope,first);const accepted=await journal.append(scope,first.id,0,data());
    expect(await journal.append(scope,first.id,0,data())).toEqual(accepted);
    await journal.seal(scope,first.id,1,2000);
    expect((await journal.begin(scope,first)).state).toBe('SEALED');
    expect(await journal.append(scope,first.id,0,data())).toEqual(accepted);
    expect((await journal.seal(scope,first.id,1,2000)).endedAt).toBe(2000);
  });
  it('same sequence with different bytes or a new chunk after sealing is never overwritten',async()=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data());
    await expect(journal.append(scope,first.id,0,data('changed'))).rejects.toMatchObject({code:'CONFLICT'});
    await journal.seal(scope,first.id,1,2000);
    await expect(journal.append(scope,first.id,1,data('new answer'))).rejects.toMatchObject({code:'CONFLICT'});
    expect(await (await journal.playable(scope,first.id)).text()).toBe('first');
  });
  it('a tail still being written prevents premature sealing',async()=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data('first'));
    await expect(journal.seal(scope,first.id,2,2000)).rejects.toMatchObject({code:'CONFLICT'});
    expect((await journal.list(scope))[0].state).toBe('OPEN');
    await journal.append(scope,first.id,1,data('tail'));await journal.seal(scope,first.id,2,2000);
    expect(await (await journal.playable(scope,first.id)).text()).toBe('firsttail');
  });
  it('empty or interrupted recording is not passed off as a playable complete answer',async()=>{
    await journal.begin(scope,first);
    await expect(journal.seal(scope,first.id,0,2000)).rejects.toMatchObject({code:'CONFLICT'});
    const interrupted=await journal.interrupt(scope,first.id,2000,'DEVICE_ENDED');
    expect(interrupted).toMatchObject({state:'INTERRUPTED',chunkCount:0,interruption:'DEVICE_ENDED'});
    await expect(journal.playable(scope,first.id)).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.seal(scope,first.id,0,2000)).rejects.toMatchObject({code:'CONFLICT'});
  });
  it('a new recorder uses a new segment and never concatenates distinct containers',async()=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data());
    await journal.interrupt(scope,first.id,1500,'PAGE_REFRESH');
    const second={...first,id:crypto.randomUUID(),ordinal:2,startedAt:1600};
    await journal.begin(scope,second);await journal.append(scope,second.id,0,data('second recorder'));
    await journal.seal(scope,second.id,1,2500);
    expect(await (await journal.playable(scope,second.id)).text()).toBe('second recorder');
    expect((await journal.read(scope,first.id)).chunks).toHaveLength(1);
    expect((await journal.list(scope)).map(s=>s.state)).toEqual(['INTERRUPTED','SEALED']);
  });
  it('another owner, deployment source, assessment, attempt or answer cannot read the namespace',async()=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data());
    for(const otherIdentity of [{...identity,studentId:13},{...identity,origin:'https://prepared.example.test'},{...identity,deploymentId:'prepared-other-test'}]){
      const other=new PreparedAudioJournal(otherIdentity,factory);expect(await other.list(scope)).toEqual([]);
      await expect(other.read(scope,first.id)).rejects.toMatchObject({code:'NOT_FOUND'});other.close();
    }
    for(const other of [{...scope,assessmentId:9},{...scope,attemptId:crypto.randomUUID()},{...scope,answerId:crypto.randomUUID()}]){
      expect(await journal.list(other)).toEqual([]);
      await expect(journal.append(other,first.id,0,data())).rejects.toMatchObject({code:'NOT_FOUND'});
    }
  });
  it('only one open segment is allowed and ordinals must remain contiguous',async()=>{
    await expect(journal.begin(scope,{...first,ordinal:2})).rejects.toMatchObject({code:'CONFLICT'});
    await journal.begin(scope,first);
    await expect(journal.begin(scope,{...first,id:crypto.randomUUID(),ordinal:2})).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.begin(scope,{...first,startedAt:1001})).rejects.toMatchObject({code:'CONFLICT'});
    await journal.interrupt(scope,first.id,1500,'CAPTURE_ERROR');
    await expect(journal.begin(scope,{...first,id:crypto.randomUUID(),ordinal:3})).rejects.toMatchObject({code:'CONFLICT'});
  });
  it('concurrent creation and identical appends serialize without duplicates',async()=>{
    const [a,b]=await Promise.all([journal.begin(scope,first),journal.begin(scope,first)]);expect(a).toEqual(b);
    const [one,two]=await Promise.all([journal.append(scope,first.id,0,data()),journal.append(scope,first.id,0,data())]);
    expect(one).toEqual(two);expect((await journal.read(scope,first.id)).chunks).toHaveLength(1);
  });
  it('captures validated segment metadata before asynchronous opening even if the caller mutates its object',async()=>{
    const mutable={...first};const creating=journal.begin(scope,mutable);
    mutable.id=crypto.randomUUID();mutable.ordinal=2;mutable.startedAt=999999;mutable.mimeType='video/webm';
    expect(await creating).toMatchObject(first);expect((await journal.list(scope))[0]).toMatchObject(first);
  });
  it('null identities, scopes and inputs fail with a bounded validation error instead of raw exceptions',async()=>{
    expect(()=>new PreparedAudioJournal(null as never,factory)).toThrowError(expect.objectContaining({code:'INVALID'}));
    await expect(journal.list(null as never)).rejects.toMatchObject({code:'INVALID'});
    await expect(journal.begin(scope,null as never)).rejects.toMatchObject({code:'INVALID'});
  });
  it('out-of-order chunks and incompatible mime types preserve all existing bytes',async()=>{
    await journal.begin(scope,first);
    await expect(journal.append(scope,first.id,1,data())).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.append(scope,first.id,0,data('x','audio/mp3'))).rejects.toMatchObject({code:'CONFLICT'});
    expect((await journal.list(scope))[0].chunkCount).toBe(0);
  });
  it('closing does not erase prior data and closed handles cannot return another account data',async()=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data());journal.close();journal.close();
    await expect(journal.list(scope)).rejects.toMatchObject({code:'CLOSED'});
    await expect(journal.append(scope,first.id,1,data())).rejects.toMatchObject({code:'CLOSED'});
    const restored=new PreparedAudioJournal(identity,factory);expect((await restored.read(scope,first.id)).chunks).toHaveLength(1);restored.close();
  });
  it.each([0,-1,Number.MAX_SAFE_INTEGER,NaN,1.5])('rejects invalid authenticated owner id %s',studentId=>{
    expect(()=>new PreparedAudioJournal({...identity,studentId},factory)).toThrow();
  });
  it.each(['http://remote.example','https://prepared.test/path','https://user:secret@prepared.test','file:///tmp/test','not-an-origin'])('rejects unsafe or noncanonical source %s',value=>{
    expect(()=>new PreparedAudioJournal({...identity,origin:value},factory)).toThrow();
  });
  it.each([0,-1,1.5,2147483648])('rejects invalid assessment %s',async assessmentId=>{
    await expect(journal.begin({...scope,assessmentId},first)).rejects.toMatchObject({code:'INVALID'});
  });
  it('rejects malformed identity, timestamps, ids, ordinals, mime and chunk bounds',async()=>{
    for(const changed of [{...scope,attemptId:'invalid'},{...scope,answerId:'invalid'}])await expect(journal.list(changed)).rejects.toMatchObject({code:'INVALID'});
    for(const changed of [{...first,id:'invalid'},{...first,ordinal:0},{...first,ordinal:1001},{...first,startedAt:NaN},{...first,mimeType:'video/webm'}])
      await expect(journal.begin(scope,changed)).rejects.toMatchObject({code:'INVALID'});
    await journal.begin(scope,first);
    await expect(journal.append(scope,first.id,-1,data())).rejects.toMatchObject({code:'INVALID'});
    await expect(journal.append(scope,first.id,0,new Blob([],{type:first.mimeType}))).rejects.toMatchObject({code:'INVALID'});
    await expect(journal.append(scope,first.id,0,new Blob([new Uint8Array(2*1024*1024+1)],{type:first.mimeType}))).rejects.toMatchObject({code:'LIMIT'});
    await expect(journal.seal(scope,first.id,0,999)).rejects.toMatchObject({code:'INVALID'});
  });
  it('native transaction abort never returns saved or advances count and preserves prior prefix',async()=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data('prefix'));
    const original=IDBDatabase.prototype.transaction;
    const spy=vi.spyOn(IDBDatabase.prototype,'transaction').mockImplementation(function(this:IDBDatabase,...args:Parameters<typeof original>){const tx=original.apply(this,args);queueMicrotask(()=>tx.abort());return tx;});
    try{await expect(journal.append(scope,first.id,1,data('tail'))).rejects.toMatchObject({code:'WRITE_FAILED'});}finally{spy.mockRestore();}
    expect((await journal.read(scope,first.id)).chunks).toHaveLength(1);expect((await journal.list(scope))[0].chunkCount).toBe(1);
    await expect(journal.seal(scope,first.id,2,2000)).rejects.toMatchObject({code:'CONFLICT'});
  });
  it('successful append awaits transaction completion rather than request success',async()=>{
    await journal.begin(scope,first);let committed=false;const original=IDBDatabase.prototype.transaction;
    const spy=vi.spyOn(IDBDatabase.prototype,'transaction').mockImplementation(function(this:IDBDatabase,...args:Parameters<typeof original>){const tx=original.apply(this,args);tx.addEventListener('complete',()=>{committed=true;});return tx;});
    try{await journal.append(scope,first.id,0,data());expect(committed).toBe(true);}finally{spy.mockRestore();}
  });
  it('unavailable database and broken digest reject without exposing source errors',async()=>{
    const unavailable=new PreparedAudioJournal(identity,{open:()=>{throw new Error('private source details');}} as unknown as IDBFactory);
    await expect(unavailable.list(scope)).rejects.toMatchObject({code:'UNAVAILABLE',message:'本地录音存储不可用'});
    await journal.begin(scope,first);const spy=vi.spyOn(Blob.prototype,'arrayBuffer').mockRejectedValue(new Error('private media details'));
    try{await expect(journal.append(scope,first.id,0,data())).rejects.toMatchObject({code:'WRITE_FAILED'});}finally{spy.mockRestore();}
    expect((await journal.list(scope))[0].chunkCount).toBe(0);
  });
  it('closing during pending database opening and hashing never hands old data to a new session',async()=>{
    const opening=journal.list(scope);journal.close();await expect(opening).rejects.toMatchObject({code:'CLOSED'});
    journal=new PreparedAudioJournal(identity,factory);await journal.begin(scope,first);
    let release!:(value:ArrayBuffer)=>void;
    const spy=vi.spyOn(Blob.prototype,'arrayBuffer').mockImplementation(()=>new Promise(resolve=>{release=resolve;}));
    const writing=journal.append(scope,first.id,0,data());journal.close();release(new Uint8Array([1]).buffer);
    try{await expect(writing).rejects.toMatchObject({code:'CLOSED'});}finally{spy.mockRestore();}
  });
  it('missing records, changed final commands and interrupted retries remain explicit',async()=>{
    await expect(journal.seal(scope,first.id,1,2000)).rejects.toMatchObject({code:'NOT_FOUND'});
    await expect(journal.interrupt(scope,first.id,2000,'CAPTURE_ERROR')).rejects.toMatchObject({code:'NOT_FOUND'});
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data());
    const result=await journal.interrupt(scope,first.id,2000,'LOCAL_WRITE_FAILED');
    expect(await journal.interrupt(scope,first.id,2000,'LOCAL_WRITE_FAILED')).toEqual(result);
    await expect(journal.interrupt(scope,first.id,2001,'SESSION_CHANGED')).rejects.toMatchObject({code:'CONFLICT'});
    const next={...first,id:crypto.randomUUID(),ordinal:2,startedAt:2200};await journal.begin(scope,next);await journal.append(scope,next.id,0,data());await journal.seal(scope,next.id,1,3000);
    await expect(journal.seal(scope,next.id,1,3001)).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.interrupt(scope,next.id,3000,'DEVICE_ENDED')).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.interrupt(scope,next.id,2100,'DEVICE_ENDED')).rejects.toMatchObject({code:'INVALID'});
    await expect(journal.interrupt(scope,next.id,3000,'UNKNOWN' as never)).rejects.toMatchObject({code:'INVALID'});
  });
  it('missing or corrupted durable chunks are detected without deleting recoverable copies',async()=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,data());await journal.seal(scope,first.id,1,2000);
    await alter('chunks',store=>{const req=store.getAll();req.onsuccess=()=>store.put({...req.result[0],sha256:'a'.repeat(64)});});
    await expect(journal.read(scope,first.id)).rejects.toMatchObject({code:'INTEGRITY'});
    expect((await journal.list(scope))[0].chunkCount).toBe(1);
    await alter('chunks',store=>{const req=store.getAllKeys();req.onsuccess=()=>store.delete(req.result[0]);});
    await expect(journal.read(scope,first.id)).rejects.toMatchObject({code:'INTEGRITY'});
  });
  it('aggregate size is bounded and the refused chunk does not replace accepted bytes',async()=>{
    await journal.begin(scope,first);const part=new Blob([new Uint8Array(2*1024*1024)],{type:first.mimeType});
    for(let index=0;index<32;index++)await journal.append(scope,first.id,index,part);
    await expect(journal.append(scope,first.id,32,part)).rejects.toMatchObject({code:'LIMIT'});
    expect((await journal.list(scope))[0]).toMatchObject({chunkCount:32,byteSize:64*1024*1024});
  });
  it.each(['','legacy','prepared-Invalid','prepared-'])('rejects invalid deployment identity %s',deploymentId=>{
    expect(()=>new PreparedAudioJournal({...identity,deploymentId},factory)).toThrow();
  });
});
