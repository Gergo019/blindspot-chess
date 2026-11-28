import type { Lesson, LessonSummary, Side } from "../api/types";
import type { Loadable } from "../api/useApi";

/**
 * What the map's board previews (ADR 0007, amendment of 2026-11-21, "Map"; decided by Gergo for #95): the
 * position of the recommended next puzzle, without arrows, but only for a lesson that is already started.
 * The map never starts a lesson (that POST assigns it), and a lesson that is not started has no items to show.
 *
 * - No recommended lesson: nothing to preview.
 * - Recommended, not started: nothing yet; starting it shows its first position.
 * - Recommended and started: its first unanswered item. With every item answered (a failed check without
 *   unused positions, as with the demo data), its first practice item, which can be tried again.
 */
export type Preview =
  | { kind: "position"; fen: string; orientation: Side; lesson: LessonSummary; note: string | null }
  | { kind: "none"; note: string };

/** The lesson whose position the map previews, if any: the recommended one, once it is started. */
export function previewLesson(lessons: readonly LessonSummary[]): LessonSummary | null {
  const recommended = lessons.find((l) => l.recommended);
  return recommended?.state === "IN_PROGRESS" ? recommended : null;
}

export function choosePreview(lessons: readonly LessonSummary[], lesson: Loadable<Lesson> | null): Preview {
  const recommended = lessons.find((l) => l.recommended);
  if (!recommended) return { kind: "none", note: "No lesson is recommended right now." };
  if (recommended.state !== "IN_PROGRESS" || lesson === null)
    return { kind: "none", note: "Start the lesson to see its first position." };
  if (lesson.status === "loading") return { kind: "none", note: "Loading the lesson's next position…" };
  if (lesson.status === "error")
    return { kind: "none", note: "The lesson's next position could not be loaded." };

  const items = lesson.data.items;
  const next = items.find((item) => !item.answered);
  if (next)
    return {
      kind: "position",
      fen: next.fen,
      orientation: next.orientation,
      lesson: recommended,
      note: null,
    };
  const practice = items.find((item) => item.role === "PRACTICE");
  if (practice)
    return {
      kind: "position",
      fen: practice.fen,
      orientation: practice.orientation,
      lesson: recommended,
      note: "Every position of this lesson is answered; its practice positions can be tried again.",
    };
  return { kind: "none", note: "This lesson has no position left to show." };
}
