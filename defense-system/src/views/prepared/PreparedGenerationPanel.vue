<template>
  <section class="generation-panel" aria-label="AI 辅助备题">
    <h2>AI 辅助备题</h2>
    <p>建议与当前草稿分开保存。采用后仍需教师编辑、审核，不会自动发布。</p>
    <p v-if="availability">{{ availability.reason }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" aria-live="polite">{{ notice }}</p>
    <label :for="`generation-requirements-${assessmentId}`">出题要求</label>
    <textarea :id="`generation-requirements-${assessmentId}`" v-model="requirements" rows="3" maxlength="8000" :disabled="sending || adopting" />
    <div class="generation-rules">
      <label>建议题数<input v-model.number="count" type="number" min="1" max="20" :disabled="sending || adopting" /></label>
      <label>每题分值<input v-model.number="maxScore" type="number" min="1" max="10000" :disabled="sending || adopting" /></label>
      <label>每题时限（秒）<input v-model.number="answerSeconds" type="number" min="1" max="1800" :disabled="sending || adopting" /></label>
    </div>
    <p v-if="hasUnsavedChanges">请先保存草稿，再生成或采用建议。当前编辑内容不会被覆盖。</p>
    <div class="generation-actions">
      <button :disabled="!availability?.enabled || sending || adopting || parentBusy || hasUnsavedChanges || !!pendingGenerate" @click="generate">生成建议</button>
      <button v-if="pendingGenerate && !sending" :disabled="adopting || parentBusy" @click="sendGeneration">重试原请求</button>
      <button :disabled="sending || adopting || reading" @click="refresh">刷新建议状态</button>
    </div>
    <p v-if="reading">正在读取建议状态，不影响手工备题……</p>
    <label v-if="jobs.length">历史生成任务
      <select v-model="selectedJob" :disabled="adopting || sending" @change="loadSelected">
        <option v-for="item in jobs" :key="item.id" :value="item.id">{{ label(item.state) }} · {{ item.createdAt }} · 草稿版本 {{ item.inputVersion }}</option>
      </select>
    </label>
    <p v-if="!jobs.length && availability">尚无生成任务。</p>
    <p v-if="view">{{ label(view.state) }}{{ view.errorCode ? ' · ' + view.errorCode : '' }}</p>
    <p v-if="view?.state === 'NEEDS_REVIEW'">生成未完成，原草稿仍保留。费用不确定时不会自动重发，请管理员核账。</p>
    <article v-for="item in view?.suggestions || []" :key="item.questionId" class="suggestion">
      <label>
        <input v-model="selected" data-testid="suggestion-choice" type="checkbox" :value="item.questionId"
          :disabled="adopting || !!pendingAdopt || view?.adoptedQuestionIds.includes(item.questionId)" />
        {{ view?.adoptedQuestionIds.includes(item.questionId) ? '已采用' : '选择建议' }}
      </label>
      <p class="suggestion-body">{{ item.body }}</p>
      <p>{{ item.maxScore }} 分 · {{ item.answerSeconds }} 秒</p>
      <p>评分要求：{{ item.rubric }}</p>
    </article>
    <button v-if="view?.suggestions.length" :disabled="!selected.length || hasUnsavedChanges || parentBusy || adopting || !!pendingAdopt" @click="adopt">采用所选建议</button>
    <button v-if="pendingAdopt && !adopting" :disabled="hasUnsavedChanges || parentBusy" @click="sendAdoption">重试原采用</button>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { listGenerations, getGeneration, requestGeneration, adoptGeneration,
  type GenerationListing, type GenerationSummary, type GenerationView, type GenerationCommand, type AdoptGenerationCommand, type GenerationState } from '@/api/preparedGeneration';
import type { PreparedDraft } from '@/api/preparedPaper';
const props = defineProps<{ assessmentId: number; revision: number; hasUnsavedChanges: boolean; parentBusy: boolean }>();
const emit = defineEmits<{ adopted: [draft: PreparedDraft]; busy: [value: boolean] }>();
const availability = ref<GenerationListing['availability']>();
const jobs = ref<GenerationSummary[]>([]), selectedJob = ref(''), view = ref<GenerationView>();
const selected = ref<string[]>([]), requirements = ref(''), count = ref(3), maxScore = ref(10), answerSeconds = ref(120);
const error = ref(''), notice = ref(''), sending = ref(false), adopting = ref(false), reading = ref(false);
const pendingGenerate = ref<GenerationCommand>();
const pendingAdopt = ref<{ job: string; command: AdoptGenerationCommand }>();
let epoch = 0, detailEpoch = 0, timer: ReturnType<typeof setTimeout> | undefined;
function stopTimer() { if (timer) clearTimeout(timer); timer = undefined; }
function failure(reason: unknown) { error.value = reason instanceof Error ? reason.message : '请求未确认，原草稿保留，请稍后重试'; }
function label(state: GenerationState) { return ({ QUEUED: '等待生成', RUNNING: '正在生成', RETRY_WAIT: '等待恢复', SUCCEEDED: '建议已生成', NEEDS_REVIEW: '需要处理' })[state]; }

async function loadSelected() {
  stopTimer(); const current = epoch, detail = ++detailEpoch, id = props.assessmentId, job = selectedJob.value;
  if (!job) { view.value = undefined; return; }
  try {
    const result = await getGeneration(id, job);
    if (current !== epoch || detail !== detailEpoch) return;
    if (result.data.id !== job) throw new Error('建议返回标识无效');
    view.value = result.data;
    selected.value = selected.value.filter(q => result.data.suggestions.some(item => item.questionId === q) && !result.data.adoptedQuestionIds.includes(q));
    if (['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(result.data.state)) timer = setTimeout(() => { void loadSelected(); }, 3000);
  } catch (reason) { if (current === epoch && detail === detailEpoch) failure(reason); }
}
async function refresh() {
  const current = epoch, id = props.assessmentId; reading.value = true; error.value = ''; stopTimer();
  try {
    const response = await listGenerations(id); if (current !== epoch) return;
    availability.value = response.data.availability; jobs.value = response.data.jobs;
    if (!jobs.value.some(j => j.id === selectedJob.value)) selectedJob.value = jobs.value[0]?.id || '';
    await loadSelected();
  } catch (reason) { if (current === epoch) failure(reason); }
  finally { if (current === epoch) reading.value = false; }
}
async function generate() {
  if (props.hasUnsavedChanges || props.parentBusy || !availability.value?.enabled || sending.value || pendingGenerate.value) return;
  if (!requirements.value.trim() || requirements.value.length > 8000 || !Number.isInteger(count.value) || count.value < 1 || count.value > 20
    || !Number.isInteger(maxScore.value) || maxScore.value < 1 || maxScore.value > 10000 || !Number.isInteger(answerSeconds.value) || answerSeconds.value < 1 || answerSeconds.value > 1800) {
    error.value = '请填写出题要求和有效题数、分值、时限'; return;
  }
  pendingGenerate.value = { expectedRevision: props.revision, idempotencyKey: crypto.randomUUID(), requirements: requirements.value,
    count: count.value, maxScore: maxScore.value, answerSeconds: answerSeconds.value };
  await sendGeneration();
}
async function sendGeneration() {
  if (!pendingGenerate.value || sending.value) return;
  const current = epoch, id = props.assessmentId, command = { ...pendingGenerate.value }; sending.value = true; error.value = '';
  try {
    const response = await requestGeneration(id, command); if (current !== epoch) return;
    pendingGenerate.value = undefined; selectedJob.value = response.data.id; notice.value = '出题任务已保存，您可继续手工编辑；建议不会自动覆盖草稿';
    await refresh();
  } catch (reason) { if (current === epoch) failure(reason); }
  finally { if (current === epoch) sending.value = false; }
}
async function adopt() {
  if (!selectedJob.value || !selected.value.length || props.hasUnsavedChanges || props.parentBusy || adopting.value || pendingAdopt.value) return;
  pendingAdopt.value = { job: selectedJob.value, command: { expectedRevision: props.revision, idempotencyKey: crypto.randomUUID(), questionIds: [...selected.value] } };
  await sendAdoption();
}
async function sendAdoption() {
  if (!pendingAdopt.value || props.hasUnsavedChanges || props.parentBusy || adopting.value) return;
  const current = epoch, id = props.assessmentId, pending = pendingAdopt.value; adopting.value = true; emit('busy', true); error.value = '';
  try {
    const response = await adoptGeneration(id, pending.job, { ...pending.command, questionIds: [...pending.command.questionIds] });
    if (current !== epoch) return;
    if (response.data.draft.assessmentId !== id) throw new Error('采用回执考核标识无效');
    emit('adopted', response.data.draft); pendingAdopt.value = undefined; selected.value = [];
    notice.value = '建议已加入草稿，请逐题编辑并审核'; await refresh();
  } catch (reason) { if (current === epoch) failure(reason); }
  finally { if (current === epoch) { adopting.value = false; emit('busy', false); } }
}
watch(() => props.revision, () => {
  // A confirmed newer draft makes stale rejected commands obsolete; job/adoption uniqueness remains the server boundary.
  if (!adopting.value && !sending.value) { pendingGenerate.value = undefined; pendingAdopt.value = undefined; }
});
watch(() => props.assessmentId, () => {
  if (adopting.value) emit('busy', false);
  ++epoch; ++detailEpoch; stopTimer(); jobs.value = []; selectedJob.value = ''; view.value = undefined; selected.value = [];
  pendingGenerate.value = undefined; pendingAdopt.value = undefined; availability.value = undefined; error.value = ''; notice.value = '';
  sending.value = false; adopting.value = false; void refresh();
}, { immediate: true });
onBeforeUnmount(() => { ++epoch; ++detailEpoch; stopTimer(); });
</script>

<style scoped>
.generation-panel { padding: 20px; margin: 20px 0; border: 1px solid #c4d4e4; border-radius: 12px; background: #f6faff; }
h2 { margin-top: 0; font-size: 20px; } label { display: block; margin: 10px 0; }
textarea, select, input[type="number"] { width: 100%; box-sizing: border-box; padding: 8px; border: 1px solid #99adbf; border-radius: 6px; font: inherit; }
.generation-rules, .generation-actions { display: flex; gap: 12px; flex-wrap: wrap; }.generation-rules label { flex: 1; min-width: 130px; }
button { margin: 4px 8px 4px 0; padding: 8px 12px; border: 1px solid #99adbf; border-radius: 6px; background: white; color: #174a77; cursor: pointer; }
button:disabled { opacity: .5; cursor: not-allowed; }.suggestion { border-top: 1px solid #c4d4e4; margin-top: 16px; }.suggestion-body { white-space: pre-wrap; overflow-wrap: anywhere; }
[role="alert"] { color: #a32222; }button:focus-visible, input:focus-visible, textarea:focus-visible, select:focus-visible { outline: 3px solid #82b5f2; }
</style>
