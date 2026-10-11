// Regression: a generic question WebSocket failure left the page permanently
// blank instead of offering a retry for question generation.

import { describe, expect, it } from "vitest";
import {
  getQuestionReconnectAction,
  getQuestionRecoveryStep,
} from "@/views/studentDashboard/questions/recovery";

describe("question WebSocket recovery", () => {
  it("turns an unexpected startup failure into a question-generation retry", () => {
    expect(getQuestionRecoveryStep("处理消息失败，请稍后重试")).toBe("generate");
  });

  it("keeps recording failures scoped to the current answer", () => {
    expect(getQuestionRecoveryStep("录音数据不完整，请重新回答")).toBe("answer");
  });

  it("does not present a completed assessment as a recoverable failure", () => {
    expect(getQuestionRecoveryStep("考核已完成")).toBeNull();
  });

  it("restarts question generation after reconnecting an interrupted empty page", () => {
    expect(getQuestionReconnectAction(true, 0)).toBe("start-assessment");
    expect(getQuestionReconnectAction(true, 1)).toBe("generate_question");
    expect(getQuestionReconnectAction(false, 0)).toBeNull();
  });
});
