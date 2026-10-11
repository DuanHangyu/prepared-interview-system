import { describe, expect, it, vi } from "vitest";
import dashboardSource from "../../src/views/studentDashboard/index.vue?raw";

vi.mock("@/components/Layout", () => ({
  Layout: { name: "LayoutStub", template: "<div />" },
}));

vi.mock("@/components/Layout/componets/StudentContainter.vue", () => ({
  default: { name: "StudentContainerStub", template: "<div />" },
}));

import { studnetRoutes } from "@/router";

describe("student result route contract", () => {
  it("registers the current result and detail destinations without the legacy route", () => {
    const children = studnetRoutes.flatMap((route) => route.children || []);

    expect(children).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: "/resultOver", name: "ResultOverPage" }),
        expect.objectContaining({ path: "/prepared-results/:assessmentId", name: "PreparedResults",meta:{role:['student']} }),
        expect.objectContaining({
          path: "/studyDetail",
          name: "StudentStudyDetail",
        }),
      ])
    );
    expect(children.map((route) => route.path)).not.toContain("/result");
  });

  it("routes assigned students to prepared preflight instead of the realtime defense", () => {
    const children=studnetRoutes.flatMap(route=>route.children||[]);
    expect(children).toContainEqual(expect.objectContaining({path:'/prepared-interview/:assessmentId/preflight',name:'PreparedPreflight',meta:{role:['student']}}));
    expect(dashboardSource).toMatch(
      /name:\s*["']PreparedPreflight["'],\s*params:\s*\{\s*assessmentId:\s*String\(e\?\.id\)\s*\}/
    );
    expect(dashboardSource).not.toContain('router.push(`/question?id=');
    expect(dashboardSource).not.toContain('router.push(`/dashboard?id=');
  });
});
