// Regression: ISSUE-001 — the assessment card exposed a blank, unsupported follow-up count.
// Found by /qa on 2026-10-03
// Report: .gstack/qa-reports/qa-report-localhost-2026-10-03.md

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

describe("assessment settings summary", () => {
  it("does not render a follow-up count that the assessment model cannot configure", () => {
    const source = readFileSync(
      "src/views/teacherDashboard/AssessSetting.vue",
      "utf8"
    );

    expect(source).not.toContain("followUpCount");
    expect(source).not.toContain("追问次数");
  });
});
