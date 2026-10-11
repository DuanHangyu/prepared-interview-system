<template>
  <section aria-label="题目语音播报" class="question-audio">
    <p>题目播报在考前准备。加载、播放失败或授权到期不阻止录音，不重置本题原截止时间。</p>
    <button type="button" :disabled="loading" @click="authorize">{{ loading ? '正在获取题目播报……' : '获取题目播报／重播' }}</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <audio v-if="audio" ref="player" :src="audio.url" controls preload="none" crossorigin="anonymous" @error="playbackFailure" />
  </section>
</template>
<script setup lang="ts">
import {onBeforeUnmount,ref,watch} from 'vue';
import {getPreparedQuestionAudio,type PreparedQuestionAudio} from '@/api/preparedAttempt';
const props=defineProps<{studentId:number;assessmentId:number;attemptId:string;answerId:string;captureClientId:string;paperVersionId:string;questionId:string;deploymentId:string}>();
const audio=ref<PreparedQuestionAudio>(),player=ref<HTMLAudioElement>(),loading=ref(false),error=ref('');
let epoch=0,controller:AbortController|undefined,timer:ReturnType<typeof setTimeout>|undefined;
const uuid=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
function clear(){clearTimeout(timer);timer=undefined;const element=player.value;if(element){element.pause();element.removeAttribute('src');element.load();}audio.value=undefined;}
function failure(){return '题目播报暂不可用，请检查网络后重试；文字保留，不阻止录音，不重置原截止时间';}
function validate(value:PreparedQuestionAudio,elapsed:number){
  const lifetime=Date.parse(value?.expiresAt)-Date.parse(value?.serverTime),remaining=lifetime-Math.max(0,elapsed);
  if(!value||value.studentId!==props.studentId||value.assessmentId!==props.assessmentId||value.attemptId!==props.attemptId||value.answerId!==props.answerId
      ||value.captureClientId!==props.captureClientId||value.paperVersionId!==props.paperVersionId||value.questionId!==props.questionId
      ||![value.attemptId,value.answerId,value.captureClientId,value.paperVersionId,value.questionId,value.assetId].every(uuid)
      ||!Number.isSafeInteger(value.byteSize)||value.byteSize<46||value.byteSize>67108864||!Number.isSafeInteger(value.durationMs)||value.durationMs<1||value.durationMs>1800000
      ||!/^[a-f0-9]{64}$/.test(value.sha256)||!Number.isFinite(remaining)||remaining<=0||lifetime>300000||typeof value.url!=='string'||value.url.length>4096
      ||!/^prepared-[a-z0-9][a-z0-9-]{0,47}$/.test(props.deploymentId)||props.deploymentId.endsWith('-'))throw new Error(failure());
  const url=new URL(value.url),path=`/prepared/${props.deploymentId}/assessments/${props.assessmentId}/questions/${props.questionId}/audio/${value.assetId}.wav`;
  if(url.protocol!=='https:'||!/^prepared-interview-[a-z0-9][a-z0-9-]{0,43}\.oss-cn-beijing\.aliyuncs\.com$/.test(url.hostname)
      ||url.username||url.password||url.port||url.hash||!url.search||url.pathname!==path||value.url.includes('/../')||value.url.includes('/./')||value.url.split('?')[0].includes('%'))throw new Error(failure());
  return remaining;
}
async function authorize(){
  if(loading.value)return;const current=++epoch;controller?.abort();controller=new AbortController();const request=controller,started=performance.now();clear();loading.value=true;error.value='';
  try{const reply=await getPreparedQuestionAudio(props.assessmentId,props.attemptId,props.answerId,props.captureClientId,request.signal);
    if(current!==epoch||request.signal.aborted)return;const remaining=validate(reply.data,performance.now()-started);audio.value=reply.data;
    timer=setTimeout(()=>{clear();error.value='题目播报授权已到期，请重新获取；不阻止录音，不重置原截止时间';},remaining);
  }catch{if(current===epoch&&!request.signal.aborted)error.value=failure();}
  finally{if(current===epoch)loading.value=false;}
}
function playbackFailure(){clear();error.value=failure();}
watch(()=>[props.studentId,props.assessmentId,props.attemptId,props.answerId,props.captureClientId,props.paperVersionId,props.questionId,props.deploymentId],()=>{++epoch;controller?.abort();clear();loading.value=false;error.value='';});
onBeforeUnmount(()=>{++epoch;controller?.abort();clear();});
</script>
<style scoped>
.question-audio{margin:12px 0}audio{display:block;width:100%;max-width:600px}[role=alert]{color:#a32222}
</style>
