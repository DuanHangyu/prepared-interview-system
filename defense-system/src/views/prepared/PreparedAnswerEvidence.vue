<template>
  <section aria-label="原结束声明证据" class="evidence">
    <h3>原结束声明与异常信息</h3>
    <p>本页展示已登记的声明，不等于音频字节已收件或完整提交；仅供核验，不解除异常、不增加答题时间，也不要求学生重答。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="reading" role="status">正在读取原结束声明……</p>
    <button :disabled="reading" @click="read(0)">刷新原结束声明</button>
    <template v-if="evidence">
      <p>{{ evidence.current ? '当前尝试' : '历史尝试，只读' }} · {{ stateLabel(evidence.answerState) }}</p>
      <p class="plain">第 {{ evidence.question.ordinal }} 题：{{ evidence.question.body }}</p>
      <h4>冻结标准</h4><p class="plain">{{ evidence.question.rubric }}</p>
      <p>原授权开始：{{ evidence.startedAt }} · 原截止：{{ evidence.answerDeadline }} · 每题 {{ evidence.question.answerSeconds }} 秒</p>
      <p v-if="!evidence.declaration">尚未收到原结束声明，不能据此认定空录音、缺考或回答已完整。</p>
      <template v-else>
        <p>服务端登记：{{ evidence.declaration.acceptedAt }} · 原有效结束：{{ evidence.declaration.effectiveEndedAt }}</p>
        <p>声明字节数：{{ evidence.declaration.byteSize }} · 声明分片：{{ evidence.declaration.chunkCount }} · 声明分段：{{ evidence.declaration.segmentCount }}</p>
        <p class="plain">原清单摘要：{{ evidence.declaration.manifestSha256 }}</p>
        <ul v-if="evidence.declaration.reviewReasons.length"><li v-for="reason in evidence.declaration.reviewReasons" :key="reason">{{ reasonLabel(reason) }}</li></ul>
        <p v-else>原声明没有登记采集争议；仍须完成实际音频上传与完整性核验。</p>
        <p>以下起止为客户端声明时间，仅作诊断，不能证明离线录制时间；有间断的内容不会伪标为完整录音。</p>
        <ol><li v-for="segment in evidence.segments" :key="segment.id" data-testid="evidence-segment">
          第 {{ segment.ordinal }} 段 · {{ segment.state==='INTERRUPTED' ? '采集中断' : '本地分段封存声明' }}
          <span v-if="segment.interruption"> · {{ faultLabel(segment.interruption) }}</span>
          <p>客户端声明时间：{{ segment.declaredStartedAt }}–{{ segment.declaredEndedAt }} 毫秒 · {{ segment.chunkCount }} 片 · {{ segment.byteSize }} 字节 · {{ segment.mimeType }}</p>
        </li></ol>
        <button v-if="evidence.nextSegmentOrdinal!==null" :disabled="reading" @click="read(evidence.nextSegmentOrdinal)">更多原录音分段</button>
      </template>
    </template>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount,ref,watch } from 'vue';
import { getReviewEvidence,type ReviewEvidence } from '@/api/preparedReview';
const props=defineProps<{actorId:number;assessmentId:number;studentId:number;attemptId:string;answerId:string;ordinal:number;current:boolean}>();
const evidence=ref<ReviewEvidence>(),reading=ref(false),error=ref('');let epoch=0;
const uuid=(value:unknown)=>typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
const integer=(value:unknown,min:number,max=Number.MAX_SAFE_INTEGER)=>Number.isSafeInteger(value)&&Number(value)>=min&&Number(value)<=max;
const text=(value:unknown,max:number)=>typeof value==='string'&&value.trim().length>0&&value.length<=max;
function requireValid(valid:boolean):asserts valid{if(!valid)throw new Error('原结束声明与评阅范围无法核验，请重新读取');}
function time(value:unknown){requireValid(typeof value==='string');const n=Date.parse(value);requireValid(Number.isSafeInteger(n));return n;}
function stateLabel(state:string){return ({RECORDING:'原题已开始，尚未登记结束',UPLOAD_PENDING:'原回答待补传',REQUIRES_REVIEW:'需人工核验',SAVED:'服务端原回答状态：已保存'} as Record<string,string>)[state];}
function reasonLabel(reason:string){const labels:Record<string,string>={CAPTURE_INTERRUPTED:'录音采集有间断，需人工核验',EMPTY_AUDIO:'无法确认有效录音，需人工核验',DECLARED_TIME_EXCEEDS_LIMIT:'客户端声明时长超过本题时限，需人工核验'};return Object.hasOwn(labels,reason)?labels[reason]:undefined;}
function faultLabel(reason:string){const labels:Record<string,string>={PAGE_REFRESH:'页面刷新或离开',DEVICE_ENDED:'麦克风采集结束',CAPTURE_ERROR:'录音器异常',LOCAL_WRITE_FAILED:'本机保存异常',SESSION_CHANGED:'会话或身份变化'};return Object.hasOwn(labels,reason)?labels[reason]:undefined;}
function validate(value:ReviewEvidence,after:number){
  requireValid(!!value&&value.actorId===props.actorId&&value.assessmentId===props.assessmentId&&value.studentId===props.studentId
    &&value.attemptId===props.attemptId&&value.answerId===props.answerId&&value.current===props.current);
  requireValid(integer(value.attemptRevision,0)&&integer(value.answerRevision,0)&&['IN_PROGRESS','UPLOAD_PENDING','SUBMITTED','REQUIRES_REVIEW','CANCELLED'].includes(value.attemptState)
    &&['RECORDING','UPLOAD_PENDING','SAVED','REQUIRES_REVIEW'].includes(value.answerState));
  const q=value.question;requireValid(!!q&&uuid(q.questionId)&&q.ordinal===props.ordinal&&text(q.body,10000)&&text(q.rubric,20000)&&integer(q.answerSeconds,1,1800)&&integer(q.maxScore,1,10000));
  const started=time(value.startedAt),deadline=time(value.answerDeadline);requireValid(deadline===started+q.answerSeconds*1000);
  requireValid(Array.isArray(value.segments)&&value.segments.length<=20);
  const d=value.declaration;
  if(d===null){requireValid(after===0&&value.answerState==='RECORDING'&&value.segments.length===0&&value.nextSegmentOrdinal===null);return;}
  requireValid(!!d&&/^[a-f0-9]{64}$/.test(d.manifestSha256)&&integer(d.revision,1,value.answerRevision)
    &&integer(d.byteSize,0,67108864)&&integer(d.chunkCount,0,10000)&&integer(d.segmentCount,1,1000)&&Array.isArray(d.reviewReasons)&&d.reviewReasons.length<=3);
  requireValid(d.reviewReasons.every(reason=>!!reasonLabel(reason))&&new Set(d.reviewReasons).size===d.reviewReasons.length
    &&d.state===(d.reviewReasons.length?'REQUIRES_REVIEW':'UPLOAD_PENDING')&&value.answerState!=='RECORDING');
  const accepted=time(d.acceptedAt);requireValid(accepted>=started&&time(d.effectiveEndedAt)===Math.min(accepted,deadline));
  requireValid(after<=d.segmentCount&&value.segments.length===Math.min(20,d.segmentCount-after));
  const end=after+value.segments.length;requireValid(value.nextSegmentOrdinal===(end<d.segmentCount?end:null));
  let last=-1,bytes=0,chunks=0;const ids=new Set<string>();
  for(let index=0;index<value.segments.length;index++){
    const s=value.segments[index];
    requireValid(!!s&&uuid(s.id)&&!ids.has(s.id)&&s.ordinal===after+index+1&&['audio/webm;codecs=opus','audio/webm','audio/ogg;codecs=opus','audio/ogg','audio/mp4'].includes(s.mimeType));ids.add(s.id);
    requireValid(integer(s.declaredStartedAt,0,8640000000000000)&&integer(s.declaredEndedAt,s.declaredStartedAt,8640000000000000)&&s.declaredStartedAt>=last);last=s.declaredEndedAt;
    requireValid(integer(s.chunkCount,0,10000)&&integer(s.byteSize,s.chunkCount,67108864)&&s.byteSize<=s.chunkCount*2097152);
    requireValid(s.state==='SEALED'?s.interruption===null&&s.chunkCount>0:s.state==='INTERRUPTED'&&typeof s.interruption==='string'&&!!faultLabel(s.interruption)&&d.reviewReasons.includes('CAPTURE_INTERRUPTED'));
    bytes+=s.byteSize;chunks+=s.chunkCount;
  }
  requireValid(bytes<=d.byteSize&&chunks<=d.chunkCount);
}
function binding(value:ReviewEvidence){
  const q=value.question,d=value.declaration;
  return JSON.stringify([value.actorId,value.assessmentId,value.studentId,value.attemptId,value.answerId,value.current,value.attemptState,value.attemptRevision,value.answerState,value.answerRevision,
    q.questionId,q.ordinal,q.body,q.answerSeconds,q.maxScore,q.rubric,value.startedAt,value.answerDeadline,d&&[d.manifestSha256,d.state,d.revision,d.effectiveEndedAt,d.acceptedAt,d.reviewReasons,d.byteSize,d.chunkCount,d.segmentCount]]);
}
async function read(after=0){
  if(reading.value)return;const current=++epoch,scope={...props},old=after?evidence.value:undefined;reading.value=true;error.value='';
  if(!after)evidence.value=undefined;
  try{
    requireValid(integer(scope.actorId,1)&&integer(scope.assessmentId,1)&&integer(scope.studentId,1)&&uuid(scope.attemptId)&&uuid(scope.answerId)&&integer(scope.ordinal,1,50)&&typeof scope.current==='boolean');
    requireValid(after===0||!!old&&old.nextSegmentOrdinal===after);
    const result=await getReviewEvidence(scope.assessmentId,scope.attemptId,scope.answerId,after);if(current!==epoch)return;
    const value=result.data;validate(value,after);
    if(old){requireValid(binding(old)===binding(value));const known=new Set(old.segments.map(s=>s.id));requireValid(value.segments.every(s=>!known.has(s.id))
      &&(!value.segments.length||value.segments[0].declaredStartedAt>=old.segments.at(-1)!.declaredEndedAt));value.segments=[...old.segments,...value.segments];}
    if(value.declaration&&value.nextSegmentOrdinal===null){requireValid(value.segments.reduce((sum,s)=>sum+s.byteSize,0)===value.declaration.byteSize
      &&value.segments.reduce((sum,s)=>sum+s.chunkCount,0)===value.declaration.chunkCount);}
    evidence.value=value;
  }catch(cause){if(current===epoch){evidence.value=undefined;error.value=cause instanceof Error?cause.message:'原结束声明暂不可读取，请重试';}}
  finally{if(current===epoch)reading.value=false;}
}
watch([()=>props.actorId,()=>props.assessmentId,()=>props.studentId,()=>props.attemptId,()=>props.answerId,()=>props.ordinal,()=>props.current],()=>{
  ++epoch;reading.value=false;evidence.value=undefined;error.value='';void read(0);
},{immediate:true});
onBeforeUnmount(()=>{++epoch;});
</script>
<style scoped>
.evidence{padding:16px;margin:12px 0;background:#f5f9ff;border:1px solid #c4d4e4;border-radius:8px}.plain{white-space:pre-wrap;overflow-wrap:anywhere}li p{font-size:14px}[role=alert]{color:#a32222}button{padding:8px 12px;margin:4px 0;border:1px solid #8ca7bf;border-radius:6px;cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible{outline:3px solid #82b5f2}
</style>
