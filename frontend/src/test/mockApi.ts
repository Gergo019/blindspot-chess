import { vi } from "vitest";
import type { FindingDetail, FindingSummary, GameMoves, Page, PatternReport } from "../api/types";

/**
 * A mocked API for component tests (#24). The fixture is a real short game, so SAN is what chess.js
 * derives: 1. e4 e5 2. Nf3 Nc6 3. Bc4 Nd4 4. Nxe5? (the user's blunder, ply 7) Qg5, after which the queen
 * attacks the knight on e5 and the pawn on g2. Evidence line: 4… Qg5 5. Nxf7 Qxg2, so the facts say the pawn
 * on g2 is lost to the queen.
 */

export const FEN_BEFORE = "r1bqkbnr/pppp1ppp/8/4p3/2BnP3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";

export const GAME = {
  id: 7,
  lichessId: "abcd1234",
  lichessUrl: "https://lichess.org/abcd1234",
  playedAt: "2026-11-01T18:00:00Z",
  userColor: "WHITE" as const,
  result: "LOSS" as const,
  timeControl: "600+0",
  opponentName: "rival",
  opponentRating: 1500,
  userRating: 1480,
  openingEco: "C50",
  openingName: "Italian Game",
};

export const FACTS = {
  piece: "pawn",
  square: "g2",
  capturedBy: "queen",
  materialLost: 1,
  movedPiece: "knight",
  movedFrom: "f3",
  movedTo: "e5",
};

export function summary(id: number, overrides: Partial<FindingSummary> = {}): FindingSummary {
  return {
    id,
    motif: "HANGING_PIECE",
    ply: 7,
    moveNumber: 4,
    side: "WHITE",
    played: { uci: "f3e5", san: "Nxe5" },
    facts: FACTS,
    detectorVersion: "hanging-0.4",
    game: GAME,
    ...overrides,
  };
}

export function detail(id: number, overrides: Partial<FindingDetail> = {}): FindingDetail {
  return {
    id,
    motif: "HANGING_PIECE",
    ply: 7,
    moveNumber: 4,
    side: "WHITE",
    fenBefore: FEN_BEFORE,
    played: { uci: "f3e5", san: "Nxe5" },
    best: { uci: "f3d4", san: null },
    evidenceLineUci: ["d8g5", "e5f7", "g5g2"],
    facts: FACTS,
    classification: "BLUNDER",
    detectorVersion: "hanging-0.4",
    engine: { version: "Stockfish 19", depth: 16, threads: 1, hashMb: 64 },
    game: GAME,
    explanation: null,
    ...overrides,
  };
}

const GAME_UCI = ["e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "c6d4", "f3e5", "d8g5"];

export const MOVES: GameMoves = {
  gameId: GAME.id,
  analysisStatus: "DONE",
  userColor: "WHITE",
  moves: GAME_UCI.map((uci, i) => ({
    ply: i + 1,
    moveNumber: Math.floor(i / 2) + 1,
    side: i % 2 === 0 ? "WHITE" : "BLACK",
    byUser: i % 2 === 0,
    played: { uci, san: null },
    bestMoveUci: uci,
    classification: i === 6 ? "BLUNDER" : "BEST",
  })),
};

export function patterns(gamesWithFinding: number, sampleSize = 20): PatternReport {
  return {
    window: 20,
    threshold: 3,
    sampleSize,
    // Only motifs with a detector, as the API sends them (no knight-fork detector yet, #26).
    motifs: [{ motif: "HANGING_PIECE", gamesWithFinding, sampleSize, pattern: gamesWithFinding >= 3 }],
  };
}

export function page<T>(items: T[], totalItems = items.length): Page<T> {
  return { items, page: 0, size: 10, totalItems, totalPages: Math.max(1, Math.ceil(totalItems / 10)) };
}

/** A JSON body for 200, or `{ status, body }` for an error response. */
type Handler = unknown;

/** Answers fetch by path (query string ignored for matching unless the key contains "?"). */
export function mockApi(routes: Record<string, Handler>) {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url =
      typeof input === "string" ? input : input instanceof URL ? input.pathname + input.search : input.url;
    const key = url in routes ? url : (url.split("?")[0] ?? url);
    const handler = routes[key];
    if (handler === undefined) return Promise.resolve(new Response(null, { status: 404 }));
    if (typeof handler === "object" && handler !== null && "status" in handler) {
      const { status, body } = handler as { status: number; body?: unknown };
      return Promise.resolve(
        new Response(body === undefined ? null : JSON.stringify(body), {
          status,
          headers: { "Content-Type": "application/problem+json" },
        }),
      );
    }
    return Promise.resolve(
      new Response(JSON.stringify(handler), { status: 200, headers: { "Content-Type": "application/json" } }),
    );
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/** The happy path: one finding, its detail and its game. */
export function mockHappyApi(overrides: Record<string, Handler> = {}) {
  return mockApi({
    "/api/patterns": patterns(2),
    "/api/findings": page([summary(1)]),
    "/api/findings/1": detail(1),
    "/api/games/7/moves": MOVES,
    ...overrides,
  });
}
