import AxeBuilder from "@axe-core/playwright";
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import { expect, test } from "./fixtures";

/**
 * 10. The learning journey on the real stack (#97, ADR 0007 amendment of 2026-11-21, ADR 0008), with the demo
 * data as the oracle: 5 hanging-piece findings give 5 drills, exactly lesson 1 (3 practice, 2 check), and
 * no position is left for a retry (ADR 0008, amendment of 2026-11-27).
 *
 * These scenarios CHANGE data: a started lesson stays started, and a check puzzle is answered once. So they
 * are one serial sequence in one worker, in the order a player goes: the map before the lesson is started,
 * a lesson that needs positions, the start, the practice puzzles, the check, the result, and the map
 * afterwards. Each step needs the one before (a failure skips the rest), and the first one fails at once
 * on data that is not fresh. No other spec reads the journey, so their order does not matter.
 *
 * The demo's real ending is the only one reachable: the check is failed (not mastered) and a retry needs
 * 2 more positions. A mastered lesson and a retry are covered by the backend (LessonServiceIT, Cucumber)
 * and the frontend unit tests (LessonScreen.test.tsx).
 *
 * Moves come from data, never guessed: for each puzzle, the correct move is the engine's best move and
 * the wrong one the player's own mistake, both from the findings API for the finding whose position is the
 * puzzle's FEN.
 *
 * The answers are given with the keyboard only, through the text field: Tab to the field, type the move,
 * Enter, and Tab and Enter for "Submit move", "Choose another move" and "Next". The mouse is used for one
 * real drag on the board (which the unit tests cannot drive), the theme switch of the axe checks, and the
 * finding links.
 */
test.describe.configure({ mode: "serial" });

const LESSON_1 = "/journey/lessons/hanging-piece/1";
const START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

interface Puzzle {
  position: number;
  role: "PRACTICE" | "CHECK";
  fen: string;
  findingId: number;
  /** The engine's best move in the finding: the correct answer. */
  best: string;
  /** The player's move in the game: a wrong answer (the sanity rule keeps only drills that reject it). */
  mistake: string;
}

/**
 * Lesson 1's puzzles in order, each with its moves from the finding whose position is the puzzle's FEN.
 * Asserts the data the moves come from: one finding per puzzle, and five different findings.
 */
async function puzzles(request: APIRequestContext): Promise<Puzzle[]> {
  const lesson = (await (await request.get("/api/lessons/hanging-piece/1")).json()) as {
    items: { position: number; role: "PRACTICE" | "CHECK"; fen: string }[];
  };
  const list = (await (await request.get("/api/findings?size=50")).json()) as { items: { id: number }[] };
  const details = await Promise.all(
    list.items.map(
      async (f) =>
        (await (await request.get(`/api/findings/${String(f.id)}`)).json()) as {
          id: number;
          fenBefore: string;
          best: { uci: string };
          played: { uci: string };
        },
    ),
  );
  const result = lesson.items.map((item) => {
    const matching = details.filter((d) => d.fenBefore === item.fen);
    expect(matching, `one finding for position ${String(item.position)}`).toHaveLength(1);
    const finding = matching[0] as (typeof details)[number];
    expect(finding.best.uci).not.toBe(finding.played.uci);
    return {
      position: item.position,
      role: item.role,
      fen: item.fen,
      findingId: finding.id,
      best: finding.best.uci,
      mistake: finding.played.uci,
    };
  });
  expect(new Set(result.map((p) => p.findingId)).size).toBe(result.length);
  return result;
}

async function lessonSummary(request: APIRequestContext, n: number) {
  const journey = (await (await request.get("/api/journey")).json()) as {
    lessons: {
      number: number;
      state: string;
      positionsNeeded: number;
      size: number;
      solvedFirstTry: number;
    }[];
  };
  const lesson = journey.lessons.find((l) => l.number === n);
  if (!lesson) throw new Error(`no lesson ${String(n)}`);
  return lesson;
}

/** WCAG 2.x A and AA, no serious or critical violations, in both themes (as a11y.spec.ts). */
async function expectAccessible(page: Page, what: string): Promise<void> {
  for (const theme of ["dark", "light"] as const) {
    if (theme === "light") {
      await page.getByRole("button", { name: "Switch to light theme" }).click();
      await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
    }
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
      .analyze();
    const serious = results.violations
      .filter((v) => v.impact === "serious" || v.impact === "critical")
      .map(
        (v) =>
          `${v.id} (${String(v.impact)}): ${v.help} on ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`,
      );
    expect(serious, `axe on ${what} (${theme})`).toEqual([]);
  }
  await page.getByRole("button", { name: "Switch to dark theme" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
}

/** The narrow, stacked layout (as layout.spec.ts): the panel below the board, nothing wider than the screen. */
async function expectStacked(page: Page, boardTestId: string, panel: string): Promise<void> {
  const size = page.viewportSize();
  await page.setViewportSize({ width: 400, height: 900 });
  const boardBox = await page.getByTestId(boardTestId).boundingBox();
  const panelBox = await page.getByRole("complementary", { name: panel }).boundingBox();
  expect(boardBox).not.toBeNull();
  expect(panelBox).not.toBeNull();
  if (boardBox && panelBox) {
    expect(panelBox.y).toBeGreaterThanOrEqual(boardBox.y + boardBox.height);
    expect(boardBox.width).toBeLessThanOrEqual(400);
    expect(boardBox.width).toBeGreaterThan(300);
  }
  const overflow = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(overflow.scroll, "page wider than the viewport").toBeLessThanOrEqual(overflow.client);
  if (size) await page.setViewportSize(size);
}

/** Moves the keyboard focus to `target` with Tab only, as a keyboard user would. */
async function tabTo(page: Page, target: Locator, limit = 150): Promise<void> {
  for (let i = 0; i < limit; i++) {
    if (await target.evaluate((element) => element === document.activeElement)) return;
    await page.keyboard.press("Tab");
  }
  throw new Error(`Tab never reached ${target.toString()}`);
}

/** Types a move into the text field with the keyboard and plays it with Enter. */
async function typeMove(page: Page, uci: string): Promise<void> {
  const field = page.getByLabel("Your move");
  await expect(field).toBeEnabled();
  await tabTo(page, field);
  await page.keyboard.type(uci);
  await page.keyboard.press("Enter");
}

async function pressNext(page: Page): Promise<void> {
  await tabTo(page, page.getByRole("button", { name: "Next" }));
  await page.keyboard.press("Enter");
}

function position(page: Page, index: number): Locator {
  return page.getByRole("heading", { name: `Position ${String(index)} of 5` });
}

/** The node of a lesson on the map; its accessible name starts with the title (the number is aria-hidden). */
function node(page: Page, n: number): Locator {
  return page
    .getByRole("region", { name: "Hanging pieces: lessons" })
    .getByRole("button", { name: new RegExp(`^Hanging pieces ${String(n)}(?!\\d)`) });
}

test.describe("10. the learning journey", () => {
  test("the demo data is fresh: lesson 1 has its 5 positions and is not started", async ({ request }) => {
    const lesson = await lessonSummary(request, 1);
    expect(
      lesson.state,
      "the journey spec needs fresh demo data: run it on a new stack (docker compose down -v)",
    ).toBe("AVAILABLE");
    expect(lesson.size).toBe(5);
    expect(lesson.positionsNeeded).toBe(0);
  });

  test("the map: lesson 1 can be started and is recommended, the others need positions; viewing starts nothing", async ({
    page,
    request,
  }) => {
    await page.goto("/journey");

    await expect(node(page, 1)).toHaveAttribute("aria-current", "step");
    await expect(node(page, 1)).toContainText("5 positions ready, not started");
    await expect(node(page, 1)).toContainText("Recommended next");
    for (const n of [2, 3, 4, 5]) {
      await expect(node(page, n)).toContainText("5 more positions needed");
      await expect(node(page, n)).not.toHaveAttribute("aria-current", "step");
    }
    await expect(page.getByRole("region", { name: "Your blind spot" })).toContainText(
      "in 5 of your last 20 rapid games",
    );
    await expect(page.getByTestId("board")).toHaveAttribute("data-fen", START_FEN);
    await expect(page.getByText("Start the lesson to see its first position.")).toBeVisible();

    await expectAccessible(page, "the map");
    await expectStacked(page, "board", "Journey");

    // Looking at the map changed nothing.
    expect((await lessonSummary(request, 1)).state).toBe("AVAILABLE");
    const lesson = (await (await request.get("/api/lessons/hanging-piece/1")).json()) as { items: unknown[] };
    expect(lesson.items).toEqual([]);
  });

  test("a lesson that needs positions is an ordinary page without a start button", async ({
    page,
    request,
  }) => {
    await page.goto("/journey");
    await node(page, 2).click();

    await expect(page).toHaveURL(/\/journey\/lessons\/hanging-piece\/2$/);
    await expect(page.getByText(/5 more positions needed before this lesson can start\./)).toBeVisible();
    await expect(page.getByRole("button", { name: "Start lesson" })).toHaveCount(0);
    await expect(page.getByRole("alert")).toHaveCount(0);
    expect((await lessonSummary(request, 2)).state).toBe("NEEDS_POSITIONS");
  });

  test("starting lesson 1 with the button, and a practice puzzle solved with the keyboard", async ({
    page,
    request,
  }) => {
    await page.goto(LESSON_1);
    const start = page.getByRole("button", { name: "Start lesson" });
    await expect(start).toBeVisible();
    expect((await lessonSummary(request, 1)).state).toBe("AVAILABLE"); // opening it started nothing
    await tabTo(page, start);
    await page.keyboard.press("Enter");

    await expect(position(page, 1)).toBeVisible();
    expect((await lessonSummary(request, 1)).state).toBe("IN_PROGRESS");
    const [first] = await puzzles(request);
    if (!first) throw new Error("no puzzle");
    expect(first.role).toBe("PRACTICE");
    await expect(page.getByTestId("puzzle-board")).toHaveAttribute("data-fen", first.fen);
    await expectAccessible(page, "a puzzle");
    await expectStacked(page, "puzzle-board", "Lesson");

    await typeMove(page, first.best);

    const correct = page.getByRole("heading", { name: "Correct" });
    await expect(correct).toBeFocused();
    await expect(page.getByTestId("puzzle-status")).toContainText("Correct.");
    await expect(page.getByText(/^In your game you played /)).toBeVisible();
    // The review: the game's move in orange, then the evidence line in blue, stepped with the arrow keys.
    const arrow = page.getByTestId("puzzle-board").locator("[data-arrow]");
    await expect(arrow).toHaveAttribute("data-kind", "mistake");
    await expect(arrow).toHaveAttribute("data-arrow", first.mistake.slice(0, 4));
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(arrow).toHaveAttribute("data-kind", "evidence");
    await page.keyboard.press("Home");
    await expect(arrow).toHaveAttribute("data-kind", "mistake");
    await expectAccessible(page, "an answered puzzle");

    await pressNext(page);
    await expect(position(page, 2)).toBeVisible();
  });

  test("a practice puzzle answered wrongly, then the last practice puzzle", async ({ page, request }) => {
    const all = await puzzles(request);
    await page.goto(LESSON_1);

    const second = all[1];
    const third = all[2];
    if (!second || !third) throw new Error("no puzzle");
    await expect(position(page, 2)).toBeVisible();
    await typeMove(page, second.mistake);
    const wrong = page.getByRole("heading", { name: "Not this one" });
    await expect(wrong).toBeFocused();
    await expect(page.getByText(/^It is refuted by: /)).toBeVisible();
    await expect(page.getByTestId("puzzle-status")).toContainText("Not this one.");
    await pressNext(page);

    await expect(position(page, 3)).toBeVisible();
    await typeMove(page, third.best);
    await expect(page.getByRole("heading", { name: "Correct" })).toBeFocused();
    await pressNext(page);
    await expect(position(page, 4)).toBeVisible();
  });

  test("a real mouse drag on the board makes a move; a check puzzle waits for 'Submit move'", async ({
    page,
    request,
  }) => {
    const check = (await puzzles(request))[3];
    if (!check) throw new Error("no puzzle");
    expect(check.role).toBe("CHECK");
    await page.goto(LESSON_1);
    await expect(position(page, 4)).toBeVisible();
    await expect(page.getByText("Mastery check: one try")).toBeVisible();

    // Drag the engine's move with the mouse: press on the piece, move in steps, release on the target. The
    // whole board must be on screen (the mouse cannot press below the fold), so the window is made taller.
    await page.setViewportSize({ width: 1280, height: 1200 });
    const square = (name: string) => page.getByTestId("puzzle-board").locator(`[data-square="${name}"]`);
    await expect(square(check.best.slice(0, 2))).toBeInViewport();
    await expect(square(check.best.slice(2, 4))).toBeInViewport();
    const from = await square(check.best.slice(0, 2)).boundingBox();
    const to = await square(check.best.slice(2, 4)).boundingBox();
    if (!from || !to) throw new Error("square not on screen");
    await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
    await page.mouse.down();
    await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 12 });
    await page.mouse.up();

    // The drag chose the move; nothing is sent before "Submit move".
    await expect(page.getByRole("heading", { name: /^You chose / })).toBeVisible();
    await expect(page.getByRole("button", { name: "Submit move" })).toBeFocused();
    await expect(page.getByTestId("puzzle-status")).toContainText("Submit it, or choose another move.");

    await page.keyboard.press("Tab");
    const another = page.getByRole("button", { name: "Choose another move" });
    await expect(another).toBeFocused();
    // dnd-kit swallows clicks on the page until 50 ms after a drop (so the drop is not also a click), and
    // Enter on a button is a click. A person cannot press that fast; the test can, so it presses again while
    // the button is still there, waiting on the field instead of on time.
    await expect(async () => {
      if (await another.isVisible()) await page.keyboard.press("Enter");
      await expect(page.getByLabel("Your move")).toBeEnabled({ timeout: 200 });
    }).toPass();
    const lesson = (await (await request.get("/api/lessons/hanging-piece/1")).json()) as {
      items: { position: number; answered: boolean }[];
    };
    expect(lesson.items.find((i) => i.position === check.position)?.answered).toBe(false);
  });

  test.describe(() => {
    // The browser logs the refused request; that is the one expected console error, in this test only.
    test.use({ allowedConsoleErrors: [/the server responded with a status of 409/] });

    test("a second answer to a check puzzle is refused (409), and the screen says why and moves on", async ({
      page,
      context,
      request,
    }) => {
      const check = (await puzzles(request))[3];
      if (!check) throw new Error("no puzzle");
      await page.goto(LESSON_1);
      await expect(position(page, 4)).toBeVisible();

      // Another tab answers the check puzzle first, wrongly: the game's own mistake.
      const other = await context.newPage();
      await other.goto(LESSON_1);
      await expect(position(other, 4)).toBeVisible();
      await other.getByLabel("Your move").fill(check.mistake);
      await other.getByLabel("Your move").press("Enter");
      await other.getByRole("button", { name: "Submit move" }).click();
      await expect(other.getByRole("heading", { name: "Not this one" })).toBeVisible();
      await other.close();

      // This tab still shows it open, and answers it again with the keyboard.
      await typeMove(page, check.best);
      await expect(page.getByRole("button", { name: "Submit move" })).toBeFocused();
      await page.keyboard.press("Enter");

      const notice = page.getByRole("region", { name: "Notice" });
      await expect(notice).toContainText("it had been answered already, or the lesson changed");
      await expect(notice).toBeFocused();
      await expect(position(page, 5)).toBeVisible();
    });
  });

  test("the last check puzzle, and the result: not mastered, a retry needs 2 more positions", async ({
    page,
    request,
  }) => {
    const all = await puzzles(request);
    const last = all[4];
    if (!last) throw new Error("no puzzle");
    await page.goto(LESSON_1);
    await expect(position(page, 5)).toBeVisible();

    await typeMove(page, last.best);
    await page.keyboard.press("Enter"); // "Submit move" has the focus
    await expect(page.getByRole("heading", { name: "Correct" })).toBeFocused();
    await pressNext(page);

    const heading = page.getByRole("heading", { name: "Result: Hanging pieces 1" });
    await expect(heading).toBeFocused();
    const result = page.getByRole("region", { name: "Result: Hanging pieces 1" });
    await expect(result).toContainText("Not mastered yet");
    await expect(result).toContainText("A retry of the mastery check needs 2 more positions.");
    await expect(result.getByRole("button", { name: "Retry the mastery check" })).toHaveCount(0);
    await expect(result).toContainText("practice on your own mistakes, not a test");
    // 1, 3 and 5 solved first try; 2 wrong; 4 answered wrongly by the other tab.
    await expect(result.locator(".result__label")).toHaveText([
      "Position 1: solved first try",
      "Position 2: not solved first try",
      "Position 3: solved first try",
      "Position 4 (mastery check): not solved first try",
      "Position 5 (mastery check): solved first try",
    ]);
    await expect(result.getByRole("link", { name: "Game on Lichess" })).toHaveCount(5);
    await expectAccessible(page, "the result");

    // Each finding link opens that finding in the app, and Back returns to the result.
    for (const [index, puzzle] of all.entries()) {
      await result.getByRole("link", { name: `The finding of position ${String(index + 1)}` }).click();
      await expect(page).toHaveURL(new RegExp(`/findings/${String(puzzle.findingId)}$`));
      await expect(page.getByRole("heading", { name: /^Hanging piece · / })).toBeVisible();
      await page.goBack();
      await expect(heading).toBeVisible();
    }

    await page.reload();
    await expect(heading).toBeVisible();
    await expect(result).toContainText("Not mastered yet");
  });

  test("the map afterwards: lesson 1 in progress with its evidence, previewing a practice position", async ({
    page,
    request,
  }) => {
    const [first] = await puzzles(request);
    if (!first) throw new Error("no puzzle");
    await page.goto("/journey");

    await expect(node(page, 1)).toContainText("3 of 5 positions solved first try");
    await expect(node(page, 1)).toContainText("A retry of the mastery check needs 2 more positions.");
    await expect(node(page, 1)).toHaveAttribute("aria-current", "step");
    await expect(page.getByTestId("board")).toHaveAttribute("data-fen", first.fen);
    await expect(
      page.getByText("Every position of this lesson is answered; its practice positions can be tried again."),
    ).toBeVisible();
    const lesson = await lessonSummary(request, 1);
    expect(lesson).toMatchObject({ state: "IN_PROGRESS", solvedFirstTry: 3, positionsNeeded: 2 });
  });
});
