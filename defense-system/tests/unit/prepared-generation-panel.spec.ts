import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import PreparedGenerationPanel from '@/views/prepared/PreparedGenerationPanel.vue';
import { listGenerations, getGeneration, requestGeneration, adoptGeneration } from '@/api/preparedGeneration';

vi.mock('@/api/preparedGeneration', () => ({ listGenerations: vi.fn(), getGeneration: vi.fn(), requestGeneration: vi.fn(), adoptGeneration: vi.fn() }));
const job = '11111111-1111-4111-8111-111111111111';
const q = '22222222-2222-4222-8222-222222222222';
const summary = { id: job, state: 'SUCCEEDED' as const, inputVersion: 1, errorCode: null, createdAt: '2026-10-04T01:00:00Z' };
const detail = { ...summary, suggestions: [{ questionId: q, body: '<script>不可信建议</script>', rubric: '依据', maxScore: 10, answerSeconds: 120 }], adoptedQuestionIds: [] as string[] };
const props = { assessmentId: 21, revision: 1, hasUnsavedChanges: false, parentBusy: false };
const button = (w: ReturnType<typeof mount>, name: string) => w.findAll('button').find(b => b.text() === name)!;
let mounted: ReturnType<typeof mount>[] = [];
const render = () => { const w = mount(PreparedGenerationPanel, { props }); mounted.push(w); return w; };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(listGenerations).mockResolvedValue({ data: { availability: { enabled: true, reason: '已核验费用上限' }, jobs: [summary] } });
  vi.mocked(getGeneration).mockResolvedValue({ data: detail });
});
afterEach(() => { mounted.forEach(w => w.unmount()); mounted = []; vi.useRealTimers(); });

describe('teacher AI suggestion panel (synthetic API results only)', () => {
  it('validates generation input and reports read failures without invented suggestions', async () => {
    const w = render(); await flushPromises();
    await button(w, '生成建议').trigger('click'); expect(requestGeneration).not.toHaveBeenCalled();
    expect(w.text()).toContain('请填写出题要求');
    vi.mocked(listGenerations).mockRejectedValue(new Error('读取失败'));
    await button(w, '刷新建议状态').trigger('click'); await flushPromises(); expect(w.text()).toContain('读取失败');
  });
  it('polls only an active task and stops after completion or unmount', async () => {
    vi.useFakeTimers();
    vi.mocked(getGeneration).mockResolvedValueOnce({ data: { ...detail, state: 'RUNNING', suggestions: [] } });
    const w = render(); await flushPromises();
    expect(w.text()).toContain('正在生成');
    await vi.advanceTimersByTimeAsync(3000); await flushPromises();
    expect(w.text()).toContain('建议已生成');
    expect(getGeneration).toHaveBeenCalledTimes(2);
    w.unmount(); mounted = mounted.filter(v => v !== w);
    await vi.advanceTimersByTimeAsync(6000); expect(getGeneration).toHaveBeenCalledTimes(2);
  });
  it('honestly disables unavailable integration while manual editing stays independent', async () => {
    vi.mocked(listGenerations).mockResolvedValue({ data: { availability: { enabled: false, reason: '独立凭据尚未核验' }, jobs: [] } });
    const w = render(); await flushPromises();
    expect(w.text()).toContain('独立凭据尚未核验');
    expect(button(w, '生成建议').attributes('disabled')).toBeDefined();
    expect(requestGeneration).not.toHaveBeenCalled();
  });
  it('shows separate suggestions as plain text and requires explicit teacher adoption', async () => {
    const w = render(); await flushPromises();
    expect(w.find('script').exists()).toBe(false);
    expect(w.text()).toContain('<script>不可信建议</script>');
    expect(adoptGeneration).not.toHaveBeenCalled();
    await w.find('[data-testid="suggestion-choice"]').setValue(true);
    vi.mocked(adoptGeneration).mockResolvedValue({ data: { draft: { assessmentId: 21, revision: 2, questions: [] }, adoptedRevision: 2 } });
    await button(w, '采用所选建议').trigger('click'); await flushPromises();
    expect(adoptGeneration).toHaveBeenCalledWith(21, job, expect.objectContaining({ expectedRevision: 1, questionIds: [q] }));
    expect(w.emitted('adopted')?.[0]?.[0]).toEqual({ assessmentId: 21, revision: 2, questions: [] });
    expect(w.emitted('busy')?.map(e => e[0])).toEqual([true, false]);
  });
  it('does not adopt over unsaved edits and retains selection on conflict', async () => {
    const w = render(); await flushPromises(); await w.find('[data-testid="suggestion-choice"]').setValue(true);
    await w.setProps({ hasUnsavedChanges: true });
    expect(button(w, '采用所选建议').attributes('disabled')).toBeDefined();
    expect(w.text()).toContain('先保存草稿');
    await w.setProps({ hasUnsavedChanges: false });
    vi.mocked(adoptGeneration).mockRejectedValue(new Error('版本冲突，请读取最新版本'));
    await button(w, '采用所选建议').trigger('click'); await flushPromises();
    expect(w.text()).toContain('版本冲突'); expect(w.emitted('adopted')).toBeUndefined();
    expect((w.find('[data-testid="suggestion-choice"]').element as HTMLInputElement).checked).toBe(true);
  });
  it('reuses the original generation command after an ambiguous network failure', async () => {
    const w = render(); await flushPromises(); await w.find('textarea').setValue('明确教师出题要求');
    vi.mocked(requestGeneration).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce({ data: summary });
    await button(w, '生成建议').trigger('click'); await flushPromises();
    await w.find('textarea').setValue('随后编辑，不得改变原重试命令');
    await button(w, '重试原请求').trigger('click'); await flushPromises();
    expect(vi.mocked(requestGeneration).mock.calls).toHaveLength(2);
    expect(vi.mocked(requestGeneration).mock.calls[1]).toEqual(vi.mocked(requestGeneration).mock.calls[0]);
  });
  it('cleans up polling and ignores late results after switching assessment', async () => {
    let resolve!: (value: { data: typeof detail }) => void;
    vi.mocked(getGeneration).mockImplementationOnce(() => new Promise(r => { resolve = r; }));
    const w = render(); await flushPromises();
    vi.mocked(listGenerations).mockResolvedValue({ data: { availability: { enabled: false, reason: '未启用' }, jobs: [] } });
    await w.setProps({ assessmentId: 22 }); await flushPromises(); resolve({ data: detail }); await flushPromises();
    expect(w.text()).not.toContain('不可信建议'); expect(w.emitted('adopted')).toBeUndefined();
  });
});
