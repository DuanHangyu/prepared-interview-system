import { beforeEach, describe, expect, it } from "vitest";

import { clearSession, hasSession, isSessionMarkerStorageEvent, markSession } from "@/utils/auth";

const sessionMarkerKey = "Admin-Token-Defense-System";

describe("browser session marker", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("replaces a legacy readable token with a non-secret marker", () => {
    window.localStorage.setItem(sessionMarkerKey, "legacy-readable-token");

    expect(hasSession()).toBe(true);
    expect(window.localStorage.getItem(sessionMarkerKey)).toBe("1");
  });

  it("marks and clears only the non-secret login hint", () => {
    expect(hasSession()).toBe(false);markSession();
    expect(hasSession()).toBe(true);expect(window.localStorage.getItem(sessionMarkerKey)).toBe("1");
    clearSession();expect(hasSession()).toBe(false);
  });

  it.each([sessionMarkerKey, null])("recognizes a local login marker change or clear (%s)", key => {
    expect(isSessionMarkerStorageEvent(new StorageEvent("storage", {key, storageArea: window.localStorage}))).toBe(true);
  });

  it.each(["prepared-ui-theme", "prepared-other-command", ""])("ignores unrelated local storage (%s)", key => {
    expect(isSessionMarkerStorageEvent(new StorageEvent("storage", {key, storageArea: window.localStorage}))).toBe(false);
  });

  it.each([sessionMarkerKey, null])("ignores session-only storage (%s)", key => {
    expect(isSessionMarkerStorageEvent(new StorageEvent("storage", {key, storageArea: window.sessionStorage}))).toBe(false);
  });

  it.each([sessionMarkerKey, null])("does not infer authentication from a missing storage area (%s)", key => {
    expect(isSessionMarkerStorageEvent(new StorageEvent("storage", {key}))).toBe(false);
  });
});
