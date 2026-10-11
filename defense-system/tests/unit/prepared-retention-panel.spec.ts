import { mount,flushPromises } from '@vue/test-utils';
import { IDBFactory } from 'fake-indexeddb';
import { beforeEach,afterEach,expect,it,vi } from 'vitest';
import PreparedRetentionPanel from '@/views/prepared/PreparedRetentionPanel.vue';
import { getRetentionProtection,changeRetentionProtection } from '@/api/preparedRetention';
import { PreparedRetentionJournal } from '@/prepared/retentionJournal';
import { scope,command,receipt,view,protectedView } from '../fixtures/prepared-retention';
vi.mock('@/api/preparedRetention',()=>({getRetentionProtection:vi.fn(),changeRetentionProtection:vi.fn()}));
let mounted:ReturnType<typeof mount>[]=[];
const render=(changed={})=>{const w=mount(PreparedRetentionPanel,{props:{...scope,...changed}});mounted.push(w);return w;};
const button=(w:ReturnType<typeof mount>,name:string)=>w.findAll('button').find(b=>b.text()===name)!;
async function settled(w:ReturnType<typeof mount>){await vi.waitFor(()=>expect(button(w,'刷新原留存保护状态').attributes('disabled')).toBeUndefined());await flushPromises();}
async function fill(w:ReturnType<typeof mount>){await settled(w);await w.get('textarea').setValue(command.reason);}
async function send(w:ReturnType<typeof mount>,name='保护原记录'){const count=vi.mocked(changeRetentionProtection).mock.calls.length;await button(w,name).trigger('click');await vi.waitFor(()=>expect(vi.mocked(changeRetentionProtection).mock.calls.length>count||w.find('[role="alert"]').exists()).toBe(true));await settled(w);}
beforeEach(()=>{vi.resetAllMocks();vi.stubGlobal('indexedDB',new IDBFactory());vi.mocked(getRetentionProtection).mockResolvedValue({data:view});});
afterEach(()=>{mounted.forEach(w=>w.unmount());mounted=[];vi.unstubAllGlobals();});
it('requires reason and presents retention boundaries without pretending cleanup or student recovery',async()=>{
  const w=render();await settled(w);expect(w.text()).toContain('不恢复作答');expect(w.text()).toContain('未启用自动清理');await send(w);expect(changeRetentionProtection).not.toHaveBeenCalled();expect(w.text()).toContain('请填写');
  await fill(w);vi.mocked(changeRetentionProtection).mockImplementation(async(_s,c)=>({data:{...receipt,idempotencyKey:c.idempotencyKey}}));vi.mocked(getRetentionProtection).mockResolvedValue({data:protectedView});await send(w);expect(w.text()).toContain('原留存保护请求已确认');expect(w.text()).toContain('已人工保护');expect(await new PreparedRetentionJournal(scope).read()).toBeNull();
});
it('restores exactly one unknown original across refresh after another manager releases it, without auto-send',async()=>{
  let w=render();await fill(w);vi.mocked(changeRetentionProtection).mockRejectedValueOnce(new Error('响应丢失'));await send(w);const original=vi.mocked(changeRetentionProtection).mock.calls[0][1];w.unmount();mounted=[];
  vi.mocked(getRetentionProtection).mockResolvedValue({data:{...view,revision:2,latest:{...receipt,actorId:9,action:'RELEASE',expectedRevision:1,revision:2,held:false}}});w=render();await settled(w);expect(changeRetentionProtection).toHaveBeenCalledTimes(1);expect(w.get('textarea').attributes('disabled')).toBeDefined();expect(w.text()).toContain('原请求尚待确认');
  vi.mocked(changeRetentionProtection).mockResolvedValue({data:{...receipt,idempotencyKey:original.idempotencyKey}});await send(w,'恢复原留存保护请求');expect(vi.mocked(changeRetentionProtection).mock.calls[1][1]).toEqual(original);expect(await new PreparedRetentionJournal(scope).read()).toBeNull();expect(w.text()).toContain('未人工保护');expect(w.text()).toContain('不表示当前状态未被其他教师改变');
});
it('releases only manual protection and cannot restore retired audio or override clearing',async()=>{
  vi.mocked(getRetentionProtection).mockResolvedValue({data:{...protectedView,audioState:'RETIRED'}});const w=render();await fill(w);expect(w.text()).toContain('不能恢复已清理录音');
  vi.mocked(changeRetentionProtection).mockImplementation(async(_s,c)=>({data:{...receipt,...c,revision:2,held:false,audioState:'RETIRED'}}));vi.mocked(getRetentionProtection).mockResolvedValue({data:{...view,revision:2,audioState:'RETIRED',latest:{...receipt,expectedRevision:1,revision:2,action:'RELEASE',held:false,audioState:'RETIRED'}}});await send(w,'解除人工保护');expect(vi.mocked(changeRetentionProtection).mock.calls[0][1].action).toBe('RELEASE');
  vi.mocked(getRetentionProtection).mockResolvedValue({data:{...view,audioState:'RETIRING',mayChange:false}});await button(w,'刷新原留存保护状态').trigger('click');await settled(w);expect(button(w,'保护原记录').attributes('disabled')).toBeDefined();expect(w.text()).toContain('不能承诺重新保护');
});
it('keeps original on mismatched receipt or authorization failure; only 409 plus fresh scope permits explicit clearing',async()=>{
  const w=render();await fill(w);vi.mocked(changeRetentionProtection).mockResolvedValue({data:{...receipt,studentId:9}});await send(w);expect(w.text()).toContain('无法核验');expect(await new PreparedRetentionJournal(scope).read()).not.toBeNull();
  vi.mocked(changeRetentionProtection).mockRejectedValue(Object.assign(new Error('权限撤销'),{status:403}));await send(w,'恢复原留存保护请求');expect(button(w,'读取最新状态并撤销未接受请求')).toBeUndefined();
  vi.mocked(changeRetentionProtection).mockRejectedValue(Object.assign(new Error('版本冲突'),{status:409}));await send(w,'恢复原留存保护请求');vi.mocked(getRetentionProtection).mockRejectedValueOnce(new Error('读取失败'));await button(w,'读取最新状态并撤销未接受请求').trigger('click');await settled(w);expect(await new PreparedRetentionJournal(scope).read()).not.toBeNull();
  await button(w,'读取最新状态并撤销未接受请求').trigger('click');await settled(w);expect(await new PreparedRetentionJournal(scope).read()).toBeNull();
});
it('blocks writes if durable storage or fresh original permission is unavailable',async()=>{
  vi.stubGlobal('indexedDB',{open:()=>{throw Error('blocked');}});const w=render();await settled(w);expect(w.text()).toContain('本机');expect(button(w,'保护原记录').attributes('disabled')).toBeDefined();expect(changeRetentionProtection).not.toHaveBeenCalled();
  vi.mocked(getRetentionProtection).mockRejectedValue(new Error('权限撤销'));await button(w,'刷新原留存保护状态').trigger('click');await settled(w);expect(w.find('textarea').exists()).toBe(false);
});
it('ignores late POST when selection changes and leaves its original journal for later recovery',async()=>{
  const w=render();await fill(w);let resolve!:(value:{data:typeof receipt})=>void;vi.mocked(changeRetentionProtection).mockImplementation(()=>new Promise(r=>resolve=r));await button(w,'保护原记录').trigger('click');await vi.waitFor(()=>expect(changeRetentionProtection).toHaveBeenCalledTimes(1));const original=vi.mocked(changeRetentionProtection).mock.calls[0][1];
  vi.mocked(getRetentionProtection).mockResolvedValue({data:{...view,studentId:9,attemptId:receipt.id}});await w.setProps({studentId:9,attemptId:receipt.id});await settled(w);resolve({data:{...receipt,idempotencyKey:original.idempotencyKey}});await flushPromises();expect(w.text()).not.toContain('原留存保护请求已确认');expect(await new PreparedRetentionJournal(scope).read()).toEqual(original);expect(await new PreparedRetentionJournal({...scope,studentId:9,attemptId:receipt.id}).read()).toBeNull();
});
it('requires explicit recovery when another tab prepared a different original after this state read',async()=>{
  const w=render();await fill(w);const saved=await new PreparedRetentionJournal(scope).prepare({...command,reason:'另一页原依据'});await button(w,'保护原记录').trigger('click');await vi.waitFor(()=>expect(button(w,'恢复原留存保护请求')).toBeDefined());await settled(w);expect(changeRetentionProtection).not.toHaveBeenCalled();expect(w.get('textarea').element.value).toBe(saved.reason);expect(w.text()).toContain('明确恢复');
});
