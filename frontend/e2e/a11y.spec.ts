import AxeBuilder from "@axe-core/playwright";
import { expect, openFindings, test } from "./fixtures";

/**
 * 9. Accessibility (#52): axe on the findings screen in both themes, WCAG 2.x A and AA rules. No serious or
 * critical violations; accepted exceptions, if any, are listed in the devlog with the reason.
 */
for (const theme of ["dark", "light"] as const) {
  test(`axe finds no serious or critical violations on the findings screen (${theme})`, async ({ page }) => {
    await openFindings(page);
    if (theme === "light") {
      await page.getByRole("button", { name: "Switch to light theme" }).click();
      await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
    }
    // Some evidence on the board, so arrows and highlighted squares are part of the check.
    await page.getByRole("list", { name: "Your move and what follows" }).getByRole("button").nth(1).click();

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
      .analyze();
    const serious = results.violations
      .filter((v) => v.impact === "serious" || v.impact === "critical")
      .map(
        (v) =>
          `${v.id} (${String(v.impact)}): ${v.help} on ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`,
      );
    expect(serious).toEqual([]);
  });
}
