import router, { adminRoutes, studnetRoutes, teacherRoutes } from "./index";
import { message, Modal } from "ant-design-vue";
import NProgress from "nprogress";
import "nprogress/nprogress.css";
import { hasSession } from "@/utils/auth";
import { isPathMatch } from "@/utils/validate";
import { isRelogin } from "../utils/request";
import useSettingsStore from "@/stores/settings";
import { useAuthStore } from "@/stores/auth";
import {
  canAccessRole,
  defaultPathForRole,
  normalizeRole,
  requiredRolesForPath,
} from "./access";

NProgress.configure({ showSpinner: false });

const whiteList = ["/login"];

const isWhiteList = (path: string) => {
  return whiteList.some((pattern) => isPathMatch(pattern, path));
};

function registerRoutesForRole(role: unknown) {
  const normalized = normalizeRole(role);
  const routes =
    normalized === "ADMIN"
      ? adminRoutes
      : normalized === "TEACHER"
        ? teacherRoutes
        : normalized === "STUDENT"
          ? studnetRoutes
          : [];
  routes.forEach((route) => router.addRoute(route));
  return normalized;
}

router.beforeEach((to, _from, next) => {
  NProgress.start();
  if (hasSession()) {
    to.meta.title && useSettingsStore().setTitle(to.meta?.title as string);
    if (to.path === "/login") {
      const role = useAuthStore().role || useAuthStore().info?.role;
      next({ path: role ? defaultPathForRole(role) : "/" });
      NProgress.done();
    } else if (isWhiteList(to.path)) {
      next();
    } else if (!useAuthStore().id) {
      isRelogin.show = true;
      useAuthStore()
        .getInfo()
        .then((response: any) => {
          const role = registerRoutesForRole(response?.data?.role);
          isRelogin.show = false;
          if (!role) {
            useAuthStore().logout(false).finally(() => {
              message.error("账号角色无效，请联系管理员");
              next({ path: "/login" });
            });
            return;
          }
          next({ ...to, replace: true });
        })
        .catch((error) => {
          Modal.destroyAll();
          isRelogin.show = false;
          useAuthStore()
            .logout(false)
            .then(() => {
              message.error(error?.message || "登录状态已失效，请重新登录");
              next({ path: "/login" });
            });
        });
    } else {
      const role = useAuthStore().role || useAuthStore().info?.role;
      const requiredRoles = to.meta.role || requiredRolesForPath(to.path);
      if (!canAccessRole(requiredRoles, role)) {
        message.error("当前账号无权访问该页面");
        next({ path: defaultPathForRole(role), replace: true });
      } else {
        next();
      }
    }
  } else if (isWhiteList(to.path)) {
    next();
  } else {
    next(`/login?redirect=${to.fullPath}`);
    NProgress.done();
  }
});

router.afterEach(() => {
  NProgress.done();
});
