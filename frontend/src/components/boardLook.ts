import type { CSSProperties } from "react";
import { highlightStyle, type HighlightKind } from "../theme/highlight";

/**
 * The board's look, shared by EvidenceBoard and PuzzleBoard (ADR 0007): the square and coordinate colours
 * from the theme, rounded corners, and the animation that respects "reduce motion".
 */
export const BOARD_LOOK = {
  showNotation: true,
  animationDurationInMs: 150,
  lightSquareStyle: { backgroundColor: "var(--square-light)" },
  darkSquareStyle: { backgroundColor: "var(--square-dark)" },
  lightSquareNotationStyle: { color: "var(--coordinate)" },
  darkSquareNotationStyle: { color: "var(--coordinate)" },
  boardStyle: { borderRadius: "6px", overflow: "hidden" },
} as const;

/** Respect "reduce motion"; without matchMedia (tests, very old browsers) do not animate either. */
export function prefersReducedMotion(): boolean {
  if (typeof window.matchMedia !== "function") return true;
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** The ring-between-halos style of each marked square (highlight.ts). */
export function squareStylesFor(
  marks: readonly { square: string; kind: HighlightKind }[],
): Record<string, CSSProperties> {
  const styles: Record<string, CSSProperties> = {};
  for (const mark of marks) styles[mark.square] = highlightStyle(mark.kind);
  return styles;
}
