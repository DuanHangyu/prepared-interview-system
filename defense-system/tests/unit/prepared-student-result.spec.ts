import {mount,flushPromises} from '@vue/test-utils';
import {beforeEach,afterEach,it,expect,vi} from 'vitest';
import PreparedStudentResult from '@/views/prepared/PreparedStudentResult.vue';
import {getPreparedStudentResult,getPreparedStudentResultAnswer,type StudentResultState} from '@/api/preparedResult';
vi.mock('@/api/preparedResult',()=>({getPreparedStudentResult:vi.fn(),getPreparedStudentResultAnswer:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',answer='22222222-2222-4222-8222-222222222222';
const summary={studentId:8,assessmentId:21,theme:'预置结果',prepared:true,state:'READY' as const,attemptId:attempt,resultVersion:'a'.repeat(64),
  completedAt:'2026-10-05T00:00:00Z',totalScore:8.75,totalMaxScore:10,passScore:6,
  answers:[{answerId:answer,ordinal:1,maxScore:10,score:8.75,scoringSource:'TEACHER' as const,version:1,confirmed:true}]};
const detail={studentId:8,assessmentId:21,attemptId:attempt,resultVersion:summary.resultVersion,answerId:answer,ordinal:1,
  body:'<script>原题文字</script>',maxScore:10,score:8.75,scoringSource:'TEACHER' as const,version:1,confirmed:true,feedback:null,transcript:null};
let wrappers:ReturnType<typeof mount>[]=[];
const render=(props={})=>{const w=mount(PreparedStudentResult,{props:{studentId:8,assessmentId:21,...props}});wrappers.push(w);return w;};
const button=(w:ReturnType<typeof mount>,label:string)=>w.findAll('button').find(b=>b.text()===label)!;
const noResults=(state:StudentResultState,prepared=true)=>({...summary,prepared,state,attemptId:state==='NOT_STARTED'||!prepared?null:attempt,resultVersion:null,
  completedAt:null,totalScore:null,totalMaxScore:null,passScore:null,answers:[]});
beforeEach(()=>{vi.clearAllMocks();vi.mocked(getPreparedStudentResult).mockResolvedValue({data:structuredClone(summary)});vi.mocked(getPreparedStudentResultAnswer).mockResolvedValue({data:structuredClone(detail)});});
afterEach(()=>{for(const w of wrappers)w.unmount();wrappers=[];});
it('shows exact decimal and honest human provenance without fabricated transcript, camera analysis or legacy score',async()=>{
  const w=render();await flushPromises();expect(w.text()).toContain('8.75 / 10');expect(w.text()).toContain('教师人工评分');
  expect(getPreparedStudentResult).toHaveBeenCalledWith(21,expect.any(AbortSignal));
  await button(w,'查看第 1 题结果').trigger('click');await flushPromises();
  expect(getPreparedStudentResultAnswer).toHaveBeenCalledWith(21,answer,summary.resultVersion,expect.any(AbortSignal));
  expect(w.text()).toContain('<script>原题文字</script>');expect(w.find('script').exists()).toBe(false);expect(w.text()).toContain('暂无有效转写');
  expect(w.text()).not.toContain('视频分析');expect(w.find('audio').exists()).toBe(false);expect(w.find('textarea').exists()).toBe(false);
});
it('renders original model transcription and feedback only after a matching current result version',async()=>{
  vi.mocked(getPreparedStudentResult).mockResolvedValueOnce({data:{...summary,answers:[{...summary.answers[0],scoringSource:'MODEL',confirmed:false}]}});
  vi.mocked(getPreparedStudentResultAnswer).mockResolvedValueOnce({data:{...detail,scoringSource:'MODEL',confirmed:false,feedback:'<img src=x onerror=alert(1)>评分反馈',transcript:{sentences:[{startMs:0,endMs:1000,text:'原回答'}]}}});
  const w=render();await flushPromises();await button(w,'查看第 1 题结果').trigger('click');await flushPromises();
  expect(w.text()).toContain('模型评分，尚未核分');expect(w.text()).toContain('原回答');expect(w.text()).toContain('评分反馈');expect(w.find('img').exists()).toBe(false);
});
it('shows hidden, processing, unsubmitted and legacy states without sending answer reads or displaying any score',async()=>{
  for(const state of ['HIDDEN','PROCESSING','NOT_STARTED','IN_PROGRESS','UPLOAD_PENDING','REQUIRES_REVIEW','CANCELLED','LEGACY'] as const){
    vi.mocked(getPreparedStudentResult).mockResolvedValueOnce({data:noResults(state,state!=='LEGACY')});const w=render();await flushPromises();
    expect(w.find('[data-testid="result-total"]').exists()).toBe(false);expect(w.findAll('[data-testid="result-answer"]')).toHaveLength(0);
    expect(w.text()).not.toContain('8.75');expect(w.findAll('[role="status"]').map(x=>x.text()).join(' ')).not.toContain('考核失败');w.unmount();
  }
  expect(getPreparedStudentResultAnswer).not.toHaveBeenCalled();
});
it('clears grades immediately during refresh and after permission or connection failures',async()=>{
  const w=render();await flushPromises();vi.mocked(getPreparedStudentResult).mockRejectedValueOnce(new Error('结果读取失败'));
  await button(w,'刷新结果').trigger('click');expect(w.find('[data-testid="result-total"]').exists()).toBe(false);await flushPromises();
  expect(w.get('[role="alert"]').text()).toContain('结果读取失败');expect(button(w,'刷新结果').attributes('disabled')).toBeUndefined();
  vi.mocked(getPreparedStudentResult).mockResolvedValueOnce({data:noResults('HIDDEN')});await button(w,'刷新结果').trigger('click');await flushPromises();expect(w.text()).toContain('教师尚未开放结果');
});
it('rejects wrong owner, partial grades, duplicate ordinals, unknown states and malformed result boundaries',async()=>{
  for(const change of [{studentId:9},{assessmentId:22},{state:'UNKNOWN'},{prepared:false},{attemptId:'bad'},{resultVersion:'bad'},
    {completedAt:'bad'},{totalScore:9},{totalScore:-1},{totalMaxScore:11},{passScore:11},{answers:[]},
    {answers:[{...summary.answers[0],score:11}]},{answers:[{...summary.answers[0],ordinal:2}]},{answers:[{...summary.answers[0],version:0}]},
    {answers:[{...summary.answers[0],scoringSource:'FAKE'}]},{answers:[summary.answers[0],summary.answers[0]]},
    {...noResults('HIDDEN'),totalScore:8.75},{...noResults('PROCESSING'),answers:summary.answers}]){
    vi.mocked(getPreparedStudentResult).mockResolvedValueOnce({data:{...summary,...change} as typeof summary});const w=render();await flushPromises();
    expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="result-total"]').exists()).toBe(false);w.unmount();
  }
});
it('rejects cross-answer, changed-version or malformed detail and never retains its supplied text',async()=>{
  for(const change of [{studentId:9},{assessmentId:22},{attemptId:answer},{answerId:attempt},{resultVersion:'b'.repeat(64)},{ordinal:2},
    {score:9},{maxScore:11},{scoringSource:'MODEL'},{version:2},{confirmed:false},{body:''},{transcript:{sentences:[]}},
    {transcript:{sentences:[{startMs:100,endMs:50,text:'不得显示'}]}},{transcript:{sentences:[{startMs:0,endMs:1,text:''}]}},{feedback:42},{feedback:'教师内部理由，不应展示'}]){
    vi.mocked(getPreparedStudentResultAnswer).mockResolvedValueOnce({data:{...detail,...change} as typeof detail});const w=render();await flushPromises();await button(w,'查看第 1 题结果').trigger('click');await flushPromises();
    expect(w.text()).toContain('无法核验');expect(w.text()).not.toContain('原题文字');expect(w.find('[data-testid="result-detail"]').exists()).toBe(false);w.unmount();
  }
});
it('does not accept a fabricated model score with a missing original transcript or model feedback',async()=>{
  for(const change of [{transcript:null},{feedback:null}]){
    vi.mocked(getPreparedStudentResult).mockResolvedValueOnce({data:{...summary,answers:[{...summary.answers[0],scoringSource:'MODEL',confirmed:false}]}});
    vi.mocked(getPreparedStudentResultAnswer).mockResolvedValueOnce({data:{...detail,scoringSource:'MODEL',confirmed:false,feedback:'合成反馈',transcript:{sentences:[{startMs:0,endMs:1,text:'原回答'}]},...change}});
    const w=render();await flushPromises();await button(w,'查看第 1 题结果').trigger('click');await flushPromises();expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="result-detail"]').exists()).toBe(false);w.unmount();
  }
});
it('ignores late summaries and details after changing the authenticated owner or assessment',async()=>{
  let first!:(v:any)=>void;vi.mocked(getPreparedStudentResult).mockImplementationOnce(()=>new Promise(r=>{first=r;}));
  const w=render();await flushPromises();vi.mocked(getPreparedStudentResult).mockResolvedValueOnce({data:{...summary,assessmentId:22}});await w.setProps({assessmentId:22});await flushPromises();first({data:summary});await flushPromises();
  expect(getPreparedStudentResult).toHaveBeenLastCalledWith(22,expect.any(AbortSignal));
  let late!:(v:any)=>void;vi.mocked(getPreparedStudentResultAnswer).mockImplementationOnce(()=>new Promise(r=>{late=r;}));await button(w,'查看第 1 题结果').trigger('click');await flushPromises();
  vi.mocked(getPreparedStudentResult).mockResolvedValueOnce({data:{...noResults('HIDDEN'),studentId:9,assessmentId:22}});await w.setProps({studentId:9});await flushPromises();late({data:{...detail,assessmentId:22}});await flushPromises();
  expect(w.text()).not.toContain('原题文字');expect(w.text()).not.toContain('8.75');
});
it('bounds invalid identities and unmounts pending work without publishing late content',async()=>{
  const invalid=render({studentId:0});await flushPromises();expect(getPreparedStudentResult).not.toHaveBeenCalled();expect(invalid.text()).toContain('无法核验');
  let done!:(v:any)=>void;vi.mocked(getPreparedStudentResult).mockImplementationOnce(()=>new Promise(r=>{done=r;}));const w=render();await flushPromises();const signal=vi.mocked(getPreparedStudentResult).mock.calls[0][1];w.unmount();expect(signal?.aborted).toBe(true);done({data:summary});await flushPromises();
});
