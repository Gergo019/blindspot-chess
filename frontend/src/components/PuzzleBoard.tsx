import { type CSSProperties, useEffect, useId, useMemo, useRef, useState } from "react";
import { Chessboard } from "react-chessboard";
import type { Side } from "../api/types";
import {
  type MoveAttempt,
  type PromotionPiece,
  resolveSquares,
  resolveText,
  sideToMove,
} from "../chess/moveInput";
import { playLine } from "../chess/notation";
import { lineLabelAt, lineView, nextStep, orientationOf, plyOf } from "../findings/boardView";
import { LineChips } from "../findings/LineChips";
import { useStepKeys } from "../findings/useStepKeys";
import { BoardArrows } from "./BoardArrows";
import { BOARD_LOOK, prefersReducedMotion, squareStylesFor } from "./boardLook";

/** The move handed to `onMove`: UCI, its SAN for display, and the position it was played from. */
export interface PuzzleMove {
  uci: string;
  san: string;
  fen: string;
}

/** The server's verdict on that move (ADR 0008: correctness is decided there, never here). */
export type PuzzleResult = "correct" | "wrong";

/**
 * What the board may show once the item is answered: the game's move from the puzzle position and the
 * evidence line after it. Given only after the answer, so before it there is nothing to leak.
 */
export interface PuzzleReview {
  gameMoveUci: string;
  evidenceLineUci: readonly string[];
}

interface Props {
  /** The puzzle position (the item's `fen`). */
  fen: string;
  /** The side the user plays, which the board is drawn from (the item's `orientation`). */
  orientation: Side;
  /** The task in words (the item's `prompt`, for example "Find a safe move"). */
  prompt: string;
  /** Called once with the chosen legal move; the parent sends it to the server. */
  onMove: (move: PuzzleMove) => void;
  /** The server's verdict on the move, null until it arrives. */
  result: PuzzleResult | null;
  /** The game's move and its evidence line, only after the answer. */
  review?: PuzzleReview | null;
  /**
   * What the chosen move waits for: the server's verdict (the default), or the player's confirmation, for a
   * check puzzle that the screen sends only after "Submit move" (#96).
   */
  awaiting?: "verdict" | "confirmation";
}

const PIECES: { piece: PromotionPiece; name: string }[] = [
  { piece: "q", name: "Queen" },
  { piece: "r", name: "Rook" },
  { piece: "b", name: "Bishop" },
  { piece: "n", name: "Knight" },
];

/** A neutral ring for the selected piece: not orange or blue, which mean "your mistake" and "evidence". */
const SELECTED: CSSProperties = {
  boxShadow: "inset 0 0 0 2px var(--halo), inset 0 0 0 5px var(--text), inset 0 0 0 7px var(--halo)",
};

function sideName(side: Side): string {
  return side === "WHITE" ? "White" : "Black";
}

/**
 * The interactive board of a lesson puzzle (ADR 0007, amendment of 2026-11-21, "PuzzleBoard"): the look of
 * EvidenceBoard (boardLook.ts, BoardArrows), and three ways to move:
 * - drag a piece;
 * - click a piece, then its target square;
 * - type the move in SAN or UCI. This is the reliable path for the keyboard and screen readers, because
 *   react-chessboard's pieces are unnamed buttons (#52).
 *
 * A pawn reaching the last rank asks for the piece in a labelled group of buttons. chess.js refuses an
 * illegal move at once; a legal one is handed to `onMove` and the board waits for `result`. The component
 * knows no answer: it is never given the accepted moves, the engine's move or the game's move before the
 * answer, and `review` (the game's move and the evidence line, stepped with the arrow keys and chips as on
 * the findings screen) is passed in only afterwards.
 *
 * One answer per mount: after a move, the inputs are disabled. A practice puzzle that may be tried again is
 * mounted again by the screen (a new `key`).
 */
export function PuzzleBoard({
  fen,
  orientation,
  prompt,
  onMove,
  result,
  review = null,
  awaiting = "verdict",
}: Props) {
  const ids = useId();
  const toMove = sideToMove(fen);
  const boardOrientation = orientationOf(orientation);
  const [played, setPlayed] = useState<PuzzleMove & { fenAfter: string }>();
  const [selected, setSelected] = useState<string | null>(null);
  const [promotion, setPromotion] = useState<{ from: string; to: string } | null>(null);
  const [text, setText] = useState("");
  const [refusal, setRefusal] = useState<string | null>(null);
  const [step, setStep] = useState(0);
  const locked = played !== undefined || review !== null;

  /** Applies an attempt: refuses it, asks for the promotion piece, or hands the legal move on. */
  const attempt = (move: MoveAttempt, described: string): boolean => {
    if (locked) return false;
    setSelected(null);
    if (move.kind === "illegal") {
      setPromotion(null);
      setRefusal(`${described} is not a legal move here.`);
      return false;
    }
    setRefusal(null);
    if (move.kind === "promotion") {
      setPromotion({ from: move.from, to: move.to });
      return false;
    }
    setPromotion(null);
    setPlayed({ uci: move.uci, san: move.san, fen, fenAfter: move.fenAfter });
    onMove({ uci: move.uci, san: move.san, fen });
    return true;
  };

  const onSquareClick = ({ piece, square }: { piece: { pieceType: string } | null; square: string }) => {
    if (locked) return;
    const own = piece !== null && piece.pieceType.startsWith(toMove === "WHITE" ? "w" : "b");
    if (selected === null || selected === square || own) {
      setSelected(own && selected !== square ? square : null);
      return;
    }
    attempt(resolveSquares(fen, selected, square), `${selected} to ${square}`);
  };

  // The review: the game's move and the evidence line, stepped as on the findings screen.
  const line = useMemo(
    () => (review ? playLine(fen, [review.gameMoveUci, ...review.evidenceLineUci]) : []),
    [fen, review],
  );
  useStepKeys(review !== null, (key) => {
    setStep((current) => nextStep(current, key, line.length));
  });
  const reviewView = review
    ? lineView({ fen, orientation: boardOrientation, firstPly: plyOf(fen), marks: [] }, line, step)
    : null;

  const position = reviewView?.fen ?? played?.fenAfter ?? fen;
  const squareStyles = reviewView
    ? squareStylesFor(reviewView.marks)
    : selected
      ? { [selected]: SELECTED }
      : {};

  const status = refusal
    ? refusal
    : played === undefined
      ? ""
      : result === null
        ? awaiting === "confirmation"
          ? `You chose ${played.san}. Submit it, or choose another move.`
          : `You played ${played.san}. Checking…`
        : `${played.san}: ${result === "correct" ? "Correct." : "Not this one."}`;

  const label = `${sideName(toMove)} to move: ${prompt}. Board seen from ${sideName(orientation)}.`;

  return (
    <div className="puzzle">
      <div
        className="board"
        data-testid="puzzle-board"
        data-fen={position}
        data-orientation={boardOrientation}
        role="group"
        aria-label={label}
      >
        <div className="board__inner">
          <Chessboard
            options={{
              ...BOARD_LOOK,
              id: "puzzle-board",
              position,
              boardOrientation,
              allowDragging: !locked,
              allowDrawingArrows: false,
              showAnimations: !prefersReducedMotion(),
              squareStyles,
              canDragPiece: ({ piece }) =>
                !locked && piece.pieceType.startsWith(toMove === "WHITE" ? "w" : "b"),
              onPieceDrop: ({ sourceSquare, targetSquare }) =>
                targetSquare !== null &&
                attempt(
                  resolveSquares(fen, sourceSquare, targetSquare),
                  `${sourceSquare} to ${targetSquare}`,
                ),
              onSquareClick,
            }}
          />
          {reviewView ? <BoardArrows arrows={reviewView.arrows} orientation={boardOrientation} /> : null}
        </div>
      </div>

      {/* Not the only status region on the page: dnd-kit, inside the board, has its own for drag announcements. */}
      <p className="puzzle__status" role="status" data-testid="puzzle-status">
        {played && result ? (
          <span className="puzzle__icon" aria-hidden="true">
            {result === "correct" ? "✓ " : "✗ "}
          </span>
        ) : null}
        {status}
      </p>

      {promotion ? (
        <PromotionChoice
          titleId={`${ids}-promotion`}
          onChoose={(piece) => {
            attempt(
              resolveSquares(fen, promotion.from, promotion.to, piece),
              `${promotion.from} to ${promotion.to}`,
            );
          }}
          onCancel={() => {
            setPromotion(null);
          }}
        />
      ) : null}

      <form
        className="puzzle__form"
        onSubmit={(event) => {
          event.preventDefault();
          attempt(resolveText(fen, text), text.trim() === "" ? "An empty move" : `“${text.trim()}”`);
        }}
      >
        <label htmlFor={`${ids}-move`} className="puzzle__label">
          Your move
        </label>
        <div className="puzzle__row">
          <input
            id={`${ids}-move`}
            className="puzzle__input"
            type="text"
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            value={text}
            disabled={locked}
            aria-describedby={`${ids}-hint`}
            aria-invalid={refusal !== null}
            onChange={(event) => {
              setText(event.target.value);
            }}
          />
          <button type="submit" className="button" disabled={locked}>
            Play
          </button>
        </div>
        <p id={`${ids}-hint`} className="puzzle__hint">
          In SAN or UCI, for example Nf3 or g1f3.
        </p>
      </form>

      {reviewView ? (
        <div className="puzzle__review">
          <p className="board-caption" aria-live="polite">
            {reviewView.caption}
          </p>
          <h3 className="card__subtitle" id={`${ids}-line`}>
            In your game, and what follows
          </h3>
          <LineChips
            titleId={`${ids}-line`}
            labels={line.map((_, index) => lineLabelAt(plyOf(fen), line, index))}
            step={step}
            onStep={setStep}
          />
        </div>
      ) : null}
    </div>
  );
}

/** The piece a pawn promotes to, as a labelled group of buttons; the first one takes the focus. */
function PromotionChoice({
  titleId,
  onChoose,
  onCancel,
}: {
  titleId: string;
  onChoose: (piece: PromotionPiece) => void;
  onCancel: () => void;
}) {
  const first = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    first.current?.focus();
  }, []);
  return (
    <div className="puzzle__promotion" role="group" aria-labelledby={titleId}>
      <p id={titleId} className="puzzle__label">
        Promote the pawn to
      </p>
      <div className="actions">
        {PIECES.map(({ piece, name }, index) => (
          <button
            key={piece}
            ref={index === 0 ? first : undefined}
            type="button"
            className="button"
            onClick={() => {
              onChoose(piece);
            }}
          >
            {name}
          </button>
        ))}
        <button type="button" className="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </div>
  );
}
