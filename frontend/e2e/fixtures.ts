import { expect, test as base, type Locator, type Page } from "@playwright/test";

/**
 * Every scenario (#52) also asserts that the page produced no CSP violations, no console errors and no
 * uncaught exceptions. The guard is an automatic fixture: it starts listening before the page loads and
 * checks after the test. A test that provokes an expected console error (the error state) lists it in
 * `allowedConsoleErrors`.
 */
interface Guard {
  allowedConsoleErrors: RegExp[];
  guard: undefined;
}

declare global {
  interface Window {
    __cspViolations?: string[];
  }
}

export const test = base.extend<Guard>({
  allowedConsoleErrors: [[], { option: true }],
  guard: [
    async ({ page, allowedConsoleErrors }, use) => {
      const problems: string[] = [];
      await page.addInitScript(() => {
        window.__cspViolations = [];
        document.addEventListener("securitypolicyviolation", (event) => {
          window.__cspViolations?.push(`${event.violatedDirective} ${event.blockedURI}`);
        });
      });
      page.on("console", (message) => {
        if (message.type() !== "error") return;
        const text = message.text();
        if (!allowedConsoleErrors.some((pattern) => pattern.test(text))) problems.push(`console: ${text}`);
      });
      page.on("pageerror", (error) => problems.push(`page error: ${error.message}`));

      await use(undefined);

      if (!page.isClosed()) {
        const csp = await page.evaluate(() => window.__cspViolations ?? []).catch(() => []);
        problems.push(...csp.map((v) => `CSP: ${v}`));
      }
      expect(problems, "CSP violations, console errors or uncaught exceptions").toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };

/** The board, and the piece code on one of its squares (for example "bR"), or null for an empty square. */
export function board(page: Page): Locator {
  return page.getByTestId("board");
}

export async function pieceOn(page: Page, square: string): Promise<string | null> {
  const piece = board(page).locator(`[data-square="${square}"] [data-piece]`);
  return (await piece.count()) === 0 ? null : piece.first().getAttribute("data-piece");
}

/** Opens the findings screen and waits until a finding is shown. */
export async function openFindings(page: Page, path = "/"): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole("heading", { name: /^Hanging piece · / })).toBeVisible();
}

/** The finding row in the list for the move it is about, for example "18… Rb6". */
export function findingRow(page: Page, move: string): Locator {
  return page
    .getByRole("region", { name: /^Findings/ })
    .getByRole("button", { name: new RegExp(move.replace(/[.…+]/g, (c) => `\\${c}`)) });
}
