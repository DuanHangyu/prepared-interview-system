import { IDBFactory,IDBDatabase } from 'fake-indexeddb';
import { beforeEach,expect,it,vi } from 'vitest';
import { PreparedSupplementJournal } from '@/prepared/supplementJournal';
import { scope,command } from '../fixtures/prepared-supplement';
let factory:IDBFactory;
beforeEach(()=>{factory=new IDBFactory();});
const journal=(changed={})=>new PreparedSupplementJournal({...scope,...changed},factory);
it('persists original before sending, survives a new connection and never replaces its content or key',async()=>{
  const first=journal(),saved=await first.prepare(command);first.close();saved.reason='later edit';
  const fresh=journal();const original=await fresh.read();expect(original?.reason).toBe(command.reason);
  expect(await fresh.prepare({...command,expectedRevision:5,reason:'replacement'})).toEqual(original);
  expect(()=>journal({actorId:0})).toThrow();expect(()=>journal({answerId:'bad'})).toThrow();
  for(const changed of [{actorId:9},{studentId:9},{assessmentId:22},{attemptId:scope.answerId},{answerId:scope.attemptId}]){const other=journal(changed);expect(await other.read()).toBeNull();other.close();}
  await expect(fresh.clear(saved)).rejects.toThrow();expect(await fresh.read()).toEqual(original);await fresh.clear(original!);expect(await fresh.read()).toBeNull();fresh.close();
});
it('serializes concurrent tabs to one original and refuses a late clear that would delete a later request',async()=>{
  const a=journal(),b=journal();const results=await Promise.all([a.prepare(command),b.prepare({...command,reason:'concurrent'})]);expect(results[0]).toEqual(results[1]);
  await a.clear(results[0]);const later=await b.prepare({...command,reason:'next approval'});
  await expect(a.clear(results[0])).rejects.toThrow();expect(await a.read()).toEqual(later);a.close();b.close();
});
it('fails closed for absent, blocked or failed durable storage and for use after close',async()=>{
  const missing=journal();missing.close();await expect(missing.read()).rejects.toThrow();
  const failed=new PreparedSupplementJournal(scope,{open:()=>{throw new Error('storage unavailable');}} as unknown as IDBFactory);await expect(failed.prepare(command)).rejects.toThrow();failed.close();
  const blockedRequest={} as IDBOpenDBRequest;
  const blocked=new PreparedSupplementJournal(scope,{open:()=>{queueMicrotask(()=>blockedRequest.onblocked?.call(blockedRequest,new Event('blocked') as IDBVersionChangeEvent));return blockedRequest;}} as unknown as IDBFactory);
  await expect(blocked.read()).rejects.toThrow();blocked.close();
  const request={} as IDBOpenDBRequest;
  const rejected=new PreparedSupplementJournal(scope,{open:()=>{queueMicrotask(()=>request.onerror?.call(request,new Event('error')));return request;}} as unknown as IDBFactory);
  await expect(rejected.read()).rejects.toThrow();rejected.close();
});
it('keeps the original when a transaction aborts rather than pretending persistence succeeded',async()=>{
  const a=journal();await a.prepare(command);
  const transaction=vi.spyOn(IDBDatabase.prototype,'transaction');transaction.mockImplementation(()=>{throw new Error('transaction blocked');});
  await expect(a.read()).rejects.toThrow();transaction.mockRestore();expect((await a.read())?.reason).toBe(command.reason);a.close();
});
it('can retry local opening after a temporary storage failure without remounting or changing the original',async()=>{
  let attempt=0;const flaky={open:(name:string,version:number)=>{if(attempt++===0)throw new Error('temporary');return factory.open(name,version);}} as unknown as IDBFactory;
  const a=new PreparedSupplementJournal(scope,flaky);await expect(a.read()).rejects.toThrow();expect(await a.read()).toBeNull();const original=await a.prepare(command);expect(await a.read()).toEqual(original);a.close();
});
