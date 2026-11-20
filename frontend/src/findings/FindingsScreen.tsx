import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import type { FindingDetail } from "../api/types";
import { useApi } from "../api/useApi";
import { playLine, START_FEN } from "../chess/notation";
import { EvidenceBoard } from "../components/EvidenceBoard";
import { Legend } from "../components/Legend";
import { findingPath } from "../router";
import { BlindSpotCard } from "./BlindSpotCard";
import { buildView, type Cursor } from "./boardView";
import { FindingFooter } from "./FindingFooter";
import { FindingList } from "./FindingList";
import { FindingPanel } from "./FindingPanel";
import { MoveList } from "./MoveList";
import { ErrorCard, LoadingCard } from "./StatusCard";

const PAGE_SIZE = 10;

interface Props {
  findingId: number | null;
  navigate: (path: string, options?: { replace?: boolean }) => void;
}

/**
 * The findings screen (#24, ADR 0007): the board on the left; the blind-spot report, the findings, the
 * selected finding, its game's moves and how it was produced on the right. The selected finding is in the
 * URL (/findings/:id), so it can be linked and survives a reload.
 */
export function FindingsScreen({ findingId, navigate }: Props) {
  const [page, setPage] = useState(0);
  const patterns = useApi("patterns", (signal) => api.patterns(signal));
  const list = useApi(`findings:${String(page)}`, (signal) =>
    api.findings({ page, size: PAGE_SIZE }, signal),
  );
  const detail = useApi(findingId === null ? null : `finding:${String(findingId)}`, (signal) =>
    api.finding(findingId ?? 0, signal),
  );
  const current: FindingDetail | null = detail.state.status === "ok" ? detail.state.data : null;
  const moves = useApi(current ? `moves:${String(current.game.id)}` : null, (signal) =>
    api.gameMoves(current?.game.id ?? 0, signal),
  );

  // Without a finding in the URL, select the newest one (replacing the URL, so Back still leaves the screen).
  const first = list.state.status === "ok" ? list.state.data.items[0] : undefined;
  useEffect(() => {
    if (findingId === null && page === 0 && first) navigate(findingPath(first.id), { replace: true });
  }, [findingId, first, page, navigate]);

  const line = useMemo(
    () => (current ? playLine(current.fenBefore, [current.played.uci, ...current.evidenceLineUci]) : []),
    [current],
  );
  const gameMoves = useMemo(() => (moves.state.status === "ok" ? moves.state.data.moves : []), [moves.state]);
  const gamePlies = useMemo(
    () =>
      playLine(
        START_FEN,
        gameMoves.map((m) => m.played.uci),
      ),
    [gameMoves],
  );

  // The cursor belongs to one finding: selecting another one starts at the position before its mistake.
  const START: Cursor = { mode: "line", step: 0 };
  const [cursorState, setCursorState] = useState<{ findingId: number | null; cursor: Cursor }>({
    findingId: null,
    cursor: START,
  });
  const currentId = current?.id ?? null;
  const cursor = cursorState.findingId === currentId ? cursorState.cursor : START;
  const setCursor = useCallback(
    (next: Cursor | ((previous: Cursor) => Cursor)) => {
      setCursorState((state) => {
        const previous = state.findingId === currentId ? state.cursor : { mode: "line" as const, step: 0 };
        return { findingId: currentId, cursor: typeof next === "function" ? next(previous) : next };
      });
    },
    [currentId],
  );

  const step = useCallback(
    (key: string) => {
      setCursor((c) => {
        if (c.mode === "game") {
          const last = gamePlies.length;
          const ply = key === "Home" ? 0 : key === "End" ? last : c.ply + (key === "ArrowRight" ? 1 : -1);
          return { mode: "game", ply: Math.max(0, Math.min(last, ply)) };
        }
        const from = c.mode === "best" ? 0 : c.step;
        const last = line.length;
        const next = key === "Home" ? 0 : key === "End" ? last : from + (key === "ArrowRight" ? 1 : -1);
        return { mode: "line", step: Math.max(0, Math.min(last, next)) };
      });
    },
    [gamePlies.length, line.length, setCursor],
  );

  useEffect(() => {
    if (!current) return;
    const onKey = (event: KeyboardEvent) => {
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
      const target = event.target as HTMLElement | null;
      if (target?.closest("input, textarea, select, [contenteditable='true']")) return;
      event.preventDefault();
      step(event.key);
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
    };
  }, [current, step]);

  const view = current
    ? buildView(current, line, gamePlies, gameMoves, cursor)
    : { fen: START_FEN, orientation: "white" as const, arrows: [], marks: [], caption: "" };

  const select = (id: number) => {
    navigate(findingPath(id));
  };

  return (
    <main className="layout">
      <section className="board-column" aria-label="Board">
        <EvidenceBoard
          view={view}
          label={`Chess board seen from ${view.orientation === "white" ? "White" : "Black"}${view.caption ? `: ${view.caption}` : ""}`}
        />
        <p className="board-caption" role="status">
          {view.caption}
        </p>
        <Legend />
      </section>
      <aside className="panel" aria-label="Findings">
        <BlindSpotCard state={patterns.state} onRetry={patterns.retry} />
        <FindingList
          state={list.state}
          selectedId={findingId}
          onSelect={select}
          onPage={setPage}
          onRetry={list.retry}
        />
        {findingId === null ? null : detail.state.status === "loading" ? (
          <LoadingCard what="the finding" />
        ) : detail.state.status === "error" ? (
          <ErrorCard what="finding" error={detail.state.error} onRetry={detail.retry} />
        ) : current ? (
          <>
            <FindingPanel detail={current} line={line} cursor={cursor} onCursor={setCursor} />
            <MoveList
              state={moves.state}
              plies={gamePlies}
              mistakePly={current.ply}
              cursor={cursor}
              onCursor={setCursor}
              onRetry={moves.retry}
            />
            <FindingFooter detail={current} />
          </>
        ) : null}
      </aside>
    </main>
  );
}
