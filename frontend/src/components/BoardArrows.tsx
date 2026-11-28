import type { Arrow } from "../findings/boardView";
import { squareCentre } from "./boardGeometry";

/**
 * The arrows over a board (ADR 0007), shared by EvidenceBoard and PuzzleBoard: an SVG layer with one board
 * unit per square, each arrow drawn on a wider dark halo. Plain react-chessboard arrows would be a flat
 * colour, which reaches only 1.0 to 1.9:1 on the squares (#50). Hidden from assistive technology; the
 * caption says the same in words.
 */
export function BoardArrows({
  arrows,
  orientation,
}: {
  arrows: readonly Arrow[];
  orientation: "white" | "black";
}) {
  return (
    <svg className="board__arrows" viewBox="0 0 8 8" aria-hidden="true">
      {arrows.map((arrow) => (
        <ArrowShape key={`${arrow.from}${arrow.to}${arrow.kind}`} arrow={arrow} orientation={orientation} />
      ))}
    </svg>
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
