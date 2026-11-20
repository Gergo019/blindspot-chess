import type { Loadable } from "../api/useApi";
import type { PatternReport } from "../api/types";
import { MOTIF_LABEL } from "./labels";
import { ErrorCard, LoadingCard } from "./StatusCard";

/**
 * "Your blind spot" (ADR 0007): per motif, in how many of the last games it happened. The sample size is
 * always shown; below the threshold the card says so plainly instead of hiding the motif (README, step 4).
 */
export function BlindSpotCard({ state, onRetry }: { state: Loadable<PatternReport>; onRetry: () => void }) {
  if (state.status === "loading") return <LoadingCard what="your blind-spot report" />;
  if (state.status === "error")
    return <ErrorCard what="blind-spot report" error={state.error} onRetry={onRetry} />;
  const report = state.data;
  const motifs = [...report.motifs].sort((a, b) => b.gamesWithFinding - a.gamesWithFinding);

  return (
    <section className="card" aria-labelledby="blind-spot-title">
      <h2 id="blind-spot-title" className="card__title">
        Your blind spot
      </h2>
      {report.sampleSize === 0 ? (
        <p className="card__body">No analysed rapid games yet, so there is nothing to report.</p>
      ) : (
        <ul className="patterns">
          {motifs.map((m) => (
            <li key={m.motif} className="pattern" data-motif={m.motif}>
              <span className="pattern__motif">{MOTIF_LABEL[m.motif]}</span>
              <span className="pattern__count">
                in {m.gamesWithFinding} of your last {m.sampleSize} rapid games
              </span>
              <span className={m.pattern ? "pattern__verdict pattern__verdict--yes" : "pattern__verdict"}>
                {m.pattern
                  ? `A pattern: ${String(report.threshold)} or more games.`
                  : `Below the pattern threshold of ${String(report.threshold)} games, so not a pattern yet.`}
              </span>
            </li>
          ))}
        </ul>
      )}
      {report.sampleSize > 0 && report.sampleSize < report.window ? (
        <p className="card__note">
          Only {report.sampleSize} analysed rapid games so far; the report looks at the last {report.window}.
        </p>
      ) : null}
    </section>
  );
}
