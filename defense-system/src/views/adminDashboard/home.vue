<template>
  <div class="dashboard-shell">
    <aside class="dashboard-sidebar">
      <div class="dashboard-sidebar-head">
        <div class="dashboard-sidebar-icon">
          <AppstoreOutlined />
        </div>
        <div>
          <div class="dashboard-sidebar-title">管理端</div>
          <div class="dashboard-sidebar-subtitle">Admin Console</div>
        </div>
      </div>

      <nav class="dashboard-nav" aria-label="管理端导航">
        <button
        v-for="item in tabs"
        :key="item.key"
          type="button"
          class="dashboard-nav-item"
          :class="activeTab == item.key ? 'is-active' : ''"
        @click="changeTab(item.key)"
      >
          <span class="dashboard-nav-icon">
            <component :is="item.icon" />
          </span>
          <span class="dashboard-nav-label">{{ item?.label }}</span>
        </button>
      </nav>

      <div class="dashboard-sidebar-card">
        <div class="dashboard-sidebar-card-title">AI Interview</div>
        <p>统一管理学生、教师、考核与评估记录。</p>
      </div>
    </aside>

    <section class="dashboard-main">
      <header class="dashboard-main-header">
        <div>
          <div class="dashboard-eyebrow">Backoffice</div>
          <h1>{{ activeTabInfo?.label }}</h1>
        </div>
        <div class="dashboard-main-tools">
          <div class="dashboard-main-meta">
            <span>{{ tabs.length }} 个模块</span>
          </div>
          <Dropdown placement="bottomRight" trigger="hover" :arrow="false">
            <button class="dashboard-user-trigger" type="button">
              <span class="dashboard-user-avatar">
                <UserOutlined />
              </span>
              <span class="dashboard-user-copy">
                <span class="dashboard-user-name">
                  {{ authStore?.info?.name || "管理员" }}
                </span>
                <span class="dashboard-user-role">系统管理员</span>
              </span>
              <DownOutlined class="dashboard-user-arrow" />
            </button>
            <template #overlay>
              <Menu class="console-user-menu" @click="commandFn">
                <MenuItem key="password">
                  <template #icon>
                    <LockOutlined />
                  </template>
                  修改密码
                </MenuItem>
                <MenuItem key="logout">
                  <template #icon>
                    <LogoutOutlined />
                  </template>
                  退出登录
                </MenuItem>
              </Menu>
            </template>
          </Dropdown>
        </div>
      </header>

      <nav class="dashboard-flow-stepper admin-module-overview" aria-label="管理端模块概览">
        <span
          v-for="(item, index) in tabs"
          :key="`overview-${item.key}`"
          :class="{ active: activeTab == item.key }"
        >
          <i>
            <component :is="item.icon" />
          </i>
          <strong>{{ item.label }}</strong>
          <small>{{ adminModuleDescriptions[item.key] }}</small>
        </span>
      </nav>

      <div class="dashboard-main-body">
        <StudentManage v-if="activeTab == 0" />
        <TeacherManage v-else-if="activeTab == 1" />
        <StudyRecord v-else-if="activeTab == 2" />
        <AssessSetting v-else-if="activeTab == 3" />
      </div>
    </section>
    <UpdatePassword ref="updatePass" />
  </div>
</template>
<script setup lang="ts">
import { computed, markRaw, ref } from "vue";
import { Dropdown, Menu, MenuItem, MenuProps } from "ant-design-vue";
import AssessSetting from "../teacherDashboard/AssessSetting.vue";
import StudentManage from "../teacherDashboard/StudentManage.vue";
import StudyRecord from "../teacherDashboard/StudyRecord.vue";
import TeacherManage from "./index.vue";
import UpdatePassword from "@/components/Layout/componets/UpdatePassword.vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import {
  AppstoreOutlined,
  BarChartOutlined,
  DownOutlined,
  LockOutlined,
  LogoutOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
  UserSwitchOutlined,
} from "@ant-design/icons-vue";

defineOptions({
  name: "AdminDashboard",
});

const tabs = ref([
  {
    label: "学生信息管理",
    key: 0,
    component: StudentManage,
    icon: markRaw(TeamOutlined),
  },
  {
    label: "教师管理",
    key: 1,
    component: TeacherManage,
    icon: markRaw(UserSwitchOutlined),
  },
  {
    label: "考核记录",
    key: 2,
    component: StudyRecord,
    icon: markRaw(BarChartOutlined),
  },
  {
    label: "考核设置",
    key: 3,
    component: AssessSetting,
    icon: markRaw(SettingOutlined),
  },
]);
const adminModuleDescriptions: Record<number, string> = {
  0: "学生账号与班级",
  1: "教师账号维护",
  2: "成绩与报告记录",
  3: "考核规则配置",
};

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const updatePass = ref();
const initialTab = Number(route.query?.tab || 0);
const activeTab = ref(
  tabs.value.some((item) => item.key === initialTab) ? initialTab : 0
);
const activeTabInfo = computed(() =>
  tabs.value.find((item) => item.key === activeTab.value)
);
const changeTab = (e: number) => {
  activeTab.value = e;
  router.push(`/adminDashboard?tab=${e}`);
};

const commandFn: MenuProps["onClick"] = ({ key }) => {
  if (key === "password") {
    updatePass.value?.openModal();
    return;
  }
  authStore.logout();
  window.location.href = "/login";
};
</script>
<style scoped>
.dashboard-shell {
  width: 100%;
  height: 100%;
  display: flex;
  gap: 16px;
  overflow: hidden;
}

.dashboard-sidebar {
  width: 184px;
  min-width: 184px;
  display: flex;
  flex-direction: column;
  padding: 18px 14px;
  border-radius: 24px;
  background: linear-gradient(180deg, #3d3e3d, #2d2e2d);
  color: var(--color-text-inverse);
  box-shadow:
    0 24px 70px rgba(18, 19, 20, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.dashboard-sidebar-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 4px 18px;
}

.dashboard-sidebar-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 999px;
  background: #ffffff;
  color: var(--color-accent);
}

.dashboard-sidebar-title {
  font-size: 14px;
  font-weight: 800;
  line-height: 1.2;
}

.dashboard-sidebar-subtitle {
  margin-top: 4px;
  color: rgba(255, 255, 255, 0.48);
  font-size: 11px;
  font-weight: 600;
}

.dashboard-nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}

.dashboard-nav-item {
  width: 100%;
  min-height: 46px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: rgba(255, 255, 255, 0.6);
  cursor: pointer;
  text-align: left;
  transition:
    background-color 180ms ease,
    color 180ms ease,
    transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.dashboard-nav-item:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.08);
  color: #ffffff;
}

.dashboard-nav-item.is-active {
  background: rgba(255, 255, 255, 0.14);
  color: #ffffff;
}

.dashboard-nav-icon {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
}

.dashboard-nav-item.is-active .dashboard-nav-icon {
  background: #ffffff;
  color: var(--color-accent);
}

.dashboard-nav-label {
  min-width: 0;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.25;
}

.dashboard-sidebar-card {
  padding: 14px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.08);
}

.dashboard-sidebar-card-title {
  font-size: 13px;
  font-weight: 800;
}

.dashboard-sidebar-card p {
  margin: 8px 0 0;
  color: rgba(255, 255, 255, 0.58);
  font-size: 12px;
  line-height: 1.6;
}

.dashboard-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 24px;
  border: 1px solid var(--color-border-light);
  border-radius: 28px;
  background: var(--color-surface);
  box-shadow:
    0 28px 80px rgba(20, 21, 22, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(22px);
  overflow: hidden;
}

.dashboard-main-header {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.dashboard-eyebrow {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  background: var(--color-accent-soft);
  color: var(--color-accent);
  font-size: 12px;
  font-weight: 800;
}

.dashboard-main-header h1 {
  margin: 10px 0 0;
  color: var(--color-text-primary);
  font-size: 30px;
  font-weight: 800;
  line-height: 1.1;
}

.dashboard-main-meta {
  height: 34px;
  display: inline-flex;
  align-items: center;
  padding: 0 12px;
  border: 1px solid rgba(24, 25, 27, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.58);
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.dashboard-main-tools {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dashboard-user-trigger {
  height: 46px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px 5px 6px;
  border: 1px solid rgba(24, 25, 27, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.58);
  color: var(--color-text-primary);
  cursor: pointer;
  transition:
    transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1),
    box-shadow 180ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.dashboard-user-trigger:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 28px rgba(20, 21, 22, 0.1);
}

.dashboard-user-avatar {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 999px;
  background: var(--color-accent);
  color: #ffffff;
  box-shadow: 0 10px 22px var(--color-accent-glow);
}

.dashboard-user-copy {
  min-width: 72px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.1;
}

.dashboard-user-name {
  max-width: 120px;
  overflow: hidden;
  color: var(--color-text-primary);
  font-size: 13px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-user-role {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 600;
}

.dashboard-user-arrow {
  color: var(--color-text-muted);
  font-size: 11px;
}

.dashboard-main-body {
  min-height: 0;
  flex: 1;
  overflow: hidden;
}

:global(.console-user-menu) {
  min-width: 150px;
  padding: 6px;
  border-radius: 14px;
  box-shadow: 0 16px 42px rgba(20, 21, 22, 0.16);
}

@media (max-width: 900px) {
  .dashboard-shell {
    flex-direction: column;
    overflow: auto;
  }

  .dashboard-sidebar {
    width: 100%;
    min-width: 0;
  }

  .dashboard-nav {
    flex-direction: row;
    overflow-x: auto;
  }

  .dashboard-nav-item {
    min-width: 138px;
  }

  .dashboard-sidebar-card {
    display: none;
  }

  .dashboard-main {
    min-height: 720px;
    padding: 18px;
  }

  .dashboard-main-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .dashboard-main-tools,
  .dashboard-user-trigger {
    width: 100%;
  }

  .dashboard-main-tools {
    align-items: stretch;
    flex-direction: column;
  }

  .dashboard-user-copy {
    flex: 1;
  }
}

/* Admin console refresh: align with the student and teacher blue-white SaaS shell. */
.dashboard-shell {
  --edu-page: #f3f7fc;
  --edu-shell: #ffffff;
  --edu-main: #f7fbff;
  --edu-card: #ffffff;
  --edu-border: #e3edf9;
  --edu-border-soft: rgba(223, 234, 247, 0.72);
  --edu-primary: #1597ff;
  --edu-primary-deep: #0078e7;
  --edu-primary-soft: #dff0ff;
  --edu-primary-glow: rgba(21, 151, 255, 0.22);
  --edu-mint: #37c978;
  --edu-amber: #ffb33f;
  --edu-text: #12233d;
  --edu-main-text: #27364b;
  --edu-muted: #6b7c93;

  min-height: 100%;
  height: auto;
  display: grid;
  grid-template-columns: 204px minmax(0, 1fr);
  gap: 0;
  border: 1px solid #e8f0fb;
  border-radius: 22px;
  background: var(--edu-shell);
  box-shadow: 0 24px 60px rgba(38, 64, 102, 0.12);
  overflow: visible;
}

.dashboard-sidebar {
  width: auto;
  min-width: 0;
  padding: 18px 14px;
  border-right: 1px solid var(--edu-border-soft);
  border-radius: 22px 0 0 22px;
  background: #ffffff;
  color: var(--edu-text);
  box-shadow: none;
}

.dashboard-sidebar-head {
  padding: 2px 4px 22px;
}

.dashboard-sidebar-icon {
  border-radius: 12px;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  color: #ffffff;
  box-shadow: 0 12px 24px var(--edu-primary-glow);
}

.dashboard-sidebar-title {
  color: var(--edu-text);
  font-size: 16px;
}

.dashboard-sidebar-subtitle {
  color: var(--edu-muted);
}

.dashboard-nav {
  gap: 6px;
}

.dashboard-nav-item {
  min-height: 44px;
  padding: 0 12px;
  border-radius: 8px;
  color: var(--edu-main-text);
}

.dashboard-nav-item:hover {
  transform: none;
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.dashboard-nav-item.is-active {
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  color: #ffffff;
  box-shadow: 0 10px 22px var(--edu-primary-glow);
}

.dashboard-nav-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: rgba(21, 151, 255, 0.1);
  color: var(--edu-primary-deep);
}

.dashboard-nav-item.is-active .dashboard-nav-icon {
  background: rgba(255, 255, 255, 0.18);
  color: #ffffff;
}

.dashboard-nav-label {
  font-size: 14px;
}

.dashboard-sidebar-card {
  border: 1px solid var(--edu-border);
  border-radius: 14px;
  background:
    radial-gradient(circle at 86% 18%, rgba(21, 151, 255, 0.1), transparent 30%),
    linear-gradient(180deg, #f2f8ff, #ffffff);
}

.dashboard-sidebar-card-title {
  color: var(--edu-primary-deep);
}

.dashboard-sidebar-card p {
  color: var(--edu-muted);
}

.dashboard-main {
  min-width: 0;
  padding: 18px;
  border: 0;
  border-radius: 0 22px 22px 0;
  background: var(--edu-main);
  box-shadow: none;
  backdrop-filter: none;
  overflow: visible;
}

.dashboard-main-header {
  min-height: 58px;
  align-items: center;
  margin-bottom: 12px;
}

.dashboard-eyebrow {
  height: 24px;
  border-radius: 999px;
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.dashboard-main-header h1 {
  margin-top: 8px;
  color: var(--edu-text);
  font-size: 28px;
  font-weight: 850;
}

.dashboard-main-meta,
.dashboard-user-trigger {
  border: 1px solid var(--edu-border);
  background: #ffffff;
  color: var(--edu-main-text);
  box-shadow: 0 8px 22px rgba(54, 97, 150, 0.06);
}

.dashboard-user-avatar {
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  box-shadow: 0 10px 22px var(--edu-primary-glow);
}

.dashboard-user-name {
  color: var(--edu-text);
}

.dashboard-user-role,
.dashboard-user-arrow {
  color: var(--edu-muted);
}

.dashboard-flow-stepper {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.dashboard-flow-stepper span {
  min-height: 54px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 4px 10px;
  padding: 8px 12px;
  border: 1px solid var(--edu-border);
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff, #f4f8fe);
  color: var(--edu-muted);
}

.dashboard-flow-stepper span.active {
  border-color: transparent;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  color: #ffffff;
  box-shadow: 0 10px 22px var(--edu-primary-glow);
}

.dashboard-flow-stepper i {
  grid-row: 1 / 3;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 10px;
  background: rgba(21, 151, 255, 0.12);
  color: var(--edu-primary-deep);
  font-style: normal;
  font-size: 16px;
  font-weight: 850;
}

.dashboard-flow-stepper span.active i {
  background: rgba(255, 255, 255, 0.24);
  color: #ffffff;
}

.dashboard-flow-stepper strong {
  min-width: 0;
  overflow: hidden;
  color: var(--edu-text);
  font-size: 14px;
  font-weight: 850;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-flow-stepper small {
  min-width: 0;
  overflow: hidden;
  color: var(--edu-muted);
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-flow-stepper span.active strong,
.dashboard-flow-stepper span.active small {
  color: #ffffff;
}

.dashboard-flow-stepper > span {
  min-height: 54px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 4px 10px;
  padding: 8px 12px;
  border: 1px solid var(--edu-border);
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff, #f4f8fe);
  color: var(--edu-muted);
}

.dashboard-flow-stepper > span.active {
  border-color: transparent;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  color: #ffffff;
  box-shadow: 0 10px 22px var(--edu-primary-glow);
}

.dashboard-flow-stepper > span.active i {
  background: rgba(255, 255, 255, 0.24);
  color: #ffffff;
}

.dashboard-flow-stepper i .anticon {
  width: 1rem;
  height: 1rem;
  min-height: 0;
  display: grid;
  grid-template-columns: none;
  place-items: center;
  gap: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: inherit;
  box-shadow: none;
  line-height: 1;
}

.dashboard-flow-stepper i .anticon svg {
  width: 1rem;
  height: 1rem;
  display: block;
}

.dashboard-main-body {
  padding: 0;
  overflow: visible;
}

.dashboard-shell :deep(.management-view) {
  gap: 12px;
  height: auto;
  overflow: visible;
}

.dashboard-shell :deep(.management-filter),
.dashboard-shell :deep(.management-card),
.dashboard-shell :deep(.setting-card) {
  border: 1px solid var(--edu-border);
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 8px 22px rgba(54, 97, 150, 0.08);
  backdrop-filter: none;
}

.dashboard-shell :deep(.management-filter) {
  padding: 16px;
  background:
    radial-gradient(circle at 96% 10%, rgba(21, 151, 255, 0.1), transparent 28%),
    linear-gradient(180deg, #edf6ff, #f8fbff);
}

.dashboard-shell :deep(.management-card) {
  min-height: 560px;
  padding: 16px;
  overflow: visible;
}

.dashboard-shell :deep(.management-table) {
  min-height: 420px;
}

.dashboard-shell :deep(.settings-list) {
  overflow: visible;
}

.dashboard-shell :deep(.management-toolbar-title) {
  color: var(--edu-text);
  font-size: 20px;
  font-weight: 850;
}

.dashboard-shell :deep(.management-toolbar-desc) {
  color: var(--edu-muted);
}

.dashboard-shell :deep(.ant-input),
.dashboard-shell :deep(.ant-input-affix-wrapper),
.dashboard-shell :deep(.ant-select-selector) {
  min-height: 38px;
  border-color: var(--edu-border) !important;
  border-radius: 8px !important;
  background: #ffffff !important;
  box-shadow: none !important;
}

.dashboard-shell :deep(.ant-input:hover),
.dashboard-shell :deep(.ant-input:focus),
.dashboard-shell :deep(.ant-input-affix-wrapper:hover),
.dashboard-shell :deep(.ant-input-affix-wrapper-focused),
.dashboard-shell :deep(.ant-select-focused .ant-select-selector) {
  border-color: rgba(21, 151, 255, 0.48) !important;
  box-shadow: 0 0 0 3px rgba(21, 151, 255, 0.12) !important;
}

.dashboard-shell :deep(.ant-form-item-label > label) {
  color: var(--edu-main-text);
  font-weight: 700;
}

.dashboard-shell :deep(.ant-btn) {
  border-radius: 8px;
  font-weight: 750;
}

.dashboard-shell :deep(.ant-btn-primary:not(.ant-btn-dangerous)) {
  border-color: var(--edu-primary);
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  box-shadow: 0 10px 22px var(--edu-primary-glow);
}

.dashboard-shell :deep(.ant-btn-background-ghost.ant-btn-primary) {
  border-color: rgba(21, 151, 255, 0.36);
  background: #ffffff !important;
  color: var(--edu-primary-deep);
  box-shadow: none;
}

.dashboard-shell :deep(.ant-btn-default) {
  border-color: var(--edu-border);
  background: #ffffff;
  color: var(--edu-main-text);
}

.dashboard-shell :deep(.management-view .ant-table) {
  border: 1px solid var(--edu-border);
  border-radius: 12px;
  background: #ffffff;
}

.dashboard-shell :deep(.management-view .ant-table-thead > tr > th) {
  border-bottom: 1px solid var(--edu-border) !important;
  background: #f5f9ff !important;
  color: var(--edu-main-text);
  font-weight: 800;
}

.dashboard-shell :deep(.management-view .ant-table-tbody > tr > td) {
  border-bottom: 1px solid var(--edu-border-soft) !important;
  color: var(--edu-main-text);
}

.dashboard-shell :deep(.management-view .ant-table-tbody > tr.ant-table-row:hover > td) {
  background: #f8fbff !important;
}

.dashboard-shell :deep(.action-cell) {
  flex-wrap: nowrap;
}

.dashboard-shell :deep(.score-orb) {
  background:
    radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.42), transparent 32%),
    linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  box-shadow: 0 10px 20px var(--edu-primary-glow);
}

.dashboard-shell :deep(.score-edit),
.dashboard-shell :deep(.setting-link) {
  color: var(--edu-primary-deep);
}

.dashboard-shell :deep(.settings-list) {
  padding-right: 2px;
}

.dashboard-shell :deep(.setting-card) {
  padding: 16px;
  background: linear-gradient(180deg, #edf6ff, #f8fbff);
}

.dashboard-shell :deep(.setting-card-title) {
  color: var(--edu-text);
  font-size: 18px;
  font-weight: 850;
}

.dashboard-shell :deep(.setting-card-title::before) {
  background: var(--edu-primary);
  box-shadow: 0 0 0 6px var(--edu-primary-soft);
}

.dashboard-shell :deep(.setting-field) {
  border-color: var(--edu-border);
  border-radius: 8px;
  background: #ffffff;
}

.dashboard-shell :deep(.setting-label) {
  color: var(--edu-muted);
}

.dashboard-shell :deep(.setting-value) {
  color: var(--edu-text);
}

@media (max-width: 900px) {
  .dashboard-shell {
    grid-template-columns: 1fr;
    height: auto;
    overflow: visible;
  }

  .dashboard-sidebar {
    width: 100%;
    border-right: 0;
    border-bottom: 1px solid var(--edu-border-soft);
    border-radius: 22px 22px 0 0;
  }

  .dashboard-main {
    border-radius: 0 0 22px 22px;
  }

  .dashboard-flow-stepper {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .dashboard-main {
    padding: 14px;
  }

  .dashboard-flow-stepper {
    grid-template-columns: 1fr;
  }
}

/* Proportion pass: keep the admin console visually aligned with the student/teacher workbench. */
.dashboard-shell {
  grid-template-columns: 180px minmax(0, 1fr);
  min-height: calc(100dvh - 40px);
  align-items: stretch;
  border-radius: 18px;
  box-shadow: 0 18px 42px rgba(38, 64, 102, 0.1);
}

.dashboard-sidebar {
  padding: 14px 12px;
  border-radius: 18px 0 0 18px;
}

.dashboard-sidebar-head {
  padding: 0 2px 14px;
}

.dashboard-sidebar-icon {
  width: 32px;
  height: 32px;
  border-radius: 10px;
}

.dashboard-sidebar-title {
  font-size: 15px;
}

.dashboard-sidebar-subtitle {
  margin-top: 2px;
  font-size: 10px;
}

.dashboard-nav {
  flex: 0 0 auto;
  gap: 4px;
}

.dashboard-nav-item {
  min-height: 38px;
  padding: 0 10px;
  gap: 8px;
}

.dashboard-nav-icon {
  width: 24px;
  height: 24px;
  border-radius: 7px;
}

.dashboard-nav-label {
  font-size: 13px;
}

.dashboard-sidebar-card {
  margin-top: auto;
  padding: 12px;
  border-radius: 12px;
}

.dashboard-sidebar-card p {
  margin-top: 6px;
  font-size: 11px;
}

.dashboard-main {
  min-height: auto;
  padding: 14px 16px 16px;
  border-radius: 0 18px 18px 0;
}

.dashboard-main-header {
  min-height: 44px;
  margin-bottom: 8px;
}

.dashboard-eyebrow {
  height: 22px;
  font-size: 11px;
}

.dashboard-main-header h1 {
  margin-top: 6px;
  font-size: 24px;
}

.dashboard-main-meta {
  height: 30px;
}

.dashboard-user-trigger {
  height: 40px;
}

.dashboard-user-avatar {
  width: 30px;
  height: 30px;
}

.dashboard-flow-stepper {
  gap: 6px;
  margin-bottom: 10px;
}

.dashboard-flow-stepper span,
.dashboard-flow-stepper > span {
  min-height: 40px;
  gap: 3px 8px;
  padding: 6px 10px;
  font-size: 13px;
}

.dashboard-flow-stepper i {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  font-size: 12px;
}

.dashboard-flow-stepper i .anticon,
.dashboard-flow-stepper i .anticon svg {
  width: 0.9rem;
  height: 0.9rem;
}

.dashboard-flow-stepper strong {
  font-size: 13px;
}

.dashboard-flow-stepper small {
  font-size: 11px;
}

.dashboard-main-body {
  flex: 1 1 auto;
}

.dashboard-shell :deep(.management-view) {
  height: 100%;
  gap: 10px;
  min-height: 0;
}

.dashboard-shell :deep(.management-filter) {
  padding: 12px 14px;
}

.dashboard-shell :deep(.management-card) {
  flex: 1 1 auto;
  min-height: 0;
  padding: 14px;
}

.dashboard-shell :deep(.management-table) {
  flex: 1 1 auto;
  min-height: 0;
}

.dashboard-shell :deep(.management-table .ant-table) {
  height: calc(100% - 44px);
}

.dashboard-shell :deep(.management-table .ant-pagination) {
  margin: 12px 0 0 !important;
}

.dashboard-shell :deep(.settings-list) {
  min-height: 0;
}

.dashboard-shell :deep(.setting-card) {
  padding: 14px;
}

.dashboard-shell :deep(.management-toolbar) {
  align-items: center;
  margin-bottom: 10px;
}

.dashboard-shell :deep(.management-toolbar-title) {
  font-size: 18px;
}

.dashboard-shell :deep(.management-toolbar-desc) {
  margin-top: 2px;
  font-size: 12px;
}

.dashboard-shell :deep(.ant-form-item) {
  margin-bottom: 0;
}

.dashboard-shell :deep(.ant-form-item-label > label) {
  font-size: 12px;
}

.dashboard-shell :deep(.ant-input),
.dashboard-shell :deep(.ant-input-affix-wrapper),
.dashboard-shell :deep(.ant-select-selector) {
  min-height: 34px;
}

.dashboard-shell :deep(.ant-btn) {
  min-height: 34px;
  padding: 0 12px;
  box-shadow: none;
}

.dashboard-shell :deep(.ant-btn-primary:not(.ant-btn-dangerous)) {
  box-shadow: 0 6px 14px rgba(21, 151, 255, 0.18);
}

.dashboard-shell :deep(.ant-btn-dangerous) {
  box-shadow: 0 6px 14px rgba(231, 76, 60, 0.14);
}

.dashboard-shell :deep(.management-view .ant-table-thead > tr > th),
.dashboard-shell :deep(.management-view .ant-table-tbody > tr > td) {
  padding: 12px 16px !important;
}

.dashboard-shell :deep(.action-cell) {
  gap: 8px;
}

.dashboard-shell :deep(.score-orb) {
  width: 34px;
  height: 34px;
  box-shadow: 0 6px 14px var(--edu-primary-glow);
}

@media (max-width: 900px) {
  .dashboard-shell {
    grid-template-columns: 1fr;
  }

  .dashboard-sidebar,
  .dashboard-main {
    border-radius: 18px;
  }

  .dashboard-main {
    min-height: 0;
  }
}
</style>
