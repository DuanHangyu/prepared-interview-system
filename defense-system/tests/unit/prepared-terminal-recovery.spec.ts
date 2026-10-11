// @vitest-environment node
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {IDBFactory} from 'fake-indexeddb';
import {PreparedAudioJournal,type AnswerScope} from '../../src/prepared/localAudioJournal';
const identity={origin:'http://127.0.0.1:13015',studentId:12,deploymentId:'prepared-local'};
let factory:IDBFactory,journal:PreparedAudioJournal,scope:AnswerScope,id:string;
const command=()=>({expectedChunks:1,endedAt:2000,reason:null});
beforeEach(async()=>{
  factory=new IDBFactory();journal=new PreparedAudioJournal(identity,factory);
  scope={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()};id=crypto.randomUUID();
  await journal.begin(scope,{id,ordinal:1,mimeType:'audio/webm;codecs=opus',startedAt:1000});
  await journal.append(scope,id,0,new Blob(['original bytes'],{type:'audio/webm;codecs=opus'}));
});
afterEach(()=>{journal.close();vi.restoreAllMocks();});
async function alter(change:(row:any)=>void){
  const db=await new Promise<IDBDatabase>((resolve,reject)=>{const request=factory.open('prepared-interview-audio-v1',1);request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});
  try{await new Promise<void>((resolve,reject)=>{const tx=db.transaction('segments','readwrite'),store=tx.objectStore('segments'),request=store.getAll();
    request.onsuccess=()=>{const row=request.result[0];change(row);store.put(row);};tx.oncomplete=()=>resolve();tx.onabort=()=>reject(tx.error);
  });}finally{db.close();}
}
describe('durable original terminal command — storage substitute, NOT server authorization or complete interview acceptance',()=>{
  it('persists the exact final command before sealing; reopening can finish without inventing a new time',async()=>{
    expect(await journal.prepareTerminal(scope,id,command())).toEqual(command());
    expect((await journal.list(scope))[0].state).toBe('OPEN');journal.close();journal=new PreparedAudioJournal(identity,factory);
    expect(await journal.recoverTerminal(scope,id)).toMatchObject({state:'SEALED',startedAt:1000,endedAt:2000,chunkCount:1});
    expect(await (await journal.playable(scope,id)).text()).toBe('original bytes');
  });
  it('same command is idempotent before/after completion; changed commands cannot overwrite the original',async()=>{
    const original=command();await journal.prepareTerminal(scope,id,original);
    for(let i=0;i<10;i++)expect(await journal.prepareTerminal(scope,id,original)).toEqual(original);
    for(const value of [{...original,endedAt:2001},{...original,expectedChunks:2},{...original,reason:'DEVICE_ENDED' as const}])
      await expect(journal.prepareTerminal(scope,id,value)).rejects.toMatchObject({code:'CONFLICT'});
    const saved=await journal.recoverTerminal(scope,id);expect(await journal.prepareTerminal(scope,id,original)).toEqual(original);expect(await journal.recoverTerminal(scope,id)).toEqual(saved);
    expect(saved).not.toHaveProperty('terminal');
  });
  it('preparing a terminal command freezes new bytes but permits identical already-stored retries',async()=>{
    await journal.prepareTerminal(scope,id,command());
    await expect(journal.append(scope,id,1,new Blob(['late new bytes'],{type:'audio/webm;codecs=opus'}))).rejects.toMatchObject({code:'CONFLICT'});
    expect(await journal.append(scope,id,0,new Blob(['original bytes'],{type:'audio/webm;codecs=opus'}))).toMatchObject({chunkCount:1});
    expect((await journal.read(scope,id)).chunks).toHaveLength(1);
  });
  it('neither direct sealing nor interruption can contradict a stored terminal command',async()=>{
    await journal.prepareTerminal(scope,id,command());
    await expect(journal.seal(scope,id,1,2001)).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.interrupt(scope,id,2000,'PAGE_REFRESH')).rejects.toMatchObject({code:'CONFLICT'});
    expect((await journal.recoverTerminal(scope,id)).endedAt).toBe(2000);
  });
  it('a persisted interruption preserves reason, bytes and original times after reopening; never becomes a complete answer',async()=>{
    await journal.prepareTerminal(scope,id,{...command(),reason:'DEVICE_ENDED'});journal.close();journal=new PreparedAudioJournal(identity,factory);
    const restored=await journal.recoverTerminal(scope,id);expect(restored).toMatchObject({state:'INTERRUPTED',interruption:'DEVICE_ENDED',endedAt:2000,chunkCount:1});
    await expect(journal.playable(scope,id)).rejects.toMatchObject({code:'CONFLICT'});
  });
  it('an OPEN prefix without durable end evidence cannot be guessed complete or erased',async()=>{
    await expect(journal.recoverTerminal(scope,id)).rejects.toMatchObject({code:'MISSING_TERMINAL'});
    expect((await journal.read(scope,id)).segment.state).toBe('OPEN');expect(await (await journal.read(scope,id)).chunks[0].blob.text()).toBe('original bytes');
  });
  it('legacy terminal records without a command remain readable and are not reset',async()=>{
    const sealed=await journal.seal(scope,id,1,2000);journal.close();journal=new PreparedAudioJournal(identity,factory);
    expect(await journal.recoverTerminal(scope,id)).toEqual(sealed);
    await expect(journal.prepareTerminal(scope,id,command())).rejects.toMatchObject({code:'CONFLICT'});
  });
  it('a sealed answer cannot be voluntarily reopened as another segment; genuine interrupted segments remain append-only',async()=>{
    await journal.seal(scope,id,1,2000);
    await expect(journal.begin(scope,{id:crypto.randomUUID(),ordinal:2,mimeType:'audio/webm;codecs=opus',startedAt:3000})).rejects.toMatchObject({code:'CONFLICT'});
  });
  it('captures scope and command before asynchronous storage work, never uses a mutated caller identity',async()=>{
    const originalScope={...scope},mutable=command(),pending=journal.prepareTerminal(scope,id,mutable);
    scope.answerId=crypto.randomUUID();mutable.endedAt=99999;
    expect(await pending).toEqual(command());expect((await journal.recoverTerminal(originalScope,id)).endedAt).toBe(2000);
    await expect(journal.recoverTerminal(scope,id)).rejects.toMatchObject({code:'NOT_FOUND'});
  });
  it('recovery also snapshots the original scope before reading and replaying',async()=>{
    await journal.prepareTerminal(scope,id,command());const originalScope={...scope},pending=journal.recoverTerminal(scope,id);scope.answerId=crypto.randomUUID();
    expect((await pending).state).toBe('SEALED');expect((await journal.read(originalScope,id)).segment.endedAt).toBe(2000);
  });
  it('another owner or deployment cannot recover the original command or bytes',async()=>{
    await journal.prepareTerminal(scope,id,command());
    for(const other of [{...identity,studentId:13},{...identity,deploymentId:'prepared-other'}]){
      const handle=new PreparedAudioJournal(other,factory);try{await expect(handle.recoverTerminal(scope,id)).rejects.toMatchObject({code:'NOT_FOUND'});}finally{handle.close();}
    }
    expect((await journal.list(scope))[0].state).toBe('OPEN');
  });
  it.each([null,{expectedChunks:0,endedAt:2000,reason:null},{expectedChunks:1,endedAt:NaN,reason:null},{expectedChunks:1,endedAt:2000,reason:'DELETE_AND_RESTART'},{expectedChunks:1,endedAt:2000}])('invalid terminal command is rejected boundedly: %j',async(input)=>{
    await expect(journal.prepareTerminal(scope,id,input as never)).rejects.toMatchObject({code:'INVALID'});expect((await journal.list(scope))[0].state).toBe('OPEN');
  });
  it('missing tail, missing segment, backward time and closed handles cannot be treated as durable ending',async()=>{
    await expect(journal.prepareTerminal(scope,id,{...command(),expectedChunks:2})).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.prepareTerminal(scope,crypto.randomUUID(),command())).rejects.toMatchObject({code:'NOT_FOUND'});
    await expect(journal.prepareTerminal(scope,id,{...command(),endedAt:999})).rejects.toMatchObject({code:'INVALID'});
    journal.close();await expect(journal.recoverTerminal(scope,id)).rejects.toMatchObject({code:'CLOSED'});
  });
  it('corrupt terminal evidence is not replayed as successful recovery',async()=>{
    await journal.prepareTerminal(scope,id,command());await alter(row=>{row.terminal={expectedChunks:1,endedAt:99999,reason:'not-valid'};});
    await expect(journal.recoverTerminal(scope,id)).rejects.toMatchObject({code:'INTEGRITY'});expect((await journal.list(scope))[0].state).toBe('OPEN');
  });
  it('zero-byte fault evidence is recoverable only as an interruption',async()=>{
    const empty=crypto.randomUUID(),emptyScope={...scope,answerId:crypto.randomUUID()};await journal.begin(emptyScope,{id:empty,ordinal:1,mimeType:'audio/webm',startedAt:1000});
    await journal.prepareTerminal(emptyScope,empty,{expectedChunks:0,endedAt:2000,reason:'CAPTURE_ERROR'});
    expect(await journal.recoverTerminal(emptyScope,empty)).toMatchObject({state:'INTERRUPTED',chunkCount:0,interruption:'CAPTURE_ERROR'});
  });
});
