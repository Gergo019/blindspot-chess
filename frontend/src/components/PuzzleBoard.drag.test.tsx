import { act, render, screen } from "@testing-library/react";
import type { ChessboardOptions } from "react-chessboard";
import { PuzzleBoard } from "./PuzzleBoard";

/**
 * Dragging (#94). react-chessboard drags through dnd-kit's pointer sensors, which jsdom cannot drive, so
 * this file replaces the board with a stub that keeps the options PuzzleBoard passes, and calls the drop
 * handler as react-chessboard does when a piece is dropped. The real board and the other input methods
 * are in PuzzleBoard.test.tsx.
 */
const board = vi.hoisted(() => ({ options: undefined as ChessboardOptions | undefined }));
vi.mock("react-chessboard", () => ({
  Chessboard: ({ options }: { options: ChessboardOptions }) => {
    board.options = options;
    return null;
  },
  defaultPieces: {},
}));

const FORK = "r1bqkbnr/pppp1ppp/8/4p3/2BnP3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";
const PROMOTION = "3r3k/3PP3/8/8/8/8/7P/4K2R w K - 0 1";

function drop(sourceSquare: string, targetSquare: string | null): boolean | undefined {
  let accepted: boolean | undefined;
  act(() => {
    accepted = board.options?.onPieceDrop?.({
      piece: { isSparePiece: false, position: sourceSquare, pieceType: "wN" },
      sourceSquare,
      targetSquare,
    });
  });
  return accepted;
}

function setup(fen = FORK) {
  const onMove = vi.fn();
  render(
    <PuzzleBoard fen={fen} orientation="WHITE" prompt="Find a safe move" onMove={onMove} result={null} />,
  );
  return onMove;
}

describe("PuzzleBoard dragging", () => {
  it("hands a legal drop to the callback and keeps the piece where it was dropped", () => {
    const onMove = setup();

    expect(drop("f3", "d4")).toBe(true);

    expect(onMove).toHaveBeenCalledExactlyOnceWith({ uci: "f3d4", san: "Nxd4", fen: FORK });
    expect(screen.getByTestId("puzzle-status")).toHaveTextContent("You played Nxd4. Checking…");
    expect(board.options?.allowDragging).toBe(false); // one answer per mount
  });

  it("refuses an illegal drop, which sends the piece back", () => {
    const onMove = setup();

    expect(drop("f3", "f5")).toBe(false);
    expect(drop("f3", null)).toBe(false); // dropped off the board

    expect(onMove).not.toHaveBeenCalled();
    expect(screen.getByTestId("puzzle-status")).toHaveTextContent("f3 to f5 is not a legal move here.");
    expect(board.options?.allowDragging).toBe(true);
  });

  it("lets only the side to move drag", () => {
    setup();

    const canDrag = board.options?.canDragPiece;
    expect(canDrag?.({ isSparePiece: false, piece: { pieceType: "wN" }, square: "f3" })).toBe(true);
    expect(canDrag?.({ isSparePiece: false, piece: { pieceType: "bN" }, square: "d4" })).toBe(false);
  });

  it("asks for the piece when a pawn is dropped on the last rank", () => {
    const onMove = setup(PROMOTION);

    expect(drop("e7", "e8")).toBe(false);

    expect(screen.getByRole("group", { name: "Promote the pawn to" })).toBeInTheDocument();
    expect(onMove).not.toHaveBeenCalled();
  });
});
