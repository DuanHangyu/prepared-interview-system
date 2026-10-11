import request from "@/utils/request";

// 分页查询教师
export function getTeacherPageList(data: Record<string, any>) {
    return request({
        url: "/backend/teacher/page-list",
        method: "post",
        data,
    });
}

// 修改教师
export function modifyTeacher(data: Record<string, any>) {
    return request({
        url: "/backend/teacher/modify",
        method: "post",
        data,
    });
}

// 删除教师
export function deleteTeacher(data: Record<string, any>) {
    return request({
        url: "/backend/teacher/delete",
        method: "post",
        data,
    });
}

// 创建教师
export function createTeacher(data: Record<string, any>) {
    return request({
        url: "/backend/teacher/create",
        method: "post",
        data,
    });
}

// 搜索教师选项（服务端最多返回 50 条）
export function getAllTeachers(keyword = "") {
    return request({
        url: "/backend/teacher/all-teacher",
        method: "get",
        params: { keyword }
    });
}
