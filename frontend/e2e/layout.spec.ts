import { board, expect, openFindings, test } from "./fixtures";

test.describe("6. narrow screen", () => {
  test.use({ viewport: { width: 400, height: 900 } });

  test("the panel stacks below the board and nothing overflows horizontally", async ({ page }) => {
    await openFindings(page);
    const boardBox = await board(page).boundingBox();
    const panelBox = await page.getByRole("complementary", { name: "Findings" }).boundingBox();
    expect(boardBox).not.toBeNull();
    expect(panelBox).not.toBeNull();
    if (!boardBox || !panelBox) return;
    expect(panelBox.y).toBeGreaterThanOrEqual(boardBox.y + boardBox.height);
    expect(boardBox.width).toBeLessThanOrEqual(400);
    expect(boardBox.width).toBeGreaterThan(300);

    const overflow = await page.evaluate(() => ({
      scroll: document.documentElement.scrollWidth,
      client: document.documentElement.clientWidth,
    }));
    expect(overflow.scroll, "page wider than the viewport").toBeLessThanOrEqual(overflow.client);
  });
});

test("7. dark by default; the light theme persists after a reload", async ({ page }) => {
  await openFindings(page);
  const html = page.locator("html");
  await expect(html).toHaveAttribute("data-theme", "dark");

  await page.getByRole("button", { name: "Switch to light theme" }).click();
  await expect(html).toHaveAttribute("data-theme", "light");

  await page.reload();
  await expect(html).toHaveAttribute("data-theme", "light");
  await expect(page.getByRole("button", { name: "Switch to dark theme" })).toBeVisible();
});
