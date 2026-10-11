import { describe, expect, it } from "vitest";
import {
  canAccessRole,
  defaultPathForRole,
  normalizeRole,
  requiredRolesForPath,
} from "@/router/access";

describe("role route access", () => {
  it("normalizes only the supported backend roles", () => {
    expect(normalizeRole(" student ")).toBe("STUDENT");
    expect(normalizeRole("TEACHER")).toBe("TEACHER");
    expect(normalizeRole("admin")).toBe("ADMIN");
    expect(normalizeRole("SUPERUSER")).toBeUndefined();
    expect(normalizeRole(undefined)).toBeUndefined();
  });

  it("maps each authenticated role to its own landing page", () => {
    expect(defaultPathForRole("STUDENT")).toBe("/studentDashboard");
    expect(defaultPathForRole("TEACHER")).toBe("/teacherDashboard");
    expect(defaultPathForRole("ADMIN")).toBe("/adminDashboard");
    expect(defaultPathForRole("unknown")).toBe("/login");
  });

  it("rejects cross-role routes even when old dynamic routes remain registered", () => {
    expect(canAccessRole(["teacher"], "STUDENT")).toBe(false);
    expect(canAccessRole(["admin"], "TEACHER")).toBe(false);
    expect(canAccessRole(["student"], "STUDENT")).toBe(true);
  });

  it("allows public and layout routes without role metadata", () => {
    expect(canAccessRole(undefined, "STUDENT")).toBe(true);
    expect(canAccessRole([], "ADMIN")).toBe(true);
  });

  it("protects known role paths before their dynamic routes are registered", () => {
    expect(requiredRolesForPath("/studentDashboard")).toEqual(["STUDENT"]);
    expect(requiredRolesForPath("/teacherDashboard?tab=1")).toEqual(["TEACHER"]);
    expect(requiredRolesForPath("/adminDashboard/overview")).toEqual(["ADMIN"]);
    expect(requiredRolesForPath("/resultOver?id=17")).toEqual(["STUDENT"]);
    expect(requiredRolesForPath("/prepared-interview/8/preflight")).toEqual(["STUDENT"]);
    expect(requiredRolesForPath("/prepared-results/8")).toEqual(["STUDENT"]);
    expect(requiredRolesForPath("/login")).toBeUndefined();
  });
});
