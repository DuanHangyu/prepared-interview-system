import request,{type CallerOwnedRequestConfig} from '@/utils/request';
export type PreparedEligibility={assessmentId:number;theme:string;questionCount:number;publicationReady:boolean;appointmentRequired:boolean;
  phase:'PAPER_NOT_PUBLISHED'|'APPOINTMENT_REQUIRED'|'BEFORE_APPOINTMENT'|'APPOINTMENT_WINDOW_EXPIRED'|'APPOINTMENT_STATE_CONFLICT'|'HISTORY_REQUIRES_REVIEW'|'READY_FOR_PREFLIGHT';
  message:string;serverTime:string;appointmentStartsAt:string|null;latestFirstStartAt:string|null};
export type PreparedPreflightContext={studentId:number;deploymentId:string;eligibility:PreparedEligibility;workflowEnabled?:boolean};
export function getPreparedPreflightContext(assessmentId:number,signal?:AbortSignal){
  return request<unknown,{data:PreparedPreflightContext}>({url:`/backend/prepared-interviews/${assessmentId}/preflight-context`,method:'get',timeout:10000,signal,callerOwnsFailure:true} as CallerOwnedRequestConfig);
}
