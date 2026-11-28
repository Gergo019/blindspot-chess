import { useEffect } from "react";

const KEYS = ["ArrowLeft", "ArrowRight", "Home", "End"];

/**
 * Steps through moves with the arrow keys, Home and End anywhere on the page (the findings screen and
 * PuzzleBoard's review). Keys typed into a field, and keys with a modifier, are left alone.
 */
export function useStepKeys(enabled: boolean, onKey: (key: string) => void): void {
  useEffect(() => {
    if (!enabled) return;
    const listener = (event: KeyboardEvent) => {
      if (!KEYS.includes(event.key)) return;
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
      const target = event.target as HTMLElement | null;
      if (target?.closest("input, textarea, select, [contenteditable='true']")) return;
      event.preventDefault();
      onKey(event.key);
    };
    window.addEventListener("keydown", listener);
    return () => {
      window.removeEventListener("keydown", listener);
    };
  }, [enabled, onKey]);
}
