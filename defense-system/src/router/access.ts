export type ApplicationRole = "ADMIN" | "TEACHER" | "STUDENT";

const HOME_BY_ROLE: Record<ApplicationRole, string> = {
  ADMIN: "/adminDashboard",
  TEACHER: "/teacherDashboard",
  STUDENT: "/studentDashboard",
};

const ROLES_BY_PROTECTED_PATH: Array<{
  prefix: string;
  roles: ApplicationRole[];
}> = [
  { prefix: "/adminDashboard", roles: ["ADMIN"] },
  { prefix: "/teacherDashboard", roles: ["TEACHER"] },
  { prefix: "/studentDashboard", roles: ["STUDENT"] },
  { prefix: "/prepared-interview", roles: ["STUDENT"] },
  { prefix: "/prepared-results", roles: ["STUDENT"] },
  { prefix: "/dashboard", roles: ["STUDENT"] },
  { prefix: "/question", roles: ["STUDENT"] },
  { prefix: "/resultOver", roles: ["STUDENT"] },
];

export function normalizeRole(role: unknown): ApplicationRole | undefined {
  const normalized = String(role || "").trim().toUpperCase();
  if (normalized === "ADMIN" || normalized === "TEACHER" || normalized === "STUDENT") {
    return normalized;
  }
  return undefined;
}

export function defaultPathForRole(role: unknown) {
  const normalized = normalizeRole(role);
  return normalized ? HOME_BY_ROLE[normalized] : "/login";
}

export function requiredRolesForPath(path: unknown): ApplicationRole[] | undefined {
  const normalizedPath = String(path || "").split(/[?#]/, 1)[0];
  return ROLES_BY_PROTECTED_PATH.find(
    ({ prefix }) =>
      normalizedPath === prefix || normalizedPath.startsWith(`${prefix}/`)
  )?.roles;
}

export function canAccessRole(
  requiredRoles: unknown,
  currentRole: unknown
) {
  if (!Array.isArray(requiredRoles) || requiredRoles.length === 0) {
    return true;
  }
  const normalized = normalizeRole(currentRole);
  if (!normalized) {
    return false;
  }
  return requiredRoles.some(
    (role) => normalizeRole(role) === normalized
  );
}
