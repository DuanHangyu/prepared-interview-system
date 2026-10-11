export type QuestionRecoveryStep = "generate" | "answer";
export type QuestionReconnectAction = "start-assessment" | "generate_question";

export function getQuestionRecoveryStep(
  content: string
): QuestionRecoveryStep | null {
  if (content === "考核已完成") {
    return null;
  }
  if (content.includes("重新回答") || content.includes("录音数据")) {
    return "answer";
  }
  return "generate";
}

export function getQuestionReconnectAction(
  interviewStarted: boolean,
  questionCount: number
): QuestionReconnectAction | null {
  if (!interviewStarted) {
    return null;
  }
  return questionCount === 0 ? "start-assessment" : "generate_question";
}
