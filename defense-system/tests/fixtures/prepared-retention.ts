import type { RetentionScope,RetentionCommand,RetentionView,RetentionReceipt } from '@/api/preparedRetention';
export const scope:RetentionScope={actorId:7,studentId:8,assessmentId:21,attemptId:'11111111-1111-4111-8111-111111111111'};
export const command:RetentionCommand={idempotencyKey:'33333333-3333-4333-8333-333333333333',expectedRevision:0,action:'PROTECT',reason:'核验原提交后保护争议记录'};
export const receipt:RetentionReceipt={...scope,id:'44444444-4444-4444-8444-444444444444',...command,revision:1,held:true,executedAt:'2026-10-06T01:00:00Z',audioState:'RETAINED',resultsState:'RETAINED'};
export const view:RetentionView={...scope,revision:0,held:false,audioState:'RETAINED',resultsState:'RETAINED',mayChange:true,serverNow:'2026-10-06T01:00:00Z',latest:null};
export const protectedView:RetentionView={...view,revision:1,held:true,latest:receipt};
