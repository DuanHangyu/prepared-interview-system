import request,{ type CallerOwnedRequestConfig } from '@/utils/request';
import { checkRetentionScope,copyRetentionCommand } from '@/prepared/retentionProof';
export type RetentionScope={actorId:number;studentId:number;assessmentId:number;attemptId:string};
export type RetentionCommand={idempotencyKey:string;expectedRevision:number;action:'PROTECT'|'RELEASE';reason:string};
export type RetentionReceipt=RetentionScope & RetentionCommand & {id:string;revision:number;held:boolean;executedAt:string;audioState:'RETAINED'|'RETIRED';resultsState:'RETAINED'};
export type RetentionView=RetentionScope & {revision:number;held:boolean;audioState:'RETAINED'|'RETIRING'|'RETIRED';resultsState:'RETAINED'|'RETIRING';mayChange:boolean;serverNow:string;latest:RetentionReceipt|null};
const path=(input:RetentionScope)=>{const s=checkRetentionScope(input);return `/backend/prepared-reviews/${s.assessmentId}/students/${s.studentId}/attempts/${s.attemptId}/retention-protection`;};
export async function getRetentionProtection(scope:RetentionScope){const config:CallerOwnedRequestConfig={url:path(scope),method:'get',callerOwnsFailure:true};return request<unknown,{data:RetentionView}>(config);}
export async function changeRetentionProtection(scope:RetentionScope,command:RetentionCommand){const config:CallerOwnedRequestConfig={url:path(scope),method:'post',data:copyRetentionCommand(command),callerOwnsFailure:true};return request<unknown,{data:RetentionReceipt}>(config);}
