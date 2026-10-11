import { expect, Page, test } from "@playwright/test";

type Role = "ADMIN" | "TEACHER" | "STUDENT";

const TOKEN_KEY = "Admin-Token-Defense-System";

test.describe("role-aware application workflows", () => {
  test("redirects an unauthenticated protected route to login", async ({ page }) => {
    await page.goto("/studentDashboard");

    await expect(page).toHaveURL(/\/login\?redirect=\/studentDashboard$/);
    await expect(page.getByRole("heading", { name: "欢迎登录" })).toBeVisible();
  });

  for (const scenario of [
    {
      role: "STUDENT" as const,
      path: "/studentDashboard",
      label: "AI 面试学生端",
    },
    {
      role: "TEACHER" as const,
      path: "/teacherDashboard",
      label: "Teacher Console",
    },
    {
      role: "ADMIN" as const,
      path: "/adminDashboard",
      label: "Admin Console",
    },
  ]) {
    test(`${scenario.role.toLowerCase()} lands in its own workspace`, async ({
      page,
    }) => {
      await authenticate(page);
      await mockRoleApi(page, scenario.role);

      await page.goto("/");

      await expect(page).toHaveURL(new RegExp(`${scenario.path}$`));
      await expect(page.getByText(scenario.label, { exact: true }).first()).toBeVisible();
    });
  }

  test("an authenticated direct visit to login resolves the role before redirecting", async ({
    page,
  }) => {
    await authenticate(page);
    await mockRoleApi(page, "TEACHER");

    await page.goto("/login");

    await expect(page).toHaveURL(/\/teacherDashboard$/);
    await expect(page.getByText("Teacher Console", { exact: true })).toBeVisible();
  });

  test("blocks a teacher from an unregistered student route", async ({ page }) => {
    await authenticate(page);
    await mockRoleApi(page, "TEACHER");

    await page.goto("/studentDashboard");

    await expect(page).toHaveURL(/\/teacherDashboard$/);
    await expect(page.getByText("Teacher Console", { exact: true })).toBeVisible();
    await expect(page.getByText("AI 面试学生端", { exact: true })).toHaveCount(0);
  });

  test("teacher opens assessment settings", async ({ page }) => {
    await authenticate(page);
    await mockRoleApi(page, "TEACHER");
    await page.goto("/teacherDashboard");

    await page
      .getByRole("navigation", { name: "教师端导航" })
      .getByRole("button")
      .filter({ hasText: "考核设置" })
      .click();

    await expect(page).toHaveURL(/\/teacherDashboard\?tab=1$/);
    await expect(
      page.getByText("配置预置面试基础信息、题目数量、学生分配与预约安排", { exact: true })
    ).toBeVisible();
  });

  test("teacher opens a student's assessment report", async ({ page }) => {
    await authenticate(page);
    await mockRoleApi(page, "TEACHER", { includeStudyRecord: true });
    await page.goto("/teacherDashboard");

    await expect(page.getByText("端到端考核报告", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "查看详情", exact: true }).click();

    await expect(page).toHaveURL(/\/studyDetail\?id=51$/);
    await expect(page.getByRole("heading", { name: "端到端考核报告" })).toBeVisible();
    await expect(page.getByText("综合得分", { exact: true })).toBeVisible();
  });

  test("administrator opens teacher management", async ({ page }) => {
    await authenticate(page);
    await mockRoleApi(page, "ADMIN");
    await page.goto("/adminDashboard");

    await page
      .getByRole("navigation", { name: "管理端导航" })
      .getByRole("button")
      .filter({ hasText: "教师管理" })
      .click();

    await expect(page).toHaveURL(/\/adminDashboard\?tab=1$/);
    await expect(
      page.getByText("管理教师登录账号、联系方式和创建记录", { exact: true })
    ).toBeVisible();
  });

  test("student enters prepared preflight from the dashboard, not realtime defense", async ({ page }) => {
    await authenticate(page);
    await mockRoleApi(page, "STUDENT", { includeTodo: true });
    await page.goto("/studentDashboard");

    await page
      .getByRole("navigation", { name: "学生端导航" })
      .getByRole("button")
      .filter({ hasText: "我的考核" })
      .click();
    await expect(page.getByRole("heading", { name: "端到端答辩任务" })).toBeVisible();
    await page.getByRole("button", { name: /面试预检/ }).first().click();

    await expect(page).toHaveURL(/\/prepared-interview\/17\/preflight$/);
    await expect(page.getByRole('heading',{name:'面试预检',exact:true})).toBeVisible();
    await expect(page.getByTestId('recording-consent')).not.toBeChecked();
    const attempt=page.getByRole('region',{name:'正式作答与原回答恢复',exact:true});
    await expect(attempt.getByText(/正式开考尚未开放/)).toBeVisible();
    await expect(attempt.getByRole('button',{name:'开考并开始第一题',exact:true})).toBeDisabled();
    await expect(attempt.getByRole('alert')).toHaveCount(0);
  });

  test("clears an expired session and returns to login", async ({ page }) => {
    await authenticate(page);
    await mockRoleApi(page, "STUDENT", { expiredSession: true });

    await page.goto("/studentDashboard");

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "欢迎登录" })).toBeVisible();
    await expect
      .poll(() => page.evaluate((key) => localStorage.getItem(key), TOKEN_KEY))
      .toBeNull();
  });

  test("student workspace remains usable on a mobile viewport", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await authenticate(page);
    await mockRoleApi(page, "STUDENT");

    await page.goto("/studentDashboard");

    await expect(page.getByText("AI 面试学生端", { exact: true })).toBeVisible();
    await expect(
      page
        .getByRole("navigation", { name: "学生端导航" })
        .getByRole("button")
        .filter({ hasText: "帮助中心" })
    ).toBeVisible();
    const viewport = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }));
    expect(viewport.scrollWidth).toBeLessThanOrEqual(viewport.clientWidth + 1);
  });
});

async function authenticate(page: Page) {
  await page.addInitScript((tokenKey) => {
    window.localStorage.setItem(tokenKey, "e2e-role-token");
  }, TOKEN_KEY);
}

async function mockRoleApi(
  page: Page,
  role: Role,
  options: {
    includeTodo?: boolean;
    includeStudyRecord?: boolean;
    expiredSession?: boolean;
  } = {}
) {
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (!url.pathname.startsWith("/api/")) {
      await route.continue();
      return;
    }

    if (url.pathname === "/api/user-detail") {
      if (options.expiredSession) {
        await json(route, { code: 401, message: "登录已过期", data: null }, 401);
        return;
      }
      await json(route, {
        code: 200,
        data: {
          id: role === "STUDENT" ? 17 : role === "TEACHER" ? 27 : 37,
          name: `E2E ${role}`,
          account: role.toLowerCase(),
          role,
        },
      });
      return;
    }

    if (url.pathname === "/api/front/student-assessment/statistic") {
      await json(route, {
        code: 200,
        data: {
          all: options.includeTodo ? 1 : 0,
          toAppoint: 0,
          todo: options.includeTodo ? 1 : 0,
          analysis: 0,
          done: 0,
        },
      });
      return;
    }

    if (url.pathname === "/api/front/student-assessment/todo-list") {
      const records = options.includeTodo
        ? [
            {
              id: 17,
              theme: "端到端答辩任务",
              state: 0,
              defense: true,
              question: false,
              finishDefense: false,
              timePeriod: "2020-01-01 09:00:00",
            },
          ]
        : [];
      await json(route, { code: 200, data: { records, total: records.length } });
      return;
    }

    if (url.pathname === "/api/front/student-assessment/get-machine-time") {
      await json(route, { code: 200, data: "2026-10-03 12:00:00" });
      return;
    }

    if (url.pathname === '/api/backend/prepared-interviews/17/preflight-context') {
      // Mock-based navigation regression only; real preflight integration is tested separately.
      await json(route,{code:200,data:{studentId:17,deploymentId:'prepared-browser-mock',workflowEnabled:false,eligibility:{
        assessmentId:17,theme:'端到端答辩任务',questionCount:0,publicationReady:false,appointmentRequired:false,
        phase:'PAPER_NOT_PUBLISHED',message:'试卷尚未发布',serverTime:'2026-10-04T20:00:00Z',appointmentStartsAt:null,latestFirstStartAt:null,
      }}});
      return;
    }

    if (url.pathname === '/api/backend/prepared-interviews/17/attempts/current' && route.request().method() === 'GET') {
      // A closed workflow with no original attempt must not look like corrupt attempt data.
      await json(route,{code:200,data:null});
      return;
    }

    if (url.pathname === "/api/front/student-assessment/assessment-detail") {
      await json(route, {
        code: 200,
        data: {
          id: 17,
          theme: "端到端答辩任务",
          state: 0,
          defense: true,
          question: false,
          duration: 600,
        },
      });
      return;
    }

    if (url.pathname === "/api/backend/study-record/list") {
      const records = options.includeStudyRecord
        ? [
            {
              id: 51,
              studentName: "E2E STUDENT",
              theme: "端到端考核报告",
              totalScore: 100,
              score: 82,
              checkScore: 82,
              state: 1,
              assessmentTime: "2026-10-03 10:00:00",
            },
          ]
        : [];
      await json(route, { code: 200, data: { records, total: records.length } });
      return;
    }

    if (url.pathname === "/api/backend/study-record/assessment-detail") {
      await json(route, {
        code: 200,
        data: {
          id: 51,
          theme: "端到端考核报告",
          score: 82,
          defenseScore: 82,
          totalScore: 100,
          defense: { defenseContent: "端到端答辩内容", defenseScore: 82 },
          result: [],
          analysis: {
            analysis: "表达完整，结构清晰。",
            suggestions: ["继续加强案例细节"],
            strengths: ["逻辑清晰"],
            weaknesses: ["细节不足"],
          },
        },
      });
      return;
    }

    await json(route, { code: 200, data: { records: [], total: 0 } });
  });
}

async function json(
  route: Parameters<Parameters<Page["route"]>[1]>[0],
  body: unknown,
  status = 200
) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}
