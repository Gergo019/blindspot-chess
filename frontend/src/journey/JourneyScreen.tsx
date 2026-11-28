import { api } from "../api/client";
import { useApi } from "../api/useApi";
import { START_FEN } from "../chess/notation";
import { EvidenceBoard } from "../components/EvidenceBoard";
import { orientationOf } from "../findings/boardView";
import { BlindSpotCard } from "../findings/BlindSpotCard";
import { ErrorCard, LoadingCard } from "../findings/StatusCard";
import { lessonRoutePath } from "../router";
import { LessonPath } from "./LessonPath";
import { choosePreview, previewLesson } from "./preview";

interface Props {
  navigate: (path: string) => void;
}

/**
 * The journey map (#95, ADR 0007 amendment of 2026-11-21, "Map"): the board on the left previews the
 * recommended next puzzle (preview.ts), read-only and without arrows; the side panel has "Your blind spot"
 * and the unit's lessons as a path. The map only reads: it never starts a lesson.
 *
 * "Your blind spot" comes from GET /api/patterns, as on the findings screen: the journey's own line of the
 * report has no window or threshold, which the card needs (decided by Gergo for #95).
 */
export function JourneyScreen({ navigate }: Props) {
  const journey = useApi("journey", (signal) => api.journey(signal));
  const patterns = useApi("patterns", (signal) => api.patterns(signal));
  const lessons = journey.state.status === "ok" ? journey.state.data.lessons : [];
  const started = previewLesson(lessons);
  const lesson = useApi(started ? `lesson:${started.key}` : null, (signal) =>
    api.lesson(started?.unit ?? "", started?.number ?? 0, signal),
  );

  const preview =
    journey.state.status === "ok" ? choosePreview(lessons, started ? lesson.state : null) : null;
  const shown = preview?.kind === "position" ? preview : null;
  const view = shown
    ? { fen: shown.fen, orientation: orientationOf(shown.orientation), arrows: [], marks: [] }
    : { fen: START_FEN, orientation: "white" as const, arrows: [], marks: [] };
  const label = shown
    ? `Chess board seen from ${shown.orientation === "WHITE" ? "White" : "Black"}: the next position of ${shown.lesson.title}`
    : "Chess board in the starting position";

  return (
    <main className="layout">
      <section className="board-column" aria-label="Board">
        <EvidenceBoard view={view} label={label} />
        <p className="board-caption">{preview?.note ?? ""}</p>
      </section>
      <aside className="panel" aria-label="Journey">
        <BlindSpotCard state={patterns.state} onRetry={patterns.retry} />
        {journey.state.status === "loading" ? (
          <LoadingCard what="your journey" />
        ) : journey.state.status === "error" ? (
          <ErrorCard what="journey" error={journey.state.error} onRetry={journey.retry} />
        ) : (
          <section className="card" aria-labelledby="path-title">
            <h2 id="path-title" className="card__title">
              {journey.state.data.unit.title}: lessons
            </h2>
            {lessons.every((l) => l.state === "NEEDS_POSITIONS") ? (
              <p className="card__body">
                No lesson can be filled yet. Lessons fill up from the positions where you left a piece hanging
                in your analysed games.
              </p>
            ) : null}
            <LessonPath
              lessons={lessons}
              onOpen={(l) => {
                navigate(lessonRoutePath(l.unit, l.number));
              }}
            />
          </section>
        )}
      </aside>
    </main>
  );
}
