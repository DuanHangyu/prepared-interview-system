import request from "@/utils/request";

// 分页查询学生
export function getStudentPageList(data: Record<string, any>) {
  return request({
    url: "/backend/student/page-list",
    method: "post",
    data,
  });
}

// 修改学生
export function modifyStudent(data: Record<string, any>) {
  return request({
    url: "/backend/student/modify",
    method: "post",
    data,
  });
}

// 导入学生
export function importStudent(data: Record<string, any>) {
  return request({
    url: "/backend/student/import",
    method: "post",
    data,
    headers: {
      "Content-Type": "multipart/form-data;charset=UTF-8",
    },
  });
}

// 导出学生
export function exportStudent(data: Record<string, any>) {
  return request({
    url: "/backend/student/export",
    method: "post",
    data,
    responseType: "blob",
  });
}

// 下载模板
export function downloadTemplate() {
  return request({
    url: "/backend/student/export-template",
    method: "post",
    responseType: "blob",
  });
}

// 删除学生
export function deleteStudent(data: Record<string, any>) {
  return request({
    url: "/backend/student/delete",
    method: "post",
    data,
  });
}

// 创建学生
export function createStudent(data: Record<string, any>) {
  return request({
    url: "/backend/student/create",
    method: "post",
    data,
  });
}

// 获取班级学生
export function getSchoolClassStudent() {
  return request({
    url: "/backend/student/school-class-student",
    method: "get",
  });
}

// 按需获取单个班级的学生，避免打开考核表单时加载全校学生
export function getSchoolClassStudentOptions(schoolClass: string) {
  return request({
    url: "/backend/student/school-class-student-options",
    method: "get",
    params: { schoolClass },
  });
}
