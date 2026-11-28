import type { Theme } from "../theme/contrast";
import { ThemeToggle } from "./ThemeToggle";

export type View = "findings" | "games" | "journey";

type Props = { view: View; onNavigate: (view: View) => void; theme: Theme; onToggleTheme: () => void };

/** The main navigation (ADR 0007, amendment of 2026-11-21): "Drills" became the Journey. */
const ITEMS: { view: View; label: string }[] = [
  { view: "findings", label: "Findings" },
  { view: "games", label: "Games" },
  { view: "journey", label: "Journey" },
];

export function Header({ view, onNavigate, theme, onToggleTheme }: Props) {
  return (
    <header className="header">
      <div className="brand">
        <svg className="brand__mark" viewBox="0 0 32 32" aria-hidden="true">
          <circle cx="16" cy="16" r="10" fill="none" stroke="var(--mistake)" strokeWidth="3" />
          <circle cx="16" cy="16" r="3.5" fill="var(--mistake)" />
        </svg>
        <span className="brand__name">BlindSpot</span>
      </div>
      <nav className="nav" aria-label="Main">
        {ITEMS.map((item) => (
          <button
            key={item.view}
            type="button"
            className="nav__item"
            aria-current={view === item.view ? "page" : undefined}
            onClick={() => {
              onNavigate(item.view);
            }}
          >
            {item.label}
          </button>
        ))}
      </nav>
      <ThemeToggle theme={theme} onToggle={onToggleTheme} />
    </header>
  );
}
