/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  server: {
    // During development the backend runs on its own port; in Docker, nginx proxies /api (nginx.conf).
    proxy: { "/api": "http://localhost:8080" },
  },
  test: {
    environment: "jsdom",
    // Unit and component tests only; the Playwright end-to-end tests in e2e/ run with `npm run e2e`.
    include: ["src/**/*.test.{ts,tsx}"],
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: true,
  },
});
