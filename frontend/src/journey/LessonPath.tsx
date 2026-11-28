import type { LessonSummary } from "../api/types";

/**
 * The lessons of a unit as a vertical path of buttons (ADR 0007, amendment of 2026-11-21, "Map"). Each node
 * says its state with its evidence. Nothing is locked: every node opens its lesson, also one that still needs
 * positions (decided by Gergo for #95). The recommended next lesson has aria-current="step".
 *
 * No gamification: no points, streaks, badges or rewarding messages; the texts only say what is the case.
 */
export function LessonPath({
  lessons,
  onOpen,
}: {
  lessons: readonly LessonSummary[];
  onOpen: (lesson: LessonSummary) => void;
}) {
  return (
    <ol className="path">
      {lessons.map((lesson) => (
        <li key={lesson.key} className="path__step">
          <button
            type="button"
            className="path__node"
            data-state={lesson.state}
            aria-current={lesson.recommended ? "step" : undefined}
            onClick={() => {
              onOpen(lesson);
            }}
          >
            <span className="path__number" aria-hidden="true">
              {lesson.number}
            </span>
            <span className="path__text">
              <span className="path__title">{lesson.title}</span>
              {stateLines(lesson).map((line) => (
                <span key={line} className="path__state">
                  {line}
                </span>
              ))}
              {lesson.recommended ? <span className="path__next">Recommended next</span> : null}
            </span>
          </button>
        </li>
      ))}
    </ol>
  );
}

function positions(n: number, more = false): string {
  const noun = n === 1 ? "position" : "positions";
  return `${String(n)} ${more ? "more " : ""}${noun}`;
}

/** The state of a lesson in words, with its evidence. */
function stateLines(lesson: LessonSummary): string[] {
  switch (lesson.state) {
    case "MASTERED":
      return ["Mastered"];
    case "AVAILABLE":
      return [`${positions(lesson.size)} ready, not started`];
    case "NEEDS_POSITIONS":
      return [`${positions(lesson.positionsNeeded, true)} needed`];
    case "IN_PROGRESS": {
      const lines = [`${String(lesson.solvedFirstTry)} of ${positions(lesson.size)} solved first try`];
      if (lesson.retryAvailable) lines.push("The mastery check can be retried on new positions.");
      else if (lesson.positionsNeeded > 0)
        lines.push(`A retry of the mastery check needs ${positions(lesson.positionsNeeded, true)}.`);
      return lines;
    }
  }
}
