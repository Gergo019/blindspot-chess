import type { Loadable } from "../api/useApi";
import type { FindingSummary, Page } from "../api/types";
import { numbered } from "../chess/notation";
import { formatDate, MOTIF_LABEL } from "./labels";
import { ErrorCard, LoadingCard } from "./StatusCard";

interface Props {
  state: Loadable<Page<FindingSummary>>;
  selectedId: number | null;
  onSelect: (id: number) => void;
  onPage: (page: number) => void;
  onRetry: () => void;
}

/** The player's findings, newest game first, paged. Every row is a button, so the list works by keyboard. */
export function FindingList({ state, selectedId, onSelect, onPage, onRetry }: Props) {
  if (state.status === "loading") return <LoadingCard what="your findings" />;
  if (state.status === "error")
    return <ErrorCard what="list of findings" error={state.error} onRetry={onRetry} />;
  const page = state.data;
  if (page.totalItems === 0) {
    return (
      <section className="card empty-state">
        <h2 className="card__title">No findings yet</h2>
        <p className="card__body">
          Once your games are imported and analysed, the mistakes you repeat appear here, each with the moves
          that prove it.
        </p>
      </section>
    );
  }
  return (
    <section className="card" aria-labelledby="findings-title">
      <h2 id="findings-title" className="card__title">
        Findings <span className="card__count">({page.totalItems})</span>
      </h2>
      <ul className="finding-list">
        {page.items.map((f) => (
          <li key={f.id}>
            <button
              type="button"
              className="finding-row"
              aria-current={f.id === selectedId ? "true" : undefined}
              onClick={() => {
                onSelect(f.id);
              }}
            >
              <span className="finding-row__main">
                {MOTIF_LABEL[f.motif]}: {f.facts.piece ?? "piece"}
                {f.facts.square ? ` on ${f.facts.square}` : ""}
              </span>
              <span className="finding-row__meta">
                {numbered(f.moveNumber, f.side, f.played.san ?? f.played.uci)} ·{" "}
                {f.side === "WHITE" ? "White" : "Black"} vs {f.game.opponentName ?? "unknown"} ·{" "}
                {formatDate(f.game.playedAt)}
              </span>
            </button>
          </li>
        ))}
      </ul>
      {page.totalPages > 1 ? (
        <nav className="pager" aria-label="Pages of findings">
          <button
            type="button"
            className="button"
            disabled={page.page === 0}
            onClick={() => {
              onPage(page.page - 1);
            }}
          >
            Newer
          </button>
          <span className="pager__label">
            Page {page.page + 1} of {page.totalPages}
          </span>
          <button
            type="button"
            className="button"
            disabled={page.page + 1 >= page.totalPages}
            onClick={() => {
              onPage(page.page + 1);
            }}
          >
            Older
          </button>
        </nav>
      ) : null}
    </section>
  );
}
