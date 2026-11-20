import { Chess } from "chess.js";

/**
 * Notation for display (#24). The API gives moves as UCI plus the FEN they are played from; SAN is derived
 * here with chess.js, so the analysis service stays the only owner of the facts (devlog, #51).
 */

export const START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

export interface Ply {
  uci: string;
  san: string;
  from: string;
  to: string;
  /** The position after this move. */
  fenAfter: string;
}

export function squaresOf(uci: string): { from: string; to: string; promotion?: string } {
  const from = uci.slice(0, 2);
  const to = uci.slice(2, 4);
  const promotion = uci.length > 4 ? uci.slice(4, 5) : undefined;
  return promotion ? { from, to, promotion } : { from, to };
}

/**
 * Plays a line of UCI moves from `fen` and returns each move with its SAN and the position after it.
 * Stops at the first move that is not legal there (it would mean the data and the position disagree),
 * so callers always get a playable prefix.
 */
export function playLine(fen: string, line: readonly string[]): Ply[] {
  const chess = new Chess(fen);
  const plies: Ply[] = [];
  for (const uci of line) {
    try {
      const move = chess.move(squaresOf(uci));
      plies.push({ uci, san: move.san, from: move.from, to: move.to, fenAfter: chess.fen() });
    } catch {
      break;
    }
  }
  return plies;
}

/** SAN of one UCI move in `fen`, or null if it is not legal there. */
export function sanOf(fen: string, uci: string): string | null {
  return playLine(fen, [uci])[0]?.san ?? null;
}

/** "18…Rb6" or "19. Nxb6", from the move number and the side that played. */
export function numbered(moveNumber: number, side: "WHITE" | "BLACK", san: string): string {
  return side === "WHITE" ? `${String(moveNumber)}. ${san}` : `${String(moveNumber)}… ${san}`;
}
