import {beforeEach,expect,it,vi} from 'vitest';
import request from '@/utils/request';
import {getReviewReport,exportReviewReport} from '@/api/preparedReview';
vi.mock('@/utils/request',()=>({default:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',version='a'.repeat(64);
beforeEach(()=>{vi.mocked(request).mockReset();});
it('reads and exports the exact manager attempt digest with bounded cancellation and no client identity',async()=>{
  const signal=new AbortController().signal;
  await getReviewReport(21,attempt,version,signal);await exportReviewReport(21,attempt,version,signal);
  expect(vi.mocked(request).mock.calls.map(c=>c[0])).toEqual([
    {url:`/backend/prepared-reviews/21/attempts/${attempt}/report`,method:'get',params:{resultVersion:version},timeout:10000,signal},
    {url:`/backend/prepared-reviews/21/attempts/${attempt}/export.xlsx`,method:'get',params:{resultVersion:version},responseType:'blob',timeout:10000,signal},
  ]);
});
it('rejects malformed assessment, original attempt or digest before any network request',()=>{
  for(const read of [getReviewReport,exportReviewReport]){
    for(const id of [0,-1,NaN,1.5,2147483648])expect(()=>read(id,attempt,version)).toThrow();
    for(const id of ['bad','AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA',attempt+'?actor=9'])expect(()=>read(21,id,version)).toThrow();
    for(const digest of ['bad','a'.repeat(63),'A'.repeat(64),'a'.repeat(64)+'?actor=9'])expect(()=>read(21,attempt,digest)).toThrow();
  }
  expect(request).not.toHaveBeenCalled();
});
it('propagates refusal without manufacturing a report or download',async()=>{
  const failure=new Error('原成绩版本已经变化');vi.mocked(request).mockRejectedValue(failure);
  await expect(getReviewReport(21,attempt,version)).rejects.toBe(failure);
  await expect(exportReviewReport(21,attempt,version)).rejects.toBe(failure);
});
