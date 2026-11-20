export function Legend() {
  return (
    <div className="legend">
      <ul className="legend__items">
        <li>
          <span className="swatch swatch--mistake" aria-hidden="true" />
          Your move
        </li>
        <li>
          <span className="swatch swatch--evidence" aria-hidden="true" />
          Evidence line
        </li>
      </ul>
      <p className="legend__hint">
        <kbd>←</kbd> <kbd>→</kbd> step through moves · <kbd>Home</kbd> <kbd>End</kbd> jump to start or end
      </p>
    </div>
  );
}
