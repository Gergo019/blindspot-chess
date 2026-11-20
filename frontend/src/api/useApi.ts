import { useCallback, useEffect, useState } from "react";
import { ApiError } from "./client";

export type Loadable<T> =
  { status: "loading" } | { status: "ok"; data: T } | { status: "error"; error: ApiError };

interface Settled<T> {
  key: string;
  attempt: number;
  value: Loadable<T>;
}

/**
 * Runs `load` whenever `key` changes (or `retry` is called), cancelling the previous request. The result is
 * tagged with the key and attempt it belongs to, so a new key reads as "loading" without resetting state
 * inside the effect. A null key means "nothing to load" and stays loading.
 */
export function useApi<T>(
  key: string | null,
  load: (signal: AbortSignal) => Promise<T>,
): { state: Loadable<T>; retry: () => void } {
  const [settled, setSettled] = useState<Settled<T> | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (key === null) return;
    const controller = new AbortController();
    load(controller.signal).then(
      (data) => {
        setSettled({ key, attempt, value: { status: "ok", data } });
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        const apiError = error instanceof ApiError ? error : new ApiError(0);
        setSettled({ key, attempt, value: { status: "error", error: apiError } });
      },
    );
    return () => {
      controller.abort();
    };
    // `load` is recreated on every render; `key` and `attempt` name what it loads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, attempt]);

  const retry = useCallback(() => {
    setAttempt((n) => n + 1);
  }, []);

  const state: Loadable<T> =
    settled !== null && settled.key === key && settled.attempt === attempt
      ? settled.value
      : { status: "loading" };
  return { state, retry };
}
