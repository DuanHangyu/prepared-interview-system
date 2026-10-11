import {beforeEach,expect,it,vi} from 'vitest';
import request from '@/utils/request';
import {getPreparedPreflightContext} from '@/api/preparedStudent';
vi.mock('@/utils/request',()=>({default:vi.fn()}));
beforeEach(()=>{vi.mocked(request).mockReset();});
it('leaves preflight identity recovery with its scoped caller and retains the bounded request and cancellation',async()=>{
  const controller=new AbortController();
  await getPreparedPreflightContext(8,controller.signal);
  expect(request).toHaveBeenCalledExactlyOnceWith({url:'/backend/prepared-interviews/8/preflight-context',method:'get',timeout:10000,signal:controller.signal,callerOwnsFailure:true});
});
it('does not replace a failed identity read with local defaults',async()=>{
  const original=new Error('unknown identity');vi.mocked(request).mockRejectedValue(original);
  await expect(getPreparedPreflightContext(8)).rejects.toBe(original);
});
