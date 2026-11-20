import type { Classification, Motif } from "../api/types";

export const MOTIF_LABEL: Record<Motif, string> = {
  HANGING_PIECE: "Hanging piece",
  ALLOWED_KNIGHT_FORK: "Allowed knight fork",
};

export const CLASSIFICATION_LABEL: Record<Classification, string> = {
  BEST: "Best",
  GOOD: "Good",
  INACCURACY: "Inaccuracy",
  MISTAKE: "Mistake",
  BLUNDER: "Blunder",
};

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}
