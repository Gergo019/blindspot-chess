import { Chessboard } from "react-chessboard";

const START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

type Props = { position?: string };

/**
 * The board (ADR 0007): blue-grey squares, coordinates inside the edge squares, read-only. Colours
 * come from the theme tokens, so it follows the theme without re-rendering. Highlights and arrows
 * arrive with the findings screen (#24).
 */
export function Board({ position = START }: Props) {
  return (
    <div className="board" data-testid="board">
      <Chessboard
        options={{
          id: "main-board",
          position,
          allowDragging: false,
          allowDrawingArrows: false,
          showNotation: true,
          lightSquareStyle: { backgroundColor: "var(--square-light)" },
          darkSquareStyle: { backgroundColor: "var(--square-dark)" },
          lightSquareNotationStyle: { color: "var(--coordinate)" },
          darkSquareNotationStyle: { color: "var(--coordinate)" },
          boardStyle: { borderRadius: "6px", overflow: "hidden" },
        }}
      />
    </div>
  );
}
