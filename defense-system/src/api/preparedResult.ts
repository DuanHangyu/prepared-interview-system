import request from '@/utils/request';
import type {AxiosResponse} from 'axios';

export type StudentResultState='LEGACY'|'NOT_STARTED'|'IN_PROGRESS'|'UPLOAD_PENDING'|'REQUIRES_REVIEW'|'CANCELLED'|'PROCESSING'|'HIDDEN'|'READY';
export type StudentResultGrade={answerId:string;ordinal:number;maxScore:number;score:number;scoringSource:'MODEL'|'TEACHER';version:number;confirmed:boolean};
export type StudentResultSummary={studentId:number;assessmentId:number;theme:string;prepared:boolean;state:StudentResultState;attemptId:string|null;
  resultVersion:string|null;completedAt:string|null;totalScore:number|null;totalMaxScore:number|null;passScore:number|null;answers:StudentResultGrade[]};
export type StudentResultAnswer=StudentResultGrade & {studentId:number;assessmentId:number;attemptId:string;resultVersion:string;body:string;
  feedback:string|null;transcript:{sentences:{startMs:number;endMs:number;text:string}[]}|null};
export type StudentResultReport={schemaVersion:1;summary:StudentResultSummary;answers:StudentResultAnswer[]};
const path=(assessment:number)=>{
  if(!Number.isSafeInteger(assessment)||assessment<1||assessment>2147483647)throw new Error('考核标识无效');
  return `/backend/prepared-results/${assessment}`;
};
/** Read-only: student identity and current attempt are selected by the authenticated server. */
export function getPreparedStudentResult(assessment:number,signal?:AbortSignal){
  return request<unknown,{data:StudentResultSummary}>({url:path(assessment),method:'get',timeout:10000,signal});
}
export function getPreparedStudentResultAnswer(assessment:number,answer:string,resultVersion:string,signal?:AbortSignal){
  const url=path(assessment);
  if(typeof answer!=='string'||!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(answer)
    ||typeof resultVersion!=='string'||!/^[a-f0-9]{64}$/.test(resultVersion))throw new Error('原结果版本或回答标识无效');
  return request<unknown,{data:StudentResultAnswer}>({url:`${url}/answers/${answer}`,method:'get',params:{resultVersion},timeout:10000,signal});
}
function reportPath(assessment:number,resultVersion:string,suffix:string){
  const url=path(assessment);
  if(typeof resultVersion!=='string'||!/^[a-f0-9]{64}$/.test(resultVersion))throw new Error('原结果版本无效');
  return `${url}/${suffix}`;
}
export function getPreparedStudentResultReport(assessment:number,resultVersion:string,signal?:AbortSignal){
  return request<unknown,{data:StudentResultReport}>({url:reportPath(assessment,resultVersion,'report'),method:'get',params:{resultVersion},timeout:10000,signal});
}
export function exportPreparedStudentResultReport(assessment:number,resultVersion:string,signal?:AbortSignal){
  return request<unknown,AxiosResponse<Blob>>({url:reportPath(assessment,resultVersion,'export.xlsx'),method:'get',params:{resultVersion},responseType:'blob',timeout:10000,signal});
}
