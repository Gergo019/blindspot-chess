import type { FindingDetail, FindingSummary, GameMoves, Motif, Page, PatternReport } from "./types";

/**
 * The one place that talks to the backend (#24). Same-origin `/api` (nginx proxies it; no CORS), GET only.
 * Errors carry the HTTP status and nothing from the response body, so no server detail can reach the UI.
 */
export class ApiError extends Error {
  constructor(readonly status: number) {
    super(`API request failed with status ${String(status)}`);
    this.name = "ApiError";
  }

  get notFound(): boolean {
    return this.status === 404;
  }
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { headers: { Accept: "application/json" }, ...(signal ? { signal } : {}) });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError(0); // network error: no status
  }
  if (!response.ok) throw new ApiError(response.status);
  return (await response.json()) as T;
}

export const api = {
  patterns: (signal?: AbortSignal) => get<PatternReport>("/api/patterns", signal),

  findings: (params: { page?: number; size?: number; motif?: Motif }, signal?: AbortSignal) => {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set("page", String(params.page));
    if (params.size !== undefined) query.set("size", String(params.size));
    if (params.motif) query.set("motif", params.motif);
    const suffix = query.size > 0 ? `?${query.toString()}` : "";
    return get<Page<FindingSummary>>(`/api/findings${suffix}`, signal);
  },

  finding: (id: number, signal?: AbortSignal) => get<FindingDetail>(`/api/findings/${String(id)}`, signal),

  gameMoves: (gameId: number, signal?: AbortSignal) =>
    get<GameMoves>(`/api/games/${String(gameId)}/moves`, signal),
};
