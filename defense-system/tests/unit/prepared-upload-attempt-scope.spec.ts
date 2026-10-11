// @vitest-environment node
// Synthetic v1 IndexedDB records only; not server authorization or real-retake acceptance.
import {IDBFactory,IDBIndex} from 'fake-indexeddb';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {PreparedUploadJournal} from '@/prepared/uploadJournal';
import {uploadHash,type UploadAnswer} from '@/prepared/uploadBundle';
const owner={origin:'http://127.0.0.1:13015',studentId:12,deploymentId:'prepared-local'};
const current='ffffffff-ffff-4fff-8fff-ffffffffffff',historical='00000000-0000-4000-8000-000000000111';
let factory:IDBFactory,journal:PreparedUploadJournal,db:IDBDatabase|undefined;
const answer=(ordinal=1,attemptId=current):UploadAnswer=>({assessmentId:8,attemptId,ordinal,answerId:crypto.randomUUID(),captureClientId:'00000000-0000-4000-8000-000000000222'});
beforeEach(()=>{factory=new IDBFactory();journal=new PreparedUploadJournal(owner,8,factory);});
afterEach(()=>{db?.close();db=undefined;journal.close();});
async function database(){
  db??=await new Promise<IDBDatabase>((resolve,reject)=>{const r=factory.open('prepared-interview-upload-v1',1);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});return db;
}
async function rows(){const databaseHandle=await database();return new Promise<any[]>((resolve,reject)=>{
  const tx=databaseHandle.transaction('answers','readonly'),r=tx.objectStore('answers').getAll();let result:any[]=[];
  r.onsuccess=()=>{result=r.result;};tx.oncomplete=()=>resolve(result);tx.onabort=()=>reject(tx.error);
});}
async function put(row:any){const databaseHandle=await database();await new Promise<void>((resolve,reject)=>{
  const tx=databaseHandle.transaction('answers','readwrite');tx.objectStore('answers').put(row);tx.oncomplete=()=>resolve();tx.onabort=()=>reject(tx.error);
});}
it('bounds v1 reads before filtering, permits fifty per attempt, preserves all history and sorts numeric ordinals',async()=>{
  const expected:UploadAnswer[]=[];
  for(let ordinal=50;ordinal>0;ordinal--){const a=answer(ordinal);expected.unshift(a);await journal.track(a);await journal.track(answer(ordinal,historical));}
  const before=await rows();expect(before).toHaveLength(100);expect(db!.version).toBe(1);
  const read=vi.spyOn(IDBIndex.prototype,'getAll');expect(await journal.answers(current)).toEqual(expected);
  expect(read).toHaveBeenCalledTimes(1);const [range,count]=read.mock.calls[0];expect(count).toBe(51);
  const prefix=JSON.stringify([JSON.stringify([1,owner.deploymentId,owner.origin,12,8]),current]).slice(0,-1)+',';
  expect(range).toMatchObject({lower:prefix,upper:prefix+'\uffff',lowerOpen:false,upperOpen:false});
  expect(await rows()).toEqual(before);expect(db!.version).toBe(1);
  expect((await journal.answers(historical)).map(a=>a.ordinal)).toEqual(expected.map(a=>a.ordinal));
});
it.each([undefined,null,'','not-an-attempt',current.toUpperCase()])('refuses invalid or omitted original attempt %s before opening IndexedDB',async invalid=>{
  const open=vi.spyOn(factory,'open');
  await expect(journal.answers(invalid as string)).rejects.toMatchObject({code:'INVALID'});expect(open).not.toHaveBeenCalled();
});
it('never infers a current attempt from retained records and returns independent copies after reopen',async()=>{
  const a=answer();await journal.track(a);expect(await journal.answers(historical)).toEqual([]);
  journal.close();journal=new PreparedUploadJournal(owner,8,factory);
  const restored=await journal.answers(current);expect(restored).toEqual([a]);restored[0].ordinal=42;
  expect(await journal.answers(current)).toEqual([a]);
});
it('isolates deployment, origin, student and assessment even when the attempt UUID matches',async()=>{
  await journal.track(answer());
  for(const [identity,assessmentId] of [[{...owner,studentId:13},8],[{...owner,deploymentId:'prepared-other'},8],[{...owner,origin:'https://school.example'},8],[owner,9]] as const){
    const other=new PreparedUploadJournal(identity,assessmentId,factory);
    try{expect(await other.answers(current)).toEqual([]);}finally{other.close();}
  }
});
it.each(['hash','foreign-attempt','order','duplicate-ordinal','over-limit'] as const)('rejects a damaged current-attempt %s witness without rewriting it',async damage=>{
  const a=answer();await journal.track(a);const original=(await rows())[0],row=structuredClone(original);
  if(damage==='hash')row.sha256='a'.repeat(64);
  if(damage==='foreign-attempt'){
    row.value.attemptId=historical;
    row.sha256=await uploadHash(JSON.stringify([row.key,row.value]));
  }
  if(damage==='order')row.order=JSON.stringify([row.namespace,current,2]);
  if(damage==='duplicate-ordinal'){
    row.value.answerId=crypto.randomUUID();row.key=JSON.stringify([row.namespace,current,row.value.answerId]);
    row.order=JSON.stringify([row.namespace,current,'1']);row.sha256=await uploadHash(JSON.stringify([row.key,row.value]));
  }
  if(damage==='over-limit'){
    for(let ordinal=2;ordinal<=50;ordinal++)await journal.track(answer(ordinal));
    row.value.ordinal=51;row.value.answerId=crypto.randomUUID();row.key=JSON.stringify([row.namespace,current,row.value.answerId]);
    row.order=JSON.stringify([row.namespace,current,51]);row.sha256=await uploadHash(JSON.stringify([row.key,row.value]));
  }
  await put(row);const before=await rows();
  await expect(journal.answers(current)).rejects.toMatchObject({code:'INTEGRITY'});expect(await rows()).toEqual(before);
});
it('fails closed after the original journal closes',async()=>{
  await journal.track(answer());journal.close();await expect(journal.answers(current)).rejects.toMatchObject({code:'CLOSED'});
});
