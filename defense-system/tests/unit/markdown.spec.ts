import { describe, expect, it } from "vitest";
import { renderSafeMarkdown } from "@/utils/markdown";

describe("safe markdown rendering", () => {
  it("keeps ordinary formatting without rendering active or remote content", () => {
    expect(renderSafeMarkdown("**重点**")).toContain("<strong>重点</strong>");

    const rendered = renderSafeMarkdown(
      '<img src=x onerror=alert(1)>\n\n[x](javascript:alert(1))\n\n![track](https://example.com/pixel.png)'
    );
    expect(rendered).toContain("&lt;img src=x onerror=alert(1)&gt;");
    expect(rendered).not.toContain("<script");
    expect(rendered).not.toContain("<img");
    expect(rendered).not.toContain("<a");
    expect(rendered).not.toContain('src="https://example.com');
  });

  it("treats non-string values as empty content", () => {
    expect(renderSafeMarkdown(undefined)).toBe("");
    expect(renderSafeMarkdown({ title: "question" })).toBe("");
  });
});
