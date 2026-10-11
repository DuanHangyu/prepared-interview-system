<template>
  <section class="paper-editor" aria-label="预置试卷编辑">
    <header class="paper-heading">
      <div>
        <h1>准备面试题目</h1>
        <p>考前填写并审核固定题目。正文或评分规则修改后需要重新审核。</p>
      </div>
      <span v-if="loaded">版本 {{ revision }}{{ dirty ? ' · 有未保存修改' : '' }}</span>
    </header>
    <p v-if="error" role="alert" class="paper-error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="loading" role="status">正在读取已保存的草稿……</p>
    <div class="paper-toolbar">
      <button type="button" :disabled="!loaded || busy || questions.length >= 50" @click="addQuestion">新增题目</button>
      <button type="button" :disabled="!loaded || busy" @click="save">保存草稿</button>
      <button type="button" :disabled="busy" @click="reload">重新读取</button>
      <span v-if="loaded">{{ questions.length }} 道题 · 分值合计 {{ totalScore }}</span>
    </div>
    <PreparedGenerationPanel v-if="loaded" :key="assessmentId" :assessment-id="assessmentId" :revision="revision"
      :has-unsaved-changes="dirty" :parent-busy="loading || saving || publishing || audioWriting" @busy="adopting = $event" @adopted="acceptAdoption" />
    <PreparedAudioPanel v-if="loaded" :key="assessmentId" :assessment-id="assessmentId" :revision="revision" :questions="questions"
      :has-unsaved-changes="dirty" :parent-busy="loading || saving || adopting || publishing" @busy="audioWriting = $event" @revision-changed="acceptAudioRevision" />
    <PreparedPublicationPanel v-if="loaded" :key="assessmentId" :assessment-id="assessmentId" :revision="revision"
      :has-unsaved-changes="dirty" :parent-busy="loading || saving || adopting || audioWriting" @busy="publishing = $event" />
    <p v-if="loaded && !questions.length" class="paper-empty">尚未添加题目。点击“新增题目”，填写第一道题。</p>
    <article v-for="(q, index) in questions" :key="q.questionId" data-testid="prepared-question" class="question-card">
      <header>
        <h2>第 {{ index + 1 }} 题</h2>
        <span>{{ q.approved && unchanged(q) ? '已审核' : '待审核' }}</span>
        <span>{{ q.source === 'AI' ? 'AI 建议' : '手工题目' }}</span>
      </header>
      <label :for="`body-${q.questionId}`">题目正文</label>
      <textarea :id="`body-${q.questionId}`" v-model="q.body" data-testid="question-body" :disabled="busy" maxlength="10000" rows="4" />
      <div class="question-rules">
        <label :for="`score-${q.questionId}`">分值
          <input :id="`score-${q.questionId}`" v-model.number="q.maxScore" type="number" min="1" max="10000" :disabled="busy" />
        </label>
        <label :for="`time-${q.questionId}`">回答时限（秒）
          <input :id="`time-${q.questionId}`" v-model.number="q.answerSeconds" type="number" min="1" max="1800" :disabled="busy" />
        </label>
      </div>
      <label :for="`rubric-${q.questionId}`">评分要求（仅教师可见）</label>
      <textarea :id="`rubric-${q.questionId}`" v-model="q.rubric" data-testid="question-rubric" :disabled="busy" maxlength="20000" rows="3" />
      <footer>
        <button type="button" data-testid="move-up" :disabled="busy || index === 0" @click="move(index, -1)">上移</button>
        <button type="button" data-testid="move-down" :disabled="busy || index === questions.length - 1" @click="move(index, 1)">下移</button>
        <button type="button" data-testid="remove-question" :disabled="busy" @click="remove(index)">删除此题</button>
        <button type="button" :disabled="busy || dirty || q.approved" @click="approve(q.questionId)">审核此题</button>
        <span v-if="dirty">请先保存修改，再审核题目</span>
      </footer>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import PreparedGenerationPanel from './PreparedGenerationPanel.vue';
import PreparedPublicationPanel from './PreparedPublicationPanel.vue';
import PreparedAudioPanel from './PreparedAudioPanel.vue';
import {
  getPreparedDraft, savePreparedDraft, approvePreparedQuestions,
  type PreparedDraft, type PreparedQuestion, type PreparedQuestionInput,
} from '@/api/preparedPaper';

const props = defineProps<{ assessmentId: number }>();
const revision = ref(0);
const questions = ref<PreparedQuestion[]>([]);
const baseline = ref<PreparedQuestion[]>([]);
const loaded = ref(false);
const loading = ref(false);
const saving = ref(false);
const adopting = ref(false);
const publishing = ref(false);
const audioWriting = ref(false);
const error = ref('');
const notice = ref('');
let generation = 0;
const busy = computed(() => loading.value || saving.value || adopting.value || publishing.value || audioWriting.value);
const inputs = (items: PreparedQuestion[]): PreparedQuestionInput[] => items.map(q => ({
  questionId: q.questionId, body: q.body, maxScore: q.maxScore,
  answerSeconds: q.answerSeconds, rubric: q.rubric,
}));
const dirty = computed(() => JSON.stringify(inputs(questions.value)) !== JSON.stringify(inputs(baseline.value)));
const totalScore = computed(() => questions.value.reduce((sum, q) => sum + (Number(q.maxScore) || 0), 0));

function unchanged(q: PreparedQuestion) {
  const previous = baseline.value.find(item => item.questionId === q.questionId);
  return previous && JSON.stringify(inputs([previous])) === JSON.stringify(inputs([q]));
}

function accept(draft: PreparedDraft) {
  if (draft?.assessmentId !== props.assessmentId || !Number.isSafeInteger(draft?.revision)
      || draft.revision < 0 || !Array.isArray(draft.questions)) {
    throw new Error('服务器返回的草稿无效，当前编辑内容未覆盖');
  }
  revision.value = draft.revision;
  questions.value = draft.questions.map(q => ({ ...q }));
  baseline.value = draft.questions.map(q => ({ ...q }));
  loaded.value = true;
}

function failure(reason: unknown) {
  error.value = reason instanceof Error ? reason.message : '操作失败，当前编辑内容仍保留，请稍后重试';
}

function acceptAdoption(draft: PreparedDraft) {
  try {
    if (dirty.value) throw new Error('当前有未保存修改，采用结果未覆盖，请保留编辑并重新读取');
    accept(draft);
    notice.value = 'AI 建议已加入草稿，请逐题审核';
  } catch (reason) { failure(reason); }
}

async function load() {
  const current = ++generation;
  const assessmentId = props.assessmentId;
  loaded.value = false;
  loading.value = true;
  error.value = '';
  notice.value = '';
  try {
    if (!Number.isSafeInteger(assessmentId) || assessmentId <= 0) throw new Error('考核标识无效');
    const response = await getPreparedDraft(assessmentId);
    if (current !== generation) return;
    accept(response.data);
  } catch (reason) {
    if (current === generation) failure(reason);
  } finally {
    if (current === generation) loading.value = false;
  }
}

async function reload() {
  if (dirty.value && !window.confirm('重新读取会替换当前未保存修改，是否继续？')) return;
  await load();
}
async function acceptAudioRevision(serverRevision:number){
  if(!Number.isSafeInteger(serverRevision) || serverRevision<=revision.value || dirty.value || busy.value)return;
  await load();
}

function addQuestion() {
  notice.value = '';
  questions.value.push({ questionId: crypto.randomUUID(), source: 'MANUAL', body: '',
    ordinal: questions.value.length + 1, maxScore: 10, answerSeconds: 120, rubric: '',
    approved: false, audioState: 'PENDING', audioObjectKey: null });
}

function move(index: number, offset: number) {
  const next = index + offset;
  if (next < 0 || next >= questions.value.length) return;
  const [question] = questions.value.splice(index, 1);
  questions.value.splice(next, 0, question);
  notice.value = '';
}

function remove(index: number) {
  questions.value.splice(index, 1);
  notice.value = '';
}

function validate() {
  for (let index = 0; index < questions.value.length; index++) {
    const q = questions.value[index];
    if (!q.body.trim() || q.body.length > 10000) throw new Error(`第 ${index + 1} 题正文不能为空或超长`);
    if (!q.rubric.trim() || q.rubric.length > 20000) throw new Error(`第 ${index + 1} 题评分要求不能为空或超长`);
    if (!Number.isInteger(q.maxScore) || q.maxScore < 1 || q.maxScore > 10000
        || !Number.isInteger(q.answerSeconds) || q.answerSeconds < 1 || q.answerSeconds > 1800) {
      throw new Error(`第 ${index + 1} 题分值或时限无效`);
    }
  }
}

async function save() {
  if (busy.value || !loaded.value) return;
  error.value = '';
  notice.value = '';
  const current = generation;
  try {
    validate();
    saving.value = true;
    const response = await savePreparedDraft(props.assessmentId, { expectedRevision: revision.value, questions: inputs(questions.value) });
    if (current !== generation) return;
    accept(response.data);
    notice.value = '草稿已保存';
  } catch (reason) {
    if (current === generation) failure(reason);
  } finally {
    saving.value = false;
  }
}

async function approve(questionId: string) {
  if (busy.value || dirty.value || !loaded.value) return;
  error.value = '';
  notice.value = '';
  const current = generation;
  saving.value = true;
  try {
    const response = await approvePreparedQuestions(props.assessmentId, { expectedRevision: revision.value, questionIds: [questionId] });
    if (current !== generation) return;
    accept(response.data);
    notice.value = '题目已审核';
  } catch (reason) {
    if (current === generation) failure(reason);
  } finally {
    saving.value = false;
  }
}

watch(() => props.assessmentId, () => {
  adopting.value = false;
  publishing.value = false;
  audioWriting.value = false;
  questions.value = [];
  baseline.value = [];
  void load();
}, { immediate: true });
</script>

<style scoped>
.paper-editor { max-width: 1000px; margin: 0 auto; padding: 24px; color: #17212f; }
.paper-heading, .question-card header, .paper-toolbar, .question-card footer { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.paper-heading { justify-content: space-between; margin-bottom: 20px; }
h1 { font-size: 24px; margin: 0 0 8px; } h2 { font-size: 18px; margin: 0; }
.paper-heading p, footer span { color: #586575; }
.paper-error { background: #fff1f0; border: 1px solid #ffa39e; border-radius: 8px; padding: 12px; }
.paper-toolbar { margin: 20px 0; }
.paper-empty { border: 1px dashed #abb9c9; border-radius: 10px; padding: 32px; text-align: center; }
.question-card { background: white; border: 1px solid #dce3eb; border-radius: 12px; padding: 20px; margin: 16px 0; }
.question-card header { margin-bottom: 16px; } label { display: block; margin: 10px 0 6px; }
textarea, input { width: 100%; border: 1px solid #abb9c9; border-radius: 6px; padding: 10px; font: inherit; box-sizing: border-box; }
.question-rules { display: flex; gap: 20px; flex-wrap: wrap; } .question-rules label { flex: 1; min-width: 160px; }
button { border: 1px solid #99adbf; border-radius: 6px; background: white; color: #174a77; padding: 8px 14px; cursor: pointer; }
button:focus-visible, textarea:focus-visible, input:focus-visible { outline: 3px solid #82b5f2; outline-offset: 2px; }
button:disabled { opacity: .5; cursor: not-allowed; } footer { margin-top: 16px; }
@media (max-width: 600px) { .paper-editor { padding: 12px; } .question-card { padding: 14px; } }
</style>
