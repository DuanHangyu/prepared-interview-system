<template>
  <section class="review-panel" aria-label="预置面试评阅">
    <h1>预置面试评阅{{ roster ? ' · '+roster.theme : '' }}</h1>
    <p>原录音提交、后台处理、教师核分是不同阶段。技术异常不会自动计零分；核分不等于向学生发布成绩。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" aria-live="polite">{{ notice }}</p>
    <button :disabled="busy" @click="loadRoster(cursor)">刷新考核记录</button>
    <p v-if="reading" role="status">正在读取评阅内容……</p>
    <p v-if="roster && !roster.rows.length">尚无学生考核记录。</p>
    <ul class="students">
      <li v-for="row in roster?.rows || []" :key="row.recordId">
        <button data-testid="review-student" :disabled="busy" @click="selectStudent(row)">{{ row.studentName }} · {{ stateLabel(row.state) }}</button>
        <button data-testid="review-attempt-history" :disabled="busy" @click="loadAttempts(row)">查看尝试历史 · {{ row.studentName }}</button>
      </li>
    </ul>
    <button v-if="previous.length" :disabled="busy" @click="previousPage">上一页学生</button>
    <button v-if="roster?.nextRecordId" :disabled="busy" @click="nextPage">下一页学生</button>
    <section v-if="attemptsPage && historyStudent" aria-label="尝试历史">
      <h2>{{ historyStudent.studentName }} · 尝试历史</h2>
      <p v-if="!attemptsPage.attempts.length">尚无预置尝试历史。</p>
      <ul class="students"><li v-for="item in attemptsPage.attempts" :key="item.id">
        <button data-testid="review-attempt" :data-attempt-id="item.id" :disabled="busy" @click="selectHistoryAttempt(item)">第 {{ item.attemptNo }} 次 · {{ item.current ? '当前尝试' : '历史尝试，只读' }} · {{ stateLabel(item.state) }}</button>
      </li></ul>
      <button v-if="attemptsPage.nextAttemptNo" :disabled="busy" @click="loadAttempts(historyStudent,attemptsPage.nextAttemptNo)">更多尝试历史</button>
    </section>
    <section v-if="interview" aria-label="原面试提交">
      <h2>{{ interview.studentName }} · 第 {{ interview.attemptNo }} 次 · {{ interview.current ? '当前尝试' : '历史尝试，只读' }}</h2>
      <p v-if="!interview.current">原提交、原录音和历史成绩只读；可管理该原尝试的留存保护，不恢复学生作答、补传、核分或模型写入权限。</p>
      <p>{{ interview.submitted ? '原录音已完整提交' : '原录音尚未完整提交' }}{{ interview.submittedAt ? ' · '+interview.submittedAt : '' }}</p>
      <PreparedReviewSummary :key="interview.actorId+interview.attemptId" :actor-id="interview.actorId" :assessment-id="assessmentId" :student-id="interview.studentId" :attempt-id="interview.attemptId" :current="interview.current" :refresh-version="summaryRefresh" />
      <button data-testid="review-retention" :disabled="busy" @click="retentionOpen=!retentionOpen">{{ retentionOpen ? '收起原留存保护管理' : '管理原留存保护' }}</button>
      <PreparedRetentionPanel v-if="retentionOpen" :key="interview.actorId+':'+assessmentId+':'+interview.studentId+':'+interview.attemptId" :actor-id="interview.actorId" :assessment-id="assessmentId" :student-id="interview.studentId" :attempt-id="interview.attemptId" />
      <div v-for="item in interview.answers" :key="item.answerId" class="answer-summary">
        <button data-testid="review-answer" :data-answer-id="item.answerId" :disabled="busy || !interview.submitted" @click="selectAnswer(item.answerId)">第 {{ item.ordinal }} 题</button>
        <span>转写：{{ stateLabel(item.transcription.state) }} · 模型评分：{{ stateLabel(item.scoring.state) }}</span>
        <span> · 回答：{{ stateLabel(item.state) }}</span>
        <button data-testid="review-evidence" :data-answer-id="item.answerId" :disabled="busy" @click="evidenceAnswer=item.answerId">查看原结束声明与异常信息</button>
        <PreparedAnswerEvidence v-if="evidenceAnswer===item.answerId" :key="interview.attemptId+item.answerId" :actor-id="interview.actorId" :assessment-id="assessmentId" :student-id="interview.studentId" :attempt-id="interview.attemptId" :answer-id="item.answerId" :ordinal="item.ordinal" :current="interview.current" />
        <button v-if="interview.current && !interview.submitted && item.state!=='SAVED'" data-testid="review-supplement" :data-answer-id="item.answerId" :disabled="busy" @click="supplementAnswer=item.answerId">处理原回答补传</button>
        <PreparedSupplementPanel v-if="interview.current && supplementAnswer===item.answerId" :key="interview.attemptId+item.answerId" :actor-id="interview.actorId" :assessment-id="assessmentId" :student-id="interview.studentId" :attempt-id="interview.attemptId" :answer-id="item.answerId" :ordinal="item.ordinal" :current="interview.current" />
        <p v-if="item.transcription.errorCode || item.scoring.errorCode">处理异常：{{ item.transcription.errorCode || item.scoring.errorCode }}。请核验原回答，不要求学生重答。</p>
      </div>
      <p v-if="!interview.submitted">待补传或异常核验，不按缺考、零分处理。</p>
    </section>
    <article v-if="answer" aria-label="原回答评阅">
      <h2>第 {{ answer.question.ordinal }} 题 · 评阅版本 {{ answer.revision }}</h2>
      <p class="plain">{{ answer.question.body }}</p><h3>冻结评分标准</h3><p class="plain">{{ answer.question.rubric }}</p>
      <h3>原完整转写</h3>
      <ol v-if="answer.transcript"><li v-for="(sentence,index) in answer.transcript.sentences" :key="index" class="plain">{{ sentence.text }} <small>（{{ sentence.startMs }}–{{ sentence.endMs }} 毫秒）</small></li></ol>
      <p v-else>尚无有效转写，请等待处理或由教师核验。</p>
      <h3>当前有效评分</h3>
      <template v-if="answer.manualScore">
        <p data-testid="review-score">{{ answer.manualScore.score }} / {{ answer.manualScore.maxScore }} 分 · 人工评分 · {{ answer.confirmed ? '已人工确认' : '保留原人工评分，等待重评处理' }}</p>
        <p>操作人 {{ answer.manualScore.recordedBy }} · {{ answer.manualScore.recordedAt }}</p><p class="plain">{{ answer.manualScore.reason }}</p>
      </template>
      <template v-else-if="answer.score">
        <p data-testid="review-score">{{ answer.score.score }} / {{ answer.score.maxScore }} 分 · {{ answer.confirmed ? '已确认评分' : '待教师确认' }}</p>
        <p class="plain">{{ answer.score.feedback }}</p>
        <ul><li v-for="(evidence,index) in answer.score.evidence" :key="index" class="plain">第 {{ evidence.sentenceOrdinal }} 句：{{ evidence.quote }}</li></ul>
      </template>
      <p v-else>尚无有效评分。后台未完成或需要教师处理，不代表零分。</p>
      <PreparedOriginalAudio v-if="interview" :key="answer.answerId" :actor-id="answer.actorId" :assessment-id="assessmentId" :student-id="interview.studentId" :attempt-id="interview.attemptId" :answer-id="answer.answerId" />
      <template v-if="interview?.current">
      <label for="review-reason">核分或重评依据</label><textarea id="review-reason" v-model="reason" rows="3" maxlength="1000" :disabled="busy || !!pending" />
      <label for="review-manual-score">人工分数（0–{{ answer.question.maxScore }}，最多两位小数）</label>
      <input id="review-manual-score" v-model="manualInput" inputmode="decimal" maxlength="16" :disabled="busy || !!pending" />
      <p>依据原录音和冻结标准人工评分，不依赖模型成功。保存后保留旧成绩、操作人和依据，不自动向学生发布。</p>
      <button :disabled="busy || !!pending" @click="prepare('adjust')">保存人工评分</button>
      <p>重评仅提交后台请求，受模型可用性及共享测试预算控制，不即时生成新成绩，不重录、不删除历史。</p>
      <button :disabled="busy || !!pending || !answer.scoreId || answer.confirmed || !!answer.manualScore" @click="prepare('confirm')">确认当前评分</button>
      <button :disabled="busy || !!pending || !answer.transcriptId" @click="prepare('regrade')">请求重评</button>
      <button v-if="pending" :disabled="busy" @click="sendOriginal">{{ pending.kind==='confirm' ? '恢复原核分请求' : pending.kind==='adjust' ? '恢复原人工评分请求' : '恢复原重评请求' }}</button>
      <button v-if="pending && rejectedConflict" :disabled="busy" @click="clearRejectedConflict">读取最新版本并撤销未接受请求</button>
      </template>
      <button :disabled="busy" @click="selectAnswer(answer.answerId)">刷新原回答</button>
      <h3>历史结果</h3><p v-if="!history.length">尚无历史结果。</p>
      <ul><li v-for="item in history" :key="item.id">{{ item.kind==='SCORE'?'评分':'转写' }} v{{ item.version }} · {{ item.completedAt }} · {{ item.provider }} / {{ item.model }}<span v-if="item.score"> · {{ item.score.score }} / {{ item.score.maxScore }} 分</span></li></ul>
      <button v-if="answer.nextResultId" :disabled="busy" @click="moreHistory">更多历史结果</button>
      <h3>人工评分历史</h3><button :disabled="busy" @click="loadManualHistory()">查看人工评分历史</button>
      <p v-if="manualHistoryRead && !manualHistory.length">尚无人工评分历史。</p>
      <ul><li v-for="item in manualHistory" :key="item.id">人工评分 v{{ item.version }} · {{ item.score }} / {{ item.maxScore }} 分 · 操作人 {{ item.recordedBy }} · {{ item.recordedAt }}<p class="plain">{{ item.reason }}</p></li></ul>
      <button v-if="nextManualScoreId" :disabled="busy" @click="loadManualHistory(nextManualScoreId)">更多人工评分历史</button>
    </article>
  </section>
</template>
<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue';
import { getReviewRoster,getReviewInterview,getReviewAnswer,confirmReviewScore,requestReviewRegrade,adjustReviewScore,getReviewManualHistory,getReviewAttempts,type ReviewRoster,type ReviewRow,type ReviewInterview,type ReviewAnswer,type ReviewHistory,type ReviewManualScore,type ReviewAttempt,type ReviewAttemptsPage } from '@/api/preparedReview';
import { PreparedReviewJournal,type ReviewIntent } from '@/prepared/reviewJournal';
import PreparedOriginalAudio from './PreparedOriginalAudio.vue';
import PreparedSupplementPanel from './PreparedSupplementPanel.vue';
import PreparedAnswerEvidence from './PreparedAnswerEvidence.vue';
import PreparedReviewSummary from './PreparedReviewSummary.vue';
import PreparedRetentionPanel from './PreparedRetentionPanel.vue';
const props=defineProps<{assessmentId:number}>();
const roster=ref<ReviewRoster>(),interview=ref<ReviewInterview>(),answer=ref<ReviewAnswer>(),history=ref<ReviewHistory[]>([]),pending=ref<ReviewIntent>();
const error=ref(''),notice=ref(''),reason=ref(''),reading=ref(false),sending=ref(false),rejectedConflict=ref(false),cursor=ref(0),previous=ref<number[]>([]);
const manualInput=ref(''),manualHistory=ref<ReviewManualScore[]>([]),manualHistoryRead=ref(false),nextManualScoreId=ref<string|null>(null);
const attemptsPage=ref<ReviewAttemptsPage>(),historyStudent=ref<ReviewRow>();
const supplementAnswer=ref<string>();
const evidenceAnswer=ref<string>();
const summaryRefresh=ref(0);
const retentionOpen=ref(false);
const busy=computed(()=>reading.value||sending.value);let epoch=0;
function stateLabel(state:string){return ({NO_PREPARED_ATTEMPT:'尚未开始预置面试',IN_PROGRESS:'面试进行中',RECORDING:'本题已授权，尚未登记结束',UPLOAD_PENDING:'待补传原录音',REQUIRES_REVIEW:'需人工核验',CANCELLED:'已取消',SUBMITTED:'原录音已提交',STARTED:'已开始',ANSWERING_ENDED:'作答已结束，待补传',SAVED:'原回答已保存',QUEUED:'后台排队中',RUNNING:'后台处理中',RETRY_WAIT:'等待恢复',NEEDS_REVIEW:'需要教师处理',SUCCEEDED:'处理完成',NOT_QUEUED:'尚未进入处理'} as Record<string,string>)[state]||state;}
function failure(cause:unknown){error.value=cause instanceof Error?cause.message:'读取或确认失败，请保留原内容';}
function requireValid(valid:boolean){if(!valid)throw new Error('返回内容的原评阅范围无法核验，请重新读取');}
function resetManualHistory(){supplementAnswer.value=undefined;evidenceAnswer.value=undefined;manualHistory.value=[];manualHistoryRead.value=false;nextManualScoreId.value=null;manualInput.value='';}
function checkManual(value:ReviewManualScore,a:ReviewAnswer){requireValid(value.answerId===a.answerId&&value.questionId===a.question.questionId&&value.maxScore===a.question.maxScore&&Number.isFinite(value.score)&&value.score>=0&&value.score<=value.maxScore&&Number.isSafeInteger(value.recordedBy)&&value.recordedBy>0);}
function journal(){requireValid(!!roster.value&&!!interview.value&&!!answer.value);return new PreparedReviewJournal({actorId:roster.value!.actorId,assessmentId:props.assessmentId,attemptId:interview.value!.attemptId,answerId:answer.value!.answerId});}
async function loadRoster(after=0){
  retentionOpen.value=false;
  const current=++epoch,id=props.assessmentId;reading.value=true;error.value='';roster.value=undefined;interview.value=undefined;answer.value=undefined;pending.value=undefined;rejectedConflict.value=false;history.value=[];
  resetManualHistory();
  attemptsPage.value=undefined;historyStudent.value=undefined;
  try{requireValid(Number.isSafeInteger(id)&&id>0);const response=await getReviewRoster(id,after);if(current!==epoch)return;
    requireValid(response.data.assessmentId===id&&Number.isSafeInteger(response.data.actorId)&&response.data.actorId>0);roster.value=response.data;cursor.value=after;
  }catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)reading.value=false;}
}
async function nextPage(){const next=roster.value?.nextRecordId;if(!next)return;const old=cursor.value;await loadRoster(next);if(roster.value)previous.value.push(old);}
async function previousPage(){const back=previous.value.at(-1);if(back===undefined)return;await loadRoster(back);if(roster.value)previous.value.pop();}
async function loadAttempts(row:ReviewRow,after=0){
  if(busy.value)return;const current=++epoch,id=props.assessmentId,actor=roster.value?.actorId;
  retentionOpen.value=false;
  const old=after&&historyStudent.value?.recordId===row.recordId?attemptsPage.value:undefined;
  reading.value=true;error.value='';notice.value='';
  if(!old){interview.value=undefined;answer.value=undefined;pending.value=undefined;history.value=[];rejectedConflict.value=false;resetManualHistory();attemptsPage.value=undefined;historyStudent.value=undefined;}
  try{requireValid(after===0||!!old&&after===old.nextAttemptNo);const response=await getReviewAttempts(id,row.studentId,after);if(current!==epoch)return;const page=response.data;
    requireValid(page.actorId===actor&&page.assessmentId===id&&page.studentId===row.studentId&&page.recordId===row.recordId&&Array.isArray(page.attempts)&&page.attempts.length<=10);
    const uuid=(value:unknown)=>typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value);
    requireValid(page.activeAttemptId===null||uuid(page.activeAttemptId));
    requireValid(page.activeAttemptId!==null||page.attempts.length===0);
    let previousNo=after||Number.MAX_SAFE_INTEGER;const ids=new Set<string>();
    for(const item of page.attempts){requireValid(uuid(item.id)&&!ids.has(item.id)&&Number.isSafeInteger(item.attemptNo)&&item.attemptNo>0&&item.attemptNo<previousNo&&typeof item.current==='boolean'&&item.current===(item.id===page.activeAttemptId));ids.add(item.id);previousNo=item.attemptNo;}
    requireValid(page.nextAttemptNo===null||(page.attempts.length>0&&page.nextAttemptNo===page.attempts.at(-1)!.attemptNo));
    if(old){requireValid(old.activeAttemptId===page.activeAttemptId);const known=new Set(old.attempts.map(item=>item.id));page.attempts=[...old.attempts,...page.attempts.filter(item=>!known.has(item.id))];}
    attemptsPage.value=page;historyStudent.value=row;
  }catch(cause){if(current===epoch){interview.value=undefined;answer.value=undefined;pending.value=undefined;history.value=[];rejectedConflict.value=false;resetManualHistory();attemptsPage.value=undefined;historyStudent.value=undefined;failure(cause);}}finally{if(current===epoch)reading.value=false;}
}
async function selectHistoryAttempt(item:ReviewAttempt){if(!historyStudent.value)return;await readInterview(historyStudent.value,item.id);}
async function selectStudent(row:ReviewRow){
  attemptsPage.value=undefined;historyStudent.value=undefined;await readInterview(row,row.attemptId);
}
async function readInterview(row:ReviewRow,attemptId:string|null){
  retentionOpen.value=false;
  const current=++epoch,id=props.assessmentId,actor=roster.value?.actorId;interview.value=undefined;answer.value=undefined;pending.value=undefined;rejectedConflict.value=false;history.value=[];error.value='';notice.value='';
  resetManualHistory();
  if(!attemptId){notice.value='该学生尚未开始预置面试，无原回答可评阅';return;}
  reading.value=true;try{const response=await getReviewInterview(id,attemptId);if(current!==epoch)return;
    requireValid(response.data.actorId===actor&&response.data.assessmentId===id&&response.data.studentId===row.studentId&&response.data.attemptId===attemptId&&typeof response.data.current==='boolean');interview.value=response.data;
  }catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)reading.value=false;}
}
async function selectAnswer(answerId:string){
  if(!interview.value?.submitted)return;const current=++epoch,id=props.assessmentId,attempt=interview.value.attemptId,actor=roster.value?.actorId;
  reading.value=true;error.value='';answer.value=undefined;pending.value=undefined;rejectedConflict.value=false;history.value=[];reason.value='';
  resetManualHistory();
  try{const response=await getReviewAnswer(id,attempt,answerId);if(current!==epoch)return;requireValid(response.data.actorId===actor&&response.data.answerId===answerId);
    if(response.data.manualScore)checkManual(response.data.manualScore,response.data);
    answer.value=response.data;history.value=response.data.history;summaryRefresh.value++;
    if(interview.value?.current){pending.value=journal().read()??undefined;if(pending.value){reason.value=pending.value.command.reason;if(pending.value.kind==='adjust')manualInput.value=String(pending.value.command.score);}}
  }catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)reading.value=false;}
}
async function moreHistory(){
  if(!answer.value?.nextResultId||!interview.value)return;const current=epoch,id=props.assessmentId,a=answer.value,attempt=interview.value.attemptId;reading.value=true;error.value='';
  try{const response=await getReviewAnswer(id,attempt,a.answerId,a.nextResultId!);if(current!==epoch)return;
    requireValid(response.data.actorId===a.actorId&&response.data.answerId===a.answerId);
    if(response.data.revision!==a.revision){answer.value=response.data;history.value=response.data.history;summaryRefresh.value++;notice.value='有效版本已变化，请重新核验；历史原内容仍保留';}
    else{const known=new Set(history.value.map(item=>item.id));history.value.push(...response.data.history.filter(item=>!known.has(item.id)));answer.value=response.data;}
  }catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)reading.value=false;}
}
async function prepare(kind:ReviewIntent['kind']){
  if(busy.value||pending.value||!answer.value||!interview.value?.current)return;error.value='';
  if(!reason.value.trim()||reason.value.length>1000){error.value='请填写核分或重评依据（最多 1000 字）';return;}
  try{
    if(kind==='adjust'){
      if(!/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(manualInput.value)||Number(manualInput.value)>answer.value.question.maxScore){error.value='请输入该题范围内的人工分数，最多两位小数，不自动四舍五入';return;}
      pending.value=journal().prepareAdjust(answer.value.revision,Number(manualInput.value),reason.value);
    }else{const resultId=kind==='confirm'?answer.value.scoreId:answer.value.transcriptId;if(!resultId)return;pending.value=journal().prepare(kind,answer.value.revision,resultId,reason.value);}
    await sendOriginal();
  }catch(cause){failure(cause);}
}
async function sendOriginal(){
  if(busy.value||!pending.value||!answer.value||!interview.value?.current)return;const current=epoch,id=props.assessmentId,attempt=interview.value.attemptId,answerId=answer.value.answerId,original=pending.value,saved=journal();sending.value=true;error.value='';
  try{
    if(original.kind==='confirm'){const result=await confirmReviewScore(id,attempt,answerId,{...original.command});if(current!==epoch)return;
      requireValid(result.data.answerId===answerId&&result.data.scoreId===original.command.scoreId&&result.data.revision===original.command.expectedRevision+1);notice.value='当前评分已确认，原回答和历史仍保留；尚未向学生发布成绩';
    }else if(original.kind==='adjust'){const result=await adjustReviewScore(id,attempt,answerId,{...original.command});if(current!==epoch)return;
      requireValid(result.data.answerId===answerId&&result.data.revision===original.command.expectedRevision+1&&typeof result.data.manualScoreId==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(result.data.manualScoreId));notice.value='人工评分已保存，原回答和旧成绩仍保留；尚未向学生发布成绩';
    }else{const result=await requestReviewRegrade(id,attempt,answerId,{...original.command});if(current!==epoch)return;
      requireValid(result.data.answerId===answerId&&result.data.transcriptId===original.command.transcriptId&&result.data.revision===original.command.expectedRevision+1);notice.value='重评请求已保存，等待后台处理；原评分和历史仍保留';
    }
    saved.clear(original);pending.value=undefined;sending.value=false;await selectAnswer(answerId);
  }catch(cause){if(current===epoch){failure(cause);rejectedConflict.value=cause instanceof Error&&'status' in cause&&cause.status===409;}}finally{if(current===epoch)sending.value=false;}
}
async function loadManualHistory(after?:string|null){
  if(busy.value||!answer.value||!interview.value)return;const current=epoch,id=props.assessmentId,a=answer.value,attempt=interview.value.attemptId;reading.value=true;error.value='';
  try{const response=await getReviewManualHistory(id,attempt,a.answerId,after??undefined);if(current!==epoch)return;
    requireValid(Array.isArray(response.data.history)&&response.data.history.length<=10);response.data.history.forEach(item=>checkManual(item,a));
    if(response.data.current)checkManual(response.data.current,a);
    if((response.data.current?.id??null)!==(a.manualScore?.id??null))notice.value='人工有效版本已变化，请刷新原回答后重新核验';
    if(after){const known=new Set(manualHistory.value.map(item=>item.id));manualHistory.value.push(...response.data.history.filter(item=>!known.has(item.id)));}
    else manualHistory.value=response.data.history;
    nextManualScoreId.value=response.data.nextManualScoreId;manualHistoryRead.value=true;
  }catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)reading.value=false;}
}
async function clearRejectedConflict(){
  if(busy.value||!rejectedConflict.value||!pending.value||!answer.value||!interview.value?.current)return;
  try{const id=answer.value.answerId;journal().clear(pending.value);pending.value=undefined;rejectedConflict.value=false;notice.value='原请求已被服务器拒绝，读取最新版本后请重新核验并填写依据';await selectAnswer(id);}catch(cause){failure(cause);}
}
watch(()=>props.assessmentId,()=>{sending.value=false;previous.value=[];cursor.value=0;notice.value='';void loadRoster();},{immediate:true});
onBeforeUnmount(()=>{++epoch;});
</script>
<style scoped>
.review-panel{max-width:1100px;margin:24px auto;padding:24px;background:#fff;border:1px solid #c4d4e4;border-radius:12px;color:#17334b}h1{font-size:24px}h2{font-size:20px;margin-top:24px}h3{font-size:16px;margin-top:20px}.students{display:flex;gap:12px;flex-wrap:wrap;list-style:none;padding:0}.plain{white-space:pre-wrap;overflow-wrap:anywhere}.answer-summary{margin:12px 0}button{padding:8px 12px;margin:4px 8px 4px 0;border:1px solid #8ca7bf;border-radius:6px;background:#f5f9ff;cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}label{display:block}textarea,input{width:100%;box-sizing:border-box;padding:10px;border:1px solid #8ca7bf;border-radius:6px;font:inherit}input{min-height:54px;max-width:280px;margin:8px 0}[role=alert]{color:#a32222}button:focus-visible,textarea:focus-visible,input:focus-visible{outline:3px solid #82b5f2}small{color:#586b7a}@media(max-width:600px){.review-panel{margin:12px;padding:14px}}
</style>
