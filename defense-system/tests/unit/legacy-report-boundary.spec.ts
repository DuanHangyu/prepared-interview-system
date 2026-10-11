import { mount,flushPromises } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach,afterEach,expect,it,vi } from 'vitest';
import Report from '@/views/studentDashboard/studyDetail/index.vue';
const f=vi.hoisted(()=>({teacher:vi.fn(),student:vi.fn()}));
const route=reactive({name:'StudyDetail',query:{id:'9'}});
vi.mock('vue-router',()=>({useRoute:()=>route}));
vi.mock('@/api/studyRecord',()=>({getStudyDetail:f.teacher}));
vi.mock('@/api/studentAssessment',()=>({getStudentAssessmentDetail:f.student}));
const data={id:9,theme:'原旧考核',score:0,showResult:true,defense:{defenseAnswer:'原旧答辩'},result:[{question:'原题',answer:'原回答'}],analysis:{analysis:[{name:'原指标',score:0}],suggestions:['原建议'],strengths:['原优势'],weaknesses:['原不足']},summary:'原总结'};
let mounted:ReturnType<typeof mount>[]=[];
const render=()=>{const w=mount(Report,{global:{stubs:{StudentHeader:true,DefenseCard:true,QuestionCard:true,SuggestionCard:true,LdYBzCard:true,AnalysisCard:true,DqdfChart:true,DfmxChart:true}}});mounted.push(w);return w;};
const retry=(w:ReturnType<typeof mount>)=>w.findAll('button').find(b=>b.text()==='重新读取报告')!;
beforeEach(()=>{vi.resetAllMocks();route.name='StudyDetail';route.query.id='9';f.teacher.mockResolvedValue({data});f.student.mockResolvedValue({data});});
afterEach(()=>{mounted.forEach(w=>w.unmount());mounted=[];vi.useRealTimers();});
it('shows an explicit rejected legacy-path error, no empty/hidden-result fiction, and an actual read retry',async()=>{
  f.teacher.mockRejectedValueOnce(new Error('预置考核不能使用旧答辩流程'));const w=render();await flushPromises();expect(w.get('[data-testid="legacy-report-error"]').text()).toContain('预置考核不能使用旧答辩流程');expect(w.text()).not.toContain('暂无报告内容');expect(w.text()).not.toContain('教师设置不允许查看');expect(w.find('.detail-score-pill').exists()).toBe(false);
  await retry(w).trigger('click');await flushPromises();expect(f.teacher).toHaveBeenCalledTimes(2);expect(w.text()).toContain('原旧考核');expect(w.find('.detail-score-pill').text()).toContain('0');
});
it('clears stale grades before loading, ignores old results on route changes and does not append duplicate cards',async()=>{
  let resolve!:(v:any)=>void;f.teacher.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));const w=render();await flushPromises();expect(w.get('[role="status"]').text()).toContain('正在读取报告');
  route.query.id='10';await flushPromises();expect(w.text()).toContain('原旧考核');resolve({data:{...data,theme:'不该出现的旧请求'}});await flushPromises();expect(w.text()).not.toContain('不该出现的旧请求');
  route.query.id='11';await flushPromises();expect(w.findAll('defense-card-stub')).toHaveLength(1);expect(w.findAll('question-card-stub')).toHaveLength(1);expect(data.result[0]).not.toHaveProperty('cardTag');
});
it('keeps student-hidden content hidden even if an upstream response incorrectly contains protected fields',async()=>{
  route.name='StudentStudyDetail';f.student.mockResolvedValue({data:{...data,showResult:false}});const w=render();await flushPromises();expect(f.teacher).not.toHaveBeenCalled();expect(w.text()).toContain('教师设置不允许查看');expect(w.find('suggestion-card-stub').exists()).toBe(false);expect(w.find('analysis-card-stub').exists()).toBe(false);expect(w.find('.detail-score-pill').exists()).toBe(false);
});
it('handles student read rejection and genuinely empty legacy content without fabricating analysis cards',async()=>{
  route.name='StudentStudyDetail';f.student.mockRejectedValueOnce('unavailable');const w=render();await flushPromises();expect(w.get('[data-testid="legacy-report-error"]').text()).toContain('报告读取失败');
  f.student.mockResolvedValueOnce({data:{id:9,showResult:true}});await retry(w).trigger('click');await flushPromises();expect(w.text()).toContain('暂无报告内容');expect(w.find('analysis-card-stub').exists()).toBe(false);
});
it('supports genuine visible legacy student zero, all available cards and previous/next navigation',async()=>{
  vi.useFakeTimers();route.name='StudentStudyDetail';const w=render();await flushPromises();expect(w.find('.detail-score-pill').text()).toContain('0');const state=(w.vm as any).$.setupState;
  state.next();await vi.advanceTimersByTimeAsync(900);state.prev();await vi.advanceTimersByTimeAsync(900);expect(w.find('defense-card-stub').exists()).toBe(true);
  let suggestions=false,strengths=false;for(let n=0;n<5;n++){state.next();await vi.advanceTimersByTimeAsync(900);suggestions||=w.findComponent({name:'SuggestionCard'}).exists();strengths||=w.findComponent({name:'LdYBzCard'}).exists();}expect(suggestions).toBe(true);expect(strengths).toBe(true);
});
it('does not show failed, malformed or late reads as a successful AI report after unmount',async()=>{
  let reject!:(e:any)=>void;f.teacher.mockImplementationOnce(()=>new Promise((_,r)=>{reject=r;}));const w=render();await flushPromises();route.query.id='10';await flushPromises();reject(new Error('过期请求'));await flushPromises();expect(w.find('[data-testid="legacy-report-error"]').exists()).toBe(false);
  let resolve!:(v:any)=>void;f.teacher.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));route.query.id='11';await flushPromises();w.unmount();resolve({data});await flushPromises();
});

it.each([
  ['false string', 'false'],
  ['true string', 'true'],
  ['numeric true', 1],
  ['object', {}],
  ['array', []],
  ['null', null],
  ['missing flag', undefined],
])('does not treat a malformed student visibility flag (%s) as publication', async (_label, showResult) => {
  route.name = 'StudentStudyDetail';
  f.student.mockResolvedValue({ data: { ...data, showResult } });
  const w = render();
  await flushPromises();
  expect(f.teacher).not.toHaveBeenCalled();
  expect(w.get('[data-testid="legacy-report-error"]').text()).toContain('报告可见性信息无效');
  expect(w.text()).not.toContain('教师设置不允许查看');
  expect(w.text()).not.toContain('暂无报告内容');
  expect(w.find('.detail-score-pill').exists()).toBe(false);
  for (const card of ['defense-card-stub', 'question-card-stub', 'analysis-card-stub', 'suggestion-card-stub', 'ld-y-bz-card-stub', 'dqdf-chart-stub', 'dfmx-chart-stub']) {
    expect(w.find(card).exists()).toBe(false);
  }
  expect(w.find('.detail-footer').exists()).toBe(false);
});

it.each([false, true])('explicitly rereads an invalid student report using the new valid publication flag (%s)', async showResult => {
  route.name = 'StudentStudyDetail';
  f.student.mockResolvedValueOnce({ data: { ...data, showResult: 'false' } });
  const w = render();
  await flushPromises();
  expect(w.get('[data-testid="legacy-report-error"]').text()).toContain('报告可见性信息无效');
  f.student.mockResolvedValueOnce({ data: { ...data, showResult } });
  await retry(w).trigger('click');
  await flushPromises();
  expect(f.student).toHaveBeenCalledTimes(2);
  expect(f.teacher).not.toHaveBeenCalled();
  expect(w.find('[data-testid="legacy-report-error"]').exists()).toBe(false);
  expect(w.find('.detail-score-pill').exists()).toBe(showResult);
  expect(w.find('defense-card-stub').exists()).toBe(showResult);
  expect(w.find('question-card-stub').exists()).toBe(showResult);
  expect(w.text().includes('教师设置不允许查看')).toBe(!showResult);
});

it('does not impose student publication on the separately authorized teacher report', async () => {
  f.teacher.mockResolvedValue({ data: { ...data, showResult: false } });
  const w = render();
  await flushPromises();
  expect(f.teacher).toHaveBeenCalledTimes(1);
  expect(f.student).not.toHaveBeenCalled();
  expect(w.find('.detail-score-pill').text()).toContain('0');
  expect(w.find('defense-card-stub').exists()).toBe(true);
  expect(w.find('question-card-stub').exists()).toBe(true);
});
