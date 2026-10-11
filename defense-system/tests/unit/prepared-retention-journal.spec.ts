import { IDBFactory,IDBDatabase } from 'fake-indexeddb';
import { beforeEach,expect,it,vi } from 'vitest';
import { PreparedRetentionJournal } from '@/prepared/retentionJournal';
import { scope,command } from '../fixtures/prepared-retention';
let factory:IDBFactory;beforeEach(()=>{factory=new IDBFactory();});
const journal=(changed={})=>new PreparedRetentionJournal({...scope,...changed},factory);
it('durably stores a defensive copy of one original and never rebinds identity or original scope',async()=>{
  const a=journal(),saved=await a.prepare(command);a.close();saved.reason='mutation';const b=journal(),original=await b.read();expect(original?.reason).toBe(command.reason);
  expect(await b.prepare({...command,reason:'replacement',expectedRevision:4})).toEqual(original);
  for(const changed of [{actorId:9},{studentId:9},{assessmentId:22},{attemptId:command.idempotencyKey}]){const other=journal(changed);expect(await other.read()).toBeNull();other.close();}
  await expect(b.clear(saved)).rejects.toThrow();expect(await b.read()).toEqual(original);await b.clear(original!);expect(await b.read()).toBeNull();b.close();expect(()=>journal({actorId:0})).toThrow();
});
it('serializes separate tabs and refuses late clear against a later original',async()=>{
  const a=journal(),b=journal();const saved=await Promise.all([a.prepare(command),b.prepare({...command,reason:'concurrent'})]);expect(saved[0]).toEqual(saved[1]);
  await a.clear(saved[0]);const later=await b.prepare({...command,expectedRevision:1,action:'RELEASE'});await expect(a.clear(saved[0])).rejects.toThrow();expect(await a.read()).toEqual(later);a.close();b.close();
});
it('fails closed for unavailable storage, delayed closed opens, aborts and corrupted original metadata',async()=>{
  const closed=journal();closed.close();await expect(closed.read()).rejects.toThrow();
  let request={} as IDBOpenDBRequest;const factoryStub={open:()=>request} as unknown as IDBFactory;
  const blocked=new PreparedRetentionJournal(scope,factoryStub),opening=blocked.read();request.onblocked?.call(request,new Event('blocked') as IDBVersionChangeEvent);await expect(opening).rejects.toThrow();
  const db={close:vi.fn()} as unknown as IDBDatabase;request={result:db} as IDBOpenDBRequest;const delayed=new PreparedRetentionJournal(scope,factoryStub),late=delayed.read();delayed.close();request.onsuccess?.call(request,new Event('success'));await expect(late).rejects.toThrow();expect(db.close).toHaveBeenCalled();
  const a=journal();await a.prepare(command);const tx=vi.spyOn(IDBDatabase.prototype,'transaction').mockImplementation(()=>{throw new Error('abort');});await expect(a.read()).rejects.toThrow();tx.mockRestore();expect(await a.read()).not.toBeNull();a.close();
  const b=journal();const raw=await new Promise<IDBDatabase>((resolve,reject)=>{const req=factory.open('prepared-teacher-retention-v1',1);req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);});
  await new Promise<void>((resolve,reject)=>{const tx=raw.transaction('intents','readwrite');tx.objectStore('intents').put('{"invalid":true}',JSON.stringify(['prepared-retention-v1',scope]));tx.oncomplete=()=>resolve();tx.onerror=()=>reject(tx.error);});
  await expect(b.read()).rejects.toThrow();await expect(b.prepare(command)).rejects.toThrow();b.close();raw.close();
});
it('can retry a temporary opening failure without losing the original',async()=>{
  let count=0;const flaky={open:(name:string,version:number)=>{if(!count++)throw Error('temporarily unavailable');return factory.open(name,version);}} as unknown as IDBFactory;
  const a=new PreparedRetentionJournal(scope,flaky);await expect(a.read()).rejects.toThrow();expect(await a.read()).toBeNull();expect((await a.prepare(command)).reason).toBe(command.reason);a.close();
});
