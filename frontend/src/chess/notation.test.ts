import { numbered, playLine, sanOf, START_FEN } from "./notation";

describe("notation", () => {
  it("derives SAN and positions from FEN and UCI", () => {
    const line = playLine(START_FEN, ["e2e4", "e7e5", "g1f3"]);
    expect(line.map((p) => p.san)).toEqual(["e4", "e5", "Nf3"]);
    expect(line[2]?.fenAfter).toBe("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2");
  });

  it("handles promotion and stops at the first illegal move", () => {
    expect(sanOf("8/P7/8/8/8/8/8/k6K w - - 0 1", "a7a8q")).toBe("a8=Q+");
    expect(playLine(START_FEN, ["e2e4", "e2e4", "e7e5"]).map((p) => p.san)).toEqual(["e4"]);
    expect(sanOf(START_FEN, "e2e5")).toBeNull();
  });

  it("numbers moves for both sides", () => {
    expect(numbered(4, "WHITE", "Nxe5")).toBe("4. Nxe5");
    expect(numbered(18, "BLACK", "Rb6")).toBe("18… Rb6");
  });
});
