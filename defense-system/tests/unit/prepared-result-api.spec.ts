import {beforeEach,it,expect,vi} from 'vitest';
import request from '@/utils/request';
import {getPreparedStudentResult,getPreparedStudentResultAnswer,getPreparedStudentResultReport,exportPreparedStudentResultReport} from '@/api/preparedResult';
vi.mock('@/utils/request',()=>({default:vi.fn().mockResolvedValue({data:{}})}));
const answer='22222222-2222-4222-8222-222222222222',version='a'.repeat(64);
beforeEach(()=>vi.clearAllMocks());
it('only sends bounded GETs, original result version and abort signal without student or teacher identity',async()=>{
  const signal=new AbortController().signal;await getPreparedStudentResult(21,signal);await getPreparedStudentResultAnswer(21,answer,version,signal);
  expect(request).toHaveBeenNthCalledWith(1,{url:'/backend/prepared-results/21',method:'get',timeout:10000,signal});
  expect(request).toHaveBeenNthCalledWith(2,{url:`/backend/prepared-results/21/answers/${answer}`,method:'get',params:{resultVersion:version},timeout:10000,signal});
});
it('rejects invalid scopes before transport',()=>{
  for(const id of [0,-1,1.5,NaN,Infinity,2147483648,'21'])expect(()=>getPreparedStudentResult(id as number)).toThrow('考核标识');
  for(const id of [null,undefined,'bad','../','AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA'])expect(()=>getPreparedStudentResultAnswer(21,id as string,version)).toThrow('原结果');
  for(const hash of [null,undefined,'bad','A'.repeat(64)])expect(()=>getPreparedStudentResultAnswer(21,answer,hash as string)).toThrow('原结果');
  expect(request).not.toHaveBeenCalled();
});
it('report and export only send the bounded original version, not a client-selected identity or object URL',async()=>{
  const signal=new AbortController().signal;await getPreparedStudentResultReport(21,version,signal);await exportPreparedStudentResultReport(21,version,signal);
  expect(request).toHaveBeenNthCalledWith(1,{url:'/backend/prepared-results/21/report',method:'get',params:{resultVersion:version},timeout:10000,signal});
  expect(request).toHaveBeenNthCalledWith(2,{url:'/backend/prepared-results/21/export.xlsx',method:'get',params:{resultVersion:version},responseType:'blob',timeout:10000,signal});
});
it('report and download reject malformed versions and assessment scopes before transport',()=>{
  for(const read of [getPreparedStudentResultReport,exportPreparedStudentResultReport]) {
    for(const hash of [null,undefined,'bad','A'.repeat(64)])expect(()=>read(21,hash as string)).toThrow('原结果');
    for(const id of [0,-1,NaN,2147483648])expect(()=>read(id,version)).toThrow('考核标识');
  }
  expect(request).not.toHaveBeenCalled();
});
