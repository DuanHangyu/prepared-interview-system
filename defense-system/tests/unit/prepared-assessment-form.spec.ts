import { flushPromises, mount } from '@vue/test-utils';
import { h } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Form, FormItem, Input, InputNumber, Modal } from 'ant-design-vue';
import AssessmentForm from '@/views/teacherDashboard/components/HandleAssessment.vue';
import { createAssessment, modifyAssessment } from '@/api/assessment';
import { getSchoolClassStudent } from '@/api/student';
import { getAllTeachers } from '@/api/teacher';

vi.mock('@/api/assessment', () => ({ createAssessment: vi.fn(), modifyAssessment: vi.fn() }));
vi.mock('@/api/student', () => ({ getSchoolClassStudent: vi.fn(), getSchoolClassStudentOptions: vi.fn() }));
vi.mock('@/api/teacher', () => ({ getAllTeachers: vi.fn() }));
vi.mock('@/api/common', () => ({ getFileSign: vi.fn() }));

let wrapper: ReturnType<typeof mount> | undefined;
let filterWrapper: ReturnType<typeof mount> | undefined;
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getSchoolClassStudent).mockResolvedValue({ data: [{ schoolClass: '测试班' }] } as never);
  vi.mocked(getAllTeachers).mockResolvedValue({ data: [] } as never);
  vi.mocked(createAssessment).mockResolvedValue({ data: true } as never);
  vi.mocked(modifyAssessment).mockResolvedValue({ data: true } as never);
});
afterEach(() => { wrapper?.unmount(); wrapper = undefined; filterWrapper?.unmount(); filterWrapper = undefined; });

async function open(existing: Record<string, unknown> = {}) {
  wrapper = mount(AssessmentForm, { attachTo: document.body, global: { stubs: { Preview: true } } });
  (wrapper.vm as unknown as { openModal: (value: Record<string, unknown>) => void }).openModal(existing);
  await flushPromises();
  return wrapper;
}

describe('prepared assessment settings, real component with explicitly mocked HTTP', () => {
  it('links the create-dialog theme label to its own input rather than the list filter', async () => {
    filterWrapper = mount({ render: () => h(Form, { model: { theme: '' } }, () =>
      h(FormItem, { name: 'theme', label: '筛选考核主题' }, () => h(Input))) }, { attachTo: document.body });
    const w = await open();
    const form = w.findComponent(Form);
    const label = form.findAll('label').find(item => item.text() === '考核主题')!;
    const control = form.find('input');
    expect(label.exists()).toBe(true);
    expect(document.querySelectorAll(`[id="${control.attributes('id')}"]`)).toHaveLength(1);
    expect((label.element as HTMLLabelElement).control).toBe(control.element);
    expect((filterWrapper.find('label').element as HTMLLabelElement).control).toBe(filterWrapper.find('input').element);
  });

  it('opens a new fixed-question assessment without realtime or follow-up controls', async () => {
    const w = await open();
    const labels = w.findAllComponents(FormItem).map(item => item.props('label'));
    expect(labels).toContain('预置题目数量');
    for (const obsolete of ['是否答辩', '是否提问', '是否追问', '答辩时长（秒）', '每个提问回答时长（秒）']) {
      expect(labels).not.toContain(obsolete);
    }
    expect(w.findComponent(Modal).props('title')).toBe('新增预置面试考核');
    const model = w.findComponent(Form).props('model')!;
    expect(model).toMatchObject({ defense: false, question: true, followUp: false });
    expect(model.duration).toBeUndefined();
    expect(model.answerTime).toBe(120); // Required legacy metadata, NOT the authorized question deadline.
    expect(model.questionCount).toBeUndefined(); // Teacher explicitly selects it.
  });

  it('explains where actual per-question deadlines are configured and does not imply publication', async () => {
    const w = await open();
    expect(w.findComponent(Form).text()).toContain('每题回答时限在“准备题目”中逐题设置');
    expect(w.findComponent(Form).text()).toContain('保存考核设置不会发布试卷');
    expect(w.findComponent(Form).text()).toContain('预置面试不启用实时答辩或追问，不设整场时限');
    expect(createAssessment).not.toHaveBeenCalled();
  });

  it('bounds a new question count to the same fifty-question limit as the prepared editor', async () => {
    const w = await open();
    const item = w.findAllComponents(FormItem).find(item => item.props('name') === 'questionCount')!;
    expect(item).toBeDefined();
    const count = item.findComponent(InputNumber);
    expect(count.props()).toMatchObject({ min: 1, max: 50, precision: 0 });
  });

  it('preserves existing compatibility values without presenting them as prepared interview rules', async () => {
    const existing = { id: 21, theme: '历史考核', defense: true, duration: 600, question: false,
      followUp: true, followUpPrompt: '历史提示', followUpStandards: '历史标准', answerTime: 45, questionCount: 60 };
    const w = await open(existing);
    expect(w.findComponent(Form).props('model')).toMatchObject(existing);
    const labels = w.findAllComponents(FormItem).map(item => item.props('label'));
    expect(labels).not.toContain('是否追问');
    expect(labels).not.toContain('答辩时长（秒）');
    expect(w.findComponent(Form).text()).toContain('历史兼容字段保留；它们不参与预置面试的题序、追问或时限');
    const item = w.findAllComponents(FormItem).find(item => item.props('name') === 'questionCount')!;
    expect(item.findComponent(InputNumber).props('max')).toBe(100); // Do not rewrite old stored counts.
    expect(modifyAssessment).not.toHaveBeenCalled();
  });

  it('opening a new assessment after an old one does not carry over its realtime settings', async () => {
    const w = await open({ id: 21, defense: true, duration: 600, question: false, followUp: true, answerTime: 45 });
    (w.vm as unknown as { openModal: () => void }).openModal();
    await flushPromises();
    const model = w.findComponent(Form).props('model')!;
    expect(model).toMatchObject({ defense: false, question: true, followUp: false, answerTime: 120 });
    expect(model.duration).toBeUndefined();
    expect(model.id).toBeUndefined();
    expect(model.classAndStudent).toEqual([]);
  });
});
