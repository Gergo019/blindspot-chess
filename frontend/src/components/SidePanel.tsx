type View = "findings" | "games";

const EMPTY: Record<View, { title: string; body: string }> = {
  findings: {
    title: "No findings yet",
    body: "Once your games are imported and analysed, the mistakes you repeat appear here, each with the moves that prove it.",
  },
  games: {
    title: "No games yet",
    body: "Imported games and their analysis status will be listed here.",
  },
};

export function SidePanel({ view }: { view: View }) {
  const { title, body } = EMPTY[view];
  return (
    <aside className="panel" aria-label={view === "findings" ? "Findings" : "Games"}>
      <section className="card empty-state">
        <h2 className="card__title">{title}</h2>
        <p className="card__body">{body}</p>
      </section>
    </aside>
  );
}
