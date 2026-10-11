// Regression: expired appointment slots remained selectable and assessment entry
// did not unlock when server time passed the scheduled start.

import { describe, expect, it } from "vitest";
import dayjs from "dayjs";
import dashboardSource from "../../src/views/studentDashboard/index.vue?raw";
import {
  canStartAssessment,
  isAppointmentTimeAvailable,
} from "@/views/studentDashboard/assessmentTime";

describe("student assessment time rules", () => {
  const now = dayjs("2026-10-03 16:45:30");

  it("hides appointment slots whose start time has already passed", () => {
    expect(
      isAppointmentTimeAvailable(
        { timePeriod: "2026-10-03 16:45:00", full: false },
        now
      )
    ).toBe(false);
  });

  it("keeps future appointment slots available when they are not full", () => {
    expect(
      isAppointmentTimeAvailable(
        { timePeriod: "2026-10-03 16:46:00", full: false },
        now
      )
    ).toBe(true);
    expect(
      isAppointmentTimeAvailable(
        { timePeriod: "2026-10-03 16:46:00", full: true },
        now
      )
    ).toBe(false);
  });

  it("unlocks an assessment as soon as its scheduled start is reached", () => {
    expect(canStartAssessment("2026-10-03 16:45:30", now)).toBe(true);
    expect(canStartAssessment("2026-10-03 16:46:00", now)).toBe(false);
  });

  it("allows assessments that do not require an appointment to start immediately", () => {
    expect(canStartAssessment(undefined, now)).toBe(true);
    expect(canStartAssessment(null, now)).toBe(true);
  });

  it("enters read-only preflight without granting exam access from cached client time", () => {
    expect(dashboardSource).toContain(
      'name: "PreparedPreflight", params: { assessmentId: String(e?.id) }'
    );
    expect(dashboardSource).not.toContain("if (!canStartAssessment(e?.timePeriod, serverTime.value)) return;");
    expect(dashboardSource).not.toContain(
      "serverTime.value?.isBefore(dayjs(e?.timePeriod))"
    );
  });
});
