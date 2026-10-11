import { beforeEach, expect, it, vi } from 'vitest';
import { PreparedReviewJournal } from '@/prepared/reviewJournal';
const scope={actorId:7,assessmentId:21,attemptId:'11111111-1111-4111-8111-111111111111',answerId:'22222222-2222-4222-8222-222222222222'};
const resultId='33333333-3333-4333-8333-333333333333';
beforeEach(()=>sessionStorage.clear());
it('restores immutable original intent after reload and isolates actor, assessment, attempt and answer',()=>{
  const journal=new PreparedReviewJournal(scope);const saved=journal.prepare('confirm',2,resultId,'原核分依据');
  saved.command.reason='调用者编辑';
  const original=new PreparedReviewJournal(scope).read()!;expect(original.command.reason).toBe('原核分依据');
  expect(journal.prepare('regrade',9,scope.answerId,'后续编辑')).toEqual(original);
  for(const changed of [{actorId:8},{assessmentId:22},{attemptId:resultId},{answerId:resultId}])expect(new PreparedReviewJournal({...scope,...changed}).read()).toBeNull();
  expect(()=>journal.clear(saved)).toThrow();journal.clear(original);expect(journal.read()).toBeNull();
});
it('never sends a replacement request when durable local storage fails or is corrupted',()=>{
  const failing={getItem:()=>null,setItem:()=>{throw new Error('quota');},removeItem:vi.fn()} as unknown as Storage;
  expect(()=>new PreparedReviewJournal(scope,failing).prepare('regrade',2,resultId,'依据')).toThrow('保存');
  const j=new PreparedReviewJournal(scope);j.prepare('regrade',2,resultId,'依据');const key=sessionStorage.key(0)!;
  for(const raw of ['{',JSON.stringify({kind:'confirm',command:{}}),'a'.repeat(16385)]){
    sessionStorage.setItem(key,raw);expect(()=>j.read()).toThrow();expect(()=>j.prepare('confirm',2,resultId,'新请求')).toThrow();
  }
});
it('refuses invalid scopes, versions, reasons, ids and failed deletion confirmation',()=>{
  for(const changed of [{actorId:0},{assessmentId:NaN},{attemptId:'bad'},{answerId:'bad'}])expect(()=>new PreparedReviewJournal({...scope,...changed})).toThrow();
  const j=new PreparedReviewJournal(scope);for(const revision of [-1,1.5,Number.MAX_SAFE_INTEGER+1])expect(()=>j.prepare('confirm',revision,resultId,'依据')).toThrow();
  for(const reason of ['',' '.repeat(5),'字'.repeat(1001)])expect(()=>j.prepare('confirm',2,resultId,reason)).toThrow();
  expect(()=>j.prepare('confirm',2,'bad','依据')).toThrow();expect(()=>j.prepare('unknown' as 'confirm',2,resultId,'依据')).toThrow();
  const original=j.prepare('confirm',2,resultId,'依据');
  const noDelete={getItem:sessionStorage.getItem.bind(sessionStorage),setItem:sessionStorage.setItem.bind(sessionStorage),removeItem:()=>{}} as unknown as Storage;
  expect(()=>new PreparedReviewJournal(scope,noDelete).clear(original)).toThrow();
});
it('persists an exact human adjustment without requiring a model result and restores it once',()=>{
  const journal=new PreparedReviewJournal(scope);const original=journal.prepareAdjust(0,8.5,'原人工核验依据');
  expect(original).toEqual({kind:'adjust',command:{idempotencyKey:expect.any(String),expectedRevision:0,score:8.5,reason:'原人工核验依据'}});
  expect(new PreparedReviewJournal(scope).read()).toEqual(original);
  expect(journal.prepareAdjust(9,7,'不得替换原请求')).toEqual(original);
  journal.clear(original);expect(journal.read()).toBeNull();
});
it('refuses nonnumeric, negative and imprecise manual scores rather than silently rounding',()=>{
  for(const value of [-1,NaN,Infinity,8.001,'8' as unknown as number])expect(()=>new PreparedReviewJournal(scope).prepareAdjust(0,value,'人工依据')).toThrow();
  expect(()=>new PreparedReviewJournal(scope).prepareAdjust(Number.MAX_SAFE_INTEGER,8,'人工依据')).toThrow();
  expect(new PreparedReviewJournal(scope).prepareAdjust(0,0,'合法人工零分').command).toEqual(expect.objectContaining({score:0}));
});
it('rejects corrupted human intent fields instead of converting or replacing the original score',()=>{
  const journal=new PreparedReviewJournal(scope);const saved=journal.prepareAdjust(0,8.5,'人工依据');const key=sessionStorage.key(0)!;
  for(const changed of [{score:'8.5'},{score:-1},{score:8.001},{studentId:8},{expectedRevision:Number.MAX_SAFE_INTEGER}]){
    sessionStorage.setItem(key,JSON.stringify({...saved,command:{...saved.command,...changed}}));expect(()=>journal.read()).toThrow();expect(()=>journal.prepareAdjust(0,9,'新理由')).toThrow();
  }
});
