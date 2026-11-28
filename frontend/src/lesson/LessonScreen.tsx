import { type ReactNode, useEffect, useRef, useState } from "react";
import { api, ApiError } from "../api/client";
import type { LessonAnswer, LessonItem } from "../api/types";
import { useApi } from "../api/useApi";
import { sideToMove } from "../chess/moveInput";
import { START_FEN } from "../chess/notation";
import { EvidenceBoard } from "../components/EvidenceBoard";
import { PuzzleBoard, type PuzzleMove } from "../components/PuzzleBoard";
import { ErrorCard, LoadingCard } from "../findings/StatusCard";
import { AnswerPanel } from "./AnswerPanel";
import { LessonResult } from "./LessonResult";
import { canShow, nextItem, placeOf } from "./progress";

interface Props {
  unit: string;
  number: number;
  navigate: (path: string) => void;
}

/** One move of the current puzzle, from choosing it to the server's answer. */
type Attempt =
  | { phase: "choosing" }
  /** A check puzzle has one try: the move waits for "Submit move" (decided by Gergo for #96). */
  | { phase: "confirming"; move: PuzzleMove }
  | { phase: "sending"; move: PuzzleMove }
  | { phase: "answered"; move: PuzzleMove; answer: LessonAnswer }
  /** The request failed for another reason than the rules: the move stays, to send again or to change. */
  | { phase: "failed"; move: PuzzleMove };

/**
 * A lesson (#96, ADR 0007 amendment of 2026-11-21, "Lesson" and "Result"). Opening it only reads it: a lesson
 * that was never started shows its explanation and "Start lesson", and only that button and "Retry the
 * mastery check" call the start POST (decided by Gergo). Then the puzzles one by one with PuzzleBoard, the
 * answer and its evidence, and the result once every item is answered.
 */
export function LessonScreen({ unit, number, navigate }: Props) {
  const loaded = useApi(`lesson:${unit}/${String(number)}`, (signal) => api.lesson(unit, number, signal));
  /** Positions answered (or skipped) in this visit, before the lesson is loaded again. */
  const [done, setDone] = useState<ReadonlySet<number>>(new Set());
  /** The position on screen while its move is being answered, so a reload never moves away from it. */
  const [pinned, setPinned] = useState<number | null>(null);
  const [attempt, setAttempt] = useState<Attempt>({ phase: "choosing" });
  const [boardKey, setBoardKey] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [starting, setStarting] = useState<{ busy: boolean; failed: boolean }>({
    busy: false,
    failed: false,
  });

  if (loaded.state.status === "loading") return <Layout panel={<LoadingCard what="the lesson" />} />;
  if (loaded.state.status === "error")
    return (
      <Layout
        panel={
          <>
            <ErrorCard what="lesson" error={loaded.state.error} onRetry={loaded.retry} />
            <BackToJourney navigate={navigate} />
          </>
        }
      />
    );

  const lesson = loaded.state.data;
  const summary = lesson.lesson;
  const items = lesson.items;

  /** Calls the start POST (the start, or a retry of the check), then reads the lesson again. */
  const start = () => {
    setStarting({ busy: true, failed: false });
    api.startLesson(unit, number).then(
      () => {
        setStarting({ busy: false, failed: false });
        setDone(new Set());
        loaded.retry();
      },
      () => {
        setStarting({ busy: false, failed: true });
      },
    );
  };
  const startFailed = starting.failed ? (
    <p className="card__body" role="alert">
      The lesson could not be started. Check that the backend is running, then try again.
    </p>
  ) : null;

  // Never started: the explanation, and either "Start lesson" or what it still needs. Reading changed nothing.
  if (items.length === 0 && (summary.state === "AVAILABLE" || summary.state === "NEEDS_POSITIONS")) {
    return (
      <Layout
        panel={
          <section className="card" aria-labelledby="lesson-title">
            <h2 id="lesson-title" className="card__title">
              {summary.title}
            </h2>
            <p className="card__body">{lesson.explanation}</p>
            {summary.state === "AVAILABLE" ? (
              <>
                <p className="card__body">
                  {summary.size} positions from your own games: practice first, then a mastery check of two
                  positions with one try each.
                </p>
                <div className="actions">
                  <button type="button" className="button" onClick={start} disabled={starting.busy}>
                    Start lesson
                  </button>
                </div>
                {startFailed}
              </>
            ) : (
              <p className="card__body">
                {summary.positionsNeeded} more {summary.positionsNeeded === 1 ? "position" : "positions"}{" "}
                needed before this lesson can start. They come from the positions where you left a piece
                hanging in your analysed games.
              </p>
            )}
            <BackToJourney navigate={navigate} />
          </section>
        }
      />
    );
  }

  const item: LessonItem | undefined =
    (pinned !== null ? items.find((i) => i.position === pinned) : undefined) ?? nextItem(items, done);

  if (!item) {
    return (
      <Layout
        panel={
          <>
            <LessonResult lesson={lesson} onRetry={start} retrying={starting.busy} navigate={navigate} />
            {startFailed}
          </>
        }
      />
    );
  }

  const isCheck = item.role === "CHECK";
  const { index, count } = placeOf(items, item);

  /** Moves on to the next unanswered item; after the last one, reads the lesson again for the result. */
  const next = () => {
    const nowDone = new Set(done).add(item.position);
    setDone(nowDone);
    setPinned(null);
    setAttempt({ phase: "choosing" });
    setBoardKey((k) => k + 1);
    if (!nextItem(items, nowDone)) loaded.retry();
  };

  /** A new board for the same puzzle: the move is chosen again. */
  const chooseAgain = () => {
    setAttempt({ phase: "choosing" });
    setBoardKey((k) => k + 1);
  };

  /** After a conflict the lesson is read again; the same puzzle stays if it is still open. */
  const reload = (message: string) => {
    setNotice(message);
    setPinned(null);
    setAttempt({ phase: "choosing" });
    setBoardKey((k) => k + 1);
    loaded.retry();
  };

  const send = (move: PuzzleMove) => {
    setNotice(null);
    setAttempt({ phase: "sending", move });
    api.answer(unit, number, item.position, move.uci).then(
      (answer) => {
        setAttempt({ phase: "answered", move, answer });
      },
      (error: unknown) => {
        const status = error instanceof ApiError ? error.status : 0;
        if (status === 409) {
          reload(
            "This puzzle could not be answered: it had been answered already, or the lesson changed while you " +
              "answered (for example after a new analysis of your games). Nothing new was recorded, and the " +
              "lesson has been loaded again.",
          );
        } else if (status === 404) {
          reload(
            "This puzzle is no longer part of the lesson, for example after a new analysis of your games. " +
              "Nothing was recorded, and the lesson has been loaded again.",
          );
        } else if (status === 400) {
          setNotice("The server did not accept this move, and nothing was recorded. Choose a move again.");
          chooseAgain();
        } else {
          setAttempt({ phase: "failed", move });
        }
      },
    );
  };

  const onMove = (move: PuzzleMove) => {
    setNotice(null);
    setPinned(item.position);
    if (isCheck) setAttempt({ phase: "confirming", move });
    else send(move);
  };

  const answered = attempt.phase === "answered" ? attempt : null;
  const anyAnswered = items.some((i) => i.answered) || done.size > 0;
  const side = sideToMove(item.fen) === "WHITE" ? "White" : "Black";

  return (
    <main className="layout">
      <section className="board-column" aria-label="Board">
        {canShow(item) ? (
          <PuzzleBoard
            key={`${String(item.position)}:${String(boardKey)}`}
            fen={item.fen}
            orientation={item.orientation}
            prompt={item.prompt}
            onMove={onMove}
            result={answered ? (answered.answer.correct ? "correct" : "wrong") : null}
            review={
              answered
                ? {
                    gameMoveUci: answered.answer.gameMove.uci,
                    evidenceLineUci: answered.answer.evidenceLineUci,
                  }
                : null
            }
            awaiting={
              attempt.phase === "confirming" || attempt.phase === "failed" ? "confirmation" : "verdict"
            }
          />
        ) : (
          <StartingBoard />
        )}
      </section>
      <aside className="panel" aria-label="Lesson">
        <section className="card" aria-labelledby="lesson-title">
          <h2 id="lesson-title" className="card__title">
            {summary.title}
          </h2>
          {/* Open until the first puzzle is answered, then collapsed (and still expandable). */}
          <details key={anyAnswered ? "collapsed" : "open"} className="explanation" open={!anyAnswered}>
            <summary>About this lesson</summary>
            <p className="card__body">{lesson.explanation}</p>
          </details>
          <h3 className="card__subtitle">
            Position {index} of {count}
          </h3>
          {isCheck ? <p className="card__body lesson__check">Mastery check: one try</p> : null}
          <p className="card__body">
            {side} to move: {item.prompt}
          </p>
        </section>

        {notice ? <Notice text={notice} /> : null}

        {!canShow(item) ? (
          <section className="card">
            <p className="card__body">This kind of puzzle cannot be shown yet.</p>
            <div className="actions">
              <button type="button" className="button" onClick={next}>
                Next
              </button>
            </div>
          </section>
        ) : attempt.phase === "confirming" || attempt.phase === "failed" ? (
          <ConfirmMove
            key={attempt.phase}
            san={attempt.move.san}
            failed={attempt.phase === "failed"}
            onSend={() => {
              send(attempt.move);
            }}
            onChooseAgain={chooseAgain}
          />
        ) : answered ? (
          <AnswerPanel move={answered.move} answer={answered.answer} onNext={next} />
        ) : null}

        <BackToJourney navigate={navigate} />
      </aside>
    </main>
  );
}

/**
 * The chosen move of a check puzzle before it is sent, or a move whose request failed: send it, or choose
 * another. The first button takes the focus, so a keyboard user lands on "Submit move".
 */
function ConfirmMove({
  san,
  failed,
  onSend,
  onChooseAgain,
}: {
  san: string;
  failed: boolean;
  onSend: () => void;
  onChooseAgain: () => void;
}) {
  const first = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    first.current?.focus();
  }, []);
  return (
    <section className="card" aria-labelledby="confirm-title">
      <h3 id="confirm-title" className="card__subtitle">
        {failed ? "The move could not be sent" : `You chose ${san}`}
      </h3>
      {failed ? (
        <p className="card__body" role="alert">
          {san} was not recorded: the backend could not be reached or failed. Check that it is running, then
          send the move again.
        </p>
      ) : (
        <p className="card__body">
          A mastery check puzzle has one try, so the move is sent only when you submit it.
        </p>
      )}
      <div className="actions">
        <button type="button" className="button" ref={first} onClick={onSend}>
          {failed ? "Send again" : "Submit move"}
        </button>
        <button type="button" className="button" onClick={onChooseAgain}>
          Choose another move
        </button>
      </div>
    </section>
  );
}

function Layout({ panel }: { panel: ReactNode }) {
  return (
    <main className="layout">
      <section className="board-column" aria-label="Board">
        <StartingBoard />
      </section>
      <aside className="panel" aria-label="Lesson">
        {panel}
      </aside>
    </main>
  );
}

function StartingBoard() {
  return (
    <EvidenceBoard
      view={{ fen: START_FEN, orientation: "white", arrows: [], marks: [] }}
      label="Chess board in the starting position"
    />
  );
}

/** A message after a conflict or a refused move; it takes the focus, so it is read before anything else. */
function Notice({ text }: { text: string }) {
  const ref = useRef<HTMLElement>(null);
  useEffect(() => {
    ref.current?.focus();
  }, [text]);
  return (
    <section className="card card--notice" tabIndex={-1} ref={ref} aria-label="Notice">
      <p className="card__body">{text}</p>
    </section>
  );
}

function BackToJourney({ navigate }: { navigate: (path: string) => void }) {
  return (
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
  );
}
