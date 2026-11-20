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
  /** What the value measures; may depend on the line (see materialLost). */
  label: string | ((facts: Facts, context: FactContext) => string);
  value: (facts: Facts) => string | null;
  marks: (facts: Facts, context: FactContext) => SquareMark[];
  /** A short explanation of how the value is measured, shown under it. */
  hint?: (facts: Facts, context: FactContext) => string;
}

/**
 * ADR 0004, rule 2: when your move captures and the opponent's first reply recaptures on the same square,
 * the detector judges only the exchange on that square. Otherwise (rule 3) `materialLost` is how far your
 * line ends behind the engine's best line over the same number of moves, so it can be larger than the
 * piece itself (ADR 0004, "Consequences": rook for knight while the best line wins a pawn is 4).
 */
export function exchangeSquare(context: FactContext): string | null {
  const [played, reply] = context.line;
  if (!played || !reply) return null;
  return played.san.includes("x") && reply.to === played.to ? played.to : null;
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
    label: (_f, context) => {
      const square = exchangeSquare(context);
      return square
        ? `Material lost in the exchange on ${square}`
        : "Material lost compared with the best move";
    },
    value: (f) =>
      f.materialLost === null ? null : `${String(f.materialLost)} point${f.materialLost === 1 ? "" : "s"}`,
    marks: (f) => square(f.square, "evidence"),
    hint: (_f, context) =>
      exchangeSquare(context)
        ? "What you gave up on that square minus what you took back there, in pawns (N, B = 3, R = 5, Q = 9)."
        : "How far your line ends behind the engine's best line over the same moves, in pawns (N, B = 3, R = 5, Q = 9). It includes what the best move would have won.",
  },
} satisfies Record<keyof Facts, FactRenderer>;

/** The facts to show, in renderer order, skipping those the detector did not report. */
export function renderFacts(facts: Facts, context: FactContext) {
  return (Object.keys(FACT_RENDERERS) as (keyof Facts)[]).flatMap((key) => {
    const renderer: FactRenderer = FACT_RENDERERS[key];
    const value = renderer.value(facts);
    if (value === null) return [];
    const label = typeof renderer.label === "function" ? renderer.label(facts, context) : renderer.label;
    const hint = renderer.hint ? renderer.hint(facts, context) : null;
    return [{ key, label, value, hint, marks: renderer.marks(facts, context) }];
  });
}
