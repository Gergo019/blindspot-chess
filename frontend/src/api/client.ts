import type {
  AnswerRequest,
  FindingDetail,
  FindingSummary,
  GameMoves,
  Journey,
  Lesson,
  LessonAnswer,
  Motif,
  Page,
  PatternReport,
} from "./types";

/**
 * The one place that talks to the backend (#24). Same-origin `/api` (nginx proxies it; no CORS). Reads are
 * GETs; the journey's two writes are JSON POSTs (#90). Errors carry the HTTP status and nothing from the
 * response body, so no server detail can reach the UI.
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

function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  return send<T>(path, { headers: { Accept: "application/json" } }, signal);
}

/**
 * A JSON POST. The Content-Type is sent also without a body (starting a lesson): the API accepts only
 * application/json on its POSTs, so a cross-site form cannot reach them, and answers anything else with 415.
 */
function post<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  return send<T>(
    path,
    {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    },
    signal,
  );
}

function lessonPath(unit: string, number: number): string {
  return `/api/lessons/${encodeURIComponent(unit)}/${String(number)}`;
}

async function send<T>(path: string, init: RequestInit, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { ...init, ...(signal ? { signal } : {}) });
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

  journey: (signal?: AbortSignal) => get<Journey>("/api/journey", signal),

  /** A lesson as it is; a lesson that is not started has no items. Never assigns it. */
  lesson: (unit: string, number: number, signal?: AbortSignal) =>
    get<Lesson>(lessonPath(unit, number), signal),

  /** Starts the lesson or a retry of its check (idempotent); with too few positions nothing changes. */
  startLesson: (unit: string, number: number, signal?: AbortSignal) =>
    post<Lesson>(lessonPath(unit, number), undefined, signal),

  answer: (unit: string, number: number, position: number, moveUci: string, signal?: AbortSignal) =>
    post<LessonAnswer>(
      `${lessonPath(unit, number)}/items/${String(position)}/answer`,
      { moveUci } satisfies AnswerRequest,
      signal,
    ),
};
