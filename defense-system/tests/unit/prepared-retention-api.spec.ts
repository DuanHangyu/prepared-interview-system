import { beforeEach,expect,it,vi } from 'vitest';
import request from '@/utils/request';
import { getRetentionProtection,changeRetentionProtection } from '@/api/preparedRetention';
import { scope,command } from '../fixtures/prepared-retention';
vi.mock('@/utils/request',()=>({default:vi.fn()}));
beforeEach(()=>{vi.mocked(request).mockReset();});
it('uses original attempt-scoped routes and never submits client-supplied teacher identity',async()=>{
  await getRetentionProtection(scope);await changeRetentionProtection(scope,command);
  const url=`/backend/prepared-reviews/21/students/8/attempts/${scope.attemptId}/retention-protection`;
  expect(vi.mocked(request).mock.calls.map(c=>c[0])).toEqual([{url,method:'get',callerOwnsFailure:true},{url,method:'post',data:command,callerOwnsFailure:true}]);
});
it('rejects malformed scope or command before network IO and preserves unknown outcomes',async()=>{
  await expect(getRetentionProtection({...scope,studentId:NaN})).rejects.toThrow();await expect(changeRetentionProtection(scope,{...command,action:'DELETE'} as never)).rejects.toThrow();expect(request).not.toHaveBeenCalled();
  vi.mocked(request).mockRejectedValue(new Error('unknown outcome'));await expect(changeRetentionProtection(scope,command)).rejects.toThrow('unknown outcome');
});
