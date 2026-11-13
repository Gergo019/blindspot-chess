-- Background analysis (#22): when the current attempt started, and how many
-- attempts there have been, so a game left RUNNING by a crash or restart can
-- be found and put back to PENDING once, then marked FAILED.
ALTER TABLE games
    ADD COLUMN analysis_attempts   INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN analysis_started_at TIMESTAMPTZ;

-- Games already RUNNING before this migration count as one attempt that
-- started at their last update.
UPDATE games
SET analysis_attempts   = 1,
    analysis_started_at = updated_at
WHERE analysis_status = 'RUNNING';

ALTER TABLE games
    ADD CONSTRAINT games_analysis_attempts_ck CHECK (analysis_attempts >= 0),
    ADD CONSTRAINT games_running_has_start_ck
        CHECK (analysis_status <> 'RUNNING' OR analysis_started_at IS NOT NULL);
