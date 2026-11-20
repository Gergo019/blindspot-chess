import type { ApiError } from "../api/client";

/** Loading and error states. The error never shows server details, only what the player can do. */
export function LoadingCard({ what }: { what: string }) {
  return (
    <section className="card" aria-busy="true">
      <p className="card__body">Loading {what}…</p>
    </section>
  );
}

export function ErrorCard({ what, error, onRetry }: { what: string; error: ApiError; onRetry?: () => void }) {
  const message = error.notFound
    ? `This ${what} does not exist, or it is not one of yours.`
    : `The ${what} could not be loaded. Check that the backend is running, then try again.`;
  return (
    <section className="card card--error" role="alert">
      <h2 className="card__title">Something went wrong</h2>
      <p className="card__body">{message}</p>
      {onRetry && !error.notFound ? (
        <button type="button" className="button" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </section>
  );
}
