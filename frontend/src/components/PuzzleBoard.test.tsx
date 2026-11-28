import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { type ComponentProps } from "react";
import { PuzzleBoard } from "./PuzzleBoard";

/**
 * PuzzleBoard (#94) with the real react-chessboard: the text field and click-to-move, promotion, the status
 * announcements, orientation, and what is (not) in the DOM before and after the answer. Dragging runs
 * through dnd-kit's pointer sensors, which jsdom cannot drive; PuzzleBoard.drag.test.tsx checks the drop
 * handler instead.
 */

/** After 1. e4 e5 2. Nf3 Nc6 3. Bc4 Nd4, White to move (the backend's TestData.FORK_FEN). */
const FORK = "r1bqkbnr/pppp1ppp/8/4p3/2BnP3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";
const PROMOTION = "3r3k/3PP3/8/8/8/8/7P/4K2R w K - 0 1";
/** The game's move and its evidence line in FORK (the findings screen's fixture). */
const REVIEW = { gameMoveUci: "f3e5", evidenceLineUci: ["d8g5", "e5f7", "g5g2"] };

function setup(props: Partial<ComponentProps<typeof PuzzleBoard>> = {}) {
  const onMove = vi.fn();
  const utils = render(
    <PuzzleBoard
      fen={FORK}
      orientation="WHITE"
      prompt="Find a safe move"
      onMove={onMove}
      result={null}
      {...props}
    />,
  );
  const square = (name: string) => {
    const element = utils.container.querySelector(`[data-square="${name}"]`);
    if (!element) throw new Error(`no square ${name}`);
    return element as HTMLElement;
  };
  return { ...utils, onMove, square };
}

/**
 * PuzzleBoard's status region. Not found by role alone: dnd-kit renders its own role="status" region for
 * drag announcements inside the board.
 */
function status() {
  const region = screen.getByTestId("puzzle-status");
  expect(region).toHaveAttribute("role", "status");
  return region;
}

async function type(move: string) {
  const field = screen.getByLabelText("Your move");
  await userEvent.clear(field);
  await userEvent.type(field, `${move}{Enter}`);
}

describe("PuzzleBoard", () => {
  it("labels the board with the side to move and the task, and the field visibly", () => {
    setup();

    expect(
      screen.getByRole("group", { name: "White to move: Find a safe move. Board seen from White." }),
    ).toBeInTheDocument();
    expect(screen.getByText("Your move").tagName).toBe("LABEL");
    expect(screen.getByLabelText("Your move")).toHaveAccessibleDescription(
      "In SAN or UCI, for example Nf3 or g1f3.",
    );
    expect(status()).toHaveTextContent("");
  });

  it.each([
    ["WHITE", "white", "a8"],
    ["BLACK", "black", "h1"],
  ] as const)("draws the board from the user's side (%s)", (orientation, drawn, topLeft) => {
    const { container } = setup({
      orientation,
      fen:
        orientation === "BLACK" ? "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 3 3" : FORK,
    });

    expect(screen.getByTestId("puzzle-board")).toHaveAttribute("data-orientation", drawn);
    expect(container.querySelector("[data-square]")).toHaveAttribute("data-square", topLeft);
    const side = orientation === "WHITE" ? "White" : "Black";
    expect(
      screen.getByRole("group", { name: `${side} to move: Find a safe move. Board seen from ${side}.` }),
    ).toBeVisible();
  });

  it("names every piece, so the board's draggable buttons are not unnamed (#97, axe aria-command-name)", () => {
    const { square } = setup();

    expect(within(square("f3")).getByRole("img", { name: "White knight on f3" })).toBeInTheDocument();
    expect(within(square("d4")).getByRole("img", { name: "Black knight on d4" })).toBeInTheDocument();
    const draggable = square("f3").querySelector('[aria-roledescription="draggable"]');
    expect(draggable).toHaveAccessibleName("White knight on f3");
    expect(screen.getAllByRole("img", { name: / on [a-h][1-8]$/ })).toHaveLength(32); // nothing captured yet
  });

  describe("the text field", () => {
    it.each([
      ["Nxd4", "f3d4", "Nxd4"],
      ["b1c3", "b1c3", "Nc3"],
    ])(
      "hands a legal move (%s) to the callback with the position it was played from",
      async (input, uci, san) => {
        const { onMove } = setup();

        await type(input);

        expect(onMove).toHaveBeenCalledExactlyOnceWith({ uci, san, fen: FORK });
        expect(status()).toHaveTextContent(`You played ${san}. Checking…`);
        expect(screen.getByLabelText("Your move")).toBeDisabled();
        expect(screen.getByTestId("puzzle-board").dataset["fen"]).not.toBe(FORK);
      },
    );

    it("refuses an illegal move at once and keeps the puzzle open", async () => {
      const { onMove } = setup();

      await type("e2e5");

      expect(onMove).not.toHaveBeenCalled();
      expect(status()).toHaveTextContent("“e2e5” is not a legal move here.");
      expect(screen.getByLabelText("Your move")).toHaveAttribute("aria-invalid", "true");
      expect(screen.getByTestId("puzzle-board")).toHaveAttribute("data-fen", FORK);

      await type("Nxd4");
      expect(onMove).toHaveBeenCalledOnce();
      expect(screen.getByLabelText("Your move")).toHaveAttribute("aria-invalid", "false");
    });
  });

  describe("clicking the piece, then the target square", () => {
    it("hands a legal move to the callback", async () => {
      const { onMove, square } = setup();

      await userEvent.click(square("f3"));
      await userEvent.click(square("d4"));

      expect(onMove).toHaveBeenCalledExactlyOnceWith({ uci: "f3d4", san: "Nxd4", fen: FORK });
      expect(status()).toHaveTextContent("You played Nxd4. Checking…");
    });

    it("refuses an illegal move, and a click on another own piece selects that one", async () => {
      const { onMove, square } = setup();

      await userEvent.click(square("f3"));
      await userEvent.click(square("f5"));
      expect(onMove).not.toHaveBeenCalled();
      expect(status()).toHaveTextContent("f3 to f5 is not a legal move here.");

      await userEvent.click(square("f3"));
      await userEvent.click(square("b1")); // another white piece: now the knight on b1 is selected
      await userEvent.click(square("c3"));
      expect(onMove).toHaveBeenCalledExactlyOnceWith({ uci: "b1c3", san: "Nc3", fen: FORK });
    });

    it("ignores a first click on an empty square or an opponent's piece", async () => {
      const { onMove, square } = setup();

      await userEvent.click(square("d4")); // a black knight
      await userEvent.click(square("e5"));
      await userEvent.click(square("a3"));

      expect(onMove).not.toHaveBeenCalled();
      expect(status()).toHaveTextContent("");
    });
  });

  describe("promotion", () => {
    it("asks for the piece in a labelled group, and hands on the chosen one", async () => {
      const { onMove, square } = setup({ fen: PROMOTION, prompt: "Promote" });

      await userEvent.click(square("e7"));
      await userEvent.click(square("e8"));

      const group = screen.getByRole("group", { name: "Promote the pawn to" });
      const buttons = within(group).getAllByRole("button");
      expect(buttons.map((b) => b.textContent)).toEqual(["Queen", "Rook", "Bishop", "Knight", "Cancel"]);
      expect(buttons[0]).toHaveFocus();
      expect(onMove).not.toHaveBeenCalled();

      await userEvent.click(within(group).getByRole("button", { name: "Knight" }));

      expect(onMove).toHaveBeenCalledExactlyOnceWith({ uci: "e7e8n", san: "e8=N", fen: PROMOTION });
      expect(screen.queryByRole("group", { name: "Promote the pawn to" })).not.toBeInTheDocument();
    });

    it("works from the text field too, and can be cancelled", async () => {
      const { onMove } = setup({ fen: PROMOTION });

      await type("e8");
      await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
      expect(screen.queryByRole("group", { name: "Promote the pawn to" })).not.toBeInTheDocument();
      expect(onMove).not.toHaveBeenCalled();

      await type("e7e8");
      await userEvent.click(screen.getByRole("button", { name: "Queen" }));
      expect(onMove).toHaveBeenCalledExactlyOnceWith({ uci: "e7e8q", san: "e8=Q+", fen: PROMOTION });
    });
  });

  describe("the result", () => {
    it.each([
      ["correct", "Nxd4: Correct."],
      ["wrong", "Nxd4: Not this one."],
    ] as const)("announces the server's verdict (%s) in the status region", async (result, text) => {
      const { rerender, onMove } = setup();
      await type("Nxd4");

      rerender(
        <PuzzleBoard
          fen={FORK}
          orientation="WHITE"
          prompt="Find a safe move"
          onMove={onMove}
          result={result}
        />,
      );

      expect(status()).toHaveTextContent(text);
    });

    it("takes one answer per mount", async () => {
      const { onMove, square } = setup();
      await type("Nxd4");

      await userEvent.click(square("b1"));
      await userEvent.click(square("c3"));

      expect(onMove).toHaveBeenCalledOnce();
      expect(screen.getByRole("button", { name: "Play" })).toBeDisabled();
    });
  });

  describe("solution data", () => {
    it("is not in the DOM before the answer", () => {
      const { container } = setup();

      expect(container.querySelector("[data-arrow]")).toBeNull();
      expect(screen.queryByRole("list")).not.toBeInTheDocument();
      // Nothing of the game's move or the evidence line: no arrow, no chip, no SAN, no UCI.
      expect(container.innerHTML).not.toMatch(/Nxe5|Qg5|f3e5|d8g5|e5f7|In your game/);
    });

    it("is not even a prop: the accepted moves and the engine's move cannot be passed", () => {
      // A type-level check (tsc runs on the tests): the component has no prop for any answer.
      render(
        <PuzzleBoard
          fen={FORK}
          orientation="WHITE"
          prompt="Find a safe move"
          onMove={vi.fn()}
          result={null}
          // @ts-expect-error -- PuzzleBoard is never given the accepted moves
          acceptedMoves={["f3d4"]}
        />,
      );
      expect(screen.getByRole("group", { name: /White to move/ })).toBeInTheDocument();
    });

    it("after the answer, shows the game's move in orange and the evidence line in blue, stepped by chips and arrow keys", async () => {
      const { container } = setup({ result: "wrong", review: REVIEW });

      const chips = within(screen.getByRole("list", { name: "In your game, and what follows" })).getAllByRole(
        "button",
      );
      expect(chips.map((c) => c.textContent)).toEqual(["4. Nxe5", "4… Qg5", "5. Nxf7", "5… Qxg2"]);
      expect(container.querySelector("[data-arrow]")).toHaveAttribute("data-kind", "mistake");
      expect(container.querySelector("[data-arrow]")).toHaveAttribute("data-arrow", "f3e5");
      expect(screen.getByText("Position before your move 4. Nxe5")).toBeInTheDocument();

      await userEvent.keyboard("{ArrowRight}{ArrowRight}");
      expect(container.querySelector("[data-arrow]")).toHaveAttribute("data-arrow", "d8g5");
      expect(container.querySelector("[data-arrow]")).toHaveAttribute("data-kind", "evidence");
      expect(chips[1]).toHaveAttribute("aria-current", "step");

      await userEvent.click(chips[3] as HTMLElement);
      expect(screen.getByText("Evidence move 3 of 3: 5… Qxg2")).toBeInTheDocument();
      await userEvent.keyboard("{Home}");
      expect(container.querySelector("[data-arrow]")).toHaveAttribute("data-arrow", "f3e5");

      expect(screen.getByLabelText("Your move")).toBeDisabled(); // the review is not a second try
    });
  });
});
