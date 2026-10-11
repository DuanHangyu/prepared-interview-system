<template>
  <main class="student-page">
    <section class="student-shell">
      <header class="student-topbar">
        <div class="topbar-actions">
          <button
            class="icon-button"
            type="button"
            aria-label="通知"
            @click="noticeOpen = true"
          >
            <BellOutlined />
            <span class="notice-dot" v-if="unreadNoticeCount">{{ unreadNoticeCount }}</span>
          </button>
          <Dropdown placement="bottomRight" :trigger="['click']" :arrow="false">
            <button class="user-chip" type="button">
              <span>{{ studentInitial }}</span>
              <strong>{{ studentName }}</strong>
              <DownOutlined />
            </button>
            <template #overlay>
              <Menu class="student-user-menu" @click="handleUserCommand">
                <MenuItem key="password">修改密码</MenuItem>
                <MenuItem key="logout">退出登录</MenuItem>
              </Menu>
            </template>
          </Dropdown>
        </div>
      </header>

      <div class="student-workspace">
        <aside class="student-sidebar">
          <button class="brand-lockup" type="button" @click="handleSideNav('home')">
            <span class="brand-mark">
              <img src="/logo.png" alt="AI 面试学生端" />
            </span>
            <span class="brand-copy">
              <strong>AI 面试学生端</strong>
              <small>Student Workspace</small>
            </span>
          </button>

          <section class="profile-card">
            <div class="profile-avatar">{{ studentInitial }}</div>
            <h2>{{ studentName }}</h2>
            <p>学号：{{ studentNo }}</p>
            <span>{{ studentStage }}</span>
          </section>

          <nav class="sidebar-nav" aria-label="学生端导航">
            <button
              v-for="item in sidebarNavItems"
              :key="item.key"
              type="button"
              :class="{ active: sidebarActive === item.key }"
              @click="handleSideNav(item.key)"
            >
              <component :is="item.icon" />
              <span>{{ item.label }}</span>
            </button>
          </nav>

          <section class="trust-card">
            <SafetyCertificateOutlined />
            <strong>安全可靠</strong>
            <span>AI 技术保障公平公正</span>
          </section>
        </aside>

        <section class="student-main" v-loading="loading && !list.length">
          <section
            class="assessment-hero"
            v-if="featuredTask"
            :class="{ 'has-appointment': showAppointmentPanel, 'is-direct': !showAppointmentPanel }"
          >
            <div class="hero-heading">
              <span class="hero-kicker">当前考核</span>
              <div class="hero-title-row">
                <h1>{{ featuredTask?.theme || "未命名考核" }}</h1>
                <span class="mode-badge" :class="heroBadgeTone">
                  {{ heroModeBadge }}
                </span>
              </div>
              <div class="hero-status">
                <span :class="heroStatusTone">
                  <component :is="heroStatusIcon" />
                </span>
                <div>
                  <strong>{{ heroStatusTitle }}</strong>
                  <p>{{ heroStatusDesc }}</p>
                </div>
              </div>
            </div>

            <button
              class="hero-primary-action"
              type="button"
              :disabled="primaryDisabled(featuredTask)"
              @click="handlePrimaryAction(featuredTask)"
            >
              <component :is="heroPrimaryIcon" />
              {{ primaryActionText(featuredTask) }}
              <RightOutlined />
            </button>

            <nav class="assessment-path" aria-label="学生考核流程">
              <span
                v-for="(item, index) in examJourneySteps"
                :key="item.label"
                :class="{
                  active: index === examJourneyActiveIndex,
                  done: index < examJourneyActiveIndex,
                }"
              >
                <i>
                  <component :is="item.icon" />
                </i>
                <strong>{{ item.label }}</strong>
                <small>{{ item.desc }}</small>
                <em>{{ index + 1 }}</em>
              </span>
            </nav>

            <section class="appointment-inline" v-if="showAppointmentPanel">
              <div class="appointment-copy">
                <h2>下一步：预约时间</h2>
                <p>请选择一个合适的时间完成预约，预约成功后即可进入考核流程。</p>
              </div>
              <div class="appointment-calendar">
                <header>
                  <button type="button" aria-label="上一天">
                    <LeftOutlined />
                  </button>
                  <strong>{{ appointmentDateLabel }}</strong>
                  <button type="button" aria-label="下一天">
                    <RightOutlined />
                  </button>
                </header>
                <div class="appointment-week appointment-week-head">
                  <span>一</span>
                  <span>二</span>
                  <span>三</span>
                  <span>四</span>
                  <span>五</span>
                  <span>六</span>
                  <span>日</span>
                </div>
                <div class="appointment-days">
                  <span
                    v-for="day in appointmentCalendarCells"
                    :key="day.date"
                    :class="{
                      muted: !day.currentMonth,
                      available: day.available,
                      active: day.selected,
                    }"
                  >
                    {{ day.label }}
                  </span>
                </div>
              </div>
              <div class="appointment-slots">
                <header>
                  <strong>可预约时间</strong>
                  <span>{{ appointmentDayLabel }}</span>
                </header>
                <button
                  v-for="item in appointmentTimeOptions"
                  :key="item.timePeriod"
                  type="button"
                  :disabled="item.full"
                  @click="toAppoint(featuredTask)"
                >
                  {{ appointmentTimeLabel(item) }}
                </button>
                <p v-if="!appointmentTimeOptions.length">暂无可预约时间</p>
              </div>
            </section>
          </section>

          <section class="assessment-empty" v-else-if="isResult">
            <CalendarOutlined />
            <h2>暂无考核安排</h2>
            <p>当前没有需要处理的考核任务，新的安排会在这里展示。</p>
          </section>

          <section class="recent-assessment-card">
            <header>
              <h2>最近考核</h2>
              <button type="button" @click="handleSideNav('assessment')">
                查看全部
                <RightOutlined />
              </button>
            </header>
            <div class="assessment-table" v-if="recentAssessmentRows.length">
              <div class="assessment-row assessment-head">
                <span>考核名称</span>
                <span>考核类型</span>
                <span>状态</span>
                <span>考核时间</span>
                <span>操作</span>
              </div>
              <div
                class="assessment-row"
                v-for="item in recentAssessmentRows"
                :key="item?.id"
              >
                <span>
                  <FileTextOutlined />
                  {{ item?.theme || "未命名考核" }}
                </span>
                <span>{{ modeLabel(item) }}</span>
                <span>
                  <i :class="statusTone(item)">{{ statusText(item) }}</i>
                </span>
                <span>{{ detailTimeLabel(item) }}</span>
                <div class="assessment-actions">
                  <button type="button" @click="handlePrimaryAction(item)">
                    {{ primaryActionText(item) }}
                    <RightOutlined />
                  </button>
                  <button type="button" @click="openPreparedResults(item)">预置结果／处理状态</button>
                </div>
              </div>
            </div>
            <div class="recent-empty" v-else>
              暂无考核记录
            </div>
          </section>

          <div class="student-pagination" v-if="total > 6">
            <button type="button" :disabled="page <= 1" @click="preFn">
              <LeftOutlined />
            </button>
            <span>{{ page }} / {{ Math.ceil(total / 6) }}</span>
            <button type="button" :disabled="page * 6 >= total" @click="nextFn">
              <RightOutlined />
            </button>
          </div>
        </section>

        <aside class="progress-panel">
          <section class="side-card schedule-reminder">
            <header>
              <h3>
                <CalendarOutlined />
                日程提醒
              </h3>
              <button type="button" @click="handleSideNav('schedule')">
                查看全部
                <RightOutlined />
              </button>
            </header>
            <article v-if="scheduleTask">
              <strong>{{ scheduleTask?.theme || "待安排考核" }}</strong>
              <small>{{ scheduleDate(scheduleTask).day }} {{ scheduleDate(scheduleTask).time }}</small>
              <span :class="statusTone(scheduleTask)">{{ statusText(scheduleTask) }}</span>
            </article>
            <div class="side-empty" v-else>
              <CalendarOutlined />
              <strong>暂无近期安排</strong>
              <small>当前没有即将进行的考核安排</small>
            </div>
          </section>

          <section class="side-card report-reminder">
            <header>
              <h3>
                <FileSearchOutlined />
                最近报告
              </h3>
              <button type="button" @click="handleSideNav('report')">
                查看全部
                <RightOutlined />
              </button>
            </header>
            <article v-for="item in reportPreview" :key="item?.id">
              <strong>{{ item?.theme || "未命名考核" }}</strong>
              <small>{{ featuredDeadline }}</small>
              <button type="button" @click="handleSecondaryAction(item)">
                查看报告
                <RightOutlined />
              </button>
            </article>
            <div class="side-empty" v-if="!reportPreview.length">
              <FileTextOutlined />
              <strong>暂无报告</strong>
              <small>考核完成后，报告会在这里展示</small>
            </div>
          </section>
        </aside>
      </div>
    </section>

    <AppointModal
      ref="appointRef"
      @success="() => refreshDashboard({ forceStatistic: true })"
    />
    <UpdatePassword ref="updatePass" />

    <Modal
      v-model:open="noticeOpen"
      title="消息通知"
      :footer="null"
      width="520px"
      class="student-action-modal"
    >
      <div class="modal-list">
        <div class="notice-toolbar">
          <span>{{ unreadNoticeCount ? `${unreadNoticeCount} 条未读消息` : "消息已全部读完" }}</span>
          <button type="button" :disabled="!unreadNoticeCount" @click="markAllNoticesRead">
            全部已读
          </button>
        </div>
        <article
          v-for="item in noticeItems"
          :key="item.id"
          :class="{ read: isNoticeRead(item.id) }"
        >
          <span>
            <component :is="item.icon" />
          </span>
          <div>
            <strong>{{ item.title }}</strong>
            <small>{{ item.desc }}</small>
          </div>
          <div class="notice-actions">
            <button
              type="button"
              class="read-action"
              :disabled="isNoticeRead(item.id)"
              @click="markNoticeRead(item.id)"
            >
              {{ isNoticeRead(item.id) ? "已读" : "标为已读" }}
            </button>
            <button type="button" @click="handleNoticeAction(item)">处理</button>
          </div>
        </article>
      </div>
    </Modal>

    <Modal
      v-model:open="taskDetailOpen"
      title="考核详情"
      :footer="null"
      width="620px"
      class="student-action-modal"
    >
      <section class="task-detail-modal" v-if="detailTask">
        <header>
          <span>{{ statusText(detailTask) }}</span>
          <h3>{{ detailTask?.theme || "未命名考核" }}</h3>
          <p>考察产品思维、表达结构、分析能力与问题解决能力。</p>
        </header>
        <div class="detail-grid-list">
          <div>
            <small>考核模式</small>
            <strong>{{ modeLabel(detailTask) }}</strong>
          </div>
          <div>
            <small>预计时长</small>
            <strong>{{ durationLabel(detailTask) }}</strong>
          </div>
          <div>
            <small>考核时间</small>
            <strong>{{ detailTimeLabel(detailTask) }}</strong>
          </div>
          <div>
            <small>当前状态</small>
            <strong>{{ statusText(detailTask) }}</strong>
          </div>
        </div>
        <div class="modal-actions">
          <button type="button" class="secondary-action" @click="taskDetailOpen = false">
            关闭
          </button>
          <button
            v-if="!isReappointLocked(detailTask)"
            type="button"
            class="primary-action"
            :disabled="primaryDisabled(detailTask)"
            @click="handleDetailPrimary"
          >
            {{ primaryActionText(detailTask) }}
            <RightOutlined />
          </button>
        </div>
      </section>
    </Modal>

    <Modal
      v-model:open="helpOpen"
      title="帮助中心"
      :footer="null"
      width="580px"
      class="student-action-modal"
    >
      <section class="help-modal">
        <article v-for="item in helpItems" :key="item.title">
          <strong>{{ item.title }}</strong>
          <p>{{ item.desc }}</p>
        </article>
      </section>
    </Modal>
  </main>
</template>

<script setup lang="ts">
import {
  getAssessmentTodo,
  getAssessmentAnalysis,
  getAssessmentDone,
  getAppointmentList,
  getAssessmentStatistic,
  getMachineTime,
  cancelAppointment,
} from "@/api/studentAssessment";
import dayjs from "dayjs";
import {
  computed,
  createVNode,
  markRaw,
  onMounted,
  onUnmounted,
  ref,
} from "vue";
import { useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import AppointModal from "./AppointModal.vue";
import UpdatePassword from "@/components/Layout/componets/UpdatePassword.vue";
import { Dropdown, Menu, MenuItem, message, Modal } from "ant-design-vue";
import {
  BellOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CustomerServiceOutlined,
  DownOutlined,
  ExclamationCircleOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  HomeOutlined,
  InboxOutlined,
  LeftOutlined,
  PlayCircleOutlined,
  ProfileOutlined,
  QuestionCircleOutlined,
  RightOutlined,
  SafetyCertificateOutlined,
  ScheduleOutlined,
  SettingOutlined,
} from "@ant-design/icons-vue";
import { resolveInitialDashboardTab } from "./landingState";
import {
  canStartAssessment,
  isAppointmentTimeAvailable,
} from "./assessmentTime";

const tabItems = [
  {
    label: "待预约",
    desc: "选择考核时间",
    key: 1,
    icon: markRaw(CalendarOutlined),
  },
  {
    label: "待完成",
    desc: "按时进入面试",
    key: 2,
    icon: markRaw(ClockCircleOutlined),
  },
  {
    label: "已结束",
    desc: "查看评估状态与复盘报告",
    key: 4,
    icon: markRaw(CheckCircleOutlined),
  },
];

const sidebarNavItems = [
  { key: "home", label: "首页", icon: markRaw(HomeOutlined) },
  { key: "assessment", label: "我的考核", icon: markRaw(ProfileOutlined) },
  { key: "report", label: "我的报告", icon: markRaw(FileTextOutlined) },
  { key: "schedule", label: "日程安排", icon: markRaw(ScheduleOutlined) },
  { key: "help", label: "帮助中心", icon: markRaw(CustomerServiceOutlined) },
  { key: "setting", label: "设置", icon: markRaw(SettingOutlined) },
];

const NOTICE_STORAGE_KEY = "student-dashboard-read-notices";
const tab = ref(1);
const sidebarActive = ref("home");
const noticeOpen = ref(false);
const taskDetailOpen = ref(false);
const helpOpen = ref(false);
const detailTask = ref<Recordable | null>(null);
const updatePass = ref();
const statisticLoaded = ref(false);
const statisticLoading = ref(false);
const readNoticeIds = ref<string[]>([]);
const statistic = ref<Recordable>({
  all: 0,
  toAppoint: 0,
  todo: 0,
  analysis: 0,
  done: 0,
});
const activeTabInfo = computed(() =>
  tabItems.find((item) => item.key === tab.value)
);
const activeFlowIndex = computed(() => {
  if (tab.value === 1) return 0;
  if (tab.value === 2) return 2;
  return 3;
});
const listCache = ref<Record<number, { records: Recordable[]; total: number }>>({});
const requestId = ref(0);

const changeTab = (e: number, options: { force?: boolean } = {}) => {
  if (tab.value === e && !options.force) return;
  tab.value = e;
  page.value = 1;
  const cached = listCache.value[e];
  if (cached && !options.force) {
    list.value = cached.records;
    total.value = cached.total;
    isResult.value = true;
    onList({ silent: true });
    return;
  }
  list.value = [];
  total.value = 0;
  onList({ force: options.force });
};

const appointRef = ref();
const loading = ref(false);
const isResult = ref(false);
const list = ref<Recordable[]>([]);
const total = ref(0);
const page = ref(1);
const serverTime = ref(dayjs());
let serverClockOffsetMs = 0;
let serverClockTimer: number | undefined;

const getResponseRecords = (res: Recordable) => res?.data?.records || [];
const getResponseTotal = (res: Recordable) => Number(res?.data?.total || 0);
const withDashboardStatus = (
  records: Recordable[],
  status: "analysis" | "done"
) => records.map((item) => ({ ...item, _dashboardStatus: status }));

const fetchDashboardList = async (
  currentTab: number,
  params: Record<string, any>
) => {
  if (currentTab === 4) {
    const [analysisRes, doneRes] = await Promise.all([
      getAssessmentAnalysis(params),
      getAssessmentDone(params),
    ]);
    const analysisRecords = withDashboardStatus(
      getResponseRecords(analysisRes),
      "analysis"
    );
    const doneRecords = withDashboardStatus(getResponseRecords(doneRes), "done");
    return {
      records: [...analysisRecords, ...doneRecords].slice(0, params.size),
      total: getResponseTotal(analysisRes) + getResponseTotal(doneRes),
    };
  }

  const requestMap: Record<number, (params: Record<string, any>) => Promise<any>> = {
    1: getAppointmentList,
    2: getAssessmentTodo,
  };
  const res = await requestMap[currentTab](params);
  return {
    records: getResponseRecords(res),
    total: getResponseTotal(res),
  };
};

const onList = (options: { force?: boolean; silent?: boolean } = {}) => {
  const currentTab = tab.value;
  const currentRequest = requestId.value + 1;
  requestId.value = currentRequest;
  const cached = listCache.value[currentTab];
  loading.value = Boolean(!options.silent && (!cached || options.force));
  isResult.value = !!(options.silent && cached);
  const params = {
    page: page.value,
    size: 6,
  };

  fetchDashboardList(currentTab, params)
    .then(({ records, total: nextTotal }) => {
      if (requestId.value !== currentRequest) return;
      list.value = records;
      total.value = nextTotal;
      if (page.value === 1) {
        listCache.value[currentTab] = {
          records,
          total: nextTotal,
        };
      }
    })
    .catch(() => {
      if (requestId.value !== currentRequest) return;
      if (!cached) {
        list.value = [];
        total.value = 0;
      }
    })
    .finally(() => {
      if (requestId.value !== currentRequest) return;
      loading.value = false;
      isResult.value = true;
    });
};

const refreshDashboard = (options: { forceStatistic?: boolean } = {}) => {
  listCache.value = {};
  onList({ force: true });
  if (options.forceStatistic) {
    loadStatistic({ force: true });
  }
};

const refreshMachineTime = async () => {
  const res = await getMachineTime();
  if (res?.data) {
    const machineTime = dayjs(res.data, "YYYY-MM-DD HH:mm:ss");
    if (machineTime.isValid()) {
      serverClockOffsetMs = machineTime.valueOf() - Date.now();
      serverTime.value = machineTime;
    }
  }
};

const startServerClock = () => {
  if (serverClockTimer) window.clearInterval(serverClockTimer);
  serverClockTimer = window.setInterval(() => {
    serverTime.value = dayjs(Date.now() + serverClockOffsetMs);
  }, 1000);
};

const router = useRouter();
const authStore = useAuthStore();

const studentName = computed(() => authStore?.info?.name || "同学");
const studentGreetingName = computed(() =>
  studentName.value.endsWith("同学")
    ? studentName.value
    : `${studentName.value}同学`
);
const studentInitial = computed(() => studentName.value.slice(0, 1).toUpperCase());
const studentNo = computed(
  () => authStore?.info?.studentNo || authStore?.info?.account || "--"
);
const studentStage = computed(
  () => authStore?.info?.grade || authStore?.info?.className || "学生"
);
const featuredTask = computed(() => list.value?.[0] || null);
const scheduleTask = computed(() =>
  tab.value === 4 ? null : list.value?.[1] || featuredTask.value
);
const reportPreview = computed(() => (tab.value === 4 ? list.value.slice(0, 3) : []));
const progressTotalCount = computed(() => {
  if (statisticLoaded.value) return Number(statistic.value?.all || 0);
  return tab.value === 4 ? total.value : 0;
});
const progressDoneCount = computed(() => {
  const doneCount = statisticLoaded.value
    ? Number(statistic.value?.done || 0)
    : list.value.filter((item) => item?._dashboardStatus === "done").length;
  return Math.min(progressTotalCount.value, doneCount);
});
const progressPercent = computed(() => {
  if (!progressTotalCount.value) return 0;
  return Math.min(
    100,
    Math.round((progressDoneCount.value / progressTotalCount.value) * 100)
  );
});
const featuredDeadline = computed(() => {
  if (!featuredTask.value?.timePeriod) return "等待通知";
  const time = dayjs(featuredTask.value.timePeriod).format("YYYY-MM-DD HH:mm");
  if (tab.value === 1) return `预约时间 ${time}`;
  if (tab.value === 2) return `考核时间 ${time}`;
  if (isAnalysisTask(featuredTask.value)) return `提交时间 ${time}`;
  return `完成时间 ${time}`;
});
const heroBadgeTone = computed(() => {
  if (!featuredTask.value) return "muted";
  if (tab.value === 1 || canReappoint(featuredTask.value)) return "warning";
  if (tab.value === 2) return canStart(featuredTask.value) ? "success" : "info";
  if (isAnalysisTask(featuredTask.value)) return "info";
  return "success";
});
const heroModeBadge = computed(() => {
  if (!featuredTask.value) return "暂无考核";
  if (tab.value === 1 || canReappoint(featuredTask.value)) return "需要预约";
  if (tab.value === 2) return featuredTask.value?.timePeriod ? "已预约" : "无需预约";
  if (isAnalysisTask(featuredTask.value)) return "分析中";
  return "已完成";
});
const heroStatusTone = computed(() => {
  if (!featuredTask.value) return "status-info";
  if (tab.value === 1 || canReappoint(featuredTask.value)) return "status-warning";
  if (tab.value === 2 && canStart(featuredTask.value)) return "status-success";
  if (tab.value === 4 && !isAnalysisTask(featuredTask.value)) return "status-success";
  return "status-info";
});
const heroStatusIcon = computed(() => {
  if (tab.value === 1 || (featuredTask.value && canReappoint(featuredTask.value))) {
    return markRaw(CalendarOutlined);
  }
  if (tab.value === 2 && featuredTask.value && canStart(featuredTask.value)) {
    return markRaw(PlayCircleOutlined);
  }
  if (tab.value === 4 && featuredTask.value && !isAnalysisTask(featuredTask.value)) {
    return markRaw(CheckCircleOutlined);
  }
  if (tab.value === 4) return markRaw(InboxOutlined);
  return markRaw(ClockCircleOutlined);
});
const heroPrimaryIcon = computed(() => {
  if (tab.value === 1) return markRaw(CalendarOutlined);
  if (tab.value === 2) return markRaw(PlayCircleOutlined);
  return markRaw(FileSearchOutlined);
});
const heroStatusTitle = computed(() => {
  if (!featuredTask.value) return "暂无考核";
  if (tab.value === 1 || canReappoint(featuredTask.value)) return "待预约";
  if (tab.value === 2) return canStart(featuredTask.value) ? "可进入考核" : "待开始";
  if (isAnalysisTask(featuredTask.value)) return "报告生成中";
  if (featuredTask.value?.state === 2) return "报告已生成";
  return "考核已结束";
});
const heroStatusDesc = computed(() => {
  if (!featuredTask.value) return "当前没有需要处理的考核任务。";
  if (tab.value === 1 || canReappoint(featuredTask.value)) {
    return "请选择一个合适的时间完成预约，预约成功后即可进入考核流程。";
  }
  if (tab.value === 2) {
    return canStart(featuredTask.value)
      ? "点击下方按钮进入考核流程。"
      : "已预约考核，请在指定时间进入。";
  }
  if (isAnalysisTask(featuredTask.value)) {
    return "系统正在生成评估报告，完成后可查看复盘。";
  }
  if (isReappointLocked(featuredTask.value)) {
    return "当前暂无可操作事项，可查看任务详情并等待教师后续安排。";
  }
  return "你可以查看本次考核报告与复盘结果。";
});
const examJourneySteps = computed(() => {
  const requiresAppointment =
    tab.value === 1 || (featuredTask.value ? canReappoint(featuredTask.value) : false);
  const steps = [
    {
      key: "task",
      label: "考核任务",
      desc: "查看任务要求",
      icon: markRaw(FileTextOutlined),
    },
  ];

  if (requiresAppointment) {
    steps.push({
      key: "appointment",
      label: "预约时间",
      desc: "选择考核时间",
      icon: markRaw(CalendarOutlined),
    });
  }

  steps.push(
    {
      key: "enter",
      label: "进入考核",
      desc: "开始面试流程",
      icon: markRaw(PlayCircleOutlined),
    },
    {
      key: "report",
      label: "报告复盘",
      desc: "查看考核报告",
      icon: markRaw(FileSearchOutlined),
    }
  );

  return steps;
});
const examJourneyActiveIndex = computed(() => {
  const targetKey = tab.value === 1 ? "appointment" : tab.value === 4 ? "report" : "enter";
  return Math.max(
    0,
    examJourneySteps.value.findIndex((item) => item.key === targetKey)
  );
});
const showAppointmentPanel = computed(
  () => tab.value === 1 && !!featuredTask.value
);
const appointmentTimeOptions = computed(() => {
  const times = Array.isArray(featuredTask.value?.canAppointmentTimes)
    ? featuredTask.value.canAppointmentTimes
    : [];
  return times
    .filter((time: Recordable) =>
      isAppointmentTimeAvailable(time, serverTime.value)
    )
    .slice(0, 5);
});
const firstAppointmentTime = computed(() => appointmentTimeOptions.value?.[0]);
const appointmentDateLabel = computed(() => {
  if (!firstAppointmentTime.value?.timePeriod) return "暂无可预约时间";
  return dayjs(firstAppointmentTime.value.timePeriod).format("YYYY年M月");
});
const appointmentDayLabel = computed(() => {
  if (!firstAppointmentTime.value?.timePeriod) return "待开放";
  return dayjs(firstAppointmentTime.value.timePeriod).format("MM-DD ddd");
});
const appointmentCalendarCells = computed(() => {
  const anchor = firstAppointmentTime.value?.timePeriod
    ? dayjs(firstAppointmentTime.value.timePeriod)
    : dayjs();
  const monthStart = anchor.startOf("month");
  const startOffset = (monthStart.day() + 6) % 7;
  const gridStart = monthStart.subtract(startOffset, "day");
  const availableDates = new Set(
    appointmentTimeOptions.value
      .map((item) =>
        item?.timePeriod ? dayjs(item.timePeriod).format("YYYY-MM-DD") : ""
      )
      .filter(Boolean)
  );
  const selectedDate = firstAppointmentTime.value?.timePeriod
    ? dayjs(firstAppointmentTime.value.timePeriod).format("YYYY-MM-DD")
    : anchor.format("YYYY-MM-DD");

  return Array.from({ length: 35 }, (_, index) => {
    const date = gridStart.add(index, "day");
    const key = date.format("YYYY-MM-DD");
    return {
      date: key,
      label: date.format("D"),
      currentMonth: date.month() === anchor.month(),
      available: availableDates.has(key),
      selected: key === selectedDate,
    };
  });
});
const appointmentTimeLabel = (item: Recordable) => {
  if (!item?.timePeriod) return "待确认";
  const value = dayjs(item.timePeriod);
  if (!value.isValid()) return item.timePeriod;
  const label = value.format("HH:mm");
  return item?.remainCount ? `${label} · 剩余 ${item.remainCount}` : label;
};
const recentAssessmentRows = computed(() => list.value.slice(0, 3));
const nextSectionTitle = computed(() => {
  if (tab.value === 1) return "待预约考核";
  if (tab.value === 2) return "待完成考核";
  return "评估与复盘";
});
const todoItems = computed(() => {
  const items = [];
  const appointmentCount = tabCount(1);
  const todoCount = tabCount(2);
  if (appointmentCount > 0) {
    items.push({
      title: tab.value === 1 && featuredTask.value?.theme
        ? `预约 ${featuredTask.value.theme}`
        : "预约待处理考核",
      desc: `${appointmentCount} 场考核等待预约`,
      time: "待处理",
      icon: markRaw(CalendarOutlined),
    });
  }
  if (todoCount > 0) {
    items.push({
      title: "进入待完成面试",
      desc: `${todoCount} 场面试需要按时进入`,
      time: "待开始",
      icon: markRaw(PlayCircleOutlined),
    });
  }
  if (!items.length) {
    items.unshift({
      title: "暂无待办考核",
      desc: "当前没有需要处理的面试任务",
      time: "已同步",
      icon: markRaw(CheckCircleOutlined),
    });
  }
  return items;
});

const tabCount = (key: number) => {
  const countMap: Record<number, number> = {
    1: Number(statistic.value?.toAppoint || 0),
    2: Number(statistic.value?.todo || 0),
    4:
      Number(statistic.value?.analysis || 0) +
      Number(statistic.value?.done || 0),
  };
  if (statisticLoaded.value) return countMap[key] || 0;
  return tab.value === key ? total.value : 0;
};

const noticeItems = computed<Recordable[]>(() => {
  const items: Recordable[] = [];
  const appointmentCount = tabCount(1);
  const todoCount = tabCount(2);
  const analysisCount = Number(statistic.value?.analysis || 0);

  if (appointmentCount > 0) {
    items.push({
      id: "appointment",
      title: "待预约考核",
      desc: `${appointmentCount} 场考核等待预约时间`,
      icon: markRaw(CalendarOutlined),
      action: () => {
        noticeOpen.value = false;
        handleSideNav("schedule");
      },
    });
  }

  if (todoCount > 0) {
    items.push({
      id: "todo",
      title: "待完成面试",
      desc: `${todoCount} 场面试需要按时进入`,
      icon: markRaw(PlayCircleOutlined),
      action: () => {
        noticeOpen.value = false;
        handleSideNav("assessment");
      },
    });
  }

  if (analysisCount > 0) {
    items.push({
      id: "analysis",
      title: "报告生成中",
      desc: `${analysisCount} 份报告正在评估，完成后可查看复盘`,
      icon: markRaw(InboxOutlined),
      action: () => {
        noticeOpen.value = false;
        handleSideNav("report");
      },
    });
  }

  return items;
});

const isNoticeRead = (id: string) => readNoticeIds.value.includes(id);
const unreadNoticeCount = computed(
  () => noticeItems.value.filter((item) => !isNoticeRead(item.id)).length
);

const saveReadNoticeIds = () => {
  localStorage.setItem(NOTICE_STORAGE_KEY, JSON.stringify(readNoticeIds.value));
};

const markNoticeRead = (id: string) => {
  if (isNoticeRead(id)) return;
  readNoticeIds.value = [...readNoticeIds.value, id];
  saveReadNoticeIds();
};

const markAllNoticesRead = () => {
  readNoticeIds.value = Array.from(new Set(noticeItems.value.map((item) => item.id)));
  saveReadNoticeIds();
};

const handleNoticeAction = (item: Recordable) => {
  markNoticeRead(item.id);
  item.action?.();
};

const helpItems = [
  {
    title: "如何预约面试？",
    desc: "进入“待预约”列表，点击对应考核的立即预约，选择可用时间段后提交。",
  },
  {
    title: "什么时候可以开始考核？",
    desc: "已预约考核会在“待完成”中显示，到达预约时间后按钮会变为可开始。",
  },
  {
    title: "报告在哪里看？",
    desc: "考核结束后会进入“已结束”，分析完成前显示正在评估，完成后可查看复盘报告。",
  },
];

const loadStatistic = (options: { force?: boolean } = {}) => {
  if (statisticLoading.value) return;
  if (statisticLoaded.value && !options.force) return;
  statisticLoading.value = true;
  getAssessmentStatistic()
    .then((res) => {
      statistic.value = {
        all: res?.data?.all || 0,
        toAppoint: res?.data?.toAppoint || 0,
        todo: res?.data?.todo || 0,
        analysis: res?.data?.analysis || 0,
        done: res?.data?.done || 0,
      };
      statisticLoaded.value = true;
      changeTab(resolveInitialDashboardTab(statistic.value));
    })
    .catch(() => {
      // 列表接口仍可正常使用，统计失败时保留列表数量兜底。
    })
    .finally(() => {
      statisticLoading.value = false;
    });
};

const handleSideNav = (key: string) => {
  sidebarActive.value = key;
  if (key === "home") {
    changeTab(1);
    return;
  }
  if (key === "assessment") {
    changeTab(2);
    if (!tabCount(2)) {
      message.info("当前没有待完成考核");
    }
    return;
  }
  if (key === "report") {
    changeTab(4);
    if (!tabCount(4)) {
      message.info("暂无可查看报告");
    }
    return;
  }
  if (key === "schedule") {
    changeTab(1);
    if (!tabCount(1)) {
      message.info("当前没有待预约考核");
    }
    return;
  }
  if (key === "help") {
    helpOpen.value = true;
    return;
  }
  if (key === "setting") {
    updatePass.value?.openModal();
  }
};

const handleUserCommand = ({ key }: { key: string | number }) => {
  if (key === "password") {
    updatePass.value?.openModal();
    return;
  }
  Modal.confirm({
    title: "确认退出登录吗？",
    icon: createVNode(ExclamationCircleOutlined, { class: "!text-[#e75f49]" }),
    okText: "退出登录",
    cancelText: "取消",
    onOk: async () => {
      await authStore.logout();
      window.location.href = "/login";
    },
  });
};

const startFn = async (e: Recordable) => {
  // Preflight is read-only. The backend, not cached browser time or legacy defense flags, decides exam eligibility.
  if (!Number.isSafeInteger(Number(e?.id)) || Number(e?.id) < 1) return;
  await router.push({ name: "PreparedPreflight", params: { assessmentId: String(e?.id) } });
};

const hasAvailableAppointmentTimes = (item?: Recordable | null) => {
  return Array.isArray(item?.canAppointmentTimes)
    ? item.canAppointmentTimes.some((time: Recordable) =>
        isAppointmentTimeAvailable(time, serverTime.value)
      )
    : false;
};

const toAppoint = (e: Record<string, any>) => {
  if (!hasAvailableAppointmentTimes(e)) {
    message.info("暂无可预约时间段，请联系教师开放新的预约时间");
    return;
  }
  appointRef.value?.openModal(e, serverTime.value);
};

const toDetail = (e: Record<string, any>) => {
  if (isAnalysisTask(e)) {
    message.info("系统正在生成评估报告，请稍后查看");
    return;
  }
  if (e?.state == 2) {
    router.push(`/studyDetail?id=${e?.id}`);
  }
  if (e?.state == 3 || e?.state == 5) {
    return;
  }
  if (e?.state == 4) {
    toAppoint(e);
  }
};

const openPreparedResults = (item: Recordable) => {
  const assessmentId = Number(item?.id);
  if (!Number.isSafeInteger(assessmentId) || assessmentId < 1 || assessmentId > 2147483647) return;
  router.push({ name: "PreparedResults", params: { assessmentId: String(assessmentId) } });
};

const openTaskDetail = (item: Recordable) => {
  detailTask.value = item;
  taskDetailOpen.value = true;
};

const handleDetailPrimary = () => {
  if (!detailTask.value) return;
  const currentTask = detailTask.value;
  taskDetailOpen.value = false;
  handlePrimaryAction(currentTask);
};

const canStart = (item: Recordable) => {
  return canStartAssessment(item?.timePeriod, serverTime.value);
};

const shouldShowCancel = (item: Recordable) => {
  return tab.value == 2 && !canStart(item) && item?.timePeriod;
};

const canReappoint = (item: Recordable) => {
  return (
    item.state == 4 ||
    (item?.appointRecordFlag &&
      item.state != 3 &&
      item.state != 5 &&
      typeof item?.defenseScore == "number" &&
      item?.defenseScore < item?.passScore)
  );
};

const isReappointLocked = (item: Recordable) => {
  return item.state == 3 || item.state == 5;
};

const isAnalysisTask = (item?: Recordable | null) => {
  return item?._dashboardStatus === "analysis";
};

const statusText = (item: Recordable) => {
  if (isAnalysisTask(item)) return "正在评估";
  if (tab.value == 1) {
    return hasAvailableAppointmentTimes(item) ? "待预约" : "暂无可约";
  }
  if (tab.value == 2) return canStart(item) ? "可开始" : "已预约";
  if (item?.state == 2) return "已完成";
  if (isReappointLocked(item)) return "暂不可约";
  if (canReappoint(item)) {
    return hasAvailableAppointmentTimes(item) ? "可重约" : "暂无可约";
  }
  return "已结束";
};

const statusTone = (item?: Recordable | null) => {
  const text = item ? statusText(item) : "";
  if (["待预约", "可重约", "暂无可约"].includes(text)) return "tone-warning";
  if (["可开始", "已完成"].includes(text)) return "tone-success";
  if (["已预约", "正在评估"].includes(text)) return "tone-info";
  return "tone-muted";
};

const primaryActionText = (item: Recordable) => {
  if (!item) return "暂无任务";
  if (tab.value === 1) {
    return hasAvailableAppointmentTimes(item) ? "预约考核" : "暂无可约";
  }
  if (tab.value === 2) return "面试预检";
  if (isAnalysisTask(item)) return "正在评估";
  if (item?.state === 2) return "查看报告";
  if (canReappoint(item)) {
    return hasAvailableAppointmentTimes(item) ? "重新预约" : "暂无可约";
  }
  return "查看详情";
};

const primaryDisabled = (item: Recordable) => {
  return (
    isAnalysisTask(item) ||
    ((tab.value === 1 || canReappoint(item)) &&
      !hasAvailableAppointmentTimes(item))
  );
};

const handlePrimaryAction = (item: Recordable) => {
  if (!item) return;
  if (tab.value === 1) {
    toAppoint(item);
    return;
  }
  if (tab.value === 2) {
    startFn(item);
    return;
  }
  if (isAnalysisTask(item)) return;
  if (item?.state === 2) {
    toDetail(item);
    return;
  }
  if (isReappointLocked(item)) {
    openTaskDetail(item);
    return;
  }
  if (canReappoint(item)) {
    toAppoint(item);
    return;
  }
  message.info("暂无可操作事项");
};

const handleSecondaryAction = (item: Recordable) => {
  if (!item) return;
  if (tab.value === 4 && item?.state === 2) {
    toDetail(item);
    return;
  }
  openTaskDetail(item);
};

const durationLabel = (item?: Recordable | null) => {
  if (!item) return "暂无时长";
  const seconds = Number(item?.duration || item?.answerTime || 0);
  if (!seconds) return "60 分钟";
  if (seconds >= 60) return `${Math.ceil(seconds / 60)} 分钟`;
  return `${seconds} 秒`;
};

const modeLabel = (item?: Recordable | null) => {
  if (!item) return "AI 面试";
  if (item?.defense && item?.question) return "答辩 + 问答";
  if (item?.defense) return "AI 答辩";
  if (item?.question) return "AI 问答";
  return "AI 面试";
};

const firstAvailableAppointmentTime = (item?: Recordable | null) => {
  if (!Array.isArray(item?.canAppointmentTimes)) return null;
  return (
    item.canAppointmentTimes.find((time: Recordable) =>
      isAppointmentTimeAvailable(time, serverTime.value)
    ) ||
    null
  );
};

const scheduleDate = (item?: Recordable | null) => {
  const displayTime =
    item?.timePeriod || firstAvailableAppointmentTime(item)?.timePeriod;
  if (!displayTime) {
    return {
      day: "待定",
      week: "",
      time: "待预约",
    };
  }
  const value = dayjs(displayTime);
  return {
    day: value.format("MM/DD"),
    week: value.format("ddd"),
    time: value.format("HH:mm"),
  };
};

const detailTimeLabel = (item?: Recordable | null) => {
  if (!item?.timePeriod) {
    return firstAvailableAppointmentTime(item)?.timePeriod ? "待预约" : "等待通知";
  }
  return dayjs(item.timePeriod).format("YYYY-MM-DD HH:mm");
};

onMounted(() => {
  try {
    readNoticeIds.value = JSON.parse(
      localStorage.getItem(NOTICE_STORAGE_KEY) || "[]"
    );
  } catch (error) {
    readNoticeIds.value = [];
  }
  onList();
  loadStatistic();
  refreshMachineTime();
  startServerClock();
});

onUnmounted(() => {
  if (serverClockTimer) {
    window.clearInterval(serverClockTimer);
    serverClockTimer = undefined;
  }
});

const preFn = () => {
  if (page.value <= 1) return;
  page.value -= 1;
  onList({ force: true });
};
const nextFn = () => {
  if (page.value * 6 >= total.value) return;
  page.value += 1;
  onList({ force: true });
};

const cancelSubscribe = (e: Recordable) => {
  Modal.confirm({
    title: "确认解除预约吗？",
    icon: createVNode(ExclamationCircleOutlined, { class: "!text-[#e75f49]" }),
    okText: "解除预约",
    onOk: async () => {
      await cancelAppointment(e?.id);
      message.success("已解除预约");
      refreshDashboard({ forceStatistic: true });
    },
  });
};
</script>

<style scoped>
.assessment-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.student-page {
  min-height: 100dvh;
  padding: 28px;
  overflow: visible;
  background:
    radial-gradient(circle at 78% 16%, rgba(255, 226, 112, 0.2), transparent 24%),
    radial-gradient(circle at 18% 10%, rgba(255, 255, 255, 0.22), transparent 28%),
    linear-gradient(180deg, #bebdb9 0%, #a8a7a2 58%, #8f8e8a 100%);
  color: #17181a;
}

.student-shell {
  width: min(1720px, 100%);
  min-height: calc(100dvh - 56px);
  margin: 0 auto;
  padding: 18px;
  border: 1px solid rgba(255, 255, 255, 0.72);
  border-radius: 28px;
  background:
    radial-gradient(circle at 78% 14%, rgba(255, 229, 108, 0.34), transparent 26%),
    linear-gradient(125deg, rgba(245, 246, 241, 0.92), rgba(255, 250, 219, 0.82)),
    rgba(246, 246, 239, 0.92);
  box-shadow:
    0 34px 92px rgba(31, 32, 30, 0.26),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
}

.student-topbar {
  min-height: 58px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 16px;
}

.brand-lockup,
.user-chip,
.icon-button,
.sidebar-nav button,
.state-tabs button,
.section-title button,
.primary-action,
.secondary-action,
.plain-action,
.todo-card > button,
.student-pagination button,
.device-action {
  border: 0;
  font-family: inherit;
  cursor: pointer;
}

.brand-lockup {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  background: transparent;
  color: #17181a;
  text-align: left;
}

.brand-mark {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  background: linear-gradient(145deg, #3f403d, #232422);
  box-shadow: 0 12px 24px rgba(31, 32, 29, 0.18);
}

.brand-mark img {
  width: 28px;
  height: 28px;
  object-fit: contain;
}

.brand-copy strong,
.brand-copy small {
  display: block;
}

.brand-copy strong {
  font-size: 22px;
  font-weight: 850;
  letter-spacing: 0;
}

.brand-copy small {
  margin-top: 3px;
  color: #7a7c75;
  font-size: 12px;
  font-weight: 700;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.icon-button {
  width: 46px;
  height: 46px;
  position: relative;
  display: grid;
  place-items: center;
  border: 1px solid rgba(23, 24, 26, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 247, 0.72);
  color: #232422;
  font-size: 20px;
}

.notice-dot {
  min-width: 18px;
  height: 18px;
  position: absolute;
  top: -1px;
  right: -1px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #e75f49;
  color: #fff;
  font-size: 11px;
  font-weight: 850;
}

.user-chip {
  height: 48px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 5px 12px 5px 5px;
  border: 1px solid rgba(23, 24, 26, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 247, 0.76);
  color: #17181a;
}

.user-chip span {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #2f302f;
  color: #fff;
  font-size: 18px;
  font-weight: 850;
}

.user-chip strong {
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.student-workspace {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr) 340px;
  gap: 18px;
}

.student-sidebar,
.student-main,
.progress-panel {
  min-width: 0;
}

.student-sidebar {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 24px;
  background:
    radial-gradient(circle at 30% 0%, rgba(255, 255, 255, 0.12), transparent 30%),
    linear-gradient(180deg, #555652 0%, #3b3c39 46%, #2d2e2b 100%);
  box-shadow:
    0 24px 60px rgba(37, 38, 35, 0.26),
    inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

.profile-card {
  padding: 8px 6px 4px;
  text-align: center;
}

.profile-avatar {
  width: 88px;
  height: 88px;
  display: grid;
  place-items: center;
  margin: 18px auto 14px;
  border-radius: 999px;
  background:
    radial-gradient(circle at 35% 28%, rgba(255, 255, 255, 0.78), transparent 24%),
    linear-gradient(145deg, #f8f8f0, #d9d9d2);
  color: #2d2e2b;
  box-shadow:
    0 18px 38px rgba(20, 21, 20, 0.24),
    inset 0 0 0 1px rgba(255, 255, 255, 0.52);
  font-size: 42px;
  font-weight: 850;
}

.profile-card h2 {
  margin: 0;
  color: #f7f7ef;
  font-size: 24px;
  font-weight: 850;
}

.profile-card p {
  margin: 8px 0 10px;
  color: rgba(247, 247, 239, 0.58);
  font-size: 13px;
  font-weight: 700;
}

.profile-card > span,
.soft-pill,
.next-card .section-title > span,
.schedule-row > span,
.quick-card > span,
.mini-list small {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(244, 207, 85, 0.32);
  color: #7b6720;
  font-size: 12px;
  font-weight: 850;
}

.sidebar-nav {
  display: grid;
  gap: 7px;
}

.sidebar-nav button {
  height: 48px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
  color: rgba(247, 247, 239, 0.58);
  font-size: 15px;
  font-weight: 750;
  text-align: left;
}

.sidebar-nav button.active,
.sidebar-nav button:hover {
  background: rgba(255, 255, 255, 0.13);
  color: #fff;
  box-shadow:
    inset 3px 0 0 #f4cf55,
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.trust-card {
  min-height: 138px;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  gap: 7px;
  margin-top: auto;
  padding: 18px;
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 18px;
  background: rgba(26, 27, 25, 0.22);
}

.trust-card :deep(.anticon) {
  color: #f4cf55;
  font-size: 22px;
}

.trust-card strong {
  color: #fff;
  font-size: 15px;
  font-weight: 850;
}

.trust-card span {
  color: rgba(247, 247, 239, 0.54);
  font-size: 12px;
  line-height: 1.5;
}

.student-main {
  display: grid;
  gap: 14px;
}

.state-tabs {
  width: fit-content;
  max-width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  overflow-x: auto;
  border: 1px solid rgba(23, 24, 26, 0.06);
  border-radius: 999px;
  background: rgba(255, 255, 247, 0.78);
}

.state-tabs button {
  height: 48px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  border-radius: 999px;
  background: transparent;
  color: #2f302f;
  font-size: 15px;
  font-weight: 800;
  white-space: nowrap;
}

.state-tabs button strong {
  min-width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: rgba(47, 48, 47, 0.1);
  font-size: 14px;
}

.state-tabs button.active {
  background: #2f302f;
  color: #fff;
  box-shadow: 0 16px 34px rgba(32, 33, 31, 0.2);
}

.state-tabs button.active strong {
  background: #fff;
  color: #2f302f;
}

.welcome-card,
.next-card,
.schedule-card,
.quick-card {
  border: 1px solid rgba(23, 24, 26, 0.06);
  border-radius: 24px;
  background: rgba(255, 255, 247, 0.72);
  box-shadow:
    0 16px 42px rgba(45, 46, 43, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.86);
}

.welcome-card {
  min-height: 300px;
  position: relative;
  overflow: hidden;
  padding: 28px 30px 108px;
}

.welcome-copy {
  max-width: 560px;
  position: relative;
  z-index: 2;
}

.welcome-copy h1 {
  margin: 16px 0 10px;
  font-size: clamp(30px, 3vw, 42px);
  line-height: 1.1;
  font-weight: 850;
  letter-spacing: 0;
}

.welcome-copy p {
  margin: 0;
  color: #686b63;
  font-size: 15px;
  line-height: 1.75;
}

.welcome-visual {
  position: absolute;
  right: -26px;
  top: 0;
  width: min(440px, 46%);
  height: 190px;
  object-fit: cover;
  object-position: right center;
  mix-blend-mode: multiply;
  opacity: 0.78;
}

.progress-dock {
  min-height: 70px;
  position: absolute;
  left: 24px;
  right: 24px;
  bottom: 18px;
  z-index: 3;
  display: grid;
  grid-template-columns: 112px minmax(160px, 1fr) 110px;
  align-items: center;
  gap: 18px;
  padding: 10px 16px 10px 10px;
  border: 1px solid rgba(23, 24, 26, 0.06);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(14px);
}

.progress-percent {
  display: grid;
  place-items: center;
  min-height: 54px;
  border-radius: 14px;
  background: #2f302f;
  color: #f4cf55;
}

.progress-percent small,
.progress-total small {
  color: inherit;
  font-size: 11px;
  font-weight: 750;
}

.progress-percent strong,
.progress-total strong {
  display: block;
  font-size: 24px;
  font-weight: 850;
  line-height: 1;
}

.progress-track,
.dark-progress {
  height: 16px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(47, 48, 47, 0.08);
}

.progress-track i,
.dark-progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #f4cf55, #f7df82);
}

.progress-total {
  text-align: right;
}

.progress-total small {
  color: #777a72;
}

.progress-total strong {
  color: #2f302f;
}

.next-card,
.schedule-card {
  padding: 22px 24px;
}

.section-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.section-title h2 {
  margin: 0;
  color: #17181a;
  font-size: 24px;
  font-weight: 850;
}

.section-title p {
  margin: 6px 0 0;
  color: #777a72;
  font-size: 13px;
}

.section-title.compact {
  align-items: center;
}

.section-title button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: transparent;
  color: #2f302f;
  font-size: 13px;
  font-weight: 850;
}

.next-task {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) 210px;
  align-items: center;
  gap: 18px;
  padding: 18px;
  border: 1px solid rgba(23, 24, 26, 0.06);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.62);
}

.task-icon {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  border-radius: 16px;
  background: linear-gradient(145deg, #f07157, #e75f49);
  color: #fff;
  font-size: 26px;
  box-shadow: 0 14px 28px rgba(231, 95, 73, 0.24);
}

.task-copy span {
  color: #7b6720;
  font-size: 12px;
  font-weight: 850;
}

.task-copy h3 {
  margin: 8px 0 6px;
  color: #17181a;
  font-size: 22px;
  font-weight: 850;
  line-height: 1.3;
}

.task-copy p {
  margin: 0;
  color: #696c65;
  font-size: 13px;
}

.task-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  margin-top: 14px;
  color: #6c6e68;
  font-size: 12px;
  font-weight: 750;
}

.task-actions {
  display: grid;
  gap: 10px;
}

.primary-action,
.secondary-action,
.todo-card > button,
.device-action {
  min-height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 999px;
  font-size: 15px;
  font-weight: 850;
}

.primary-action {
  background: linear-gradient(135deg, #f07157, #e75f49);
  color: #fff;
  box-shadow: 0 16px 34px rgba(231, 95, 73, 0.26);
}

.primary-action:disabled {
  background: rgba(47, 48, 47, 0.12);
  color: #777a72;
  cursor: not-allowed;
  box-shadow: none;
}

.secondary-action {
  border: 1px solid rgba(23, 24, 26, 0.08);
  background: rgba(255, 255, 247, 0.78);
  color: #2f302f;
}

.plain-action {
  background: transparent;
  color: #e75f49;
  font-size: 13px;
  font-weight: 850;
}

.next-empty {
  display: grid;
  place-items: center;
  min-height: 148px;
  border: 1px dashed rgba(47, 48, 47, 0.16);
  border-radius: 20px;
  color: #777a72;
  text-align: center;
}

.next-empty :deep(.anticon) {
  color: #f4cf55;
  font-size: 32px;
}

.next-empty h3 {
  margin: 10px 0 4px;
  color: #17181a;
  font-size: 18px;
}

.next-empty p {
  margin: 0;
  font-size: 13px;
}

.schedule-row {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  padding: 14px 16px;
  border: 1px solid rgba(23, 24, 26, 0.06);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.56);
}

.schedule-row time strong,
.quick-card > strong {
  display: block;
  color: #17181a;
  font-size: 24px;
  font-weight: 850;
  line-height: 1;
}

.schedule-row time span,
.schedule-row p,
.quick-card p {
  color: #777a72;
  font-size: 12px;
  font-weight: 700;
}

.schedule-row h3 {
  margin: 0 0 6px;
  color: #17181a;
  font-size: 18px;
  font-weight: 850;
}

.schedule-row p {
  margin: 0;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.quick-card {
  min-height: 190px;
  padding: 18px;
}

.quick-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}

.quick-card header > :deep(.anticon) {
  color: #2f302f;
  font-size: 22px;
}

.quick-card header button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 0;
  background: transparent;
  color: #4f514c;
  cursor: pointer;
  font-size: 12px;
  font-weight: 850;
}

.quick-card h3 {
  margin: 0 0 14px;
  color: #17181a;
  font-size: 17px;
  font-weight: 850;
}

.quick-card p {
  margin: 10px 0 12px;
  line-height: 1.6;
}

.mini-list {
  display: grid;
  gap: 10px;
}

.mini-list div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid rgba(23, 24, 26, 0.06);
}

.mini-list div:last-child {
  border-bottom: 0;
}

.mini-list span {
  min-width: 0;
  overflow: hidden;
  color: #17181a;
  font-size: 13px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-list small {
  flex-shrink: 0;
  padding: 4px 8px;
  font-size: 11px;
}

.device-icons {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin: 14px 0;
}

.device-icons span {
  position: relative;
  display: grid;
  place-items: center;
  gap: 5px;
  color: #2f302f;
  font-size: 20px;
}

.device-icons i {
  width: 9px;
  height: 9px;
  position: absolute;
  right: 18px;
  top: 20px;
  border-radius: 999px;
  background: #69bd5b;
  box-shadow: 0 0 0 3px rgba(105, 189, 91, 0.14);
}

.device-icons small {
  color: #6c6e68;
  font-size: 11px;
  font-weight: 750;
}

.device-action {
  width: 100%;
  min-height: 42px;
  background: rgba(244, 207, 85, 0.42);
  color: #6f5d1e;
}

.student-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  color: #555852;
  font-size: 13px;
  font-weight: 850;
}

.student-pagination button {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: rgba(255, 255, 247, 0.72);
  color: #2f302f;
}

.student-pagination button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.progress-panel {
  display: flex;
  flex-direction: column;
  gap: 22px;
  padding: 28px;
  border-radius: 24px;
  background:
    radial-gradient(circle at 72% 0%, rgba(255, 255, 255, 0.12), transparent 28%),
    linear-gradient(145deg, #3f403d, #242523);
  color: #f8f8f0;
  box-shadow: 0 22px 54px rgba(32, 33, 31, 0.26);
}

.progress-card header,
.todo-card header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 14px;
}

.progress-card header span,
.flow-card h3,
.todo-card h3 {
  color: #fff;
  font-size: 16px;
  font-weight: 850;
}

.progress-card header strong {
  color: #f4cf55;
  font-size: 34px;
  font-weight: 850;
  line-height: 1;
}

.dark-progress {
  margin: 24px 0 12px;
  background: rgba(255, 255, 247, 0.16);
}

.progress-card p {
  margin: 0;
  color: rgba(255, 255, 247, 0.72);
  font-size: 13px;
  font-weight: 750;
}

.flow-card {
  padding-top: 22px;
  border-top: 1px solid rgba(255, 255, 247, 0.14);
}

.flow-list {
  display: grid;
  gap: 0;
  margin-top: 18px;
}

.flow-list div {
  position: relative;
  display: grid;
  grid-template-columns: 36px 1fr;
  column-gap: 12px;
  min-height: 58px;
}

.flow-list div::after {
  content: "";
  position: absolute;
  left: 17px;
  top: 34px;
  bottom: 4px;
  width: 1px;
  background: rgba(255, 255, 247, 0.18);
}

.flow-list div:last-child::after {
  display: none;
}

.flow-list span {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: rgba(255, 255, 247, 0.08);
  color: rgba(255, 255, 247, 0.48);
}

.flow-list div.active span {
  background: #f4cf55;
  color: #2f302f;
}

.flow-list strong,
.flow-list small {
  display: block;
}

.flow-list strong {
  color: rgba(255, 255, 247, 0.9);
  font-size: 14px;
  font-weight: 850;
}

.flow-list small {
  margin-top: 5px;
  color: rgba(255, 255, 247, 0.48);
  font-size: 12px;
  font-weight: 700;
}

.todo-card {
  padding-top: 22px;
  border-top: 1px solid rgba(255, 255, 247, 0.14);
}

.todo-card header span {
  color: rgba(255, 255, 247, 0.62);
  font-size: 13px;
  font-weight: 800;
}

.todo-list {
  display: grid;
  gap: 14px;
  margin: 18px 0 22px;
}

.todo-list article {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
}

.todo-list article > span {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 13px;
  background: rgba(244, 207, 85, 0.22);
  color: #f4cf55;
}

.todo-list strong,
.todo-list small {
  display: block;
}

.todo-list strong {
  overflow: hidden;
  color: #fff;
  font-size: 13px;
  font-weight: 850;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-list small {
  margin-top: 4px;
  color: rgba(255, 255, 247, 0.48);
  font-size: 11px;
  font-weight: 700;
}

.todo-list time {
  color: #f07157;
  font-size: 12px;
  font-weight: 850;
}

.todo-card > button {
  width: 100%;
  border: 1px solid rgba(244, 207, 85, 0.42);
  background: transparent;
  color: #f4cf55;
}

:global(.student-user-menu) {
  min-width: 142px;
  padding: 8px;
  border-radius: 14px;
  box-shadow: 0 18px 42px rgba(35, 36, 34, 0.14);
}

.modal-list,
.device-check-list,
.help-modal {
  display: grid;
  gap: 12px;
}

.notice-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 2px 8px;
}

.notice-toolbar span {
  color: #62645f;
  font-size: 13px;
  font-weight: 800;
}

.notice-toolbar button {
  height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: 999px;
  background: rgba(47, 48, 47, 0.08);
  color: #2f302f;
  cursor: pointer;
  font-size: 12px;
  font-weight: 850;
}

.notice-toolbar button:disabled {
  opacity: 0.44;
  cursor: not-allowed;
}

.modal-list article,
.device-check-list article {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid rgba(23, 24, 26, 0.08);
  border-radius: 16px;
  background: rgba(255, 255, 247, 0.76);
}

.modal-list article.read {
  opacity: 0.62;
  background: rgba(245, 245, 238, 0.58);
}

.modal-list article > span,
.device-check-list article > span {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  background: rgba(244, 207, 85, 0.24);
  color: #6f5d1e;
  font-size: 20px;
}

.device-check-list article > span.success {
  background: rgba(105, 189, 91, 0.16);
  color: #4f8b43;
}

.device-check-list article > span.warning {
  background: rgba(231, 95, 73, 0.15);
  color: #e75f49;
}

.modal-list strong,
.device-check-list strong,
.help-modal strong {
  display: block;
  color: #17181a;
  font-size: 15px;
  font-weight: 850;
}

.modal-list small,
.device-check-list small,
.task-detail-modal p,
.device-modal p,
.help-modal p {
  margin: 4px 0 0;
  color: #6c6e68;
  font-size: 13px;
  line-height: 1.7;
}

.modal-list button {
  height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: 999px;
  background: #2f302f;
  color: #fff;
  cursor: pointer;
  font-size: 12px;
  font-weight: 850;
}

.notice-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.modal-list button.read-action {
  background: rgba(47, 48, 47, 0.08);
  color: #3c3d39;
}

.modal-list button:disabled {
  cursor: not-allowed;
  opacity: 0.56;
}

.task-detail-modal header {
  padding: 18px;
  border-radius: 18px;
  background: rgba(244, 207, 85, 0.16);
}

.task-detail-modal header span {
  display: inline-flex;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(244, 207, 85, 0.36);
  color: #7b6720;
  font-size: 12px;
  font-weight: 850;
}

.task-detail-modal h3 {
  margin: 12px 0 0;
  color: #17181a;
  font-size: 22px;
  font-weight: 850;
}

.detail-grid-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 14px;
}

.detail-grid-list div {
  min-height: 76px;
  padding: 14px;
  border: 1px solid rgba(23, 24, 26, 0.08);
  border-radius: 16px;
  background: rgba(255, 255, 247, 0.76);
}

.detail-grid-list small {
  display: block;
  color: #777a72;
  font-size: 12px;
  font-weight: 750;
}

.detail-grid-list strong {
  display: block;
  margin-top: 8px;
  color: #17181a;
  font-size: 15px;
  font-weight: 850;
}

.device-modal > p {
  margin-bottom: 14px;
}

.help-modal article {
  padding: 16px;
  border: 1px solid rgba(23, 24, 26, 0.08);
  border-radius: 16px;
  background: rgba(255, 255, 247, 0.76);
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}

.modal-actions .primary-action,
.modal-actions .secondary-action {
  min-width: 128px;
  padding: 0 18px;
}

@media (max-width: 1360px) {
  .student-workspace {
    grid-template-columns: 210px minmax(0, 1fr);
  }

  .progress-panel {
    grid-column: 1 / -1;
    display: grid;
    grid-template-columns: 1fr 1fr 1.2fr;
  }
}

@media (max-width: 980px) {
  .student-page {
    padding: 16px;
  }

  .student-shell {
    padding: 14px;
    border-radius: 24px;
  }

  .student-topbar,
  .student-workspace,
  .next-task,
  .quick-grid,
  .progress-panel {
    grid-template-columns: 1fr;
  }

  .student-topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .student-sidebar {
    order: 2;
  }

  .student-main {
    order: 1;
  }

  .progress-panel {
    order: 3;
  }

  .sidebar-nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .welcome-visual {
    opacity: 0.3;
  }
}

@media (max-width: 640px) {
  .student-page {
    padding: 10px;
  }

  .student-shell {
    min-height: auto;
  }

  .topbar-actions,
  .user-chip,
  .state-tabs,
  .schedule-row,
  .task-actions {
    width: 100%;
  }

  .state-tabs {
    border-radius: 22px;
  }

  .state-tabs button {
    flex: 0 0 auto;
  }

  .welcome-card {
    min-height: 330px;
    padding: 22px 18px;
  }

  .progress-dock {
    grid-template-columns: 1fr;
    gap: 10px;
  }

  .progress-total {
    text-align: left;
  }

  .next-task,
  .schedule-row {
    grid-template-columns: 1fr;
  }

  .sidebar-nav {
    grid-template-columns: 1fr;
  }

  .modal-list article,
  .device-check-list article {
    grid-template-columns: 1fr;
  }

  .notice-toolbar,
  .notice-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .notice-actions button,
  .notice-toolbar button {
    width: 100%;
  }

  .progress-panel {
    padding: 22px;
  }
}

/* DESIGN.md refresh: light education-SaaS app shell. */
.student-page {
  --edu-page-bg: #f3f7fc;
  --edu-page-dot: rgba(207, 218, 235, 0.42);
  --edu-shell: #ffffff;
  --edu-main: #f7fbff;
  --edu-section: #edf6ff;
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

  min-height: 100dvh;
  padding: 24px;
  background:
    radial-gradient(circle, var(--edu-page-dot) 1px, transparent 1px),
    var(--edu-page-bg);
  background-size: 28px 28px;
  color: var(--edu-text);
}

.student-shell {
  width: min(1500px, 100%);
  min-height: calc(100dvh - 48px);
  padding: 0;
  overflow: hidden;
  border: 1px solid #e8f0fb;
  border-radius: 22px;
  background: var(--edu-shell);
  box-shadow: 0 24px 60px rgba(38, 64, 102, 0.12);
}

.student-topbar {
  min-height: 70px;
  padding: 16px 22px;
  border-bottom: 1px solid var(--edu-border-soft);
  background: rgba(255, 255, 255, 0.92);
}

.brand-lockup {
  color: var(--edu-text);
}

.brand-mark {
  border-radius: 12px;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  box-shadow: 0 12px 26px var(--edu-primary-glow);
}

.brand-copy strong {
  font-size: 20px;
}

.brand-copy small {
  color: var(--edu-muted);
}

.icon-button,
.user-chip {
  border: 1px solid var(--edu-border);
  background: #ffffff;
  color: var(--edu-main-text);
  box-shadow: 0 8px 22px rgba(54, 97, 150, 0.08);
}

.user-chip span {
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  box-shadow: 0 10px 22px var(--edu-primary-glow);
}

.student-workspace {
  min-height: calc(100dvh - 118px);
  display: grid;
  grid-template-columns: 204px minmax(0, 1fr) 308px;
  gap: 0;
  background: var(--edu-shell);
}

.student-sidebar {
  gap: 14px;
  padding: 18px 14px;
  border: 0;
  border-right: 1px solid var(--edu-border-soft);
  border-radius: 0;
  background: #ffffff;
  box-shadow: none;
}

.profile-card {
  padding: 14px 12px;
  border: 1px solid var(--edu-border);
  border-radius: 14px;
  background: linear-gradient(180deg, #f8fbff, #ffffff);
  text-align: left;
}

.profile-avatar {
  width: 58px;
  height: 58px;
  margin: 0 0 12px;
  border-radius: 18px;
  background:
    radial-gradient(circle at 35% 28%, rgba(255, 255, 255, 0.82), transparent 25%),
    linear-gradient(135deg, #4eb8ff, var(--edu-primary));
  color: #ffffff;
  box-shadow: 0 12px 24px var(--edu-primary-glow);
  font-size: 26px;
}

.profile-card h2 {
  color: var(--edu-text);
  font-size: 18px;
}

.profile-card p {
  margin: 6px 0 8px;
  color: var(--edu-muted);
}

.profile-card > span,
.soft-pill,
.next-card .section-title > span,
.schedule-row > span,
.quick-card > span,
.mini-list small {
  border-radius: 999px;
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.sidebar-nav {
  gap: 6px;
}

.sidebar-nav button {
  height: 44px;
  padding: 0 12px;
  border-radius: 8px;
  background: transparent;
  color: var(--edu-main-text);
  font-size: 14px;
  font-weight: 700;
}

.sidebar-nav button :deep(.anticon) {
  color: #3f526b;
  font-size: 16px;
}

.sidebar-nav button.active,
.sidebar-nav button:hover {
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  color: #ffffff;
  box-shadow: 0 10px 22px var(--edu-primary-glow);
}

.sidebar-nav button.active :deep(.anticon),
.sidebar-nav button:hover :deep(.anticon) {
  color: #ffffff;
}

.trust-card {
  min-height: 132px;
  border: 1px solid var(--edu-border);
  border-radius: 14px;
  background: linear-gradient(180deg, #f2f8ff, #ffffff);
}

.trust-card :deep(.anticon) {
  color: var(--edu-primary);
}

.trust-card strong {
  color: var(--edu-text);
}

.trust-card span {
  color: var(--edu-muted);
}

.student-main {
  gap: 12px;
  padding: 16px;
  background: var(--edu-main);
}

.state-tabs {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 0;
  overflow: visible;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.state-tabs button {
  height: 54px;
  justify-content: space-between;
  padding: 0 18px;
  border: 1px solid var(--edu-border);
  border-radius: 8px;
  background: #ffffff;
  color: var(--edu-main-text);
  box-shadow: 0 8px 22px rgba(54, 97, 150, 0.06);
}

.state-tabs button strong {
  width: 34px;
  min-width: 34px;
  height: 34px;
  background: #f1f6fd;
  color: var(--edu-main-text);
  font-size: 22px;
}

.state-tabs button.active {
  border-color: rgba(21, 151, 255, 0.48);
  background: #ffffff;
  color: var(--edu-primary-deep);
  box-shadow:
    inset 0 0 0 1px rgba(21, 151, 255, 0.16),
    0 12px 26px rgba(21, 151, 255, 0.12);
}

.state-tabs button.active strong {
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.student-flow-stepper {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.student-flow-stepper span {
  min-height: 52px;
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  overflow: hidden;
  border: 1px solid var(--edu-border);
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff, #f4f8fe);
  color: var(--edu-muted);
  font-weight: 800;
}

.student-flow-stepper span.active,
.student-flow-stepper span.done {
  border-color: transparent;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  color: #ffffff;
  box-shadow: 0 10px 22px var(--edu-primary-glow);
}

.student-flow-stepper i {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 999px;
  background: rgba(21, 151, 255, 0.12);
  color: var(--edu-primary-deep);
  font-style: normal;
  font-size: 12px;
  font-weight: 850;
}

.student-flow-stepper span.active i,
.student-flow-stepper span.done i {
  background: rgba(255, 255, 255, 0.24);
  color: #ffffff;
}

.welcome-card,
.next-card,
.schedule-card,
.quick-card {
  border: 1px solid var(--edu-border);
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 8px 22px rgba(54, 97, 150, 0.08);
}

.welcome-card {
  min-height: 218px;
  padding: 24px 250px 92px 24px;
  background:
    linear-gradient(90deg, rgba(234, 244, 255, 0.78), rgba(255, 255, 255, 0.72)),
    #ffffff;
}

.welcome-card::after {
  content: "";
  position: absolute;
  right: 34px;
  top: 28px;
  width: 160px;
  height: 132px;
  border-radius: 18px;
  background:
    linear-gradient(90deg, rgba(21, 151, 255, 0.36), rgba(21, 151, 255, 0.04)),
    repeating-linear-gradient(0deg, #ffffff 0 12px, #f1f7ff 12px 24px);
  box-shadow: inset 0 0 0 1px rgba(21, 151, 255, 0.14);
  opacity: 0.86;
}

.welcome-copy h1 {
  color: var(--edu-text);
  font-size: 30px;
}

.welcome-copy p,
.section-title p,
.task-copy p,
.task-meta,
.schedule-row time span,
.schedule-row p,
.quick-card p,
.device-icons small {
  color: var(--edu-muted);
}

.progress-dock {
  grid-template-columns: 118px minmax(160px, 1fr) 104px;
  border: 1px solid var(--edu-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: 0 8px 22px rgba(54, 97, 150, 0.06);
}

.progress-percent {
  border-radius: 10px;
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.progress-track,
.dark-progress {
  height: 10px;
  background: #edf3fb;
}

.progress-track i,
.dark-progress i {
  background: linear-gradient(90deg, var(--edu-primary), #42c9ff);
}

.progress-total strong,
.section-title h2,
.schedule-row h3,
.quick-card h3,
.quick-card > strong,
.task-copy h3,
.next-empty h3,
.modal-list strong,
.device-check-list strong,
.help-modal strong {
  color: var(--edu-text);
}

.next-card,
.schedule-card {
  padding: 18px;
  background: linear-gradient(180deg, #edf6ff, #f8fbff);
}

.next-task,
.schedule-row,
.detail-grid-list div,
.help-modal article,
.modal-list article,
.device-check-list article {
  border: 1px solid var(--edu-border);
  border-radius: 8px;
  background: #ffffff;
}

.next-task {
  grid-template-columns: 58px minmax(0, 1fr) 190px;
}

.task-icon {
  width: 50px;
  height: 50px;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  box-shadow: 0 12px 24px var(--edu-primary-glow);
}

.task-copy span {
  color: var(--edu-primary-deep);
}

.primary-action {
  border-radius: 8px;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  box-shadow: 0 12px 24px var(--edu-primary-glow);
}

.primary-action:disabled {
  background: #e8eef6;
  color: var(--edu-muted);
}

.secondary-action {
  border-color: var(--edu-border);
  border-radius: 8px;
  background: #ffffff;
  color: var(--edu-primary-deep);
}

.plain-action {
  color: #e75f49;
}

.next-empty {
  border-color: var(--edu-border);
}

.next-empty :deep(.anticon) {
  color: var(--edu-primary);
}

.quick-grid {
  gap: 12px;
}

.quick-card {
  min-height: 160px;
  padding: 16px;
}

.quick-card header {
  margin-bottom: 14px;
}

.quick-card header > :deep(.anticon) {
  width: 36px;
  height: 36px;
  display: inline-grid;
  place-items: center;
  border-radius: 10px;
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.quick-card header button,
.section-title button {
  color: var(--edu-primary-deep);
}

.mini-list div {
  border-color: var(--edu-border-soft);
}

.device-icons i {
  background: var(--edu-mint);
  box-shadow: 0 0 0 3px rgba(55, 201, 120, 0.14);
}

.device-action {
  border-radius: 8px;
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.student-pagination button {
  border: 1px solid var(--edu-border);
  background: #ffffff;
  color: var(--edu-primary-deep);
}

.progress-panel {
  gap: 14px;
  padding: 16px;
  border-left: 1px solid var(--edu-border-soft);
  border-radius: 0;
  background: #ffffff;
  color: var(--edu-main-text);
  box-shadow: none;
}

.progress-card,
.flow-card,
.todo-card {
  padding: 16px;
  border: 1px solid var(--edu-border);
  border-radius: 14px;
  background: linear-gradient(180deg, #f8fbff, #ffffff);
}

.flow-card,
.todo-card {
  border-top: 1px solid var(--edu-border);
}

.progress-card header span,
.flow-card h3,
.todo-card h3,
.flow-list strong,
.todo-list strong {
  color: var(--edu-text);
}

.flow-list small {
  grid-column: 2;
}

.progress-card header strong {
  color: var(--edu-primary-deep);
}

.progress-card p,
.flow-list small,
.todo-card header span,
.todo-list small {
  color: var(--edu-muted);
}

.flow-list span,
.todo-list article > span {
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.flow-list div::after {
  background: var(--edu-border);
}

.flow-list div.active span {
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  color: #ffffff;
}

.todo-list time {
  color: var(--edu-amber);
}

.todo-card > button {
  border-color: rgba(21, 151, 255, 0.24);
  border-radius: 8px;
  color: var(--edu-primary-deep);
}

.task-detail-modal header {
  background: var(--edu-section);
}

.task-detail-modal header span,
.modal-list article > span,
.device-check-list article > span {
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.device-check-list article > span.success {
  background: rgba(55, 201, 120, 0.14);
  color: #18965a;
}

@media (max-width: 1360px) {
  .student-workspace {
    grid-template-columns: 188px minmax(0, 1fr);
  }

  .progress-panel {
    grid-column: 1 / -1;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    border-top: 1px solid var(--edu-border-soft);
    border-left: 0;
  }
}

@media (max-width: 980px) {
  .student-page {
    padding: 14px;
  }

  .student-shell {
    min-height: auto;
    border-radius: 20px;
  }

  .student-workspace,
  .next-task,
  .quick-grid,
  .progress-panel {
    grid-template-columns: 1fr;
  }

  .student-sidebar {
    order: 2;
    border-top: 1px solid var(--edu-border-soft);
    border-right: 0;
  }

  .student-main {
    order: 1;
  }

  .progress-panel {
    order: 3;
  }

  .student-flow-stepper {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .welcome-card {
    padding-right: 24px;
  }

  .welcome-card::after {
    display: none;
  }
}

@media (max-width: 640px) {
  .student-page {
    padding: 8px;
  }

  .student-topbar {
    padding: 14px;
  }

  .state-tabs,
  .student-flow-stepper {
    grid-template-columns: 1fr;
  }

  .state-tabs button,
  .topbar-actions,
  .user-chip,
  .schedule-row,
  .task-actions {
    width: 100%;
  }

  .welcome-card {
    min-height: 300px;
    padding: 18px;
  }

  .progress-dock {
    position: static;
    margin-top: 18px;
    grid-template-columns: 1fr;
  }
}

/* Scroll fix: keep the redesigned shell, but let real pages grow naturally. */
.student-page {
  overflow-x: hidden;
  overflow-y: auto;
}

.student-shell {
  height: auto;
  overflow: visible;
}

.student-workspace {
  height: auto;
  align-items: stretch;
}

.student-main,
.progress-panel {
  overflow: visible;
}

/* Student visual polish: reference-style blue education SaaS console. */
.student-page {
  --edu-page-bg: #f4f9ff;
  --edu-page-dot: rgba(25, 128, 255, 0.12);
  --edu-shell: #ffffff;
  --edu-main: #f8fbff;
  --edu-panel: #f5f9ff;
  --edu-card: #ffffff;
  --edu-border: #dceaff;
  --edu-border-strong: #bed8ff;
  --edu-primary: #1288ff;
  --edu-primary-deep: #006fe6;
  --edu-primary-soft: #eaf4ff;
  --edu-primary-glow: rgba(18, 136, 255, 0.2);
  --edu-green: #24bd72;
  --edu-orange: #ff9f24;
  --edu-text: #10213f;
  --edu-main-text: #263a59;
  --edu-muted: #71819a;

  padding: 36px 46px;
  background:
    radial-gradient(circle, var(--edu-page-dot) 1.2px, transparent 1.2px),
    linear-gradient(180deg, #f7fbff 0%, #eef6ff 100%);
  background-size: 18px 18px, auto;
}

.student-shell {
  width: min(1560px, 100%);
  border: 1px solid #e6f0ff;
  border-radius: 20px;
  background: var(--edu-shell);
  box-shadow: 0 22px 60px rgba(42, 101, 171, 0.12);
}

.student-topbar {
  min-height: 76px;
  justify-content: flex-end;
  padding: 0 28px;
  border: 0;
  border-bottom: 1px solid #eaf2ff;
  border-radius: 20px 20px 0 0;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: none;
}

.topbar-context {
  min-width: 0;
  margin-right: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.topbar-context span {
  color: var(--edu-muted);
  font-size: 12px;
  font-weight: 700;
}

.topbar-context strong {
  color: var(--edu-text);
  font-size: 22px;
  line-height: 1.15;
}

.topbar-actions {
  gap: 14px;
}

.icon-button {
  width: 42px;
  height: 42px;
  border-radius: 999px;
  box-shadow: none;
}

.notice-dot {
  right: 7px;
  top: 7px;
  width: 8px;
  height: 8px;
  min-width: 8px;
  padding: 0;
  overflow: hidden;
  border: 2px solid #ffffff;
  border-radius: 999px;
  color: transparent;
  background: #ff4d4f;
}

.user-chip {
  min-height: 42px;
  padding: 5px 12px 5px 6px;
  border: 0;
  border-radius: 999px;
  box-shadow: none;
}

.user-chip span {
  width: 32px;
  height: 32px;
  font-size: 16px;
}

.student-workspace {
  grid-template-columns: 240px minmax(0, 1fr) 330px;
  background: #ffffff;
}

.student-sidebar {
  min-height: calc(100dvh - 112px);
  padding: 24px 18px 28px;
  border-right: 1px solid #eaf2ff;
  background: #ffffff;
}

.brand-lockup {
  width: 100%;
  min-height: 48px;
  justify-content: flex-start;
  gap: 12px;
  padding: 0 8px 22px;
  border: 0;
  border-bottom: 1px solid #eef4ff;
  background: transparent;
  color: var(--edu-text);
  text-align: left;
}

.brand-mark {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, #006eff 0%, #1aa8ff 100%);
  box-shadow: 0 12px 22px rgba(18, 136, 255, 0.22);
}

.brand-copy strong {
  font-size: 18px;
  line-height: 1.15;
}

.brand-copy small {
  margin-top: 4px;
  font-size: 12px;
}

.profile-card {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  column-gap: 14px;
  align-items: center;
  padding: 16px;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(180deg, #f4f9ff 0%, #eef6ff 100%);
}

.profile-avatar {
  grid-row: span 3;
  width: 58px;
  height: 58px;
  margin: 0;
  border-radius: 999px;
  background:
    radial-gradient(circle at 50% 34%, #ffffff 0 15%, transparent 16%),
    linear-gradient(180deg, #4da9ff 0%, #0078ff 100%);
  box-shadow: inset 0 -6px 0 rgba(0, 91, 196, 0.1);
}

.profile-card h2 {
  margin: 0;
  font-size: 17px;
}

.profile-card p {
  margin: 4px 0 0;
  font-size: 12px;
}

.profile-card > span {
  width: fit-content;
  margin-top: 8px;
  padding: 3px 9px;
  background: rgba(18, 136, 255, 0.1);
  font-size: 12px;
}

.sidebar-nav {
  gap: 8px;
}

.sidebar-nav button {
  height: 50px;
  gap: 13px;
  padding: 0 16px;
  border-radius: 8px;
  color: #213656;
  font-size: 16px;
  font-weight: 700;
}

.sidebar-nav button:nth-child(6) {
  margin-top: 18px;
}

.sidebar-nav button :deep(.anticon) {
  width: 22px;
  font-size: 20px;
  color: #243855;
}

.sidebar-nav button.active,
.sidebar-nav button:hover {
  background: linear-gradient(135deg, #0587ff 0%, #0a75f5 100%);
  box-shadow: 0 10px 22px rgba(18, 136, 255, 0.24);
}

.trust-card {
  margin-top: auto;
  min-height: 176px;
  justify-content: center;
  border: 0;
  border-radius: 10px;
  background:
    radial-gradient(circle at 50% 30%, rgba(18, 136, 255, 0.14), transparent 34%),
    linear-gradient(180deg, #f4f9ff 0%, #ebf5ff 100%);
}

.trust-card :deep(.anticon) {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  border-radius: 20px;
  background: #ffffff;
  color: var(--edu-primary);
  box-shadow: 0 10px 24px rgba(64, 116, 178, 0.12);
}

.student-main {
  gap: 16px;
  padding: 22px 24px;
  background: var(--edu-main);
}

.state-tabs {
  gap: 16px;
}

.state-tabs button {
  height: 56px;
  padding: 0 22px;
  border-color: var(--edu-border);
  border-radius: 10px;
  box-shadow: none;
}

.state-tabs button :deep(.anticon) {
  display: none;
}

.state-tabs button span {
  color: var(--edu-main-text);
  font-size: 16px;
  font-weight: 800;
}

.state-tabs button strong {
  width: auto;
  min-width: 34px;
  background: transparent;
  color: #43516a;
  font-size: 30px;
  line-height: 1;
}

.state-tabs button.active {
  border-color: var(--edu-primary);
  background: linear-gradient(180deg, #ffffff 0%, #f7fbff 100%);
}

.state-tabs button.active span,
.state-tabs button.active strong {
  color: var(--edu-primary);
}

.student-flow-stepper {
  gap: 10px;
}

.student-flow-stepper span {
  min-height: 60px;
  padding: 0 22px;
  border-color: var(--edu-border);
  border-radius: 10px;
  background: linear-gradient(180deg, #ffffff 0%, #f7fbff 100%);
  clip-path: polygon(0 0, calc(100% - 22px) 0, 100% 50%, calc(100% - 22px) 100%, 0 100%, 20px 50%);
}

.student-flow-stepper span:first-child {
  clip-path: polygon(0 0, calc(100% - 22px) 0, 100% 50%, calc(100% - 22px) 100%, 0 100%);
}

.student-flow-stepper span strong {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 15px;
}

.student-flow-stepper i {
  width: 28px;
  height: 28px;
  font-size: 13px;
}

.welcome-card,
.next-card,
.schedule-card,
.quick-card,
.progress-card,
.flow-card,
.todo-card {
  border-color: var(--edu-border);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 8px 20px rgba(54, 97, 150, 0.05);
}

.welcome-card {
  min-height: 156px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 190px;
  gap: 26px;
  padding: 26px;
  background:
    linear-gradient(90deg, rgba(235, 246, 255, 0.9), rgba(255, 255, 255, 0.95)),
    #ffffff;
}

.welcome-card::after {
  display: none;
}

.soft-pill {
  width: fit-content;
  padding: 5px 10px;
  font-size: 12px;
}

.welcome-copy h1 {
  margin-top: 12px;
  font-size: 28px;
}

.welcome-copy p {
  max-width: 560px;
  font-size: 14px;
}

.progress-dock {
  position: static;
  grid-template-columns: 1fr;
  align-self: center;
  gap: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.progress-percent {
  min-height: 0;
  padding: 0;
  background: transparent;
}

.progress-percent small,
.progress-total small {
  color: var(--edu-muted);
}

.progress-percent strong {
  color: var(--edu-primary);
  font-size: 42px;
  line-height: 1;
}

.progress-track {
  height: 8px;
}

.progress-total {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.next-card,
.schedule-card {
  padding: 18px;
  background: #ffffff;
}

.section-title {
  margin-bottom: 16px;
}

.section-title h2 {
  font-size: 19px;
}

.section-title p {
  margin-top: 5px;
}

.next-task {
  grid-template-columns: minmax(0, 1fr) 190px;
  gap: 18px;
  padding: 20px;
  background: linear-gradient(180deg, #fbfdff 0%, #f6faff 100%);
}

.task-icon {
  display: none;
}

.task-copy span {
  width: fit-content;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(36, 189, 114, 0.12);
  color: #079653;
  font-size: 12px;
  font-weight: 800;
}

.task-copy h3 {
  margin-top: 10px;
  font-size: 22px;
}

.task-meta {
  gap: 10px;
}

.task-meta small {
  border-color: #e7f0ff;
  background: #ffffff;
}

.task-actions {
  align-self: center;
}

.primary-action,
.secondary-action,
.plain-action,
.device-action,
.todo-card > button {
  min-height: 38px;
  border-radius: 7px;
  font-weight: 800;
}

.primary-action {
  background: linear-gradient(135deg, #0c89ff 0%, #0073ed 100%);
}

.schedule-row {
  padding: 16px;
}

.schedule-row time {
  border-radius: 10px;
  background: var(--edu-primary-soft);
}

.quick-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.quick-card {
  min-height: 126px;
  padding: 18px;
}

.quick-card header > :deep(.anticon) {
  width: 46px;
  height: 46px;
  border-radius: 10px;
  background: linear-gradient(135deg, #168cff 0%, #0074ee 100%);
  color: #ffffff;
}

.quick-card:nth-child(2) header > :deep(.anticon) {
  background: linear-gradient(135deg, #ff9b2e 0%, #ff7415 100%);
}

.quick-card:nth-child(3) header > :deep(.anticon) {
  background: linear-gradient(135deg, #33d98b 0%, #15b86b 100%);
}

.quick-card h3 {
  font-size: 17px;
}

.device-icons span {
  border-color: #e7f0ff;
  background: #fbfdff;
}

.progress-panel {
  gap: 16px;
  padding: 22px 24px 22px 0;
  border-left: 0;
  background: var(--edu-main);
}

.progress-card,
.flow-card,
.todo-card {
  padding: 18px;
}

.progress-card {
  background:
    radial-gradient(circle at 88% 20%, rgba(18, 136, 255, 0.12), transparent 34%),
    #ffffff;
}

.progress-card header strong {
  font-size: 28px;
}

.flow-list div {
  grid-template-columns: 28px minmax(0, 1fr);
  row-gap: 3px;
}

.flow-list span,
.todo-list article > span {
  width: 28px;
  height: 28px;
}

.todo-list article {
  grid-template-columns: 32px minmax(0, 1fr) auto;
  padding: 12px 0;
}

.todo-list time {
  color: var(--edu-primary);
}

@media (max-width: 1360px) {
  .student-workspace {
    grid-template-columns: 220px minmax(0, 1fr);
  }

  .progress-panel {
    padding: 0 24px 24px;
    grid-column: 2;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    background: var(--edu-main);
  }
}

@media (max-width: 980px) {
  .student-page {
    padding: 16px;
  }

  .student-topbar {
    padding: 14px 16px;
  }

  .student-workspace {
    grid-template-columns: 1fr;
  }

  .student-sidebar {
    min-height: auto;
  }

  .progress-panel {
    grid-column: auto;
    grid-template-columns: 1fr;
  }

  .welcome-card,
  .next-task,
  .quick-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .student-page {
    padding: 8px;
  }

  .student-shell {
    border-radius: 16px;
  }

  .student-topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .topbar-actions {
    width: 100%;
  }

  .state-tabs,
  .student-flow-stepper {
    grid-template-columns: 1fr;
  }

  .student-flow-stepper span,
  .student-flow-stepper span:first-child {
    clip-path: none;
  }
}

/* Final sizing guard: this project scales px with viewport width, so use rem for stable desktop rails. */
.student-workspace {
  grid-template-columns: 18.75rem minmax(0, 1fr) 25.78125rem;
}

.brand-copy strong,
.brand-copy small,
.profile-card > span {
  white-space: nowrap;
}

@media (max-width: 1360px) and (min-width: 981px) {
  .student-workspace {
    grid-template-columns: 17.1875rem minmax(0, 1fr);
  }
}

@media (max-width: 980px) {
  .student-workspace {
    grid-template-columns: 1fr;
  }

  .brand-copy strong,
  .brand-copy small,
  .profile-card > span {
    white-space: normal;
  }
}

/* Student home redesign: current assessment first, secondary context to the right. */
.student-page {
  padding: 1.5rem;
  overflow: visible;
  background:
    radial-gradient(circle, rgba(193, 211, 235, 0.5) 0.0625rem, transparent 0.0625rem),
    #f3f8ff;
  background-size: 1.5rem 1.5rem;
  color: var(--edu-text, #12233d);
}

.student-shell {
  width: min(96.875rem, 100%);
  min-height: calc(100dvh - 3rem);
  overflow: hidden;
  border: 1px solid #e4eefb;
  border-radius: 1.25rem;
  background: #ffffff;
  box-shadow: 0 1.75rem 4rem rgba(41, 75, 119, 0.12);
}

.student-topbar {
  min-height: 4.75rem;
  justify-content: flex-end;
  padding: 1rem 1.5rem;
  border-bottom: 1px solid #e5effb;
  background: #ffffff;
}

.student-workspace {
  min-height: calc(100dvh - 7.75rem);
  grid-template-columns: 17rem minmax(0, 1fr) 25rem;
  gap: 0;
  background: #ffffff;
}

.student-sidebar {
  gap: 1rem;
  padding: 1.375rem 1.25rem 1.5rem;
  border: 0;
  border-right: 1px solid #e5effb;
  border-radius: 0;
  background: #ffffff;
  box-shadow: none;
}

.brand-lockup {
  min-height: 3rem;
}

.brand-mark {
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 0.75rem;
  background: linear-gradient(135deg, #1597ff 0%, #006ee8 100%);
  box-shadow: 0 0.75rem 1.5rem rgba(21, 151, 255, 0.22);
}

.brand-copy strong {
  color: #111d31;
  font-size: 1.25rem;
}

.brand-copy small {
  color: #73839a;
  font-size: 0.75rem;
}

.profile-card {
  padding: 1rem;
  border: 1px solid #e5effb;
  border-radius: 0.875rem;
  background: linear-gradient(180deg, #f6fbff 0%, #ffffff 100%);
  text-align: left;
}

.profile-avatar {
  width: 3.5rem;
  height: 3.5rem;
  margin: 0 0 0.75rem;
  border-radius: 1rem;
  background:
    radial-gradient(circle at 34% 26%, rgba(255, 255, 255, 0.84), transparent 24%),
    linear-gradient(135deg, #48b7ff 0%, #087cff 100%);
  color: #ffffff;
  box-shadow: 0 0.75rem 1.5rem rgba(21, 151, 255, 0.18);
  font-size: 1.5rem;
}

.profile-card h2 {
  color: #12233d;
  font-size: 1.125rem;
}

.profile-card p {
  margin: 0.375rem 0 0.5rem;
  color: #61738c;
  font-size: 0.8125rem;
}

.profile-card > span {
  padding: 0;
  background: transparent;
  color: #6f8199;
  font-size: 0.75rem;
  line-height: 1.5;
  white-space: normal;
}

.sidebar-nav {
  gap: 0.375rem;
}

.sidebar-nav button {
  height: 2.875rem;
  gap: 0.75rem;
  padding: 0 0.875rem;
  border-radius: 0.5rem;
  background: transparent;
  color: #27364b;
  font-size: 0.9375rem;
  font-weight: 700;
}

.sidebar-nav button :deep(.anticon) {
  color: #3e5068;
  font-size: 1rem;
}

.sidebar-nav button.active,
.sidebar-nav button:hover {
  background: linear-gradient(135deg, #168dff 0%, #0074ef 100%);
  color: #ffffff;
  box-shadow: 0 0.75rem 1.5rem rgba(22, 141, 255, 0.18);
}

.sidebar-nav button.active :deep(.anticon),
.sidebar-nav button:hover :deep(.anticon) {
  color: #ffffff;
}

.trust-card {
  min-height: 8.5rem;
  padding: 1rem;
  border: 1px solid #e5effb;
  border-radius: 0.875rem;
  background:
    radial-gradient(circle at 50% 20%, rgba(21, 151, 255, 0.13), transparent 34%),
    linear-gradient(180deg, #f2f8ff 0%, #ffffff 100%);
}

.trust-card :deep(.anticon) {
  color: #168dff;
}

.trust-card strong {
  color: #087cff;
  font-size: 0.9375rem;
}

.trust-card span {
  color: #61738c;
}

.student-main {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1.25rem 1.5rem 1.5rem;
  background: #f7fbff;
}

.assessment-hero,
.recent-assessment-card,
.side-card,
.assessment-empty {
  border: 1px solid #dfeaf8;
  border-radius: 0.875rem;
  background: #ffffff;
  box-shadow: 0 0.75rem 1.875rem rgba(58, 99, 151, 0.06);
}

.assessment-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 1.5rem;
  padding: 1.5rem;
}

.hero-heading {
  min-width: 0;
}

.hero-kicker {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  color: #12233d;
  font-size: 1rem;
  font-weight: 850;
}

.hero-kicker::before {
  width: 0.3125rem;
  height: 1.25rem;
  display: inline-block;
  border-radius: 999px;
  background: #168dff;
  content: "";
}

.hero-title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 1.125rem;
}

.hero-title-row h1 {
  min-width: 0;
  margin: 0;
  color: #071226;
  font-size: clamp(1.75rem, 2vw, 2.25rem);
  line-height: 1.22;
  font-weight: 900;
  letter-spacing: 0;
}

.mode-badge {
  display: inline-flex;
  align-items: center;
  min-height: 2rem;
  padding: 0 0.75rem;
  border-radius: 0.5rem;
  font-size: 0.875rem;
  font-weight: 850;
  white-space: nowrap;
}

.mode-badge.warning {
  background: #fff1df;
  color: #ff850c;
}

.mode-badge.success {
  background: #e5f9ee;
  color: #17a861;
}

.mode-badge.info,
.mode-badge.muted {
  background: #e8f3ff;
  color: #087cff;
}

.hero-status {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 0.875rem;
  margin-top: 1.125rem;
  color: #31435d;
}

.hero-status > span {
  width: 2.75rem;
  height: 2.75rem;
  display: grid;
  place-items: center;
  border-radius: 0.875rem;
  font-size: 1.375rem;
}

.hero-status > span.status-warning {
  background: #fff5e8;
  color: #ff8b10;
}

.hero-status > span.status-info {
  background: #e8f3ff;
  color: #087cff;
}

.hero-status > span.status-success {
  background: #e7f8ef;
  color: #17a861;
}

.hero-status strong {
  color: #12233d;
  font-size: 1.125rem;
}

.hero-status p {
  margin: 0.25rem 0 0;
  color: #61738c;
  font-size: 0.875rem;
  line-height: 1.65;
}

.hero-primary-action {
  width: 12rem;
  height: 3.5rem;
  align-self: start;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.625rem;
  border: 0;
  border-radius: 0.5rem;
  background: linear-gradient(135deg, #168dff 0%, #0074ef 100%);
  color: #ffffff;
  box-shadow: 0 0.875rem 1.875rem rgba(22, 141, 255, 0.24);
  font-size: 1rem;
  font-weight: 850;
  cursor: pointer;
}

.hero-primary-action:disabled {
  cursor: not-allowed;
  background: #cfd9e6;
  box-shadow: none;
}

.assessment-path {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(8rem, 1fr));
  align-items: stretch;
  gap: 1rem;
  margin-top: 0.75rem;
}

.assessment-path span {
  position: relative;
  min-height: 5.75rem;
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 0.25rem;
  color: #6a7c94;
  text-align: center;
}

.assessment-path span::after {
  height: 0.125rem;
  position: absolute;
  left: 58%;
  right: -42%;
  top: 1.875rem;
  background: repeating-linear-gradient(90deg, #c9d7e9 0 0.375rem, transparent 0.375rem 0.625rem);
  content: "";
}

.assessment-path span:last-child::after {
  display: none;
}

.assessment-path i {
  width: 3.25rem;
  height: 3.25rem;
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  border: 1px solid #d8e5f5;
  border-radius: 999px;
  background: #f4f9ff;
  color: #087cff;
  font-size: 1.5rem;
  font-style: normal;
}

.assessment-path strong {
  color: #22324a;
  font-size: 0.9375rem;
}

.assessment-path small {
  color: #70829a;
  font-size: 0.8125rem;
}

.assessment-path span.done i,
.assessment-path span.active i {
  border-color: transparent;
  background: linear-gradient(135deg, #168dff 0%, #0074ef 100%);
  color: #ffffff;
  box-shadow: 0 0.75rem 1.625rem rgba(22, 141, 255, 0.22);
}

.assessment-path span.done::after {
  background: linear-gradient(90deg, #168dff, #70bfff);
}

.appointment-inline {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: minmax(11rem, 1fr) 19rem 15rem;
  align-items: center;
  gap: 1.25rem;
  margin-top: 0.5rem;
  padding: 1rem;
  border: 1px solid #dfeaf8;
  border-radius: 0.75rem;
  background: linear-gradient(180deg, #f8fbff 0%, #ffffff 100%);
}

.appointment-copy h2 {
  margin: 0 0 0.5rem;
  color: #12233d;
  font-size: 1.125rem;
  font-weight: 850;
}

.appointment-copy p {
  margin: 0;
  color: #61738c;
  font-size: 0.875rem;
  line-height: 1.7;
}

.appointment-calendar {
  padding: 1rem;
  border: 1px solid #dfeaf8;
  border-radius: 0.75rem;
  background: #ffffff;
}

.appointment-calendar header,
.appointment-slots header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

.appointment-calendar header button {
  width: 1.875rem;
  height: 1.875rem;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 0.5rem;
  background: #f2f7fe;
  color: #30445f;
  cursor: pointer;
}

.appointment-calendar strong,
.appointment-slots strong {
  color: #12233d;
  font-size: 0.9375rem;
}

.appointment-week {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 0.375rem;
  margin-top: 0.875rem;
}

.appointment-week span {
  height: 2rem;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #f4f8fe;
  color: #667990;
  font-size: 0.8125rem;
  font-weight: 750;
}

.appointment-week span:nth-child(3) {
  background: #168dff;
  color: #ffffff;
}

.appointment-slots {
  min-width: 0;
}

.appointment-slots header span {
  color: #61738c;
  font-size: 0.8125rem;
}

.appointment-slots button {
  width: 100%;
  min-height: 2.25rem;
  margin-top: 0.5rem;
  border: 1px solid #cfdff4;
  border-radius: 0.5rem;
  background: #ffffff;
  color: #184675;
  font-weight: 750;
  cursor: pointer;
}

.appointment-slots button:hover:not(:disabled) {
  border-color: #168dff;
  color: #087cff;
  box-shadow: inset 0 0 0 1px rgba(22, 141, 255, 0.1);
}

.appointment-slots button:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.appointment-slots p {
  margin: 0.75rem 0 0;
  color: #7b8ca3;
  font-size: 0.875rem;
}

.assessment-empty {
  min-height: 22rem;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 0.625rem;
  color: #6d7f96;
  text-align: center;
}

.assessment-empty :deep(.anticon) {
  color: #168dff;
  font-size: 3rem;
}

.assessment-empty h2 {
  margin: 0;
  color: #12233d;
  font-size: 1.375rem;
}

.assessment-empty p {
  margin: 0;
}

.recent-assessment-card {
  padding: 1rem;
}

.recent-assessment-card > header,
.side-card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.875rem;
}

.recent-assessment-card h2,
.side-card h3 {
  margin: 0;
  color: #12233d;
  font-size: 1.125rem;
  font-weight: 850;
}

.side-card h3 {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
}

.side-card h3 :deep(.anticon) {
  color: #168dff;
}

.recent-assessment-card header button,
.side-card header button {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  border: 0;
  background: transparent;
  color: #58708e;
  font-size: 0.8125rem;
  font-weight: 750;
  cursor: pointer;
}

.assessment-table {
  overflow: hidden;
  border: 1px solid #dfeaf8;
  border-radius: 0.625rem;
}

.assessment-row {
  display: grid;
  grid-template-columns: minmax(12rem, 2fr) minmax(8rem, 1fr) minmax(6rem, 0.8fr) minmax(10rem, 1.1fr) minmax(8rem, auto);
  align-items: center;
  gap: 1rem;
  min-height: 3.875rem;
  padding: 0 1rem;
  border-top: 1px solid #e8f0fa;
  color: #2e415c;
  font-size: 0.875rem;
}

.assessment-row:first-child {
  border-top: 0;
}

.assessment-head {
  min-height: 2.5rem;
  background: #f4f8fe;
  color: #6d7f96;
  font-size: 0.8125rem;
  font-weight: 800;
}

.assessment-row > span:first-child {
  display: inline-flex;
  align-items: center;
  gap: 0.625rem;
  min-width: 0;
  color: #12233d;
  font-weight: 800;
}

.assessment-row > span:first-child :deep(.anticon) {
  flex-shrink: 0;
  color: #168dff;
  font-size: 1.25rem;
}

.assessment-row i {
  display: inline-flex;
  align-items: center;
  min-height: 1.625rem;
  padding: 0 0.625rem;
  border-radius: 999px;
  background: #e8f7ef;
  color: #18a760;
  font-style: normal;
  font-size: 0.8125rem;
  font-weight: 850;
}

.assessment-row button {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.25rem;
  border: 0;
  background: transparent;
  color: #087cff;
  font-weight: 850;
  cursor: pointer;
}

.recent-empty {
  min-height: 6rem;
  display: grid;
  place-items: center;
  border: 1px dashed #cbdcf0;
  border-radius: 0.625rem;
  color: #7b8da5;
}

.student-pagination {
  justify-content: flex-end;
  margin-top: 0;
}

.progress-panel {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  padding: 1.25rem 1.5rem 1.5rem 0;
  border-left: 0;
  background: #f7fbff;
}

.side-card {
  padding: 1.25rem;
}

.side-card article {
  position: relative;
  display: grid;
  gap: 0.375rem;
  padding: 1rem;
  border: 1px solid #dfeaf8;
  border-radius: 0.625rem;
  background: #fbfdff;
}

.side-card article + article {
  margin-top: 0.75rem;
}

.side-card article strong {
  color: #12233d;
  font-size: 0.9375rem;
  font-weight: 850;
  line-height: 1.45;
}

.side-card article small {
  color: #6e8098;
  font-size: 0.8125rem;
}

.side-card article > span {
  width: fit-content;
  margin-top: 0.25rem;
  padding: 0.1875rem 0.5625rem;
  border-radius: 999px;
  background: #e8f7ef;
  color: #18a760;
  font-size: 0.75rem;
  font-weight: 850;
}

.side-card article button {
  width: fit-content;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  margin-top: 0.25rem;
  border: 0;
  background: transparent;
  color: #087cff;
  font-weight: 850;
  cursor: pointer;
}

.side-empty {
  min-height: 13rem;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 0.625rem;
  padding: 1.25rem;
  border: 1px solid #dfeaf8;
  border-radius: 0.625rem;
  background: linear-gradient(180deg, #fbfdff 0%, #ffffff 100%);
  color: #6e8098;
  text-align: center;
}

.side-empty :deep(.anticon) {
  color: #b5d2f5;
  font-size: 3rem;
}

.side-empty strong {
  color: #12233d;
  font-size: 1rem;
}

.side-empty small {
  color: #7b8da5;
}

@media (max-width: 1360px) {
  .student-workspace {
    grid-template-columns: 15rem minmax(0, 1fr);
  }

  .progress-panel {
    grid-column: 2;
    padding: 0 1.5rem 1.5rem;
  }

  .appointment-inline {
    grid-template-columns: 1fr 18rem;
  }

  .appointment-slots {
    grid-column: 1 / -1;
  }
}

@media (max-width: 980px) {
  .student-page {
    padding: 0.75rem;
  }

  .student-shell {
    min-height: calc(100dvh - 1.5rem);
    border-radius: 1rem;
  }

  .student-topbar {
    min-height: auto;
  }

  .student-workspace {
    min-height: auto;
    grid-template-columns: 1fr;
  }

  .student-sidebar {
    border-right: 0;
    border-bottom: 1px solid #e5effb;
  }

  .progress-panel {
    grid-column: auto;
    padding: 0 1rem 1rem;
  }

  .student-main {
    padding: 1rem;
  }

  .assessment-hero {
    grid-template-columns: 1fr;
  }

  .hero-primary-action {
    width: 100%;
  }

  .appointment-inline {
    grid-template-columns: 1fr;
  }

  .assessment-row,
  .assessment-head {
    grid-template-columns: 1fr;
    align-items: start;
    gap: 0.5rem;
    padding: 0.875rem;
  }

  .assessment-head {
    display: none;
  }
}

@media (max-width: 640px) {
  .student-page {
    padding: 0.5rem;
  }

  .student-topbar {
    align-items: stretch;
  }

  .topbar-actions {
    justify-content: space-between;
  }

  .assessment-path {
    grid-template-columns: 1fr;
  }

  .assessment-path span {
    justify-items: start;
    min-height: auto;
    grid-template-columns: auto minmax(0, 1fr);
    text-align: left;
  }

  .assessment-path span::after {
    display: none;
  }

  .assessment-path small {
    grid-column: 2;
  }
}

/* Keep the sidebar full-height from the top, while the action bar belongs to content. */
.student-shell {
  position: relative;
}

.student-topbar {
  position: absolute;
  top: 0;
  right: 0;
  left: 17rem;
  z-index: 5;
  min-height: 4.5rem;
  border-left: 1px solid #e5effb;
}

.student-workspace {
  min-height: calc(100dvh - 3rem);
}

.student-main {
  padding-top: 5.75rem;
}

.progress-panel {
  padding-top: 5.75rem;
}

@media (max-width: 1360px) {
  .student-topbar {
    left: 15rem;
  }
}

@media (max-width: 980px) {
  .student-topbar {
    position: static;
    border-left: 0;
  }

  .student-main {
    padding-top: 1rem;
  }

  .progress-panel {
    padding-top: 0;
  }
}

/* Precision pass: match the polished blue-white SaaS student dashboard references. */
.student-page {
  min-height: 100dvh;
  padding: 1.25rem;
  background:
    radial-gradient(circle, rgba(185, 207, 234, 0.46) 0.0625rem, transparent 0.0625rem),
    linear-gradient(180deg, #f5faff 0%, #edf6ff 100%);
  background-size: 1.375rem 1.375rem, auto;
}

.student-shell {
  width: calc(100vw - 2.5rem);
  min-height: calc(100dvh - 2.5rem);
  border-color: #dbeafd;
  border-radius: 1.125rem;
  background: linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
  box-shadow: 0 1.5rem 4.5rem rgba(45, 91, 150, 0.13);
}

.student-workspace {
  grid-template-columns: 17.25rem minmax(0, 1fr) 25.75rem;
  min-height: calc(100dvh - 2.5rem);
}

.student-topbar {
  left: 17.25rem;
  min-height: 4.875rem;
  padding: 1rem 1.75rem;
  border-color: #deebfb;
  background: rgba(255, 255, 255, 0.94);
}

.icon-button,
.user-chip {
  border-color: transparent;
  background: #ffffff;
  box-shadow: 0 0.5rem 1.5rem rgba(55, 95, 145, 0.08);
}

.notice-dot {
  box-shadow: 0 0.25rem 0.625rem rgba(239, 58, 58, 0.28);
}

.user-chip > span {
  background: linear-gradient(180deg, #eef6ff 0%, #dfeeff 100%);
  color: #087cff;
}

.student-sidebar {
  padding: 1.625rem 1.35rem 1.875rem;
  border-right-color: #deebfb;
  background: linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
}

.brand-lockup {
  gap: 0.875rem;
  margin-bottom: 0.25rem;
}

.brand-mark {
  width: 2.75rem;
  height: 2.75rem;
  border-radius: 0.75rem;
  box-shadow: 0 0.875rem 1.625rem rgba(0, 116, 239, 0.22);
}

.brand-copy strong {
  font-size: 1.25rem;
  letter-spacing: 0;
}

.brand-copy small {
  display: none;
}

.profile-card {
  display: grid;
  grid-template-columns: 4.125rem minmax(0, 1fr);
  align-items: center;
  column-gap: 0.95rem;
  min-height: 6.75rem;
  padding: 1rem;
  border-color: #e3eefb;
  border-radius: 0.75rem;
  background:
    radial-gradient(circle at 88% 18%, rgba(22, 141, 255, 0.08), transparent 28%),
    linear-gradient(180deg, #f7fbff 0%, #ffffff 100%);
  box-shadow: inset 0 0.0625rem 0 rgba(255, 255, 255, 0.9);
}

.profile-avatar {
  grid-row: 1 / 4;
  width: 3.75rem;
  height: 3.75rem;
  margin: 0;
  position: relative;
  overflow: hidden;
  border-radius: 999px;
  background:
    radial-gradient(circle at 50% 30%, #188dff 0 19%, transparent 20%),
    radial-gradient(ellipse at 50% 86%, #087cff 0 38%, transparent 39%),
    linear-gradient(180deg, #eaf4ff 0%, #d7eaff 100%);
  color: transparent;
  box-shadow: none;
}

.profile-card h2 {
  align-self: end;
  font-size: 1rem;
  line-height: 1.25;
}

.profile-card p,
.profile-card > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-nav {
  gap: 0.55rem;
}

.sidebar-nav button {
  height: 3.125rem;
  padding: 0 1rem;
  border-radius: 0.5rem;
  font-size: 1rem;
}

.sidebar-nav button :deep(.anticon) {
  font-size: 1.18rem;
}

.sidebar-nav button.active,
.sidebar-nav button:hover {
  background: linear-gradient(135deg, #168dff 0%, #006ff0 100%);
  box-shadow: 0 0.75rem 1.375rem rgba(22, 141, 255, 0.22);
}

.trust-card {
  margin-top: auto;
  min-height: 10.75rem;
  border-color: #e5effb;
  border-radius: 0.625rem;
  background:
    radial-gradient(ellipse at 50% 28%, rgba(22, 141, 255, 0.15), transparent 33%),
    linear-gradient(180deg, #f2f8ff 0%, #ffffff 100%);
}

.trust-card :deep(.anticon) {
  width: 4.25rem;
  height: 4.25rem;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #eaf4ff;
  color: #168dff;
}

.trust-card strong {
  color: #087cff;
  font-size: 1rem;
}

.student-main {
  gap: 1.625rem;
  padding: 6rem 1.5rem 1.5rem;
  background: #f8fbff;
}

.progress-panel {
  gap: 1.625rem;
  padding: 6rem 1.5rem 1.5rem 0;
  background: #f8fbff;
}

.assessment-hero,
.recent-assessment-card,
.side-card,
.assessment-empty {
  border-color: #d8e8fb;
  border-radius: 0.75rem;
  background: #ffffff;
  box-shadow:
    0 1.125rem 2.5rem rgba(54, 94, 143, 0.07),
    inset 0 0.0625rem 0 rgba(255, 255, 255, 0.9);
}

.assessment-hero {
  position: relative;
  overflow: hidden;
  grid-template-columns: minmax(0, 1fr) 11.5rem;
  gap: 1.25rem 1.5rem;
  padding: 1.75rem 1.875rem;
  background:
    radial-gradient(circle at 75% 30%, rgba(33, 145, 255, 0.13), transparent 28%),
    linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.assessment-hero.is-direct {
  min-height: 35.25rem;
  align-content: center;
}

.assessment-hero.is-direct::after {
  width: 15.25rem;
  height: 12.5rem;
  position: absolute;
  top: 3rem;
  right: 6.75rem;
  border-radius: 0.75rem;
  background:
    linear-gradient(110deg, transparent 45%, rgba(255, 255, 255, 0.74) 45% 54%, transparent 54%),
    linear-gradient(155deg, transparent 42%, rgba(95, 154, 230, 0.18) 43% 65%, transparent 66%),
    linear-gradient(90deg, #168dff 0 0.45rem, #cfe4ff 0.45rem 1rem, transparent 1rem),
    radial-gradient(ellipse at 52% 92%, rgba(22, 141, 255, 0.16), transparent 48%);
  content: "";
  pointer-events: none;
}

.assessment-hero.is-direct .hero-heading,
.assessment-hero.is-direct .assessment-path,
.assessment-hero.is-direct .hero-primary-action {
  position: relative;
  z-index: 1;
}

.assessment-hero.is-direct .assessment-path {
  width: min(42rem, 100%);
  margin: 1.75rem auto 0;
}

.assessment-hero.is-direct .hero-primary-action {
  grid-column: 1 / -1;
  justify-self: center;
  width: 14.125rem;
  margin-top: 0.5rem;
}

.hero-heading {
  align-self: start;
}

.hero-kicker {
  font-size: 1.125rem;
}

.hero-title-row {
  gap: 0.875rem;
  margin-top: 1.25rem;
}

.hero-title-row h1 {
  font-size: clamp(1.875rem, 2.35vw, 2.75rem);
  letter-spacing: 0;
}

.mode-badge {
  min-height: 2.125rem;
  padding: 0 0.75rem;
  border-radius: 0.5rem;
}

.hero-status {
  margin-top: 1.25rem;
}

.hero-status > span {
  width: 2.875rem;
  height: 2.875rem;
  border-radius: 0.625rem;
}

.hero-status strong {
  font-size: 1.125rem;
}

.hero-primary-action {
  width: 11.5rem;
  height: 3.625rem;
  border-radius: 0.5rem;
  background: linear-gradient(135deg, #168dff 0%, #0074ef 100%);
  box-shadow: 0 0.875rem 1.75rem rgba(0, 116, 239, 0.24);
}

.assessment-path {
  gap: 0;
  margin: 1.125rem 0 0;
  padding: 0 1.25rem;
}

.assessment-path span {
  min-height: 7rem;
  gap: 0.25rem;
}

.assessment-path span::after {
  left: 62%;
  right: -38%;
  top: 2rem;
  background: repeating-linear-gradient(90deg, #c6d7ec 0 0.45rem, transparent 0.45rem 0.75rem);
}

.assessment-path i {
  width: 3.75rem;
  height: 3.75rem;
  border-color: #d5e5f8;
  background: linear-gradient(180deg, #f8fbff 0%, #edf6ff 100%);
  box-shadow: inset 0 0.0625rem 0 #ffffff;
}

.assessment-path span.done i,
.assessment-path span.active i {
  background: linear-gradient(135deg, #168dff 0%, #0074ef 100%);
  box-shadow:
    0 0.875rem 1.75rem rgba(22, 141, 255, 0.2),
    0 0 0 0.5rem rgba(22, 141, 255, 0.08);
}

.assessment-path strong {
  margin-top: 0.25rem;
  color: #13233a;
  font-size: 0.98rem;
}

.assessment-path small {
  color: #6e8098;
}

.assessment-path em {
  width: 1.35rem;
  height: 1.35rem;
  display: grid;
  place-items: center;
  border: 1px solid #c9dbef;
  border-radius: 999px;
  background: #ffffff;
  color: #7a8ca4;
  font-style: normal;
  font-size: 0.75rem;
  font-weight: 850;
}

.assessment-path span.done em,
.assessment-path span.active em {
  border-color: transparent;
  background: #168dff;
  color: #ffffff;
}

.appointment-inline {
  grid-template-columns: minmax(11.5rem, 1fr) 19.5rem 14.5rem;
  gap: 1.5rem;
  margin-top: 0.5rem;
  padding: 1rem 1.125rem;
  border-color: #d8e8fb;
  border-radius: 0.75rem;
  background:
    radial-gradient(circle at 92% 18%, rgba(22, 141, 255, 0.08), transparent 30%),
    linear-gradient(180deg, #f8fbff 0%, #ffffff 100%);
}

.appointment-copy h2 {
  font-size: 1.25rem;
}

.appointment-copy p {
  max-width: 18rem;
}

.appointment-calendar {
  padding: 1rem 1.1rem 0.95rem;
  border-color: #dbe9fa;
  border-radius: 0.625rem;
  box-shadow: 0 0.75rem 1.75rem rgba(55, 95, 145, 0.08);
}

.appointment-calendar header button {
  background: transparent;
  color: #10233f;
}

.appointment-calendar strong {
  font-size: 1rem;
}

.appointment-week-head {
  gap: 0.25rem;
  margin-top: 0.875rem;
}

.appointment-week-head span {
  height: 1.45rem;
  background: transparent;
  color: #71849c;
  font-size: 0.75rem;
}

.appointment-week-head span:nth-child(3) {
  background: transparent;
  color: #71849c;
}

.appointment-days {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 0.25rem;
  margin-top: 0.35rem;
}

.appointment-days span {
  height: 1.8rem;
  display: grid;
  place-items: center;
  border-radius: 999px;
  color: #263b56;
  font-size: 0.8125rem;
  font-weight: 750;
}

.appointment-days span.muted {
  color: #a6b6ca;
}

.appointment-days span.available {
  background: #f4f9ff;
  box-shadow: inset 0 0 0 1px #cfe2fb;
}

.appointment-days span.active {
  background: linear-gradient(135deg, #168dff 0%, #0074ef 100%);
  color: #ffffff;
  box-shadow: 0 0.5rem 1rem rgba(22, 141, 255, 0.25);
}

.appointment-slots {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.appointment-slots header {
  margin-bottom: 0.25rem;
}

.appointment-slots button {
  min-height: 2.125rem;
  border-radius: 0.375rem;
  background: linear-gradient(180deg, #ffffff 0%, #f9fcff 100%);
}

.appointment-slots button:hover:not(:disabled),
.appointment-slots button:first-of-type {
  border-color: #168dff;
  color: #087cff;
  box-shadow: inset 0 0 0 1px rgba(22, 141, 255, 0.12);
}

.recent-assessment-card {
  padding: 1.125rem;
}

.recent-assessment-card h2,
.side-card h3 {
  font-size: 1.125rem;
}

.assessment-table {
  border-color: #dbe9fa;
  border-radius: 0.5rem;
}

.assessment-head {
  background: linear-gradient(180deg, #f6faff 0%, #f1f7ff 100%);
}

.assessment-row {
  min-height: 3.625rem;
  border-color: #e8f1fb;
}

.assessment-row > span:first-child :deep(.anticon) {
  width: 1.875rem;
  height: 1.875rem;
  display: grid;
  place-items: center;
  border-radius: 0.375rem;
  background: linear-gradient(135deg, #168dff 0%, #0074ef 100%);
  color: #ffffff;
  font-size: 1rem;
}

.assessment-row i,
.side-card article > span {
  background: #eef4fb;
  color: #64778f;
}

.assessment-row i.tone-warning,
.side-card article > span.tone-warning {
  background: #fff1df;
  color: #ff850c;
}

.assessment-row i.tone-success,
.side-card article > span.tone-success {
  background: #e5f9ee;
  color: #17a861;
}

.assessment-row i.tone-info,
.side-card article > span.tone-info {
  background: #e8f3ff;
  color: #087cff;
}

.side-card {
  padding: 1.375rem;
}

.side-card article {
  min-height: 5.5rem;
  padding: 1rem;
  border-color: #dbe9fa;
  border-radius: 0.5rem;
  background:
    radial-gradient(circle at 96% 12%, rgba(22, 141, 255, 0.07), transparent 28%),
    #ffffff;
}

.side-card article strong {
  padding-right: 4rem;
}

.side-card article > span {
  position: absolute;
  top: 1rem;
  right: 1rem;
  margin: 0;
}

.side-card article button {
  justify-self: end;
}

.side-empty {
  min-height: 17.25rem;
  border-color: #dbe9fa;
  border-radius: 0.5rem;
  background:
    radial-gradient(ellipse at 50% 35%, rgba(22, 141, 255, 0.12), transparent 34%),
    linear-gradient(180deg, #fbfdff 0%, #ffffff 100%);
}

@media (max-width: 1360px) {
  .student-workspace {
    grid-template-columns: 15rem minmax(0, 1fr);
  }

  .student-topbar {
    left: 15rem;
  }

  .progress-panel {
    padding: 0 1.5rem 1.5rem;
  }

  .appointment-inline {
    grid-template-columns: minmax(11.5rem, 1fr) 19.5rem;
  }

  .appointment-slots {
    grid-column: 1 / -1;
  }
}

@media (max-width: 980px) {
  .student-page {
    padding: 0.75rem;
  }

  .student-shell {
    width: 100%;
    min-height: calc(100dvh - 1.5rem);
  }

  .student-topbar {
    position: static;
    left: auto;
    min-height: auto;
    padding: 1rem;
    border-left: 0;
  }

  .student-workspace {
    min-height: auto;
    grid-template-columns: minmax(0, 1fr);
  }

  .student-sidebar,
  .student-main,
  .progress-panel {
    grid-column: auto;
  }

  .student-sidebar {
    order: 1;
    border-right: 0;
    border-bottom: 1px solid #e5effb;
  }

  .student-main {
    order: 2;
  }

  .progress-panel {
    order: 3;
  }

  .student-main,
  .progress-panel {
    padding: 1rem;
  }

  .assessment-hero {
    grid-template-columns: 1fr;
  }

  .assessment-hero.is-direct {
    min-height: auto;
  }

  .assessment-hero.is-direct::after {
    display: none;
  }

  .appointment-inline {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .student-page {
    padding: 0.5rem;
  }

  .assessment-hero,
  .recent-assessment-card,
  .side-card {
    padding: 1rem;
  }

  .hero-title-row h1 {
    font-size: 1.65rem;
  }

  .profile-card {
    grid-template-columns: 3.75rem minmax(0, 1fr);
  }

  .assessment-path {
    padding: 0;
  }

  .assessment-path span {
    align-items: center;
    grid-template-columns: auto minmax(0, 1fr) auto;
  }

  .assessment-path small {
    grid-column: 2 / -1;
  }

  .appointment-days span {
    height: 1.65rem;
  }
}

/* Flow icon alignment guard: only step wrappers should receive step layout. */
.assessment-path > span {
  position: relative;
  min-height: 7rem;
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 0.25rem;
  color: #6a7c94;
  text-align: center;
}

.assessment-path > span::after {
  height: 0.125rem;
  position: absolute;
  left: 62%;
  right: -38%;
  top: 2rem;
  z-index: 0;
  background: repeating-linear-gradient(90deg, #c6d7ec 0 0.45rem, transparent 0.45rem 0.75rem);
  content: "";
}

.assessment-path > span:last-child::after {
  display: none;
}

.assessment-path > span.done::after {
  background: linear-gradient(90deg, #168dff, #70bfff);
}

.assessment-path > span.done i,
.assessment-path > span.active i {
  border-color: transparent;
  background: linear-gradient(135deg, #168dff 0%, #0074ef 100%);
  color: #ffffff;
  box-shadow:
    0 0.875rem 1.75rem rgba(22, 141, 255, 0.2),
    0 0 0 0.5rem rgba(22, 141, 255, 0.08);
}

.assessment-path i {
  isolation: isolate;
}

.assessment-path i .anticon {
  width: 1.45rem;
  height: 1.45rem;
  min-height: 0;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 0;
  color: inherit;
  line-height: 1;
  text-align: center;
  vertical-align: middle;
}

.assessment-path i .anticon::after {
  display: none;
  content: none;
}

.assessment-path i .anticon svg {
  width: 1.45rem;
  height: 1.45rem;
  display: block;
}

@media (max-width: 640px) {
  .assessment-path > span {
    justify-items: start;
    min-height: auto;
    grid-template-columns: auto minmax(0, 1fr) auto;
    text-align: left;
  }

  .assessment-path > span::after {
    display: none;
  }

  .assessment-path > span small {
    grid-column: 2 / -1;
  }
}
</style>
