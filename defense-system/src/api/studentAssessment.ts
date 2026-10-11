import request from "@/utils/request";
import { AxiosProgressEvent } from "axios";

// 获取待预约列表
export function getAppointmentList(data: Record<string, any>) {
  return request({
    url: "/front/student-assessment/to-appointment-list",
    method: "post",
    data,
  });
}

// 获取待考核列表
export function getAssessmentTodo(data: Record<string, any>) {
  return request({
    url: "/front/student-assessment/todo-list",
    method: "post",
    data,
  });
}

// 获取已完成列表
export function getAssessmentDone(data: Record<string, any>) {
  return request({
    url: "/front/student-assessment/done-list",
    method: "post",
    data,
  });
}

// 获取分析中列表
export function getAssessmentAnalysis(data: Record<string, any>) {
  return request({
    url: "/front/student-assessment/analysis-list",
    method: "post",
    data,
  });
}

// 获取学生考核统计信息
export function getAssessmentStatistic() {
  return request({
    url: "/front/student-assessment/statistic",
    method: "get",
  });
}

// 上传答辩文件
export function uploadAssessmentFile(data: any) {
  return request({
    url: "/front/student-assessment/upload-file",
    method: "post",
    data,
  });
}

// 预约考核
export function appointment(data: any) {
  return request({
    url: "/front/student-assessment/appointment",
    method: "post",
    data,
  });
}

// 获取考核信息
export function getStudentAssessmentDetail(params: any) {
  return request({
    url: "/front/student-assessment/assessment-detail",
    method: "get",
    params,
  });
}

// 获取当前时间
export function getMachineTime() {
  return request({
    url: "/front/student-assessment/get-machine-time",
    method: "get",
  });
}

// 取消预约
export function cancelAppointment(assessmentId: number) {
  return request({
    url: "/front/student-assessment/cancel-appointment",
    method: "post",
    data: { assessmentId },
  });
}
