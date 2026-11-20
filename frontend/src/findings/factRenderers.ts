import type { Facts } from "../api/types";
import type { Ply } from "../chess/notation";

/**
 * ADR 0007, "Evidence on the board": every fact the API can return has a renderer, which gives its label,
 * its value and the squares it marks on the board. A fact without a renderer is never shown, so no claim
 * can appear in the panel without something to point at. New fact types (#34) add their renderer here, in
 * the same PR; factRenderers.test.ts and the type check below enforce it.
 */

export type MarkKind = "mistake" | "evidence";

export interface SquareMark {
  square: string;
  kind: MarkKind;
}

export interface FactContext {
  /** The played move followed by the evidence line, as played from the position before the mistake. */
  line: readonly Ply[];
}

export interface FactRenderer {
  label: string;
  value: (facts: Facts) => string | null;
  marks: (facts: Facts, context: FactContext) => SquareMark[];
}

const square = (value: string | null, kind: MarkKind): SquareMark[] =>
  value ? [{ square: value, kind }] : [];

export const FACT_RENDERERS = {
  movedPiece: {
    label: "You moved",
    value: (f) => f.movedPiece,
    marks: (f) => [...square(f.movedFrom, "mistake"), ...square(f.movedTo, "mistake")],
  },
  movedFrom: { label: "From", value: (f) => f.movedFrom, marks: (f) => square(f.movedFrom, "mistake") },
  movedTo: { label: "To", value: (f) => f.movedTo, marks: (f) => square(f.movedTo, "mistake") },
  piece: { label: "Piece lost", value: (f) => f.piece, marks: (f) => square(f.square, "evidence") },
  square: { label: "On", value: (f) => f.square, marks: (f) => square(f.square, "evidence") },
  capturedBy: {
    label: "Captured by",
    value: (f) => f.capturedBy,
    // The capturing piece: where the line's first move onto the lost piece's square starts.
    marks: (f, { line }) => {
      const capture = line.slice(1).find((ply) => ply.to === f.square);
      return [...square(capture?.from ?? null, "evidence"), ...square(f.square, "evidence")];
    },
  },
  materialLost: {
    label: "Material lost",
    value: (f) =>
      f.materialLost === null ? null : `${String(f.materialLost)} point${f.materialLost === 1 ? "" : "s"}`,
    marks: (f) => square(f.square, "evidence"),
  },
} satisfies Record<keyof Facts, FactRenderer>;

/** The facts to show, in renderer order, skipping those the detector did not report. */
export function renderFacts(facts: Facts, context: FactContext) {
  return (Object.keys(FACT_RENDERERS) as (keyof Facts)[]).flatMap((key) => {
    const renderer: FactRenderer = FACT_RENDERERS[key];
    const value = renderer.value(facts);
    return value === null
      ? []
      : [{ key, label: renderer.label, value, marks: renderer.marks(facts, context) }];
  });
}
