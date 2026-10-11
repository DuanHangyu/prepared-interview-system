import { expect,it } from 'vitest';
import { checkSupplementScope,copySupplementCommand,checkSupplementView,checkSupplementReceipt,localDeadlineToUtc } from '@/prepared/supplementProof';
import { scope,command,view,receipt,approvedView,localInput } from '../fixtures/prepared-supplement';
it('binds approval to original scope, two exact versions, original times and immutable command rather than storage success',()=>{
  expect(checkSupplementScope(scope)).toEqual(scope);expect(copySupplementCommand(command)).toEqual(command);
  expect(checkSupplementView(view,scope)).toEqual(view);expect(checkSupplementView(approvedView,scope)).toEqual(approvedView);
  expect(checkSupplementReceipt(receipt,scope,command,view)).toEqual(receipt);
  expect(checkSupplementView({...approvedView,latest:{...receipt,actorId:9}},scope).latest?.actorId).toBe(9);
  expect(localDeadlineToUtc(localInput(command.uploadDeadline))).toBe(command.uploadDeadline);
});
it.each([{actorId:0},{studentId:NaN},{assessmentId:2147483648},{attemptId:'bad'},{answerId:scope.attemptId.toUpperCase().replace('1','A')}])('rejects unsafe or missing scope %j',changed=>expect(()=>checkSupplementScope({...scope,...changed})).toThrow());
it.each([null,[],{...command,extra:'token'},{...command,idempotencyKey:'bad'},{...command,expectedRevision:-1},{...command,expectedRevision:Number.MAX_SAFE_INTEGER},{...command,expectedAttemptRevision:1.1},{...command,expectedAttemptRevision:Number.MAX_SAFE_INTEGER},{...command,manifestSha256:'A'.repeat(64)},{...command,reason:''},{...command,reason:'x'.repeat(1001)},{...command,reason:'\u0000bad'},{...command,uploadDeadline:'2026-02-30T01:00:00Z'},{...command,uploadDeadline:'2026-10-06T01:00:00+00:00'},{...command,uploadDeadline:'2026-10-06T01:00:00.0001Z'},{...command,uploadDeadline:'0999-01-01T00:00:00Z'}])('rejects malformed original command %#',input=>expect(()=>copySupplementCommand(input)).toThrow());
it.each([{actorId:9},{studentId:9},{assessmentId:22},{attemptId:scope.answerId},{answerId:scope.attemptId},{approvalRevision:-1},{attemptRevision:Number.MAX_SAFE_INTEGER+1},{phase:'SAVED_BY_APPROVAL'},{mayApprove:'true'},{eligibilityIssue:'unexpected while permitted'},{manifestSha256:null},{automaticDeadline:null},{serverNow:'2026-10-04T11:59:59Z'},{answerState:'SAVED'}])('rejects untrusted or ineligible approval view %j',changed=>expect(()=>checkSupplementView({...view,...changed},scope)).toThrow());
it.each([{approvalRevision:2},{attemptRevision:11},{manifestSha256:'b'.repeat(64)},{answerDeadline:'2026-10-03T09:00:00Z'},{automaticDeadline:'2026-10-04T11:00:00Z'}])('rejects mismatched latest original proof %j',changed=>expect(()=>checkSupplementView({...approvedView,latest:{...receipt,...changed}},scope)).toThrow());
it('accepts closed, incomplete, complete and expired states without inventing permission or a latest receipt',()=>{
  for(const changed of [
    {phase:'NOT_ELIGIBLE' as const,manifestSha256:null,automaticDeadline:null},
    {phase:'AUTOMATIC_WINDOW' as const,serverNow:'2026-10-04T11:00:00Z'},
    {phase:'COMPLETE' as const,answerState:'SAVED'},
  ])expect(checkSupplementView({...view,...changed,mayApprove:false,eligibilityIssue:'另行人工核验'},scope).mayApprove).toBe(false);
  expect(checkSupplementView({...approvedView,phase:'EXPIRED',serverNow:'2026-10-07T01:00:00Z'},scope).latest).toEqual(receipt);
});
it('accepts students ending every question early: original question deadline may follow recognized overall end',()=>{
  const endedEarly={...receipt,answerDeadline:'2026-10-03T12:02:00Z'},status={...view,answerDeadline:endedEarly.answerDeadline};
  expect(checkSupplementReceipt(endedEarly,scope,command,status)).toEqual(endedEarly);
  expect(checkSupplementView({...approvedView,answerDeadline:endedEarly.answerDeadline,latest:endedEarly},scope).latest).toEqual(endedEarly);
});
it.each([{actorId:9},{studentId:9},{assessmentId:22},{attemptId:scope.answerId},{answerId:scope.attemptId},{id:'bad'},{paperVersionId:'bad'},{idempotencyKey:scope.attemptId},{approvalRevision:2},{attemptRevision:11},{manifestSha256:'b'.repeat(64)},{reason:'different'},{uploadDeadline:'2026-10-07T01:00:00Z'},{answerDeadline:'2026-10-03T09:00:00Z'},{automaticDeadline:'2026-10-04T13:00:00Z'},{approvedAt:'2026-10-04T11:00:00Z'},{answeringEndedAt:'2026-10-03T11:00:00Z'}])('cannot clear original intent from a mismatched approval receipt %j',changed=>expect(()=>checkSupplementReceipt({...receipt,...changed},scope,command,view)).toThrow());
it.each(['','not a date','0999-01-01T00:00','2026-02-30T01:00','2026-10-06T01:00Z','2026-10-06T01:00:00.001'])('requires explicit valid local deadline %s',value=>expect(()=>localDeadlineToUtc(value)).toThrow());
