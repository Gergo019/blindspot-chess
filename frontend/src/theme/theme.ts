import { useCallback, useState } from "react";
import type { Theme } from "./contrast";

export const THEME_STORAGE_KEY = "blindspot.theme";
export const DEFAULT_THEME: Theme = "dark";

type ReadableStorage = Pick<Storage, "getItem">;
type WritableStorage = Pick<Storage, "setItem">;

function isTheme(value: unknown): value is Theme {
  return value === "dark" || value === "light";
}

/** localStorage, or null where even touching it throws (some browsers with site data blocked). */
function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/**
 * The remembered theme, or dark. Storage can be missing, throw (private mode, blocked site data,
 * sandboxed frames) or hold junk; none of that may break the page, so all of it falls back to dark.
 */
export function readStoredTheme(storage: ReadableStorage | null = browserStorage()): Theme {
  if (storage === null) return DEFAULT_THEME;
  try {
    const value = storage.getItem(THEME_STORAGE_KEY);
    return isTheme(value) ? value : DEFAULT_THEME;
  } catch {
    return DEFAULT_THEME;
  }
}

/** Remembers the theme; returns false when storage is unavailable (the theme still applies). */
export function storeTheme(theme: Theme, storage: WritableStorage | null = browserStorage()): boolean {
  if (storage === null) return false;
  try {
    storage.setItem(THEME_STORAGE_KEY, theme);
    return true;
  } catch {
    return false;
  }
}

export function applyTheme(theme: Theme): void {
  document.documentElement.dataset["theme"] = theme;
}

/** The current theme as set on <html> (index.html applies the stored one before the first paint). */
export function currentTheme(): Theme {
  const value = document.documentElement.dataset["theme"];
  return isTheme(value) ? value : DEFAULT_THEME;
}

export function useTheme(): { theme: Theme; toggleTheme: () => void } {
  const [theme, setTheme] = useState<Theme>(currentTheme);
  const toggleTheme = useCallback(() => {
    setTheme((previous) => {
      const next: Theme = previous === "dark" ? "light" : "dark";
      applyTheme(next);
      storeTheme(next);
      return next;
    });
  }, []);
  return { theme, toggleTheme };
}
