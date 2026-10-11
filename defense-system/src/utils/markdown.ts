import MarkdownIt from "markdown-it";

const markdown = new MarkdownIt({
  html: false,
  linkify: false,
  typographer: false,
});

// Interview questions are display text. They do not need network-capable
// images or clickable links, both of which can be induced by untrusted model
// output even when script execution is disabled.
markdown.disable(["image", "link"]);

export function renderSafeMarkdown(value: unknown): string {
  if (typeof value !== "string" || value.length === 0) {
    return "";
  }
  return markdown.render(value);
}
