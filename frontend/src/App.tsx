import { EvidenceBoard } from "./components/EvidenceBoard";
import { Header, type View } from "./components/Header";
import { JourneyScreen } from "./journey/JourneyScreen";
import { LessonScreen } from "./lesson/LessonScreen";
import { Legend } from "./components/Legend";
import { SidePanel } from "./components/SidePanel";
import { START_FEN } from "./chess/notation";
import { FindingsScreen } from "./findings/FindingsScreen";
import { useRoute } from "./router";
import { useTheme } from "./theme/theme";

export function App() {
  const { theme, toggleTheme } = useTheme();
  const { route, navigate } = useRoute();
  const view: View =
    route.view === "games"
      ? "games"
      : route.view === "journey" || route.view === "lesson"
        ? "journey"
        : "findings";

  return (
    <div className="app">
      <Header
        view={view}
        onNavigate={(next) => {
          navigate(`/${next}`);
        }}
        theme={theme}
        onToggleTheme={toggleTheme}
      />
      {route.view === "findings" ? (
        <FindingsScreen findingId={route.findingId} navigate={navigate} />
      ) : route.view === "journey" ? (
        <JourneyScreen navigate={navigate} />
      ) : route.view === "lesson" ? (
        <LessonScreen
          key={`${route.unit}/${String(route.number)}`}
          unit={route.unit}
          number={route.number}
          navigate={navigate}
        />
      ) : route.view === "games" ? (
        <main className="layout">
          <section className="board-column" aria-label="Board">
            <EvidenceBoard
              view={{ fen: START_FEN, orientation: "white", arrows: [], marks: [] }}
              label="Chess board in the starting position"
            />
            <Legend />
          </section>
          <SidePanel view="games" />
        </main>
      ) : (
        <main className="layout layout--single">
          <section className="card">
            <h2 className="card__title">Page not found</h2>
            <p className="card__body">
              <a
                href="/findings"
                onClick={(event) => {
                  event.preventDefault();
                  navigate("/findings");
                }}
              >
                Go to your findings
              </a>
            </p>
          </section>
        </main>
      )}
    </div>
  );
}
