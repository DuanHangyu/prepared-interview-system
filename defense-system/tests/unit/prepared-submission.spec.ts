// @vitest-environment node
import {describe,expect,it} from 'vitest';
import {verifySubmission,type SubmissionScope} from '@/prepared/submission';
const scope:SubmissionScope={attemptId:'00000000-0000-4000-8000-000000000001',paperVersionId:'00000000-0000-4000-8000-000000000002',questionCount:3};
const saved=()=>({...scope,revision:4,phase:'SUBMITTED',submitted:true,pending:[],receipt:{id:'00000000-0000-4000-8000-000000000003',attemptId:scope.attemptId,paperVersionId:scope.paperVersionId,revision:4,answerCount:3,submittedAt:'2026-10-05T05:00:00Z'}});
describe('strict final receipt projection — synthetic responses, not cloud acceptance',()=>{
  it.each(['WAITING_ANSWERS','REQUIRES_REVIEW'])('rejects one answer identifier reused by two question ordinals in %s',phase=>{
    const answerId='00000000-0000-4000-8000-000000000010';
    const response={attemptId:scope.attemptId,paperVersionId:scope.paperVersionId,revision:3,phase,submitted:false,
      pending:[{ordinal:1,answerId,phase:'RECORDING'},{ordinal:2,answerId,phase:'QUEUED'}],receipt:null};
    expect(()=>verifySubmission(response,scope)).toThrow('原提交回执尚未核验');
  });
  it.each(['WAITING_ANSWERS','REQUIRES_REVIEW'])('rejects NOT_STARTED carrying an existing answer identifier in %s',phase=>{
    const response={attemptId:scope.attemptId,paperVersionId:scope.paperVersionId,revision:3,phase,submitted:false,
      pending:[{ordinal:1,answerId:'00000000-0000-4000-8000-000000000010',phase:'NOT_STARTED'}],receipt:null};
    expect(()=>verifySubmission(response,scope)).toThrow('原提交回执尚未核验');
  });
  it.each(['WAITING_ANSWERS','REQUIRES_REVIEW'])('allows multiple not-started questions with no answer identity and distinct existing answers in %s',phase=>{
    const response={attemptId:scope.attemptId,paperVersionId:scope.paperVersionId,revision:3,phase,submitted:false,
      pending:[{ordinal:1,answerId:null,phase:'NOT_STARTED'},{ordinal:2,answerId:null,phase:'NOT_STARTED'},
        {ordinal:3,answerId:'00000000-0000-4000-8000-000000000010',phase:'QUEUED'}],receipt:null};
    const checked=verifySubmission(response,scope);
    expect(checked.pending).toEqual(response.pending);
    expect(checked.pending).not.toBe(response.pending);
    expect(checked.pending[0]).not.toBe(response.pending[0]);
    const distinct={...response,pending:[{ordinal:1,answerId:'00000000-0000-4000-8000-000000000010',phase:'RECORDING'},
      {ordinal:2,answerId:'00000000-0000-4000-8000-000000000011',phase:'RUNNING'}]};
    expect(verifySubmission(distinct,scope).pending).toEqual(distinct.pending);
  });
  it('retains a specifically paused original media task as pending approval, never READY or submitted',()=>{
    const original={attemptId:scope.attemptId,paperVersionId:scope.paperVersionId,revision:3,phase:'REQUIRES_REVIEW',submitted:false,pending:[{ordinal:1,answerId:scope.attemptId,phase:'WAITING_APPROVAL'}],receipt:null};
    expect(verifySubmission(original,scope).pending[0].phase).toBe('WAITING_APPROVAL');
    for(const changed of [{phase:'READY'},{submitted:true},{receipt:saved().receipt},{pending:[{ordinal:1,answerId:null,phase:'WAITING_APPROVAL'}]}])expect(()=>verifySubmission({...original,...changed},scope)).toThrow();
  });
  it('accepts one matching server final receipt separately from task completion',()=>{expect(verifySubmission(saved(),scope).receipt?.answerCount).toBe(3);});
  it('rejects false saved flags, wrong bindings, missing proof, incomplete count and malformed original version',()=>{
    for(const changed of [{submitted:false},{phase:'SAVED'},{receipt:null},{revision:0},{revision:4.5},{pending:[{ordinal:1,answerId:scope.attemptId,phase:'UPLOAD_PENDING'}]},
      {attemptId:scope.paperVersionId},{paperVersionId:scope.attemptId},{receipt:{...saved().receipt,id:'receipt'}},{receipt:{...saved().receipt,answerCount:2}},
      {receipt:{...saved().receipt,revision:3}},{receipt:{...saved().receipt,submittedAt:'not-time'}},{receipt:{...saved().receipt,attemptId:scope.paperVersionId}}])expect(()=>verifySubmission({...saved(),...changed},scope)).toThrow();
  });
  it('accepts actionable pending states but rejects READY with missing questions and no-success receipts',()=>{
    const pending={attemptId:scope.attemptId,paperVersionId:scope.paperVersionId,revision:3,phase:'WAITING_ANSWERS',submitted:false,pending:[{ordinal:1,answerId:null,phase:'NOT_STARTED'}],receipt:null};
    expect(verifySubmission(pending,scope).pending[0].ordinal).toBe(1);
    for(const changed of [{phase:'READY'},{submitted:true},{receipt:saved().receipt},{pending:[{ordinal:4,answerId:null,phase:'NOT_STARTED'}]},
      {pending:[...pending.pending,...pending.pending]},{pending:[{ordinal:1,answerId:'private-path',phase:'UPLOAD_PENDING'}]},{pending:[{ordinal:1,answerId:null,phase:'SUCCEEDED'}]}])expect(()=>verifySubmission({...pending,...changed},scope)).toThrow();
    expect(verifySubmission({...pending,phase:'READY',pending:[]},scope).phase).toBe('READY');
  });
});
