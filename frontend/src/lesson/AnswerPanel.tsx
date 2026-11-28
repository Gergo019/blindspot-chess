import { useEffect, useRef } from "react";
import type { LessonAnswer } from "../api/types";
import { playLine, sanOf } from "../chess/notation";
import type { PuzzleMove } from "../components/PuzzleBoard";
import { lineLabelAt, plyOf } from "../findings/boardView";

/**
 * What an answer reveals (ADR 0007, amendment of 2026-11-21, "Lesson"): "Correct" or "Not this one" as text
 * with an icon (no third colour), the move's verdict and its line, the engine's move, the accepted moves,
 * and the move from the game. The board below shows the game's move and the evidence line (PuzzleBoard's
 * review). Takes the focus when it appears, so the result is read next.
 */
export function AnswerPanel({
  move,
  answer,
  onNext,
}: {
  move: PuzzleMove;
  answer: LessonAnswer;
  onNext: () => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    heading.current?.focus();
  }, []);

  const san = (uci: string) => sanOf(move.fen, uci) ?? uci;
  const fenAfter = playLine(move.fen, [move.uci])[0]?.fenAfter;
  const line = fenAfter ? playLine(fenAfter, answer.verdict.refutationUci.split(" ").filter(Boolean)) : [];
  const lineText = fenAfter ? line.map((_, i) => lineLabelAt(plyOf(fenAfter), line, i)).join(" ") : "";
  const percent = Math.round(answer.verdict.drop * 100);
  const accepted = answer.acceptedMoves.map(san);
  const gameMove = answer.gameMove.san ?? san(answer.gameMove.uci);

  return (
    <section className="card answer" aria-labelledby="answer-title">
      <h3 id="answer-title" className="card__title" tabIndex={-1} ref={heading}>
        <span aria-hidden="true">{answer.correct ? "✓ " : "✗ "}</span>
        {answer.correct ? "Correct" : "Not this one"}
      </h3>
      <p className="card__body">
        Your move {answer.verdict.san}{" "}
        {percent === 0
          ? "keeps your winning chances."
          : `costs about ${String(percent)}% of your winning chances.`}
      </p>
      {lineText ? (
        <p className="card__body">
          {answer.correct ? "The engine continues: " : "It is refuted by: "}
          {lineText}
        </p>
      ) : null}
      <p className="card__body">
        The engine&apos;s move: {san(answer.bestMove)}.
        {accepted.length > 1 ? ` Accepted moves: ${accepted.join(", ")}.` : null}
      </p>
      <p className="card__body">In your game you played {gameMove}.</p>
      {answer.firstTry ? null : (
        <p className="card__note">
          This was not your first try at this position, so it does not count as solved first try.
        </p>
      )}
      <div className="actions">
        <button type="button" className="button" onClick={onNext}>
          Next
        </button>
      </div>
    </section>
  );
}
