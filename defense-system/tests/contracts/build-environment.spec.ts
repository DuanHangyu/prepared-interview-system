import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import dockerfile from "../../Dockerfile?raw";
import nginxConfig from "../../nginx.conf?raw";
import packageJsonSource from "../../package.json?raw";
import environmentTypes from "../../types/env.d.ts?raw";
import viteConfig from "../../vite.config.mts?raw";

const projectReadme = readFileSync("../README.md", "utf8");
const globalStyles = readFileSync("src/assets/styles/global.css", "utf8");
const postcssConfig = readFileSync("postcss.config.js", "utf8");

const expectedPublicConfig = {
  VUE_APP_BASE_API: "/api",
  VUE_APP_BASE_TEXT_WS: "/ws/defense-assessment",
  VUE_APP_BASE_VOICE_WS: "/ws/voice/defense-assessment",
  VUE_APP_BASE_PLAYVOICE_WS: "/ws/voice-play/defense-assessment",
};

describe("frontend build environment contract", () => {
  it.each([
    [".env.production", __PRODUCTION_ENVIRONMENT__],
    [".env.formal", __FORMAL_ENVIRONMENT__],
  ])(
    "%s uses the same-origin API and websocket paths",
    (_fileName, values) => {
      expect(values).toMatchObject(expectedPublicConfig);
      expect(values).not.toHaveProperty("VUE_APP_API_BASE_URL");
      expect(values).not.toHaveProperty("VUE_APP_WEBSOCKET_BASE_URL");
      expect(Object.values(values).join("\n")).not.toMatch(
        /(?:https?:\/\/|wss?:\/\/|localhost)/i
      );
    }
  );

  it("keeps Docker build arguments aligned with the checked-in production env", () => {
    expect(dockerfile).toContain("FROM node:22.23.3-alpine3.23 AS build-stage");
    for (const [key, value] of Object.entries(expectedPublicConfig)) {
      expect(dockerfile).toContain(`ARG ${key}=${value}`);
      expect(dockerfile).toContain(`ENV ${key}=\${${key}}`);
    }
  });

  it("declares every public runtime key in the TypeScript environment contract", () => {
    for (const key of Object.keys(expectedPublicConfig)) {
      expect(environmentTypes).toContain(`readonly ${key}: string`);
    }
  });

  it("exposes reproducible unit, coverage, typecheck and e2e commands", () => {
    const packageJson = JSON.parse(packageJsonSource);

    expect(packageJson.scripts).toMatchObject({
      test: "vitest run",
      "test:watch": "vitest",
      "test:coverage": "vitest run --coverage",
      typecheck: "vue-tsc --noEmit",
      "test:e2e": "playwright test",
    });
    expect(packageJson.devDependencies).toEqual(
      expect.objectContaining({
        "@playwright/test": expect.any(String),
        "@vitejs/plugin-vue": expect.any(String),
        "@vitest/coverage-v8": expect.any(String),
        "@vue/test-utils": expect.any(String),
        jsdom: expect.any(String),
        "vue-tsc": expect.any(String),
        vitest: expect.any(String),
      })
    );
    expect(packageJson.scripts).toMatchObject({
      serve: "vite --host 0.0.0.0",
      build: "vite build && npm run check:bundle",
      "check:bundle": "node scripts/check-bundle-size.mjs",
    });
    expect(packageJson.devDependencies).not.toEqual(
      expect.objectContaining({
        "@vue/cli-service": expect.any(String),
        "compression-webpack-plugin": expect.any(String),
        "terser-webpack-plugin": expect.any(String),
      })
    );
    expect(viteConfig).toContain('envPrefix: "VUE_APP_"');
    expect(viteConfig).toContain("process.env.VUE_APP_PROXY_TARGET");
    expect(viteConfig).toContain("sourcemap: false");
    expect(viteConfig).toContain("chunkSizeWarningLimit: 512");
  });

  it("keeps the documented frontend runtime aligned with package metadata", () => {
    const packageJson = JSON.parse(packageJsonSource);

    expect(projectReadme).toContain(`Node.js ${packageJson.engines.node}`);
    expect(projectReadme).not.toContain("Node.js 18+");
    expect(projectReadme).not.toContain("- PDF.js");
    expect(projectReadme).not.toContain("- XGPlayer");
  });

  it("uses the patched Tailwind 4 build chain without unused file watchers", () => {
    const packageJson = JSON.parse(packageJsonSource);

    expect(packageJson.devDependencies.tailwindcss).toMatch(/^\^4\./);
    expect(packageJson.devDependencies["@tailwindcss/vite"]).toMatch(/^\^4\./);
    expect(packageJson.devDependencies).not.toHaveProperty("unplugin-auto-import");
    expect(packageJson.devDependencies).not.toHaveProperty(
      "unplugin-vue-components"
    );
    expect(packageJson.devDependencies).not.toHaveProperty("autoprefixer");
    expect(viteConfig).toContain('import tailwindcss from "@tailwindcss/vite"');
    expect(viteConfig).toContain("plugins: [tailwindcss(), vue()]");
    expect(globalStyles).toContain('@import "tailwindcss"');
    expect(globalStyles).not.toContain("@tailwind ");
    expect(postcssConfig).not.toContain("tailwindcss:");
    expect(postcssConfig).not.toContain("autoprefixer:");
  });

  it("serves the application with browser security boundaries", () => {
    expect(nginxConfig).toContain('add_header Content-Security-Policy "');
    expect(nginxConfig).toContain("default-src 'self'");
    expect(nginxConfig).toContain("script-src 'self'");
    expect(nginxConfig).toContain("object-src 'none'");
    expect(nginxConfig).toContain("frame-ancestors 'none'");
    expect(nginxConfig).toContain("frame-src 'self' blob:");
    expect(nginxConfig).toContain("connect-src 'self' ws: wss:");
    expect(nginxConfig).toContain("add_header Permissions-Policy");
    expect(nginxConfig).toContain("camera=(self)");
    expect(nginxConfig).toContain("microphone=(self)");
  });
});
