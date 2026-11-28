/**
 * A move and the evidence line that follows it as chips (ADR 0007): the first in orange, the rest in blue,
 * each showing the position after it. Chip `i` is step `i + 1`. Used by the findings panel and by
 * PuzzleBoard's review.
 */
export function LineChips({
  titleId,
  labels,
  step,
  onStep,
}: {
  /** The id of the heading that names the list. */
  titleId: string;
  labels: readonly string[];
  /** The current step, or -1 when the board shows something else. */
  step: number;
  onStep: (step: number) => void;
}) {
  return (
    <ol className="chips" aria-labelledby={titleId}>
      {labels.map((label, index) => (
        <li key={`${String(index)}-${label}`}>
          <button
            type="button"
            className={index === 0 ? "chip chip--mistake" : "chip chip--evidence"}
            aria-current={step === index + 1 ? "step" : undefined}
            onClick={() => {
              onStep(index + 1);
            }}
          >
            <span
              className={index === 0 ? "swatch swatch--mistake" : "swatch swatch--evidence"}
              aria-hidden="true"
            />
            {label}
          </button>
        </li>
      ))}
    </ol>
  );
}
