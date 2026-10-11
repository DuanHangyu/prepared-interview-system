<template>
  <main class="studentDetail">
    <div class="student-detail-inner">
      <StudentHeader />

      <section class="detail-hero">
        <button class="detail-back" type="button" @click="$router.back()">
          <ArrowLeftOutlined />
        </button>
        <div class="detail-hero-copy">
          <div class="detail-eyebrow">Interview Report</div>
          <h1>{{ detailTitle }}</h1>
          <p>查看本次面试的得分、维度表现、回答细节和 AI 复盘建议。</p>
        </div>
        <div class="detail-score-pill" v-if="!loading && !loadError && showResult">
          <span>{{ info?.defenseScore ?? info?.score ?? "-" }}</span>
          <small>综合得分</small>
        </div>
      </section>

      <nav class="detail-flow-stepper" aria-label="报告复盘流程">
        <span v-for="(item, index) in detailFlowSteps" :key="item" class="active">
          <i>{{ index + 1 }}</i>
          <strong>{{ item }}</strong>
        </span>
      </nav>

      <p v-if="loading" role="status">正在读取报告，请稍候。</p>
      <section v-else-if="loadError" class="detail-card detail-read-error" role="alert" data-testid="legacy-report-error">
        <h2>报告暂不可读取</h2>
        <p>{{ loadError }}</p>
        <p>读取失败不代表回答丢失或零分；请保留原内容，不要重新回答。</p>
        <button type="button" @click="onLoad">重新读取报告</button>
      </section>
      <section v-else class="detail-grid">
        <aside class="detail-score-column">
          <section class="detail-card score-card">
            <div class="detail-card-title">
              <FundOutlined />
              当前得分
            </div>
            <div class="detail-card-body">
              <div class="detail-locked" v-if="!showResult">
                <LockOutlined />
                <span>教师设置不允许查看</span>
              </div>
              <DqdfChart :info="info" v-else-if="info?.id" />
            </div>
            <p class="detail-note" v-if="showResult">当前得分由系统换算得出</p>
          </section>

          <section class="detail-card score-card">
            <div class="detail-card-title">
              <BarChartOutlined />
              得分明细
            </div>
            <div class="detail-card-body">
              <div class="detail-locked" v-if="!showResult">
                <LockOutlined />
                <span>教师设置不允许查看</span>
              </div>
              <DfmxChart :info="info" v-else-if="info?.id" />
            </div>
          </section>
        </aside>

        <section class="detail-card detail-main-card">
          <header class="detail-main-head">
            <div class="detail-card-title">
              <FileTextOutlined />
              {{ showResult ? "答辩详情" : "建议老师发布" }}
            </div>
            <div class="detail-nav">
              <button type="button" @click="prev" :disabled="list.length <= 1">
                <LeftOutlined />
              </button>
              <button type="button" @click="next" :disabled="list.length <= 1">
                <RightOutlined />
              </button>
            </div>
          </header>

          <div class="detail-card-stage">
            <template v-for="(item, index) in list">
              <div
                class="detail-stack-item"
                v-if="index < 3"
                :class="`index${index}`"
                :key="index"
              >
                <AnalysisCard
                  :info="item"
                  :summary="info?.summary"
                  v-if="item.cardTag == 'analysis'"
                />
                <SuggestionCard
                  :info="item"
                  v-else-if="item.cardTag == 'suggestions'"
                />
                <LdYBzCard
                  :info="item"
                  v-else-if="item.cardTag == 'strengths'"
                />
                <DefenseCard
                  :info="item"
                  v-else-if="item.cardTag == 'defense'"
                />
                <QuestionCard :info="item" :key="index" v-else />
              </div>
            </template>
            <div class="detail-locked empty-card" v-if="!loading && !list.length">
              <FileTextOutlined />
              <span>{{ showResult ? '暂无报告内容' : '成绩尚未公开' }}</span>
            </div>
          </div>
        </section>
      </section>

      <footer v-if="!loading && !loadError && showResult && list.length" class="detail-footer">本报告由人工智能生成</footer>
    </div>
  </main>
</template>
<script setup lang="ts">
import StudentHeader from "@/components/Layout/componets/StudentHeader.vue";
import DefenseCard from "./components/DefenseCard.vue";
import QuestionCard from "./components/QuestionCard.vue";
import SuggestionCard from "./components/SuggestionCard.vue";
import LdYBzCard from "./components/LdYBzCard.vue";
import AnalysisCard from "./components/AnalysisCard.vue";
import { useRoute } from "vue-router";
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, watch } from "vue";
import { getStudentAssessmentDetail } from "@/api/studentAssessment";
import { getStudyDetail } from "@/api/studyRecord";
import {
  ArrowLeftOutlined,
  BarChartOutlined,
  FileTextOutlined,
  FundOutlined,
  LeftOutlined,
  LockOutlined,
  RightOutlined,
} from "@ant-design/icons-vue";

const DfmxChart = defineAsyncComponent(
  () => import("./components/DfmxChart.vue")
);
const DqdfChart = defineAsyncComponent(
  () => import("./components/DqdfChart.vue")
);

const colorClasses = [
  "cardColor1",
  "cardColor2",
  "cardColor3",
  "cardColor4",
  "cardColor5",
  "cardColor6",
];
const detailFlowSteps = ["预约考核", "设备检测", "正式面试", "报告复盘"];
const loading = ref(false);
const loadError = ref("");
const route = useRoute();
const info = ref<Recordable>({});
const list = ref<Recordable[]>([]);
const showResult = computed(() => {
  if (route.name == "StudentStudyDetail") {
    return info.value?.showResult === true;
  } else {
    return true;
  }
});
const detailTitle = computed(() => info.value?.theme || "面试复盘报告");
let loadEpoch = 0;
const onLoad = async () => {
  const epoch = ++loadEpoch;
  loading.value = true;
  loadError.value = "";
  info.value = {};
  list.value = [];
  try {
      const res = await (route.name == "StudentStudyDetail" ? getStudentAssessmentDetail : getStudyDetail)({id: route.query?.id});
      if (epoch !== loadEpoch) return;
      const response = res?.data || {};
      if (route.name === "StudentStudyDetail" && typeof response.showResult !== "boolean") {
        throw new Error("报告可见性信息无效，请重新读取或联系管理教师");
      }
      info.value = response;
      if (!showResult.value) return;
      const original = info.value;
      if (original.defense) list.value.push({ cardTag: "defense", ...original.defense });
      if (Array.isArray(original.result)) list.value.push(...original.result.map((item: Recordable,index: number) => ({ ...item, cardTag: "question", questionIndex: index + 1 })));
      if (original.analysis?.analysis?.length) list.value.push({ cardTag: "analysis", analysis: original.analysis.analysis });
      if (original.analysis?.suggestions?.length) list.value.push({ cardTag: "suggestions", suggestions: original.analysis.suggestions });
      if (original.analysis?.strengths?.length || original.analysis?.weaknesses?.length) list.value.push({ cardTag: "strengths", strengths: original.analysis.strengths, weaknesses: original.analysis.weaknesses });
      list.value.forEach((item,index) => { item.colorClass = colorClasses[index % 6]; });
  } catch (error) {
      if (epoch === loadEpoch) loadError.value = error instanceof Error ? error.message : "报告读取失败，请稍后重试或联系管理教师";
  } finally {
      if (epoch === loadEpoch) loading.value = false;
  }
};

onMounted(() => {
  onLoad();
});
watch(() => [route.name, route.query.id], onLoad);
onUnmounted(() => { loadEpoch++; });

const prev = () => {
  const data = list.value?.pop();
  list.value?.unshift(data as any);
  setTimeout(() => {
    document.querySelector(".index0")?.classList?.add("fade-enter");
  }, 100);
  setTimeout(() => {
    document.querySelector(".index0")?.classList?.remove("fade-enter");
  }, 800);
};
const next = () => {
  const data = list.value?.shift();
  list.value?.push(data as any);
  setTimeout(() => {
    document.querySelector(".index0")?.classList?.add("fade-enter");
  }, 100);
  setTimeout(() => {
    document.querySelector(".index0")?.classList?.remove("fade-enter");
  }, 800);
};
</script>
<style scoped>
.studentDetail {
  width: 100%;
  min-height: 100vh;
  padding: 24px;
  overflow-y: auto;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.36), rgba(0, 0, 0, 0.18)),
    linear-gradient(135deg, #eeeeec 0%, #d5d5d2 45%, #7f807d 100%);
}
.detail-read-error { padding: 24px; margin-top: 16px; }
.detail-read-error h2 { margin-top: 0; }
.detail-read-error button { padding: 8px 16px; border: 1px solid #94a3b8; border-radius: 6px; background: white; cursor: pointer; }

.student-detail-inner {
  width: min(1500px, 100%);
  min-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 0 auto;
}

.detail-hero {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  padding: 24px;
  border: 1px solid var(--color-border-light);
  border-radius: 28px;
  background: var(--color-surface);
  box-shadow:
    0 28px 80px rgba(20, 21, 22, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(22px);
}

.detail-back,
.detail-nav button {
  border: 0;
  cursor: pointer;
  transition:
    transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1),
    box-shadow 180ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.detail-back {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: linear-gradient(145deg, #444544, #2f302f);
  color: #ffffff;
  box-shadow: 0 14px 30px rgba(20, 21, 22, 0.22);
}

.detail-back:hover,
.detail-nav button:not(:disabled):hover {
  transform: translateY(-1px);
}

.detail-eyebrow {
  width: fit-content;
  padding: 6px 11px;
  border-radius: 999px;
  background: var(--color-accent-soft);
  color: var(--color-accent);
  font-size: 12px;
  font-weight: 800;
}

.detail-hero h1 {
  margin: 10px 0 8px;
  color: var(--color-text-primary);
  font-size: clamp(30px, 3.6vw, 46px);
  font-weight: 850;
  line-height: 1.08;
}

.detail-hero p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 15px;
  line-height: 1.7;
}

.detail-score-pill {
  min-width: 150px;
  min-height: 96px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: 18px;
  border-radius: 22px;
  background: linear-gradient(145deg, #444544, #2f302f);
  color: #ffffff;
  box-shadow: 0 18px 42px rgba(20, 21, 22, 0.18);
}

.detail-score-pill span {
  font-size: 34px;
  font-weight: 850;
  line-height: 1;
}

.detail-score-pill small {
  margin-top: 8px;
  color: rgba(255, 255, 255, 0.58);
  font-size: 12px;
  font-weight: 700;
}

.detail-grid {
  flex: 1;
  min-height: 680px;
  display: grid;
  grid-template-columns: 410px minmax(0, 1fr);
  gap: 16px;
}

.detail-score-column {
  display: grid;
  grid-template-rows: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.detail-card {
  min-width: 0;
  min-height: 0;
  padding: 20px;
  border: 1px solid var(--color-border-light);
  border-radius: 26px;
  background: var(--color-surface);
  box-shadow:
    0 20px 52px rgba(20, 21, 22, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(22px);
}

.score-card {
  display: flex;
  flex-direction: column;
  min-height: 330px;
}

.detail-card-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-primary);
  font-size: 16px;
  font-weight: 850;
}

.detail-card-title :deep(.anticon) {
  color: var(--color-accent);
}

.detail-card-body {
  min-height: 0;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.score-card .detail-card-body {
  min-height: 260px;
}

.detail-note {
  margin: 8px 0 0;
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 600;
  text-align: center;
}

.detail-locked {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 220px;
  color: var(--color-text-muted);
  font-size: 15px;
  font-weight: 700;
}

.detail-locked :deep(.anticon) {
  color: var(--color-accent);
  font-size: 28px;
}

.detail-main-card {
  display: flex;
  flex-direction: column;
}

.detail-main-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-shrink: 0;
}

.detail-nav {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-nav button {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--color-text-primary);
}

.detail-nav button:disabled {
  cursor: not-allowed;
  color: var(--color-text-muted);
  opacity: 0.5;
}

.detail-card-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  margin-top: 18px;
}

.detail-stack-item {
  position: absolute;
  width: 100%;
}

.index0 {
  height: calc(100% - 32px);
  top: 32px;
  left: 0;
  z-index: 3;
}

.index1 {
  width: calc(100% - 32px);
  height: calc(100% - 16px);
  top: 16px;
  left: 16px;
  z-index: 2;
}

.index2 {
  width: calc(100% - 64px);
  height: 100%;
  top: 0;
  left: 32px;
  z-index: 1;
}

.empty-card {
  height: 100%;
}

.detail-footer {
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255, 255, 255, 0.82);
  font-size: 12px;
  font-weight: 600;
}

@keyframes scaleEnter {
  0% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.03);
  }
  100% {
    transform: scale(1);
  }
}

.fade-enter {
  animation: scaleEnter 0.9s cubic-bezier(0.34, 1.56, 0.64, 1);
}

@media (max-width: 1180px) {
  .detail-grid,
  .detail-hero {
    grid-template-columns: 1fr;
  }

  .detail-score-column {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: 1fr;
  }

  .detail-grid {
    min-height: 1040px;
  }

  .detail-score-pill {
    width: 100%;
  }
}

@media (max-width: 760px) {
  .studentDetail {
    padding: 16px;
  }

  .detail-hero,
  .detail-card {
    padding: 18px;
    border-radius: 24px;
  }

  .detail-score-column {
    grid-template-columns: 1fr;
  }

  .detail-grid {
    min-height: 1260px;
  }
}

/* DESIGN.md refresh: report review page uses the same light SaaS surface. */
.studentDetail {
  --edu-page-bg: #f3f7fc;
  --edu-page-dot: rgba(207, 218, 235, 0.42);
  --edu-shell: #ffffff;
  --edu-main: #f7fbff;
  --edu-section: #edf6ff;
  --edu-border: #e3edf9;
  --edu-border-soft: rgba(223, 234, 247, 0.72);
  --edu-primary: #1597ff;
  --edu-primary-deep: #0078e7;
  --edu-primary-soft: #dff0ff;
  --edu-primary-glow: rgba(21, 151, 255, 0.22);
  --edu-text: #12233d;
  --edu-main-text: #27364b;
  --edu-muted: #6b7c93;

  background:
    radial-gradient(circle, var(--edu-page-dot) 1px, transparent 1px),
    var(--edu-page-bg);
  background-size: 28px 28px;
  color: var(--edu-text);
}

.student-detail-inner {
  width: min(1500px, 100%);
}

.detail-hero,
.detail-card {
  border: 1px solid var(--edu-border);
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 8px 22px rgba(54, 97, 150, 0.08);
  backdrop-filter: none;
}

.detail-hero {
  padding: 20px;
  background:
    linear-gradient(90deg, rgba(234, 244, 255, 0.82), rgba(255, 255, 255, 0.76)),
    #ffffff;
}

.detail-back {
  border-radius: 12px;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  box-shadow: 0 12px 24px var(--edu-primary-glow);
}

.detail-eyebrow {
  border-radius: 999px;
  background: var(--edu-primary-soft);
  color: var(--edu-primary-deep);
}

.detail-hero h1 {
  color: var(--edu-text);
  font-size: clamp(26px, 2.6vw, 34px);
}

.detail-hero p {
  color: var(--edu-muted);
}

.detail-score-pill {
  border-radius: 14px;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  box-shadow: 0 16px 34px var(--edu-primary-glow);
}

.detail-score-pill small {
  color: rgba(255, 255, 255, 0.74);
}

.detail-flow-stepper {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.detail-flow-stepper span {
  min-height: 48px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  border: 1px solid var(--edu-border);
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff, #f4f8fe);
  color: var(--edu-muted);
  font-weight: 800;
}

.detail-flow-stepper span:last-child {
  border-color: transparent;
  background: linear-gradient(135deg, var(--edu-primary), var(--edu-primary-deep));
  color: #ffffff;
  box-shadow: 0 10px 22px var(--edu-primary-glow);
}

.detail-flow-stepper i {
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

.detail-flow-stepper span:last-child i {
  background: rgba(255, 255, 255, 0.24);
  color: #ffffff;
}

.detail-grid {
  min-height: 680px;
}

.detail-card {
  padding: 18px;
}

.score-card {
  min-height: 300px;
}

.detail-card-title {
  color: var(--edu-text);
}

.detail-card-title :deep(.anticon),
.detail-locked :deep(.anticon) {
  color: var(--edu-primary);
}

.detail-note,
.detail-locked {
  color: var(--edu-muted);
}

.detail-nav button {
  border: 1px solid var(--edu-border);
  border-radius: 8px;
  background: #ffffff;
  color: var(--edu-primary-deep);
}

.detail-main-card {
  background: linear-gradient(180deg, #edf6ff, #f8fbff);
}

.detail-card-stage {
  margin-top: 14px;
}

.detail-footer {
  color: var(--edu-muted);
}

@media (max-width: 1180px) {
  .detail-flow-stepper {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .detail-flow-stepper {
    grid-template-columns: 1fr;
  }
}
</style>
<style>
.cardColor1,
.cardColor2,
.cardColor3,
.cardColor4,
.cardColor5,
.cardColor6 {
  border: 1px solid rgba(255, 255, 255, 0.72);
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(246, 246, 244, 0.64)),
    linear-gradient(135deg, rgba(231, 95, 73, 0.12), rgba(68, 69, 68, 0.08));
  box-shadow:
    0 18px 42px rgba(20, 21, 22, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(22px);
}

.cardColor2 {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(246, 246, 244, 0.64)),
    linear-gradient(135deg, rgba(231, 95, 73, 0.16), rgba(79, 111, 143, 0.08));
}

.cardColor3 {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(246, 246, 244, 0.64)),
    linear-gradient(135deg, rgba(216, 154, 53, 0.13), rgba(68, 69, 68, 0.08));
}

.cardColor4 {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(246, 246, 244, 0.64)),
    linear-gradient(135deg, rgba(79, 111, 143, 0.14), rgba(231, 95, 73, 0.08));
}

.cardColor5 {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(246, 246, 244, 0.64)),
    linear-gradient(135deg, rgba(47, 143, 105, 0.12), rgba(68, 69, 68, 0.08));
}

.cardColor6 {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(246, 246, 244, 0.64)),
    linear-gradient(135deg, rgba(68, 69, 68, 0.13), rgba(231, 95, 73, 0.1));
}

.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}

.custom-scrollbar::-webkit-scrollbar-track {
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.3);
}

.custom-scrollbar::-webkit-scrollbar-thumb {
  border-radius: 3px;
  background: #b7b8b6;
}

.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: #9b9c99;
}

@supports not selector(::-webkit-scrollbar) {
  .custom-scrollbar {
    scrollbar-width: thin;
    scrollbar-color: #b7b8b6 rgba(255, 255, 255, 0.3);
  }
}

.studentDetail .cardColor1,
.studentDetail .cardColor2,
.studentDetail .cardColor3,
.studentDetail .cardColor4,
.studentDetail .cardColor5,
.studentDetail .cardColor6 {
  border: 1px solid #e3edf9;
  border-radius: 12px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(248, 251, 255, 0.94)),
    linear-gradient(135deg, rgba(21, 151, 255, 0.09), rgba(66, 201, 255, 0.04));
  box-shadow: 0 12px 30px rgba(54, 97, 150, 0.08);
  backdrop-filter: none;
}

.studentDetail .cardColor3,
.studentDetail .cardColor5 {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(248, 251, 255, 0.94)),
    linear-gradient(135deg, rgba(55, 201, 120, 0.1), rgba(21, 151, 255, 0.04));
}

.studentDetail .custom-scrollbar::-webkit-scrollbar-thumb {
  background: #b8c8dc;
}

.studentDetail .custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: #8ea8c5;
}
</style>
