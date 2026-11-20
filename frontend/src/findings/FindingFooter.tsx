import type { FindingDetail } from "../api/types";

/** How the finding was produced (product rule 4, ADR 0007 side panel item 5). */
export function FindingFooter({ detail }: { detail: FindingDetail }) {
  const { engine } = detail;
  const settings = [`depth ${String(engine.depth)}`];
  if (engine.threads !== null)
    settings.push(`${String(engine.threads)} thread${engine.threads === 1 ? "" : "s"}`);
  if (engine.hashMb !== null) settings.push(`${String(engine.hashMb)} MB hash`);
  return (
    <footer className="finding-footer">
      {engine.version}, {settings.join(", ")} · detector {detail.detectorVersion}
    </footer>
  );
}
