import { mount,flushPromises } from '@vue/test-utils';
import { beforeEach,afterEach,expect,it,vi } from 'vitest';
import PreparedAnswerEvidence from '@/views/prepared/PreparedAnswerEvidence.vue';
import { getReviewEvidence } from '@/api/preparedReview';
vi.mock('@/api/preparedReview',()=>({getReviewEvidence:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',answer='22222222-2222-4222-8222-222222222222',question='33333333-3333-4333-8333-333333333333';
const segment='44444444-4444-4444-8444-444444444444';
const props={actorId:7,assessmentId:21,studentId:8,attemptId:attempt,answerId:answer,ordinal:1,current:true};
const value={actorId:7,assessmentId:21,studentId:8,attemptId:attempt,answerId:answer,current:true,attemptState:'IN_PROGRESS',attemptRevision:0,answerState:'REQUIRES_REVIEW',answerRevision:1,
  question:{questionId:question,ordinal:1,body:'<script>原题</script>',answerSeconds:120,maxScore:10,rubric:'冻结标准'},startedAt:'2026-10-05T00:00:00.000Z',answerDeadline:'2026-10-05T00:02:00.000Z',
  declaration:{manifestSha256:'a'.repeat(64),state:'REQUIRES_REVIEW',revision:1,effectiveEndedAt:'2026-10-05T00:00:10.000Z',acceptedAt:'2026-10-05T00:00:10.000Z',reviewReasons:['CAPTURE_INTERRUPTED'],byteSize:6,chunkCount:2,segmentCount:2},
  segments:[{id:segment,ordinal:1,mimeType:'audio/webm;codecs=opus',state:'INTERRUPTED',interruption:'DEVICE_ENDED',declaredStartedAt:1000,declaredEndedAt:2000,chunkCount:1,byteSize:3},
    {id:'55555555-5555-4555-8555-555555555555',ordinal:2,mimeType:'audio/webm;codecs=opus',state:'SEALED',interruption:null,declaredStartedAt:3000,declaredEndedAt:4000,chunkCount:1,byteSize:3}],nextSegmentOrdinal:null};
let mounted:ReturnType<typeof mount>[]=[];
const render=(changed={})=>{const w=mount(PreparedAnswerEvidence,{props:{...props,...changed}});mounted.push(w);return w;};
const button=(w:ReturnType<typeof mount>,label:string)=>w.findAll('button').find(b=>b.text()===label)!;
beforeEach(()=>{vi.clearAllMocks();vi.mocked(getReviewEvidence).mockResolvedValue({data:structuredClone(value)});});
afterEach(()=>{for(const w of mounted)w.unmount();mounted=[];});
it('shows exact original declaration and human readable faults without pretending cloud success or granting continuation',async()=>{
  const w=render();await flushPromises();expect(getReviewEvidence).toHaveBeenCalledWith(21,attempt,answer,0);
  expect(w.text()).toContain('录音采集有间断');expect(w.text()).toContain('麦克风采集结束');expect(w.text()).toContain('服务端登记');
  expect(w.text()).toContain('声明字节数：6');expect(w.text()).toContain('客户端声明时间');expect(w.text()).toContain('不能证明离线录制时间');
  expect(w.text()).not.toContain('原录音已完整提交');expect(w.text()).not.toContain('允许继续');expect(w.find('script').exists()).toBe(false);
  expect(w.find('audio').exists()).toBe(false);expect(w.find('textarea').exists()).toBe(false);
});
it('distinguishes not-yet-ended from empty interrupted recording without inventing evidence',async()=>{
  vi.mocked(getReviewEvidence).mockResolvedValueOnce({data:{...value,answerState:'RECORDING',answerRevision:0,declaration:null,segments:[]}});
  const w=render();await flushPromises();expect(w.text()).toContain('尚未收到原结束声明');expect(w.text()).not.toContain('无法确认有效录音');
  vi.mocked(getReviewEvidence).mockResolvedValueOnce({data:{...value,declaration:{...value.declaration,reviewReasons:['CAPTURE_INTERRUPTED','EMPTY_AUDIO'],byteSize:0,chunkCount:0,segmentCount:1},segments:[{...value.segments[0],chunkCount:0,byteSize:0}]}});
  await button(w,'刷新原结束声明').trigger('click');await flushPromises();expect(w.text()).toContain('无法确认有效录音');expect(w.text()).toContain('声明字节数：0');
});
it('shows historical evidence as read-only without any write controls',async()=>{
  vi.mocked(getReviewEvidence).mockResolvedValueOnce({data:{...value,current:false}});const w=render({current:false});await flushPromises();
  expect(w.text()).toContain('历史尝试，只读');expect(w.text()).toContain('冻结标准');expect(w.findAll('button').map(b=>b.text())).toEqual(['刷新原结束声明']);
});
it('rejects mismatched ownership, frozen question, revision and malformed declarations without exposing supplied text',async()=>{
  for(const change of [{actorId:9},{studentId:9},{assessmentId:22},{attemptId:answer},{answerId:attempt},{current:false},{question:{...value.question,ordinal:2}},
    {question:{...value.question,body:'',rubric:'不应展示'}},{answerRevision:0},{answerDeadline:'2026-10-05T00:02:01.000Z'},
    {declaration:{...value.declaration,reviewReasons:['UNKNOWN_REASON']}},{declaration:{...value.declaration,reviewReasons:['__proto__']}},{declaration:{...value.declaration,manifestSha256:'bad'}},
    {declaration:{...value.declaration,state:'UPLOAD_PENDING'}},{declaration:{...value.declaration,byteSize:67108865}},
    {declaration:{...value.declaration,acceptedAt:'bad'}},{declaration:{...value.declaration,effectiveEndedAt:'2026-10-05T00:00:09.000Z'}},
    {segments:[{...value.segments[0],ordinal:2},value.segments[1]]},{segments:[{...value.segments[0],interruption:'<script>bad</script>'},value.segments[1]]},
    {segments:[{...value.segments[0],interruption:'toString'},value.segments[1]]},{segments:[{...value.segments[0],chunkCount:10001},value.segments[1]]},{nextSegmentOrdinal:1}]){
    vi.mocked(getReviewEvidence).mockResolvedValueOnce({data:{...value,...change}});const w=render();await flushPromises();expect(w.text()).toContain('无法核验');
    expect(w.text()).not.toContain('冻结标准');expect(w.find('[data-testid="evidence-segment"]').exists()).toBe(false);w.unmount();
  }
});
it('ignores late content after an owner or original answer change and keeps reads scoped',async()=>{
  let resolve!:(v:{data:typeof value})=>void;vi.mocked(getReviewEvidence).mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
  const w=render();await flushPromises();vi.mocked(getReviewEvidence).mockResolvedValueOnce({data:{...value,answerId:question}});await w.setProps({answerId:question});await flushPromises();
  resolve({data:value});await flushPromises();expect(w.findAll('[data-testid="evidence-segment"]')).toHaveLength(2);expect(getReviewEvidence).toHaveBeenLastCalledWith(21,attempt,question,0);
  vi.mocked(getReviewEvidence).mockRejectedValueOnce(new Error('管理权限已撤销'));await button(w,'刷新原结束声明').trigger('click');await flushPromises();expect(w.text()).toContain('管理权限已撤销');expect(w.text()).not.toContain('冻结标准');
});
it('paginates only the same immutable declaration and rejects changes instead of combining two originals',async()=>{
  const all=Array.from({length:21},(_,i)=>({...value.segments[0],id:`${String(i+1).padStart(8,'0')}-4444-4444-8444-444444444444`,ordinal:i+1,declaredStartedAt:(i+1)*1000,declaredEndedAt:(i+1)*1000+500}));
  const first={...value,declaration:{...value.declaration,byteSize:63,chunkCount:21,segmentCount:21},segments:all.slice(0,20),nextSegmentOrdinal:20};
  vi.mocked(getReviewEvidence).mockResolvedValueOnce({data:first});const w=render();await flushPromises();expect(w.findAll('[data-testid="evidence-segment"]')).toHaveLength(20);
  vi.mocked(getReviewEvidence).mockResolvedValueOnce({data:{...first,segments:all.slice(20),nextSegmentOrdinal:null}});await button(w,'更多原录音分段').trigger('click');await flushPromises();
  expect(getReviewEvidence).toHaveBeenLastCalledWith(21,attempt,answer,20);expect(w.findAll('[data-testid="evidence-segment"]')).toHaveLength(21);
  vi.mocked(getReviewEvidence).mockResolvedValueOnce({data:first});await button(w,'刷新原结束声明').trigger('click');await flushPromises();
  vi.mocked(getReviewEvidence).mockResolvedValueOnce({data:{...first,declaration:{...first.declaration,manifestSha256:'b'.repeat(64)},segments:all.slice(20),nextSegmentOrdinal:null}});
  await button(w,'更多原录音分段').trigger('click');await flushPromises();expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="evidence-segment"]').exists()).toBe(false);
});
it('fails closed on invalid props and on read errors instead of loading forever or retaining a stale original',async()=>{
  const invalid=render({assessmentId:NaN});await flushPromises();expect(getReviewEvidence).not.toHaveBeenCalled();expect(invalid.text()).toContain('无法核验');
  vi.mocked(getReviewEvidence).mockRejectedValueOnce(new Error('服务暂不可用'));const w=render();await flushPromises();expect(w.text()).toContain('服务暂不可用');expect(button(w,'刷新原结束声明').attributes('disabled')).toBeUndefined();
});
