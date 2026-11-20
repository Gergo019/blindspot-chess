/** WCAG 2.2 relative luminance and contrast ratio (https://www.w3.org/TR/WCAG22/#dfn-contrast-ratio). */

export type Theme = "dark" | "light";

/** WCAG AA thresholds: 4.5:1 for normal text, 3:1 for large text and for graphical objects (1.4.11). */
export const AA_TEXT = 4.5;
export const AA_GRAPHICS = 3;

function channel(value: number): number {
  const c = value / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

export function relativeLuminance(hex: string): number {
  const match = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!match?.[1]) throw new Error(`Not a #rrggbb colour: ${hex}`);
  const n = parseInt(match[1], 16);
  return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255);
}

export function contrastRatio(a: string, b: string): number {
  const [hi, lo] = [relativeLuminance(a), relativeLuminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

/**
 * Reads the tokens of one theme from theme.css: the custom properties declared in the block whose
 * selector names that theme. Dark is also the plain :root block (the default).
 */
export function parseTokens(css: string, theme: Theme): Record<string, string> {
  const blocks = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)];
  const block = blocks.find(([, selector]) => selector?.includes(`[data-theme="${theme}"]`));
  if (!block?.[2]) throw new Error(`No token block for theme ${theme}`);
  const tokens: Record<string, string> = {};
  for (const [, name, value] of block[2].matchAll(/--([\w-]+):\s*([^;]+);/g)) {
    if (name && value) tokens[name] = value.trim().toLowerCase();
  }
  return tokens;
}
