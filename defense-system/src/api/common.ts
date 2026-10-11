import request from "@/utils/request";

export interface ChangePasswordCommand {
  currentPassword: string;
  newPassword: string;
}

// 登录
export function loginApi(data: Record<string, any>) {
  return request({
    url: "/login",
    method: "post",
    data,
  });
}

// 退出登录并让服务端会话失效
export function logoutApi() {
  return request({
    url: "/logout",
    method: "post",
  });
}

// 获取用户详情
export function getUserInfo() {
  return request({
    url: "/user-detail",
    method: "get",
  });
}

// 上传文件
export function uploadFile(data: Record<string, any>) {
  return request({
    url: "/file/upload",
    method: "post",
    data,
  });
}

// 获取文件签名URL
export function getFileSign(params: Record<string, any>) {
  return request({
    url: "/file/signed-url",
    method: "get",
    params,
  });
}

// 预览文件
export function previewFile(fileUrl: string) {
  return request({
    url: "/file/preview",
    method: "get",
    params: { fileUrl },
    responseType: "blob",
  });
}

// 修改密码
export function changePassword(data: ChangePasswordCommand) {
  return request({
    url: "/change-password",
    method: "post",
    data,
  });
}
