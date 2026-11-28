import { resolveSquares, resolveText, sideToMove } from "./moveInput";

/** After 1. e4 e5 2. Nf3 Nc6 3. Bc4 Nd4: White to move (the backend's TestData.FORK_FEN). */
const FORK = "r1bqkbnr/pppp1ppp/8/4p3/2BnP3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";
/** White pawns on d7 and e7, a black rook on d8 to take with promotion; White can castle short. */
const PROMOTION = "3r3k/3PP3/8/8/8/8/7P/4K2R w K - 0 1";

describe("resolveText", () => {
  it.each([
    ["Nxd4", "f3d4", "Nxd4"],
    ["f3d4", "f3d4", "Nxd4"],
    ["F3D4", "f3d4", "Nxd4"],
    ["  Nc3 ", "b1c3", "Nc3"],
    ["Nxe5", "f3e5", "Nxe5"],
  ])("takes %j as %s", (input, uci, san) => {
    expect(resolveText(FORK, input)).toMatchObject({ kind: "legal", uci, san });
  });

  it.each(["e2e5", "Nf5", "nxd4", "Qh5h7", "", "   ", "hello", "e9e4"])("refuses %j", (input) => {
    expect(resolveText(FORK, input)).toEqual({ kind: "illegal" });
  });

  it("accepts check marks and castling with zeros", () => {
    expect(resolveText(PROMOTION, "O-O")).toMatchObject({ kind: "legal", uci: "e1g1" });
    expect(resolveText(PROMOTION, "0-0")).toMatchObject({ kind: "legal", uci: "e1g1" });
    expect(resolveText(PROMOTION, "e8=Q+")).toMatchObject({ kind: "legal", uci: "e7e8q", san: "e8=Q+" });
    expect(resolveText(PROMOTION, "e8=Q")).toMatchObject({ kind: "legal", uci: "e7e8q" });
  });

  it("asks for the piece when a promotion is written without it", () => {
    expect(resolveText(PROMOTION, "e8")).toEqual({ kind: "promotion", from: "e7", to: "e8" });
    expect(resolveText(PROMOTION, "e7e8")).toEqual({ kind: "promotion", from: "e7", to: "e8" });
    expect(resolveText(PROMOTION, "exd8")).toEqual({ kind: "promotion", from: "e7", to: "d8" });
    expect(resolveText(PROMOTION, "e7e8n")).toMatchObject({ kind: "legal", uci: "e7e8n", san: "e8=N" });
  });
});

describe("resolveSquares", () => {
  it("gives the legal move with its SAN and the position after it", () => {
    const move = resolveSquares(FORK, "f3", "d4");
    expect(move).toMatchObject({ kind: "legal", uci: "f3d4", san: "Nxd4" });
    // The white knight now stands on d4, between the bishop and the pawn.
    expect(move.kind === "legal" && move.fenAfter).toContain("/2BNP3/");
  });

  it("refuses an illegal move and a move of the wrong side", () => {
    expect(resolveSquares(FORK, "f3", "f5")).toEqual({ kind: "illegal" });
    expect(resolveSquares(FORK, "d4", "f3")).toEqual({ kind: "illegal" });
    expect(resolveSquares(FORK, "e4", "e4")).toEqual({ kind: "illegal" });
  });

  it("asks for the piece of a promotion, then takes the chosen one", () => {
    expect(resolveSquares(PROMOTION, "e7", "e8")).toEqual({ kind: "promotion", from: "e7", to: "e8" });
    expect(resolveSquares(PROMOTION, "e7", "e8", "r")).toMatchObject({ kind: "legal", uci: "e7e8r" });
  });
});

it("reads the side to move from the FEN", () => {
  expect(sideToMove(FORK)).toBe("WHITE");
  expect(sideToMove("8/8/8/8/8/8/8/K6k b - - 0 1")).toBe("BLACK");
});
