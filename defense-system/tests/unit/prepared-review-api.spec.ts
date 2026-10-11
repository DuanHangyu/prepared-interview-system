import { beforeEach, expect, it, vi } from 'vitest';
import request from '@/utils/request';
import { getReviewRoster, getReviewInterview, getReviewAnswer, getReviewAudio, confirmReviewScore, requestReviewRegrade,adjustReviewScore,getReviewManualHistory,getReviewAttempts,getReviewEvidence,getReviewSummary } from '@/api/preparedReview';
vi.mock('@/utils/request',()=>({default:vi.fn()}));
beforeEach(()=>{vi.mocked(request).mockReset();});
it('uses a bounded cancellable read-only manager summary route with no client-supplied actor or student',async()=>{
  const attempt='11111111-1111-4111-8111-111111111111',signal=new AbortController().signal;
  await getReviewSummary(21,attempt,signal);expect(request).toHaveBeenCalledWith({url:`/backend/prepared-reviews/21/attempts/${attempt}/summary`,method:'get',timeout:10000,signal});
  vi.mocked(request).mockClear();for(const assessment of [0,-1,NaN,2147483648])expect(()=>getReviewSummary(assessment,attempt)).toThrow();expect(()=>getReviewSummary(21,'bad')).toThrow();expect(request).not.toHaveBeenCalled();
});
it('uses bounded teacher review routes and preserves the original command without client identities',async()=>{
  await getReviewRoster(21,3);await getReviewInterview(21,'attempt');await getReviewAnswer(21,'attempt','answer','cursor');
  const confirm={idempotencyKey:'key',expectedRevision:2,scoreId:'score',reason:'核验'};
  const regrade={idempotencyKey:'key2',expectedRevision:3,transcriptId:'transcript',reason:'重评'};
  await confirmReviewScore(21,'attempt','answer',confirm);await requestReviewRegrade(21,'attempt','answer',regrade);
  await getReviewAudio(21,'attempt','answer');
  expect(vi.mocked(request).mock.calls.map(c=>c[0])).toEqual([
    {url:'/backend/prepared-reviews/21',method:'get',params:{afterRecordId:3,size:20}},
    {url:'/backend/prepared-reviews/21/attempts/attempt',method:'get'},
    {url:'/backend/prepared-reviews/21/attempts/attempt/answers/answer',method:'get',params:{afterResultId:'cursor',size:10}},
    {url:'/backend/prepared-reviews/21/attempts/attempt/answers/answer/confirm',method:'post',data:confirm},
    {url:'/backend/prepared-reviews/21/attempts/attempt/answers/answer/regrade',method:'post',data:regrade},
    {url:'/backend/prepared-reviews/21/attempts/attempt/answers/answer/audio',method:'get'},
  ]);
});
it('propagates rejected reads without fabricating an answer',async()=>{
  const failure=new Error('读取失败');vi.mocked(request).mockRejectedValue(failure);
  let caught:unknown;try{await getReviewAnswer(21,'a','b');}catch(error){caught=error;}
  expect(caught).toBe(failure);
});
it('posts the original human score command and reads bounded human history without fake model identity',async()=>{
  const command={idempotencyKey:'key',expectedRevision:0,score:8.5,reason:'人工依据'};
  await adjustReviewScore(21,'attempt','answer',command);await getReviewManualHistory(21,'attempt','answer','cursor');
  expect(vi.mocked(request).mock.calls.map(c=>c[0])).toEqual([
    {url:'/backend/prepared-reviews/21/attempts/attempt/answers/answer/adjust',method:'post',data:command},
    {url:'/backend/prepared-reviews/21/attempts/attempt/answers/answer/manual-history',method:'get',params:{afterManualScoreId:'cursor',size:10}},
  ]);
});
it('reads the scoped teacher attempt history using a bounded numeric keyset without a write',async()=>{
  await getReviewAttempts(21,8);await getReviewAttempts(21,8,3);
  expect(vi.mocked(request).mock.calls.map(c=>c[0])).toEqual([
    {url:'/backend/prepared-reviews/21/students/8/attempts',method:'get',params:{afterAttemptNo:0,size:10}},
    {url:'/backend/prepared-reviews/21/students/8/attempts',method:'get',params:{afterAttemptNo:3,size:10}},
  ]);
});
it('reads only bounded original declaration evidence without student identity or a mutation',async()=>{
  await getReviewEvidence(21,'attempt','answer');await getReviewEvidence(21,'attempt','answer',20);
  expect(vi.mocked(request).mock.calls.map(c=>c[0])).toEqual([
    {url:'/backend/prepared-reviews/21/attempts/attempt/answers/answer/evidence',method:'get',params:{afterSegmentOrdinal:0,size:20}},
    {url:'/backend/prepared-reviews/21/attempts/attempt/answers/answer/evidence',method:'get',params:{afterSegmentOrdinal:20,size:20}},
  ]);
});
