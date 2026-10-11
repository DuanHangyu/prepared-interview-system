import { expect, Page, test } from "@playwright/test";

test.describe("student result routing", () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem("Admin-Token-Defense-System", "e2e-student-token");
    });
  });

  test("requires an assessment id before polling analysis", async ({ page }) => {
    const requests = await mockApi(page);

    await page.goto("/resultOver");

    await expect(page.getByText("需要重新检查")).toBeVisible();
    expect(requests.detail).toBe(0);
  });

  test("opens the matching study detail after analysis completes", async ({ page }) => {
    await mockApi(page, { resultVisible: true });

    await page.goto("/resultOver?id=17");
    await expect(page.getByRole("heading", { name: "考核分析已完成" })).toBeVisible();
    await page.getByRole("button", { name: /查看分析结果/ }).click();

    await expect(page).toHaveURL(/\/studyDetail\?id=17$/);
  });
});

async function mockApi(page: Page, options: { resultVisible?: boolean } = {}) {
  const requests = { detail: 0 };
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    // Vite serves source modules from /src/api/* in development. Those are
    // application code, not backend calls, and must never be replaced by a
    // JSON mock response.
    if (!url.pathname.startsWith("/api/")) {
      await route.continue();
      return;
    }
    if (url.pathname === "/api/user-detail") {
      await route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({
          code: 200,
          data: { id: 9, name: "E2E Student", role: "STUDENT" },
        }),
      });
      return;
    }
    if (url.pathname === "/api/front/student-assessment/assessment-detail") {
      requests.detail += 1;
      await route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({
          code: 200,
          data: {
            id: Number(url.searchParams.get("id")),
            state: 1,
            showResult: options.resultVisible ?? false,
          },
        }),
      });
      return;
    }
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data: {} }),
    });
  });
  return requests;
}
