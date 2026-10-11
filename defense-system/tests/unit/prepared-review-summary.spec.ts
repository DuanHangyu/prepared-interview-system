import {mount,flushPromises} from '@vue/test-utils';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import PreparedReviewSummary from '@/views/prepared/PreparedReviewSummary.vue';
import {getReviewSummary,type ReviewSummary} from '@/api/preparedReview';
vi.mock('@/api/preparedReview',()=>({getReviewSummary:vi.fn(),getReviewReport:vi.fn(),exportReviewReport:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',answer='22222222-2222-4222-8222-222222222222';
const value:ReviewSummary={actorId:7,assessmentId:21,studentId:8,studentName:'学生甲',theme:'冻结试卷',attemptId:attempt,attemptNo:1,current:true,showResult:false,
  state:'READY',resultVersion:'a'.repeat(64),completedAt:'2026-10-05T00:00:00Z',totalScore:8.75,totalMaxScore:10,passScore:6,
  answers:[{answerId:answer,ordinal:1,maxScore:10,score:8.75,scoringSource:'TEACHER',version:1,confirmed:true}]};
const empty=(state:Exclude<ReviewSummary['state'],'READY'>):ReviewSummary=>({...value,state,resultVersion:null,completedAt:null,totalScore:null,totalMaxScore:null,passScore:null,answers:[]});
let wrappers:ReturnType<typeof mount>[]=[];
function render(props={}){const w=mount(PreparedReviewSummary,{props:{actorId:7,assessmentId:21,studentId:8,attemptId:attempt,current:true,refreshVersion:0,...props}});wrappers.push(w);return w;}
beforeEach(()=>{vi.resetAllMocks();vi.mocked(getReviewSummary).mockResolvedValue({data:structuredClone(value)});});
afterEach(()=>{for(const w of wrappers)w.unmount();wrappers=[];});
it('shows exact decimal effective human scores without publishing results or inventing transcript or camera analysis',async()=>{
  const w=render();await flushPromises();expect(w.get('[data-testid="review-summary-total"]').text()).toContain('8.75 / 10');expect(w.text()).toContain('通过分数：6');expect(w.text()).toContain('教师人工评分');expect(w.text()).toContain('尚未开放');expect(w.find('audio').exists()).toBe(false);expect(w.find('textarea').exists()).toBe(false);expect(getReviewSummary).toHaveBeenCalledWith(21,attempt,expect.any(AbortSignal));
});
it('preserves valid zero and separates unconfirmed model provenance from human confirmation',async()=>{
  vi.mocked(getReviewSummary).mockResolvedValueOnce({data:{...value,totalScore:0,showResult:true,answers:[{...value.answers[0],score:0,scoringSource:'MODEL',confirmed:false}]}});
  const w=render();await flushPromises();expect(w.get('[data-testid="review-summary-total"]').text()).toContain('0 / 10');expect(w.text()).toContain('尚未核分');expect(w.text()).toContain('已开放');
});
it('renders current and historical attempts honestly as read-only summaries',async()=>{
  vi.mocked(getReviewSummary).mockResolvedValueOnce({data:{...value,current:false}});const w=render({current:false});await flushPromises();expect(w.text()).toContain('历史尝试，只读');expect(w.find('[data-testid="review-summary-total"]').exists()).toBe(true);expect(w.findAll('button').map(b=>b.text())).toEqual(['刷新整场成绩','查看完整报告','导出原尝试报告']);
});
it('does not fabricate totals from incomplete processing or unsubmitted states',async()=>{
  for(const state of ['PROCESSING','IN_PROGRESS','UPLOAD_PENDING','REQUIRES_REVIEW','CANCELLED'] as const){vi.mocked(getReviewSummary).mockResolvedValueOnce({data:empty(state)});const w=render();await flushPromises();expect(w.find('[data-testid="review-summary-total"]').exists()).toBe(false);expect(w.text()).not.toContain('8.75');expect(w.text()).toContain('不代表零分');w.unmount();}
});
it('distinguishes an in-flight read from the persistent incomplete-result business status',async()=>{
  let resolve!:(response:{data:ReviewSummary})=>void;
  vi.mocked(getReviewSummary).mockImplementationOnce(()=>new Promise(done=>{resolve=done;}));
  const w=render();await flushPromises();
  expect(w.findAll('[role="status"]')).toHaveLength(1);expect(w.get('[role="status"]').text()).toContain('正在读取整场成绩');expect(w.get('button').attributes('disabled')).toBeDefined();
  resolve({data:empty('PROCESSING')});await flushPromises();
  expect(w.findAll('[role="status"]')).toHaveLength(1);expect(w.get('[role="status"]').text()).toBe('成绩尚未全部就绪；不以旧版整数成绩或部分题目的成绩补成总分。');
  expect(w.get('button').attributes('disabled')).toBeUndefined();expect(w.find('[role="alert"]').exists()).toBe(false);expect(w.find('[data-testid="review-summary-total"]').exists()).toBe(false);
});
it('rejects wrong identity, changed active attempt, unknown states and inconsistent aggregate proofs',async()=>{
  for(const change of [{actorId:9},{assessmentId:22},{studentId:9},{attemptId:answer},{attemptNo:0},{current:false},{showResult:'yes'},{state:'HIDDEN'},
    {resultVersion:'bad'},{completedAt:'bad'},{totalScore:9},{totalScore:-1},{totalMaxScore:11},{passScore:11},{answers:[]},
    {answers:[{...value.answers[0],ordinal:2}]},{answers:[{...value.answers[0],score:11}]},{answers:[{...value.answers[0],score:8.751}]},
    {answers:[{...value.answers[0],answerId:'bad'}]},{answers:[{...value.answers[0],version:0}]},{answers:[{...value.answers[0],scoringSource:'FAKE'}]},
    {answers:[{...value.answers[0],confirmed:false}]},{answers:[value.answers[0],value.answers[0]]},{...empty('PROCESSING'),totalScore:1},{...empty('IN_PROGRESS'),answers:value.answers}]){
    vi.mocked(getReviewSummary).mockResolvedValueOnce({data:{...value,...change} as typeof value});const w=render();await flushPromises();expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="review-summary-total"]').exists()).toBe(false);w.unmount();
  }
});
it('clears old grades on refresh, supports retry, and does not retain them after permission or network failure',async()=>{
  const w=render();await flushPromises();vi.mocked(getReviewSummary).mockRejectedValueOnce(new Error('权限已变化'));await w.get('button').trigger('click');expect(w.find('[data-testid="review-summary-total"]').exists()).toBe(false);await flushPromises();expect(w.get('[role="alert"]').text()).toContain('权限已变化');
  await w.get('button').trigger('click');await flushPromises();expect(w.find('[data-testid="review-summary-total"]').exists()).toBe(true);
});
it('cancels and ignores late responses after actor, scope or refreshed grading changes',async()=>{
  let late!:(v:any)=>void;vi.mocked(getReviewSummary).mockImplementationOnce(()=>new Promise(r=>{late=r;}));const w=render();await flushPromises();const firstSignal=vi.mocked(getReviewSummary).mock.calls[0][2];
  vi.mocked(getReviewSummary).mockResolvedValueOnce({data:{...value,actorId:9,totalScore:0,answers:[{...value.answers[0],score:0}]}});await w.setProps({actorId:9,refreshVersion:1});await flushPromises();expect(firstSignal?.aborted).toBe(true);late({data:value});await flushPromises();expect(w.get('[data-testid="review-summary-total"]').text()).toContain('0 / 10');expect(w.text()).not.toContain('8.75');
});
it('renders supplied names and themes as plain text, not HTML',async()=>{
  vi.mocked(getReviewSummary).mockResolvedValueOnce({data:{...value,theme:'<script>主题</script>',studentName:'<img src=x onerror=alert(1)>'}});const w=render();await flushPromises();expect(w.text()).toContain('<script>主题</script>');expect(w.find('script').exists()).toBe(false);expect(w.find('img').exists()).toBe(false);
});
it('rejects invalid request scopes before reading and aborts in-flight work on unmount',async()=>{
  const invalid=render({actorId:0});await flushPromises();expect(getReviewSummary).not.toHaveBeenCalled();expect(invalid.text()).toContain('无法核验');
  let done!:(v:any)=>void;vi.mocked(getReviewSummary).mockImplementationOnce(()=>new Promise(r=>{done=r;}));const w=render();await flushPromises();const signal=vi.mocked(getReviewSummary).mock.calls[0][2];w.unmount();expect(signal?.aborted).toBe(true);done({data:value});await flushPromises();
});
