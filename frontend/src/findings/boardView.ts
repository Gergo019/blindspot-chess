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
  return lineLabelAt(detail.ply, line, index);
}

/** "4. Nxe5" for the move at `index` of a line whose first move is ply `firstPly` of its game. */
export function lineLabelAt(firstPly: number, line: readonly Ply[], index: number): string {
  const { moveNumber, side } = plyLabel(firstPly + index);
  return numbered(moveNumber, side, line[index]?.san ?? "?");
}

/** The ply of the next move in a position, from its FEN's side to move and move number. */
export function plyOf(fen: string): number {
  const [, toMove, , , , fullmove] = fen.split(" ");
  return (Number(fullmove) - 1) * 2 + (toMove === "b" ? 2 : 1);
}

/** One step with the arrow keys, Home or End, kept within 0..last (the findings screen and PuzzleBoard). */
export function nextStep(current: number, key: string, last: number): number {
  const next = key === "Home" ? 0 : key === "End" ? last : current + (key === "ArrowRight" ? 1 : -1);
  return Math.max(0, Math.min(last, next));
}

/**
 * The line view (cursor mode "line") from what it needs, so a puzzle's review can show it without a
 * finding: step 0 is the position before the move with that move as the orange arrow (and `marks`); step 1
 * is after it; steps 2.. follow the evidence line in blue.
 */
export function lineView(
  start: { fen: string; orientation: "white" | "black"; firstPly: number; marks: SquareMark[] },
  line: readonly Ply[],
  cursorStep: number,
): BoardView {
  const { fen, orientation, firstPly } = start;
  const step = Math.max(0, Math.min(cursorStep, line.length));
  if (step === 0) {
    const played = line[0];
    return {
      fen,
      orientation,
      arrows: played ? [{ from: played.from, to: played.to, kind: "mistake" }] : [],
      marks: dedupe(start.marks),
      caption: `Position before your move ${lineLabelAt(firstPly, line, 0)}`,
    };
  }
  const ply = line[step - 1];
  if (!ply) return { fen, orientation, arrows: [], marks: [], caption: "" };
  const kind: MarkKind = step === 1 ? "mistake" : "evidence";
  return {
    fen: ply.fenAfter,
    orientation,
    arrows: [{ from: ply.from, to: ply.to, kind }],
    marks: [{ square: ply.to, kind }],
    caption:
      step === 1
        ? `After your move ${lineLabelAt(firstPly, line, 0)}`
        : `Evidence move ${String(step - 1)} of ${String(line.length - 1)}: ${lineLabelAt(firstPly, line, step - 1)}`,
  };
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
  const factMarks = renderFacts(detail.facts, { line }).flatMap((fact) => fact.marks);
  return lineView(
    { fen: detail.fenBefore, orientation, firstPly: detail.ply, marks: factMarks },
    line,
    cursor.step,
  );
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
