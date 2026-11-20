/** Centre of a square in board units (0..8), seen from `orientation`. */
export function squareCentre(square: string, orientation: "white" | "black"): { x: number; y: number } {
  const file = square.charCodeAt(0) - "a".charCodeAt(0);
  const rank = Number(square[1]);
  return orientation === "white"
    ? { x: file + 0.5, y: 8 - rank + 0.5 }
    : { x: 7 - file + 0.5, y: rank - 1 + 0.5 };
}
