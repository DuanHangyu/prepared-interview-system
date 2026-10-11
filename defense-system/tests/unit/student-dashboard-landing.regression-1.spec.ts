// Regression: ISSUE-002 — booked assessments disappeared from the student landing dashboard.
// Found by /qa on 2026-10-03
// Report: .gstack/qa-reports/qa-report-localhost-2026-10-03.md

import { describe, expect, it } from "vitest";
import { resolveInitialDashboardTab } from "@/views/studentDashboard/landingState";

describe("student dashboard landing state", () => {
  it("opens booked assessments when there is nothing left to appoint", () => {
    expect(
      resolveInitialDashboardTab({
        toAppoint: 0,
        todo: 1,
        analysis: 0,
        done: 0,
      })
    ).toBe(2);
  });

  it("keeps pending appointments ahead of later workflow stages", () => {
    expect(
      resolveInitialDashboardTab({
        toAppoint: 1,
        todo: 1,
        analysis: 1,
        done: 1,
      })
    ).toBe(1);
  });

  it("shows finished work when no actionable assessment remains", () => {
    expect(
      resolveInitialDashboardTab({
        toAppoint: 0,
        todo: 0,
        analysis: 1,
        done: 0,
      })
    ).toBe(4);
  });
});
