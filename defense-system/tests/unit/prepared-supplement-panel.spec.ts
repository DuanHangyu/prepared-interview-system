import { mount,flushPromises } from '@vue/test-utils';
import { IDBFactory } from 'fake-indexeddb';
import { beforeEach,afterEach,expect,it,vi } from 'vitest';
import PreparedSupplementPanel from '@/views/prepared/PreparedSupplementPanel.vue';
import { getSupplementStatus,approveSupplement } from '@/api/preparedSupplement';
import { PreparedSupplementJournal } from '@/prepared/supplementJournal';
import { scope,command,receipt,view,approvedView,localInput } from '../fixtures/prepared-supplement';
vi.mock('@/api/preparedSupplement',()=>({getSupplementStatus:vi.fn(),approveSupplement:vi.fn()}));
let mounted:ReturnType<typeof mount>[]=[];
const render=(changed={})=>{const w=mount(PreparedSupplementPanel,{props:{...scope,ordinal:1,...changed}});mounted.push(w);return w;};
const button=(w:ReturnType<typeof mount>,name:string)=>w.findAll('button').find(b=>b.text()===name)!;
async function settled(w:ReturnType<typeof mount>){await vi.waitFor(()=>expect(button(w,'刷新原补传状态').attributes('disabled')).toBeUndefined());await flushPromises();}
async function fill(w:ReturnType<typeof mount>){await settled(w);await w.get('textarea').setValue(command.reason);await w.get('input[type="datetime-local"]').setValue(localInput(command.uploadDeadline));}
async function send(w:ReturnType<typeof mount>,name='批准限时补传'){const count=vi.mocked(approveSupplement).mock.calls.length;await button(w,name).trigger('click');await vi.waitFor(()=>expect(vi.mocked(approveSupplement).mock.calls.length>count||w.find('[role="alert"]').exists()).toBe(true));await settled(w);}
beforeEach(()=>{vi.resetAllMocks();vi.stubGlobal('indexedDB',new IDBFactory());vi.mocked(getSupplementStatus).mockResolvedValue({data:view});});
afterEach(()=>{mounted.forEach(w=>w.unmount());mounted=[];vi.unstubAllGlobals();});
it('shows original per-question and automatic deadlines, explicit reason and upload-only boundary without false submission',async()=>{
  const w=render();await settled(w);expect(w.text()).toContain('原单题截止');expect(w.text()).toContain('原自动补传截止');expect(w.text()).toContain('不增加答题时间');
  await button(w,'批准限时补传').trigger('click');await flushPromises();expect(approveSupplement).not.toHaveBeenCalled();expect(w.text()).toContain('明确截止');
  await fill(w);vi.mocked(approveSupplement).mockImplementation(async(_s,c)=>({data:{...receipt,idempotencyKey:c.idempotencyKey}}));vi.mocked(getSupplementStatus).mockResolvedValue({data:approvedView});
  await send(w);expect(approveSupplement).toHaveBeenCalledWith(scope,expect.objectContaining({expectedRevision:0,expectedAttemptRevision:9,manifestSha256:command.manifestSha256,reason:command.reason}));expect(w.text()).toContain('补传已批准');expect(w.text()).toContain('不表示录音已保存或已提交');
});
it('restores the exact unknown original after refresh, without automatic send or replacement even when a later approval is visible',async()=>{
  let w=render();await fill(w);vi.mocked(approveSupplement).mockRejectedValueOnce(new Error('响应丢失'));await send(w);const original=vi.mocked(approveSupplement).mock.calls[0][1];expect((await new PreparedSupplementJournal(scope).read())?.idempotencyKey).toBe(original.idempotencyKey);
  w.unmount();mounted=[];vi.mocked(getSupplementStatus).mockResolvedValue({data:{...approvedView,approvalRevision:2,attemptRevision:11,latest:{...receipt,idempotencyKey:scope.attemptId,approvalRevision:2,attemptRevision:11,uploadDeadline:'2026-10-07T01:00:00Z'}}});w=render();await settled(w);expect(approveSupplement).toHaveBeenCalledTimes(1);expect(button(w,'恢复原补传审批请求')).toBeDefined();expect(w.get('textarea').attributes('disabled')).toBeDefined();
  vi.mocked(approveSupplement).mockResolvedValue({data:{...receipt,idempotencyKey:original.idempotencyKey}});await send(w,'恢复原补传审批请求');expect(vi.mocked(approveSupplement).mock.calls[1][1]).toEqual(original);expect(await new PreparedSupplementJournal(scope).read()).toBeNull();expect(w.text()).toContain('补传已批准');
});
it('does not send or recover any pending write in a historical attempt',async()=>{
  const saved=await new PreparedSupplementJournal(scope).prepare(command);const w=render({current:false});await flushPromises();expect(getSupplementStatus).not.toHaveBeenCalled();expect(approveSupplement).not.toHaveBeenCalled();expect(w.text()).toContain('历史尝试只读');expect(await new PreparedSupplementJournal(scope).read()).toEqual(saved);expect(w.find('textarea').exists()).toBe(false);
});
it('preserves unknown original and refuses to clear a mismatched success or unauthorized response',async()=>{
  const w=render();await fill(w);vi.mocked(approveSupplement).mockResolvedValue({data:{...receipt,answerId:scope.attemptId}});await send(w);expect(w.text()).toContain('无法核验');expect(await new PreparedSupplementJournal(scope).read()).not.toBeNull();
  vi.mocked(approveSupplement).mockRejectedValue(Object.assign(new Error('权限撤销'),{status:403}));await send(w,'恢复原补传审批请求');expect(button(w,'读取最新状态并撤销未接受请求')).toBeUndefined();expect(await new PreparedSupplementJournal(scope).read()).not.toBeNull();
});
it('only definitive 409 plus fresh scoped state allows explicit removal of a rejected request',async()=>{
  const w=render();await fill(w);vi.mocked(approveSupplement).mockRejectedValue(Object.assign(new Error('版本冲突'),{status:409}));await send(w);
  vi.mocked(getSupplementStatus).mockRejectedValueOnce(new Error('读取失败'));await button(w,'读取最新状态并撤销未接受请求').trigger('click');await settled(w);expect(await new PreparedSupplementJournal(scope).read()).not.toBeNull();
  await button(w,'读取最新状态并撤销未接受请求').trigger('click');await settled(w);expect(await new PreparedSupplementJournal(scope).read()).toBeNull();expect(approveSupplement).toHaveBeenCalledTimes(1);
});
it('does not send if local persistence fails, even with legitimate teacher eligibility',async()=>{
  vi.stubGlobal('indexedDB',{open:()=>{throw new Error('blocked');}});const w=render();await flushPromises();expect(w.text()).toContain('本机');expect(button(w,'批准限时补传').attributes('disabled')).toBeDefined();expect(approveSupplement).not.toHaveBeenCalled();
});
it('rejects changed scope and ignores stale responses without deleting another scope original',async()=>{
  let resolve!:(v:{data:typeof view})=>void;vi.mocked(getSupplementStatus).mockImplementationOnce(()=>new Promise(r=>resolve=r));const w=render();await w.setProps({assessmentId:22});vi.mocked(getSupplementStatus).mockResolvedValue({data:{...view,assessmentId:22}});resolve({data:view});await flushPromises();expect(w.text()).toContain('无法核验');expect(w.find('textarea').exists()).toBe(false);
});
it('shows ineligible and closed-server failures without approval, zero-score punishment or invented receipt',async()=>{
  vi.mocked(getSupplementStatus).mockResolvedValueOnce({data:{...view,mayApprove:false,eligibilityIssue:'采集异常需人工核验'}});const closed=render();await flushPromises();expect(closed.text()).toContain('采集异常需人工核验');expect(button(closed,'批准限时补传').attributes('disabled')).toBeDefined();
  const w=render();await fill(w);vi.mocked(approveSupplement).mockRejectedValue(Object.assign(new Error('正式流程未开放'),{status:503}));await send(w);expect(w.text()).toContain('正式流程未开放');expect(await new PreparedSupplementJournal(scope).read()).not.toBeNull();expect(w.text()).not.toContain('补传已批准');
});
it('requires explicit recovery when another tab prepared a different original after this page read its state',async()=>{
  const w=render();await fill(w);const saved=await new PreparedSupplementJournal(scope).prepare({...command,reason:'另一标签页的原核验依据'});
  await button(w,'批准限时补传').trigger('click');await vi.waitFor(()=>expect(button(w,'恢复原补传审批请求')).toBeDefined());await settled(w);
  expect(approveSupplement).not.toHaveBeenCalled();expect(w.get('textarea').element.value).toBe(saved.reason);expect(w.text()).toContain('请核验原请求后明确恢复');
});

it.each(['direct-refresh','failed-read-then-refresh'] as const)(
  'never transfers a previous request rejection to a different unknown original after %s',async(mode)=>{
    const w=render();await fill(w);
    vi.mocked(approveSupplement).mockRejectedValueOnce(Object.assign(new Error('原请求版本冲突'),{status:409}));
    await send(w);expect(button(w,'读取最新状态并撤销未接受请求')).toBeDefined();
    const rejected=vi.mocked(approveSupplement).mock.calls[0][1],otherTab=new PreparedSupplementJournal(scope);
    await otherTab.clear(rejected);
    const unknown=await otherTab.prepare({...command,reason:'另一标签页的结果未知原请求'});
    expect(unknown.idempotencyKey).not.toBe(rejected.idempotencyKey);
    if(mode==='failed-read-then-refresh'){
      vi.mocked(getSupplementStatus).mockRejectedValueOnce(new Error('刷新读取连接中断'));
      await button(w,'刷新原补传状态').trigger('click');await settled(w);
      expect(w.text()).toContain('刷新读取连接中断');expect(await otherTab.read()).toEqual(unknown);
    }
    await button(w,'刷新原补传状态').trigger('click');await settled(w);
    expect(w.get('textarea').element.value).toBe(unknown.reason);
    expect(approveSupplement).toHaveBeenCalledTimes(1);
    // Exercise the exposed old action, if present, to detect loss of the new
    // unknown request rather than only testing that its button is hidden.
    const inherited=button(w,'读取最新状态并撤销未接受请求');
    if(inherited){await inherited.trigger('click');await settled(w);}
    expect(await otherTab.read()).toEqual(unknown);
    expect(button(w,'读取最新状态并撤销未接受请求')).toBeUndefined();
    expect(button(w,'恢复原补传审批请求')).toBeDefined();
    otherTab.close();
  });

it('retains the same rejected original across refresh and requires its own new rejection before explicit removal',async()=>{
  const w=render();await fill(w);
  vi.mocked(approveSupplement).mockRejectedValue(Object.assign(new Error('原请求版本冲突'),{status:409}));
  await send(w);const original=vi.mocked(approveSupplement).mock.calls[0][1];
  expect(button(w,'读取最新状态并撤销未接受请求')).toBeDefined();
  await button(w,'刷新原补传状态').trigger('click');await settled(w);
  expect(await new PreparedSupplementJournal(scope).read()).toEqual(original);
  expect(approveSupplement).toHaveBeenCalledTimes(1);
  expect(button(w,'读取最新状态并撤销未接受请求')).toBeUndefined();
  await send(w,'恢复原补传审批请求');
  expect(vi.mocked(approveSupplement).mock.calls[1][1]).toEqual(original);
  expect(button(w,'读取最新状态并撤销未接受请求')).toBeDefined();
  await button(w,'读取最新状态并撤销未接受请求').trigger('click');await settled(w);
  expect(await new PreparedSupplementJournal(scope).read()).toBeNull();
});

it('replaces stale rejection evidence with an unknown outcome only after explicit recovery of the new original',async()=>{
  const w=render();await fill(w);
  vi.mocked(approveSupplement).mockRejectedValueOnce(Object.assign(new Error('旧请求版本冲突'),{status:409}));
  await send(w);const rejected=vi.mocked(approveSupplement).mock.calls[0][1],otherTab=new PreparedSupplementJournal(scope);
  await otherTab.clear(rejected);const unknown=await otherTab.prepare({...command,reason:'新原请求结果未知'});
  await button(w,'刷新原补传状态').trigger('click');await settled(w);
  expect(approveSupplement).toHaveBeenCalledTimes(1);
  expect(button(w,'读取最新状态并撤销未接受请求')).toBeUndefined();
  vi.mocked(approveSupplement).mockRejectedValueOnce(new Error('新原请求响应丢失'));
  await send(w,'恢复原补传审批请求');
  expect(vi.mocked(approveSupplement).mock.calls[1][1]).toEqual(unknown);
  expect(await otherTab.read()).toEqual(unknown);
  expect(button(w,'读取最新状态并撤销未接受请求')).toBeUndefined();otherTab.close();
});

it('keeps another tab new original when the old explicitly rejected request is replaced during its fresh read',async()=>{
  const w=render();await fill(w);
  vi.mocked(approveSupplement).mockRejectedValueOnce(Object.assign(new Error('版本冲突'),{status:409}));
  await send(w);const rejected=vi.mocked(approveSupplement).mock.calls[0][1],otherTab=new PreparedSupplementJournal(scope);
  let resolve!:(value:{data:typeof view})=>void;
  vi.mocked(getSupplementStatus).mockImplementationOnce(()=>new Promise(done=>{resolve=done;}));
  await button(w,'读取最新状态并撤销未接受请求').trigger('click');
  await vi.waitFor(()=>expect(resolve).toBeTypeOf('function'));
  await otherTab.clear(rejected);const unknown=await otherTab.prepare({...command,reason:'核验期间另一份结果未知的原请求'});
  resolve({data:view});await settled(w);
  expect(await otherTab.read()).toEqual(unknown);expect(w.text()).toContain('本机');
  expect(approveSupplement).toHaveBeenCalledTimes(1);
  await button(w,'刷新原补传状态').trigger('click');await settled(w);
  expect(w.get('textarea').element.value).toBe(unknown.reason);
  expect(button(w,'读取最新状态并撤销未接受请求')).toBeUndefined();otherTab.close();
});
