import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import PreparedPaperEditor from '@/views/prepared/PreparedPaperEditor.vue';
import { getPreparedDraft, savePreparedDraft, approvePreparedQuestions } from '@/api/preparedPaper';
import type { PreparedQuestion } from '@/api/preparedPaper';
import PreparedGenerationPanel from '@/views/prepared/PreparedGenerationPanel.vue';
import PreparedPublicationPanel from '@/views/prepared/PreparedPublicationPanel.vue';
import PreparedAudioPanel from '@/views/prepared/PreparedAudioPanel.vue';

vi.mock('@/views/prepared/PreparedAudioPanel.vue',()=>({default:{name:'PreparedAudioPanel',props:['assessmentId','revision','hasUnsavedChanges','parentBusy','questions'],template:'<div data-testid="audio-panel" />'}}));

vi.mock('@/views/prepared/PreparedGenerationPanel.vue', () => ({ default: {
  name: 'PreparedGenerationPanel', props: ['assessmentId', 'revision', 'hasUnsavedChanges', 'parentBusy'],
  template: '<div data-testid="generation-panel" />',
} }));

vi.mock('@/views/prepared/PreparedPublicationPanel.vue', () => ({ default: {
  name: 'PreparedPublicationPanel', props: ['assessmentId', 'revision', 'hasUnsavedChanges', 'parentBusy'],
  template: '<div data-testid="publication-panel">播报与发布检查</div>',
} }));

vi.mock('@/api/preparedPaper', () => ({
  getPreparedDraft: vi.fn(), savePreparedDraft: vi.fn(), approvePreparedQuestions: vi.fn(),
}));

const Q1 = '11111111-1111-4111-8111-111111111111';
const Q2 = '22222222-2222-4222-8222-222222222222';
const question = (id: string, body: string, ordinal = 1): PreparedQuestion => ({
  questionId: id, source: 'MANUAL', body, ordinal, maxScore: 10, answerSeconds: 120,
  rubric: '说明依据', approved: false, audioState: 'PENDING', audioObjectKey: null,
});
const button = (wrapper: ReturnType<typeof mount>, name: string) => {
  const found = wrapper.findAll('button').find(b => b.text() === name);
  if (!found) throw new Error(`按钮不存在：${name}`);
  return found;
};

beforeEach(() => {
  vi.mocked(getPreparedDraft).mockReset();
  vi.mocked(savePreparedDraft).mockReset();
  vi.mocked(approvePreparedQuestions).mockReset();
  vi.mocked(getPreparedDraft).mockResolvedValue({ data: { assessmentId: 21, revision: 0, questions: [] } });
});

describe('teacher prepared paper editor', () => {
  it('audio command writes lock sibling controls and background completion never replaces dirty text',async()=>{
    const wrapper=mount(PreparedPaperEditor,{props:{assessmentId:21}});await flushPromises();const audio=wrapper.findComponent(PreparedAudioPanel);expect(audio.exists()).toBe(true);
    audio.vm.$emit('busy',true);await flushPromises();expect(button(wrapper,'新增题目').attributes('disabled')).toBeDefined();
    expect(wrapper.findComponent(PreparedPublicationPanel).props('parentBusy')).toBe(true);audio.vm.$emit('busy',false);await flushPromises();
    await button(wrapper,'新增题目').trigger('click');await wrapper.find('[data-testid="question-body"]').setValue('保留未保存编辑');
    audio.vm.$emit('revision-changed',2);await flushPromises();expect(wrapper.find('[data-testid="question-body"]').element).toHaveProperty('value','保留未保存编辑');
    expect(getPreparedDraft).toHaveBeenCalledTimes(1);
  });
  it('blocks draft edits and AI adoption while publication is in flight', async () => {
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    const publication = wrapper.findComponent(PreparedPublicationPanel);
    publication.vm.$emit('busy', true); await flushPromises();
    expect(button(wrapper, '新增题目').attributes('disabled')).toBeDefined();
    expect(wrapper.findComponent(PreparedGenerationPanel).props('parentBusy')).toBe(true);
    publication.vm.$emit('busy', false); await flushPromises();
    expect(button(wrapper, '新增题目').attributes('disabled')).toBeUndefined();
  });
  it('exposes a separate authorized publication check without treating draft saving as publication', async () => {
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    expect(wrapper.text()).toContain('播报与发布检查');
    expect(wrapper.text()).not.toContain('试卷已发布');
  });
  it('locks manual writes during adoption and accepts only the returned authorized draft', async () => {
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    const panel = wrapper.findComponent(PreparedGenerationPanel);
    expect(panel.exists()).toBe(true);
    panel.vm.$emit('busy', true); await flushPromises();
    expect(button(wrapper, '新增题目').attributes('disabled')).toBeDefined();
    panel.vm.$emit('adopted', { assessmentId: 21, revision: 1, questions: [{ ...question(Q1, '已采用建议'), source: 'AI' }] });
    panel.vm.$emit('busy', false); await flushPromises();
    expect(wrapper.find('[data-testid="question-body"]').element).toHaveProperty('value', '已采用建议');
    expect(wrapper.text()).toContain('AI 建议');
    expect(wrapper.text()).toContain('版本 1');
  });
  it('shows an empty draft without inventing generated questions', async () => {
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    expect(wrapper.text()).toContain('尚未添加题目');
    expect(getPreparedDraft).toHaveBeenCalledWith(21);
  });

  it('creates three questions and persists the displayed order with the expected revision', async () => {
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    for (let index = 1; index <= 3; index++) {
      await button(wrapper, '新增题目').trigger('click');
      const cards = wrapper.findAll('[data-testid="prepared-question"]');
      await cards[index - 1].find('[data-testid="question-body"]').setValue(`第${index}题`);
      await cards[index - 1].find('[data-testid="question-rubric"]').setValue('评分依据');
    }
    vi.mocked(savePreparedDraft).mockImplementation(async (_id, cmd) => ({ data: {
      assessmentId: 21, revision: 1,
      questions: cmd.questions.map((q, index) => ({ ...question(q.questionId, q.body, index + 1), ...q })),
    } }));
    await button(wrapper, '保存草稿').trigger('click');
    await flushPromises();
    expect(savePreparedDraft).toHaveBeenCalledWith(21, {
      expectedRevision: 0,
      questions: expect.arrayContaining([
        expect.objectContaining({ body: '第1题', rubric: '评分依据' }),
        expect.objectContaining({ body: '第2题' }), expect.objectContaining({ body: '第3题' }),
      ]),
    });
    expect(wrapper.text()).toContain('草稿已保存');
    expect(wrapper.text()).toContain('版本 1');
  });

  it('reorders and deletes questions without changing their identity', async () => {
    vi.mocked(getPreparedDraft).mockResolvedValue({ data: { assessmentId: 21, revision: 2,
      questions: [question(Q1, '第一题'), question(Q2, '第二题', 2)] } });
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    const cards = wrapper.findAll('[data-testid="prepared-question"]');
    await cards[1].find('[data-testid="move-up"]').trigger('click');
    expect(wrapper.findAll('[data-testid="question-body"]')[0].element).toHaveProperty('value', '第二题');
    await wrapper.findAll('[data-testid="prepared-question"]')[1].find('[data-testid="remove-question"]').trigger('click');
    vi.mocked(savePreparedDraft).mockResolvedValue({ data: { assessmentId: 21, revision: 3, questions: [question(Q2, '第二题')] } });
    await button(wrapper, '保存草稿').trigger('click');
    await flushPromises();
    expect(vi.mocked(savePreparedDraft).mock.calls[0][1].questions.map(q => q.questionId)).toEqual([Q2]);
  });

  it('requires a save before explicit review and uses the returned revision', async () => {
    vi.mocked(getPreparedDraft).mockResolvedValue({ data: { assessmentId: 21, revision: 2, questions: [question(Q1, '第一题')] } });
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    await wrapper.find('[data-testid="question-body"]').setValue('修改后的题目');
    expect(button(wrapper, '审核此题').attributes('disabled')).toBeDefined();
    vi.mocked(savePreparedDraft).mockResolvedValue({ data: { assessmentId: 21, revision: 3, questions: [question(Q1, '修改后的题目')] } });
    await button(wrapper, '保存草稿').trigger('click');
    await flushPromises();
    vi.mocked(approvePreparedQuestions).mockResolvedValue({ data: { assessmentId: 21, revision: 4,
      questions: [{ ...question(Q1, '修改后的题目'), approved: true }] } });
    await button(wrapper, '审核此题').trigger('click');
    await flushPromises();
    expect(approvePreparedQuestions).toHaveBeenCalledWith(21, { expectedRevision: 3, questionIds: [Q1] });
    expect(wrapper.text()).toContain('已审核');
    expect(wrapper.text()).toContain('版本 4');
  });

  it('preserves unsaved teacher edits on a version conflict and does not auto reload', async () => {
    vi.mocked(getPreparedDraft).mockResolvedValue({ data: { assessmentId: 21, revision: 2, questions: [question(Q1, '第一题')] } });
    vi.mocked(savePreparedDraft).mockRejectedValue(new Error('试卷已被修改，请重新读取最新版本'));
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    await wrapper.find('[data-testid="question-body"]').setValue('不能丢失的教师修改');
    await button(wrapper, '保存草稿').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="question-body"]').element).toHaveProperty('value', '不能丢失的教师修改');
    expect(wrapper.find('[role="alert"]').text()).toContain('试卷已被修改');
    expect(getPreparedDraft).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).not.toContain('草稿已保存');
  });

  it('shows authorization/load errors instead of a successful empty draft', async () => {
    vi.mocked(getPreparedDraft).mockRejectedValue(new Error('无权限'));
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('无权限');
    expect(button(wrapper, '新增题目').attributes('disabled')).toBeDefined();
  });

  it('rejects blank questions locally without sending a save request', async () => {
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    await button(wrapper, '新增题目').trigger('click');
    await button(wrapper, '保存草稿').trigger('click');
    await flushPromises();
    expect(savePreparedDraft).not.toHaveBeenCalled();
    expect(wrapper.find('[role="alert"]').text()).toContain('正文');
  });

  it('requires confirmation before replacing unsaved edits and reloads only when confirmed', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    await button(wrapper, '新增题目').trigger('click');
    await wrapper.find('[data-testid="question-body"]').setValue('未保存的内容');
    await button(wrapper, '重新读取').trigger('click');
    await flushPromises();
    expect(getPreparedDraft).toHaveBeenCalledTimes(1);
    expect(wrapper.find('[data-testid="question-body"]').element).toHaveProperty('value', '未保存的内容');
    confirm.mockReturnValue(true);
    await button(wrapper, '重新读取').trigger('click');
    await flushPromises();
    expect(getPreparedDraft).toHaveBeenCalledTimes(2);
    expect(wrapper.findAll('[data-testid="prepared-question"]')).toHaveLength(0);
  });

  it('rejects missing rubric and invalid numeric rules without sending writes', async () => {
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    await button(wrapper, '新增题目').trigger('click');
    await wrapper.find('[data-testid="question-body"]').setValue('有效正文');
    await button(wrapper, '保存草稿').trigger('click');
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('评分要求');
    await wrapper.find('[data-testid="question-rubric"]').setValue('评分标准');
    await wrapper.find('input[type="number"]').setValue('0');
    await button(wrapper, '保存草稿').trigger('click');
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('分值或时限');
    expect(savePreparedDraft).not.toHaveBeenCalled();
  });

  it('keeps the saved draft unapproved when the review request fails', async () => {
    vi.mocked(getPreparedDraft).mockResolvedValue({ data: { assessmentId: 21, revision: 1, questions: [question(Q1, '已保存正文')] } });
    vi.mocked(approvePreparedQuestions).mockRejectedValue(new Error('审核失败，请稍后重试'));
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    await button(wrapper, '审核此题').trigger('click');
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('审核失败');
    expect(wrapper.text()).toContain('待审核');
    expect(wrapper.text()).not.toContain('题目已审核');
    expect(button(wrapper, '审核此题').attributes('disabled')).toBeUndefined();
  });

  it('rejects a mismatched server resource instead of exposing another paper', async () => {
    vi.mocked(getPreparedDraft).mockResolvedValue({ data: { assessmentId: 22, revision: 1, questions: [question(Q1, '另一个考核')] } });
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 21 } });
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('草稿无效');
    expect(wrapper.text()).not.toContain('另一个考核');
    expect(button(wrapper, '新增题目').attributes('disabled')).toBeDefined();
  });

  it('does not request a paper for an invalid route id', async () => {
    const wrapper = mount(PreparedPaperEditor, { props: { assessmentId: 0 } });
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('考核标识无效');
    expect(getPreparedDraft).not.toHaveBeenCalled();
  });
});
