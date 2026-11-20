import type { FindingDetail, GameMove, Side } from "../api/types";
import { numbered, type Ply, sanOf, START_FEN } from "../chess/notation";
import { renderFacts, type MarkKind, type SquareMark } from "./factRenderers";

/**
 * What the board shows for a finding (#24, ADR 0007). Three ways to look at it:
 * - the line: step 0 is the position before the mistake with your move as the orange arrow; step 1 is after
 *   your move; steps 2.. follow the evidence line, each move as a blue arrow onto a blue square;
 * - the engine's best move, as a blue arrow from the position before the mistake;
 * - any position of the game, chosen in the move list.
 */
export type Cursor = { mode: "line"; step: number } | { mode: "best" } | { mode: "game"; ply: number };

export interface Arrow {
  from: string;
  to: string;
  kind: MarkKind;
}

export interface BoardView {
  fen: string;
  orientation: "white" | "black";
  arrows: Arrow[];
  marks: SquareMark[];
  /** What the board shows, in words, for screen readers and the caption. */
  caption: string;
}

export function orientationOf(side: Side): "white" | "black" {
  return side === "WHITE" ? "white" : "black";
}

/** Side to move and move number of a ply (ply 1 is White's first move). */
function plyLabel(ply: number): { moveNumber: number; side: Side } {
  return { moveNumber: Math.ceil(ply / 2), side: ply % 2 === 1 ? "WHITE" : "BLACK" };
}

export function lineLabel(detail: FindingDetail, line: readonly Ply[], index: number): string {
  const { moveNumber, side } = plyLabel(detail.ply + index);
  return numbered(moveNumber, side, line[index]?.san ?? "?");
}

export function buildView(
  detail: FindingDetail,
  line: readonly Ply[],
  gamePlies: readonly Ply[],
  gameMoves: readonly GameMove[],
  cursor: Cursor,
): BoardView {
  const orientation = orientationOf(detail.game.userColor);
  if (cursor.mode === "best") {
    const best = detail.best.uci;
    const san = sanOf(detail.fenBefore, best) ?? best;
    const { moveNumber, side } = plyLabel(detail.ply);
    return {
      fen: detail.fenBefore,
      orientation,
      arrows: [{ from: best.slice(0, 2), to: best.slice(2, 4), kind: "evidence" }],
      marks: [{ square: best.slice(2, 4), kind: "evidence" }],
      caption: `The engine's best move instead: ${numbered(moveNumber, side, san)}`,
    };
  }
  if (cursor.mode === "game") {
    const ply = gamePlies[cursor.ply - 1];
    const move = gameMoves.find((m) => m.ply === cursor.ply);
    const isMistake = cursor.ply === detail.ply;
    return {
      fen: ply?.fenAfter ?? START_FEN,
      orientation,
      arrows: ply && isMistake ? [{ from: ply.from, to: ply.to, kind: "mistake" }] : [],
      marks: ply && isMistake ? [{ square: ply.to, kind: "mistake" }] : [],
      caption:
        move && ply ? `Position after ${numbered(move.moveNumber, move.side, ply.san)}` : "Starting position",
    };
  }
  const step = Math.max(0, Math.min(cursor.step, line.length));
  if (step === 0) {
    const played = line[0];
    const factMarks = renderFacts(detail.facts, { line }).flatMap((fact) => fact.marks);
    return {
      fen: detail.fenBefore,
      orientation,
      arrows: played ? [{ from: played.from, to: played.to, kind: "mistake" }] : [],
      marks: dedupe(factMarks),
      caption: `Position before your move ${lineLabel(detail, line, 0)}`,
    };
  }
  const ply = line[step - 1];
  if (!ply) return { fen: detail.fenBefore, orientation, arrows: [], marks: [], caption: "" };
  const kind: MarkKind = step === 1 ? "mistake" : "evidence";
  return {
    fen: ply.fenAfter,
    orientation,
    arrows: [{ from: ply.from, to: ply.to, kind }],
    marks: [{ square: ply.to, kind }],
    caption:
      step === 1
        ? `After your move ${lineLabel(detail, line, 0)}`
        : `Evidence move ${String(step - 1)} of ${String(line.length - 1)}: ${lineLabel(detail, line, step - 1)}`,
  };
}

/** One mark per square; the user's move wins over evidence where both apply. */
function dedupe(marks: SquareMark[]): SquareMark[] {
  const bySquare = new Map<string, SquareMark>();
  for (const mark of marks) {
    const existing = bySquare.get(mark.square);
    if (!existing || (existing.kind === "evidence" && mark.kind === "mistake"))
      bySquare.set(mark.square, mark);
  }
  return [...bySquare.values()];
}
