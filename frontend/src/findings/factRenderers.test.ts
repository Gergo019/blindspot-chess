import type { Facts } from "../api/types";
import { playLine } from "../chess/notation";
import { FACTS, FEN_BEFORE } from "../test/mockApi";
import { FACT_RENDERERS, renderFacts } from "./factRenderers";

const line = playLine(FEN_BEFORE, ["f3e5", "d8g5", "e5f7", "g5g2"]);

describe("fact renderers (ADR 0007)", () => {
  it("has a renderer for every fact type the API returns", () => {
    // A complete Facts object as the API sends it: adding a field to the type without a renderer fails
    // the type check (satisfies Record<keyof Facts, ...>) and this test.
    const everyFact: Required<Facts> = { ...FACTS };
    expect(Object.keys(FACT_RENDERERS).sort()).toEqual(Object.keys(everyFact).sort());
  });

  it("marks a square on the board for every fact with a square", () => {
    const rendered = renderFacts(FACTS, { line });
    for (const fact of rendered.filter((f) => ["movedFrom", "movedTo", "square"].includes(f.key))) {
      expect(fact.marks.map((m) => m.square)).toContain(fact.value);
    }
    expect(rendered.find((f) => f.key === "movedFrom")?.marks).toEqual([{ square: "f3", kind: "mistake" }]);
    expect(rendered.find((f) => f.key === "capturedBy")?.marks).toEqual([
      { square: "g5", kind: "evidence" },
      { square: "g2", kind: "evidence" },
    ]);
  });

  it("skips facts the detector did not report, and never shows an unknown fact", () => {
    const partial = { ...FACTS, capturedBy: null, materialLost: null, mystery: "x" } as Facts;
    const keys = renderFacts(partial, { line }).map((f) => f.key);
    expect(keys).not.toContain("capturedBy");
    expect(keys).not.toContain("materialLost");
    expect(keys).not.toContain("mystery");
  });

  it("says what the material loss measures: against the best line, or in an exchange on the square", () => {
    // 4. Nxe5 is a capture, but the reply Qg5 does not recapture on e5: rule 3, against the best line.
    const best = renderFacts(FACTS, { line }).find((f) => f.key === "materialLost");
    expect(best?.label).toBe("Material lost compared with the best move");
    expect(best?.hint).toContain("includes what the best move would have won");

    // 3. Nxd5 exd5: the opponent recaptures on d5, so only the exchange on d5 is judged (rule 2).
    const exchangeLine = playLine("rnbqkbnr/ppp2ppp/4p3/3p4/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 3", [
      "c3d5",
      "e6d5",
    ]);
    const exchange = renderFacts({ ...FACTS, square: "d5", materialLost: 2 }, { line: exchangeLine }).find(
      (f) => f.key === "materialLost",
    );
    expect(exchange?.label).toBe("Material lost in the exchange on d5");
    expect(exchange?.value).toBe("2 points");
  });
});
