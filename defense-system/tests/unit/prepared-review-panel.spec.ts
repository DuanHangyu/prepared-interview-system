import { mount,flushPromises } from '@vue/test-utils';
import { beforeEach,afterEach,expect,it,vi } from 'vitest';
import PreparedReviewPanel from '@/views/prepared/PreparedReviewPanel.vue';
import PreparedReviewSummary from '@/views/prepared/PreparedReviewSummary.vue';
import PreparedRetentionPanel from '@/views/prepared/PreparedRetentionPanel.vue';
import { getReviewRoster,getReviewInterview,getReviewAnswer,confirmReviewScore,requestReviewRegrade,adjustReviewScore,getReviewManualHistory,getReviewAttempts } from '@/api/preparedReview';
vi.mock('@/api/preparedReview',()=>({getReviewRoster:vi.fn(),getReviewInterview:vi.fn(),getReviewAnswer:vi.fn(),confirmReviewScore:vi.fn(),requestReviewRegrade:vi.fn(),adjustReviewScore:vi.fn(),getReviewManualHistory:vi.fn(),getReviewAttempts:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',answer='22222222-2222-4222-8222-222222222222',transcript='33333333-3333-4333-8333-333333333333',score='44444444-4444-4444-8444-444444444444';
const roster={actorId:7,assessmentId:21,theme:'考核',rows:[{recordId:9,studentId:8,studentName:'学生甲',attemptId:attempt,state:'SUBMITTED'}],nextRecordId:null};
const interview={actorId:7,assessmentId:21,studentId:8,studentName:'学生甲',attemptId:attempt,attemptNo:1,state:'SUBMITTED',submitted:true,current:true,submittedAt:'2026-10-04T01:00:00Z',answers:[{answerId:answer,ordinal:1,state:'SAVED',transcription:{state:'SUCCEEDED',errorCode:null},scoring:{state:'QUEUED',errorCode:null}}]};
const detail={actorId:7,answerId:answer,revision:2,confirmed:false,question:{questionId:answer,source:'MANUAL',ordinal:1,body:'<script>题目</script>',rubric:'冻结标准',maxScore:10,answerSeconds:120,audio:{}},transcriptId:transcript,transcript:{schemaVersion:1,provider:'test',model:'test',audioSha256:'a'.repeat(64),durationMs:1000,sentences:[{startMs:0,endMs:1000,text:'原回答依据'}]},scoreId:score,score:{schemaVersion:1,questionId:answer,transcriptId:transcript,transcriptSha256:'a'.repeat(64),maxScore:10,score:0,feedback:'<script>反馈</script>',evidence:[{sentenceOrdinal:1,quote:'原回答'}]},history:[],nextResultId:null};
let mounted:ReturnType<typeof mount>[]=[];
const render=(id=21)=>{const w=mount(PreparedReviewPanel,{props:{assessmentId:id},global:{stubs:{PreparedReviewSummary:true,PreparedRetentionPanel:true}}});mounted.push(w);return w;};
const button=(w:ReturnType<typeof mount>,name:string)=>w.findAll('button').find(b=>b.text()===name)!;
async function open(w:ReturnType<typeof mount>){await flushPromises();await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();await w.find('[data-testid="review-answer"]').trigger('click');await flushPromises();}
beforeEach(()=>{sessionStorage.clear();vi.resetAllMocks();vi.mocked(getReviewRoster).mockResolvedValue({data:roster});vi.mocked(getReviewInterview).mockResolvedValue({data:interview});vi.mocked(getReviewAnswer).mockResolvedValue({data:detail});});
afterEach(()=>{mounted.forEach(w=>w.unmount());mounted=[];});
it('opens retention management only on explicit selection, scoped to the original current or historical attempt',async()=>{
  const w=render();await open(w);expect(w.findComponent(PreparedRetentionPanel).exists()).toBe(false);
  await w.get('[data-testid="review-retention"]').trigger('click');expect(w.findComponent(PreparedRetentionPanel).props()).toEqual({actorId:7,assessmentId:21,studentId:8,attemptId:attempt});
  await w.get('[data-testid="review-student"]').trigger('click');await flushPromises();expect(w.findComponent(PreparedRetentionPanel).exists()).toBe(false);
  vi.mocked(getReviewInterview).mockResolvedValue({data:{...interview,current:false,attemptId:score}});
  vi.mocked(getReviewAttempts).mockResolvedValue({data:{actorId:7,assessmentId:21,studentId:8,recordId:9,activeAttemptId:attempt,attempts:[{id:score,attemptNo:1,state:'SUBMITTED',current:false,startedAt:'2026-10-04T00:00:00Z',submittedAt:'2026-10-04T01:00:00Z'}],nextAttemptNo:null}});
  await w.get('[data-testid="review-attempt-history"]').trigger('click');await flushPromises();await w.get('[data-testid="review-attempt"]').trigger('click');await flushPromises();await w.get('[data-testid="review-retention"]').trigger('click');expect(w.findComponent(PreparedRetentionPanel).props('attemptId')).toBe(score);expect(w.text()).toContain('不恢复学生作答');
  await button(w,'刷新考核记录').trigger('click');await flushPromises();expect(w.findComponent(PreparedRetentionPanel).exists()).toBe(false);
});
it('binds the whole-interview summary to the exact selected manager scope and refreshes after accepted grading',async()=>{
  const w=render();await open(w);const summary=w.findComponent(PreparedReviewSummary);expect(summary.exists()).toBe(true);
  expect(summary.props()).toMatchObject({actorId:7,assessmentId:21,studentId:8,attemptId:attempt,current:true});const before=Number(summary.props('refreshVersion'));expect(Number.isSafeInteger(before)).toBe(true);
  await w.find('textarea').setValue('教师核验原评分');vi.mocked(confirmReviewScore).mockResolvedValueOnce({data:{answerId:answer,revision:3,scoreId:score}});
  await button(w,'确认当前评分').trigger('click');await flushPromises();expect(summary.props('refreshVersion')).toBeGreaterThan(before);
});
it('distinguishes absent grade from valid zero and safely renders frozen question, original transcript and evidence',async()=>{
  const w=render();await open(w);expect(w.text()).toContain('0 / 10');expect(w.text()).toContain('冻结标准');expect(w.find('script').exists()).toBe(false);expect(w.text()).toContain('<script>反馈</script>');
  vi.mocked(getReviewAnswer).mockResolvedValue({data:{...detail,score:null,scoreId:null,transcript:null,transcriptId:null}});
  await button(w,'刷新原回答').trigger('click');await flushPromises();expect(w.text()).toContain('尚无有效评分');expect(button(w,'确认当前评分').attributes('disabled')).toBeDefined();expect(button(w,'请求重评').attributes('disabled')).toBeDefined();
});
it('requires reason, saves before POST, restores same command after remount and never overwrites its reason',async()=>{
  let w=render();await open(w);await button(w,'确认当前评分').trigger('click');expect(confirmReviewScore).not.toHaveBeenCalled();expect(w.text()).toContain('填写核分或重评依据');
  await w.find('textarea').setValue('原教师依据');vi.mocked(confirmReviewScore).mockRejectedValueOnce(new Error('响应丢失'));
  await button(w,'确认当前评分').trigger('click');await flushPromises();expect(w.text()).toContain('响应丢失');const original=vi.mocked(confirmReviewScore).mock.calls[0];
  w.unmount();mounted=[];w=render();await open(w);expect(confirmReviewScore).toHaveBeenCalledTimes(1);
  vi.mocked(confirmReviewScore).mockResolvedValue({data:{answerId:answer,revision:3,scoreId:score}});
  await button(w,'恢复原核分请求').trigger('click');await flushPromises();expect(vi.mocked(confirmReviewScore).mock.calls[1]).toEqual(original);expect(sessionStorage.length).toBe(0);
});
it('enqueues regrade with immutable transcript and reports original evidence retained, not completed new grades',async()=>{
  const w=render();await open(w);await w.find('textarea').setValue('核对标准');vi.mocked(requestReviewRegrade).mockResolvedValue({data:{jobId:score,answerId:answer,revision:3,transcriptId:transcript}});
  await button(w,'请求重评').trigger('click');await flushPromises();expect(requestReviewRegrade).toHaveBeenCalledWith(21,attempt,answer,expect.objectContaining({expectedRevision:2,transcriptId:transcript,reason:'核对标准'}));
  expect(w.text()).toContain('重评请求已保存');expect(w.text()).toContain('0 / 10');expect(w.text()).not.toContain('重评已完成');
});
it('refuses POST if local intent cannot persist and ignores stale assessment responses',async()=>{
  const w=render();await open(w);await w.find('textarea').setValue('核验');vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('blocked');});
  await button(w,'确认当前评分').trigger('click');await flushPromises();expect(confirmReviewScore).not.toHaveBeenCalled();expect(w.text()).toContain('保存');vi.restoreAllMocks();
  let resolve!:(v:{data:typeof detail})=>void;vi.mocked(getReviewAnswer).mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
  await button(w,'刷新原回答').trigger('click');vi.mocked(getReviewRoster).mockResolvedValue({data:{...roster,assessmentId:22,rows:[]}});await w.setProps({assessmentId:22});await flushPromises();resolve({data:detail});await flushPromises();expect(w.text()).not.toContain('冻结标准');
});
it('shows empty roster and read failures without inventing students or silently confirming',async()=>{
  vi.mocked(getReviewRoster).mockResolvedValue({data:{...roster,rows:[]}});const w=render();await flushPromises();expect(w.text()).toContain('尚无学生考核记录');
  vi.mocked(getReviewRoster).mockRejectedValue(new Error('权限已变化'));await button(w,'刷新考核记录').trigger('click');await flushPromises();expect(w.text()).toContain('权限已变化');expect(w.find('[data-testid="review-student"]').exists()).toBe(false);
});
it('only a definitive version conflict permits clearing a rejected intent and rereading before a new request',async()=>{
  const w=render();await open(w);await w.find('textarea').setValue('过期依据');
  vi.mocked(confirmReviewScore).mockRejectedValue(Object.assign(new Error('版本已变化'),{status:409,code:409}));
  await button(w,'确认当前评分').trigger('click');await flushPromises();expect(w.text()).toContain('版本已变化');expect(sessionStorage.length).toBe(1);
  await button(w,'读取最新版本并撤销未接受请求').trigger('click');await flushPromises();expect(sessionStorage.length).toBe(0);expect(getReviewAnswer).toHaveBeenCalledTimes(2);expect(confirmReviewScore).toHaveBeenCalledTimes(1);
});
it('paginates students and history with bounded cursors while effective results remain visible',async()=>{
  vi.mocked(getReviewRoster).mockResolvedValueOnce({data:{...roster,nextRecordId:9}}).mockResolvedValueOnce({data:roster}).mockResolvedValueOnce({data:roster});
  const w=render();await flushPromises();await button(w,'下一页学生').trigger('click');await flushPromises();expect(getReviewRoster).toHaveBeenLastCalledWith(21,9);
  await button(w,'上一页学生').trigger('click');await flushPromises();expect(getReviewRoster).toHaveBeenLastCalledWith(21,0);
  const first={id:score,kind:'SCORE',version:1,completedAt:'now',provider:'test',model:'test',score:detail.score};
  const second={...first,id:transcript,kind:'TRANSCRIBE',score:null};
  vi.mocked(getReviewAnswer).mockResolvedValueOnce({data:{...detail,history:[first],nextResultId:score}}).mockResolvedValueOnce({data:{...detail,history:[second],nextResultId:null}});
  await open(w);await button(w,'更多历史结果').trigger('click');await flushPromises();expect(getReviewAnswer).toHaveBeenLastCalledWith(21,attempt,answer,score);expect(w.text()).toContain('评分 v1');expect(w.text()).toContain('转写 v1');expect(w.text()).toContain('0 / 10');
});
it('handles history changes and read failures without replacing original request or pretending a new grade',async()=>{
  const first={id:score,kind:'SCORE',version:1,completedAt:'now',provider:'test',model:'test',score:detail.score};
  vi.mocked(getReviewAnswer).mockResolvedValueOnce({data:{...detail,history:[first],nextResultId:score}}).mockRejectedValueOnce(new Error('历史读取失败')).mockResolvedValueOnce({data:{...detail,revision:3,history:[first],nextResultId:null}});
  const w=render();await open(w);await button(w,'更多历史结果').trigger('click');await flushPromises();expect(w.text()).toContain('历史读取失败');expect(w.text()).toContain('0 / 10');
  await button(w,'更多历史结果').trigger('click');await flushPromises();expect(w.text()).toContain('有效版本已变化');expect(w.text()).toContain('评阅版本 3');expect(confirmReviewScore).not.toHaveBeenCalled();
});
it('shows not-started and pending-original states without opening a fake answer',async()=>{
  vi.mocked(getReviewRoster).mockResolvedValue({data:{...roster,rows:[{...roster.rows[0],attemptId:null,state:'NO_PREPARED_ATTEMPT'}]}});
  const w=render();await flushPromises();await w.find('[data-testid="review-student"]').trigger('click');expect(w.text()).toContain('无原回答可评阅');expect(getReviewInterview).not.toHaveBeenCalled();
  vi.mocked(getReviewRoster).mockResolvedValue({data:roster});await button(w,'刷新考核记录').trigger('click');await flushPromises();
  vi.mocked(getReviewInterview).mockResolvedValue({data:{...interview,submitted:false,submittedAt:null,state:'UPLOAD_PENDING',answers:[{...interview.answers[0],scoring:{state:'NEEDS_REVIEW',errorCode:'ORIGINAL_MISSING'}}]}});
  await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();expect(w.text()).toContain('不要求学生重答');expect(w.text()).toContain('待补传或异常核验');expect(w.find('[data-testid="review-answer"]').attributes('disabled')).toBeDefined();
});
it('validates returned scopes, handles interview/answer failures and refuses invalid assessment ids',async()=>{
  const invalid=render(NaN);await flushPromises();expect(getReviewRoster).not.toHaveBeenCalled();expect(invalid.text()).toContain('无法核验');
  const w=render();await flushPromises();vi.mocked(getReviewInterview).mockRejectedValueOnce(new Error('原尝试异常'));await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();expect(w.text()).toContain('原尝试异常');
  vi.mocked(getReviewInterview).mockResolvedValueOnce({data:{...interview,actorId:9}});await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="review-answer"]').exists()).toBe(false);
  vi.mocked(getReviewInterview).mockResolvedValue({data:interview});vi.mocked(getReviewAnswer).mockResolvedValueOnce({data:{...detail,answerId:score}});await open(w);expect(w.text()).toContain('无法核验');expect(w.text()).not.toContain('冻结标准');
});
it('retains original regrade intent and rejects mismatched receipts before clearing it',async()=>{
  let w=render();await open(w);await w.find('textarea').setValue('原重评依据');vi.mocked(requestReviewRegrade).mockRejectedValueOnce(new Error('后台连接中断'));
  await button(w,'请求重评').trigger('click');await flushPromises();const command=vi.mocked(requestReviewRegrade).mock.calls[0];expect(button(w,'恢复原重评请求')).toBeDefined();
  w.unmount();mounted=[];w=render();await open(w);expect(requestReviewRegrade).toHaveBeenCalledTimes(1);
  vi.mocked(requestReviewRegrade).mockResolvedValueOnce({data:{jobId:score,answerId:score,revision:3,transcriptId:transcript}});
  await button(w,'恢复原重评请求').trigger('click');await flushPromises();expect(w.text()).toContain('无法核验');expect(sessionStorage.length).toBe(1);
  vi.mocked(requestReviewRegrade).mockResolvedValueOnce({data:{jobId:score,answerId:answer,revision:3,transcriptId:transcript}});
  await button(w,'恢复原重评请求').trigger('click');await flushPromises();expect(vi.mocked(requestReviewRegrade).mock.calls[2]).toEqual(command);expect(sessionStorage.length).toBe(0);
});
it('permits a human decision on a complete original without successful models and never invents transcript evidence',async()=>{
  vi.mocked(getReviewAnswer).mockResolvedValue({data:{...detail,revision:0,score:null,scoreId:null,transcript:null,transcriptId:null}});
  const w=render();await open(w);await w.find('#review-manual-score').setValue('8.5');await w.find('textarea').setValue('听审原录音依据');
  vi.mocked(adjustReviewScore).mockResolvedValue({data:{answerId:answer,revision:1,manualScoreId:score}});
  await button(w,'保存人工评分').trigger('click');await flushPromises();
  expect(adjustReviewScore).toHaveBeenCalledWith(21,attempt,answer,expect.objectContaining({expectedRevision:0,score:8.5,reason:'听审原录音依据'}));
  expect(w.text()).toContain('人工评分已保存');expect(confirmReviewScore).not.toHaveBeenCalled();expect(requestReviewRegrade).not.toHaveBeenCalled();
});
it('restores exactly the same human adjustment after response loss and rejects a mismatched receipt',async()=>{
  let w=render();await open(w);await w.find('#review-manual-score').setValue('8');await w.find('textarea').setValue('原人工依据');vi.mocked(adjustReviewScore).mockRejectedValueOnce(new Error('人工评分响应丢失'));
  await button(w,'保存人工评分').trigger('click');await flushPromises();const original=vi.mocked(adjustReviewScore).mock.calls[0];expect(sessionStorage.length).toBe(1);
  w.unmount();mounted=[];w=render();await open(w);expect(adjustReviewScore).toHaveBeenCalledTimes(1);expect(w.find('#review-manual-score').element).toHaveProperty('value','8');
  vi.mocked(adjustReviewScore).mockResolvedValueOnce({data:{answerId:score,revision:3,manualScoreId:score}});await button(w,'恢复原人工评分请求').trigger('click');await flushPromises();expect(sessionStorage.length).toBe(1);
  vi.mocked(adjustReviewScore).mockResolvedValueOnce({data:{answerId:answer,revision:3,manualScoreId:score}});await button(w,'恢复原人工评分请求').trigger('click');await flushPromises();expect(vi.mocked(adjustReviewScore).mock.calls[2]).toEqual(original);expect(sessionStorage.length).toBe(0);
});
it('shows the effective human origin, operator and reason while keeping the old model score in history',async()=>{
  const manual={id:score,answerId:answer,version:1,revision:3,questionId:answer,maxScore:10,score:8.5,reason:'人工核验理由',recordedBy:7,recordedAt:'now',audioSha256:'a'.repeat(64),transcriptId:transcript,scoreId:score,previousManualScoreId:null};
  vi.mocked(getReviewAnswer).mockResolvedValue({data:{...detail,revision:3,confirmed:true,manualScore:manual}});
  const w=render();await open(w);expect(w.find('[data-testid="review-score"]').text()).toContain('8.5 / 10');expect(w.text()).toContain('人工评分');expect(w.text()).toContain('人工核验理由');expect(w.text()).toContain('操作人 7');
  expect(button(w,'确认当前评分').attributes('disabled')).toBeDefined();
  vi.mocked(getReviewManualHistory).mockResolvedValueOnce({data:{current:manual,history:[manual],nextManualScoreId:score}}).mockResolvedValueOnce({data:{current:manual,history:[{...manual,id:transcript,version:2,score:9}],nextManualScoreId:null}});
  await button(w,'查看人工评分历史').trigger('click');await flushPromises();expect(w.text()).toContain('人工评分 v1');await button(w,'更多人工评分历史').trigger('click');await flushPromises();expect(getReviewManualHistory).toHaveBeenLastCalledWith(21,attempt,answer,score);expect(w.text()).toContain('人工评分 v2');
});
it('rejects missing reason, out-of-range or imprecise human input without saving a command',async()=>{
  const w=render();await open(w);await w.find('#review-manual-score').setValue('8');await button(w,'保存人工评分').trigger('click');expect(adjustReviewScore).not.toHaveBeenCalled();
  await w.find('textarea').setValue('人工依据');for(const value of ['','-1','10.01','8.001','oops']){await w.find('#review-manual-score').setValue(value);await button(w,'保存人工评分').trigger('click');expect(adjustReviewScore).not.toHaveBeenCalled();expect(sessionStorage.length).toBe(0);}
});
it('keeps current content and original human intent when manual history fails or returns another answer',async()=>{
  const w=render();await open(w);await w.find('#review-manual-score').setValue('8');await w.find('textarea').setValue('原人工依据');vi.mocked(adjustReviewScore).mockRejectedValueOnce(new Error('响应不明'));
  await button(w,'保存人工评分').trigger('click');await flushPromises();const saved=sessionStorage.getItem(sessionStorage.key(0)!);
  vi.mocked(getReviewManualHistory).mockRejectedValueOnce(new Error('人工历史暂不可用'));
  await button(w,'查看人工评分历史').trigger('click');await flushPromises();expect(w.text()).toContain('人工历史暂不可用');expect(w.text()).toContain('0 / 10');expect(sessionStorage.getItem(sessionStorage.key(0)!)).toBe(saved);
  const other={id:score,answerId:score,version:1,revision:3,questionId:answer,maxScore:10,score:8,reason:'不应显示的他人依据',recordedBy:7,recordedAt:'now',audioSha256:'a'.repeat(64),transcriptId:null,scoreId:null,previousManualScoreId:null};
  vi.mocked(getReviewManualHistory).mockResolvedValueOnce({data:{current:null,history:[other],nextManualScoreId:null}});
  await button(w,'查看人工评分历史').trigger('click');await flushPromises();expect(w.text()).toContain('无法核验');expect(w.text()).not.toContain('不应显示的他人依据');expect(adjustReviewScore).toHaveBeenCalledTimes(1);
});
it('refuses human POST if the original command cannot persist before sending',async()=>{
  const w=render();await open(w);await w.find('#review-manual-score').setValue('8');await w.find('textarea').setValue('人工依据');
  vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('storage blocked');});
  try{await button(w,'保存人工评分').trigger('click');await flushPromises();expect(adjustReviewScore).not.toHaveBeenCalled();expect(w.text()).toContain('保存');}finally{vi.restoreAllMocks();}
});
const newerAttempt='55555555-5555-4555-8555-555555555555';
const attemptsPage={actorId:7,assessmentId:21,studentId:8,recordId:9,activeAttemptId:newerAttempt,attempts:[
  {id:newerAttempt,attemptNo:2,state:'IN_PROGRESS',startedAt:'2026-10-05T01:00:00Z',submittedAt:null,current:true},
  {id:attempt,attemptNo:1,state:'SUBMITTED',startedAt:'2026-10-04T00:00:00Z',submittedAt:interview.submittedAt,current:false},
],nextAttemptNo:null};
it('opens real scoped attempt history and reads the historical answer without any writing controls',async()=>{
  vi.mocked(getReviewAttempts).mockResolvedValue({data:attemptsPage});vi.mocked(getReviewInterview).mockResolvedValue({data:{...interview,current:false}});
  const w=render();await flushPromises();await w.find('[data-testid="review-attempt-history"]').trigger('click');await flushPromises();
  expect(getReviewAttempts).toHaveBeenCalledWith(21,8,0);expect(w.findAll('[data-testid="review-attempt"]')).toHaveLength(2);
  await w.findAll('[data-testid="review-attempt"]')[1].trigger('click');await flushPromises();await w.find('[data-testid="review-answer"]').trigger('click');await flushPromises();
  expect(w.text()).toContain('历史尝试，只读');expect(w.text()).toContain('冻结标准');expect(w.find('#review-manual-score').exists()).toBe(false);expect(w.find('textarea').exists()).toBe(false);
  expect(w.findAll('button').some(b=>['保存人工评分','确认当前评分','请求重评'].includes(b.text()))).toBe(false);
  expect(confirmReviewScore).not.toHaveBeenCalled();expect(adjustReviewScore).not.toHaveBeenCalled();expect(requestReviewRegrade).not.toHaveBeenCalled();expect(sessionStorage.length).toBe(0);
});
it('does not clear or restore old pending write intentions when their attempt has become historical',async()=>{
  const w=render();await open(w);await w.find('#review-manual-score').setValue('8');await w.find('textarea').setValue('未知原评分请求');vi.mocked(adjustReviewScore).mockRejectedValueOnce(new Error('响应丢失'));
  await button(w,'保存人工评分').trigger('click');await flushPromises();const saved=sessionStorage.getItem(sessionStorage.key(0)!);
  vi.mocked(getReviewInterview).mockResolvedValue({data:{...interview,current:false}});await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();await w.find('[data-testid="review-answer"]').trigger('click');await flushPromises();
  expect(w.text()).toContain('历史尝试，只读');expect(w.text()).not.toContain('恢复原人工评分请求');expect(sessionStorage.getItem(sessionStorage.key(0)!)).toBe(saved);expect(adjustReviewScore).toHaveBeenCalledTimes(1);
});
it('paginates attempt history without hiding the selected original or duplicating attempts',async()=>{
  const first=[{...attemptsPage.attempts[0],attemptNo:3},{...attemptsPage.attempts[1],attemptNo:2}];
  const oldest={...attemptsPage.attempts[1],id:'66666666-6666-4666-8666-666666666666'};
  vi.mocked(getReviewAttempts).mockResolvedValueOnce({data:{...attemptsPage,attempts:first,nextAttemptNo:2}}).mockResolvedValueOnce({data:{...attemptsPage,attempts:[oldest]}});
  vi.mocked(getReviewInterview).mockResolvedValueOnce({data:{...interview,attemptNo:2,current:false}});
  const w=render();await flushPromises();await w.find('[data-testid="review-attempt-history"]').trigger('click');await flushPromises();
  await w.findAll('[data-testid="review-attempt"]')[1].trigger('click');await flushPromises();await w.find('[data-testid="review-answer"]').trigger('click');await flushPromises();expect(w.text()).toContain('冻结标准');
  await button(w,'更多尝试历史').trigger('click');await flushPromises();expect(getReviewAttempts).toHaveBeenLastCalledWith(21,8,2);expect(w.findAll('[data-testid="review-attempt"]')).toHaveLength(3);expect(w.text()).toContain('冻结标准');expect(w.text()).toContain('0 / 10');
});
it('ignores late history responses after changing assessment and never restores an old scope',async()=>{
  let resolve!:(v:{data:typeof attemptsPage})=>void;vi.mocked(getReviewAttempts).mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
  const w=render();await flushPromises();await w.find('[data-testid="review-attempt-history"]').trigger('click');
  vi.mocked(getReviewRoster).mockResolvedValueOnce({data:{...roster,assessmentId:22,rows:[]}});await w.setProps({assessmentId:22});await flushPromises();resolve({data:attemptsPage});await flushPromises();expect(w.find('[data-testid="review-attempt"]').exists()).toBe(false);expect(w.text()).not.toContain('学生甲');
});
it('rejects mismatched historical ownership, actor and current flags without exposing supplied content',async()=>{
  for(const changed of [{studentId:9},{actorId:9},{recordId:10},{attempts:[{...attemptsPage.attempts[1],current:true}]}]){
    vi.mocked(getReviewAttempts).mockResolvedValueOnce({data:{...attemptsPage,...changed}});
    const w=render();await flushPromises();await w.find('[data-testid="review-attempt-history"]').trigger('click');await flushPromises();expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="review-attempt"]').exists()).toBe(false);w.unmount();
  }
});
it('shows no-attempt and history read errors without fabricating an interview or grade',async()=>{
  vi.mocked(getReviewAttempts).mockResolvedValueOnce({data:{...attemptsPage,activeAttemptId:null,attempts:[]}}).mockRejectedValueOnce(new Error('历史权限已撤销'));
  const w=render();await flushPromises();await w.find('[data-testid="review-attempt-history"]').trigger('click');await flushPromises();expect(w.text()).toContain('尚无预置尝试历史');expect(getReviewInterview).not.toHaveBeenCalled();
  await w.find('[data-testid="review-attempt-history"]').trigger('click');await flushPromises();expect(w.text()).toContain('历史权限已撤销');expect(w.find('[data-testid="review-attempt"]').exists()).toBe(false);
});
it('rejects nonempty historical attempts that lack their original active anchor',async()=>{
  vi.mocked(getReviewAttempts).mockResolvedValueOnce({data:{...attemptsPage,activeAttemptId:null,attempts:[attemptsPage.attempts[1]]}});
  const w=render();await flushPromises();await w.find('[data-testid="review-attempt-history"]').trigger('click');await flushPromises();expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="review-attempt"]').exists()).toBe(false);
});
it('uses the same readable business states in attempt history rather than raw internal values',async()=>{
  vi.mocked(getReviewAttempts).mockResolvedValueOnce({data:attemptsPage});
  const w=render();await flushPromises();await w.find('[data-testid="review-attempt-history"]').trigger('click');await flushPromises();expect(w.text()).toContain('面试进行中');expect(w.text()).not.toContain('IN_PROGRESS');
});
it('offers explicit original supplement handling only in current incomplete attempts, never in historical or submitted originals',async()=>{
  const w=render();await flushPromises();vi.mocked(getReviewInterview).mockResolvedValue({data:{...interview,submitted:false,state:'REQUIRES_REVIEW',answers:[{...interview.answers[0],state:'UPLOAD_PENDING'}]}});
  await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();expect(w.find('[data-testid="review-supplement"]').exists()).toBe(true);
  vi.mocked(getReviewInterview).mockResolvedValue({data:{...interview,submitted:false,current:false}});await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();expect(w.find('[data-testid="review-supplement"]').exists()).toBe(false);
  vi.mocked(getReviewInterview).mockResolvedValue({data:interview});await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();expect(w.find('[data-testid="review-supplement"]').exists()).toBe(false);
});
it('clears stale writable review scope if the active attempt changes between historical pages',async()=>{
  const first=[{...attemptsPage.attempts[0],attemptNo:3},{...attemptsPage.attempts[1],attemptNo:2}],next='66666666-6666-4666-8666-666666666666';
  vi.mocked(getReviewAttempts).mockResolvedValueOnce({data:{...attemptsPage,attempts:first,nextAttemptNo:2}}).mockResolvedValueOnce({data:{...attemptsPage,activeAttemptId:next,attempts:[attemptsPage.attempts[1]]}});
  vi.mocked(getReviewInterview).mockResolvedValueOnce({data:interview}).mockResolvedValueOnce({data:{...interview,attemptId:newerAttempt,attemptNo:3}});
  const w=render();await open(w);await w.find('#review-manual-score').setValue('8');await w.find('textarea').setValue('未知原请求');vi.mocked(adjustReviewScore).mockRejectedValueOnce(new Error('未知响应'));
  await button(w,'保存人工评分').trigger('click');await flushPromises();const saved=sessionStorage.getItem(sessionStorage.key(0)!);
  await w.find('[data-testid="review-attempt-history"]').trigger('click');await flushPromises();await w.findAll('[data-testid="review-attempt"]')[0].trigger('click');await flushPromises();await w.find('[data-testid="review-answer"]').trigger('click');await flushPromises();expect(w.find('#review-manual-score').exists()).toBe(true);
  await button(w,'更多尝试历史').trigger('click');await flushPromises();expect(w.text()).toContain('无法核验');expect(w.find('#review-manual-score').exists()).toBe(false);expect(w.find('[data-testid="review-attempt"]').exists()).toBe(false);expect(sessionStorage.getItem(sessionStorage.key(0)!)).toBe(saved);expect(adjustReviewScore).toHaveBeenCalledTimes(1);
});
it('offers read-only original declaration evidence before full submission without enabling grading or voluntary redo',async()=>{
  vi.mocked(getReviewInterview).mockResolvedValueOnce({data:{...interview,submitted:false,state:'REQUIRES_REVIEW',answers:[{...interview.answers[0],state:'REQUIRES_REVIEW'}]}});
  const w=render();await flushPromises();await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();
  expect(w.find('[data-testid="review-evidence"]').exists()).toBe(true);expect(w.find('[data-testid="review-evidence"]').attributes('disabled')).toBeUndefined();
  expect(w.find('[data-testid="review-answer"]').attributes('disabled')).toBeDefined();expect(w.find('#review-manual-score').exists()).toBe(false);
  expect(w.text()).toContain('需人工核验');expect(adjustReviewScore).not.toHaveBeenCalled();
});
it('labels a started but unended original question without exposing the internal RECORDING state as a student outcome',async()=>{
  vi.mocked(getReviewInterview).mockResolvedValueOnce({data:{...interview,submitted:false,state:'IN_PROGRESS',answers:[{...interview.answers[0],state:'RECORDING'}]}});
  const w=render();await flushPromises();await w.find('[data-testid="review-student"]').trigger('click');await flushPromises();
  expect(w.text()).toContain('本题已授权，尚未登记结束');expect(w.text()).not.toContain('RECORDING');expect(w.find('[data-testid="review-answer"]').attributes('disabled')).toBeDefined();
});
