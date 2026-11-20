import { expect, test } from "./fixtures";

const SERVER_TEXT = "org.postgresql.util.PSQLException: connection to 10.0.0.5 refused";

test.describe("8. error state", () => {
  // The browser itself logs the failed requests; that is the expected console error here, nothing else.
  test.use({ allowedConsoleErrors: [/Failed to load resource: the server responded with a status of 500/] });

  test("with /api failing, the screen says what to do and shows no server text", async ({ page }) => {
    await page.route("**/api/**", (route) =>
      route.fulfill({
        status: 500,
        contentType: "application/problem+json",
        body: JSON.stringify({ title: "Internal error", status: 500, detail: SERVER_TEXT }),
      }),
    );
    await page.goto("/");

    const alerts = page.getByRole("alert");
    await expect(alerts.first()).toBeVisible();
    await expect(
      page.getByRole("alert").filter({ hasText: "The list of findings could not be loaded." }),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Try again" }).first()).toBeVisible();
    await expect(page.locator("body")).not.toContainText(SERVER_TEXT);
    await expect(page.locator("body")).not.toContainText("PSQLException");
    await expect(page.locator("body")).not.toContainText("500");
  });
});
