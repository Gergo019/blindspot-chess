/**
 * Response types of the read API (#51), mirroring the backend's records in
 * backend/src/main/java/.../analysis/api/FindingResponses.java. Moves are UCI plus the FEN they are
 * played from; SAN is present only where the analysis service stored it (the played move), the rest is
 * derived here with chess.js for display.
 */

export type Motif = "HANGING_PIECE" | "ALLOWED_KNIGHT_FORK";
export type Side = "WHITE" | "BLACK";
export type Classification = "BEST" | "GOOD" | "INACCURACY" | "MISTAKE" | "BLUNDER";

export interface Move {
  uci: string;
  san: string | null;
}

/** The checked facts of a finding (HANGING_PIECE); a detector leaves out what it does not report. */
export interface Facts {
  piece: string | null;
  square: string | null;
  capturedBy: string | null;
  materialLost: number | null;
  movedPiece: string | null;
  movedFrom: string | null;
  movedTo: string | null;
}

export interface GameSummary {
  id: number;
  lichessId: string;
  lichessUrl: string;
  playedAt: string;
  userColor: Side;
  result: "WIN" | "LOSS" | "DRAW";
  timeControl: string | null;
  opponentName: string | null;
  opponentRating: number | null;
  userRating: number | null;
  openingEco: string | null;
  openingName: string | null;
}

export interface FindingSummary {
  id: number;
  motif: Motif;
  ply: number;
  moveNumber: number;
  side: Side;
  played: Move;
  facts: Facts;
  detectorVersion: string;
  game: GameSummary;
}

export interface Engine {
  version: string;
  depth: number;
  threads: number | null;
  hashMb: number | null;
}

export interface FindingDetail {
  id: number;
  motif: Motif;
  ply: number;
  moveNumber: number;
  side: Side;
  fenBefore: string;
  played: Move;
  best: Move;
  evidenceLineUci: string[];
  facts: Facts;
  classification: Classification;
  detectorVersion: string;
  engine: Engine;
  game: GameSummary;
  /** Only a TEMPLATE explanation that passed verification; already filtered by the API. */
  explanation: { content: string } | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface MotifPattern {
  motif: Motif;
  gamesWithFinding: number;
  sampleSize: number;
  pattern: boolean;
}

export interface PatternReport {
  window: number;
  threshold: number;
  sampleSize: number;
  motifs: MotifPattern[];
}

export interface GameMove {
  ply: number;
  moveNumber: number;
  side: Side;
  byUser: boolean;
  played: Move;
  bestMoveUci: string;
  classification: Classification;
}

export interface GameMoves {
  gameId: number;
  analysisStatus: "PENDING" | "RUNNING" | "DONE" | "FAILED";
  userColor: Side;
  moves: GameMove[];
}
