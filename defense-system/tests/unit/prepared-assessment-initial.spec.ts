import { describe, expect, it } from 'vitest';
import { initialPreparedAssessmentForm } from '@/prepared/assessmentForm';

describe('prepared form initial values and legacy compatibility, not authorization', () => {
  it('starts a fixed-question assessment with no realtime defense, follow-up or overall duration', () => {
    const model = initialPreparedAssessmentForm();
    expect(model).toMatchObject({ defense: false, question: true, followUp: false, answerTime: 120,
      showResult: false, needAppoint: true, assessmentFiles: [], classAndStudent: [], inviteTeachers: [] });
    expect(model.duration).toBeUndefined();
    expect(model.questionCount).toBeUndefined();
  });
  it('returns fresh mutable selection arrays for each form', () => {
    const first = initialPreparedAssessmentForm();
    const second = initialPreparedAssessmentForm();
    (first.classAndStudent as unknown[]).push({ value: '**class**__一班' });
    (first.inviteTeachers as number[]).push(9);
    (first.assessmentFiles as unknown[]).push({ name: 'one.pdf' });
    expect(second.classAndStudent).toEqual([]);
    expect(second.inviteTeachers).toEqual([]);
    expect(second.assessmentFiles).toEqual([]);
  });
  it('keeps all existing compatibility values, including explicit nulls, and does not mutate the source', () => {
    const existing = Object.freeze({ id: 21, question: false, defense: true, duration: 600,
      questionCount: null, answerTime: 45, followUp: true, followUpPrompt: '历史提示', followUpStandards: '历史标准' });
    expect(initialPreparedAssessmentForm(existing)).toMatchObject(existing);
    expect(existing.questionCount).toBeNull();
  });
  it('prefilled new forms cannot inherit realtime settings from a copied object without an existing id', () => {
    const copied = { theme: '复制主题', defense: true, question: false, duration: 600, answerTime: 45,
      followUp: true, followUpPrompt: '不要恢复实时追问', followUpStandards: '不要恢复实时追问' };
    expect(initialPreparedAssessmentForm(copied)).toMatchObject({ theme: '复制主题', defense: false,
      question: true, duration: undefined, followUp: false, answerTime: 120, followUpPrompt: '', followUpStandards: '' });
    expect(copied.followUp).toBe(true);
  });
});
