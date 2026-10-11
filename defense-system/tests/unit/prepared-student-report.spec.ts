import {mount,flushPromises} from '@vue/test-utils';
import {beforeEach,afterEach,it,expect,vi} from 'vitest';
import {saveAs} from 'file-saver';
import PreparedStudentResult from '@/views/prepared/PreparedStudentResult.vue';
import * as api from '@/api/preparedResult';
const bufferModule='node:buffer';
let NativeBlob:typeof Blob;
vi.mock('@/api/preparedResult',()=>({getPreparedStudentResult:vi.fn(),getPreparedStudentResultAnswer:vi.fn(),getPreparedStudentResultReport:vi.fn(),exportPreparedStudentResultReport:vi.fn()}));
vi.mock('file-saver',()=>({saveAs:vi.fn()}));
const attempt='11111111-1111-4111-8111-111111111111',answer='22222222-2222-4222-8222-222222222222',version='a'.repeat(64);
const summary={studentId:8,assessmentId:21,theme:'公开结果',prepared:true,state:'READY' as const,attemptId:attempt,resultVersion:version,
  completedAt:'2026-10-05T00:00:00Z',totalScore:7.25,totalMaxScore:10,passScore:6,answers:[{answerId:answer,ordinal:1,maxScore:10,score:7.25,scoringSource:'TEACHER' as const,version:1,confirmed:true}]};
const detail={...summary.answers[0],studentId:8,assessmentId:21,attemptId:attempt,resultVersion:version,body:'<script>题目正文</script>',feedback:null,
  transcript:{sentences:[{startMs:0,endMs:1000,text:'<img src=x onerror=alert(1)>原回答'}]}};
const report={schemaVersion:1 as const,summary,answers:[detail]};
let wrappers:ReturnType<typeof mount>[]=[];
const render=()=>{const w=mount(PreparedStudentResult,{props:{studentId:8,assessmentId:21}});wrappers.push(w);return w;};
const button=(w:ReturnType<typeof mount>,label:string)=>{const b=w.findAll('button').find(b=>b.text()===label);expect(b,`missing ${label}`).toBeDefined();return b!;};
const download=()=>({data:new NativeBlob([new Uint8Array([80,75,3,4,0,0,0,0])],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}),headers:{'x-prepared-result-version':version,'content-length':'8'}});
beforeEach(async()=>{
  NativeBlob=(await import(bufferModule)).Blob;
  vi.clearAllMocks();vi.stubGlobal('Blob',NativeBlob);
  vi.mocked(api.getPreparedStudentResult).mockResolvedValue({data:structuredClone(summary)});
  vi.mocked(api.getPreparedStudentResultReport).mockResolvedValue({data:structuredClone(report)});
  vi.mocked(api.exportPreparedStudentResultReport).mockResolvedValue(download() as any);
});
afterEach(()=>{for(const w of wrappers)w.unmount();wrappers=[];vi.unstubAllGlobals();});
it('reads a complete version-bound report and displays all original content as plain text',async()=>{
  const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');await flushPromises();
  expect(api.getPreparedStudentResultReport).toHaveBeenCalledWith(21,version,expect.any(AbortSignal));
  const panel=w.get('[data-testid="result-report"]');expect(panel.text()).toContain('7.25 / 10');expect(panel.text()).toContain('题目正文');expect(panel.text()).toContain('原回答');
  expect(panel.find('script').exists()).toBe(false);expect(panel.find('img').exists()).toBe(false);expect(api.getPreparedStudentResultAnswer).not.toHaveBeenCalled();
});
it('rejects mixed owners, grades, result versions or partial reports without showing their supplied content',async()=>{
  for(const mutation of [{schemaVersion:2},{summary:{...summary,studentId:9}},{summary:{...summary,resultVersion:'b'.repeat(64)}},{summary:{...summary,totalScore:8}},
    {answers:[]},{answers:[{...detail,score:8}]},{answers:[{...detail,studentId:9}]},{answers:[{...detail,resultVersion:'b'.repeat(64)}]}]) {
    vi.mocked(api.getPreparedStudentResultReport).mockResolvedValueOnce({data:{...report,...mutation}} as any);
    const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');await flushPromises();
    expect(w.text()).toContain('无法核验');expect(w.find('[data-testid="result-report"]').exists()).toBe(false);expect(w.text()).not.toContain('题目正文');w.unmount();
  }
});
it('refresh and owner changes cancel report reads and discard late protected content',async()=>{
  let late!:(value:any)=>void;vi.mocked(api.getPreparedStudentResultReport).mockImplementationOnce(()=>new Promise(resolve=>{late=resolve;}));
  const w=render();await flushPromises();await button(w,'查看完整报告').trigger('click');const signal=vi.mocked(api.getPreparedStudentResultReport).mock.calls[0][2];
  vi.mocked(api.getPreparedStudentResult).mockResolvedValueOnce({data:{...summary,studentId:9,state:'HIDDEN',resultVersion:null,completedAt:null,totalScore:null,totalMaxScore:null,passScore:null,answers:[]}});
  await w.setProps({studentId:9});await flushPromises();expect(signal?.aborted).toBe(true);late({data:report});await flushPromises();
  expect(w.find('[data-testid="result-report"]').exists()).toBe(false);expect(w.text()).not.toContain('原回答');expect(w.findAll('button').map(x=>x.text())).not.toContain('导出本人报告');
});
it('downloads only the exact expected authenticated result workbook with a fixed filename',async()=>{
  const w=render();await flushPromises();await button(w,'导出本人报告').trigger('click');await flushPromises();
  expect(api.exportPreparedStudentResultReport).toHaveBeenCalledWith(21,version,expect.any(AbortSignal));
  expect(saveAs).toHaveBeenCalledWith(expect.any(NativeBlob),`prepared-result-21-${attempt}.xlsx`);
});
it('does not save hidden-error blobs, changed versions, wrong lengths or non-workbook payloads',async()=>{
  const good=download();
  for(const value of [{...good,headers:{...good.headers,'x-prepared-result-version':'b'.repeat(64)}},{...good,headers:{...good.headers,'content-length':'99'}},
    {...good,data:new NativeBlob(['{"code":403}'],{type:'application/json'})},{...good,data:new NativeBlob(['not-xlsx'],{type:good.data.type})}]) {
    vi.mocked(api.exportPreparedStudentResultReport).mockResolvedValueOnce(value as any);const w=render();await flushPromises();await button(w,'导出本人报告').trigger('click');await flushPromises();
    expect(w.text()).toContain('无法核验');expect(w.get('[data-testid="result-total"]').text()).toContain('7.25');expect(saveAs).not.toHaveBeenCalled();w.unmount();
  }
});
it('failed downloads retain current results and late downloads after refresh are not saved',async()=>{
  const w=render();await flushPromises();vi.mocked(api.exportPreparedStudentResultReport).mockRejectedValueOnce(new Error('教师尚未开放结果'));
  await button(w,'导出本人报告').trigger('click');await flushPromises();expect(w.text()).toContain('教师尚未开放结果');expect(w.find('[data-testid="result-total"]').exists()).toBe(true);
  let late!:(value:any)=>void;vi.mocked(api.exportPreparedStudentResultReport).mockImplementationOnce(()=>new Promise(resolve=>{late=resolve;}));
  await button(w,'导出本人报告').trigger('click');await button(w,'刷新结果').trigger('click');await flushPromises();late(download());await flushPromises();expect(saveAs).not.toHaveBeenCalled();
});
