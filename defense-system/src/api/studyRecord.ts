import request from "@/utils/request";

// 获取学习记录列表
export function getStudyRecordList(data: Record<string, any>) {
  return request({
    url: "/backend/study-record/list",
    method: "post",
    data,
  });
}

// 修改学生记录得分
export function changeScore(data: Record<string, any>) {
  return request({
    url: "/backend/study-record/change-score",
    method: "post",
    data,
  });
}

// 获取考核信息
export function getStudyDetail(params: Record<string, any>) {
  return request({
    url: "/backend/study-record/assessment-detail",
    method: "get",
    params,
  });
}

// 导出学习记录
export function exportStudentRecord(data: Record<string, any>) {
  return request({
    url: "/backend/study-record/export-record",
    method: "post",
    data,
    responseType: "blob",
  });
}

// 重新考核
export function retakeApi(data: Record<string, any>) {
  return request({
    url: "/backend/study-record/retake",
    method: "post",
    data,
  });
}