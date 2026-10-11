import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  hasSession: vi.fn(),
  markSession: vi.fn(),
  clearSession: vi.fn(),
  loginApi: vi.fn(),
  getUserInfo: vi.fn(),
  logoutApi: vi.fn(),
}));

vi.mock("@/utils/auth", () => ({
  hasSession: mocks.hasSession,
  markSession: mocks.markSession,
  clearSession: mocks.clearSession,
}));

vi.mock("@/api/common", () => ({
  loginApi: mocks.loginApi,
  getUserInfo: mocks.getUserInfo,
  logoutApi: mocks.logoutApi,
}));

import { useAuthStore } from "@/stores/auth";

describe("authentication store", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.hasSession.mockReturnValue(false);
    setActivePinia(createPinia());
  });

  it("trims the account, marks the cookie session and never alters the password", async () => {
    mocks.loginApi.mockResolvedValue({ data: null });
    const store = useAuthStore();

    await store.login({ account: "  student01  ", password: " secret value " });

    expect(mocks.loginApi).toHaveBeenCalledWith({
      account: "student01",
      password: " secret value ",
    });
    expect(mocks.markSession).toHaveBeenCalledOnce();
    expect(store.authenticated).toBe(true);
  });

  it("hydrates identity and role from the backend user detail", async () => {
    mocks.getUserInfo.mockResolvedValue({
      data: { id: 12, name: "Student", role: "STUDENT" },
    });
    const store = useAuthStore();

    await store.getInfo();

    expect(store.id).toBe(12);
    expect(store.role).toBe("STUDENT");
    expect(store.info.name).toBe("Student");
  });

  it("clears local authentication even when server logout fails", async () => {
    mocks.hasSession.mockReturnValue(true);
    mocks.logoutApi.mockRejectedValue(new Error("offline"));
    setActivePinia(createPinia());
    const store = useAuthStore();
    store.id = "12";
    store.role = "STUDENT";
    store.info = { id: 12 };

    await expect(store.logout()).rejects.toThrow("offline");

    expect(store.authenticated).toBe(false);
    expect(store.id).toBe("");
    expect(store.role).toBe("");
    expect(store.info).toEqual({});
    expect(mocks.clearSession).toHaveBeenCalledOnce();
  });

  it("supports local-only logout for expired sessions", async () => {
    mocks.hasSession.mockReturnValue(true);
    setActivePinia(createPinia());
    const store = useAuthStore();

    await store.logout(false);

    expect(mocks.logoutApi).not.toHaveBeenCalled();
    expect(mocks.clearSession).toHaveBeenCalledOnce();
  });
});
