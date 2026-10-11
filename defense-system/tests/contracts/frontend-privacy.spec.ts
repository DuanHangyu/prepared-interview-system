import { describe, expect, it } from "vitest";

import questionsPage from "../../src/views/studentDashboard/questions/index2.vue?raw";
import assessmentForm from "../../src/views/teacherDashboard/components/HandleAssessment.vue?raw";

describe("frontend privacy logging contract", () => {
  it("does not log interview messages or malformed payload bodies", () => {
    expect(questionsPage).not.toContain("console.log(msg)");
    expect(questionsPage).not.toContain('error, event.data');
  });

  it("does not log uploaded file objects", () => {
    expect(assessmentForm).not.toContain("console.log(info.file, info.fileList)");
  });
});
