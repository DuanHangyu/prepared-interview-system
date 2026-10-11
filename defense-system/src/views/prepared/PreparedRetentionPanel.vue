<template>
  <section class="retention-panel" aria-label="原尝试留存保护">
    <h3>原尝试留存保护</h3>
    <p>保护原录音及结果的留存标记，不恢复作答、不延长答题或补传时间，不允许重答；历史尝试也按其原编号管理。</p>
    <p>当前版本未启用自动清理。此处只管理人工保护标记，不表示完整清理流程已经上线。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" aria-live="polite">{{ notice }}</p>
    <p v-if="reading" role="status">正在核验原留存保护状态……</p>
    <button :disabled="busy" @click="load">刷新原留存保护状态</button>
    <template v-if="view">
      <p data-testid="retention-current-state">{{ view.held ? '已人工保护' : '未人工保护' }} · 保护版本 {{ view.revision }}</p>
      <p>录音：{{ audioLabel(view.audioState) }} · 结果：{{ view.resultsState==='RETAINED' ? '保留' : '清理中' }}</p>
      <p v-if="view.audioState==='RETIRED'">不能恢复已清理录音；只能管理仍保留的结果。</p>
      <p v-if="!view.mayChange">当前原数据不能修改保护标记，不能承诺重新保护或恢复。</p>
      <template v-if="view.latest"><p>最近操作：{{ view.latest.action==='PROTECT' ? '保护' : '解除人工保护' }} · 操作人 {{ view.latest.actorId }} · {{ display(view.latest.executedAt) }}</p><p class="plain">{{ view.latest.reason }}</p></template>
      <label :for="'retention-reason-'+attemptId">留存保护核验原因（最多 1000 字）</label>
      <p>只填写核验依据，不粘贴完整回答、账号口令或访问凭据。本机只保存待确认请求，需重新核验管理权限后才能恢复。</p>
      <textarea :id="'retention-reason-'+attemptId" v-model="reason" rows="3" maxlength="1000" :disabled="busy || !!pending || !journalReady || !view.mayChange" />
      <p>解除的只是人工保护标记，不清理任何内容，也不移除待补传、人工核验等其他保护条件。</p>
      <button :disabled="busy || !!pending || !journalReady || !view.mayChange" @click="prepare(view.held ? 'RELEASE' : 'PROTECT')">{{ view.held ? '解除人工保护' : '保护原记录' }}</button>
      <template v-if="pending"><p>原请求尚待确认。不会自动发送或替换，即使其他教师已更改当前保护状态，也只能恢复原请求。</p><p class="plain">原请求：{{ pending.action==='PROTECT' ? '保护' : '解除人工保护' }} · 原版本 {{ pending.expectedRevision }} · {{ pending.reason }}</p><button :disabled="busy || !journalReady" @click="sendOriginal">恢复原留存保护请求</button><button v-if="rejectedConflict" :disabled="busy" @click="clearRejected">读取最新状态并撤销未接受请求</button></template>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue';
import { getRetentionProtection,changeRetentionProtection,type RetentionScope,type RetentionCommand,type RetentionView } from '@/api/preparedRetention';
import { checkRetentionScope,copyRetentionCommand,checkRetentionView,checkRetentionReceipt } from '@/prepared/retentionProof';
import { PreparedRetentionJournal } from '@/prepared/retentionJournal';
const props=defineProps<RetentionScope>();
const view=ref<RetentionView>(),pending=ref<RetentionCommand>(),error=ref(''),notice=ref(''),reason=ref(''),reading=ref(false),saving=ref(false),sending=ref(false),journalReady=ref(false),rejectedConflict=ref(false);
const busy=computed(()=>reading.value||saving.value||sending.value);let epoch=0,journal:PreparedRetentionJournal|undefined;
const scope=()=>checkRetentionScope({actorId:props.actorId,studentId:props.studentId,assessmentId:props.assessmentId,attemptId:props.attemptId});
const display=(value:string)=>new Date(value).toLocaleString('zh-CN',{hour12:false});
const audioLabel=(state:RetentionView['audioState'])=>({RETAINED:'保留',RETIRING:'清理中',RETIRED:'已清理'})[state];
const failure=(cause:unknown)=>{error.value=cause instanceof Error?cause.message:'留存保护暂不可用，请保留原请求';};
async function load(){const current=++epoch;reading.value=true;error.value='';view.value=undefined;pending.value=undefined;journalReady.value=false;rejectedConflict.value=false;
  try{const s=scope(),response=await getRetentionProtection(s);if(current!==epoch)return;view.value=checkRetentionView(response.data,s);journal??=new PreparedRetentionJournal(s);const saved=await journal.read();if(current!==epoch)return;pending.value=saved??undefined;journalReady.value=true;if(saved)reason.value=saved.reason;
  }catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)reading.value=false;}
}
async function prepare(action:RetentionCommand['action']){if(busy.value||pending.value||!view.value?.mayChange||!journalReady.value)return;const current=epoch;error.value='';saving.value=true;
  try{if(!reason.value.trim())throw Error('请填写留存保护核验原因（最多 1000 字）');const requested={expectedRevision:view.value.revision,action,reason:reason.value},saved=await journal!.prepare(requested);if(current!==epoch)return;pending.value=saved;reason.value=saved.reason;rejectedConflict.value=false;saving.value=false;
    if(JSON.stringify(saved)!==JSON.stringify(copyRetentionCommand({...requested,idempotencyKey:saved.idempotencyKey}))){notice.value='另一标签页已有不同原请求，请核验原请求后明确恢复；不会发送替代请求';return;}await sendOriginal();
  }catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)saving.value=false;}
}
async function sendOriginal(){if(busy.value||!pending.value||!view.value||!journalReady.value)return;const current=epoch,s=scope(),original=copyRetentionCommand(pending.value),saved=journal!;sending.value=true;error.value='';rejectedConflict.value=false;
  try{if(JSON.stringify(await saved.read())!==JSON.stringify(original))throw Error('本机原留存保护请求已变化，请刷新核验，不创建替代请求');if(current!==epoch)return;const response=await changeRetentionProtection(s,original);if(current!==epoch)return;checkRetentionReceipt(response.data,s,original);await saved.clear(original);if(current!==epoch)return;
    pending.value=undefined;notice.value='原留存保护请求已确认；不表示当前状态未被其他教师改变，请以刷新后的状态为准';sending.value=false;await load();
  }catch(cause){if(current===epoch){failure(cause);rejectedConflict.value=cause instanceof Error&&'status' in cause&&cause.status===409;}}finally{if(current===epoch)sending.value=false;}
}
async function clearRejected(){if(busy.value||!pending.value||!rejectedConflict.value)return;const current=epoch,original=copyRetentionCommand(pending.value),saved=journal!;reading.value=true;error.value='';
  try{const s=scope(),response=await getRetentionProtection(s);if(current!==epoch)return;const checked=checkRetentionView(response.data,s);await saved.clear(original);if(current!==epoch)return;view.value=checked;pending.value=undefined;rejectedConflict.value=false;notice.value='已撤销服务器明确未接受的请求，请按最新状态重新核验';}
  catch(cause){if(current===epoch)failure(cause);}finally{if(current===epoch)reading.value=false;}
}
watch(()=>[props.actorId,props.studentId,props.assessmentId,props.attemptId],()=>{++epoch;journal?.close();journal=undefined;view.value=undefined;pending.value=undefined;error.value='';notice.value='';reason.value='';reading.value=false;saving.value=false;sending.value=false;rejectedConflict.value=false;journalReady.value=false;void load();},{immediate:true});
onBeforeUnmount(()=>{++epoch;journal?.close();});
</script>
<style scoped>
.retention-panel{margin:1rem 0;padding:1rem;border:1px solid #bccbda;border-radius:.5rem;background:#f7fafc;color:#172b40;font-size:1rem;line-height:1.6}.retention-panel p{margin:.5rem 0}.retention-panel label{display:block;margin-top:.75rem}.retention-panel textarea{box-sizing:border-box;display:block;width:min(100%,36rem);padding:.5rem;font:inherit;border:1px solid #8da1b5;border-radius:.25rem}.retention-panel button{min-height:3.5rem;margin:.5rem .5rem .5rem 0;padding:.5rem .75rem;border:1px solid #7e94ab;border-radius:.25rem;background:white;font:inherit;cursor:pointer}.retention-panel button:disabled{opacity:.55;cursor:not-allowed}.plain{white-space:pre-wrap;overflow-wrap:anywhere}[role=alert]{color:#9b2424}
</style>
