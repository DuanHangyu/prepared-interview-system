<template>
  <div class="result-over-page">
    <div class="result-over-inner">
      <StudentHeader />

      <section class="result-card">
        <div class="result-visual">
          <img src="../assets/resultover.png" alt="" class="result-image" />
          <span class="result-orbit"></span>
        </div>

        <div class="result-content">
          <div class="result-eyebrow">Interview Submitted</div>
          <h1>{{ statusTitle }}</h1>
          <p>{{ statusDescription }}</p>

          <div class="result-state-card" :class="status">
            <span class="result-state-icon">
              <CheckCircleOutlined v-if="status === 'done'" />
              <ExclamationCircleOutlined v-else-if="status === 'error'" />
              <ClockCircleOutlined v-else />
            </span>
            <span>
              <strong>{{ statusLabel }}</strong>
              <small>{{ statusHint }}</small>
            </span>
          </div>

          <div class="result-actions">
            <Button v-if="canViewResult" type="primary" @click="goStudyDetail">
              <FileSearchOutlined />
              查看分析结果
            </Button>
            <Button @click="goDashboard">
              <HomeOutlined />
              返回主界面
            </Button>
            <Button
              v-if="status === 'error'"
              type="primary"
              ghost
              @click="pollDetail"
            >
              <ReloadOutlined />
              重新检查
            </Button>
          </div>
        </div>
      </section>
    </div>
    <LoadingOverlay v-if="checking && !loaded" text="正在确认分析状态，请稍后..." />
  </div>
</template>

<script setup lang="ts">
import { Button } from "ant-design-vue";
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import LoadingOverlay from "@/components/LoadingOverlay/index.vue";
import { getStudentAssessmentDetail } from "@/api/studentAssessment";
import StudentHeader from "@/components/Layout/componets/StudentHeader.vue";
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  FileSearchOutlined,
  HomeOutlined,
  ReloadOutlined,
} from "@ant-design/icons-vue";

type AnalysisStatus = "pending" | "retrying" | "done" | "error";

const POLL_INTERVAL = 5000;
const route = useRoute();
const router = useRouter();
const status = ref<AnalysisStatus>("pending");
const loaded = ref(false);
const checking = ref(false);
const detail = ref<Recordable>({});
let pollTimer: number | undefined;

const canViewResult = computed(
  () => status.value === "done" && !!detail.value?.showResult
);

const statusTitle = computed(() => {
  if (status.value === "done") {
    return "考核分析已完成";
  }
  if (status.value === "retrying") {
    return "分析暂未完成";
  }
  if (status.value === "error") {
    return "分析状态确认失败";
  }
  return "考核已提交，正在分析";
});

const statusDescription = computed(() => {
  if (status.value === "done") {
    return detail.value?.showResult
      ? "系统已生成本次面试结果，可以查看分析详情。"
      : "系统已完成本次面试分析，结果暂不对学生展示。";
  }
  if (status.value === "retrying") {
    return "系统会自动重试生成分析结果，请稍后回到主界面查看。";
  }
  if (status.value === "error") {
    return "暂时无法获取分析状态，请检查网络后重新检查。";
  }
  return "系统正在整理录音、转写内容和评分结果，请不要重复进入面试。";
});

const statusLabel = computed(() => {
  if (status.value === "done") {
    return detail.value?.showResult ? "报告可查看" : "报告已归档";
  }
  if (status.value === "retrying") {
    return "自动重试中";
  }
  if (status.value === "error") {
    return "需要重新检查";
  }
  return "分析处理中";
});

const statusHint = computed(() => {
  if (status.value === "done") {
    return detail.value?.showResult ? "点击查看完整复盘" : "教师暂未开放学生查看";
  }
  if (status.value === "retrying") {
    return "稍后可在主界面查看进度";
  }
  if (status.value === "error") {
    return "网络恢复后再次确认";
  }
  return "通常需要等待片刻";
});

function clearPollTimer() {
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = undefined;
  }
}

function scheduleNextPoll() {
  clearPollTimer();
  if (status.value !== "done") {
    pollTimer = window.setTimeout(pollDetail, POLL_INTERVAL);
  }
}

async function pollDetail() {
  const assessmentId = route.query?.id;
  if (!assessmentId) {
    status.value = "error";
    loaded.value = true;
    return;
  }

  checking.value = true;
  try {
    const res = await getStudentAssessmentDetail({ id: assessmentId });
    detail.value = res?.data || {};
    const hasAnalysisResult =
      !!detail.value?.analysis ||
      !!detail.value?.defense ||
      (Array.isArray(detail.value?.result) && detail.value.result.length > 0);
    if (detail.value?.state === 1 || hasAnalysisResult) {
      status.value = "done";
      clearPollTimer();
    } else if (detail.value?.state === 2 && detail.value?.reAnalysis === false) {
      status.value = "retrying";
      scheduleNextPoll();
    } else {
      status.value = "pending";
      scheduleNextPoll();
    }
  } catch (error) {
    console.error("确认分析状态失败", error);
    status.value = "error";
    scheduleNextPoll();
  } finally {
    loaded.value = true;
    checking.value = false;
  }
}

function goStudyDetail() {
  router.push({
    name: "StudentStudyDetail",
    query: route.query,
  });
}

function goDashboard() {
  router.push("/");
}

onMounted(() => {
  pollDetail();
});

onUnmounted(() => {
  clearPollTimer();
});
</script>

<style scoped>
.result-over-page {
  width: 100%;
  min-height: 100vh;
  padding: 24px;
  overflow-y: auto;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.36), rgba(0, 0, 0, 0.18)),
    linear-gradient(135deg, #eeeeec 0%, #d5d5d2 45%, #7f807d 100%);
}

.result-over-inner {
  width: min(1280px, 100%);
  margin: 0 auto;
}

.result-card {
  min-height: calc(100vh - 132px);
  display: grid;
  grid-template-columns: 0.88fr 1.12fr;
  gap: 24px;
  align-items: stretch;
  margin-top: 16px;
  padding: 24px;
  border: 1px solid var(--color-border-light);
  border-radius: 30px;
  background: var(--color-surface);
  box-shadow:
    0 28px 80px rgba(20, 21, 22, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(22px);
}

.result-visual {
  position: relative;
  min-height: 420px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 24px;
  background: linear-gradient(145deg, #444544, #2f302f);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

.result-orbit {
  position: absolute;
  inset: auto 12% 16% 12%;
  height: 20px;
  border-radius: 999px;
  background: rgba(231, 95, 73, 0.22);
  filter: blur(18px);
}

.result-image {
  position: relative;
  z-index: 1;
  width: min(300px, 70%);
  max-height: 60%;
  object-fit: contain;
  filter: drop-shadow(0 24px 42px rgba(0, 0, 0, 0.28));
}

.result-content {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 24px clamp(8px, 3vw, 44px);
}

.result-eyebrow {
  width: fit-content;
  padding: 6px 11px;
  border-radius: 999px;
  background: var(--color-accent-soft);
  color: var(--color-accent);
  font-size: 12px;
  font-weight: 800;
}

.result-content h1 {
  margin: 14px 0 12px;
  color: var(--color-text-primary);
  font-size: clamp(32px, 4vw, 52px);
  font-weight: 850;
  line-height: 1.08;
}

.result-content p {
  max-width: 560px;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 16px;
  line-height: 1.8;
}

.result-state-card {
  max-width: 520px;
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 28px;
  padding: 16px;
  border: 1px solid rgba(24, 25, 27, 0.08);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.62);
}

.result-state-icon {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 999px;
  background: var(--color-accent-soft);
  color: var(--color-accent);
  font-size: 22px;
}

.result-state-card.done .result-state-icon {
  background: rgba(47, 143, 105, 0.12);
  color: var(--color-success);
}

.result-state-card.error .result-state-icon {
  background: rgba(216, 74, 58, 0.12);
  color: var(--color-danger);
}

.result-state-card strong,
.result-state-card small {
  display: block;
}

.result-state-card strong {
  color: var(--color-text-primary);
  font-size: 15px;
  font-weight: 800;
}

.result-state-card small {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.result-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 28px;
  flex-wrap: wrap;
}

@media (max-width: 860px) {
  .result-card {
    grid-template-columns: 1fr;
  }

  .result-visual {
    min-height: 300px;
  }
}
</style>
