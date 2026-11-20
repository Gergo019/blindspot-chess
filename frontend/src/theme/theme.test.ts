import { DEFAULT_THEME, THEME_STORAGE_KEY, readStoredTheme, storeTheme } from "./theme";

const throwing = {
  getItem: () => {
    throw new DOMException("blocked", "SecurityError");
  },
  setItem: () => {
    throw new DOMException("quota", "QuotaExceededError");
  },
};

describe("theme storage", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("defaults to dark", () => {
    expect(DEFAULT_THEME).toBe("dark");
    expect(readStoredTheme()).toBe("dark");
  });

  it("remembers the theme", () => {
    expect(storeTheme("light")).toBe(true);
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");
    expect(readStoredTheme()).toBe("light");
  });

  it("ignores a stored value that is not a theme", () => {
    localStorage.setItem(THEME_STORAGE_KEY, "sepia");
    expect(readStoredTheme()).toBe("dark");
  });

  it("falls back to dark when storage throws, and storing reports failure instead of throwing", () => {
    expect(readStoredTheme(throwing)).toBe("dark");
    expect(storeTheme("light", throwing)).toBe(false);
  });

  it("copes with no storage at all", () => {
    expect(readStoredTheme(null)).toBe("dark");
    expect(storeTheme("light", null)).toBe(false);
  });
});
