import { mount,flushPromises } from '@vue/test-utils';
import { beforeEach,afterEach,expect,it,vi } from 'vitest';
import StudyRecord from '@/views/teacherDashboard/StudyRecord.vue';
const f=vi.hoisted(()=>({list:vi.fn(),retake:vi.fn(),export:vi.fn(),push:vi.fn(),warning:vi.fn(),error:vi.fn(),success:vi.fn(),confirm:vi.fn(),open:vi.fn(),reset:vi.fn(),save:vi.fn()}));
vi.mock('@/api/studyRecord',()=>({getStudyRecordList:f.list,retakeApi:f.retake,exportStudentRecord:f.export}));
vi.mock('vue-router',()=>({useRouter:()=>({push:f.push})}));
vi.mock('file-saver',()=>({default:f.save}));
vi.mock('@/views/teacherDashboard/components/UpdateTeacherScoreModal.vue',async()=>{const {defineComponent,h}=await import('vue');return{default:defineComponent({setup(_,ctx){ctx.expose({openModal:f.open});return()=>h('span');}})};});
vi.mock('ant-design-vue',async()=>{
  const {defineComponent,h}=await import('vue');
  const passthrough=defineComponent({setup(_,ctx){ctx.expose({resetFields:f.reset});return()=>h('div',ctx.slots.default?.());}});
  const table=defineComponent({name:'RecordTable',props:['columns','dataSource','rowSelection','pagination'],emits:['change'],setup(p,ctx){return()=>h('div',p.dataSource.map((r:any,index:number)=>h('section',{'data-record':r.id},p.columns.map((column:any)=>{const formatted=column.customRender?.({index,value:r[column.dataIndex]});return h('div',{'data-cell':column.key},[column.key==='score'?null:formatted,ctx.slots.bodyCell?.({column,record:r})]);}))));}});
  return {Form:passthrough,FormItem:passthrough,Input:passthrough,Button:defineComponent({setup(_,ctx){return()=>h('button',ctx.attrs,ctx.slots.default?.());}}),Table:table,message:{warning:f.warning,error:f.error,success:f.success},Modal:{confirm:f.confirm}};
});
const legacy={id:1,assessmentId:10,studentName:'旧学生',theme:'旧考核',score:6,checkScore:0,totalScore:10,state:1,prepared:false,assessmentTime:'2026-10-04T12:00:00'};
const prepared={...legacy,id:2,assessmentId:20,studentName:'新学生',theme:'预置考核',score:null,checkScore:null,state:2,prepared:true,preparedState:'SUBMITTED'};
let mounted:ReturnType<typeof mount>[]=[];
async function render(rows:any[]=[legacy,prepared]){f.list.mockResolvedValueOnce({data:{records:rows,total:rows.length}});const w=mount(StudyRecord);mounted.push(w);await flushPromises();return w;}
const button=(w:ReturnType<typeof mount>,name:string)=>w.findAll('button').find(b=>b.text().includes(name))!;
const table=(w:ReturnType<typeof mount>)=>w.findComponent({name:'RecordTable'});
const select=(w:ReturnType<typeof mount>,keys:(number|string)[])=>table(w).props('rowSelection').onChange(keys);
beforeEach(()=>{vi.resetAllMocks();f.list.mockResolvedValue({data:{records:[legacy,prepared],total:2}});f.retake.mockResolvedValue({data:true});f.export.mockResolvedValue({data:new Blob(['xlsx']),headers:{'content-type':'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','content-disposition':"attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.xlsx"}});});
afterEach(()=>{mounted.forEach(w=>w.unmount());mounted=[];});
it('shows prepared status and no fake zero, old mutations or old report entry; legacy checked zero is a real zero',async()=>{
  const w=await render();const row=w.find('[data-record="2"]');expect(row.find('[data-cell="score"]').text()).toBe('在预置评阅页查看');expect(row.find('[data-cell="checkScore"]').text()).toBe('在预置评阅页查看');expect(row.text()).toContain('待评阅');expect(row.text()).not.toContain('重考');expect(row.text()).not.toContain('查看详情');
  expect(w.find('[data-record="1"] [data-cell="checkScore"]').text()).toBe('0分');
  expect(table(w).props('rowSelection').getCheckboxProps(prepared).disabled).toBe(true);expect(table(w).props('rowSelection').getCheckboxProps(legacy).disabled).toBe(false);
  await button(w,'预置面试评阅').trigger('click');expect(f.push).toHaveBeenCalledWith('/prepared-review/20');expect(f.open).not.toHaveBeenCalled();expect(f.retake).not.toHaveBeenCalled();
});
it('renders each prepared lifecycle without disclosing legacy scores and cannot route an unbound assessment',async()=>{
  const states=['IN_PROGRESS','UPLOAD_PENDING','REQUIRES_REVIEW','CANCELLED','unknown'];const w=await render(states.map((preparedState,i)=>({...prepared,id:i+2,preparedState,score:0,checkScore:7,assessmentId:0})));
  for(const text of ['面试进行中','待补传原录音','需人工核验','已取消','状态待核验'])expect(w.text()).toContain(text);
  expect(w.find('[data-record="2"] [data-cell="score"]').text()).not.toContain('0');await button(w,'预置面试评阅').trigger('click');expect(f.push).not.toHaveBeenCalled();expect(f.warning).toHaveBeenCalled();
});
it('keeps true legacy detail, edit and retake, while prepared cannot be selected for old export even through a stale callback',async()=>{
  const w=await render();await button(w,'查看详情').trigger('click');expect(f.push).toHaveBeenCalledWith('/studyDetail?id=1');
  await w.find('[data-record="1"] .score-edit').trigger('click');expect(f.open).toHaveBeenCalledWith(legacy);
  await button(w,'重考').trigger('click');const cmd=f.confirm.mock.calls[0][0];await cmd.onOk();await flushPromises();expect(f.retake).toHaveBeenCalledWith({recordId:1});
  select(w,[2,999]);await button(w,'导出学生报告').trigger('click');expect(f.export).not.toHaveBeenCalled();expect(f.warning).toHaveBeenCalled();
  select(w,['1',2]);await button(w,'导出学生报告').trigger('click');await flushPromises();expect(f.export).toHaveBeenCalledWith({recordIds:['1']});expect(f.save).toHaveBeenCalledWith(expect.any(Blob),'报告.xlsx');
});
it('resets stale selection on filter/page refresh and handles list failure without leaving old records actionable',async()=>{
  const w=await render();select(w,[1]);f.list.mockRejectedValueOnce(new Error('权限已变化'));await button(w,'查询').trigger('click');await flushPromises();expect(w.find('[data-record]').exists()).toBe(false);expect(f.error).toHaveBeenCalled();await button(w,'导出学生报告').trigger('click');expect(f.export).not.toHaveBeenCalled();
  await button(w,'重置').trigger('click');await flushPromises();expect(f.reset).toHaveBeenCalled();table(w).vm.$emit('change',{current:2,pageSize:50});await flushPromises();expect(f.list).toHaveBeenLastCalledWith(expect.objectContaining({page:2,size:50}));
});
it('never downloads rejected or malformed export responses and preserves legitimate fallback filenames',async()=>{
  const w=await render([legacy]);select(w,[1]);f.export.mockRejectedValueOnce(new Error('不能使用旧报告'));await button(w,'导出学生报告').trigger('click');await flushPromises();expect(f.save).not.toHaveBeenCalled();expect(f.error).toHaveBeenCalled();
  for(const headers of [{},{'content-disposition':"attachment; filename*=UTF-8''%ZZ"},{'content-disposition':'attachment; filename=record.xlsx'}]){select(w,[1]);f.export.mockResolvedValueOnce({data:new Blob(['xlsx']),headers});await button(w,'导出学生报告').trigger('click');await flushPromises();expect(f.save).toHaveBeenLastCalledWith(expect.any(Blob),'考核记录报告.xlsx');}
});
it('formats old timestamps and zero/missing fields, blocks programmatic stale prepared actions and handles retake failure',async()=>{
  const w=await render([{...legacy,id:1,assessmentTime:null,score:undefined,checkScore:undefined,state:3},{...legacy,id:3,assessmentTime:'invalid',score:-1,checkScore:null},prepared]);
  expect(w.find('[data-record="1"] [data-cell="checkScore"]').text()).toBe('0分');expect(w.find('[data-record="1"]').text()).toContain('--');expect(w.find('[data-record="3"]').text()).toContain('--');
  const actions=(w.vm as any).$.setupState;actions.updateScore(prepared);actions.goDetail(prepared);actions.retakeFn(prepared);expect(f.open).not.toHaveBeenCalled();expect(f.confirm).not.toHaveBeenCalled();expect(f.push).toHaveBeenCalledWith('/prepared-review/20');
  actions.selectedRowKeys=[2];await actions.exportFn();expect(f.export).not.toHaveBeenCalled();
  f.retake.mockRejectedValueOnce('lost');await button(w,'重考').trigger('click');await f.confirm.mock.calls[0][0].onOk();expect(f.error).toHaveBeenCalledWith('旧版重考操作失败，请刷新后重试');
  table(w).vm.$emit('change',{});await flushPromises();expect(f.list).toHaveBeenLastCalledWith(expect.objectContaining({page:1,size:20}));
});
it('ignores superseded and unmounted list results, including old failures, and handles an empty server page',async()=>{
  const w=await render();let finish!:(value:any)=>void;f.list.mockImplementationOnce(()=>new Promise(r=>{finish=r;}));await button(w,'查询').trigger('click');
  f.list.mockResolvedValueOnce({data:{records:[],total:0}});await button(w,'查询').trigger('click');await flushPromises();finish({data:{records:[legacy],total:1}});await flushPromises();expect(w.find('[data-record]').exists()).toBe(false);
  let fail!:(value:any)=>void;f.list.mockImplementationOnce(()=>new Promise((_,reject)=>{fail=reject;}));await button(w,'查询').trigger('click');await button(w,'查询').trigger('click');await flushPromises();fail(new Error('old error'));await flushPromises();expect(f.error).not.toHaveBeenCalled();
  f.list.mockResolvedValueOnce({});await button(w,'查询').trigger('click');await flushPromises();expect(w.find('[data-record]').exists()).toBe(false);
  f.list.mockImplementationOnce(()=>new Promise(r=>{finish=r;}));await button(w,'查询').trigger('click');w.unmount();finish({data:{records:[legacy],total:1}});await flushPromises();expect(f.error).not.toHaveBeenCalled();
});
