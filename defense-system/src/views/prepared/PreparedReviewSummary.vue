<template>
  <section aria-label="整场成绩汇总" class="summary-panel">
    <h3>整场成绩汇总</h3>
    <p>只读取原尝试的有效成绩；不发起模型、重考或结果发布。技术异常和尚未出齐成绩不代表零分。</p>
    <p v-if="reading" role="status">正在读取整场成绩，最多等待 10 秒……</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button :disabled="reading" @click="read">刷新整场成绩</button>
    <template v-if="summary">
      <p>{{ summary.theme }} · {{ summary.studentName }} · 第 {{ summary.attemptNo }} 次 · {{ summary.current ? '当前尝试' : '历史尝试，只读' }}</p>
      <p>学生结果设置：{{ summary.showResult ? '已开放' : '尚未开放' }}。实际查看仍受本人身份、当前有效尝试和完整结果权限限制。</p>
      <template v-if="summary.state==='READY'">
        <p data-testid="review-summary-total">总分：{{ summary.totalScore }} / {{ summary.totalMaxScore }} · 通过分数：{{ summary.passScore }}</p>
        <p>首次完整结果完成：{{ summary.completedAt }}。核分或重评可能改变读取版本，请刷新核验。</p>
        <button :disabled="reportReading||exporting" @click="readReport">查看完整报告</button>
        <button :disabled="reportReading||exporting" @click="downloadReport">导出原尝试报告</button>
        <p>仅按现行管理权限读取该原尝试，不向学生公开，也不恢复历史写入。报告不含内部标准、调分理由或录音地址；下载副本请妥善保管。</p>
        <ol><li v-for="row in summary.answers" :key="row.answerId">第 {{ row.ordinal }} 题：{{ row.score }} / {{ row.maxScore }} · {{ sourceLabel(row) }} · v{{ row.version }}</li></ol>
      </template>
      <p v-else role="status">{{ stateLabel(summary.state) }}；不以旧版整数成绩或部分题目的成绩补成总分。</p>
    </template>
    <p v-if="reportReading||exporting" role="status">{{ exporting?'正在导出原尝试报告':'正在读取完整报告' }}，最多等待 10 秒……</p>
    <p v-if="reportError" role="alert">{{ reportError }}</p>
    <section v-if="report" data-testid="review-report" aria-label="原尝试完整报告">
      <h4>完整报告</h4>
      <article v-for="item in report.answers" :key="item.answerId">
        <h4>第 {{ item.ordinal }} 题</h4><p class="plain">{{ item.body }}</p>
        <p>{{ item.score }} / {{ item.maxScore }} · {{ sourceLabel(item) }} · v{{ item.version }}</p>
        <h5>原回答转写</h5>
        <p v-if="!item.transcript">暂无有效转写；教师可依据原录音人工评分，这不表示学生未回答。</p>
        <p v-for="(sentence,index) in item.transcript?.sentences??[]" :key="index" class="plain">{{ sentence.text }}</p>
        <h5 v-if="item.feedback">评分反馈</h5><p v-if="item.feedback" class="plain">{{ item.feedback }}</p>
        <p v-if="item.scoringSource==='TEACHER'">本题采用教师人工评分；报告不展示教师内部调分记录。</p>
      </article>
    </section>
  </section>
</template>
<script setup lang="ts">
import {onBeforeUnmount,ref,watch} from 'vue';
import {saveAs} from 'file-saver';
import {getReviewSummary,getReviewReport,exportReviewReport,type ReviewReport,type ReviewSummary,type ReviewSummaryGrade} from '@/api/preparedReview';
import type {StudentResultAnswer} from '@/api/preparedResult';
const props=withDefaults(defineProps<{actorId:number;assessmentId:number;studentId:number;attemptId:string;current:boolean;refreshVersion?:number}>(),{refreshVersion:0});
const summary=ref<ReviewSummary>(),error=ref(''),reading=ref(false);let generation=0,controller:AbortController|undefined;
const report=ref<ReviewReport>(),reportError=ref(''),reportReading=ref(false),exporting=ref(false);
let reportGeneration=0,exportGeneration=0,reportController:AbortController|undefined,exportController:AbortController|undefined;
const integer=(value:unknown,min:number,max=Number.MAX_SAFE_INTEGER)=>Number.isSafeInteger(value)&&Number(value)>=min&&Number(value)<=max;
const uuid=(value:unknown)=>typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
const text=(value:unknown,max:number)=>typeof value==='string'&&value.trim().length>0&&value.length<=max;
function requireValid(ok:boolean):asserts ok{if(!ok)throw new Error('原整场成绩与评阅范围无法核验，请刷新原尝试');}
function cents(value:unknown,max:number){requireValid(typeof value==='number'&&Number.isFinite(value)&&value>=0&&value<=max&&Math.abs(value*100-Math.round(value*100))<1e-7);return Math.round(value*100);}
function sourceLabel(row:ReviewSummaryGrade){return row.scoringSource==='TEACHER'?'教师人工评分':row.confirmed?'模型评分，教师已核分':'模型评分，尚未核分';}
function stateLabel(state:Exclude<ReviewSummary['state'],'READY'>){return {PROCESSING:'成绩尚未全部就绪',IN_PROGRESS:'原面试仍在进行',UPLOAD_PENDING:'原录音待补传',REQUIRES_REVIEW:'原尝试需人工核验',CANCELLED:'原尝试已结束使用'}[state];}
function validate(value:ReviewSummary){
  requireValid(!!value&&value.actorId===props.actorId&&value.assessmentId===props.assessmentId&&value.studentId===props.studentId&&value.attemptId===props.attemptId
    &&value.current===props.current&&integer(value.attemptNo,1)&&typeof value.showResult==='boolean'&&typeof value.theme==='string'&&value.theme.trim().length>0&&value.theme.length<=10000
    &&(value.studentName===null||typeof value.studentName==='string'&&value.studentName.length<=10000)&&['READY','PROCESSING','IN_PROGRESS','UPLOAD_PENDING','REQUIRES_REVIEW','CANCELLED'].includes(value.state)&&Array.isArray(value.answers));
  if(value.state!=='READY'){requireValid(value.resultVersion===null&&value.completedAt===null&&value.totalScore===null&&value.totalMaxScore===null&&value.passScore===null&&value.answers.length===0);return;}
  requireValid(typeof value.resultVersion==='string'&&/^[a-f0-9]{64}$/.test(value.resultVersion)&&typeof value.completedAt==='string'&&Number.isFinite(Date.parse(value.completedAt))
    &&integer(value.totalMaxScore,1,500000)&&integer(value.passScore,0,value.totalMaxScore!)&&value.answers.length>=1&&value.answers.length<=50);
  const ids=new Set<string>();let total=0,max=0;
  for(let n=0;n<value.answers.length;n++){
    const row=value.answers[n];requireValid(!!row&&uuid(row.answerId)&&!ids.has(row.answerId)&&row.ordinal===n+1&&integer(row.maxScore,1,10000)
      &&integer(row.version,1)&&['MODEL','TEACHER'].includes(row.scoringSource)&&typeof row.confirmed==='boolean'&&(row.scoringSource!=='TEACHER'||row.confirmed));
    ids.add(row.answerId);max+=row.maxScore;total+=cents(row.score,row.maxScore);
  }
  requireValid(max===value.totalMaxScore&&total===cents(value.totalScore,max));
}
function clearReport(){reportGeneration++;exportGeneration++;reportController?.abort();exportController?.abort();report.value=undefined;reportReading.value=false;exporting.value=false;reportError.value='';}
function invalidate(){generation++;controller?.abort();clearReport();summary.value=undefined;reading.value=false;error.value='';}
async function read(){
  invalidate();const current=generation;
  try{
    requireValid(integer(props.actorId,1,2147483647)&&integer(props.assessmentId,1,2147483647)&&integer(props.studentId,1,2147483647)&&uuid(props.attemptId)&&typeof props.current==='boolean');
    controller=new AbortController();reading.value=true;const value=(await getReviewSummary(props.assessmentId,props.attemptId,controller.signal)).data;
    if(current!==generation)return;validate(value);summary.value=value;
  }catch(cause){if(current===generation){summary.value=undefined;error.value=cause instanceof Error?cause.message:'整场成绩读取失败，请稍后重试';}}
  finally{if(current===generation)reading.value=false;}
}
function validateAnswer(value:StudentResultAnswer,row:ReviewSummaryGrade,original:ReviewSummary){
  requireValid(!!value&&value.studentId===original.studentId&&value.assessmentId===original.assessmentId&&value.attemptId===original.attemptId&&value.resultVersion===original.resultVersion
    &&value.answerId===row.answerId&&value.ordinal===row.ordinal&&value.maxScore===row.maxScore&&value.score===row.score&&value.scoringSource===row.scoringSource
    &&value.version===row.version&&value.confirmed===row.confirmed&&text(value.body,10000)&&(value.feedback===null||text(value.feedback,4000)));
  requireValid(value.scoringSource==='MODEL'?value.transcript!==null&&value.feedback!==null:value.feedback===null);
  if(value.transcript===null)return;
  requireValid(!!value.transcript&&Array.isArray(value.transcript.sentences)&&value.transcript.sentences.length>0&&value.transcript.sentences.length<=10000);
  let last=0,characters=0;
  for(const sentence of value.transcript.sentences){requireValid(!!sentence&&integer(sentence.startMs,last,1800000)&&integer(sentence.endMs,sentence.startMs+1,1800000)&&text(sentence.text,10000));last=sentence.endMs;characters+=sentence.text.length;}
  requireValid(characters<=60000);
}
function validateReport(value:ReviewReport,original:ReviewSummary){
  requireValid(!!value&&value.schemaVersion===1&&!!value.summary&&Array.isArray(value.answers));validate(value.summary);
  const fields=['actorId','assessmentId','studentId','studentName','theme','attemptId','attemptNo','current','showResult','state','resultVersion','completedAt','totalScore','totalMaxScore','passScore'] as const;
  const gradeFields=['answerId','ordinal','maxScore','score','scoringSource','version','confirmed'] as const;
  requireValid(fields.every(key=>value.summary[key]===original[key])&&value.answers.length===original.answers.length
    &&value.summary.answers.every((row,n)=>gradeFields.every(key=>row[key]===original.answers[n]?.[key])));
  for(let n=0;n<value.answers.length;n++)validateAnswer(value.answers[n],original.answers[n],original);
}
async function readReport(){
  const original=summary.value;if(!original||original.state!=='READY'||reportReading.value||exporting.value)return;
  const owner=generation,current=++reportGeneration;reportController?.abort();reportController=new AbortController();report.value=undefined;reportError.value='';reportReading.value=true;
  try{
    const value=(await getReviewReport(original.assessmentId,original.attemptId,original.resultVersion!,reportController.signal)).data;
    if(owner!==generation||current!==reportGeneration)return;validateReport(value,original);report.value=value;
  }catch(cause){if(owner===generation&&current===reportGeneration){report.value=undefined;reportError.value=cause instanceof Error?cause.message:'完整报告读取失败，请刷新原尝试后重试';}}
  finally{if(owner===generation&&current===reportGeneration)reportReading.value=false;}
}
async function downloadReport(){
  const original=summary.value;if(!original||original.state!=='READY'||reportReading.value||exporting.value)return;
  const owner=generation,current=++exportGeneration;exportController?.abort();exportController=new AbortController();reportError.value='';exporting.value=true;
  try{
    const response=await exportReviewReport(original.assessmentId,original.attemptId,original.resultVersion!,exportController.signal);
    if(owner!==generation||current!==exportGeneration)return;
    requireValid(response.data instanceof Blob&&response.data.type==='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      &&response.data.size>4&&response.data.size<=32*1024*1024&&response.headers['x-prepared-result-version']===original.resultVersion
      &&response.headers['content-length']===String(response.data.size));
    const prefix=new Uint8Array(await response.data.slice(0,4).arrayBuffer());
    if(owner!==generation||current!==exportGeneration)return;requireValid(prefix[0]===80&&prefix[1]===75&&prefix[2]===3&&prefix[3]===4);
    saveAs(response.data,`prepared-review-${original.assessmentId}-${original.attemptId}.xlsx`);
  }catch(cause){if(owner===generation&&current===exportGeneration){report.value=undefined;reportError.value=cause instanceof Error?cause.message:'原尝试报告导出失败，请刷新后重试';}}
  finally{if(owner===generation&&current===exportGeneration)exporting.value=false;}
}
watch(()=>[props.actorId,props.assessmentId,props.studentId,props.attemptId,props.current,props.refreshVersion],read,{immediate:true});onBeforeUnmount(invalidate);
</script>
<style scoped>
.summary-panel{padding:16px;border:1px solid #ddd;margin:16px 0;line-height:1.7;overflow-wrap:anywhere}button{padding:8px 14px}[role=alert]{color:#a82525}.plain{white-space:pre-wrap;overflow-wrap:anywhere}
</style>
