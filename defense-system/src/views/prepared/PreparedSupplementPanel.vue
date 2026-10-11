<template>
  <section class="supplement-panel" aria-label="原回答补传审批">
    <h3>第 {{ ordinal }} 题 · 原回答补传审批</h3>
    <p>只允许补传原录音，不增加答题时间，不允许删除重答。批准不表示录音已保存或已提交；采集异常仍需人工核验。</p>
    <p v-if="!current">历史尝试只读，不恢复补传或审批写入权限。</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" aria-live="polite">{{ notice }}</p>
      <p v-if="reading" role="status">正在核验原回答和补传状态……</p>
      <button :disabled="busy" @click="load">刷新原补传状态</button>
      <template v-if="view">
        <p>原单题截止：{{ display(view.answerDeadline) }}</p><p>原自动补传截止：{{ view.automaticDeadline ? display(view.automaticDeadline) : '全部题目尚未结束' }}</p>
        <p>当前状态：{{ phaseLabel(view.phase) }}</p>
        <p v-if="view.eligibilityIssue">{{ view.eligibilityIssue }}</p>
        <template v-if="view.latest"><p>最近批准补传截止：{{ display(view.latest.uploadDeadline) }} · 审批版本 {{ view.latest.approvalRevision }} · 操作人 {{ view.latest.actorId }}</p><p class="plain">{{ view.latest.reason }}</p></template>
        <p>截止按当前电脑时区填写，保存为明确的时间点。服务端正式流程未开放时不能批准；原请求仍保留。</p>
        <label :for="'supplement-deadline-'+answerId">批准补传截止（本地时间）</label><input :id="'supplement-deadline-'+answerId" v-model="deadline" type="datetime-local" step="1" :disabled="busy || !!pending || !view.mayApprove || !journalReady" />
        <label :for="'supplement-reason-'+answerId">补传审批原因</label><textarea :id="'supplement-reason-'+answerId" v-model="reason" rows="3" maxlength="1000" :disabled="busy || !!pending || !view.mayApprove || !journalReady" />
        <button :disabled="busy || !!pending || !view.mayApprove || !journalReady" @click="prepare">批准限时补传</button>
        <template v-if="pending"><p>有一份原审批请求尚待确认。不会自动发送或替换；即使最近审批已变化，也只恢复原请求。</p><button :disabled="busy || !journalReady" @click="sendOriginal">恢复原补传审批请求</button><button v-if="rejectedConflict" :disabled="busy" @click="clearRejected">读取最新状态并撤销未接受请求</button></template>
      </template>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue';
import { getSupplementStatus,approveSupplement,type SupplementScope,type SupplementView,type SupplementCommand } from '@/api/preparedSupplement';
import { checkSupplementScope,copySupplementCommand,checkSupplementView,checkSupplementReceipt,localDeadlineToUtc,utcDeadlineToLocal } from '@/prepared/supplementProof';
import { PreparedSupplementJournal } from '@/prepared/supplementJournal';
const props=withDefaults(defineProps<SupplementScope & {ordinal:number;current?:boolean}>(),{current:true});
const view=ref<SupplementView>(),pending=ref<SupplementCommand>(),error=ref(''),notice=ref(''),reason=ref(''),deadline=ref(''),reading=ref(false),sending=ref(false),journalReady=ref(false),rejectedConflict=ref(false);
const busy=computed(()=>reading.value||sending.value);let epoch=0,journal:PreparedSupplementJournal|undefined;
const scope=()=>checkSupplementScope({actorId:props.actorId,studentId:props.studentId,assessmentId:props.assessmentId,attemptId:props.attemptId,answerId:props.answerId});
const display=(value:string)=>new Date(value).toLocaleString('zh-CN',{hour12:false});
const phaseLabel=(phase:SupplementView['phase'])=>({AUTOMATIC_WINDOW:'原自动补传窗口内',AWAITING_APPROVAL:'已超期，等待审批',APPROVED:'已批准限时补传',EXPIRED:'批准期限已到期',COMPLETE:'原回答已保存',NOT_ELIGIBLE:'当前不能审批补传'})[phase];
const failure=(cause:unknown)=>{error.value=cause instanceof Error?cause.message:'补传审批暂不可用，请保留原请求';};
async function load(){if(!props.current)return;const current=++epoch;reading.value=true;error.value='';view.value=undefined;pending.value=undefined;journalReady.value=false;rejectedConflict.value=false;
  try{const s=scope(),response=await getSupplementStatus(s);if(current!==epoch)return;const checked=checkSupplementView(response.data,s);view.value=checked;
    journal??=new PreparedSupplementJournal(s);const saved=await journal.read();if(current!==epoch)return;pending.value=saved??undefined;journalReady.value=true;
    if(saved){reason.value=saved.reason;deadline.value=utcDeadlineToLocal(saved.uploadDeadline);}
  }catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)reading.value=false;}
}
async function prepare(){if(busy.value||pending.value||!view.value?.mayApprove||!journalReady.value||!props.current)return;error.value='';const current=epoch;
  try{if(!deadline.value||!reason.value.trim())throw new Error('请填写明确截止和补传审批原因（最多 1000 字）');const uploadDeadline=localDeadlineToUtc(deadline.value),v=view.value;
    if(Date.parse(uploadDeadline)<=Date.parse(v.serverNow)||v.latest&&Date.parse(uploadDeadline)<=Date.parse(v.latest.uploadDeadline))throw new Error('补传截止必须晚于服务端当前时间及已批准的截止');
    const requested={expectedRevision:v.approvalRevision,expectedAttemptRevision:v.attemptRevision,manifestSha256:v.manifestSha256!,uploadDeadline,reason:reason.value};
    const saved=await journal!.prepare(requested);if(current!==epoch)return;pending.value=saved;reason.value=saved.reason;deadline.value=utcDeadlineToLocal(saved.uploadDeadline);rejectedConflict.value=false;
    if(JSON.stringify(saved)!==JSON.stringify(copySupplementCommand({...requested,idempotencyKey:saved.idempotencyKey}))){notice.value='另一标签页已有不同的原审批请求，请核验原请求后明确恢复；不会发送替代请求';return;}
    await sendOriginal();
  }catch(cause){if(current===epoch)failure(cause);}
}
async function sendOriginal(){if(busy.value||!pending.value||!view.value||!journalReady.value||!props.current)return;const current=epoch,s=scope(),original=copySupplementCommand(pending.value),saved=journal!,v=view.value;sending.value=true;error.value='';rejectedConflict.value=false;
  try{if(JSON.stringify(await saved.read())!==JSON.stringify(original))throw new Error('本机原审批请求已变化，请刷新核验，不创建替代请求');if(current!==epoch)return;
    const response=await approveSupplement(s,original);if(current!==epoch)return;checkSupplementReceipt(response.data,s,original,v);await saved.clear(original);if(current!==epoch)return;
    pending.value=undefined;notice.value='补传已批准，原时限和回答保留；不表示录音已保存或已提交';sending.value=false;await load();
  }catch(cause){if(current===epoch){failure(cause);rejectedConflict.value=cause instanceof Error&&'status' in cause&&cause.status===409;}}finally{if(current===epoch)sending.value=false;}
}
async function clearRejected(){if(busy.value||!pending.value||!rejectedConflict.value||!props.current)return;const current=epoch,original=copySupplementCommand(pending.value),saved=journal!;reading.value=true;error.value='';
  try{const response=await getSupplementStatus(scope());if(current!==epoch)return;const checked=checkSupplementView(response.data,scope());await saved.clear(original);if(current!==epoch)return;view.value=checked;pending.value=undefined;rejectedConflict.value=false;notice.value='已撤销服务器明确未接受的请求，请按最新状态重新核验';}
  catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)reading.value=false;}
}
watch(()=>[props.actorId,props.studentId,props.assessmentId,props.attemptId,props.answerId,props.current],()=>{++epoch;journal?.close();journal=undefined;view.value=undefined;pending.value=undefined;error.value='';notice.value='';reason.value='';deadline.value='';reading.value=false;sending.value=false;rejectedConflict.value=false;journalReady.value=false;void load();},{immediate:true});
onBeforeUnmount(()=>{++epoch;journal?.close();});
</script>
<style scoped>
.supplement-panel{margin:1rem 0;padding:1rem;border:1px solid #bccbda;border-radius:.5rem;background:#f7fafc;color:#172b40;font-size:1rem;line-height:1.6}.supplement-panel p{margin:.5rem 0}.supplement-panel label{display:block;margin-top:.75rem}.supplement-panel input,.supplement-panel textarea{box-sizing:border-box;display:block;min-height:3.5rem;width:min(100%,36rem);padding:.5rem;font:inherit;border:1px solid #8da1b5;border-radius:.25rem}.supplement-panel button{min-height:3.5rem;margin:.5rem .5rem .5rem 0;padding:.5rem .75rem;border:1px solid #7e94ab;border-radius:.25rem;background:white;font:inherit;cursor:pointer}.supplement-panel button:disabled{opacity:.55;cursor:not-allowed}.plain{white-space:pre-wrap;overflow-wrap:anywhere}[role=alert]{color:#9b2424}
</style>
