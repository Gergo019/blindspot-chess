import { useState } from "react";
import { Board } from "./components/Board";
import { Header, type View } from "./components/Header";
import { Legend } from "./components/Legend";
import { SidePanel } from "./components/SidePanel";
import { useTheme } from "./theme/theme";

export function App() {
  const { theme, toggleTheme } = useTheme();
  const [view, setView] = useState<View>("findings");

  return (
    <div className="app">
      <Header view={view} onNavigate={setView} theme={theme} onToggleTheme={toggleTheme} />
      <main className="layout">
        <section className="board-column" aria-label="Board">
          <Board />
          <Legend />
        </section>
        <SidePanel view={view} />
      </main>
    </div>
  );
}
