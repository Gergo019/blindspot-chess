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

/*
 * The journey API (#90, ADR 0008), mirroring backend/src/main/java/.../training/api/JourneyResponses.java
 * and LessonService.State, LessonItem.Role and Drill.Task.
 */

/** Derived by the backend, never stored (ADR 0008, "Soft locking"). */
export type LessonState = "NEEDS_POSITIONS" | "AVAILABLE" | "IN_PROGRESS" | "MASTERED";
export type LessonRole = "PRACTICE" | "CHECK";
export type PuzzleTask = "FIND_SAFE_MOVE" | "SEE_THE_CAPTURE";

/** `GET /api/journey`: the unit and its lessons in the recommended order. */
export interface Journey {
  unit: JourneyUnit;
  lessons: LessonSummary[];
}

/** A unit with its motif's line of the blind-spot report (null if the report does not list the motif). */
export interface JourneyUnit {
  key: string;
  title: string;
  motif: Motif;
  report: MotifPattern | null;
}

/**
 * A lesson on the map. `unit` and `number` make its URL. `positionsNeeded` is what a lesson that cannot be
 * assigned yet still needs, or what a retry of a failed or void check still needs; 0 otherwise.
 */
export interface LessonSummary {
  key: string;
  unit: string;
  number: number;
  title: string;
  state: LessonState;
  recommended: boolean;
  solvedFirstTry: number;
  size: number;
  positionsNeeded: number;
  retryAvailable: boolean;
}

/** `GET` and `POST /api/lessons/{unit}/{number}`: items in position order; positions can have gaps. */
export interface Lesson {
  lesson: LessonSummary;
  explanation: string;
  masteredAt: string | null;
  items: LessonItem[];
}

/** One puzzle. `solution` is null until the item is answered. */
export interface LessonItem {
  position: number;
  role: LessonRole;
  currentCheck: boolean;
  task: PuzzleTask;
  prompt: string;
  fen: string;
  orientation: Side;
  answered: boolean;
  solvedFirstTry: boolean;
  solution: LessonSolution | null;
}

/** What an answered puzzle reveals. */
export interface LessonSolution {
  game: GameSummary;
  gameMove: Move;
  evidenceLineUci: string[];
  bestMove: string;
  acceptedMoves: string[];
}

/** The body of `POST /api/lessons/{unit}/{number}/items/{position}/answer`. */
export interface AnswerRequest {
  moveUci: string;
}

/** The stored verdict of the answered move: its loss in winning chances and the line that refutes it. */
export interface MoveVerdict {
  moveUci: string;
  san: string;
  drop: number;
  refutationUci: string;
}

/** The result of an answer, and the lesson afterwards. */
export interface LessonAnswer {
  position: number;
  correct: boolean;
  firstTry: boolean;
  verdict: MoveVerdict;
  bestMove: string;
  acceptedMoves: string[];
  gameMove: Move;
  evidenceLineUci: string[];
  lesson: LessonSummary;
}
