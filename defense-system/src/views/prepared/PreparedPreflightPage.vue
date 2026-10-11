<template>
  <main class="preflight">
    <h1>面试预检</h1>
    <p class="intro">设备预检不是正式答题，不会创建考试记录或消耗作答时间。下方正式作答须另行获得服务端授权。</p>
    <p v-if="loading" role="status">正在核验考核与身份，最多等待 12 秒……</p>
    <p v-if="identityError" role="alert">{{ identityError }}</p>
    <p v-if="identityPending" role="alert">暂时无法核验服务端身份，原页面与本机内容保留；当前题仅在原时限内继续，不能开始新题或申请新设备。恢复网络后请重新核验，不重置时间、不清空重答。</p>
    <button v-if="context && identityPending" :disabled="identityChecking" @click="verifyOwner">重新核验服务端身份</button>
    <button v-if="!context && !loading" @click="loadContext">重新核验身份</button>
    <template v-if="context">
      <section aria-labelledby="assessment-heading">
        <h2 id="assessment-heading">{{ context.eligibility.theme || '获分配考核' }}</h2>
        <p>{{ context.eligibility.message }}</p>
        <p>开考条件以服务端核验为准。设备检查通过不等于已授权开考；预检不显示未授权题目。</p>
      </section>
      <section aria-labelledby="consent-heading">
        <h2 id="consent-heading">录音与恢复说明</h2>
        <p>正式回答用于转写及评分，本人、管理教师和管理员在各自权限内访问。云端录音保留 180 天，转写、分数及关联审计保留 1 年，均从首次结果处理完成起算；待处理及争议数据受保护。</p>
        <p>正式答题不允许主动删除重答。断网仅能在原时限内继续当前题，恢复网络并获得授权后才能进入下一题；作答结束后自动补传 24 小时，超期联系教师核验。刷新、退出和重试不重置时限。</p>
        <p>未确认的正式录音保留本机副本。清除浏览器数据、换设备、断电或未采集声音可能无法恢复，本系统不承诺这些情况下零损失。</p>
        <p>下面的 6 秒试音只在本机临时保存与检查，不上传、不评分；试音检查后清理其专用临时副本，试听仅留在当前页面。它与正式回答分开，不会删除考试录音。</p>
        <label class="check"><input data-testid="recording-consent" type="checkbox" v-model="consent"> 我已阅读并同意进行本机设备检查</label>
      </section>
      <p v-if="!supported" role="alert">请使用 Windows／macOS 桌面 Chrome 或 Edge，并通过 HTTPS（本机测试可用回环地址）访问。需要麦克风、录音、音频解码、浏览器本地存储和本地录音独占保护能力；移动端、Safari、隐私模式不在首版保证范围内。</p>
      <section aria-labelledby="microphone-heading">
        <h2 id="microphone-heading">麦克风与本机保存</h2>
        <p>点击试音后，请对着麦克风说一句话；浏览器会请求麦克风权限。取消或超时后，稍后获批的设备也会关闭。</p>
        <button :disabled="!consent || !supported || trialBusy || formalActive || identityPending" @click="startTrial">开始麦克风试音</button>
        <button v-if="trialBusy" @click="cancelTrial">取消试音</button>
        <p v-if="trialBusy" role="status">{{ trialStatus }}</p>
        <p v-if="trialError" role="alert">{{ trialError }}</p>
        <template v-if="previewUrl">
          <p v-if="trialHasSound">本机写入、校验及完整音频解码通过，检测到声音。请试听确认能清楚听到自己的人声；声音检测不等于语音识别。</p>
          <p v-else role="alert">未检测到足够声音，请检查输入设备或音量后重新试音；不能据此认为麦克风检查通过。</p>
          <audio :src="previewUrl" controls preload="metadata" aria-label="试音回放" />
          <label v-if="trialHasSound" class="check"><input data-testid="heard-confirmation" type="checkbox" v-model="heard"> 我已试听，能清楚听到自己的人声</label>
        </template>
        <p v-if="microphonePassed" role="status">麦克风与本机保存检查通过（仅本次预检）。</p>
      </section>
      <section aria-labelledby="camera-heading">
        <h2 id="camera-heading">摄像头预览（可选，仅本机）</h2>
        <p>不上传、不分析、不录制视频。拒绝摄像头权限不影响麦克风检查，也不是正式开考的硬条件。</p>
        <button :disabled="!consent || !supported || cameraPhase !== 'OFF' || identityPending" @click="startCamera">开启摄像头预览</button>
        <button v-if="cameraPhase !== 'OFF'" @click="closeCamera">关闭摄像头预览</button>
        <p v-if="cameraPhase === 'CONNECTING'" role="status">正在等待摄像头授权或画面，授权最多等待 15 秒……</p>
        <p v-if="cameraPhase === 'PREVIEW'" role="status">摄像头预览中，仅显示本机画面。</p>
        <p v-if="cameraError" role="alert">{{ cameraError }}</p>
        <video v-if="cameraPhase !== 'OFF'" ref="cameraVideo" autoplay muted playsinline aria-label="本机摄像头预览" />
      </section>
      <p v-if="context.workflowEnabled !== true" class="notice">正式开考尚未开放：可靠上传、提交和故障处置仍在建设。此页面不会启动旧版实时答辩，也不表示完整面试已通过测试。</p>
      <PreparedAttemptPanel ref="formalPanel" :context="context" :consent="consent && supported" :microphone-passed="microphonePassed" :identity-pending="identityPending" @capture-active="formalActive = $event" />
    </template>
    <button @click="leave">返回考核列表</button>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { isSessionMarkerStorageEvent } from '@/utils/auth';
import { getPreparedPreflightContext, type PreparedPreflightContext } from '@/api/preparedStudent';
import {ApiRequestError,ApiTransportError} from '@/utils/request';
import { runMicrophoneTrial, type TrialProgress } from '@/prepared/microphoneTrial';
import { requestPreparedDevice } from '@/prepared/deviceAccess';
import PreparedAttemptPanel from './PreparedAttemptPanel.vue';

const route = useRoute(), router = useRouter(), auth = useAuthStore();
const context = ref<PreparedPreflightContext>(), loading = ref(false), identityError = ref(''), consent = ref(false);
const identityPending=ref(false),identityChecking=ref(false);
const trialBusy = ref(false), trialStatus = ref(''), trialError = ref(''), previewUrl = ref(''), trialHasSound = ref(false), heard = ref(false);
const cameraPhase = ref<'OFF' | 'CONNECTING' | 'PREVIEW'>('OFF'), cameraError = ref(''), cameraVideo = ref<HTMLVideoElement>();
const formalActive = ref(false);
const formalPanel = ref<InstanceType<typeof PreparedAttemptPanel>>();
let generation = 0, alive = true, contextController: AbortController | undefined, trialController: AbortController | undefined,
  cameraController: AbortController | undefined, cameraStream: MediaStream | undefined, cameraListeners: (() => void) | undefined;
const phases = new Set(['PAPER_NOT_PUBLISHED', 'APPOINTMENT_REQUIRED', 'BEFORE_APPOINTMENT', 'APPOINTMENT_WINDOW_EXPIRED', 'APPOINTMENT_STATE_CONFLICT', 'HISTORY_REQUIRES_REVIEW', 'READY_FOR_PREFLIGHT']);
const ua = navigator.userAgent;
const supported = isSecureContext === true && /Windows NT|Macintosh/.test(ua) && /Chrome\/|Edg\//.test(ua) && !/Mobile|Android|OPR\//.test(ua) &&
  typeof navigator.mediaDevices?.getUserMedia === 'function' && typeof MediaRecorder !== 'undefined' &&
  typeof AudioContext !== 'undefined' && typeof indexedDB?.open === 'function' && typeof crypto.subtle?.digest === 'function' &&
  typeof navigator.locks?.request === 'function';
const microphonePassed = computed(() => !trialBusy.value && trialHasSound.value && heard.value && !!previewUrl.value);
const assessmentId = () => {
  const value = String(route.params.assessmentId ?? '');
  return /^[1-9][0-9]{0,9}$/.test(value) && Number(value) <= 2147483647 ? Number(value) : 0;
};
function clearPreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = ''; trialHasSound.value = false; heard.value = false;
}
function closeCamera() {
  cameraController?.abort(); cameraController = undefined;
  cameraListeners?.(); cameraListeners = undefined;
  for (const track of cameraStream?.getTracks() ?? []) { try { track.stop(); } catch { /* bounded native failure */ } }
  cameraStream = undefined;
  if (cameraVideo.value) { cameraVideo.value.pause(); cameraVideo.value.srcObject = null; }
  cameraPhase.value = 'OFF';
}
function cancelTrial() { trialController?.abort(); trialController = undefined; trialBusy.value = false; trialStatus.value = ''; }
function invalidate(message = '', reason: 'SESSION_CHANGED' | 'PAGE_REFRESH' = 'SESSION_CHANGED') {
  // Stop the owned child with the actual cause before clearing context unmounts it.
  // Native storage callbacks can flush Vue updates before another listener runs.
  formalPanel.value?.stopForContextChange(reason);
  generation++; contextController?.abort(); contextController = undefined; cancelTrial(); closeCamera(); clearPreview();
  consent.value = false; context.value = undefined; loading.value = false; identityError.value = message; trialError.value = ''; cameraError.value = '';
  identityPending.value=false;identityChecking.value=false;
}
function validContext(value: PreparedPreflightContext): boolean {
  return !!value && Number.isSafeInteger(value.studentId) && value.studentId > 0 && String(value.studentId) === String(auth.id) && auth.role === 'STUDENT' &&
    typeof value.deploymentId === 'string' && /^prepared-[a-z0-9][a-z0-9-]{0,70}$/.test(value.deploymentId) &&
    !!value.eligibility && value.eligibility.assessmentId === assessmentId() && phases.has(value.eligibility.phase);
}
class ContextReadTimeout extends Error {constructor(){super('CONTEXT_TIMEOUT');}}
function transientOwnerFailure(error:unknown):boolean {
  if(error instanceof ContextReadTimeout)return true;
  if(error instanceof ApiTransportError)return error.kind==='NETWORK'||error.kind==='TIMEOUT';
  return error instanceof ApiRequestError && [502,503,504].includes(error.status??0) && ![401,403].includes(error.code);
}
async function verifiedRead(controller: AbortController): Promise<PreparedPreflightContext> {
  let timer: ReturnType<typeof setTimeout>;
  try {
    const response = await Promise.race([
      getPreparedPreflightContext(assessmentId(), controller.signal),
      new Promise<never>((_, reject) => { timer = setTimeout(() => { const timeout=new ContextReadTimeout();controller.abort(timeout);reject(timeout); }, 12000); }),
    ]);
    if (controller.signal.aborted || !validContext(response.data)) throw new Error('CONTEXT_INVALID');
    return response.data;
  } catch(error) {
    // Native cancellation may reject first in the same abort event. Preserve
    // this caller's bounded timeout, not a superseding owner's cancellation.
    if(controller.signal.reason instanceof ContextReadTimeout)throw controller.signal.reason;
    throw error;
  } finally { clearTimeout(timer!); }
}
async function loadContext() {
  invalidate(); const version = generation;
  if (!assessmentId()) { identityError.value = '考核标识无效，请返回考核列表'; return; }
  if (auth.role !== 'STUDENT') { identityError.value = '仅学生本人可进行面试预检'; return; }
  const controller = new AbortController(); contextController = controller; loading.value = true;
  try {
    const value = await verifiedRead(controller);
    if (alive && version === generation && !controller.signal.aborted) context.value = value;
  } catch (error) {
    if (alive && version === generation) identityError.value = (error as Error).message === 'CONTEXT_TIMEOUT'
      ? '身份核验超时，请检查网络后重新核验身份' : '身份或考核核验失败，请确认同账号登录、考核分配及网络后重试';
  } finally { if (version === generation) { loading.value = false; contextController = undefined; } }
}
/** Cookies may change in another tab. Validate the current server identity before device access and before displaying a trial. */
async function verifyOwner(): Promise<boolean> {
  if (!context.value) return false;
  const expected = context.value, version = generation, controller = new AbortController();
  contextController?.abort(); contextController = controller;identityChecking.value=true;
  try {
    const fresh = await verifiedRead(controller);
    if (!alive || version !== generation || contextController!==controller) return false;
    if (fresh.studentId !== expected.studentId || fresh.deploymentId !== expected.deploymentId) throw new Error('CONTEXT_INVALID');
    context.value = fresh;identityPending.value=false; return true;
  } catch (error) {
    if (alive && version === generation && contextController===controller) {
      // Retaining an already-verified owner is not a new authorization. Original
      // local capture keeps its deadline; every server operation still rechecks
      // authority. Definitive rejections and malformed identities fail closed.
      if(validContext(expected)&&transientOwnerFailure(error))identityPending.value=true;
      else invalidate('身份或考核核验失败，请重新核验身份后继续');
    }
    return false;
  } finally { if (contextController === controller) {contextController = undefined;identityChecking.value=false;} }
}
const trialMessages: Record<string, string> = {
  PERMISSION_DENIED: '麦克风权限被拒绝，请在浏览器中允许麦克风后重试', NOT_FOUND: '未找到麦克风，请连接设备后重试',
  BUSY: '麦克风暂不可读，请检查其他应用是否占用', TIMED_OUT: '等待麦克风权限超时，请检查浏览器授权后重试',
  STORAGE_FAILED: '本机保存检查失败，请检查浏览器存储后重试', DECODE_FAILED: '试音无法完整播放，请检查设备和浏览器后重试',
  CANCELLED: '已取消试音，可在开考前重新检查', UNSUPPORTED: '当前浏览器不支持设备检查，请更换受支持的桌面浏览器',
};
async function startTrial() {
  if (!context.value || !consent.value || !supported || trialBusy.value || formalActive.value || identityPending.value) return;
  clearPreview(); trialError.value = ''; trialBusy.value = true;
  const controller = new AbortController(), version = generation; trialController = controller;
  trialStatus.value = '正在重新核验身份……';
  try {
    if (!await verifyOwner() || controller.signal.aborted) return;
    const owner = context.value!;
    const value = await runMicrophoneTrial({origin: location.origin, studentId:owner.studentId, deploymentId:owner.deploymentId, assessmentId:assessmentId()}, controller.signal,
      (progress: TrialProgress) => {
        if (!alive || version !== generation || controller.signal.aborted) return;
        trialStatus.value = progress.phase === 'REQUESTING' ? '等待麦克风授权，最多 15 秒……' : progress.phase === 'CHECKING'
          ? '正在校验本机副本与完整音频……' : `正在试音，还剩 ${progress.secondsRemaining} 秒；已本机保存 ${progress.savedChunks} 片`;
      });
    if (!alive || version !== generation || controller.signal.aborted || !await verifyOwner()) return;
    if (!alive || version !== generation || controller.signal.aborted) return;
    previewUrl.value = URL.createObjectURL(value.blob); trialHasSound.value = value.signalDetected;
  } catch (error) {
    if (alive && version === generation) trialError.value = trialMessages[(error as {code?:string})?.code ?? ''] || '试音未完整采集或检查失败，请检查设备后重试';
  } finally { if (trialController === controller) { trialController = undefined; trialBusy.value = false; trialStatus.value = ''; } }
}
async function startCamera() {
  if (!context.value || !consent.value || !supported || cameraPhase.value !== 'OFF' || identityPending.value) return;
  cameraError.value = ''; cameraPhase.value = 'CONNECTING';
  const controller = new AbortController(), version = generation; cameraController = controller;
  try {
    if (!await verifyOwner() || controller.signal.aborted) {
      if(cameraController===controller)closeCamera();
      return;
    }
    const stream = await requestPreparedDevice('camera', controller.signal);
    if (!alive || version !== generation || controller.signal.aborted) { for (const t of stream.getTracks()) t.stop(); return; }
    cameraStream = stream;
    const unavailable = () => {
      if (cameraStream !== stream || controller.signal.aborted) return;
      cameraError.value = '摄像头预览中断，请检查设备；不影响麦克风检查'; closeCamera();
    };
    const tracks = stream.getTracks();
    for (const track of tracks) { track.addEventListener?.('ended', unavailable); track.addEventListener?.('mute', unavailable); }
    cameraListeners = () => { for (const track of tracks) { track.removeEventListener?.('ended', unavailable); track.removeEventListener?.('mute', unavailable); } };
    await nextTick();
    const video = cameraVideo.value;
    if (!video || controller.signal.aborted) throw new Error('CAMERA_CANCELLED');
    video.srcObject = stream;
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); clearInterval(interval); video.removeEventListener('loadeddata', check); controller.signal.removeEventListener('abort', aborted); };
      const check = () => { if (video.videoWidth > 0 && video.videoHeight > 0) { cleanup(); resolve(); } };
      const aborted = () => { cleanup(); reject(new Error('CAMERA_CANCELLED')); };
      const timer = setTimeout(() => { cleanup(); reject(new Error('CAMERA_TIMEOUT')); }, 5000);
      const interval = setInterval(check, 125);
      video.addEventListener('loadeddata', check); controller.signal.addEventListener('abort', aborted, {once:true});
      void video.play().then(check, () => { cleanup(); reject(new Error('CAMERA_UNAVAILABLE')); });
      if (controller.signal.aborted) aborted();
    });
    if (alive && version === generation && !controller.signal.aborted) cameraPhase.value = 'PREVIEW';
  } catch (error) {
    if (alive && version === generation && !controller.signal.aborted) {
      cameraError.value = (error as {code?:string})?.code === 'PERMISSION_DENIED' ? '摄像头权限被拒绝；不影响麦克风检查'
        : '摄像头预览不可用，请检查授权或设备；不影响麦克风检查';
      closeCamera();
    }
  }
}
function leave() { invalidate('', 'PAGE_REFRESH'); void router.push('/studentDashboard'); }
function pageExit(event?: Event) { invalidate('页面已离开或隐藏，请回来后重新核验身份', event?.type === 'storage' ? 'SESSION_CHANGED' : 'PAGE_REFRESH'); }
function storageChanged(event: StorageEvent) { if (isSessionMarkerStorageEvent(event)) pageExit(event); }
function visibility() { if (document.visibilityState === 'hidden') pageExit(); }
function focus() { if (context.value) void verifyOwner(); }
watch(() => [auth.id, auth.role], () => invalidate('身份已变化，请重新核验身份后检查设备'));
watch(() => route.params.assessmentId, () => { void loadContext(); });
watch(consent, value => { if (!value) { cancelTrial(); closeCamera(); clearPreview(); } });
onMounted(() => {
  void loadContext(); window.addEventListener('pagehide', pageExit); window.addEventListener('focus', focus);
  window.addEventListener('storage', storageChanged); document.addEventListener('visibilitychange', visibility);
});
onUnmounted(() => {
  alive = false; invalidate('', 'PAGE_REFRESH'); window.removeEventListener('pagehide', pageExit); window.removeEventListener('focus', focus);
  window.removeEventListener('storage', storageChanged); document.removeEventListener('visibilitychange', visibility);
});
</script>

<style scoped>
.preflight{max-width:860px;margin:0 auto;padding:28px 20px 60px;color:#24324a;line-height:1.65}.preflight h1{font-size:28px}.preflight h2{font-size:19px;margin-top:0}.preflight section{background:#fff;border:1px solid #dce4ed;border-radius:12px;padding:20px;margin:18px 0}.intro{color:#53637a}.preflight button{padding:9px 16px;border:1px solid #315cd2;background:#fff;color:#244bc0;border-radius:7px;cursor:pointer;margin:5px 10px 5px 0}.preflight button:disabled{opacity:.5;cursor:not-allowed}.check{display:flex;align-items:flex-start;gap:8px;padding:10px 0}.check input{margin-top:7px}.preflight [role=alert]{color:#a03a24}.preflight audio{display:block;max-width:100%;margin:12px 0}.preflight video{width:100%;max-width:560px;aspect-ratio:4/3;background:#172238;border-radius:8px}.notice{padding:14px;background:#fff7e9;border-radius:8px}
</style>
