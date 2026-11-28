import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "../App";
import {
  answeredItem,
  journey,
  lesson,
  lessonAnswer,
  lessonItem,
  lessonSummary,
  mockApi,
  patterns,
} from "../test/mockApi";

const PATH = "/api/lessons/hanging-piece/1";
const STARTED = lessonSummary(1, { state: "IN_PROGRESS", recommended: true });

/** Five fresh items: practice at 1 to 3, the check at 4 and 5. */
const FRESH = [1, 2, 3, 4, 5].map((p) => lessonItem(p));

function open(routes: Record<string, unknown>) {
  const fetchMock = mockApi({ "/api/patterns": patterns(5), "/api/journey": journey(STARTED), ...routes });
  window.history.replaceState(null, "", "/journey/lessons/hanging-piece/1");
  render(<App />);
  return fetchMock;
}

type Call = { method: string; path: string; body: unknown };

function calls(fetchMock: ReturnType<typeof mockApi>): Call[] {
  return fetchMock.mock.calls.map(([input, init]) => ({
    method: init?.method ?? "GET",
    path: typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url,
    body: typeof init?.body === "string" ? (JSON.parse(init.body) as unknown) : undefined,
  }));
}

function posts(fetchMock: ReturnType<typeof mockApi>): Call[] {
  return calls(fetchMock).filter((c) => c.method === "POST");
}

async function play(move: string) {
  const field = await screen.findByLabelText("Your move");
  await userEvent.type(field, `${move}{Enter}`);
}

function status() {
  return screen.getByTestId("puzzle-status");
}

describe("Opening a lesson", () => {
  it("only reads it: a lesson never started shows its explanation and 'Start lesson', and nothing is posted", async () => {
    const fetchMock = open({ [PATH]: lesson(lessonSummary(1, { recommended: true }), []) });

    expect(await screen.findByRole("heading", { name: "Hanging pieces 1" })).toBeInTheDocument();
    expect(screen.getByText("A piece is hanging when …")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start lesson" })).toBeEnabled();
    expect(posts(fetchMock)).toEqual([]);
  });

  it("starts the lesson only with the button, then shows its first puzzle", async () => {
    let started = false;
    const fetchMock = open({
      [PATH]: () => lesson(started ? STARTED : lessonSummary(1, { recommended: true }), started ? FRESH : []),
      [`POST ${PATH}`]: () => {
        started = true;
        return lesson(STARTED, FRESH);
      },
    });

    await userEvent.click(await screen.findByRole("button", { name: "Start lesson" }));

    expect(await screen.findByRole("heading", { name: "Position 1 of 5" })).toBeInTheDocument();
    expect(posts(fetchMock)).toEqual([{ method: "POST", path: PATH, body: undefined }]);
    expect(
      screen.getByRole("group", { name: "White to move: Find a safe move. Board seen from White." }),
    ).toBeVisible();
  });

  it("shows a lesson that cannot be filled yet as an ordinary page", async () => {
    const fetchMock = open({
      [PATH]: lesson(lessonSummary(1, { state: "NEEDS_POSITIONS", size: 0, positionsNeeded: 3 }), []),
    });

    expect(
      await screen.findByText(/3 more positions needed before this lesson can start\./),
    ).toBeInTheDocument();
    expect(screen.getByText("A piece is hanging when …")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Start lesson" })).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(posts(fetchMock)).toEqual([]);
  });

  it("viewing a started lesson posts nothing either", async () => {
    const fetchMock = open({ [PATH]: lesson(STARTED, FRESH) });

    expect(await screen.findByRole("heading", { name: "Position 1 of 5" })).toBeInTheDocument();
    expect(posts(fetchMock)).toEqual([]);
  });

  it("shows the error state for a lesson that does not exist", async () => {
    open({ [PATH]: { status: 404 } });

    expect(await screen.findByRole("alert")).toHaveTextContent("This lesson does not exist");
    expect(screen.getByRole("link", { name: "Back to the journey" })).toBeInTheDocument();
  });

  it("collapses the explanation once a puzzle is answered", async () => {
    open({ [PATH]: lesson(STARTED, [answeredItem(1, true), ...FRESH.slice(1)]) });

    const details = (await screen.findByText("About this lesson")).closest("details");
    expect(details).not.toHaveAttribute("open");
  });
});

describe("Practice puzzles", () => {
  it("send the move at once; a correct answer shows its evidence, and Next moves on", async () => {
    const fetchMock = open({
      [PATH]: lesson(STARTED, FRESH),
      [`POST ${PATH}/items/1/answer`]: lessonAnswer(1, "f3d4", STARTED),
    });

    await play("Nxd4");

    const heading = await screen.findByRole("heading", { name: "Correct" });
    expect(heading).toHaveFocus();
    expect(posts(fetchMock)).toEqual([
      { method: "POST", path: `${PATH}/items/1/answer`, body: { moveUci: "f3d4" } },
    ]);
    const panel = within(heading.closest("section") as HTMLElement);
    expect(panel.getByText("Your move Nxd4 keeps your winning chances.")).toBeInTheDocument();
    expect(panel.getByText(/The engine's move: Nxd4\. Accepted moves: Nc3, Nxd4\./)).toBeInTheDocument();
    expect(panel.getByText("In your game you played Nxe5.")).toBeInTheDocument();
    expect(status()).toHaveTextContent("Nxd4: Correct.");
    // The review on the board: the game's move and the evidence line, as chips.
    expect(screen.getByRole("list", { name: "In your game, and what follows" })).toBeInTheDocument();

    await userEvent.click(panel.getByRole("button", { name: "Next" }));
    expect(await screen.findByRole("heading", { name: "Position 2 of 5" })).toBeInTheDocument();
    expect(screen.getByLabelText("Your move")).toBeEnabled();
  });

  it("a wrong answer says 'Not this one' with what it costs and the line that refutes it", async () => {
    open({
      [PATH]: lesson(STARTED, FRESH),
      [`POST ${PATH}/items/1/answer`]: lessonAnswer(1, "f3e5", STARTED),
    });

    await play("Nxe5");

    const heading = await screen.findByRole("heading", { name: "Not this one" });
    const panel = within(heading.closest("section") as HTMLElement);
    expect(panel.getByText("Your move Nxe5 costs about 60% of your winning chances.")).toBeInTheDocument();
    expect(panel.getByText("It is refuted by: 4… Qg5 5. Nxf7 5… Qxg2")).toBeInTheDocument();
    expect(status()).toHaveTextContent("Nxe5: Not this one.");
  });
});

describe("Mastery check puzzles", () => {
  const CHECK_NEXT = lesson(STARTED, [
    answeredItem(1, true),
    answeredItem(2, true),
    answeredItem(3, false),
    lessonItem(4),
    lessonItem(5),
  ]);

  it("are marked, and the move waits for 'Submit move'", async () => {
    const fetchMock = open({
      [PATH]: CHECK_NEXT,
      [`POST ${PATH}/items/4/answer`]: lessonAnswer(4, "b1c3", STARTED),
    });

    expect(await screen.findByRole("heading", { name: "Position 4 of 5" })).toBeInTheDocument();
    expect(screen.getByText("Mastery check: one try")).toBeInTheDocument();

    await play("Nxd4");
    expect(screen.getByRole("heading", { name: "You chose Nxd4" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Submit move" })).toHaveFocus();
    expect(status()).toHaveTextContent("You chose Nxd4. Submit it, or choose another move.");
    expect(posts(fetchMock)).toEqual([]);

    await userEvent.click(screen.getByRole("button", { name: "Choose another move" }));
    expect(screen.getByLabelText("Your move")).toBeEnabled();
    expect(posts(fetchMock)).toEqual([]);

    await play("Nc3");
    await userEvent.click(screen.getByRole("button", { name: "Submit move" }));

    expect(await screen.findByRole("heading", { name: "Correct" })).toBeInTheDocument();
    expect(posts(fetchMock)).toEqual([
      { method: "POST", path: `${PATH}/items/4/answer`, body: { moveUci: "b1c3" } },
    ]);
  });

  it("a 409 says why and reloads the lesson, moving on to what is still open", async () => {
    let answered = false;
    const fetchMock = open({
      [PATH]: () =>
        answered
          ? lesson(STARTED, [...CHECK_NEXT.items.slice(0, 3), answeredItem(4, false), lessonItem(5)])
          : CHECK_NEXT,
      [`POST ${PATH}/items/4/answer`]: () => {
        answered = true; // another tab answered it first
        return { status: 409 };
      },
    });

    await play("Nxd4");
    await userEvent.click(screen.getByRole("button", { name: "Submit move" }));

    const notice = await screen.findByRole("region", { name: "Notice" });
    expect(notice).toHaveTextContent("it had been answered already, or the lesson changed");
    expect(notice).toHaveFocus();
    expect(await screen.findByRole("heading", { name: "Position 5 of 5" })).toBeInTheDocument();
    expect(calls(fetchMock).filter((c) => c.method === "GET" && c.path === PATH)).toHaveLength(2);
  });
});

describe("Errors while answering", () => {
  it("a 400 is refused without a reload, and the move can be chosen again", async () => {
    const fetchMock = open({
      [PATH]: lesson(STARTED, FRESH),
      [`POST ${PATH}/items/1/answer`]: { status: 400 },
    });

    await play("Nxd4");

    expect(await screen.findByRole("region", { name: "Notice" })).toHaveTextContent(
      "The server did not accept this move, and nothing was recorded.",
    );
    expect(screen.getByLabelText("Your move")).toBeEnabled();
    expect(screen.getByRole("heading", { name: "Position 1 of 5" })).toBeInTheDocument();
    expect(calls(fetchMock).filter((c) => c.method === "GET" && c.path === PATH)).toHaveLength(1);
  });

  it("another failure keeps the move, to send again", async () => {
    let fail = true;
    const fetchMock = open({
      [PATH]: lesson(STARTED, FRESH),
      [`POST ${PATH}/items/1/answer`]: () => (fail ? { status: 500 } : lessonAnswer(1, "f3d4", STARTED)),
    });

    await play("Nxd4");

    expect(await screen.findByRole("heading", { name: "The move could not be sent" })).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Nxd4 was not recorded");
    expect(screen.getByRole("heading", { name: "Position 1 of 5" })).toBeInTheDocument();

    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "Send again" }));

    expect(await screen.findByRole("heading", { name: "Correct" })).toBeInTheDocument();
    expect(posts(fetchMock)).toHaveLength(2);
  });

  it("a puzzle a re-analysis removed (404) says so and reloads", async () => {
    open({ [PATH]: lesson(STARTED, FRESH), [`POST ${PATH}/items/1/answer`]: { status: 404 } });

    await play("Nxd4");

    expect(await screen.findByRole("region", { name: "Notice" })).toHaveTextContent(
      "This puzzle is no longer part of the lesson",
    );
  });
});

describe("Positions with gaps", () => {
  it("counts the items shown, and answers by the item's own position", async () => {
    // A retry after a re-analysis: positions 1, 3 and 4 are left of the first five, the retry is at 6 and 7.
    const fetchMock = open({
      [PATH]: lesson(STARTED, [
        answeredItem(1, true),
        answeredItem(3, true),
        answeredItem(4, false),
        lessonItem(6),
        lessonItem(7),
      ]),
      [`POST ${PATH}/items/6/answer`]: lessonAnswer(6, "f3d4", STARTED),
    });

    expect(await screen.findByRole("heading", { name: "Position 4 of 5" })).toBeInTheDocument();
    await play("Nxd4");
    await userEvent.click(screen.getByRole("button", { name: "Submit move" }));

    expect(await screen.findByRole("heading", { name: "Correct" })).toBeInTheDocument();
    expect(posts(fetchMock).map((c) => c.path)).toEqual([`${PATH}/items/6/answer`]);
  });
});

describe("The result", () => {
  const ALL_ANSWERED = [
    answeredItem(1, true),
    answeredItem(2, false),
    answeredItem(3, true),
    answeredItem(4, true),
    answeredItem(5, true),
  ];

  it("shows a mastered lesson per position, says it is practice, and offers the recommended next lesson", async () => {
    open({
      [PATH]: lesson(lessonSummary(1, { state: "MASTERED", solvedFirstTry: 4 }), ALL_ANSWERED),
      "/api/journey": journey(lessonSummary(1, { state: "MASTERED" }), [
        lessonSummary(2, { recommended: true }),
      ]),
    });

    const heading = await screen.findByRole("heading", { name: "Result: Hanging pieces 1" });
    expect(heading).toHaveFocus();
    const result = within(heading.closest("section") as HTMLElement);
    expect(result.getByText(/^Mastered:/)).toBeInTheDocument();
    const rows = result.getAllByRole("listitem");
    expect(rows.map((r) => r.querySelector(".result__label")?.textContent)).toEqual([
      "Position 1: solved first try",
      "Position 2: not solved first try",
      "Position 3: solved first try",
      "Position 4 (mastery check): solved first try",
      "Position 5 (mastery check): solved first try",
    ]);
    expect(result.getAllByRole("link", { name: "Game on Lichess" })).toHaveLength(5);
    expect(result.getByText(/not a test of whether the lesson carries over/)).toBeInTheDocument();
    expect(result.queryByRole("button", { name: "Retry the mastery check" })).not.toBeInTheDocument();

    await userEvent.click(await result.findByRole("button", { name: "Next lesson: Hanging pieces 2" }));
    expect(window.location.pathname).toBe("/journey/lessons/hanging-piece/2");
  });

  it("offers 'Retry the mastery check' only when a retry is available, and the retry continues the lesson", async () => {
    const failed = lessonSummary(1, { state: "IN_PROGRESS", recommended: true, retryAvailable: true });
    let retried = false;
    const fetchMock = open({
      [PATH]: () =>
        retried
          ? lesson({ ...failed, retryAvailable: false }, [...ALL_ANSWERED, lessonItem(7), lessonItem(8)])
          : lesson(failed, ALL_ANSWERED),
      [`POST ${PATH}`]: () => {
        retried = true;
        return lesson(failed, []);
      },
    });

    expect(await screen.findByText(/^Not mastered yet/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Retry the mastery check" }));

    expect(await screen.findByRole("heading", { name: "Position 6 of 7" })).toBeInTheDocument();
    expect(screen.getByText("Mastery check: one try")).toBeInTheDocument();
    expect(posts(fetchMock)).toEqual([{ method: "POST", path: PATH, body: undefined }]);
  });

  it("without unused positions, says how many a retry needs instead of offering it", async () => {
    open({
      [PATH]: lesson(lessonSummary(1, { state: "IN_PROGRESS", positionsNeeded: 2 }), ALL_ANSWERED),
    });

    expect(
      await screen.findByText(/A retry of the mastery check needs 2 more positions\./),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry the mastery check" })).not.toBeInTheDocument();
  });

  it("shows a void check: a check item deleted by a re-analysis, the rest answered", async () => {
    // Position 5 was deleted; position 4 is the check's only item left, so the check cannot be passed.
    open({
      [PATH]: lesson(
        lessonSummary(1, { state: "IN_PROGRESS", positionsNeeded: 2 }),
        ALL_ANSWERED.slice(0, 4),
      ),
    });

    const heading = await screen.findByRole("heading", { name: "Result: Hanging pieces 1" });
    const result = within(heading.closest("section") as HTMLElement);
    expect(result.getAllByRole("listitem")).toHaveLength(4);
    expect(result.getByText(/^Not mastered yet/)).toBeInTheDocument();
    expect(result.getByText(/A retry of the mastery check needs 2 more positions\./)).toBeInTheDocument();
  });

  it("comes after the last puzzle of the visit", async () => {
    let answered = false;
    open({
      [PATH]: () =>
        lesson(
          lessonSummary(1, { state: answered ? "MASTERED" : "IN_PROGRESS" }),
          answered ? ALL_ANSWERED : [...ALL_ANSWERED.slice(0, 4), lessonItem(5)],
        ),
      [`POST ${PATH}/items/5/answer`]: () => {
        answered = true;
        return lessonAnswer(5, "f3d4", lessonSummary(1, { state: "MASTERED" }));
      },
    });

    await play("Nxd4");
    await userEvent.click(screen.getByRole("button", { name: "Submit move" }));
    await userEvent.click(await screen.findByRole("button", { name: "Next" }));

    expect(await screen.findByRole("heading", { name: "Result: Hanging pieces 1" })).toBeInTheDocument();
    expect(screen.getByText(/^Mastered:/)).toBeInTheDocument();
  });
});

it("shows a puzzle of another task as a plain message", async () => {
  open({ [PATH]: lesson(STARTED, [lessonItem(1, { task: "SEE_THE_CAPTURE", prompt: "See the capture" })]) });

  expect(await screen.findByText("This kind of puzzle cannot be shown yet.")).toBeInTheDocument();
  expect(screen.queryByLabelText("Your move")).not.toBeInTheDocument();
});

it("has no gamification: no points, streaks, badges or timers anywhere in a finished lesson", async () => {
  open({
    [PATH]: lesson(lessonSummary(1, { state: "MASTERED" }), [answeredItem(1, true), answeredItem(4, true)]),
  });

  await screen.findByRole("heading", { name: "Result: Hanging pieces 1" });
  expect(document.body.textContent).not.toMatch(
    /points|XP|streak|badge|level up|well done|great job|seconds/i,
  );
});
