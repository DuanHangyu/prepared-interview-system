import { expect, Page, test } from "@playwright/test";

const runtimeEnvironment = (
  globalThis as typeof globalThis & {
    process?: { env?: Record<string, string | undefined> };
  }
).process?.env || {};
const password = runtimeEnvironment.LIVE_E2E_PASSWORD;
const accounts = {
  admin: runtimeEnvironment.LIVE_E2E_ADMIN_ACCOUNT || "codex_live_admin",
  teacher: runtimeEnvironment.LIVE_E2E_TEACHER_ACCOUNT || "codex_live_teacher",
  student: runtimeEnvironment.LIVE_E2E_STUDENT_ACCOUNT || "codex_live_student",
};

test.describe("live backend role workflows", () => {
  test.beforeAll(() => {
    if (!password) {
      throw new Error("LIVE_E2E_PASSWORD is required for live browser tests");
    }
  });

  test("administrator reaches teacher management with database data", async ({
    page,
  }) => {
    await login(page, accounts.admin, "/adminDashboard");
    await page
      .getByRole("navigation", { name: "管理端导航" })
      .getByRole("button")
      .filter({ hasText: "教师管理" })
      .click();

    await expect(page.getByText("Codex 联调教师", { exact: true })).toBeVisible();
  });

  test("teacher opens a report returned by the real backend", async ({ page }) => {
    await login(page, accounts.teacher, "/teacherDashboard");
    await expect(page.getByText("Codex 真实联调考核", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "查看详情", exact: true }).click();

    await expect(page).toHaveURL(/\/studyDetail\?id=\d+$/);
    await expect(
      page.getByRole("heading", { name: "Codex 真实联调考核" })
    ).toBeVisible();
  });

  test("student opens a completed report returned by the real backend", async ({
    page,
  }) => {
    await login(page, accounts.student, "/studentDashboard");
    await page
      .getByRole("navigation", { name: "学生端导航" })
      .getByRole("button")
      .filter({ hasText: "我的报告" })
      .click();

    await expect(page.getByText("Codex 真实联调考核", { exact: true }).first()).toBeVisible();
    await page.getByRole("button", { name: /查看报告/ }).first().click();
    await expect(page).toHaveURL(/\/studyDetail\?id=\d+$/);
    await expect(
      page.getByRole("heading", { name: "Codex 真实联调考核" })
    ).toBeVisible();
  });

  test("student dashboard loads against the real backend on mobile", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page, accounts.student, "/studentDashboard");

    await expect(page.getByText("AI 面试学生端", { exact: true })).toBeVisible();
    const viewport = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }));
    expect(viewport.scrollWidth).toBeLessThanOrEqual(viewport.clientWidth + 1);
  });
});

async function login(page: Page, account: string, expectedPath: string) {
  await page.goto("/login");
  await page.getByPlaceholder("请输入账号").fill(account);
  await page.getByPlaceholder("请输入密码").fill(password || "");
  await page
    .getByRole("button")
    .filter({ hasText: "立即登录" })
    .click();
  await expect(page).toHaveURL(new RegExp(`${expectedPath}$`));
}
