import type { CSSProperties } from "react";
import { Chessboard } from "react-chessboard";
import type { Arrow, BoardView } from "../findings/boardView";
import { highlightStyle } from "../theme/highlight";
import { squareCentre } from "./boardGeometry";

/**
 * The board with evidence drawn on it (ADR 0007): squares as rings between halo rings (highlight.ts), and
 * arrows drawn by this component in an SVG layer over the board, so they get the same dark halo. Plain
 * react-chessboard arrows would be a flat colour, which reaches only 1.0 to 1.9:1 on the squares (#50).
 * Read-only: no dragging, no drawing.
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
  const animate = !prefersReducedMotion();
  const squareStyles: Record<string, CSSProperties> = {};
  for (const mark of view.marks) squareStyles[mark.square] = highlightStyle(mark.kind);

  return (
    <div className="board" data-testid="board" data-fen={view.fen} role="img" aria-label={label}>
      <div className="board__inner" inert>
        <Chessboard
          options={{
            id: "evidence-board",
            position: view.fen,
            boardOrientation: view.orientation,
            allowDragging: false,
            allowDrawingArrows: false,
            showNotation: true,
            // Respect "reduce motion"; without matchMedia (tests, very old browsers) do not animate either.
            showAnimations: animate,
            animationDurationInMs: 150,
            squareStyles,
            lightSquareStyle: { backgroundColor: "var(--square-light)" },
            darkSquareStyle: { backgroundColor: "var(--square-dark)" },
            lightSquareNotationStyle: { color: "var(--coordinate)" },
            darkSquareNotationStyle: { color: "var(--coordinate)" },
            boardStyle: { borderRadius: "6px", overflow: "hidden" },
          }}
        />
        <svg className="board__arrows" viewBox="0 0 8 8" aria-hidden="true">
          {view.arrows.map((arrow) => (
            <ArrowShape
              key={`${arrow.from}${arrow.to}${arrow.kind}`}
              arrow={arrow}
              orientation={view.orientation}
            />
          ))}
        </svg>
      </div>
    </div>
  );
}

const HEAD_LENGTH = 0.42;
const HEAD_WIDTH = 0.46;

function ArrowShape({ arrow, orientation }: { arrow: Arrow; orientation: "white" | "black" }) {
  const start = squareCentre(arrow.from, orientation);
  const tip = squareCentre(arrow.to, orientation);
  const dx = tip.x - start.x;
  const dy = tip.y - start.y;
  const length = Math.hypot(dx, dy) || 1;
  const ux = dx / length;
  const uy = dy / length;
  const base = { x: tip.x - ux * HEAD_LENGTH, y: tip.y - uy * HEAD_LENGTH };
  const left = { x: base.x - uy * (HEAD_WIDTH / 2), y: base.y + ux * (HEAD_WIDTH / 2) };
  const right = { x: base.x + uy * (HEAD_WIDTH / 2), y: base.y - ux * (HEAD_WIDTH / 2) };
  const head = `${p(tip)} ${p(left)} ${p(right)}`;
  const colour = `var(--${arrow.kind})`;
  return (
    <g data-arrow={`${arrow.from}${arrow.to}`} data-kind={arrow.kind}>
      {/* The halo first, wider, so the colour always sits on it (contrast as in #50). */}
      <line
        x1={start.x}
        y1={start.y}
        x2={base.x}
        y2={base.y}
        style={{ stroke: "var(--halo)", strokeWidth: 0.27, strokeLinecap: "round" }}
      />
      <polygon
        points={head}
        style={{ fill: "var(--halo)", stroke: "var(--halo)", strokeWidth: 0.11, strokeLinejoin: "round" }}
      />
      <line
        x1={start.x}
        y1={start.y}
        x2={base.x}
        y2={base.y}
        style={{ stroke: colour, strokeWidth: 0.15, strokeLinecap: "round" }}
      />
      <polygon points={head} style={{ fill: colour }} />
    </g>
  );
}

function p(point: { x: number; y: number }): string {
  return `${point.x.toFixed(3)},${point.y.toFixed(3)}`;
}

function prefersReducedMotion(): boolean {
  if (typeof window.matchMedia !== "function") return true;
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
