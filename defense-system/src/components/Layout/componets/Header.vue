<template>
  <header class="console-header">
    <section class="console-brand" @click="$router.push('/')">
      <div class="console-brand-mark">
        <img src="/logo.png" alt="AI 面试评估系统" />
      </div>
      <div class="console-brand-copy">
        <div class="console-brand-name">AI 面试评估系统</div>
        <div class="console-brand-subtitle">Interview Console</div>
      </div>
    </section>

    <section class="console-status" aria-label="当前工作台">
      <span class="console-status-dot"></span>
      <span>管理工作台</span>
    </section>

    <div class="console-user">
      <Dropdown placement="bottomRight" trigger="hover" :arrow="false">
        <button class="console-user-trigger" type="button">
          <span class="console-user-avatar">
            <UserOutlined />
          </span>
          <span class="console-user-copy">
            <span class="console-user-name">
              {{ authStore?.info?.name || "用户" }}
            </span>
            <span class="console-user-role">{{ roleLabel }}</span>
          </span>
          <DownOutlined class="console-user-arrow" />
        </button>
        <template #overlay>
          <Menu class="console-user-menu" @click="commandFn">
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
    </div>
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
const roleLabel = computed(() => {
  const role = authStore?.role || authStore?.info?.role;
  if (role === "ADMIN") return "系统管理员";
  if (role === "TEACHER") return "教师账号";
  return "登录用户";
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
.console-header {
  min-height: 72px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 12px 16px 12px 18px;
  border: 1px solid rgba(255, 255, 255, 0.66);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.66);
  box-shadow:
    0 18px 52px rgba(20, 21, 22, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(18px);
}

.console-brand {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.console-brand-mark {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 16px;
  background: linear-gradient(145deg, #444544, #2f302f);
  box-shadow:
    0 12px 26px rgba(20, 21, 22, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
}

.console-brand-mark img {
  width: 30px;
  height: 30px;
  object-fit: contain;
  border-radius: 8px;
}

.console-brand-copy {
  min-width: 0;
}

.console-brand-name {
  color: var(--color-text-primary);
  font-size: 17px;
  font-weight: 800;
  line-height: 1.2;
  white-space: nowrap;
}

.console-brand-subtitle {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
}

.console-status {
  height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
  border: 1px solid rgba(24, 25, 27, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.54);
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.console-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--color-accent);
  box-shadow: 0 0 0 6px var(--color-accent-soft);
}

.console-user {
  flex-shrink: 0;
}

.console-user-trigger {
  height: 48px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px 5px 6px;
  border: 1px solid rgba(24, 25, 27, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.64);
  color: var(--color-text-primary);
  cursor: pointer;
  transition:
    transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1),
    box-shadow 180ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.console-user-trigger:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 28px rgba(20, 21, 22, 0.1);
}

.console-user-avatar {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: var(--color-accent);
  color: #ffffff;
  box-shadow: 0 10px 22px var(--color-accent-glow);
}

.console-user-copy {
  min-width: 72px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.1;
}

.console-user-name {
  max-width: 120px;
  color: var(--color-text-primary);
  font-size: 13px;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.console-user-role {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 600;
}

.console-user-arrow {
  color: var(--color-text-muted);
  font-size: 11px;
}

:global(.console-user-menu) {
  min-width: 150px;
  border-radius: 14px;
  padding: 6px;
  box-shadow: 0 16px 42px rgba(20, 21, 22, 0.16);
}

@media (max-width: 760px) {
  .console-header {
    align-items: stretch;
    flex-direction: column;
  }

  .console-status {
    align-self: flex-start;
  }

  .console-user-trigger {
    width: 100%;
  }

  .console-user-copy {
    flex: 1;
  }
}
</style>
