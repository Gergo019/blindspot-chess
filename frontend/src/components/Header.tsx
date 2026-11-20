import type { Theme } from "../theme/contrast";
import { ThemeToggle } from "./ThemeToggle";

export type View = "findings" | "games";

type Props = { view: View; onNavigate: (view: View) => void; theme: Theme; onToggleTheme: () => void };

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
        <button
          type="button"
          className="nav__item"
          aria-current={view === "findings" ? "page" : undefined}
          onClick={() => {
            onNavigate("findings");
          }}
        >
          Findings
        </button>
        <button
          type="button"
          className="nav__item"
          aria-current={view === "games" ? "page" : undefined}
          onClick={() => {
            onNavigate("games");
          }}
        >
          Games
        </button>
        <button type="button" className="nav__item" disabled title="Available once drills exist">
          Drills
        </button>
      </nav>
      <ThemeToggle theme={theme} onToggle={onToggleTheme} />
    </header>
  );
}
