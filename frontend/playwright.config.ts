import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests (#52) against the real stack with the demo data: `docker compose up` with the demo
 * profile, all 20 demo games analysed (e2e/wait-for-demo.sh). They run in the pinned Playwright image, the
 * same Chromium and Node 26 locally and in CI (README, "End-to-end tests").
 */
export default defineConfig({
  testDir: "e2e",
  // The findings scenarios only read the shared demo data, so they run in parallel. The journey scenarios
  // (journey.spec.ts) change it (a lesson is started and answered): they run as one serial sequence in one
  // worker, no other spec reads the journey, and they need fresh demo data (`docker compose down -v`).
  fullyParallel: true,
  ...(process.env["CI"] ? { workers: 2 } : {}),
  // No retries: a flaky test should fail and be fixed, not be hidden.
  retries: 0,
  forbidOnly: Boolean(process.env["CI"]),
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  outputDir: "test-results",
  use: {
    baseURL: process.env["E2E_BASE_URL"] ?? "http://localhost:8081",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
