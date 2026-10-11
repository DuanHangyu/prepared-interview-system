<template>
  <section aria-label="预置面试结果" class="student-result">
    <h2>预置面试结果</h2>
    <p>结果以本次读取版本为准。技术故障或尚未出分不等于零分、缺考或考核失败；刷新不会重新作答或调用模型。</p>
    <p v-if="reading" role="status">正在读取结果，最多等待 10 秒……</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button :disabled="reading" @click="read">刷新结果</button>
    <template v-if="summary">
      <h3>{{ summary.theme }}</h3>
      <p role="status">{{ statusLabel(summary.state) }}</p>
      <template v-if="summary.state==='READY'">
        <p data-testid="result-total">总分：{{ summary.totalScore }} / {{ summary.totalMaxScore }} · 通过分数：{{ summary.passScore }}</p>
        <p>首次完整结果完成：{{ summary.completedAt }}。后续核分或重评可能更新成绩，请重新读取最新版本。</p>
        <button :disabled="reportReading||exporting||detailReading" @click="readReport">查看完整报告</button>
        <button :disabled="reportReading||exporting||detailReading" @click="downloadReport">导出本人报告</button>
        <p>导出仅含本人当前公开结果，不含教师内部标准、理由或录音地址。下载后请妥善保管，不在共用电脑上遗留报告。</p>
        <ol><li v-for="row in summary.answers" :key="row.answerId" data-testid="result-answer">
          第 {{ row.ordinal }} 题：{{ row.score }} / {{ row.maxScore }} · {{ sourceLabel(row) }} · v{{ row.version }}
          <button :disabled="detailReading||reportReading||exporting" @click="readAnswer(row)">查看第 {{ row.ordinal }} 题结果</button>
        </li></ol>
      </template>
    </template>
    <p v-if="detailReading" role="status">正在读取本题结果，最多等待 10 秒……</p>
    <p v-if="detailError" role="alert">{{ detailError }}</p>
    <p v-if="reportReading||exporting" role="status">{{ exporting?'正在导出本人报告':'正在读取完整报告' }}，最多等待 10 秒……</p>
    <p v-if="reportError" role="alert">{{ reportError }}</p>
    <section v-if="report||answer" :data-testid="report?'result-report':'result-detail'" :aria-label="report?'完整结果报告':'本题结果'">
      <h3 v-if="report">完整报告</h3>
      <article v-for="item in report?.answers??(answer?[answer]:[])" :key="item.answerId" :data-testid="report?'result-report-answer':undefined">
      <h3>第 {{ item.ordinal }} 题</h3><p class="plain">{{ item.body }}</p>
      <p>{{ item.score }} / {{ item.maxScore }} · {{ sourceLabel(item) }} · v{{ item.version }}</p>
      <h4>原回答转写</h4>
      <p v-if="!item.transcript">暂无有效转写；教师可依据原录音人工评分，这不表示学生未回答。</p>
      <p v-for="(sentence,index) in item.transcript?.sentences??[]" :key="index" class="plain">{{ sentence.text }}</p>
      <h4 v-if="item.feedback">评分反馈</h4><p v-if="item.feedback" class="plain">{{ item.feedback }}</p>
      <p v-if="item.scoringSource==='TEACHER'">本题采用教师人工评分；不展示教师内部调分记录。</p>
      </article>
    </section>
  </section>
</template>
<script setup lang="ts">
import {onBeforeUnmount,ref,watch} from 'vue';
import {saveAs} from 'file-saver';
import {getPreparedStudentResult,getPreparedStudentResultAnswer,getPreparedStudentResultReport,exportPreparedStudentResultReport,type StudentResultReport,type StudentResultSummary,type StudentResultGrade,type StudentResultAnswer,type StudentResultState} from '@/api/preparedResult';
const props=defineProps<{studentId:number;assessmentId:number}>();
const summary=ref<StudentResultSummary>(),answer=ref<StudentResultAnswer>(),reading=ref(false),detailReading=ref(false),error=ref(''),detailError=ref('');
const report=ref<StudentResultReport>(),reportReading=ref(false),exporting=ref(false),reportError=ref('');
let generation=0,detailGeneration=0,summaryController:AbortController|undefined,detailController:AbortController|undefined;
let reportGeneration=0,exportGeneration=0,reportController:AbortController|undefined,exportController:AbortController|undefined;
const integer=(x:unknown,min:number,max=Number.MAX_SAFE_INTEGER)=>Number.isSafeInteger(x)&&Number(x)>=min&&Number(x)<=max;
const uuid=(x:unknown)=>typeof x==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(x);
const sha=(x:unknown)=>typeof x==='string'&&/^[a-f0-9]{64}$/.test(x);
const text=(x:unknown,max:number)=>typeof x==='string'&&x.trim().length>0&&x.length<=max;
function requireValid(ok:boolean):asserts ok{if(!ok)throw new Error('原结果与本人范围无法核验，请刷新结果');}
function cents(x:unknown,max:number){requireValid(typeof x==='number'&&Number.isFinite(x)&&x>=0&&x<=max&&Math.abs(x*100-Math.round(x*100))<1e-7);return Math.round(x*100);}
function sourceLabel(x:StudentResultGrade){return x.scoringSource==='TEACHER'?'教师人工评分':x.confirmed?'模型评分，教师已核分':'模型评分，尚未核分';}
function statusLabel(state:StudentResultState){return {
  LEGACY:'此考核使用旧版报告，请从原报告入口查看。',NOT_STARTED:'尚未开始预置面试。',IN_PROGRESS:'原面试仍在进行，请从原作答入口恢复。',
  UPLOAD_PENDING:'原回答待补传，请保留本机录音并从原入口恢复。',REQUIRES_REVIEW:'原面试需教师核验，请保留本机录音并联系管理教师。',
  CANCELLED:'原尝试已结束使用，请联系管理教师核验。',PROCESSING:'可靠提交后的结果仍在处理，不需要重新回答。',HIDDEN:'教师尚未开放结果。',READY:'本次有效结果已可查看。',
}[state];}
function validateSummary(v:StudentResultSummary){
  requireValid(!!v&&v.studentId===props.studentId&&v.assessmentId===props.assessmentId&&text(v.theme,10000)&&typeof v.prepared==='boolean'
    &&['LEGACY','NOT_STARTED','IN_PROGRESS','UPLOAD_PENDING','REQUIRES_REVIEW','CANCELLED','PROCESSING','HIDDEN','READY'].includes(v.state));
  requireValid(v.prepared===(v.state!=='LEGACY')&&(v.state==='LEGACY'||v.state==='NOT_STARTED'?v.attemptId===null:uuid(v.attemptId))&&Array.isArray(v.answers));
  if(v.state!=='READY'){
    requireValid(v.resultVersion===null&&v.completedAt===null&&v.totalScore===null&&v.totalMaxScore===null&&v.passScore===null&&v.answers.length===0);return;
  }
  requireValid(sha(v.resultVersion)&&typeof v.completedAt==='string'&&Number.isFinite(Date.parse(v.completedAt))&&integer(v.totalMaxScore,1,500000)
    &&integer(v.passScore,0,v.totalMaxScore!)&&v.answers.length>=1&&v.answers.length<=50);
  let total=0,max=0;const ids=new Set<string>();
  for(let n=0;n<v.answers.length;n++){
    const r=v.answers[n];requireValid(!!r&&uuid(r.answerId)&&!ids.has(r.answerId)&&r.ordinal===n+1&&integer(r.maxScore,1,10000)
      &&integer(r.version,1)&&['MODEL','TEACHER'].includes(r.scoringSource)&&typeof r.confirmed==='boolean'&&(r.scoringSource!=='TEACHER'||r.confirmed));
    ids.add(r.answerId);total+=cents(r.score,r.maxScore);max+=r.maxScore;
  }
  requireValid(max===v.totalMaxScore&&total===cents(v.totalScore,max));
}
function validateAnswer(v:StudentResultAnswer,row:StudentResultGrade,original:StudentResultSummary){
  requireValid(!!v&&v.studentId===props.studentId&&v.assessmentId===props.assessmentId&&v.attemptId===original.attemptId&&v.resultVersion===original.resultVersion
    &&v.answerId===row.answerId&&v.ordinal===row.ordinal&&v.maxScore===row.maxScore&&v.score===row.score&&v.scoringSource===row.scoringSource&&v.version===row.version
    &&v.confirmed===row.confirmed&&text(v.body,10000)&&(v.feedback===null||text(v.feedback,4000)));
  requireValid(v.scoringSource==='MODEL'?v.transcript!==null&&v.feedback!==null:v.feedback===null);
  if(v.transcript===null)return;
  requireValid(!!v.transcript&&Array.isArray(v.transcript.sentences)&&v.transcript.sentences.length>0&&v.transcript.sentences.length<=10000);
  let last=0,characters=0;
  for(const s of v.transcript.sentences){requireValid(!!s&&integer(s.startMs,last,1800000)&&integer(s.endMs,s.startMs+1,1800000)&&text(s.text,10000));last=s.endMs;characters+=s.text.length;}
  requireValid(characters<=60000);
}
function clearReport(){reportGeneration++;exportGeneration++;reportController?.abort();exportController?.abort();report.value=undefined;reportReading.value=false;exporting.value=false;reportError.value='';}
function invalidate(){generation++;detailGeneration++;summaryController?.abort();detailController?.abort();clearReport();summary.value=undefined;answer.value=undefined;reading.value=false;detailReading.value=false;error.value='';detailError.value='';}
async function read(){
  invalidate();const current=generation;
  try{
    requireValid(integer(props.studentId,1,2147483647)&&integer(props.assessmentId,1,2147483647));
    reading.value=true;summaryController=new AbortController();const v=(await getPreparedStudentResult(props.assessmentId,summaryController.signal)).data;
    if(current!==generation)return;validateSummary(v);summary.value=v;
  }catch(e){if(current===generation){summary.value=undefined;error.value=e instanceof Error?e.message:'结果读取失败，请稍后重试';}}
  finally{if(current===generation)reading.value=false;}
}
async function readAnswer(row:StudentResultGrade){
  const original=summary.value;if(!original||original.state!=='READY')return;
  clearReport();
  const owner=generation,current=++detailGeneration;detailController?.abort();detailController=new AbortController();answer.value=undefined;detailError.value='';detailReading.value=true;
  try{
    const v=(await getPreparedStudentResultAnswer(props.assessmentId,row.answerId,original.resultVersion!,detailController.signal)).data;
    if(owner!==generation||current!==detailGeneration)return;validateAnswer(v,row,original);answer.value=v;
  }catch(e){if(owner===generation&&current===detailGeneration){answer.value=undefined;detailError.value=e instanceof Error?e.message:'本题结果读取失败，请刷新结果后重试';}}
  finally{if(owner===generation&&current===detailGeneration)detailReading.value=false;}
}
function validateReport(value:StudentResultReport,original:StudentResultSummary){
  requireValid(!!value&&value.schemaVersion===1&&!!value.summary&&Array.isArray(value.answers));validateSummary(value.summary);
  const fields=['studentId','assessmentId','theme','prepared','state','attemptId','resultVersion','completedAt','totalScore','totalMaxScore','passScore'] as const;
  const gradeFields=['answerId','ordinal','maxScore','score','scoringSource','version','confirmed'] as const;
  requireValid(fields.every(key=>value.summary[key]===original[key])&&value.answers.length===original.answers.length
    &&value.summary.answers.every((row,n)=>gradeFields.every(key=>row[key]===original.answers[n]?.[key])));
  for(let n=0;n<value.answers.length;n++)validateAnswer(value.answers[n],original.answers[n],original);
}
async function readReport(){
  const original=summary.value;if(!original||original.state!=='READY'||reportReading.value||exporting.value)return;
  const owner=generation,current=++reportGeneration;reportController?.abort();reportController=new AbortController();
  detailGeneration++;detailController?.abort();answer.value=undefined;detailError.value='';detailReading.value=false;report.value=undefined;reportError.value='';reportReading.value=true;
  try{
    const value=(await getPreparedStudentResultReport(props.assessmentId,original.resultVersion!,reportController.signal)).data;
    if(owner!==generation||current!==reportGeneration)return;validateReport(value,original);report.value=value;
  }catch(e){if(owner===generation&&current===reportGeneration){report.value=undefined;reportError.value=e instanceof Error?e.message:'完整报告读取失败，请刷新结果后重试';}}
  finally{if(owner===generation&&current===reportGeneration)reportReading.value=false;}
}
async function downloadReport(){
  const original=summary.value;if(!original||original.state!=='READY'||reportReading.value||exporting.value)return;
  const owner=generation,current=++exportGeneration;exportController?.abort();exportController=new AbortController();exporting.value=true;reportError.value='';
  try{
    const response=await exportPreparedStudentResultReport(props.assessmentId,original.resultVersion!,exportController.signal);
    if(owner!==generation||current!==exportGeneration)return;
    requireValid(response.data instanceof Blob&&response.data.type==='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      &&response.data.size>4&&response.data.size<=32*1024*1024&&response.headers['x-prepared-result-version']===original.resultVersion
      &&response.headers['content-length']===String(response.data.size));
    const prefix=new Uint8Array(await response.data.slice(0,4).arrayBuffer());
    if(owner!==generation||current!==exportGeneration)return;requireValid(prefix[0]===80&&prefix[1]===75&&prefix[2]===3&&prefix[3]===4);
    saveAs(response.data,`prepared-result-${original.assessmentId}-${original.attemptId}.xlsx`);
  }catch(e){if(owner===generation&&current===exportGeneration)reportError.value=e instanceof Error?e.message:'本人报告导出失败，请刷新结果后重试';}
  finally{if(owner===generation&&current===exportGeneration)exporting.value=false;}
}
watch(()=>[props.studentId,props.assessmentId],read,{immediate:true});onBeforeUnmount(invalidate);
</script>
<style scoped>
.student-result{max-width:960px;padding:24px;margin:auto;line-height:1.7}.plain{white-space:pre-wrap;overflow-wrap:anywhere}button{margin:8px;padding:8px 14px}li{margin:12px 0}[role=alert]{color:#a82525}
</style>
