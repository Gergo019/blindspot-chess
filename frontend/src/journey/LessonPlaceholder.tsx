/** The lesson route until its screen exists (#96): says so, and leads back to the map. */
export function LessonPlaceholder({ navigate }: { navigate: (path: string) => void }) {
  return (
    <main className="layout layout--single">
      <section className="card" aria-labelledby="lesson-title">
        <h2 id="lesson-title" className="card__title">
          Lesson
        </h2>
        <p className="card__body">This lesson screen is built in #96.</p>
        <p className="card__body">
          <a
            href="/journey"
            onClick={(event) => {
              event.preventDefault();
              navigate("/journey");
            }}
          >
            Back to the journey
          </a>
        </p>
      </section>
    </main>
  );
}
