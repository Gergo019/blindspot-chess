import { highlightStyle } from "./highlight";

describe("highlightStyle", () => {
  it.each(["mistake", "evidence"] as const)(
    "draws %s as a ring between two halo rings, never as a fill",
    (kind) => {
      const style = highlightStyle(kind);
      expect(style.backgroundColor).toBeUndefined();
      expect(style.background).toBeUndefined();
      const rings = String(style.boxShadow)
        .split(",")
        .map((ring) => ring.trim());
      expect(rings).toHaveLength(3);
      expect(rings[0]).toContain("var(--halo)");
      expect(rings[1]).toContain(`var(--${kind})`);
      expect(rings[2]).toContain("var(--halo)");
    },
  );
});
