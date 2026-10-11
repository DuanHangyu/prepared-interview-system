<template>
  <section class="attempt" aria-labelledby="attempt-heading">
    <h2 id="attempt-heading">正式作答与原回答恢复</h2>
    <p v-if="!enabled" class="notice">正式开考尚未开放：完整上传、提交和故障处置仍在建设。原作答可只读核验，不启动旧版实时答辩。</p>
    <p>仅限制每道题，不设整场上限。断网仅当前题在原时限内继续；刷新、重新登录不延长时限。禁止主动删除或重答。</p>
    <p v-if="busy" role="status">{{ working }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!online" role="alert">网络不可用：断网仅当前题可继续，下一题必须恢复网络后获得授权。</p>
    <p v-if="uploadProgress" data-testid="upload-status" role="status">{{ uploadMessage }}；本次会话已核对 {{ uploadProgress.confirmedChunks }} 片 / {{ uploadProgress.confirmedBytes }} 字节。字节回执不是整题保存或最终提交，本机副本保留。</p>
    <p v-if="attempt" data-testid="media-status" role="status">云端整题核验：{{ mediaMessage }}；<template v-if="mediaProgress">当前尝试已可靠保存 {{ mediaProgress.savedAnswers }} / {{ total }} 题。</template><template v-else>当前尝试保存数量尚未核验。</template>整题保存不是最终提交，本机副本保留。</p>
    <template v-if="attempt">
      <p>原尝试第 {{ attempt.attemptNo }} 次 · 服务端状态：{{ attempt.state }}。状态不等于音频上传或最终提交回执。</p>
      <p v-if="attempt.supplementalUploadDeadline && !finalState?.submitted">原自动补传截止：{{ attempt.supplementalUploadDeadline }}；未完成收件超期需要教师核验，不开放重新录音。</p>
      <div v-for="window in uploadWindows" :key="window.answerId" data-testid="upload-window" role="status">
        <p>第 {{ window.ordinal }} 题补传：{{ windowMessage(window.phase) }}。</p>
        <p v-if="window.uploadDeadline">{{ window.approvalRevision > 0 ? '教师批准补传截止' : '原自动补传截止' }}（UTC）：{{ window.uploadDeadline }}。</p>
        <p>最近服务端核验（UTC）：{{ window.serverNow }}。批准仅用于上传原回答，不增加录音时间；本机原副本保留，不表示整题保存或最终提交。</p>
      </div>
    </template>
    <template v-if="question">
      <h3>第 {{ question.ordinal }} 题 / {{ total }} 题</h3>
      <p class="question" data-testid="authorized-question">{{ question.body }}</p>
      <p>本题原截止：{{ question.answerDeadline }}；剩余 {{ remainingSeconds }} 秒。倒计时仅本机保守控制，不证明离线录制时间。</p>
      <PreparedQuestionAudio v-if="intent" :student-id="context.studentId" :assessment-id="question.assessmentId" :attempt-id="question.attemptId"
        :answer-id="question.answerId" :question-id="question.questionId" :paper-version-id="question.paperVersionId"
        :capture-client-id="intent.captureClientId" :deployment-id="context.deploymentId" />
      <p v-if="snapshot" data-testid="capture-status">{{ captureMessage }} · 原回答累计已事务保存 {{ capturedChunks }} 片 / {{ capturedBytes }} 字节；当前分段待写 {{ snapshot.queuedChunks }} 片。</p>
      <p v-else-if="segments.length">本机原录音：{{ segments.length }} 段 / {{ localBytes }} 字节。原内容保留，不提供删除重答。</p>
      <p v-if="hasOpen" role="alert">原录音尚无可靠结束依据，已保存前缀保留。不能自动当作完整回答，请联系教师核验。</p>
      <p v-if="question.state === 'RECORDING' && !question.mayRecord" role="alert">当前客户端或原时限不允许新增录音；请保留原内容并核验，不会重置时间或自动计零分。</p>
      <p v-if="receipt">本题原结束信息已登记（{{ receipt.declarationState }}），不是云端录音已保存；还需真实上传与完整性确认。</p>
    </template>
    <button v-if="!attempt" :disabled="!canBegin" @click="begin()">开考并开始第一题</button>
    <button v-else-if="!question && attempt.state === 'IN_PROGRESS'" :disabled="!canBegin" @click="begin()">开始第一题</button>
    <button v-else-if="question?.state === 'RECORDING' && !hasLocal" :disabled="!canResume" @click="begin()">恢复当前题采集</button>
    <button v-if="faultCandidate" :disabled="!canResume" @click="begin(true)">故障恢复：保留原内容继续录音</button>
    <p v-if="faultCandidate" role="status">只在原题剩余时限内追加录音，不重说已保存内容；采集间断仍需教师核验。</p>
    <button v-if="isRecording" :disabled="busy" @click="finish">结束本题并保存</button>
    <button v-if="snapshot?.phase === 'LOCAL_SAVE_FAILED'" :disabled="busy" @click="retryLocal">重试保存原录音</button>
    <button v-if="canDeclare" :disabled="busy || !enabled" @click="declare">登记本题原结束信息</button>
    <button v-if="canNext" :disabled="!canBegin" @click="begin()">授权并开始下一题</button>
    <button :disabled="busy || isRecording || snapshot?.phase === 'STOPPING'" @click="restore">重新核验原作答</button>
    <p v-if="allEnded && !finalState?.submitted" class="notice">全部题目的结束信息已登记；仍需核对完整收件并取得唯一提交回执。请保留本机副本，结束信息不是交卷成功。</p>
    <div v-if="allEnded || finalState?.submitted" data-testid="submission-status" role="status">
      <template v-if="finalState?.submitted && finalState.receipt">
        <p>整场已可靠提交，共 {{ finalState.receipt.answerCount }} 题。提交回执：{{ finalState.receipt.id }}。</p>
        <p>服务器确认时间：{{ finalState.receipt.submittedAt }}。可以离开，转写和评分在后台处理；模型失败不撤销交卷，本机原副本暂保留。</p>
      </template>
      <template v-else>
        <p>{{ finalMessage }}</p>
        <p v-for="p in finalState?.pending ?? []" :key="p.ordinal">第 {{ p.ordinal }} 题：{{ pendingMessage(p.phase) }}</p>
        <p v-if="finalError" role="alert">{{ finalError }}</p>
        <button :disabled="!canSubmit" @click="submitFinal">确认提交整场面试</button>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue';
import {useAuthStore} from '@/stores/auth';
import {getPreparedPreflightContext,type PreparedPreflightContext} from '@/api/preparedStudent';
import {startPreparedAttempt,getCurrentPreparedAttempt,startPreparedQuestion,getCurrentPreparedQuestion,endPreparedAnswer,
  type PreparedAttemptSummary,type PreparedQuestionGrant,type PreparedAnswerEndReceipt} from '@/api/preparedAttempt';
import {PreparedJourneyJournal,type JourneyQuestion} from '@/prepared/journeyJournal';
import {PreparedAudioJournal,type AnswerScope,type Segment,type Interruption} from '@/prepared/localAudioJournal';
import {PreparedAnswerCapture,type CaptureSnapshot} from '@/prepared/answerCapture';
import {startAuthorizedAnswerCapture} from '@/prepared/authorizedCapture';
import {PreparedQuestionDeadline,questionRequestTime} from '@/prepared/questionDeadline';
import {requestPreparedDevice} from '@/prepared/deviceAccess';
import {encodeAnswerEndManifest} from '@/prepared/answerEndManifest';
import {PreparedUploadJournal} from '@/prepared/uploadJournal';
import {PreparedBackgroundUpload,type UploadProgress} from '@/prepared/backgroundUpload';
import {PreparedUploadTransport} from '@/api/preparedUpload';
import {PreparedBackgroundMedia,type MediaProgress} from '@/prepared/backgroundMedia';
import {verifySubmission,type SubmissionSummary} from '@/prepared/submission';
import {isSessionMarkerStorageEvent} from '@/utils/auth';
import PreparedQuestionAudio from './PreparedQuestionAudio.vue';

const props=defineProps<{context:PreparedPreflightContext;consent:boolean;microphonePassed:boolean;identityPending?:boolean}>(),auth=useAuthStore();
const emit=defineEmits<{(e:'capture-active',active:boolean):void}>();
const attempt=ref<PreparedAttemptSummary>(),question=ref<PreparedQuestionGrant>(),receipt=ref<PreparedAnswerEndReceipt>(),segments=ref<Segment[]>([]),snapshot=ref<CaptureSnapshot>();
const busy=ref(false),working=ref(''),error=ref(''),notice=ref(''),online=ref(navigator.onLine!==false),enabled=ref(props.context.workflowEnabled===true),remainingSeconds=ref(0);
const localEnded=ref(true);
const uploadProgress=ref<UploadProgress>();
const uploadWindows=computed(()=>uploadProgress.value?.windows?.filter(w=>w.attemptId===attempt.value?.id&&w.paperVersionId===attempt.value?.paperVersionId)??[]);
const mediaProgress=ref<MediaProgress>();
const finalState=ref<SubmissionSummary>(),finalError=ref(''),finalChecking=ref(false);
let finalObserver:AbortController|undefined;
let media:PreparedBackgroundMedia|undefined,mediaWork:Promise<void>|undefined;
let uploads:PreparedUploadJournal|undefined,uploadAudio:PreparedAudioJournal|undefined,sender:PreparedBackgroundUpload|undefined,uploadWork:Promise<void>|undefined;
let uploadWakePending=false,mediaWakePending=false;
let journey:PreparedJourneyJournal|undefined,audio:PreparedAudioJournal|undefined,capture:PreparedAnswerCapture|undefined,captureQuestion:PreparedQuestionGrant|undefined,deadline:PreparedQuestionDeadline|undefined,
  intent:JourneyQuestion|undefined,unsubscribe:(()=>void)|undefined,active:AbortController|undefined,version=0,alive=true;
const owner=()=>({origin:location.origin,studentId:props.context.studentId,deploymentId:props.context.deploymentId});
const assessment=()=>props.context.eligibility.assessmentId;
const total=computed(()=>props.context.eligibility.questionCount);
const isRecording=computed(()=>snapshot.value?.phase==='RECORDING');
const hasLocal=computed(()=>segments.value.length>0||!!snapshot.value);
const hasOpen=computed(()=>segments.value.some(s=>s.state==='OPEN'));
const localBytes=computed(()=>segments.value.reduce((sum,s)=>sum+s.byteSize,0));
const previousSegments=computed(()=>segments.value.filter(s=>s.id!==snapshot.value?.segmentId));
const capturedChunks=computed(()=>previousSegments.value.reduce((sum,s)=>sum+s.chunkCount,0)+(snapshot.value?.savedChunks??0));
const capturedBytes=computed(()=>previousSegments.value.reduce((sum,s)=>sum+s.byteSize,0)+(snapshot.value?.savedBytes??0));
const canBegin=computed(()=>!busy.value&&enabled.value&&online.value&&!props.identityPending&&props.consent&&props.microphonePassed&&!isRecording.value&&(!attempt.value||attempt.value.state==='IN_PROGRESS')&&!hasOpen.value);
const canResume=computed(()=>canBegin.value&&question.value?.mayRecord===true&&remainingSeconds.value>0);
const faultCandidate=computed(()=>question.value?.state==='RECORDING'&&attempt.value?.state==='IN_PROGRESS'&&!localEnded.value&&
  segments.value.length>0&&segments.value.every(s=>s.state==='INTERRUPTED')&&(!snapshot.value||snapshot.value.phase==='INTERRUPTED'));
const canDeclare=computed(()=>!!question.value&&!isRecording.value&&!hasOpen.value&&(snapshot.value?.phase==='SEALED'||snapshot.value?.phase==='INTERRUPTED'||segments.value.length>0)&&snapshot.value?.phase!=='LOCAL_SAVE_FAILED'&&snapshot.value?.phase!=='STOPPING');
const canNext=computed(()=>!!question.value&&['UPLOAD_PENDING','SAVED'].includes(question.value.state)&&attempt.value?.state==='IN_PROGRESS'&&question.value.ordinal<total.value);
const allEnded=computed(()=>!!attempt.value?.answeringEndedAt);
const canSubmit=computed(()=>allEnded.value&&finalState.value?.phase==='READY'&&enabled.value&&online.value&&!props.identityPending&&!busy.value&&!finalChecking.value&&!isRecording.value);
const finalMessage=computed(()=>finalChecking.value?'正在查询原提交状态，不会新建回答或调用模型':finalState.value?.phase==='READY'?'全部原回答已经可靠保存，请确认提交整场面试':finalState.value?.phase==='REQUIRES_REVIEW'?'原作答需要教师核验，不自动计零分或要求重答':'尚未取得最终提交回执；请保留本机副本，后台继续补传和核验原回答');
function pendingMessage(phase:string){return ({NOT_STARTED:'尚未授权开始',RECORDING:'尚无原结束信息',UPLOAD_PENDING:'原录音尚未完整保存',REQUIRES_REVIEW:'需要教师核验',NOT_QUEUED:'等待媒体核验',QUEUED:'已排队核验',RUNNING:'正在核验原媒体',RETRY_WAIT:'等待恢复核验',WAITING_APPROVAL:'等待教师批准原录音核验',NEEDS_REVIEW:'媒体核验需要教师处理'} as Record<string,string>)[phase]??'状态待核验';}
const captureMessage=computed(()=>({RECORDING:'正在录音，本机持续保存',STOPPING:'已停止新增录音，正在等待尾片保存',SEALED:'原录音本机封存',INTERRUPTED:'采集有间断，原录音保留，需核验',LOCAL_SAVE_FAILED:'本机保存失败，请保留页面重试原内容'}[snapshot.value?.phase??'STOPPING']));
// The network event is immediate; a late in-flight reply must not overwrite it with
// an online-looking status. Keep durable receipts and original work untouched.
const uploadMessage=computed(()=>({LOCAL_ONLY:'原录音保留本机，等待有界批次或尾片封存',OFFLINE:'网络中断，后台等待恢复，不影响当前题本机录音',CLOSED:'上传门禁关闭，原内容保留',SENDING:'正在后台核验和补传原字节，不影响当前题录音',BYTES_CONFIRMED:'本批云端字节已确认',WAITING:'后台上传暂未确认，错峰重试原批次',WAITING_APPROVAL:'等待教师批准补传，联网后继续核验原回答期限，不重新录音',IDENTITY_REQUIRED:'上传身份待核验，请用原学生账号重新登录',NEEDS_REVIEW:'原上传信息需核验，请保留副本联系教师'}[online.value?uploadProgress.value?.phase??'LOCAL_ONLY':'OFFLINE']));
const mediaMessage=computed(()=>({LOCAL_ONLY:'等待原回答结束，不影响本机录音',OFFLINE:'断网等待恢复，原副本保留',CLOSED:'核验写入尚未开放，原副本保留',WAITING_END:'等待原结束信息登记',WAITING_BYTES:'等待全部原字节收件',VERIFYING:'后台核验原媒体，不影响继续答题',SAVED:'整题录音已保存',WAITING:'核验暂未确认，错峰重试原任务',WAITING_APPROVAL:'等待教师批准原内容补传，不重新录音',IDENTITY_REQUIRED:'身份待核验，请用原学生账号登录',NEEDS_REVIEW:'原录音需要教师核验，不自动零分或重答'}[online.value||mediaProgress.value?.phase==='SAVED'?mediaProgress.value?.phase??'LOCAL_ONLY':'OFFLINE']));
function windowMessage(phase:string){return ({ANSWERING:'整场尚未全部结束，自动窗口尚未起算',AUTOMATIC_WINDOW:'原自动窗口内，后台继续补传',AWAITING_APPROVAL:'等待教师批准补传',APPROVED:'已批准限时补传，后台恢复原请求',EXPIRED:'批准期限已结束，保留原副本并联系教师',NOT_ELIGIBLE:'原范围不接受补传，请联系教师核验'} as Record<string,string>)[phase]??'期限尚待核验';}
const validUuid=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
function ensureLive(controller:AbortController,v:number){if(!alive||controller.signal.aborted||v!==version||auth.role!=='STUDENT'||String(auth.id)!==String(props.context.studentId))throw new Error('IDENTITY_CHANGED');}
async function verify(controller:AbortController,v:number){
  ensureLive(controller,v);const fresh=(await getPreparedPreflightContext(assessment(),controller.signal)).data;ensureLive(controller,v);
  if(!fresh||fresh.studentId!==props.context.studentId||fresh.deploymentId!==props.context.deploymentId||fresh.eligibility?.assessmentId!==assessment()||
    fresh.eligibility.questionCount!==total.value)throw new Error('IDENTITY_CHANGED');
  enabled.value=fresh.workflowEnabled===true;return fresh;
}
function handles(){journey??=new PreparedJourneyJournal(owner(),assessment());audio??=new PreparedAudioJournal(owner());
  uploads??=new PreparedUploadJournal(owner(),assessment());uploadAudio??=new PreparedAudioJournal(owner());
}
function selectAttempt(a?:PreparedAttemptSummary){
  if(a)validAttempt(a);
  if(attempt.value?.id!==a?.id||attempt.value?.paperVersionId!==a?.paperVersionId){
    // Only discard volatile observers. Original journal rows, audio, commands
    // and historical receipts remain intact; this never authorizes a retake.
    sender?.close();sender=undefined;uploadWork=undefined;uploadWakePending=false;uploadProgress.value=undefined;
    media?.close();media=undefined;mediaWork=undefined;mediaWakePending=false;mediaProgress.value=undefined;
    finalObserver?.abort();finalObserver=undefined;finalChecking.value=false;finalState.value=undefined;finalError.value='';
  }
  attempt.value=a;
}
function startUploads(){
  const a=attempt.value;if(!a||!uploads||!uploadAudio)return;validAttempt(a);
  if(sender?.attemptId===a.id)return;
  sender?.close();const identity=owner(),id=assessment(),attemptId=a.id,transport=new PreparedUploadTransport(),v=version;
  const check=(signal:AbortSignal)=>{ensureLive({signal} as AbortController,v);if(attempt.value?.id!==attemptId)throw new Error('IDENTITY_CHANGED');};
  sender=new PreparedBackgroundUpload({owner:identity,assessmentId:id,attemptId,audio:uploadAudio,journal:uploads,transport,online:()=>online.value,
    authority:async signal=>{check(signal);const result=await transport.authority(id,signal);check(signal);return result;}});
}
function wakeUploads(followup=false){
  if(!alive||!sender||sender.attemptId!==attempt.value?.id||finalState.value?.submitted||auth.role!=='STUDENT'||String(auth.id)!==String(props.context.studentId))return;
  // Coalesce reconnect notifications during an in-flight original query. Retain
  // one follow-up, not a parallel upload or an unbounded chain of periodic polls.
  if(uploadWork){if(followup)uploadWakePending=true;return;}
  uploadWakePending=false;const current=sender,v=version,isCurrent=()=>alive&&v===version&&sender===current&&attempt.value?.id===current.attemptId;
  uploadWork=(async()=>{try{const progress=await current.run();if(isCurrent())uploadProgress.value=progress;}
    catch{if(isCurrent())uploadProgress.value={phase:'WAITING',confirmedChunks:0,confirmedBytes:0};}
    finally{if(isCurrent()){uploadWork=undefined;const again=uploadWakePending;uploadWakePending=false;wakeMedia();if(again&&online.value)wakeUploads();}}})();
}
function wakeMedia(followup=false){
  if(!alive||!attempt.value||!sender||sender.attemptId!==attempt.value.id||!uploads||!uploadAudio||finalState.value?.submitted||auth.role!=='STUDENT'||String(auth.id)!==String(props.context.studentId))return;
  if(mediaWork){if(followup)mediaWakePending=true;return;}
  mediaWakePending=false;
  const v=version,id=assessment();
  if(!media||media.attemptId!==attempt.value.id){media?.close();const transport=new PreparedUploadTransport(),originalSender=sender;
    media=new PreparedBackgroundMedia({owner:owner(),attemptId:attempt.value.id,audio:uploadAudio,journal:uploads,transport,online:()=>online.value,
      bytesConfirmed:(a,m)=>originalSender.isConfirmed(a,m),authority:async signal=>{ensureLive({signal} as AbortController,v);const fresh=await transport.authority(id,signal);ensureLive({signal} as AbortController,v);return fresh;}});
  }
  const current=media,isCurrent=()=>alive&&v===version&&media===current&&attempt.value?.id===current.attemptId;
  mediaWork=(async()=>{try{const progress=await current.run();if(isCurrent())mediaProgress.value=progress;}
    catch{if(isCurrent())mediaProgress.value={phase:'WAITING',savedAnswers:0};}
    finally{if(isCurrent()){mediaWork=undefined;const again=mediaWakePending;mediaWakePending=false;if(again&&online.value)wakeMedia();}}})();
}
async function finalAuthority(transport:PreparedUploadTransport,controller:AbortController,v:number){
  ensureLive(controller,v);const current=await transport.authority(assessment(),controller.signal);ensureLive(controller,v);const expected=owner();
  if(current.origin!==expected.origin||current.studentId!==expected.studentId||current.deploymentId!==expected.deploymentId)throw new Error('IDENTITY_CHANGED');enabled.value=current.workflowEnabled===true;
}
async function queryFinal(transport:PreparedUploadTransport,controller:AbortController,v:number){
  const original=attempt.value;if(!original)throw new Error('MISSING_ORIGINAL');await finalAuthority(transport,controller,v);
  const input=await transport.submissionStatus(assessment(),original.id,controller.signal);await finalAuthority(transport,controller,v);
  if(attempt.value?.id!==original.id)throw new Error('IDENTITY_CHANGED');const checked=verifySubmission(input,{attemptId:original.id,paperVersionId:original.paperVersionId,questionCount:total.value});
  finalState.value=checked;finalError.value='';
  if(checked.submitted)attempt.value={...attempt.value,state:'SUBMITTED',revision:Math.max(attempt.value.revision,checked.revision)};
  return checked;
}
function wakeSubmission(){
  if(!alive||!attempt.value||(!allEnded.value&&attempt.value.state!=='SUBMITTED')||finalObserver||busy.value||isRecording.value||!online.value||finalState.value?.submitted)return;
  const controller=new AbortController(),v=version;finalObserver=controller;finalChecking.value=true;
  void queryFinal(new PreparedUploadTransport(),controller,v).catch(()=>{if(alive&&v===version&&!controller.signal.aborted){finalState.value=undefined;finalError.value='提交状态尚未确认，请保留原副本，检查同账号身份和网络后查询；不会重复交卷或重录';}})
    .finally(()=>{if(finalObserver===controller){finalObserver=undefined;finalChecking.value=false;}});
}
async function submitFinal(){
  if(!canSubmit.value)return;finalObserver?.abort();finalObserver=undefined;finalChecking.value=false;
  await run('正在核验完整收件并取得唯一交卷回执……',async(controller,v)=>{
    const transport=new PreparedUploadTransport(),checked=await queryFinal(transport,controller,v);if(checked.submitted)return;
    if(!enabled.value||checked.phase!=='READY'||!attempt.value)throw new Error('NOT_READY');handles();
    const binding={attemptId:attempt.value.id,paperVersionId:attempt.value.paperVersionId},command=await journey!.prepareSubmission(binding,checked.revision);ensureLive(controller,v);
    await finalAuthority(transport,controller,v);if(!enabled.value)throw new Error('NOT_READY');
    const input=await transport.submitAttempt(assessment(),binding.attemptId,{idempotencyKey:command.idempotencyKey,expectedRevision:command.expectedRevision},controller.signal);
    await finalAuthority(transport,controller,v);if(attempt.value?.id!==binding.attemptId)throw new Error('IDENTITY_CHANGED');
    const saved=verifySubmission(input,{...binding,questionCount:total.value});if(!saved.submitted)throw new Error('SERVER_BINDING');finalState.value=saved;finalError.value='';attempt.value={...attempt.value,state:'SUBMITTED',revision:saved.revision};
  });
}
async function trackQuestion(q:PreparedQuestionGrant,original:JourneyQuestion){
  await uploads!.track({...scope(q),captureClientId:original.captureClientId,ordinal:q.ordinal});
}
function validAttempt(a:PreparedAttemptSummary){
  if(!a||a.assessmentId!==assessment()||!validUuid(a.id)||!validUuid(a.paperVersionId)||!Number.isSafeInteger(a.revision)||a.revision<0||!Number.isSafeInteger(a.attemptNo)||a.attemptNo<1||
    !['IN_PROGRESS','UPLOAD_PENDING','SUBMITTED','REQUIRES_REVIEW','CANCELLED'].includes(a.state))throw new Error('SERVER_BINDING');
}
function validQuestion(q:PreparedQuestionGrant,a:PreparedAttemptSummary){
  if(!q||q.assessmentId!==assessment()||q.attemptId!==a.id||q.paperVersionId!==a.paperVersionId||!validUuid(q.answerId)||!validUuid(q.questionId)||
    !Number.isSafeInteger(q.ordinal)||q.ordinal<1||q.ordinal>total.value||typeof q.body!=='string'||!q.body.trim()||
    !['RECORDING','UPLOAD_PENDING','SAVED','REQUIRES_REVIEW'].includes(q.state))throw new Error('SERVER_BINDING');
}
function scope(q=question.value!):AnswerScope{return {assessmentId:assessment(),attemptId:q.attemptId,answerId:q.answerId};}
function captureIsCurrent(q:PreparedQuestionGrant,local:PreparedAnswerCapture,v:number){
  const a=attempt.value,current=question.value;
  // A scope recheck can change the displayed pointer without changing the page
  // identity. Keep the original writer alive, but never project its late result
  // onto a missing/different attempt or question.
  return alive&&v===version&&capture===local&&q.assessmentId===assessment()&&
    a?.assessmentId===q.assessmentId&&a.id===q.attemptId&&a.paperVersionId===q.paperVersionId&&
    current?.assessmentId===q.assessmentId&&current.attemptId===q.attemptId&&current.paperVersionId===q.paperVersionId&&
    current.answerId===q.answerId&&current.questionId===q.questionId;
}
async function run(message:string,operation:(controller:AbortController,v:number)=>Promise<void>){
  if(busy.value)return;const controller=new AbortController(),v=version;active=controller;busy.value=true;working.value=message;error.value='';notice.value='';
  let timer:ReturnType<typeof setTimeout>|undefined;
  try{await Promise.race([operation(controller,v),new Promise<never>((_,reject)=>{timer=setTimeout(()=>{controller.abort();reject(new Error('OBSERVATION_TIMEOUT'));},30000);})]);}
  catch(e){if(alive&&v===version){const code=(e as {code?:string}).code,message=(e as Error).message;
    error.value=message==='MISSING_ORIGINAL'?'缺少原作答标识，请保留本机录音并联系教师核验，不生成替代标识':message==='IDENTITY_CHANGED'?'身份或考核已变化，请使用原账号重新核验':
      code==='RECOVERY_REQUIRED'?'原录音尚无可靠结束依据，请保留已存前缀并联系教师核验':code==='ANOTHER_WRITER'?'另一个页面持有本考核录音，请保留原页面，不会自动抢占':
      code==='AUTHORIZATION_EXPIRED'?'本题原时限已结束，保留原内容，不重新录音':message==='OBSERVATION_TIMEOUT'?'本次操作等待超时，原请求和内容保留，请核验后重试原操作':
      '原作答操作未完成，请保留本机内容，检查同账号身份、网络及设备后核验；不会清空重答或自动计零分';}}
  finally{clearTimeout(timer);if(active===controller){active=undefined;busy.value=false;working.value='';}}
}
async function restore(){await run('正在恢复原作答，单次请求最多等待 10 秒……',async(controller,v)=>{
  // Both GETs enforce current server ownership independently. Overlap their
  // network waits, but do not open local journals or display an attempt until
  // the identity check also succeeds. Cancel the sibling on either failure.
  const [,current]=await Promise.all([verify(controller,v),getCurrentPreparedAttempt(assessment(),controller.signal)])
    .catch(error=>{controller.abort();throw error;});
  const a=current.data;ensureLive(controller,v);if(a)validAttempt(a);selectAttempt(a??undefined);handles();
  question.value=undefined;receipt.value=undefined;segments.value=[];snapshot.value=undefined;deadline=undefined;remainingSeconds.value=0;intent=undefined;localEnded.value=true;
  if(!a)return;
  finalState.value=undefined;finalError.value='';
  if(a.state==='SUBMITTED'){notice.value='正在只读核验原交卷回执，不需要重新录音或生成本机提交键';return;}
  const start=await journey!.readStart();ensureLive(controller,v);if(!start)throw new Error('MISSING_ORIGINAL');
  await journey!.bindAttempt({attemptId:a.id,paperVersionId:a.paperVersionId});ensureLive(controller,v);
  startUploads();
  const sent=questionRequestTime(),q=(await getCurrentPreparedQuestion(assessment(),a.id,start.captureClientId,controller.signal)).data;ensureLive(controller,v);
  if(!q)return;validQuestion(q,a);intent=(await journey!.readQuestion(q.ordinal))??undefined;ensureLive(controller,v);
  if(!intent||intent.captureClientId!==start.captureClientId||intent.attemptId!==a.id||intent.paperVersionId!==a.paperVersionId)throw new Error('MISSING_ORIGINAL');
  question.value=q;if(q.state==='RECORDING'&&q.mayRecord){deadline=new PreparedQuestionDeadline(q,scope(q),sent);remainingSeconds.value=Math.ceil(deadline.remainingMs()/1000);}
  await trackQuestion(q,intent);ensureLive(controller,v);wakeUploads();
  const originalCapture=capture,originalCaptureQuestion=captureQuestion;
  const sameOriginal=originalCapture&&originalCaptureQuestion&&captureIsCurrent(originalCaptureQuestion,originalCapture,v)?originalCapture:undefined;
  // This same-page capture still owns its original writer and retained queue.
  // Only other saved segments use fresh writer acquisition during recovery.
  const ownedSegmentId=sameOriginal?.snapshot().segmentId;
  let rows=await audio!.list(scope(q));ensureLive(controller,v);
  for(const row of rows.filter(s=>s.state==='OPEN'&&s.id!==ownedSegmentId)){
    try{await PreparedAnswerCapture.recoverSaved({journal:audio!,scope:scope(q),segmentId:row.id});}
    catch(e){if((e as {code?:string}).code!=='RECOVERY_REQUIRED')throw e;}
    ensureLive(controller,v);
  }
  rows=await audio!.list(scope(q));ensureLive(controller,v);segments.value=rows;
  localEnded.value=await audio!.hasAnswerEnd(scope(q));ensureLive(controller,v);
  if(sameOriginal&&originalCaptureQuestion&&captureIsCurrent(originalCaptureQuestion,sameOriginal,v))snapshot.value=sameOriginal.snapshot();
  notice.value='已核验原尝试与原时限；不会自动启动麦克风或生成新回答';
});wakeSubmission();}
async function begin(fault=false){if(!canBegin.value||fault&&(!faultCandidate.value||!canResume.value))return;await run('正在核验原请求并获取本题授权……',async(controller,v)=>{
  let stream:MediaStream|undefined;
  try{
    const fresh=await verify(controller,v);if(fresh.workflowEnabled!==true||!props.consent||!props.microphonePassed||navigator.onLine===false)throw new Error('NOT_READY');
    stream=await requestPreparedDevice('microphone',controller.signal);ensureLive(controller,v);await verify(controller,v);handles();
    let a=(await getCurrentPreparedAttempt(assessment(),controller.signal)).data;ensureLive(controller,v);
    let start=await journey!.readStart();ensureLive(controller,v);
    if(a&&!start)throw new Error('MISSING_ORIGINAL');
    if(!a){if(fresh.eligibility.phase!=='READY_FOR_PREFLIGHT')throw new Error('NOT_READY');start??=await journey!.prepareStart();ensureLive(controller,v);
      a=(await startPreparedAttempt(assessment(),{idempotencyKey:start.idempotencyKey},controller.signal)).data;ensureLive(controller,v);}
    validAttempt(a);if(a.state!=='IN_PROGRESS')throw new Error('NOT_RECORDABLE');
    selectAttempt(a);await journey!.bindAttempt({attemptId:a.id,paperVersionId:a.paperVersionId});ensureLive(controller,v);startUploads();
    const current=(await getCurrentPreparedQuestion(assessment(),a.id,start!.captureClientId,controller.signal)).data;ensureLive(controller,v);
    if(current)validQuestion(current,a);
    if(fault&&(!current||current.state!=='RECORDING'||current.mayRecord!==true))throw new Error('NOT_RECORDABLE');
    const ordinal=!current?1:current.state==='UPLOAD_PENDING'||current.state==='SAVED'?current.ordinal+1:current.ordinal;
    if(ordinal>total.value||current?.state==='REQUIRES_REVIEW')throw new Error('NOT_RECORDABLE');
    if(current&&ordinal===current.ordinal){const original=await journey!.readQuestion(ordinal);ensureLive(controller,v);if(!original||original.captureClientId!==start!.captureClientId||original.attemptId!==a.id||original.paperVersionId!==a.paperVersionId)throw new Error('MISSING_ORIGINAL');
      if(!fault&&(await audio!.list(scope(current))).length)throw new Error('ORIGINAL_AUDIO_EXISTS');ensureLive(controller,v);}
    const original=await journey!.prepareQuestion(ordinal);ensureLive(controller,v);
    const sent=questionRequestTime(),q=(await startPreparedQuestion(assessment(),a.id,original,controller.signal)).data;ensureLive(controller,v);validQuestion(q,a);
    if(q.ordinal!==ordinal)throw new Error('SERVER_BINDING');
    if(fault&&(!current||q.answerId!==current.answerId||q.questionId!==current.questionId||q.body!==current.body||q.answerSeconds!==current.answerSeconds||q.startedAt!==current.startedAt||q.answerDeadline!==current.answerDeadline))throw new Error('SERVER_BINDING');
    const limit=new PreparedQuestionDeadline(q,scope(q),sent);intent=original;question.value=q;receipt.value=undefined;snapshot.value=undefined;deadline=limit;
    await trackQuestion(q,original);ensureLive(controller,v);wakeUploads();
    const rows=await audio!.list(scope(q));ensureLive(controller,v);
    if(!fault&&rows.length||fault&&(!rows.length||rows.some(s=>s.state!=='INTERRUPTED')))throw new Error('ORIGINAL_AUDIO_EXISTS');
    localEnded.value=await audio!.hasAnswerEnd(scope(q));ensureLive(controller,v);if(localEnded.value)throw new Error('ORIGINAL_AUDIO_EXISTS');segments.value=rows;
    const local=await startAuthorizedAnswerCapture({journal:audio!,scope:scope(q),ordinal:rows.length+1,stream,deadline:limit});stream=undefined;
    try{ensureLive(controller,v);}catch(e){void local.interrupt('SESSION_CHANGED');throw e;}
    capture=local;captureQuestion={...q};unsubscribe?.();unsubscribe=local.subscribe(value=>{if(captureIsCurrent(q,local,v)){snapshot.value=value;emit('capture-active',value.phase==='RECORDING'||value.phase==='STOPPING');if(['SEALED','INTERRUPTED'].includes(value.phase)){wakeUploads();void refreshTerminal(q,local,v);}}});
    notice.value=fault?'正在原时限内追加故障续录，已有内容保留；采集间断仍需教师核验':'本题已获授权；录音仅本机保存，尚未确认云端收件';
  }finally{for(const track of stream?.getTracks()??[])track.stop();}
});}
async function refreshTerminal(q:PreparedQuestionGrant,local:PreparedAnswerCapture,v:number){
  const handle=audio;if(!handle)return;
  try{const rows=await handle.list(scope(q)),ended=await handle.hasAnswerEnd(scope(q));
    if(captureIsCurrent(q,local,v)){segments.value=rows;localEnded.value=localEnded.value||ended;}}
  catch{if(captureIsCurrent(q,local,v))localEnded.value=true;}
}
async function ending(controller:AbortController,v:number){
  if(!audio||!intent||!question.value)throw new Error('MISSING_ORIGINAL');const originalQuestion=question.value,originalIntent=intent;
  const command=await PreparedAnswerCapture.prepareEnding({journal:audio,scope:scope(originalQuestion),idempotencyKey:originalIntent.endKey,captureClientId:originalIntent.captureClientId});ensureLive(controller,v);
  localEnded.value=true;
  if(navigator.onLine===false){notice.value='原结束请求已本机保存，断网期间不登记下一题；恢复后重试原请求';return;}
  await verify(controller,v);if(!enabled.value)throw new Error('NOT_READY');
  const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(encodeAnswerEndManifest(command.manifest)))),b=>b.toString(16).padStart(2,'0')).join('');ensureLive(controller,v);
  const r=(await endPreparedAnswer(assessment(),originalQuestion.attemptId,originalQuestion.answerId,command,controller.signal)).data;ensureLive(controller,v);
  if(!r||r.answerId!==originalQuestion.answerId||r.serverSaved!==false||!['UPLOAD_PENDING','REQUIRES_REVIEW'].includes(r.declarationState)||r.manifestSha256!==digest||
    !Number.isSafeInteger(r.attemptRevision)||r.attemptRevision<0||!Number.isSafeInteger(r.revision)||r.revision<1||
    !['IN_PROGRESS','UPLOAD_PENDING','SUBMITTED','REQUIRES_REVIEW','CANCELLED'].includes(r.attemptState))throw new Error('SERVER_BINDING');
  receipt.value=r;question.value={...originalQuestion,state:r.declarationState,mayRecord:false};
  if(attempt.value&&r.attemptRevision>=attempt.value.revision)attempt.value={...attempt.value,state:r.attemptState,revision:r.attemptRevision,answeringEndedAt:r.answeringEndedAt,supplementalUploadDeadline:r.supplementalUploadDeadline};
  notice.value='原结束信息已登记；录音云端字节尚未可靠收件，不是最终提交';
  wakeUploads();
}
async function finish(){await run('正在停止本题采集并可靠保存尾片……',async(controller,v)=>{
  if(!capture)throw new Error('MISSING_ORIGINAL');const value=await capture.stop();ensureLive(controller,v);snapshot.value=value;
  if(value.phase==='LOCAL_SAVE_FAILED')throw new Error('LOCAL_SAVE_FAILED');segments.value=await audio!.list(scope());ensureLive(controller,v);await ending(controller,v);
});}
async function declare(){await run('正在恢复并登记本题原结束请求……',async(controller,v)=>{await verify(controller,v);await ending(controller,v);});}
async function retryLocal(){await run('正在重试保存原录音，不重新录制……',async(controller,v)=>{if(!capture)throw new Error('MISSING_ORIGINAL');const value=await capture.retrySave();ensureLive(controller,v);snapshot.value=value;if(value.phase==='LOCAL_SAVE_FAILED')throw new Error('LOCAL_SAVE_FAILED');segments.value=await audio!.list(scope());ensureLive(controller,v);});}
function invalidate(reason:Interruption='SESSION_CHANGED'){
  version++;active?.abort();active=undefined;unsubscribe?.();unsubscribe=undefined;
  finalObserver?.abort();finalObserver=undefined;finalChecking.value=false;finalState.value=undefined;finalError.value='';
  media?.close();media=undefined;mediaWork=undefined;mediaWakePending=false;mediaProgress.value=undefined;
  sender?.close();sender=undefined;uploads?.close();uploads=undefined;uploadAudio?.close();uploadAudio=undefined;uploadWork=undefined;uploadWakePending=false;uploadProgress.value=undefined;
  const oldCapture=capture,oldAudio=audio,oldJourney=journey;capture=undefined;captureQuestion=undefined;audio=undefined;journey=undefined;deadline=undefined;intent=undefined;
  if(oldCapture)void oldCapture.interrupt(reason).finally(()=>oldAudio?.close());else oldAudio?.close();oldJourney?.close();emit('capture-active',false);
  snapshot.value=undefined;segments.value=[];question.value=undefined;attempt.value=undefined;receipt.value=undefined;busy.value=false;working.value='';remainingSeconds.value=0;localEnded.value=true;
}
function network(){online.value=navigator.onLine!==false;if(online.value){wakeUploads(true);wakeMedia(true);wakeSubmission();}}
function pageExit(event?:Event){invalidate(event?.type==='pagehide'?'PAGE_REFRESH':'SESSION_CHANGED');error.value='页面或身份已变化，原内容保留，请用原账号核验恢复';}
function storageChanged(event:StorageEvent){if(isSessionMarkerStorageEvent(event))pageExit(event);}
function stopForContextChange(reason:'SESSION_CHANGED'|'PAGE_REFRESH'){
  invalidate(reason);error.value='页面或身份已变化，原内容保留，请用原账号核验恢复';
}
// A local stop-only parent contract: no new recording, identity or write permission.
defineExpose({stopForContextChange});
// Watch the actual ownership values, not a newly allocated array. Same-owner
// context refreshes during trial/camera checks must not become SESSION_CHANGED.
watch([()=>props.context.studentId,()=>props.context.deploymentId,()=>assessment(),()=>total.value,()=>auth.id,()=>auth.role],()=>{pageExit();});
watch(()=>props.context.workflowEnabled,value=>{enabled.value=value===true;});
watch(()=>props.consent,value=>{if(!value&&capture)pageExit();});
const tick=setInterval(()=>{if(deadline)remainingSeconds.value=Math.ceil(deadline.remainingMs()/1000);},100);
const uploadTick=setInterval(()=>{wakeUploads();wakeMedia();wakeSubmission();},5000);
onMounted(()=>{void restore();window.addEventListener('online',network);window.addEventListener('offline',network);window.addEventListener('pagehide',pageExit);window.addEventListener('storage',storageChanged);});
onUnmounted(()=>{alive=false;clearInterval(tick);clearInterval(uploadTick);invalidate('PAGE_REFRESH');window.removeEventListener('online',network);window.removeEventListener('offline',network);window.removeEventListener('pagehide',pageExit);window.removeEventListener('storage',storageChanged);});
</script>

<style scoped>
.attempt{background:#fff;border:1px solid #dce4ed;border-radius:12px;padding:20px;margin:18px 0}.attempt h2{font-size:19px}.question{font-size:20px;white-space:pre-wrap;overflow-wrap:anywhere}.notice{padding:14px;background:#fff7e9;border-radius:8px}.attempt button{padding:9px 16px;border:1px solid #315cd2;background:#fff;color:#244bc0;border-radius:7px;cursor:pointer;margin:5px 10px 5px 0}.attempt button:disabled{opacity:.5;cursor:not-allowed}.attempt [role=alert]{color:#a03a24}
</style>
