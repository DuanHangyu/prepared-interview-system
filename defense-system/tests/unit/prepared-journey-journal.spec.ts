// @vitest-environment node
import {IDBFactory} from 'fake-indexeddb';
import {beforeEach,describe,expect,it} from 'vitest';
import {PreparedJourneyJournal} from '@/prepared/journeyJournal';
const owner={origin:'http://127.0.0.1:13015',studentId:12,deploymentId:'prepared-local'};
let factory:IDBFactory,journal:PreparedJourneyJournal;
beforeEach(()=>{factory=new IDBFactory();journal=new PreparedJourneyJournal(owner,8,factory);});
describe('original student journey — storage substitute, not full interview acceptance',()=>{
  it('persists the original final submission key and version before HTTP and recovers them unchanged after refresh',async()=>{
    const binding={attemptId:crypto.randomUUID(),paperVersionId:crypto.randomUUID()};await journal.prepareStart();await journal.bindAttempt(binding);
    const first=await journal.prepareSubmission(binding,3);expect(first).toMatchObject({...binding,expectedRevision:3});
    journal.close();journal=new PreparedJourneyJournal(owner,8,factory);
    expect(await journal.readSubmission(binding)).toEqual(first);expect(await journal.prepareSubmission(binding,4)).toEqual(first);
    expect('captureClientId' in first).toBe(false);
  });
  it('never invents a final command on read, without the original binding, or for another attempt',async()=>{
    const binding={attemptId:crypto.randomUUID(),paperVersionId:crypto.randomUUID()};expect(await journal.readSubmission(binding)).toBeNull();
    await expect(journal.prepareSubmission(binding,0)).rejects.toMatchObject({code:'MISSING_ORIGINAL'});await journal.prepareStart();await journal.bindAttempt(binding);
    await expect(journal.prepareSubmission({...binding,attemptId:crypto.randomUUID()},0)).rejects.toMatchObject({code:'CONFLICT'});
    await expect(journal.prepareSubmission(binding,-1)).rejects.toMatchObject({code:'INVALID'});
  });
  it('does not create keys on a read and commits one immutable start before reopening',async()=>{
    expect(await journal.readStart()).toBeNull();
    const first=await journal.prepareStart();journal.close();journal=new PreparedJourneyJournal(owner,8,factory);
    expect(await journal.readStart()).toEqual(first);expect(await journal.prepareStart()).toEqual(first);
    expect(first.idempotencyKey).not.toBe(first.captureClientId);
  });
  it('concurrent tabs receive one original command, never two client identities',async()=>{
    const other=new PreparedJourneyJournal(owner,8,factory);
    const values=await Promise.all(Array.from({length:30},(_,i)=>(i%2?other:journal).prepareStart()));
    expect(new Set(values.map(x=>JSON.stringify(x))).size).toBe(1);other.close();
  });
  it('binds one server attempt and paper without accepting changed or replacement bindings',async()=>{
    const binding={attemptId:crypto.randomUUID(),paperVersionId:crypto.randomUUID()};
    await expect(journal.bindAttempt(binding)).rejects.toMatchObject({code:'MISSING_ORIGINAL'});
    await journal.prepareStart();expect(await journal.bindAttempt(binding)).toEqual(binding);
    expect(await journal.bindAttempt(binding)).toEqual(binding);
    await expect(journal.bindAttempt({...binding,attemptId:crypto.randomUUID()})).rejects.toMatchObject({code:'CONFLICT'});
    expect(await journal.readBinding()).toEqual(binding);
  });
  it('fixes original per-question start and end keys before use and preserves them on refresh',async()=>{
    await expect(journal.prepareQuestion(1)).rejects.toMatchObject({code:'MISSING_ORIGINAL'});
    await journal.prepareStart();await journal.bindAttempt({attemptId:crypto.randomUUID(),paperVersionId:crypto.randomUUID()});
    const first=await journal.prepareQuestion(1),second=await journal.prepareQuestion(2);
    journal.close();journal=new PreparedJourneyJournal(owner,8,factory);
    expect(await journal.readQuestion(1)).toEqual(first);expect(await journal.prepareQuestion(1)).toEqual(first);
    expect(second.idempotencyKey).not.toBe(first.idempotencyKey);expect(second.endKey).not.toBe(first.endKey);
    expect(first.captureClientId).toBe((await journal.readStart())!.captureClientId);
  });
  it('isolates account, deployment, source and assessment, with no deletion API',async()=>{
    await journal.prepareStart();
    for(const [identity,id] of [[{...owner,studentId:13},8],[{...owner,deploymentId:'prepared-other'},8],[{...owner,origin:'https://school.example'},8],[owner,9]] as const){
      const other=new PreparedJourneyJournal(identity,id,factory);expect(await other.readStart()).toBeNull();other.close();
    }
    expect('clear' in journal).toBe(false);expect('delete' in journal).toBe(false);
  });
  it('fails closed on syntactically valid corruption rather than generating replacement identifiers',async()=>{
    const original=await journal.prepareStart();
    const db=await new Promise<IDBDatabase>(resolve=>{const r=factory.open('prepared-interview-journey-v1',1);r.onsuccess=()=>resolve(r.result);});
    await new Promise<void>(resolve=>{const tx=db.transaction('commands','readwrite'),s=tx.objectStore('commands'),r=s.getAll();r.onsuccess=()=>{const row=r.result[0];row.value.idempotencyKey=crypto.randomUUID();s.put(row);};tx.oncomplete=()=>resolve();});db.close();
    await expect(journal.readStart()).rejects.toMatchObject({code:'INTEGRITY'});
    await expect(journal.prepareStart()).rejects.toMatchObject({code:'INTEGRITY'});
    expect(original.idempotencyKey).toBeTypeOf('string');
  });
  it('validates scopes and refuses operations after closing',async()=>{
    for(const args of [[{...owner,studentId:0},8],[{...owner,origin:'http://unsafe.example'},8],[{...owner,deploymentId:'old'},8],[owner,0]] as const)
      expect(()=>new PreparedJourneyJournal(args[0],args[1],factory)).toThrow();
    await expect(journal.readQuestion(0)).rejects.toMatchObject({code:'INVALID'});
    await expect(journal.prepareQuestion(51)).rejects.toMatchObject({code:'INVALID'});
    journal.close();await expect(journal.prepareStart()).rejects.toMatchObject({code:'CLOSED'});
  });
});
