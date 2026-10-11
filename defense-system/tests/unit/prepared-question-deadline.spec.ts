// @vitest-environment node
import {describe,expect,it} from 'vitest';
import {PreparedQuestionDeadline} from '@/prepared/questionDeadline';
import type {PreparedQuestionGrant} from '@/api/preparedAttempt';
const scope={assessmentId:8,attemptId:'00000000-0000-4000-8000-000000000001',answerId:'00000000-0000-4000-8000-000000000002'};
const grant=():PreparedQuestionGrant=>({...scope,paperVersionId:crypto.randomUUID(),questionId:crypto.randomUUID(),ordinal:1,body:'当前题',answerSeconds:120,state:'RECORDING',startedAt:'2026-10-05T00:00:00Z',answerDeadline:'2026-10-05T00:02:00Z',serverTime:'2026-10-05T00:00:30Z',mayRecord:true});
describe('server original per-question deadline — no whole-interview clock',()=>{
  it('subtracts full request latency and follows elapsed time even when offline',()=>{
    let mono=3000,wall=103000;
    const d=new PreparedQuestionDeadline(grant(),scope,{mono:1000,wall:101000},{mono:()=>mono,wall:()=>wall});
    expect(d.remainingMs()).toBe(88000);mono+=87000;wall+=87000;expect(d.remainingMs()).toBe(1000);
    mono+=1000;wall+=1000;expect(d.remainingMs()).toBe(0);
  });
  it('never extends time when wall clock rolls back or changes again after a forward jump',()=>{
    let mono=0,wall=1000;
    const d=new PreparedQuestionDeadline(grant(),scope,{mono:0,wall:1000},{mono:()=>mono,wall:()=>wall});
    wall+=80000;expect(d.remainingMs()).toBe(10000);wall=0;mono=5000;expect(d.remainingMs()).toBe(10000);
    mono=90001;expect(d.remainingMs()).toBe(0);
  });
  it('counts sleep/forward wall time even when monotonic clock pauses',()=>{
    let wall=1000;const d=new PreparedQuestionDeadline(grant(),scope,{mono:0,wall},{mono:()=>0,wall:()=>wall});
    wall+=100000;expect(d.remainingMs()).toBe(0);
  });
  it('rejects missing permission, future server start, changed duration or scope rather than resetting',()=>{
    for(const patch of [{mayRecord:false},{state:'UPLOAD_PENDING'},{assessmentId:9},{answerId:crypto.randomUUID()},{answerSeconds:121},{serverTime:'2026-10-04T00:00:00Z'},{answerDeadline:'invalid'},{body:''}] as const)
      expect(()=>new PreparedQuestionDeadline({...grant(),...patch} as PreparedQuestionGrant,scope,{mono:0,wall:0},{mono:()=>0,wall:()=>0})).toThrow();
  });
  it('an expired original grant stays expired; invalid clock values fail closed',()=>{
    const g={...grant(),serverTime:'2026-10-05T00:02:00Z'};
    expect(new PreparedQuestionDeadline(g,scope,{mono:0,wall:0},{mono:()=>0,wall:()=>0}).remainingMs()).toBe(0);
    expect(()=>new PreparedQuestionDeadline(grant(),scope,{mono:NaN,wall:0},{mono:()=>0,wall:()=>0})).toThrow();
    let mono=0;const d=new PreparedQuestionDeadline(grant(),scope,{mono:0,wall:0},{mono:()=>mono,wall:()=>0});mono=NaN;
    expect(d.remainingMs()).toBe(0);mono=0;expect(d.remainingMs()).toBe(0);
  });
});
