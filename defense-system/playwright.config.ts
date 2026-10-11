import { defineConfig, devices } from "@playwright/test";
import path from "node:path";
import { prepareBrowserArtifacts } from "../scripts/prepared-browser-artifacts.mjs";

const localBaseUrl = "http://127.0.0.1:4173";
const externalBaseUrl = process.env.E2E_BASE_URL;
const browserArtifacts = prepareBrowserArtifacts(path.resolve(__dirname, ".."), process.env);

export default defineConfig({
  testDir: "./tests/e2e",
  testIgnore:
    process.env.LIVE_E2E === "1" ? [] : ["**/live-*.spec.ts"],
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [
        ["line"],
        [
          "html",
          {
            outputFolder: browserArtifacts.reportDir,
            open: "never",
          },
        ],
      ]
    : "list",
  outputDir: browserArtifacts.outputDir,
  use: {
    baseURL: externalBaseUrl || localBaseUrl,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: externalBaseUrl
    ? undefined
    : {
        command: "npm run serve -- --host 127.0.0.1 --port 4173",
        url: localBaseUrl,
        reuseExistingServer: !process.env.CI,
        timeout: 240_000,
        stdout: "pipe",
        stderr: "pipe",
        env: {
          VUE_APP_BASE_API: "/api",
          VUE_APP_BASE_TEXT_WS: "/ws/defense-assessment",
          VUE_APP_BASE_VOICE_WS: "/ws/voice/defense-assessment",
          VUE_APP_BASE_PLAYVOICE_WS: "/ws/voice-play/defense-assessment",
        },
      },
});
