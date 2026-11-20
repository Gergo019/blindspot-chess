import { board, expect, findingRow, openFindings, pieceOn, test } from "./fixtures";

/**
 * The findings screen against the real stack and the 20 demo games (#52). The demo data is fixed
 * (tools/demo-data, hanging-0.4): 5 hanging-piece findings, newest first 18… Rb6, 11… Be7, 8… Bh3,
 * 5. Qe2, 33… Rc3+. If the detector changes those, regenerate the demo expectations with it.
 */

test("1. the blind-spot card reports the pattern with its sample size, and no undetected motif", async ({
  page,
}) => {
  await openFindings(page);
  const card = page.getByRole("region", { name: "Your blind spot" });
  await expect(card.getByRole("listitem")).toHaveCount(1);
  const hanging = card.getByRole("listitem").filter({ hasText: "Hanging piece" });
  await expect(hanging).toContainText("in 5 of your last 20 rapid games");
  await expect(hanging).toContainText("A pattern: 3 or more games.");
  await expect(card).not.toContainText("knight fork");
});

test("2. the list shows the 5 findings newest first; selecting changes the URL, reload and Back keep it", async ({
  page,
}) => {
  await openFindings(page);
  const list = page.getByRole("region", { name: /^Findings/ });
  await expect(list.getByRole("heading")).toHaveText("Findings (5)");
  const rows = list.getByRole("listitem");
  await expect(rows).toHaveCount(5);
  const order = ["18… Rb6", "11… Be7", "8… Bh3", "5. Qe2", "33… Rc3+"];
  for (const [index, move] of order.entries()) await expect(rows.nth(index)).toContainText(move);

  // The newest finding is selected on arrival and is in the URL.
  await expect(page).toHaveURL(/\/findings\/\d+$/);
  const first = page.url();
  await expect(findingRow(page, "18… Rb6")).toHaveAttribute("aria-current", "true");

  await findingRow(page, "11… Be7").click();
  await expect(page.getByRole("heading", { name: "Hanging piece · 11… Be7" })).toBeVisible();
  await expect(page).not.toHaveURL(first);
  const second = page.url();

  await page.reload();
  await expect(page.getByRole("heading", { name: "Hanging piece · 11… Be7" })).toBeVisible();
  await expect(page).toHaveURL(second);
  await expect(findingRow(page, "11… Be7")).toHaveAttribute("aria-current", "true");

  await page.goBack();
  await expect(page).toHaveURL(first);
  await expect(page.getByRole("heading", { name: "Hanging piece · 18… Rb6" })).toBeVisible();
});

test("3. a deep link to a finding opens it directly", async ({ page }) => {
  const response = await page.request.get("/api/findings?size=50");
  const items = ((await response.json()) as { items: { id: number; played: { san: string } }[] }).items;
  const bh3 = items.find((item) => item.played.san === "Bh3");
  expect(bh3, "the demo finding 8… Bh3").toBeDefined();

  await page.goto(`/findings/${String(bh3?.id)}`);
  await expect(page.getByRole("heading", { name: "Hanging piece · 8… Bh3" })).toBeVisible();
  await expect(page).toHaveURL(`/findings/${String(bh3?.id)}`);
  await expect(findingRow(page, "8… Bh3")).toHaveAttribute("aria-current", "true");
});

test("4. chips and the arrow keys, Home and End step through the evidence line on the board", async ({
  page,
}) => {
  await openFindings(page);
  await expect(page.getByRole("heading", { name: "Hanging piece · 18… Rb6" })).toBeVisible();
  // The board caption is a live status; react-chessboard's drag library adds an empty one of its own.
  const caption = page.getByRole("status").filter({
    hasText: /^(Position before|After your move|Evidence move|The engine's best move|Position after)/,
  });

  // Before the mistake: the rook is still on b8, b6 is empty.
  await expect(caption).toHaveText("Position before your move 18… Rb6");
  await expect.poll(() => pieceOn(page, "b8")).toBe("bR");
  await expect.poll(() => pieceOn(page, "b6")).toBeNull();

  // A chip: after 19. Nxb6 the white knight stands on b6 and has left c4.
  const chips = page.getByRole("list", { name: "Your move and what follows" });
  await chips.getByRole("button", { name: "19. Nxb6" }).click();
  await expect(caption).toHaveText("Evidence move 1 of 6: 19. Nxb6");
  await expect(chips.getByRole("button", { name: "19. Nxb6" })).toHaveAttribute("aria-current", "step");
  await expect.poll(() => pieceOn(page, "b6")).toBe("wN");
  await expect.poll(() => pieceOn(page, "c4")).toBeNull();

  await page.keyboard.press("Home");
  await expect(caption).toHaveText("Position before your move 18… Rb6");
  await expect.poll(() => pieceOn(page, "b8")).toBe("bR");

  await page.keyboard.press("ArrowRight");
  await expect(caption).toHaveText("After your move 18… Rb6");
  await expect.poll(() => pieceOn(page, "b6")).toBe("bR");

  await page.keyboard.press("End");
  await expect(caption).toHaveText("Evidence move 6 of 6: 21… Qf6");
  await expect.poll(() => pieceOn(page, "f6")).toBe("bQ");

  await page.keyboard.press("ArrowLeft");
  await expect(caption).toHaveText("Evidence move 5 of 6: 21. Bf1");
  await expect.poll(() => pieceOn(page, "f1")).toBe("wB");
  // 20… Nd5 moved the knight off f6 earlier in the line, so f6 is empty until 21… Qf6.
  await expect.poll(() => pieceOn(page, "f6")).toBeNull();
  await expect.poll(() => pieceOn(page, "d5")).toBe("bN");
});

test("5. the best move is drawn on request, and a move-list click shows that position", async ({ page }) => {
  await openFindings(page);
  await expect(page.getByRole("heading", { name: "Hanging piece · 18… Rb6" })).toBeVisible();

  await page.getByRole("button", { name: "Show the best move" }).click();
  await expect(page.getByText("The engine's best move instead: 18… Nd4")).toBeVisible();
  await expect(board(page).locator('[data-arrow="c6d4"]')).toHaveAttribute("data-kind", "evidence");
  await expect.poll(() => pieceOn(page, "b8")).toBe("bR");

  const moves = page.getByRole("region", { name: "Moves" });
  await expect(moves.getByRole("button", { name: /^18… Rb6, Blunder, the finding$/ })).toBeVisible();
  await moves.getByRole("button", { name: /^19\. Nxb6,/ }).click();
  await expect(page.getByText("Position after 19. Nxb6")).toBeVisible();
  await expect(moves.getByRole("button", { name: /^19\. Nxb6,/ })).toHaveAttribute("aria-current", "step");
  await expect.poll(() => pieceOn(page, "b6")).toBe("wN");
});
