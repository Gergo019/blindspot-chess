import type { CSSProperties } from "react";

export type HighlightKind = "mistake" | "evidence";

/**
 * How a highlighted square is drawn (ADR 0007). Not a fill: the orange and blue reach only about
 * 1.2:1 to 2:1 against the blue-grey squares, below the 3:1 that WCAG 1.4.11 asks of graphics.
 * Instead the meaning colour is a ring sandwiched between two dark --halo rings, so the halo is what
 * meets the square (at least 4.5:1) and the colour always sits next to the halo (at least 3:1).
 * contrast.test.ts checks both pairs.
 */
export function highlightStyle(kind: HighlightKind): CSSProperties {
  const colour = `var(--${kind})`;
  return {
    boxShadow: `inset 0 0 0 2px var(--halo), inset 0 0 0 6px ${colour}, inset 0 0 0 8px var(--halo)`,
  };
}
