<template>
  <section aria-label="原录音播放" class="original-audio">
    <h3>原完整录音</h3>
    <p>按需授权播放，最长 5 分钟。断网、播放失败或授权到期不删除原录音，不要求学生重答；不能仅凭播放入口认定已人工复核。</p>
    <button type="button" :disabled="loading" @click="authorize">{{ loading ? '正在核验原录音……' : '获取原录音播放授权' }}</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="audio">
      <p>已核验原文件 · {{ audio.durationMs / 1000 }} 秒 · 授权至 {{ audio.expiresAt }}</p>
      <audio ref="player" :src="audio.url" controls preload="none" crossorigin="anonymous" @error="playbackFailure" />
    </template>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount,ref,watch } from 'vue';
import { getReviewAudio,type ReviewAudio } from '@/api/preparedReview';
const props=defineProps<{actorId:number;assessmentId:number;studentId:number;attemptId:string;answerId:string}>();
const audio=ref<ReviewAudio>(),player=ref<HTMLAudioElement>(),loading=ref(false),error=ref('');
let epoch=0,timer:ReturnType<typeof setTimeout>|undefined;
const uuid=(v:string)=>/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
function clear(){clearTimeout(timer);timer=undefined;const element=player.value;if(element){element.pause();element.removeAttribute('src');element.load();}audio.value=undefined;}
function validate(value:ReviewAudio,elapsed:number):number {
  const lifetime=Date.parse(value?.expiresAt)-Date.parse(value?.serverTime),remaining=lifetime-Math.max(0,elapsed);
  if(!value||value.actorId!==props.actorId||value.assessmentId!==props.assessmentId||value.studentId!==props.studentId||value.attemptId!==props.attemptId||value.answerId!==props.answerId
    ||!uuid(value.attemptId)||!uuid(value.answerId)||!Number.isSafeInteger(value.durationMs)||value.durationMs<1||value.durationMs>1800000
    ||!Number.isSafeInteger(value.byteSize)||value.byteSize<46||value.byteSize>67108864||value.byteSize<46+(value.durationMs-1)*32
    ||!/^[a-f0-9]{64}$/.test(value.sha256)||!Number.isFinite(remaining)||remaining<=0||lifetime>300000||typeof value.url!=='string'||value.url.length>4096)throw new Error('原录音授权或内容范围无法核验，请重新获取授权');
  const url=new URL(value.url),path=new RegExp(`^/prepared/prepared-[a-z0-9][a-z0-9-]{0,47}/students/${props.studentId}/assessments/${props.assessmentId}/attempts/${props.attemptId}/answers/${props.answerId}/media/[a-f0-9]{64}\\.wav$`);
  if(url.protocol!=='https:'||!/^prepared-[a-z0-9][a-z0-9-]*\.oss-cn-beijing\.aliyuncs\.com$/.test(url.hostname)||url.username||url.password||url.port||url.hash||!url.search||!path.test(url.pathname)
    ||value.url.includes('/../')||value.url.includes('/./')||value.url.split('?')[0].includes('%'))throw new Error('原录音播放地址无法核验，请重新获取授权');
  return remaining;
}
async function authorize(){
  if(loading.value)return;const current=++epoch,id=props.assessmentId,attempt=props.attemptId,answer=props.answerId,started=performance.now();clear();loading.value=true;error.value='';
  try{const reply=await getReviewAudio(id,attempt,answer);if(current!==epoch)return;const remaining=validate(reply.data,performance.now()-started);audio.value=reply.data;
    timer=setTimeout(()=>{clear();error.value='播放授权已到期，请重新获取授权；原录音仍保留，不要求学生重答';},remaining);
  }catch(cause){if(current===epoch)error.value=cause instanceof Error?cause.message:'原录音授权失败，请稍后重试；原录音仍保留';}
  finally{if(current===epoch)loading.value=false;}
}
function playbackFailure(){clear();error.value='原录音播放中断，请检查网络后重新获取授权；原录音仍保留，不要求学生重答';}
watch(()=>[props.actorId,props.assessmentId,props.studentId,props.attemptId,props.answerId],()=>{++epoch;clear();loading.value=false;error.value='';});
onBeforeUnmount(()=>{++epoch;clear();});
</script>
<style scoped>
.original-audio{margin:16px 0;padding:12px;border:1px solid #c4d4e4;border-radius:8px}button{padding:8px 12px;border:1px solid #8ca7bf;border-radius:6px;background:#f5f9ff;cursor:pointer}button:disabled{opacity:.5}audio{display:block;width:100%;max-width:600px}[role=alert]{color:#a32222}
</style>
