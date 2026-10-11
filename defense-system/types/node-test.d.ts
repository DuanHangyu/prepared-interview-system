declare module "node:fs" {
  export function readFileSync(path: string, encoding: "utf8" | "base64"): string;
}
