import {mount,flushPromises} from '@vue/test-utils';
import {beforeEach,afterEach,it,expect,vi} from 'vitest';
import {saveAs} from 'file-saver';
import PreparedReviewSummary from '@/views/prepared/PreparedReviewSummary.vue';
import * as api from '@/api/preparedReview';
const bufferModule='node:buffer';let NativeBlob:typeof Blob;
vi.mock('@/api/preparedReview',()=>({getReviewSummary:vi.fn(),getReviewReport:vi.fn(),exportReviewReport:vi.fn()}));
vi.mock('file-saver',()=>({saveAs:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',answer='22222222-2222-4222-8222-222222222222',second='33333333-3333-4333-8333-333333333333',version='a'.repeat(64);
const summary:api.ReviewSummary={actorId:7,assessmentId:21,studentId:8,studentName:'学生甲',theme:'冻结试卷',attemptId:attempt,attemptNo:1,current:true,showResult:false,
  state:'READY',resultVersion:version,completedAt:'2026-10-05T00:00:00Z',totalScore:7.25,totalMaxScore:20,passScore:12,
  answers:[{answerId:answer,ordinal:1,maxScore:10,score:7.25,scoringSource:'MODEL',version:1,confirmed:false},
    {answerId:second,ordinal:2,maxScore:10,score:0,scoringSource:'TEACHER',version:2,confirmed:true}]};
const detail={...summary.answers[0],studentId:8,assessmentId:21,attemptId:attempt,resultVersion:version,body:'<script>题目正文</script>',feedback:'<img src=x onerror=alert(1)>模型反馈',
  transcript:{sentences:[{startMs:0,endMs:1000,text:'<img src=x onerror=alert(1)>原回答'}]}};
const human={...detail,...summary.answers[1],body:'第二题原正文',transcript:null,feedback:null};
const report={schemaVersion:1 as const,summary,answers:[detail,human]};
const clone=<T,>(v:T)=>structuredClone(v);
let wrappers:ReturnType<typeof mount>[]=[];
const render=(props={})=>{const w=mount(PreparedReviewSummary,{props:{actorId:7,assessmentId:21,studentId:8,attemptId:attempt,current:true,refreshVersion:0,...props}});wrappers.push(w);return w;};
const button=(w:ReturnType<typeof mount>,label:string)=>{const b=w.findAll('button').find(b=>b.text()===label);expect(b,`missing ${label}`).toBeDefined();return b!;};
const download=()=>({data:new NativeBlob([new Uint8Array([80,75,3,4,0,0,0,0])],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}),headers:{'x-prepared-result-version':version,'content-length':'8'}});
beforeEach(async()=>{
  NativeBlob=(await import(bufferModule)).Blob;vi.resetAllMocks();vi.stubGlobal('Blob',NativeBlob);
  vi.mocked(api.getReviewSummary).mockResolvedValue({data:clone(summary)});
  vi.mocked(api.getReviewReport).mockResolvedValue({data:clone(report)});
  vi.mocked(api.exportReviewReport).mockResolvedValue(download() as any);
});
afterEach(()=>{wrappers.forEach(w=>w.unmount());wrappers=[];vi.unstubAllGlobals();});
it('reads all original model and human answers as plain text without public student authority',async()=>{
  const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');await flushPromises();
  expect(api.getReviewReport).toHaveBeenCalledWith(21,attempt,version,expect.any(AbortSignal));
  const panel=w.get('[data-testid="review-report"]');expect(panel.findAll('article')).toHaveLength(2);
  expect(panel.text()).toContain('题目正文');expect(panel.text()).toContain('原回答');expect(panel.text()).toContain('模型反馈');expect(panel.text()).toContain('暂无有效转写');expect(panel.text()).toContain('0 / 10');
  expect(panel.find('script').exists()).toBe(false);expect(panel.find('img').exists()).toBe(false);expect(w.find('audio').exists()).toBe(false);expect(w.find('textarea').exists()).toBe(false);
});
it('retains actual current, history and hidden-result metadata without offering historical writes',async()=>{
  for(const current of [true,false])for(const showResult of [true,false]){
    const s={...summary,current,showResult,studentName:null,attemptNo:3};vi.mocked(api.getReviewSummary).mockResolvedValueOnce({data:clone(s)});
    vi.mocked(api.getReviewReport).mockResolvedValueOnce({data:clone({...report,summary:s})});
    const w=render({current});await flushPromises();await button(w,'查看完整报告').trigger('click');await flushPromises();
    expect(w.text()).toContain(current?'当前尝试':'历史尝试，只读');expect(w.text()).toContain(showResult?'已开放':'尚未开放');expect(w.text()).toContain('第 3 次');
    expect(w.find('[data-testid="review-report"]').exists()).toBe(true);expect(w.findAll('button').map(b=>b.text())).toEqual(['刷新整场成绩','查看完整报告','导出原尝试报告']);w.unmount();
  }
});
it('refuses partial, changed summaries and mixed grade proofs without exposing supplied content',async()=>{
  const changes:any[]=[{schemaVersion:2},{summary:null},{answers:[]},{answers:[detail]},{answers:[detail,detail]}];
  for(const [key,value] of Object.entries({actorId:9,assessmentId:22,studentId:9,studentName:'另一学生',theme:'另一试卷',attemptId:second,attemptNo:2,current:false,showResult:true,
    state:'PROCESSING',resultVersion:'b'.repeat(64),completedAt:'2026-10-06T00:00:00Z',totalScore:8,totalMaxScore:21,passScore:13}))changes.push({summary:{...summary,[key]:value}});
  for(const [key,value] of Object.entries({answerId:second,ordinal:2,maxScore:11,score:8,scoringSource:'TEACHER',version:2,confirmed:true}))changes.push({summary:{...summary,answers:[{...summary.answers[0],[key]:value},summary.answers[1]]}});
  for(const change of changes){vi.mocked(api.getReviewReport).mockResolvedValueOnce({data:{...report,...change}});const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');await flushPromises();
    expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="review-report"]').exists()).toBe(false);expect(w.text()).not.toContain('题目正文');w.unmount();}
});
it('validates every original answer scope, effective grade, content and transcription bound',async()=>{
  const changes:any[]=[null];
  for(const [key,value] of Object.entries({studentId:9,assessmentId:22,attemptId:second,resultVersion:'b'.repeat(64),answerId:second,ordinal:2,maxScore:11,score:0,scoringSource:'TEACHER',version:9,confirmed:true,
    body:'',feedback:null,transcript:null}))changes.push({...detail,[key]:value});
  changes.push({...detail,body:'x'.repeat(10001)},{...detail,feedback:'x'.repeat(4001)}, {...human,feedback:'内部理由',ordinal:1,answerId:answer,score:7.25,scoringSource:'MODEL'},
    {...detail,transcript:{sentences:[]}},{...detail,transcript:{sentences:[{startMs:-1,endMs:1,text:'原回答'}]}},
    {...detail,transcript:{sentences:[{startMs:0,endMs:1800001,text:'原回答'}]}},{...detail,transcript:{sentences:[{startMs:0,endMs:0,text:'原回答'}]}},
    {...detail,transcript:{sentences:[{startMs:0,endMs:1,text:''}]}},{...detail,transcript:{sentences:[{startMs:0,endMs:1,text:'x'.repeat(10001)}]}},
    {...detail,transcript:{sentences:Array.from({length:7},(_,n)=>({startMs:n,endMs:n+1,text:'x'.repeat(10000)}))}},
    {...detail,transcript:{sentences:[{startMs:0,endMs:10,text:'先'},{startMs:5,endMs:20,text:'重叠'}]}});
  for(const first of changes){vi.mocked(api.getReviewReport).mockResolvedValueOnce({data:{...report,answers:[first,human]}});const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');await flushPromises();
    expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="review-report"]').exists()).toBe(false);w.unmount();}
});
it('does not offer or invoke reports for unsubmitted or incomplete original attempts',async()=>{
  for(const state of ['PROCESSING','IN_PROGRESS','UPLOAD_PENDING','REQUIRES_REVIEW','CANCELLED'] as const){
    vi.mocked(api.getReviewSummary).mockResolvedValueOnce({data:{...summary,state,resultVersion:null,completedAt:null,totalScore:null,totalMaxScore:null,passScore:null,answers:[]}});
    const w=render();await flushPromises();expect(w.findAll('button')).toHaveLength(1);expect(w.text()).toContain('不代表零分');w.unmount();
  }
  expect(api.getReviewReport).not.toHaveBeenCalled();expect(api.exportReviewReport).not.toHaveBeenCalled();
});
it('clears prior report on permission, transport or malformed rereads and supports explicit retry',async()=>{
  const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');await flushPromises();
  for(const failure of [new Error('管理权限已撤销'),new Error('网络暂不可用'),'failure']){
    vi.mocked(api.getReviewReport).mockRejectedValueOnce(failure);await button(w,'查看完整报告').trigger('click');expect(w.find('[data-testid="review-report"]').exists()).toBe(false);await flushPromises();
    expect(w.find('[role="alert"]').exists()).toBe(true);expect(w.find('[data-testid="review-summary-total"]').exists()).toBe(true);
    await button(w,'查看完整报告').trigger('click');await flushPromises();expect(w.find('[data-testid="review-report"]').exists()).toBe(true);
  }
});
it('refresh, grading changes and scope changes abort report reads and discard late original content',async()=>{
  let late!:(v:any)=>void;vi.mocked(api.getReviewReport).mockImplementationOnce(()=>new Promise(r=>{late=r;}));
  const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');const signal=vi.mocked(api.getReviewReport).mock.calls[0][3];
  await w.setProps({refreshVersion:1});await flushPromises();expect(signal?.aborted).toBe(true);late({data:report});await flushPromises();expect(w.find('[data-testid="review-report"]').exists()).toBe(false);
  await button(w,'查看完整报告').trigger('click');await flushPromises();expect(w.find('[data-testid="review-report"]').exists()).toBe(true);
  vi.mocked(api.getReviewSummary).mockRejectedValueOnce(new Error('新身份未授权'));await w.setProps({actorId:9});await flushPromises();expect(w.find('[data-testid="review-report"]').exists()).toBe(false);expect(w.text()).not.toContain('原回答');
});
it('blocks repeated report requests, aborts on unmount and ignores a late protected answer',async()=>{
  let late!:(v:any)=>void;vi.mocked(api.getReviewReport).mockImplementationOnce(()=>new Promise(r=>{late=r;}));
  const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');await button(w,'查看完整报告').trigger('click');await button(w,'导出原尝试报告').trigger('click');
  expect(api.getReviewReport).toHaveBeenCalledTimes(1);expect(api.exportReviewReport).not.toHaveBeenCalled();expect(w.text()).toContain('最多等待 10 秒');
  const signal=vi.mocked(api.getReviewReport).mock.calls[0][3];w.unmount();expect(signal?.aborted).toBe(true);late({data:report});await flushPromises();
});
it('saves only the expected original teacher workbook with a fixed scope filename',async()=>{
  const w=render();await flushPromises();await button(w,'导出原尝试报告').trigger('click');await flushPromises();
  expect(api.exportReviewReport).toHaveBeenCalledWith(21,attempt,version,expect.any(AbortSignal));expect(saveAs).toHaveBeenCalledWith(expect.any(NativeBlob),`prepared-review-21-${attempt}.xlsx`);
});
it('refuses error blobs, changed digest, invalid length, size or ZIP signatures',async()=>{
  const good=download();
  for(const response of [{...good,data:null},{...good,data:new NativeBlob(['1234'],{type:good.data.type})},
    {...good,data:new NativeBlob([new Uint8Array(32*1024*1024+1)],{type:good.data.type})},
    {...good,data:new NativeBlob(['{"code":403}'],{type:'application/json'})},{...good,data:new NativeBlob(['not-xlsx'],{type:good.data.type})},
    {...good,headers:{...good.headers,'x-prepared-result-version':'b'.repeat(64)}},{...good,headers:{...good.headers,'content-length':'99'}}]){
    const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');await flushPromises();vi.mocked(api.exportReviewReport).mockResolvedValueOnce(response as any);
    await button(w,'导出原尝试报告').trigger('click');await flushPromises();expect(w.text()).toContain('无法核验');expect(saveAs).not.toHaveBeenCalled();expect(w.find('[data-testid="review-report"]').exists()).toBe(false);w.unmount();
  }
});
it('clears protected report on failed download, keeps summary, and permits explicit retry',async()=>{
  const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');await flushPromises();
  for(const failure of [new Error('管理权限已撤销'),'failure']){
    vi.mocked(api.exportReviewReport).mockRejectedValueOnce(failure);await button(w,'导出原尝试报告').trigger('click');await flushPromises();
    expect(w.find('[data-testid="review-report"]').exists()).toBe(false);expect(w.find('[data-testid="review-summary-total"]').exists()).toBe(true);expect(w.find('[role="alert"]').exists()).toBe(true);
  }
  await button(w,'导出原尝试报告').trigger('click');await flushPromises();expect(saveAs).toHaveBeenCalledTimes(1);
});
it('does not download a late response after refresh or repeated export clicks',async()=>{
  let late!:(v:any)=>void;vi.mocked(api.exportReviewReport).mockImplementationOnce(()=>new Promise(r=>{late=r;}));
  const w=render();await flushPromises();await button(w,'导出原尝试报告').trigger('click');await button(w,'导出原尝试报告').trigger('click');await button(w,'查看完整报告').trigger('click');
  expect(api.exportReviewReport).toHaveBeenCalledTimes(1);expect(api.getReviewReport).not.toHaveBeenCalled();const signal=vi.mocked(api.exportReviewReport).mock.calls[0][3];
  await button(w,'刷新整场成绩').trigger('click');await flushPromises();expect(signal?.aborted).toBe(true);late(download());await flushPromises();expect(saveAs).not.toHaveBeenCalled();
});
it('rechecks scope after asynchronous workbook prefix reading before saving',async()=>{
  let prefix!:(v:ArrayBuffer)=>void;const file=download();vi.spyOn(file.data,'slice').mockReturnValue({arrayBuffer:()=>new Promise(r=>{prefix=r;})} as Blob);
  vi.mocked(api.exportReviewReport).mockResolvedValueOnce(file as any);const w=render();await flushPromises();await button(w,'导出原尝试报告').trigger('click');await flushPromises();
  await w.setProps({refreshVersion:1});await flushPromises();prefix(new Uint8Array([80,75,3,4]).buffer);await flushPromises();expect(saveAs).not.toHaveBeenCalled();
});
