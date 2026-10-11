// @vitest-environment node
// Synthetic bytes and IndexedDB substitute: not real media, cloud receipt or student workflow acceptance.
import {IDBFactory,IDBDatabase} from 'fake-indexeddb';
import {beforeEach,describe,expect,it,vi} from 'vitest';
import {PreparedAudioJournal,type AnswerScope,type SegmentInput} from '@/prepared/localAudioJournal';

const identity={origin:'http://127.0.0.1:13015',studentId:12,deploymentId:'prepared-local'};
const scope:AnswerScope={assessmentId:8,attemptId:'00000000-0000-4000-8000-000000000001',answerId:'00000000-0000-4000-8000-000000000002'};
const first:SegmentInput={id:'00000000-0000-4000-8000-000000000003',ordinal:1,mimeType:'audio/webm;codecs=opus',startedAt:1000};
const command={idempotencyKey:'00000000-0000-4000-8000-000000000004',captureClientId:'00000000-0000-4000-8000-000000000005'};
let factory:IDBFactory,journal:PreparedAudioJournal;
beforeEach(()=>{factory=new IDBFactory();journal=new PreparedAudioJournal(identity,factory);});
async function closed(fault=false){await journal.begin(scope,first);await journal.append(scope,first.id,0,new Blob(['original'],{type:first.mimeType}));await journal.append(scope,first.id,1,new Blob(['tail'],{type:first.mimeType}));if(fault)await journal.interrupt(scope,first.id,2000,'DEVICE_ENDED');else await journal.seal(scope,first.id,2,2000);}
async function raw(){return new Promise<IDBDatabase>((resolve,reject)=>{const request=factory.open('prepared-interview-audio-v1',1);request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});}
async function alter(store:string,change:(s:IDBObjectStore)=>void){const db=await raw();try{await new Promise<void>((resolve,reject)=>{const tx=db.transaction(store,'readwrite');tx.oncomplete=()=>resolve();tx.onabort=()=>reject(tx.error);change(tx.objectStore(store));});}finally{db.close();}}
async function mutateHead(change:(row:any)=>void){await alter('segments',s=>{const req=s.getAll();req.onsuccess=()=>{const row=req.result.find(r=>r.ordinal===1);change(row);s.put(row);};});}

describe('whole-answer original ending is durable metadata, never cloud saved',()=>{
  it('builds a bounded metadata-only command from verified original bytes and keeps it across reopening',async()=>{
    await closed();const ended=await journal.prepareAnswerEnd(scope,command);
    expect(ended).toMatchObject({...command,manifest:{schemaVersion:1,segments:[{...first,state:'SEALED',endedAt:2000,interruption:null,chunkCount:2,byteSize:12,chunks:[{index:0,byteSize:8},{index:1,byteSize:4}]}]}});
    expect(JSON.stringify(ended)).not.toMatch(/blob|namespace|terminal|serverSaved|url|objectKey/);
    journal.close();journal=new PreparedAudioJournal(identity,factory);
    expect(await journal.recoverAnswerEnd(scope)).toEqual(ended);
    expect(await (await journal.playable(scope,first.id)).text()).toBe('originaltail');
    expect((await journal.list(scope))[0]).not.toHaveProperty('answerEnd');
  });
  it('100 original retries preserve exactly one original command and do not mutate returned shared references',async()=>{
    await closed();const original=await journal.prepareAnswerEnd(scope,command);
    for(let i=0;i<100;i++)expect(await journal.prepareAnswerEnd(scope,command)).toEqual(original);
    original.manifest.segments[0].endedAt=999999;original.idempotencyKey=crypto.randomUUID();
    expect(await journal.recoverAnswerEnd(scope)).toMatchObject({...command,manifest:{segments:[{endedAt:2000}]}});
  });
  it('same scope with another key or capture client cannot replace the original local end intent',async()=>{
    await closed();const original=await journal.prepareAnswerEnd(scope,command);
    for(const changed of [{...command,idempotencyKey:crypto.randomUUID()},{...command,captureClientId:crypto.randomUUID()}])await expect(journal.prepareAnswerEnd(scope,changed)).rejects.toMatchObject({code:'CONFLICT'});
    expect(await journal.recoverAnswerEnd(scope)).toEqual(original);
  });
  it('freezes an explicitly ended interrupted answer against a new segment, but permits original byte retries',async()=>{
    await closed(true);const original=await journal.prepareAnswerEnd(scope,command);
    expect(original.manifest.segments[0]).toMatchObject({state:'INTERRUPTED',interruption:'DEVICE_ENDED'});
    await expect(journal.begin(scope,{...first,id:crypto.randomUUID(),ordinal:2,startedAt:2100})).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.append(scope,first.id,2,new Blob(['new answer'],{type:first.mimeType}))).rejects.toMatchObject({code:'CONFLICT'});
    expect((await journal.append(scope,first.id,1,new Blob(['tail'],{type:first.mimeType}))).index).toBe(1);
    expect(await journal.recoverAnswerEnd(scope)).toEqual(original);
  });
  it('retains closed fault evidence, empty segments and distinct recorder containers without inventing completion',async()=>{
    await journal.begin(scope,first);await journal.interrupt(scope,first.id,1500,'PAGE_REFRESH');
    const second={...first,id:crypto.randomUUID(),ordinal:2,startedAt:1600,mimeType:'audio/ogg'};
    await journal.begin(scope,second);await journal.append(scope,second.id,0,new Blob(['second recorder'],{type:second.mimeType}));await journal.interrupt(scope,second.id,1900,'CAPTURE_ERROR');
    const original=await journal.prepareAnswerEnd(scope,command);
    expect(original.manifest.segments.map(s=>[s.state,s.interruption,s.mimeType,s.chunkCount])).toEqual([['INTERRUPTED','PAGE_REFRESH',first.mimeType,0],['INTERRUPTED','CAPTURE_ERROR','audio/ogg',1]]);
    expect(await journal.recoverAnswerEnd(scope)).toEqual(original);await expect(journal.playable(scope,first.id)).rejects.toMatchObject({code:'CONFLICT'});
  });
  it('OPEN prefix, including a still-unconfirmed terminal command, cannot become a complete answer ending',async()=>{
    await journal.begin(scope,first);await journal.append(scope,first.id,0,new Blob(['prefix'],{type:first.mimeType}));
    await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'MISSING_TERMINAL'});
    await journal.prepareTerminal(scope,first.id,{expectedChunks:1,endedAt:1800,reason:null});
    await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'MISSING_TERMINAL'});
    expect((await journal.list(scope))[0]).toMatchObject({state:'OPEN',endedAt:null});
    await journal.recoverTerminal(scope,first.id);expect((await journal.prepareAnswerEnd(scope,command)).manifest.segments[0].endedAt).toBe(1800);
  });
  it('never guesses a missing ending even if the underlying segment is already sealed',async()=>{
    await closed();await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'MISSING_ANSWER_END'});
    expect((await journal.list(scope))[0].state).toBe('SEALED');
    await expect(journal.prepareAnswerEnd({...scope,answerId:crypto.randomUUID()},command)).rejects.toMatchObject({code:'NOT_FOUND'});
  });
  it('local account, deployment, source and answer isolation apply to end command recovery',async()=>{
    await closed();await journal.prepareAnswerEnd(scope,command);
    for(const otherIdentity of [{...identity,studentId:13},{...identity,deploymentId:'prepared-other'},{...identity,origin:'https://prepared.test'}]){
      const other=new PreparedAudioJournal(otherIdentity,factory);await expect(other.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'NOT_FOUND'});other.close();
    }
    for(const other of [{...scope,assessmentId:9},{...scope,attemptId:crypto.randomUUID()},{...scope,answerId:crypto.randomUUID()}])await expect(journal.recoverAnswerEnd(other)).rejects.toMatchObject({code:'NOT_FOUND'});
  });
  it('snapshots ids and scope before asynchronous access and never persists request fields supplied later',async()=>{
    await closed();const mutable={...command},target={...scope};const pending=journal.prepareAnswerEnd(target,mutable);
    mutable.captureClientId=crypto.randomUUID();mutable.idempotencyKey=crypto.randomUUID();target.answerId=crypto.randomUUID();
    expect(await pending).toMatchObject(command);expect(await journal.recoverAnswerEnd(scope)).toMatchObject(command);
  });
  it('concurrent identical requests serialize and conflicting keys cannot win by replacing a committed command',async()=>{
    await closed(true);const [one,two]=await Promise.all([journal.prepareAnswerEnd(scope,command),journal.prepareAnswerEnd(scope,command)]);expect(one).toEqual(two);
    const results=await Promise.allSettled([journal.prepareAnswerEnd(scope,command),journal.prepareAnswerEnd(scope,{...command,idempotencyKey:crypto.randomUUID()})]);
    expect(results.map(r=>r.status)).toEqual(['fulfilled','rejected']);expect(await journal.recoverAnswerEnd(scope)).toEqual(one);
  });
  it('a racing additional fault-recovery segment prevents stale manifest sealing instead of silently dropping it',async()=>{
    await closed(true);const originalRead=journal.read.bind(journal);let inserted=false;
    vi.spyOn(journal,'read').mockImplementation(async(s,id)=>{const result=await originalRead(s,id);if(!inserted){inserted=true;await journal.begin(scope,{...first,id:crypto.randomUUID(),ordinal:2,startedAt:2100});}return result;});
    await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'CONFLICT'});
    vi.restoreAllMocks();expect((await journal.list(scope)).map(s=>s.state)).toEqual(['INTERRUPTED','OPEN']);
    await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'MISSING_TERMINAL'});
  });
  it('a failed transaction cannot report a saved ending and preserves all original bytes for a same-command retry',async()=>{
    await closed();const transaction=IDBDatabase.prototype.transaction;
    const spy=vi.spyOn(IDBDatabase.prototype,'transaction').mockImplementation(function(this:IDBDatabase,...args:any[]){const tx=transaction.apply(this,args as never);if(args[1]==='readwrite')queueMicrotask(()=>tx.abort());return tx;});
    await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'WRITE_FAILED'});spy.mockRestore();
    await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'MISSING_ANSWER_END'});
    expect(await (await journal.playable(scope,first.id)).text()).toBe('originaltail');expect(await journal.prepareAnswerEnd(scope,command)).toMatchObject(command);
  });
  it('corrupted chunk bytes fail both initial ending and original ending recovery without deleting the evidence',async()=>{
    await closed();await journal.prepareAnswerEnd(scope,command);
    await alter('chunks',s=>{const r=s.getAll();r.onsuccess=()=>{const row=r.result[0];row.blob=new Blob(['replaced'],{type:first.mimeType});s.put(row);};});
    await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'INTEGRITY'});await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'INTEGRITY'});
    expect((await journal.list(scope))[0].chunkCount).toBe(2);
  });
  it.each(['idempotencyKey','captureClientId','manifest'] as const)('corrupted persisted %s fails recovery instead of minting another command',async field=>{
    await closed();await journal.prepareAnswerEnd(scope,command);await mutateHead(row=>{if(field==='manifest')row.answerEnd.manifest.segments[0].endedAt=2001;else row.answerEnd[field]='invalid';});
    await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'INTEGRITY'});expect(await (await journal.playable(scope,first.id)).text()).toBe('originaltail');
  });
  it.each(['idempotencyKey','captureClientId'] as const)('an accidentally changed but syntactically valid original %s cannot become a new request',async field=>{
    await closed();await journal.prepareAnswerEnd(scope,command);await mutateHead(row=>{row.answerEnd[field]=crypto.randomUUID();});
    await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'INTEGRITY'});await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'INTEGRITY'});
    expect(await (await journal.playable(scope,first.id)).text()).toBe('originaltail');
  });
  it('a missing or damaged local end checksum is preserved for review, never silently regenerated',async()=>{
    await closed();await journal.prepareAnswerEnd(scope,command);await mutateHead(row=>{row.answerEndSha256='a'.repeat(64);});
    await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'INTEGRITY'});
    await mutateHead(row=>{delete row.answerEndSha256;});await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'INTEGRITY'});
  });
  it.each(['byteSize','chunkCount'] as const)('rejects aggregate excessive %s before hashing any persisted media',async field=>{
    await closed(true);const second={...first,id:crypto.randomUUID(),ordinal:2,startedAt:2100};await journal.begin(scope,second);await journal.append(scope,second.id,0,new Blob(['two'],{type:first.mimeType}));await journal.seal(scope,second.id,1,2500);
    // Explicit corrupt-storage resource injection, not a claim these sizes were actually captured.
    await mutateHead(row=>{row[field]=field==='byteSize'?67108864:10000;});const read=vi.spyOn(journal,'read');
    await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'LIMIT'});await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'LIMIT'});expect(read).not.toHaveBeenCalled();
  });
  it.each([{byteSize:-1},{chunkCount:NaN}])('rejects invalid stored resource counts before reading private bytes: %j',async fields=>{
    await closed();await mutateHead(row=>Object.assign(row,fields));const read=vi.spyOn(journal,'read');
    await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'INTEGRITY'});expect(read).not.toHaveBeenCalled();
  });
  it('a malformed stored terminal or end intent on a non-head segment is not trusted',async()=>{
    await closed(true);const second={...first,id:crypto.randomUUID(),ordinal:2,startedAt:2100};await journal.begin(scope,second);await journal.append(scope,second.id,0,new Blob(['two'],{type:first.mimeType}));await journal.seal(scope,second.id,1,2500);
    const original=await journal.prepareAnswerEnd(scope,command);
    await alter('segments',s=>{const r=s.getAll();r.onsuccess=()=>{const row=r.result.find(x=>x.ordinal===2);row.answerEnd=original;s.put(row);};});
    await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'INTEGRITY'});
    await mutateHead(row=>{row.terminal={expectedChunks:2,endedAt:2001,reason:'DEVICE_ENDED'};});
    await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'INTEGRITY'});
  });
  it('invalid ids, null commands, closed handles and trial journals cannot create a formal answer end',async()=>{
    await closed();for(const input of [null,{...command,idempotencyKey:'invalid'},{...command,captureClientId:'invalid'}])await expect(journal.prepareAnswerEnd(scope,input as never)).rejects.toMatchObject({code:'INVALID'});
    const trial=new PreparedAudioJournal(identity,factory,'preflight');await expect(trial.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'INVALID'});await expect(trial.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'INVALID'});trial.close();
    journal.close();await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'CLOSED'});await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'CLOSED'});
  });
});

describe('original answer ending reuses verified bytes, never skips integrity or terminal evidence',()=>{
  it.each([false,true])('verifies a closed recorder exactly once per ending or recovery (interrupted=%s)',async fault=>{
    await closed(fault);
    const read=vi.spyOn(journal,'read'),hash=vi.spyOn(crypto.subtle,'digest');
    const originalByteHashes=()=>hash.mock.calls.filter(([,bytes])=>{
      const text=new TextDecoder().decode(bytes);return text==='original'||text==='tail';
    }).length;
    const original=await journal.prepareAnswerEnd(scope,command);
    expect(original).toMatchObject(command);
    expect(read).toHaveBeenCalledTimes(1);
    expect(originalByteHashes()).toBe(2);
    read.mockClear();hash.mockClear();
    expect(await journal.recoverAnswerEnd(scope)).toEqual(original);
    expect(read).toHaveBeenCalledTimes(1);
    expect(originalByteHashes()).toBe(2);
  });
  it('reads each original recorder once without merging containers or dropping interruption evidence',async()=>{
    await closed(true);
    const second={...first,id:crypto.randomUUID(),ordinal:2,startedAt:2100,mimeType:'audio/ogg'};
    await journal.begin(scope,second);await journal.append(scope,second.id,0,new Blob(['second recorder'],{type:second.mimeType}));await journal.seal(scope,second.id,1,2500);
    const read=vi.spyOn(journal,'read'),original=await journal.prepareAnswerEnd(scope,command);
    expect(original.manifest.segments).toMatchObject([{id:first.id,state:'INTERRUPTED',interruption:'DEVICE_ENDED'},
      {id:second.id,state:'SEALED',interruption:null,mimeType:'audio/ogg'}]);
    expect(read.mock.calls.map(([,id])=>id)).toEqual([first.id,second.id]);
    read.mockClear();expect(await journal.recoverAnswerEnd(scope)).toEqual(original);
    expect(read.mock.calls.map(([,id])=>id)).toEqual([first.id,second.id]);
  });
  it('still rejects terminal evidence changed after byte verification and preserves the original recording',async()=>{
    await closed();await journal.prepareAnswerEnd(scope,command);
    const originalRead=journal.read.bind(journal);let changed=false;
    vi.spyOn(journal,'read').mockImplementation(async(target,id)=>{
      const stored=await originalRead(target,id);
      if(!changed){changed=true;await mutateHead(row=>{row.terminal={expectedChunks:2,endedAt:2000,reason:'DEVICE_ENDED'};});}
      return stored;
    });
    await expect(journal.recoverAnswerEnd(scope)).rejects.toMatchObject({code:'INTEGRITY'});
    await expect(journal.prepareAnswerEnd(scope,command)).rejects.toMatchObject({code:'INTEGRITY'});
    expect((await journal.list(scope))[0]).toMatchObject({state:'SEALED',chunkCount:2,endedAt:2000});
    expect(await (await journal.playable(scope,first.id)).text()).toBe('originaltail');
  });
});

describe('original ending recovery and late terminal integrity — controlled local storage only',()=>{
  it.each([false,true])('recovery verifies each original byte again, once, without a checksum cache (interrupted=%s)',async fault=>{
    await closed(fault);const original=await journal.prepareAnswerEnd(scope,command);
    const read=vi.spyOn(journal,'read'),hash=vi.spyOn(crypto.subtle,'digest');
    expect(await journal.recoverAnswerEnd(scope)).toEqual(original);
    expect(read).toHaveBeenCalledTimes(1);
    expect(hash.mock.calls.filter(([,bytes])=>['original','tail'].includes(new TextDecoder().decode(bytes)))).toHaveLength(2);
  });
  it.each(['prepare','recover'] as const)('rejects a terminal changed during the final checksum before %s returns success',async operation=>{
    await closed();if(operation==='recover')await journal.prepareAnswerEnd(scope,command);
    const originalDigest=crypto.subtle.digest.bind(crypto.subtle);let changed=false;
    const hash=vi.spyOn(crypto.subtle,'digest').mockImplementation(async(algorithm,bytes)=>{
      const text=new TextDecoder().decode(bytes);
      if(!changed&&text.includes('"captureClientId"')&&text.includes('"idempotencyKey"')){
        changed=true;await mutateHead(row=>{row.terminal={expectedChunks:2,endedAt:2000,reason:'DEVICE_ENDED'};});
      }
      return originalDigest(algorithm,bytes);
    });
    const pending=operation==='prepare'?journal.prepareAnswerEnd(scope,command):journal.recoverAnswerEnd(scope);
    await expect(pending).rejects.toMatchObject({code:'INTEGRITY'});expect(changed).toBe(true);hash.mockRestore();
    expect((await journal.list(scope))[0]).toMatchObject({state:'SEALED',chunkCount:2,endedAt:2000});
    expect(await journal.hasAnswerEnd(scope)).toBe(operation==='recover');
    expect(await (await journal.playable(scope,first.id)).text()).toBe('originaltail');
  });
});
