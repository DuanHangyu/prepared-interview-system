import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Panel from '@/views/prepared/PreparedPublicationPanel.vue';
import { getPublicationStatus, getPublication, publishPreparedPaper, type PublicationStatus, type PublicationView } from '@/api/preparedPublication';
vi.mock('@/api/preparedPublication', () => ({ getPublicationStatus: vi.fn(), getPublication: vi.fn(), publishPreparedPaper: vi.fn() }));
const id = '11111111-1111-4111-8111-111111111111';
const rules = { theme: '面试', totalScore: 10, passScore: 6, questionCount: 1, showResult: false };
const summary = { id, versionNo: 1, draftRevision: 2, contentSha256: 'a'.repeat(64), publishedBy: 3, publishedAt: '2026-10-04T01:00:00Z' };
const ready: PublicationStatus = { assessmentId: 21, draftRevision: 2, rules, publishable: true, frozen: false, preparedContentSha256: 'a'.repeat(64), activeVersion: null, issues: [], versions: [] };
const view: PublicationView = { version: summary, snapshot: { schemaVersion: 1, assessmentId: 21, rules, questions: [
  { questionId: id, source: 'MANUAL', body: '<script>只应作为文本</script>', ordinal: 1, maxScore: 10, answerSeconds: 120, rubric: '教师要求',
    audio: { assetId: id, bodySha256: 'b'.repeat(64), configSha256: 'c'.repeat(64), contentSha256: 'd'.repeat(64), byteSize: 48044, durationMs: 1000, mediaType: 'audio/wav' } },
] } };
const props = { assessmentId: 21, revision: 2, hasUnsavedChanges: false, parentBusy: false };
const button = (w: ReturnType<typeof mount>, name: string) => w.findAll('button').find(b => b.text() === name)!;
let mounted: ReturnType<typeof mount>[] = [];
const render = () => { const w = mount(Panel, { props }); mounted.push(w); return w; };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getPublicationStatus).mockResolvedValue({ data: ready });
  vi.mocked(getPublication).mockResolvedValue({ data: view });
  vi.mocked(publishPreparedPaper).mockResolvedValue({ data: view });
  vi.spyOn(window, 'confirm').mockReturnValue(true);
});
afterEach(() => { for (const w of mounted) w.unmount(); mounted = []; });
describe('publication panel, synthetic contract results only', () => {
  it('shows precise per-question blockers and honestly keeps unavailable audio from publication', async () => {
    vi.mocked(getPublicationStatus).mockResolvedValue({ data: { ...ready, publishable: false, rules: { ...rules, totalScore: null, passScore: null, questionCount: null },
      issues: [{ code: 'AUDIO_NOT_READY', questionId: id, ordinal: 1, message: '请先生成并保存本题播报' }] } });
    const w = render(); await flushPromises();
    expect(w.text()).toContain('尚未发布'); expect(w.text()).toContain('第 1 题：请先生成');
    expect(w.text()).toContain('未启用或未可靠保存'); expect(w.text()).toContain('未设置');
    expect(button(w, '发布固定试卷').attributes('disabled')).toBeDefined();
    expect(publishPreparedPaper).not.toHaveBeenCalled();
  });
  it('requires saved current revision, an idle parent and teacher confirmation', async () => {
    const w = render(); await flushPromises();
    await w.setProps({ hasUnsavedChanges: true }); expect(button(w, '发布固定试卷').attributes('disabled')).toBeDefined();
    await w.setProps({ hasUnsavedChanges: false, parentBusy: true }); expect(button(w, '发布固定试卷').attributes('disabled')).toBeDefined();
    await w.setProps({ parentBusy: false, revision: 3 }); await flushPromises(); expect(button(w, '发布固定试卷').attributes('disabled')).toBeDefined();
    await w.setProps({ revision: 2 }); await flushPromises(); vi.mocked(window.confirm).mockReturnValue(false);
    await button(w, '发布固定试卷').trigger('click'); expect(publishPreparedPaper).not.toHaveBeenCalled();
    expect(w.text()).toContain('已保存试卷通过发布检查');
  });
  it('locks the parent during publication and displays only the actual returned fixed snapshot', async () => {
    const w = render(); await flushPromises(); await button(w, '发布固定试卷').trigger('click'); await flushPromises();
    expect(publishPreparedPaper).toHaveBeenCalledWith(21, expect.objectContaining({ expectedRevision: 2 }));
    expect(w.emitted('busy')?.map(e => e[0])).toEqual([false, true, false]);
    expect(w.text()).toContain('发布版本 1 已保存'); expect(w.find('script').exists()).toBe(false);
    expect(w.text()).toContain('<script>只应作为文本</script>'); expect(w.text()).toContain('播报资产已绑定 · 1 秒');
  });
  it('retries exactly the original command after response loss even if the draft changes', async () => {
    vi.mocked(publishPreparedPaper).mockRejectedValueOnce(new Error('响应中断')).mockResolvedValueOnce({ data: view });
    const w = render(); await flushPromises(); await button(w, '发布固定试卷').trigger('click'); await flushPromises();
    expect(w.text()).toContain('响应中断'); expect(button(w, '发布固定试卷').attributes('disabled')).toBeDefined();
    await w.setProps({ revision: 3 }); await flushPromises(); await button(w, '重试原发布').trigger('click'); await flushPromises();
    expect(vi.mocked(publishPreparedPaper).mock.calls[1]).toEqual(vi.mocked(publishPreparedPaper).mock.calls[0]);
    expect(w.text()).toContain('发布版本 1 已保存');
  });
  it('handles read failures and bad status as errors, never a publishable empty paper', async () => {
    vi.mocked(getPublicationStatus).mockRejectedValueOnce(new Error('读取失败'));
    const w = render(); await flushPromises(); expect(w.text()).toContain('读取失败');
    expect(button(w, '发布固定试卷').attributes('disabled')).toBeDefined();
    vi.mocked(getPublicationStatus).mockResolvedValueOnce({ data: { ...ready, assessmentId: 22 } });
    await button(w, '刷新发布检查').trigger('click'); await flushPromises(); expect(w.text()).toContain('发布检查无效');
    await button(w, '刷新发布检查').trigger('click'); await flushPromises(); expect(button(w, '发布固定试卷').attributes('disabled')).toBeUndefined();
  });
  it('displays the frozen published state and reads authorized history without publishing', async () => {
    vi.mocked(getPublicationStatus).mockResolvedValue({ data: { ...ready, frozen: true, publishable: false, activeVersion: summary, versions: [summary] } });
    const w = render(); await flushPromises(); expect(w.text()).toContain('已有学生开始'); expect(w.text()).toContain('当前已发布版本 1');
    await button(w, '查看发布版本 1').trigger('click'); await flushPromises();
    expect(getPublication).toHaveBeenCalledWith(21, id); expect(w.text()).toContain('教师评分要求'); expect(publishPreparedPaper).not.toHaveBeenCalled();
  });
  it('never displays a cross-assessment returned snapshot and retains the uncertain publication command', async () => {
    vi.mocked(publishPreparedPaper).mockResolvedValue({ data: { ...view, snapshot: { ...view.snapshot, assessmentId: 22 } } });
    const w = render(); await flushPromises(); await button(w, '发布固定试卷').trigger('click'); await flushPromises();
    expect(w.text()).toContain('发布快照无效'); expect(w.text()).not.toContain('只应作为文本');
    expect(button(w, '重试原发布').exists()).toBe(true);
  });
  it('reports history failure without retaining an unrelated old snapshot', async () => {
    vi.mocked(getPublicationStatus).mockResolvedValue({ data: { ...ready, activeVersion: summary, versions: [summary] } });
    vi.mocked(getPublication).mockRejectedValueOnce('untyped failure');
    const w = render(); await flushPromises(); await button(w, '查看发布版本 1').trigger('click'); await flushPromises();
    expect(w.text()).toContain('操作失败'); expect(w.text()).not.toContain('固定快照');
  });
  it('ignores late reads after changing assessment or unmounting', async () => {
    let complete!: (r: { data: PublicationStatus }) => void;
    vi.mocked(getPublicationStatus).mockImplementationOnce(() => new Promise(r => { complete = r; }));
    const w = render(); await flushPromises();
    vi.mocked(getPublicationStatus).mockResolvedValue({ data: { ...ready, assessmentId: 22, publishable: false, versions: [], issues: [] } });
    await w.setProps({ assessmentId: 22 }); await flushPromises(); complete({ data: { ...ready, activeVersion: summary } }); await flushPromises();
    expect(w.text()).not.toContain('当前已发布版本'); expect(button(w, '发布固定试卷').attributes('disabled')).toBeDefined();
    let finish!: (r: { data: PublicationStatus }) => void;
    vi.mocked(getPublicationStatus).mockImplementationOnce(() => new Promise(r => { finish = r; }));
    await button(w, '刷新发布检查').trigger('click'); w.unmount(); mounted = [];
    finish({ data: ready }); await flushPromises(); expect(publishPreparedPaper).not.toHaveBeenCalled();
  });
});
