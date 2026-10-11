import { afterEach, describe, expect, it, vi } from "vitest";

describe("settings store", () => {
  afterEach(() => {
    localStorage.clear();
    vi.resetModules();
  });

  it("ignores corrupted persisted layout settings during application startup", async () => {
    localStorage.setItem("layout-setting", "{not-json");

    await expect(import("@/stores/settings")).resolves.toBeDefined();
  });
});
