import { expect,it } from 'vitest';
import { checkRetentionScope,copyRetentionCommand,checkRetentionView,checkRetentionReceipt } from '@/prepared/retentionProof';
import { scope,command,view,receipt,protectedView } from '../fixtures/prepared-retention';
it('validates fresh identity and exact original resource, including a different authorized latest operator',()=>{
  expect(checkRetentionScope(scope)).toEqual(scope);expect(checkRetentionView(view,scope)).toEqual(view);
  expect(checkRetentionView({...protectedView,latest:{...receipt,actorId:9}},scope).latest?.actorId).toBe(9);
  for(const changed of [{actorId:9},{studentId:9},{assessmentId:22},{attemptId:receipt.id},{actorId:0},{revision:-1},{revision:NaN},{held:'true'},{serverNow:'2026-02-30T01:00:00Z'},{latest:{...receipt,studentId:9}},{latest:{...receipt,revision:2}},{latest:{...receipt,held:false}},{latest:undefined}])expect(()=>checkRetentionView({...protectedView,...changed},scope)).toThrow('无法核验');
  expect(()=>checkRetentionScope({...scope,attemptId:'../wrong'})).toThrow();expect(()=>checkRetentionScope({...scope,assessmentId:2147483648})).toThrow();
});
it('rejects coercions, unsafe revisions, unknown fields and malformed text without trimming original reason',()=>{
  expect(copyRetentionCommand({...command,reason:' 原依据\n保留空格 '})).toEqual({...command,reason:' 原依据\n保留空格 '});
  for(const changed of [{expectedRevision:'0'},{expectedRevision:-1},{expectedRevision:Number.MAX_SAFE_INTEGER},{action:'DELETE'},{action:null},{reason:''},{reason:'  '},{reason:'x'.repeat(1001)},{reason:'bad\u0000'},{reason:'bad\ud800'},{reason:'bad\udc00'},{idempotencyKey:'bad'},{actorId:scope.actorId}])expect(()=>copyRetentionCommand({...command,...changed})).toThrow();
  expect(copyRetentionCommand({...command,reason:'😀\t\r\n原依据'}).reason).toBe('😀\t\r\n原依据');
});
it('fails closed for inconsistent lifecycle eligibility while allowing retained results after audio retirement',()=>{
  expect(checkRetentionView({...view,audioState:'RETIRED'},scope).mayChange).toBe(true);
  for(const changed of [{audioState:'GONE'},{resultsState:'RETIRED'},{mayChange:'true'},{audioState:'RETIRING'},{resultsState:'RETIRING'},{revision:Number.MAX_SAFE_INTEGER}])expect(()=>checkRetentionView({...view,...changed},scope)).toThrow();
  expect(checkRetentionView({...view,audioState:'RETIRING',mayChange:false},scope).mayChange).toBe(false);
  expect(()=>checkRetentionView({...protectedView,audioState:'RETIRING',mayChange:false},scope)).toThrow();
  expect(()=>checkRetentionView({...view,held:true},scope)).toThrow();
});
it('accepts only the original receipt without rebinding it to a later protection state or operator',()=>{
  expect(checkRetentionReceipt(receipt,scope,command)).toEqual(receipt);
  for(const changed of [{id:'bad'},{actorId:9},{attemptId:receipt.id},{expectedRevision:1},{revision:2},{held:false},{action:'RELEASE'},{reason:'replacement'},{idempotencyKey:receipt.id},{audioState:'RETIRING'},{resultsState:'RETIRING'},{executedAt:'bad'}])expect(()=>checkRetentionReceipt({...receipt,...changed},scope,command)).toThrow();
  const release={...command,action:'RELEASE' as const,expectedRevision:1};expect(checkRetentionReceipt({...receipt,...release,revision:2,held:false,audioState:'RETIRED'},scope,release).held).toBe(false);
});
