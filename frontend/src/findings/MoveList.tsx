import type { Loadable } from "../api/useApi";
import type { GameMoves } from "../api/types";
import type { Ply } from "../chess/notation";
import type { Cursor } from "./boardView";
import { CLASSIFICATION_LABEL } from "./labels";
import { ErrorCard, LoadingCard } from "./StatusCard";

interface Props {
  state: Loadable<GameMoves>;
  /** The game replayed from the start (chess.js), for SAN and positions. */
  plies: readonly Ply[];
  mistakePly: number;
  cursor: Cursor;
  onCursor: (cursor: Cursor) => void;
  onRetry: () => void;
}

/** The game's moves with the engine's classification; the mistake is highlighted. Click to see a position. */
export function MoveList({ state, plies, mistakePly, cursor, onCursor, onRetry }: Props) {
  if (state.status === "loading") return <LoadingCard what="the moves of this game" />;
  if (state.status === "error") return <ErrorCard what="move list" error={state.error} onRetry={onRetry} />;
  const moves = state.data.moves;
  if (moves.length === 0) {
    return (
      <section className="card">
        <p className="card__body">This game has not been analysed yet.</p>
      </section>
    );
  }
  return (
    <section className="card" aria-labelledby="moves-title">
      <h2 id="moves-title" className="card__title">
        Moves
      </h2>
      <ol className="moves">
        {moves.map((move) => {
          const flagged =
            move.byUser && (move.classification === "MISTAKE" || move.classification === "BLUNDER");
          const classes = ["move"];
          if (move.ply === mistakePly) classes.push("move--finding");
          else if (flagged) classes.push("move--flagged");
          return (
            <li key={move.ply} className={move.side === "WHITE" ? "moves__white" : "moves__black"}>
              {move.side === "WHITE" ? <span className="moves__number">{move.moveNumber}.</span> : null}
              <button
                type="button"
                className={classes.join(" ")}
                aria-current={cursor.mode === "game" && cursor.ply === move.ply ? "step" : undefined}
                aria-label={`${String(move.moveNumber)}${move.side === "WHITE" ? "." : "…"} ${plies[move.ply - 1]?.san ?? move.played.uci}, ${CLASSIFICATION_LABEL[move.classification]}${move.ply === mistakePly ? ", the finding" : ""}`}
                onClick={() => {
                  onCursor({ mode: "game", ply: move.ply });
                }}
              >
                {plies[move.ply - 1]?.san ?? move.played.san ?? move.played.uci}
                {flagged || move.ply === mistakePly ? (
                  <span className="move__badge" aria-hidden="true">
                    {move.classification === "BLUNDER" ? "??" : "?"}
                  </span>
                ) : null}
              </button>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
