import { Chess, type Move } from "chess.js";
import type { Side } from "../api/types";

/**
 * Turning a player's input into a move of a puzzle position (PuzzleBoard, ADR 0007 amendment of 2026-11-21).
 * chess.js only says whether a move is legal, so an illegal one is refused at once; whether a legal move is
 * correct is the server's decision (ADR 0008), so nothing here knows any answer.
 */
export type MoveAttempt =
  | { kind: "legal"; uci: string; san: string; fenAfter: string }
  /** A pawn reaching the last rank: the piece to promote to is still to be chosen. */
  | { kind: "promotion"; from: string; to: string }
  | { kind: "illegal" };

export type PromotionPiece = "q" | "r" | "b" | "n";

const UCI = /^([a-h][1-8])([a-h][1-8])([qrbn])?$/;

function legalMoves(fen: string): Move[] {
  return new Chess(fen).moves({ verbose: true });
}

function legal(move: Move): MoveAttempt {
  return { kind: "legal", uci: move.lan, san: move.san, fenAfter: move.after };
}

/** A move from one square to another, as dragged or clicked; `promotion` once the piece is chosen. */
export function resolveSquares(
  fen: string,
  from: string,
  to: string,
  promotion?: PromotionPiece,
): MoveAttempt {
  const candidates = legalMoves(fen).filter((m) => m.from === from && m.to === to);
  if (candidates.length === 0) return { kind: "illegal" };
  const isPromotion = candidates.some((m) => m.promotion !== undefined);
  if (!isPromotion) return legal(candidates[0] as Move);
  if (promotion === undefined) return { kind: "promotion", from, to };
  const chosen = candidates.find((m) => m.promotion === promotion);
  return chosen ? legal(chosen) : { kind: "illegal" };
}

/**
 * A move typed in SAN ("Nxd4", "e8=Q", "O-O") or UCI ("f3d4", "e7e8q"). Check and annotation marks are
 * optional, castling may be written with zeros, and UCI is case-insensitive. A promotion written without its
 * piece ("e8", "e7e8") asks for the piece.
 */
export function resolveText(fen: string, text: string): MoveAttempt {
  const input = text
    .trim()
    .replace(/[+#!?]+$/, "")
    .replace(/^0-0-0$/, "O-O-O")
    .replace(/^0-0$/, "O-O");
  if (input === "") return { kind: "illegal" };
  const uci = UCI.exec(input.toLowerCase());
  if (uci) {
    const [, from = "", to = "", piece] = uci;
    return resolveSquares(fen, from, to, piece as PromotionPiece | undefined);
  }
  const moves = legalMoves(fen);
  const exact = moves.find((m) => bare(m.san) === input);
  if (exact) return legal(exact);
  const promotion = moves.find(
    (m) => m.promotion !== undefined && bare(m.san).replace(/=[QRBN]$/, "") === input,
  );
  return promotion ? { kind: "promotion", from: promotion.from, to: promotion.to } : { kind: "illegal" };
}

function bare(san: string): string {
  return san.replace(/[+#]$/, "");
}

/** Whose move it is in a position. */
export function sideToMove(fen: string): Side {
  return fen.split(" ")[1] === "b" ? "BLACK" : "WHITE";
}
