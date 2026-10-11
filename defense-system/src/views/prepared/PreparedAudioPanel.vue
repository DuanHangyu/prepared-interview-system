<template>
  <section class="prepared-audio" aria-label="预制题目播报">
    <h2>预制题目播报</h2>
    <p>先保存并审核题目，再准备播报。仅全部分段和整题音频可靠保存后才可发布；面试时不现场生成。</p>
    <p v-if="listing">{{ listing.availability.reason }}</p>
    <p v-if="hasUnsavedChanges">当前有未保存修改，以下是服务端已保存题目的播报状态。</p>
    <p v-if="listing && listing.draftRevision !== revision">服务端草稿已更新，请保存或处理编辑冲突后重新读取。</p>
    <p v-if="loading" aria-live="polite">正在读取播报状态……</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" aria-live="polite">{{ notice }}</p>
    <button type="button" :disabled="writing || loading || parentBusy" @click="refresh">刷新播报状态</button>
    <button v-if="pending" type="button" :disabled="writing || parentBusy || hasUnsavedChanges" @click="sendPending">重试原播报命令</button>
    <p v-if="!questions.length">尚无题目。请先填写并审核题目。</p>
    <article v-for="q in questions" :key="q.questionId" class="audio-question">
      <h3>第 {{ q.ordinal }} 题播报</h3>
      <p v-if="!q.approved">请先审核本题。</p>
      <template v-if="current(q.questionId)">
        <p>{{ stateText(current(q.questionId)!) }} · 分段已保存 {{ current(q.questionId)!.completedParts }} / {{ current(q.questionId)!.partCount }}</p>
        <p v-if="current(q.questionId)!.state === 'FAILED'">准备未完成，已成功分段仍保留；失败不表示可以发布。</p>
        <p v-if="current(q.questionId)!.assemblyRecoverable">分段已齐备，仅重试合成和保存，不再调用语音模型。</p>
        <button v-if="current(q.questionId)!.assemblyRecoverable" type="button" :disabled="!canRecover(q.questionId)" @click="recover(q.questionId)">仅重试合成和保存</button>
        <button v-if="current(q.questionId)!.state === 'READY'" type="button" :disabled="writing || parentBusy" @click="preview(current(q.questionId)!)">试听本题播报</button>
        <audio v-if="previewAssetId === current(q.questionId)!.id && previewUrl" :src="previewUrl" controls preload="none" @error="previewFailure" />
      </template>
      <button v-if="current(q.questionId)?.state !== 'READY' && !current(q.questionId)?.assemblyRecoverable" type="button" :disabled="!canPrepare(q.questionId)" @click="prepare(q.questionId)">{{ current(q.questionId)?.state === 'FAILED' ? '重新准备未成功分段' : '准备本题播报' }}</button>
    </article>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { getPreparedAudio, prepareQuestionAudio, recoverQuestionAudio, getAudioPreview, type AudioCommand, type AudioRecovery, type AudioListing, type AudioView } from '@/api/preparedAudio';
const props=defineProps<{assessmentId:number;revision:number;hasUnsavedChanges:boolean;parentBusy:boolean;questions:{questionId:string;ordinal:number;body:string;approved:boolean}[]}>();
const emit=defineEmits<{busy:[boolean];'revision-changed':[number]}>();
const listing=ref<AudioListing|null>(null),loading=ref(false),writing=ref(false),error=ref(''),notice=ref(''),previewUrl=ref(''),previewAssetId=ref('');
type Pending={kind:'prepare';command:AudioCommand}|{kind:'recover';assetId:string;command:AudioRecovery};
const pending=ref<Pending|null>(null);let epoch=0,timer:ReturnType<typeof setTimeout>|undefined;
const uuid=(v:unknown)=>typeof v==='string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
const current=(qid:string)=>listing.value?.current.find(v=>v.questionId===qid);
const readyCommand=()=>!!listing.value && !listing.value.frozen && listing.value.draftRevision===props.revision && !props.hasUnsavedChanges && !props.parentBusy && !loading.value && !writing.value && !pending.value;
const canPrepare=(qid:string)=>readyCommand() && !!listing.value?.availability.enabled && /^[a-f0-9]{64}$/.test(listing.value.availability.confirmationSha256 || '')
  && props.questions.some(q=>q.questionId===qid && q.approved) && current(qid)?.state!=='GENERATING';
const canRecover=(qid:string)=>readyCommand() && !!current(qid)?.assemblyRecoverable && props.questions.some(q=>q.questionId===qid && q.approved);
const stateText=(v:AudioView)=>({GENERATING:'正在准备播报',READY:'整题播报已可靠保存',FAILED:'播报准备失败'})[v.state];
function fail(e:unknown){error.value=e instanceof Error?e.message:'播报操作失败，已保存分段保留，请重试原命令或刷新状态';}
function validateView(v:AudioView){if(!v || !uuid(v.id) || !uuid(v.questionId) || !uuid(v.jobId) || !['GENERATING','FAILED','READY'].includes(v.state)
  || !Number.isInteger(v.partCount) || v.partCount<0 || v.partCount>50 || !Number.isInteger(v.completedParts) || v.completedParts<0 || v.completedParts>v.partCount)throw new Error('播报状态无效，未展示未经确认的音频');}
function schedule(){clearTimeout(timer);if(listing.value?.current.some(v=>v.state==='GENERATING'))timer=setTimeout(()=>void refresh(),3000);}
async function refresh(){
  const captured=epoch;loading.value=true;error.value='';clearTimeout(timer);
  try{const r=await getPreparedAudio(props.assessmentId);if(captured!==epoch)return;
    if(!r.data || r.data.assessmentId!==props.assessmentId || !Number.isSafeInteger(r.data.draftRevision) || !r.data.availability || !Array.isArray(r.data.current) || !Array.isArray(r.data.history))throw new Error('播报状态无效，请重新读取');
    r.data.current.forEach(validateView);r.data.history.forEach(validateView);listing.value=r.data;
    if(r.data.draftRevision!==props.revision && !props.hasUnsavedChanges && !props.parentBusy && !writing.value)emit('revision-changed',r.data.draftRevision);
  }catch(e){if(captured===epoch){listing.value=null;fail(e);}}
  finally{if(captured===epoch){loading.value=false;schedule();}}
}
async function prepare(qid:string){
  if(!canPrepare(qid))return;const p=listing.value!.availability,q=props.questions.find(q=>q.questionId===qid)!;
  // Worst case rather than a quoted exact charge: punctuation splitting can produce shorter pieces.
  const upperParts=Math.min(50,Math.ceil(q.body.length/300)+1),upper=upperParts*Number(p.maximumPartCostCny || 0);
  if(!window.confirm(`准备本题播报将调用 ${p.model}。费用上界不超过 ${upper.toFixed(6)} 元，计入 100 元共享预算、80 元前暂停。已成功分段可复用。是否继续？`))return;
  pending.value={kind:'prepare',command:{expectedRevision:props.revision,idempotencyKey:crypto.randomUUID(),questionId:qid,expectedConfigurationSha256:p.confirmationSha256!}};await sendPending();
}
async function recover(qid:string){if(!canRecover(qid))return;if(!window.confirm('仅重试整题合成和保存，不再调用语音模型。是否继续？'))return;
  pending.value={kind:'recover',assetId:current(qid)!.id,command:{expectedRevision:props.revision,idempotencyKey:crypto.randomUUID()}};await sendPending();}
async function sendPending(){
  if(!pending.value || writing.value || props.parentBusy || props.hasUnsavedChanges)return;const captured=epoch,p=pending.value;writing.value=true;emit('busy',true);error.value='';notice.value='';
  try{const r=p.kind==='prepare'?await prepareQuestionAudio(props.assessmentId,{...p.command}):await recoverQuestionAudio(props.assessmentId,p.assetId,{...p.command});
    if(captured!==epoch)return;validateView(r.data);if(p.kind==='prepare' && r.data.questionId!==p.command.questionId)throw new Error('播报状态无效，题目绑定不一致');
    if(listing.value)listing.value={...listing.value,current:[...listing.value.current.filter(v=>v.questionId!==r.data.questionId),r.data]};pending.value=null;notice.value='播报任务已保存。可稍后刷新，重复点击不会新建同一任务。';schedule();
  }catch(e){if(captured===epoch)fail(e);}finally{if(captured===epoch){writing.value=false;emit('busy',false);}}
}
async function preview(v:AudioView){
  if(v.state!=='READY' || writing.value || props.parentBusy)return;const captured=epoch;previewUrl.value='';previewAssetId.value='';writing.value=true;emit('busy',true);error.value='';
  try{const r=await getAudioPreview(props.assessmentId,v.id);if(captured!==epoch)return;const url=new URL(r.data.url);
    if(url.protocol!=='https:' || url.username || url.password || url.port || url.hash || !/^prepared-interview-[a-z0-9-]+\.oss-cn-beijing\.aliyuncs\.com$/.test(url.hostname) || !url.search || !url.pathname.startsWith('/prepared/'))throw new Error('试听地址无效，请刷新重试');
    previewAssetId.value=v.id;previewUrl.value=url.href;
  }catch(e){if(captured===epoch)fail(e);}finally{if(captured===epoch){writing.value=false;emit('busy',false);}}
}
function previewFailure(){previewUrl.value='';previewAssetId.value='';error.value='试听连接已失效或媒体不可用，请重新点击试听；已保存音频保留。';}
watch(()=>props.assessmentId,()=>{epoch++;clearTimeout(timer);listing.value=null;pending.value=null;previewUrl.value='';previewAssetId.value='';writing.value=false;notice.value='';emit('busy',false);void refresh();},{immediate:true});
watch(()=>props.revision,()=>{if(!writing.value){epoch++;void refresh();}});
onBeforeUnmount(()=>{epoch++;clearTimeout(timer);emit('busy',false);});
</script>
<style scoped>
.prepared-audio { border:1px solid #c8d5e3; border-radius:12px; padding:20px; margin:20px 0; background:#f6faf7; }
h2 { margin-top:0; font-size:20px; } .audio-question { border-top:1px solid #dce3eb; margin-top:16px; } [role="alert"] { color:#9d2323; }
button { margin:4px 8px 4px 0; border:1px solid #99adbf; border-radius:6px; padding:8px 14px; background:white; cursor:pointer; }
button:disabled { opacity:.5; cursor:not-allowed; } button:focus-visible { outline:3px solid #82b5f2; outline-offset:2px; } audio { display:block; width:min(100%,500px); margin:10px 0; }
</style>
