-- The checked facts a finding rests on, as reported by the analysis service's
-- detector (for HANGING_PIECE: piece, square, capturedBy, materialLost,
-- movedPiece, movedFrom, movedTo). They are the start of the typed claims of
-- ADR 0003. Existing rows (none in production yet) get an empty object.
ALTER TABLE mistake_findings
    ADD COLUMN facts JSONB NOT NULL DEFAULT '{}';
