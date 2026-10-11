<template>
  <section class="publication" aria-label="播报与发布检查">
    <h2>播报与发布检查</h2>
    <p>保存草稿不等于发布。所有题目审核且真实播报可靠保存后，才能发布固定试卷。</p>
    <p class="boundary">请在“预制题目播报”中准备并试听；未启用或未可靠保存的音频不会被视为就绪。</p>
    <p v-if="loading" aria-live="polite">正在检查已保存的试卷……</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" aria-live="polite">{{ notice }}</p>
    <p v-if="hasUnsavedChanges">请先保存草稿。以下检查针对服务端已保存内容。</p>
    <div v-if="status">
      <p>考核设置：{{ status.rules.questionCount ?? '未设置' }} 题，总分 {{ status.rules.totalScore ?? '未设置' }}，通过分数 {{ status.rules.passScore ?? '未设置' }}。</p>
      <p v-if="status.activeVersion">当前已发布版本 {{ status.activeVersion.versionNo }}；保存或编辑草稿不会改写此版本。</p>
      <p v-else>尚未发布试卷。</p>
      <p v-if="status.frozen">已有学生开始，不能切换试卷；修订须复制为新考核。</p>
      <ul v-if="status.issues.length" aria-label="发布待处理事项">
        <li v-for="(issue, i) in status.issues" :key="`${issue.code}-${i}`">
          {{ issue.ordinal ? `第 ${issue.ordinal} 题：` : '' }}{{ issue.message }}
        </li>
      </ul>
      <p v-else>已保存试卷通过发布检查。</p>
    </div>
    <div class="actions">
      <button type="button" :disabled="working || parentBusy" @click="refresh">刷新发布检查</button>
      <button type="button" :disabled="!canPublish" @click="publish">发布固定试卷</button>
      <button v-if="pending" type="button" :disabled="working || parentBusy || hasUnsavedChanges" @click="publish">重试原发布</button>
    </div>
    <div v-if="status?.versions.length" class="history">
      <h3>发布历史</h3>
      <button v-for="version in status.versions" :key="version.id" type="button" :disabled="working || parentBusy"
        @click="readVersion(version.id)">查看发布版本 {{ version.versionNo }}</button>
    </div>
    <article v-if="selected" aria-label="已发布试卷快照">
      <h3>发布版本 {{ selected.version.versionNo }} · 固定快照</h3>
      <p>总分 {{ selected.snapshot.rules.totalScore }} · 通过分数 {{ selected.snapshot.rules.passScore }}</p>
      <div v-for="q in selected.snapshot.questions" :key="q.questionId" class="snapshot-question">
        <h4>第 {{ q.ordinal }} 题 · {{ q.maxScore }} 分 · {{ q.answerSeconds }} 秒</h4>
        <p class="text">{{ q.body }}</p>
        <p class="text">教师评分要求：{{ q.rubric }}</p>
        <p>播报资产已绑定 · {{ q.audio.durationMs / 1000 }} 秒</p>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { getPublicationStatus, getPublication, publishPreparedPaper,
  type PublicationCommand, type PublicationStatus, type PublicationView } from '@/api/preparedPublication';

const props = defineProps<{ assessmentId: number; revision: number; hasUnsavedChanges: boolean; parentBusy: boolean }>();
const emit = defineEmits<{ busy: [boolean] }>();
const status = ref<PublicationStatus | null>(null);
const selected = ref<PublicationView | null>(null);
const pending = ref<PublicationCommand | null>(null);
const loading = ref(false);
const writing = ref(false);
const error = ref('');
const notice = ref('');
let epoch = 0;
const working = computed(() => loading.value || writing.value);
const canPublish = computed(() => !!status.value?.publishable && status.value.draftRevision === props.revision
  && /^[a-f0-9]{64}$/.test(status.value.preparedContentSha256 || '')
  && !props.hasUnsavedChanges && !props.parentBusy && !working.value && !pending.value);

function fail(reason: unknown) { error.value = reason instanceof Error ? reason.message : '操作失败，请刷新检查；草稿与已发布版本未被覆盖'; }
function validateView(view: PublicationView) {
  if (view?.snapshot?.assessmentId !== props.assessmentId || view.snapshot.schemaVersion !== 1 || !view.version?.id
      || !Array.isArray(view.snapshot.questions) || view.snapshot.questions.some(q => !q.audio)) {
    throw new Error('发布快照无效，未展示其他考核的内容');
  }
}
async function refresh() {
  const current = epoch;
  loading.value = true; error.value = '';
  try {
    const response = await getPublicationStatus(props.assessmentId);
    if (current !== epoch) return;
    if (response.data?.assessmentId !== props.assessmentId || !Number.isSafeInteger(response.data.draftRevision)
        || !Array.isArray(response.data.issues) || !Array.isArray(response.data.versions)) {
      throw new Error('发布检查无效，请重新读取');
    }
    status.value = response.data;
  } catch (reason) { if (current === epoch) { status.value = null; fail(reason); } }
  finally { if (current === epoch) loading.value = false; }
}
async function publish() {
  if (working.value || props.parentBusy || props.hasUnsavedChanges || (!pending.value && !canPublish.value)) return;
  if (!pending.value && !window.confirm('发布将固定全部题目、评分规则和播报。学生开考后不能切换试卷，是否继续？')) return;
  pending.value ??= { expectedRevision: props.revision, idempotencyKey: crypto.randomUUID(), expectedContentSha256: status.value!.preparedContentSha256! };
  const current = epoch;
  writing.value = true; emit('busy', true); error.value = ''; notice.value = '';
  try {
    const response = await publishPreparedPaper(props.assessmentId, { ...pending.value });
    if (current !== epoch) return;
    validateView(response.data);
    selected.value = response.data; pending.value = null;
    notice.value = `发布版本 ${response.data.version.versionNo} 已保存，后续草稿编辑不改变此快照。`;
    await refresh();
  } catch (reason) { if (current === epoch) fail(reason); }
  finally { if (current === epoch) { writing.value = false; emit('busy', false); } }
}
async function readVersion(id: string) {
  const current = epoch;
  loading.value = true; error.value = ''; selected.value = null;
  try {
    const response = await getPublication(props.assessmentId, id);
    if (current !== epoch) return;
    validateView(response.data); selected.value = response.data;
  } catch (reason) { if (current === epoch) fail(reason); }
  finally { if (current === epoch) loading.value = false; }
}
watch(() => props.assessmentId, () => {
  epoch++; status.value = null; selected.value = null; pending.value = null;
  writing.value = false; notice.value = ''; emit('busy', false);
  void refresh();
}, { immediate: true });
watch(() => props.revision, () => { if (!writing.value) { epoch++; void refresh(); } });
onBeforeUnmount(() => { epoch++; emit('busy', false); });
</script>

<style scoped>
.publication { border: 1px solid #c8d5e3; border-radius: 12px; padding: 20px; margin: 20px 0; background: #f4f8fc; }
h2 { margin-top: 0; font-size: 20px; } .boundary { color: #586575; font-size: 14px; }
[role="alert"] { color: #9d2323; } .actions, .history { display: flex; flex-wrap: wrap; gap: 10px; }
.history h3 { flex-basis: 100%; } button { border: 1px solid #99adbf; border-radius: 6px; background: white; color: #174a77; padding: 8px 14px; cursor: pointer; }
button:disabled { opacity: .5; cursor: not-allowed; } button:focus-visible { outline: 3px solid #82b5f2; outline-offset: 2px; }
.snapshot-question { border-top: 1px solid #dce3eb; margin-top: 14px; } .text { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
