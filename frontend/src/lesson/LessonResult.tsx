import { useEffect, useRef } from "react";
import { api } from "../api/client";
import type { Lesson } from "../api/types";
import { useApi } from "../api/useApi";
import { formatDate } from "../findings/labels";
import { lessonRoutePath } from "../router";

/**
 * The lesson's result (ADR 0007, amendment of 2026-11-21, "Result"; ADR 0008), once every item is answered:
 * per position whether it was solved first try, with the game it comes from; whether the lesson is
 * mastered; then the recommended next lesson, or "Retry the mastery check" only when a retry is available,
 * otherwise how many more positions a retry needs. It says plainly that these are the player's own
 * positions, so this is practice and not a transfer test.
 *
 * The link to each position's finding (`/findings/:id`) waits for the finding's id in the API (#100).
 */
export function LessonResult({
  lesson,
  onRetry,
  retrying,
  navigate,
}: {
  lesson: Lesson;
  onRetry: () => void;
  retrying: boolean;
  navigate: (path: string) => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    heading.current?.focus();
  }, []);
  const summary = lesson.lesson;
  const mastered = summary.state === "MASTERED";
  const journey = useApi("journey", (signal) => api.journey(signal));
  const next =
    journey.state.status === "ok"
      ? journey.state.data.lessons.find((l) => l.recommended && l.key !== summary.key)
      : undefined;

  return (
    <section className="card" aria-labelledby="result-title">
      <h2 id="result-title" className="card__title" tabIndex={-1} ref={heading}>
        Result: {summary.title}
      </h2>
      <p className="card__body">
        {mastered
          ? "Mastered: both puzzles of a mastery check were solved on the first try."
          : "Not mastered yet: a mastery check needs both of its puzzles solved on the first try."}
      </p>

      {lesson.items.length === 0 ? (
        <p className="card__body">No position of this lesson is left after a new analysis of your games.</p>
      ) : (
        <ol className="result">
          {lesson.items.map((item, index) => (
            <li key={item.position} className="result__item">
              <span className="result__label">
                Position {index + 1}
                {item.role === "CHECK" ? " (mastery check)" : ""}:{" "}
                {!item.answered
                  ? "not answered"
                  : item.solvedFirstTry
                    ? "solved first try"
                    : "not solved first try"}
              </span>
              {item.solution ? (
                <span className="result__game">
                  From your game against {item.solution.game.opponentName ?? "an unknown opponent"},{" "}
                  {formatDate(item.solution.game.playedAt)} ·{" "}
                  <a href={item.solution.game.lichessUrl} target="_blank" rel="noreferrer noopener">
                    Game on Lichess
                  </a>
                </span>
              ) : null}
            </li>
          ))}
        </ol>
      )}

      <p className="card__note">
        These positions come from your own games, and you may have seen them on the findings screen. So this
        is practice on your own mistakes, not a test of whether the lesson carries over to positions you have
        never seen.
      </p>

      <div className="actions">
        {!mastered && summary.retryAvailable ? (
          <button type="button" className="button" onClick={onRetry} disabled={retrying}>
            Retry the mastery check
          </button>
        ) : null}
        {next ? (
          <button
            type="button"
            className="button"
            onClick={() => {
              navigate(lessonRoutePath(next.unit, next.number));
            }}
          >
            Next lesson: {next.title}
          </button>
        ) : null}
      </div>
      {!mastered && !summary.retryAvailable && summary.positionsNeeded > 0 ? (
        <p className="card__body">
          A retry of the mastery check needs {summary.positionsNeeded} more{" "}
          {summary.positionsNeeded === 1 ? "position" : "positions"}. They come from your next analysed games.
        </p>
      ) : null}
      <p className="card__body">
        <a
          href="/journey"
          onClick={(event) => {
            event.preventDefault();
            navigate("/journey");
          }}
        >
          Back to the journey
        </a>
      </p>
    </section>
  );
}
