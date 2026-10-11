// @vitest-environment node
import {it,expect} from 'vitest';
import {verifyUploadWindow} from '@/prepared/uploadWindow';
const answer={assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID(),captureClientId:crypto.randomUUID(),ordinal:1};
const paper=crypto.randomUUID(),hash='a'.repeat(64);
const approved={studentId:12,assessmentId:8,attemptId:answer.attemptId,answerId:answer.answerId,paperVersionId:paper,manifestSha256:hash,answerDeadline:'2026-10-03T00:02:00Z',answeringEndedAt:'2026-10-03T00:01:00Z',automaticDeadline:'2026-10-04T00:01:00Z',serverNow:'2026-10-04T01:00:00Z',phase:'APPROVED',mayUploadOriginal:true,approvalRevision:1,approvedAt:'2026-10-04T00:02:00Z',uploadDeadline:'2026-10-04T02:00:00Z'};
it('binds current identity and original manifest and keeps an early end independent of question deadline',()=>{
  expect(verifyUploadWindow(approved,12,answer,hash,paper)).toEqual(approved);
  expect(()=>verifyUploadWindow(approved,13,answer,hash,paper)).toThrow();expect(()=>verifyUploadWindow(approved,12,answer,'b'.repeat(64),paper)).toThrow();expect(()=>verifyUploadWindow(approved,12,answer,hash,crypto.randomUUID())).toThrow();
});
it('rejects malformed original scope, timestamps, deadlines, revisions, phase claims and missing fields',()=>{
  for(const change of [{studentId:13},{assessmentId:9},{attemptId:crypto.randomUUID()},{answerId:crypto.randomUUID()},{paperVersionId:'../other'},{manifestSha256:'BAD'},{answerDeadline:'2026-02-30T00:00:00Z'},{serverNow:'2026-10-04T01:00:00+08:00'},{automaticDeadline:'2026-10-05T00:01:00Z'},{answeringEndedAt:null},{approvalRevision:0},{approvalRevision:Number.MAX_SAFE_INTEGER+1},{approvedAt:'2026-10-03T00:00:00Z'},{uploadDeadline:'2026-10-04T00:02:00Z'},{serverNow:approved.uploadDeadline},{mayUploadOriginal:false},{phase:'SAVED'},{phase:'EXPIRED'},{phase:'AWAITING_APPROVAL'},{approvedAt:null},{uploadDeadline:null},{phase:undefined}])expect(()=>verifyUploadWindow({...approved,...change},12,answer,hash)).toThrow();
});
it('accepts only coherent answering, automatic, waiting, expired and ineligible read states',()=>{
  const noApproval={...approved,approvalRevision:0,approvedAt:null,uploadDeadline:approved.automaticDeadline};
  for(const v of [{...noApproval,phase:'ANSWERING',answeringEndedAt:null,automaticDeadline:null,uploadDeadline:null,manifestSha256:null},{...noApproval,phase:'AUTOMATIC_WINDOW',serverNow:'2026-10-03T01:00:00Z'},{...noApproval,phase:'AWAITING_APPROVAL',mayUploadOriginal:false},{...approved,phase:'EXPIRED',mayUploadOriginal:false,serverNow:approved.uploadDeadline},{...approved,phase:'NOT_ELIGIBLE',mayUploadOriginal:false}])expect(verifyUploadWindow(v,12,answer)).toEqual(v);
  for(const v of [{...noApproval,phase:'ANSWERING'},{...noApproval,phase:'AUTOMATIC_WINDOW'},{...noApproval,phase:'AWAITING_APPROVAL',mayUploadOriginal:true},{...approved,phase:'NOT_ELIGIBLE',mayUploadOriginal:true}])expect(()=>verifyUploadWindow(v,12,answer)).toThrow();
  for(const v of [null,[],{}, {...approved,approvedAt:'2026-10-04T00:02:00.0001Z'}])expect(()=>verifyUploadWindow(v,12,answer)).toThrow();
});
