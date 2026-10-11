// Regression: ISSUE-003 — profile edits incorrectly forced administrators to reset passwords.
// Found by /qa on 2026-10-03
// Report: .gstack/qa-reports/qa-report-localhost-2026-10-03.md

import { describe, expect, it } from "vitest";
import {
  accountMutationPayload,
  passwordRequiredFor,
} from "@/views/adminDashboard/accountForm";

describe("administrator account edit form", () => {
  it("requires a password when creating an account", () => {
    expect(passwordRequiredFor(false)).toBe(true);
  });

  it("allows profile-only edits without a password", () => {
    expect(passwordRequiredFor(true)).toBe(false);
    expect(
      accountMutationPayload(
        {
          id: 7,
          name: "Updated User",
          account: "user-7",
          password: "",
        },
        true
      )
    ).toEqual({ id: 7, name: "Updated User", account: "user-7" });
  });

  it("keeps a deliberate password reset in an edit request", () => {
    expect(
      accountMutationPayload(
        { id: 7, account: "user-7", password: "new-password" },
        true
      )
    ).toEqual({ id: 7, account: "user-7", password: "new-password" });
  });
});
