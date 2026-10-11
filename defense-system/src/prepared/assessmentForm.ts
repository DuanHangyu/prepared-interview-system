/** View defaults only, never authorization or published question rules.
 * answerTime is required by the reused legacy settings DTO; the prepared
 * workflow always uses each immutable question's answerSeconds instead. */
export function initialPreparedAssessmentForm(existing: Record<string, unknown> = {}): Record<string, unknown> {
  const defaults = {
    theme: '', assessmentCriteria: '', assessmentRequirements: '',
    duration: undefined, questionCount: undefined, answerTime: 120,
    followUp: false, followUpPrompt: '', followUpStandards: '',
    totalScore: undefined, passScore: undefined, defense: false, question: true,
    showResult: false, needAppoint: true,
    assessmentFiles: [], participatingStudents: [], classAndStudent: [], inviteTeachers: [],
  };
  if (existing.id) return { ...defaults, ...existing };
  return { ...defaults, ...existing, defense: false, question: true, followUp: false,
    duration: undefined, answerTime: 120, followUpPrompt: '', followUpStandards: '' };
}
