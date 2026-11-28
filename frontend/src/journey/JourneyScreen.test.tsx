import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "../App";
import { START_FEN } from "../chess/notation";
import { journey, lesson, lessonItem, lessonSummary, mockApi, page, patterns } from "../test/mockApi";

/** A position after 1. e4 e5 2. Nf3, Black to move: the next, unanswered puzzle in these tests. */
const NEXT_FEN = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2";

async function openMap(routes: Record<string, unknown>) {
  const fetchMock = mockApi({ "/api/patterns": patterns(5), ...routes });
  window.history.replaceState(null, "", "/journey");
  render(<App />);
  await screen.findByRole("heading", { name: "Hanging pieces: lessons" });
  return fetchMock;
}

function nodes() {
  return within(screen.getByRole("region", { name: "Hanging pieces: lessons" })).getAllByRole("button");
}

function board() {
  return screen.getByTestId("board");
}

/** The map only reads: every request is a GET, and none starts a lesson. */
function expectOnlyGets(fetchMock: ReturnType<typeof mockApi>) {
  for (const call of fetchMock.mock.calls) {
    const init = (call as unknown[])[1] as RequestInit | undefined;
    expect(init?.method ?? "GET").toBe("GET");
  }
}

function requested(fetchMock: ReturnType<typeof mockApi>): string[] {
  return fetchMock.mock.calls.map(([input]) =>
    typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url,
  );
}

describe("The journey map", () => {
  it("with the demo data: lesson 1 can be started and is recommended, the others say what they need", async () => {
    const fetchMock = await openMap({
      "/api/journey": journey(lessonSummary(1, { recommended: true })),
    });

    const [first, second] = nodes();
    expect(nodes()).toHaveLength(5);
    expect(first).toHaveAttribute("aria-current", "step");
    expect(first).toHaveTextContent("Hanging pieces 1");
    expect(first).toHaveTextContent("5 positions ready, not started");
    expect(first).toHaveTextContent("Recommended next");
    expect(second).not.toHaveAttribute("aria-current");
    expect(second).toHaveTextContent("5 more positions needed");
    expect(second).toBeEnabled(); // no locks

    // Not started, so no position to preview: the starting position and a note.
    expect(board()).toHaveAttribute("data-fen", START_FEN);
    expect(screen.getByText("Start the lesson to see its first position.")).toBeInTheDocument();
    expect(requested(fetchMock)).not.toContain("/api/lessons/hanging-piece/1");
    expectOnlyGets(fetchMock);
  });

  it("shows the unit's line of the blind-spot report", async () => {
    await openMap({ "/api/journey": journey(lessonSummary(1, { recommended: true })) });

    const card = await screen.findByRole("region", { name: "Your blind spot" });
    expect(card).toHaveTextContent("Hanging piece");
    expect(card).toHaveTextContent("in 5 of your last 20 rapid games");
  });

  it("previews the first unanswered position of the recommended lesson once it is started", async () => {
    const started = lessonSummary(1, { recommended: true, state: "IN_PROGRESS", solvedFirstTry: 2 });
    const fetchMock = await openMap({
      "/api/journey": journey(started),
      "/api/lessons/hanging-piece/1": lesson(started, [
        lessonItem(1, { answered: true, solvedFirstTry: true }),
        lessonItem(2, { answered: true, solvedFirstTry: true }),
        lessonItem(3, { fen: NEXT_FEN, orientation: "BLACK" }),
        lessonItem(4),
        lessonItem(5),
      ]),
    });

    expect(nodes()[0]).toHaveTextContent("2 of 5 positions solved first try");
    expect(await screen.findByRole("img", { name: /the next position of Hanging pieces 1/ })).toBe(board());
    expect(board()).toHaveAttribute("data-fen", NEXT_FEN);
    expect(board()).toHaveAccessibleName(
      "Chess board seen from Black: the next position of Hanging pieces 1",
    );
    expect(board().querySelector('[data-square="h1"]')).toBe(board().querySelector("[data-square]")); // from Black
    expect(board().querySelector("[data-arrow]")).toBeNull(); // no arrows on the map
    expect(requested(fetchMock)).toContain("/api/lessons/hanging-piece/1");
    expectOnlyGets(fetchMock);
  });

  it("previews the first practice position when every position is answered, and says what a retry needs", async () => {
    // The demo case: the check failed, and lesson 1 used all 5 positions.
    const failed = lessonSummary(1, {
      recommended: true,
      state: "IN_PROGRESS",
      solvedFirstTry: 4,
      positionsNeeded: 2,
    });
    await openMap({
      "/api/journey": journey(failed),
      "/api/lessons/hanging-piece/1": lesson(failed, [
        lessonItem(1, { answered: true, solvedFirstTry: true, fen: NEXT_FEN, orientation: "BLACK" }),
        lessonItem(2, { answered: true, solvedFirstTry: true }),
        lessonItem(3, { answered: true, solvedFirstTry: true }),
        lessonItem(4, { answered: true }),
        lessonItem(5, { answered: true, solvedFirstTry: true }),
      ]),
    });

    expect(nodes()[0]).toHaveTextContent("4 of 5 positions solved first try");
    expect(nodes()[0]).toHaveTextContent("A retry of the mastery check needs 2 more positions.");
    expect(
      await screen.findByText(
        "Every position of this lesson is answered; its practice positions can be tried again.",
      ),
    ).toBeInTheDocument();
    expect(board()).toHaveAttribute("data-fen", NEXT_FEN);
  });

  it("says when the mastery check can be retried", async () => {
    const retry = lessonSummary(1, { state: "IN_PROGRESS", solvedFirstTry: 3, retryAvailable: true });
    await openMap({ "/api/journey": journey(retry) });

    expect(nodes()[0]).toHaveTextContent("3 of 5 positions solved first try");
    expect(nodes()[0]).toHaveTextContent("The mastery check can be retried on new positions.");
    expect(nodes()[0]).not.toHaveTextContent("more positions");
  });

  it("shows a mastered lesson, and previews nothing when no lesson is recommended", async () => {
    const fetchMock = await openMap({
      "/api/journey": journey(lessonSummary(1, { state: "MASTERED", solvedFirstTry: 5 })),
    });

    expect(nodes()[0]).toHaveTextContent("Mastered");
    expect(nodes().filter((n) => n.hasAttribute("aria-current"))).toHaveLength(0);
    expect(screen.queryByText("Recommended next")).not.toBeInTheDocument();
    expect(board()).toHaveAttribute("data-fen", START_FEN);
    expect(screen.getByText("No lesson is recommended right now.")).toBeInTheDocument();
    expect(requested(fetchMock).filter((url) => url.startsWith("/api/lessons"))).toHaveLength(0);
  });

  it("explains an empty journey and still lists every lesson, each of which opens", async () => {
    const second = lessonSummary(2, { state: "NEEDS_POSITIONS", size: 0, positionsNeeded: 5 });
    await openMap({
      "/api/journey": journey(lessonSummary(1, { state: "NEEDS_POSITIONS", size: 0, positionsNeeded: 3 })),
      "/api/lessons/hanging-piece/2": lesson(second, []),
    });

    expect(screen.getByText(/No lesson can be filled yet\./)).toBeInTheDocument();
    expect(nodes()[0]).toHaveTextContent("3 more positions needed");
    expect(nodes()).toHaveLength(5);

    await userEvent.click(nodes()[1] as HTMLElement);

    expect(window.location.pathname).toBe("/journey/lessons/hanging-piece/2");
    expect(
      await screen.findByText(/5 more positions needed before this lesson can start\./),
    ).toBeInTheDocument();
  });

  it("opens a lesson from its node", async () => {
    const first = lessonSummary(1, { recommended: true });
    await openMap({ "/api/journey": journey(first), "/api/lessons/hanging-piece/1": lesson(first, []) });

    await userEvent.click(nodes()[0] as HTMLElement);

    expect(window.location.pathname).toBe("/journey/lessons/hanging-piece/1");
    expect(await screen.findByRole("button", { name: "Start lesson" })).toBeInTheDocument();
    const nav = within(screen.getByRole("navigation", { name: "Main" }));
    expect(nav.getByRole("button", { name: "Journey" })).toHaveAttribute("aria-current", "page");

    await userEvent.click(screen.getByRole("link", { name: "Back to the journey" }));
    expect(window.location.pathname).toBe("/journey");
  });

  it("says when the preview's position cannot be loaded, and keeps the map", async () => {
    await openMap({
      "/api/journey": journey(lessonSummary(1, { recommended: true, state: "IN_PROGRESS" })),
      "/api/lessons/hanging-piece/1": { status: 500 },
    });

    expect(await screen.findByText("The lesson's next position could not be loaded.")).toBeInTheDocument();
    expect(board()).toHaveAttribute("data-fen", START_FEN);
    expect(nodes()).toHaveLength(5);
  });
});

describe("The journey map's loading and error states", () => {
  it("shows a loading card first", () => {
    mockApi({ "/api/patterns": patterns(5), "/api/journey": journey(lessonSummary(1)) });
    window.history.replaceState(null, "", "/journey");
    render(<App />);

    expect(screen.getByText("Loading your journey…")).toBeInTheDocument();
  });

  it("shows an error without server details, and tries again", async () => {
    mockApi({ "/api/patterns": patterns(5), "/api/journey": { status: 500, body: { detail: "secret" } } });
    window.history.replaceState(null, "", "/journey");
    render(<App />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The journey could not be loaded. Check that the backend is running");
    expect(alert).not.toHaveTextContent("secret");
    expect(screen.queryByRole("heading", { name: "Hanging pieces: lessons" })).not.toBeInTheDocument();

    mockApi({
      "/api/patterns": patterns(5),
      "/api/journey": journey(lessonSummary(1, { recommended: true })),
    });
    await userEvent.click(within(alert).getByRole("button", { name: "Try again" }));

    expect(await screen.findByRole("heading", { name: "Hanging pieces: lessons" })).toBeInTheDocument();
  });
});

it("reaches the map from the header", async () => {
  mockApi({
    "/api/patterns": patterns(5),
    "/api/findings": page([]),
    "/api/journey": journey(lessonSummary(1, { recommended: true })),
  });
  render(<App />);

  await userEvent.click(screen.getByRole("button", { name: "Journey" }));

  expect(window.location.pathname).toBe("/journey");
  expect(await screen.findByRole("heading", { name: "Hanging pieces: lessons" })).toBeInTheDocument();
  const nav = within(screen.getByRole("navigation", { name: "Main" }));
  expect(nav.getByRole("button", { name: "Journey" })).toHaveAttribute("aria-current", "page");
});
