import { defineStore } from "pinia";
import { clearSession, hasSession, markSession } from "@/utils/auth";
import { getUserInfo, loginApi, logoutApi } from "@/api/common";

export const useAuthStore = defineStore("auth", {
  state: () => ({
    authenticated: hasSession(),
    id: "",
    info: {} as Record<string, any>,
    role: ''
  }),

  actions: {
    // 登录
    login(userInfo: Record<string, any>) {
      const account = userInfo.account.trim();
      const password = userInfo.password;
      return new Promise<void>((resolve, reject) => {
        loginApi({
          account,
          password,
        })
          .then(() => {
            markSession();
            this.authenticated = true;
            resolve();
          })
          .catch((error) => {
            reject(error);
          });
      });
    },
    // 获取用户信息
    getInfo() {
      return new Promise((resolve, reject) => {
        getUserInfo()
          .then((res) => {
            const user = res.data;
            this.id = user.id;
            this.info = user;
            this.role = user?.role
            resolve(res);
          })
          .catch((error) => {
            reject(error);
          });
      });
    },
    async logout(revokeServer = true) {
      try {
        if (revokeServer && this.authenticated) {
          await logoutApi();
        }
      } finally {
        this.authenticated = false;
        this.id = "";
        this.info = {};
        this.role = "";
        clearSession();
      }
    },
  },
});
