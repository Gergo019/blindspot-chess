import css from "./theme.css?raw";
import { AA_GRAPHICS, AA_TEXT, contrastRatio, parseTokens, type Theme } from "./contrast";

/**
 * WCAG AA for every colour pair the UI draws (ADR 0007): text on every background, coordinates on
 * both square colours, the focus ring on every background, and the highlight rings on both squares.
 * A failure names the theme, the pair and the ratio; fix the token in theme.css (or, for a
 * highlight, the way it is drawn in highlight.ts), never the threshold.
 */
type Pair = { fg: string; bg: string; min: number; what: string };

const backgrounds = ["bg", "surface", "surface-raised"];

const pairs: Pair[] = [
  ...["text", "text-muted", "text-disabled"].flatMap((fg) =>
    backgrounds.map((bg) => ({ fg, bg, min: AA_TEXT, what: "text" })),
  ),
  ...["square-light", "square-dark"].map((bg) => ({
    fg: "coordinate",
    bg,
    min: AA_TEXT,
    what: "coordinates",
  })),
  ...backgrounds.map((bg) => ({ fg: "focus", bg, min: AA_GRAPHICS, what: "focus ring" })),
  // Highlight rings (highlight.ts): the halo meets the square, the meaning colour meets the halo.
  ...["square-light", "square-dark"].map((bg) => ({
    fg: "halo",
    bg,
    min: AA_GRAPHICS,
    what: "highlight halo",
  })),
  ...["mistake", "evidence"].map((fg) => ({ fg, bg: "halo", min: AA_GRAPHICS, what: "highlight ring" })),
  // Legend swatches and other small marks on the panel use the same halo outline.
  ...["mistake", "evidence"].map((fg) => ({ fg, bg: "halo", min: AA_GRAPHICS, what: "legend swatch" })),
];

describe.each<Theme>(["dark", "light"])("%s theme", (theme) => {
  const tokens = parseTokens(css, theme);

  it.each(pairs.map((p) => [`${p.what}: ${p.fg} on ${p.bg} >= ${String(p.min)}`, p] as const))(
    "%s",
    (_, p) => {
      const fg = tokens[p.fg];
      const bg = tokens[p.bg];
      expect(fg, `--${p.fg} missing`).toBeDefined();
      expect(bg, `--${p.bg} missing`).toBeDefined();
      const ratio = contrastRatio(fg as string, bg as string);
      expect(
        ratio,
        `${theme}: --${p.fg} ${String(fg)} on --${p.bg} ${String(bg)} is ${ratio.toFixed(2)}:1`,
      ).toBeGreaterThanOrEqual(p.min);
    },
  );
});

describe("theme.css", () => {
  it("defines the same tokens in both themes", () => {
    expect(Object.keys(parseTokens(css, "light")).sort()).toEqual(
      Object.keys(parseTokens(css, "dark")).sort(),
    );
  });

  it("keeps the agreed meaning colours (ADR 0007)", () => {
    expect(parseTokens(css, "dark")["mistake"]).toBe("#f0883e");
    expect(parseTokens(css, "light")["mistake"]).toBe("#f0883e");
    expect(parseTokens(css, "dark")["evidence"]).toBe("#58a6ff");
    expect(parseTokens(css, "light")["evidence"]).toBe("#1f6feb");
  });
});

describe("contrastRatio", () => {
  it("matches the WCAG reference values", () => {
    expect(contrastRatio("#000000", "#ffffff")).toBeCloseTo(21, 5);
    expect(contrastRatio("#777777", "#ffffff")).toBeCloseTo(4.48, 2);
    expect(contrastRatio("#ffffff", "#ffffff")).toBe(1);
  });
});
