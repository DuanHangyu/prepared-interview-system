<template>
  <header class="student-header">
    <section class="student-brand" @click="$router.push('/')">
      <div class="student-brand-mark">
        <img src="/logo.png" alt="AI 面试评估系统" />
      </div>
      <div class="student-brand-copy">
        <div class="student-brand-name">AI 面试评估系统</div>
        <div class="student-brand-subtitle">{{ roleMeta.workspace }}</div>
      </div>
    </section>

    <Dropdown placement="bottomRight" trigger="hover" :arrow="false">
      <button class="student-user-trigger" type="button">
        <span class="student-user-avatar">
          <UserOutlined />
        </span>
        <span class="student-user-copy">
          <span class="student-user-name">
            {{ authStore?.info?.name || roleMeta.fallbackName }}
          </span>
          <span class="student-user-role">{{ roleMeta.accountLabel }}</span>
        </span>
        <DownOutlined class="student-user-arrow" />
      </button>
      <template #overlay>
        <Menu class="student-user-menu" @click="commandFn">
          <MenuItem key="1">
            <template #icon>
              <LockOutlined />
            </template>
            修改密码
          </MenuItem>
          <MenuItem key="2">
            <template #icon>
              <LogoutOutlined />
            </template>
            退出登录
          </MenuItem>
        </Menu>
      </template>
    </Dropdown>
    <UpdatePassword ref="updatePass" />
  </header>
</template>
<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";
import { Dropdown, Menu, MenuItem, MenuProps } from "ant-design-vue";
import {
  DownOutlined,
  LockOutlined,
  LogoutOutlined,
  UserOutlined,
} from "@ant-design/icons-vue";
import UpdatePassword from "./UpdatePassword.vue";
import { computed, ref } from "vue";

defineOptions({ name: "Header" });

const authStore = useAuthStore();
const updatePass = ref();

const roleMeta = computed(() => {
  const role = String(authStore.role || authStore.info?.role || "").toUpperCase();
  if (role === "ADMIN") {
    return {
      workspace: "Admin Workspace",
      accountLabel: "系统管理员",
      fallbackName: "管理员",
    };
  }
  if (role === "TEACHER") {
    return {
      workspace: "Teacher Workspace",
      accountLabel: "教师账号",
      fallbackName: "教师",
    };
  }
  return {
    workspace: "Student Workspace",
    accountLabel: "学生账号",
    fallbackName: "学生",
  };
});

const commandFn: MenuProps["onClick"] = ({ key }) => {
  if (key == 1) {
    updatePass.value?.openModal();
  } else {
    authStore.logout();
    window.location.href = "/login";
  }
};
</script>
<style scoped>
.student-header {
  min-height: 72px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border: 1px solid #e8f0fb;
  border-radius: 22px;
  background: #ffffff;
  box-shadow: 0 16px 42px rgba(38, 64, 102, 0.1);
}

.student-brand {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.student-brand-mark {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 14px;
  background: linear-gradient(135deg, #1597ff, #0078e7);
  box-shadow: 0 12px 26px rgba(21, 151, 255, 0.22);
}

.student-brand-mark img {
  width: 30px;
  height: 30px;
  object-fit: contain;
  border-radius: 8px;
}

.student-brand-name {
  color: #12233d;
  font-size: 17px;
  font-weight: 800;
  line-height: 1.2;
  white-space: nowrap;
}

.student-brand-subtitle {
  margin-top: 4px;
  color: #6b7c93;
  font-size: 12px;
  font-weight: 600;
}

.student-user-trigger {
  height: 48px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px 5px 6px;
  border: 1px solid #e3edf9;
  border-radius: 999px;
  background: #ffffff;
  color: #12233d;
  cursor: pointer;
  transition:
    transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1),
    box-shadow 180ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.student-user-trigger:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 28px rgba(54, 97, 150, 0.1);
}

.student-user-avatar {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: linear-gradient(135deg, #1597ff, #0078e7);
  color: #ffffff;
  box-shadow: 0 10px 22px rgba(21, 151, 255, 0.22);
}

.student-user-copy {
  min-width: 72px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.1;
}

.student-user-name {
  max-width: 120px;
  color: #12233d;
  font-size: 13px;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.student-user-role {
  margin-top: 4px;
  color: #6b7c93;
  font-size: 11px;
  font-weight: 600;
}

.student-user-arrow {
  color: #6b7c93;
  font-size: 11px;
}

:global(.student-user-menu) {
  min-width: 150px;
  border-radius: 14px;
  padding: 6px;
  box-shadow: 0 16px 42px rgba(20, 21, 22, 0.16);
}

@media (max-width: 640px) {
  .student-header {
    align-items: stretch;
    flex-direction: column;
  }

  .student-user-trigger {
    width: 100%;
  }

  .student-user-copy {
    flex: 1;
  }
}
</style>
