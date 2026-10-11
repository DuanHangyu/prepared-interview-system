import request from "@/utils/request";

// 删除考核
export function removeAssessment(data: Record<string, any>) {
  return request({
    url: "/backend/assessment/remove",
    method: "post",
    data,
  });
}

// 修改考核
export function modifyAssessment(data: Record<string, any>) {
  return request({
    url: "/backend/assessment/modify",
    method: "post",
    data,
  });
}

// 获取考核设置列表
export function getAssessmentList(data: Record<string, any>) {
  return request({
    url: "/backend/assessment/list",
    method: "post",
    data,
  });
}

// 创建考核
export function createAssessment(data: Record<string, any>) {
  return request({
    url: "/backend/assessment/create",
    method: "post",
    data,
  });
}

// 获取考核信息
export function getAssessmentDetail(assessmentId: number) {
  return request({
    url: "/assessment/detail",
    method: "get",
    params: { assessmentId },
  });
}

// 设置考核预约
export function settingAppointment(data: Record<string, any>) {
  return request({
    url: "/backend/assessment/settingAppointment",
    method: "post",
    data,
  });
}

// 解除惩罚
export function liftPunish(data: Record<string, any>) {
  return request({
    url: "/backend/assessment/lift-punish",
    method: "post",
    data,
  });
}

// 获取考核预约设置汇总
export function getSettingAppointment(id: number) {
  return request({
    url: "/backend/assessment/setting-appointment-summary",
    method: "get",
    params: { id },
  });
}

// 取消学生预约
export function cancelStudentAppointment(appointmentId: number) {
  return request({
    url: "/backend/assessment/cancel-student-appointment",
    method: "post",
    data: { appointmentId },
  });
}
