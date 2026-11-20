import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "../App";
import { playLine } from "../chess/notation";
import { detail, FEN_BEFORE, mockApi, mockHappyApi, MOVES, page, patterns, summary } from "../test/mockApi";

const LINE = playLine(FEN_BEFORE, ["f3e5", "d8g5", "e5f7", "g5g2"]);

function board() {
  return screen.getByTestId("board");
}

async function openFinding(id = 1) {
  window.history.replaceState(null, "", `/findings/${String(id)}`);
  render(<App />);
  await screen.findByRole("heading", { name: /Hanging piece · 4\. Nxe5/ });
}

describe("Your blind spot", () => {
  it("shows the count and the sample size, and says plainly when it is below the threshold", async () => {
    mockHappyApi({ "/api/patterns": patterns(2) });
    render(<App />);
    const card = await screen.findByRole("region", { name: "Your blind spot" });
    const hanging = within(card).getByText("Hanging piece").closest("li");
    expect(hanging).toHaveTextContent("in 2 of your last 20 rapid games");
    expect(hanging).toHaveTextContent("Below the pattern threshold of 3 games, so not a pattern yet.");
    // Only motifs the API lists: no placeholder line for a motif without a detector.
    expect(within(card).queryByText("Allowed knight fork")).not.toBeInTheDocument();
    expect(within(card).getAllByRole("listitem")).toHaveLength(1);
  });

  it("calls three or more games a pattern", async () => {
    mockHappyApi({ "/api/patterns": patterns(5) });
    render(<App />);
    const card = await screen.findByRole("region", { name: "Your blind spot" });
    expect(within(card).getByText("Hanging piece").closest("li")).toHaveTextContent(
      "A pattern: 3 or more games.",
    );
  });

  it("says when fewer games than the window are analysed", async () => {
    mockHappyApi({ "/api/patterns": patterns(3, 7) });
    render(<App />);
    const card = await screen.findByRole("region", { name: "Your blind spot" });
    expect(card).toHaveTextContent("in 3 of your last 7 rapid games");
    expect(card).toHaveTextContent("Only 7 analysed rapid games so far; the report looks at the last 20.");
  });
});

describe("Findings and the URL", () => {
  it("selects the newest finding and puts it in the URL", async () => {
    mockHappyApi();
    render(<App />);
    await screen.findByRole("heading", { name: /Hanging piece · 4\. Nxe5/ });
    expect(window.location.pathname).toBe("/findings/1");
  });

  it("opens a deep link directly, even if it is not on the first page", async () => {
    const fetchMock = mockHappyApi({ "/api/findings/42": detail(42) });
    window.history.replaceState(null, "", "/findings/42");
    render(<App />);
    await screen.findByRole("heading", { name: /Hanging piece · 4\. Nxe5/ });
    expect(fetchMock).toHaveBeenCalledWith("/api/findings/42", expect.anything());
    expect(window.location.pathname).toBe("/findings/42");
  });

  it("selecting another finding changes the URL, and Back returns to the previous one", async () => {
    mockHappyApi({
      "/api/findings": page([summary(1), summary(2, { ply: 7 })]),
      "/api/findings/2": detail(2, { explanation: { content: "Second finding." } }),
    });
    await openFinding(1);
    const rows = screen.getAllByRole("button", { name: /Hanging piece: pawn on g2/ });
    expect(rows[0]).toHaveAttribute("aria-current", "true");

    await userEvent.click(rows[1] as HTMLElement);
    expect(window.location.pathname).toBe("/findings/2");
    expect(await screen.findByText("Second finding.")).toBeInTheDocument();

    window.history.back();
    await waitFor(() => {
      expect(window.location.pathname).toBe("/findings/1");
    });
    await waitFor(() => {
      expect(screen.queryByText("Second finding.")).not.toBeInTheDocument();
    });
  });

  it("shows the empty state when there are no findings", async () => {
    mockApi({ "/api/patterns": patterns(0, 0), "/api/findings": page([]) });
    render(<App />);
    expect(await screen.findByRole("heading", { name: "No findings yet" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });
});

describe("The board and the evidence line", () => {
  it("starts before the mistake with the move as the orange arrow and the facts marked", async () => {
    mockHappyApi();
    await openFinding();
    expect(board()).toHaveAttribute("data-fen", FEN_BEFORE);
    expect(board().querySelector('[data-arrow="f3e5"]')).toHaveAttribute("data-kind", "mistake");
    expect(screen.getByText("Position before your move 4. Nxe5")).toBeInTheDocument();
  });

  it("derives the chips' SAN from FEN and UCI, and a chip shows the position after that move", async () => {
    mockHappyApi();
    await openFinding();
    const chips = within(screen.getByRole("list", { name: "Your move and what follows" })).getAllByRole(
      "button",
    );
    expect(chips.map((c) => c.textContent)).toEqual(["4. Nxe5", "4… Qg5", "5. Nxf7", "5… Qxg2"]);

    await userEvent.click(chips[2] as HTMLElement);
    expect(board()).toHaveAttribute("data-fen", LINE[2]?.fenAfter);
    expect(board().querySelector('[data-arrow="e5f7"]')).toHaveAttribute("data-kind", "evidence");
    expect(chips[2]).toHaveAttribute("aria-current", "step");
    expect(screen.getByText("Evidence move 2 of 3: 5. Nxf7")).toBeInTheDocument();
  });

  it("steps through the line with the arrow keys, Home and End", async () => {
    mockHappyApi();
    await openFinding();
    await userEvent.keyboard("{ArrowRight}");
    expect(board()).toHaveAttribute("data-fen", LINE[0]?.fenAfter);
    await userEvent.keyboard("{ArrowRight}");
    expect(board()).toHaveAttribute("data-fen", LINE[1]?.fenAfter);
    await userEvent.keyboard("{End}");
    expect(board()).toHaveAttribute("data-fen", LINE[3]?.fenAfter);
    await userEvent.keyboard("{ArrowRight}");
    expect(board()).toHaveAttribute("data-fen", LINE[3]?.fenAfter);
    await userEvent.keyboard("{ArrowLeft}");
    expect(board()).toHaveAttribute("data-fen", LINE[2]?.fenAfter);
    await userEvent.keyboard("{Home}");
    expect(board()).toHaveAttribute("data-fen", FEN_BEFORE);
  });

  it("shows the engine's best move on request", async () => {
    mockHappyApi();
    await openFinding();
    await userEvent.click(screen.getByRole("button", { name: "Show the best move" }));
    expect(board()).toHaveAttribute("data-fen", FEN_BEFORE);
    expect(board().querySelector('[data-arrow="f3d4"]')).toHaveAttribute("data-kind", "evidence");
    expect(screen.getByText("The engine's best move instead: 4. Nxd4")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Find a better move/ })).not.toBeInTheDocument();
  });

  it("shows a game position from the move list, with the finding highlighted", async () => {
    mockHappyApi();
    await openFinding();
    const moves = await screen.findByRole("region", { name: "Moves" });
    const finding = within(moves).getByRole("button", { name: /4\. Nxe5, Blunder, the finding/ });
    expect(finding).toHaveClass("move--finding");

    await userEvent.click(within(moves).getByRole("button", { name: /2… Nc6/ }));
    const replay = playLine(
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      MOVES.moves.map((m) => m.played.uci),
    );
    expect(board()).toHaveAttribute("data-fen", replay[3]?.fenAfter);
    await userEvent.keyboard("{ArrowRight}");
    expect(board()).toHaveAttribute("data-fen", replay[4]?.fenAfter);
  });
});

describe("Facts, explanation and footer", () => {
  it("lists the facts, has no explanation section when the API returns none, and names the engine", async () => {
    mockHappyApi();
    await openFinding();
    expect(screen.getByText("Captured by").nextSibling).toHaveTextContent("queen");
    const lost = screen.getByText("Material lost compared with the best move").nextSibling;
    expect(lost).toHaveTextContent("1 point");
    // The hint is its own row under the value, spanning both columns.
    expect(lost?.nextSibling).toHaveClass("facts__hint");
    expect(lost?.nextSibling).toHaveTextContent(
      "How far your line ends behind the engine's best line over the same moves",
    );
    expect(screen.queryByRole("heading", { name: "Why" })).not.toBeInTheDocument();
    expect(
      screen.getByText(/Stockfish 19, depth 16, 1 thread, 64 MB hash · detector hanging-0.4/),
    ).toBeInTheDocument();
  });

  it("shows the explanation when the API returns one", async () => {
    mockHappyApi({
      "/api/findings/1": detail(1, { explanation: { content: "Your knight on e5 was undefended." } }),
    });
    await openFinding();
    expect(screen.getByRole("heading", { name: "Why" })).toBeInTheDocument();
    expect(screen.getByText("Your knight on e5 was undefended.")).toBeInTheDocument();
  });
});

describe("Errors", () => {
  it("shows a plain error without server details, and retries", async () => {
    const secret = "org.postgresql.util.PSQLException: connection refused at 10.0.0.5";
    const fetchMock = mockApi({
      "/api/patterns": patterns(2),
      "/api/findings": { status: 500, body: { title: "Internal error", detail: secret } },
    });
    render(<App />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The list of findings could not be loaded.");
    expect(document.body).not.toHaveTextContent(secret);
    expect(document.body).not.toHaveTextContent("500");

    mockApi({ "/api/patterns": patterns(2), "/api/findings": page([]) });
    await userEvent.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("heading", { name: "No findings yet" })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalled();
  });

  it("says a finding does not exist for a 404, without retrying", async () => {
    mockHappyApi({ "/api/findings/99": { status: 404, body: { detail: "No finding with id 99" } } });
    window.history.replaceState(null, "", "/findings/99");
    render(<App />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("This finding does not exist, or it is not one of yours.");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
  });
});
