import type { FindingDetail } from "../api/types";
import type { Ply } from "../chess/notation";
import type { Cursor } from "./boardView";
import { lineLabel } from "./boardView";
import { renderFacts } from "./factRenderers";
import { formatDate, MOTIF_LABEL } from "./labels";

interface Props {
  detail: FindingDetail;
  /** Your move followed by the evidence line, with SAN derived from the FEN (chess.js). */
  line: readonly Ply[];
  cursor: Cursor;
  onCursor: (cursor: Cursor) => void;
}

/**
 * The selected finding (ADR 0007, side panel item 3): its facts, the line as clickable chips, "Show the
 * best move", and the explanation only when the API returns one (it returns only verified TEMPLATE texts).
 * "Find a better move" is not here: judging an alternative needs the engine, so it belongs to drills.
 */
export function FindingPanel({ detail, line, cursor, onCursor }: Props) {
  const facts = renderFacts(detail.facts, { line });
  const step = cursor.mode === "line" ? cursor.step : -1;
  const truncated = line.length < detail.evidenceLineUci.length + 1;

  return (
    <section className="card" aria-labelledby="finding-title">
      <h2 id="finding-title" className="card__title">
        {MOTIF_LABEL[detail.motif]} · {line[0] ? lineLabel(detail, line, 0) : detail.played.uci}
      </h2>
      <p className="card__body">
        {detail.game.userColor === "WHITE" ? "White" : "Black"} vs {detail.game.opponentName ?? "unknown"}
        {detail.game.opponentRating !== null ? ` (${String(detail.game.opponentRating)})` : ""},{" "}
        {formatDate(detail.game.playedAt)} ·{" "}
        <a href={detail.game.lichessUrl} target="_blank" rel="noreferrer noopener">
          Game on Lichess
        </a>
      </p>

      {facts.length > 0 ? (
        <dl className="facts">
          {facts.map((fact) => (
            <div key={fact.key} className="facts__row">
              <dt>{fact.label}</dt>
              <dd>
                {fact.value}
                {fact.hint ? <span className="facts__hint">{fact.hint}</span> : null}
              </dd>
            </div>
          ))}
        </dl>
      ) : null}

      <h3 className="card__subtitle" id="line-title">
        Your move and what follows
      </h3>
      <ol className="chips" aria-labelledby="line-title">
        {line.map((ply, index) => (
          <li key={`${String(index)}-${ply.uci}`}>
            <button
              type="button"
              className={index === 0 ? "chip chip--mistake" : "chip chip--evidence"}
              aria-current={step === index + 1 ? "step" : undefined}
              onClick={() => {
                onCursor({ mode: "line", step: index + 1 });
              }}
            >
              <span
                className={index === 0 ? "swatch swatch--mistake" : "swatch swatch--evidence"}
                aria-hidden="true"
              />
              {lineLabel(detail, line, index)}
            </button>
          </li>
        ))}
      </ol>
      {truncated ? <p className="card__note">The rest of the evidence line could not be replayed.</p> : null}

      <div className="actions">
        <button
          type="button"
          className="button"
          onClick={() => {
            onCursor({ mode: "line", step: 0 });
          }}
          aria-pressed={step === 0}
        >
          Position before the move
        </button>
        <button
          type="button"
          className="button"
          aria-pressed={cursor.mode === "best"}
          onClick={() => {
            onCursor(cursor.mode === "best" ? { mode: "line", step: 0 } : { mode: "best" });
          }}
        >
          Show the best move
        </button>
      </div>

      {detail.explanation ? (
        <div className="explanation">
          <h3 className="card__subtitle">Why</h3>
          <p className="card__body">{detail.explanation.content}</p>
        </div>
      ) : null}
    </section>
  );
}
