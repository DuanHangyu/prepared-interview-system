import { beforeEach,expect,it,vi } from 'vitest';
import request from '@/utils/request';
import { getSupplementStatus,approveSupplement } from '@/api/preparedSupplement';
import { scope,command } from '../fixtures/prepared-supplement';
vi.mock('@/utils/request',()=>({default:vi.fn()}));
beforeEach(()=>{vi.mocked(request).mockReset();});
it('uses scoped current teacher routes with original metadata, never client-supplied teacher identity or extra payload',async()=>{
  await getSupplementStatus(scope);await approveSupplement(scope,command);
  const url=`/backend/prepared-reviews/21/students/8/attempts/${scope.attemptId}/answers/${scope.answerId}/supplement`;
  expect(vi.mocked(request).mock.calls.map(c=>c[0])).toEqual([{url,method:'get'},{url,method:'post',data:command}]);
});
it('rejects unsafe scope and command without a request and preserves unknown HTTP failures',async()=>{
  await expect(getSupplementStatus({...scope,assessmentId:NaN})).rejects.toThrow();
  await expect(approveSupplement(scope,{...command,expectedRevision:-1})).rejects.toThrow();expect(request).not.toHaveBeenCalled();
  vi.mocked(request).mockRejectedValue(new Error('unknown network outcome'));await expect(approveSupplement(scope,command)).rejects.toThrow('unknown network outcome');
});
