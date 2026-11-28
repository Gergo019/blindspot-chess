import { Chessboard } from "react-chessboard";
import type { BoardView } from "../findings/boardView";
import { BoardArrows } from "./BoardArrows";
import { BOARD_LOOK, prefersReducedMotion, squareStylesFor } from "./boardLook";

/**
 * The board with evidence drawn on it (ADR 0007): squares as rings between halo rings (highlight.ts), and
 * arrows drawn in an SVG layer over the board (BoardArrows), so they get the same dark halo. Plain
 * react-chessboard arrows would be a flat colour, which reaches only 1.0 to 1.9:1 on the squares (#50).
 * Read-only: no dragging, no drawing. PuzzleBoard shares the look (boardLook.ts) and the arrows.
 *
 * For assistive technology the board is one labelled image, and its inside is `inert`: react-chessboard
 * renders every piece as a disabled "draggable" button with tabindex 0 (dnd-kit), which put 25 to 32
 * unnamed tab stops with drag instructions on a read-only board (found by axe in #52). The caption, the
 * move chips and the move list carry the same information for keyboard and screen-reader users.
 */
export function EvidenceBoard({
  view,
  label,
}: {
  view: Pick<BoardView, "fen" | "orientation" | "arrows" | "marks">;
  label: string;
}) {
  return (
    <div className="board" data-testid="board" data-fen={view.fen} role="img" aria-label={label}>
      <div className="board__inner" inert>
        <Chessboard
          options={{
            ...BOARD_LOOK,
            id: "evidence-board",
            position: view.fen,
            boardOrientation: view.orientation,
            allowDragging: false,
            allowDrawingArrows: false,
            showAnimations: !prefersReducedMotion(),
            squareStyles: squareStylesFor(view.marks),
          }}
        />
        <BoardArrows arrows={view.arrows} orientation={view.orientation} />
      </div>
    </div>
  );
}
