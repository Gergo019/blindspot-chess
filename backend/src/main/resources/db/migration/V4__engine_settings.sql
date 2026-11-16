-- #33: besides the engine version and depth, the number of search threads and the
-- hash table size change Stockfish's output (ADR 0002). Record both with every
-- evaluation, so a stored result says exactly how to reproduce it.
--
-- Evaluations stored before V4 have no record of them: NULL means "not recorded",
-- never a default. The service has run with 1 thread and 64 MB unless
-- ENGINE_THREADS or ENGINE_HASH_MB were changed, but that cannot be proven per row.
ALTER TABLE move_evaluations
    ADD COLUMN engine_threads INTEGER,
    ADD COLUMN engine_hash_mb INTEGER;

ALTER TABLE move_evaluations
    ADD CONSTRAINT move_evaluations_engine_threads_positive CHECK (engine_threads IS NULL OR engine_threads > 0),
    ADD CONSTRAINT move_evaluations_engine_hash_mb_positive CHECK (engine_hash_mb IS NULL OR engine_hash_mb > 0),
    ADD CONSTRAINT move_evaluations_engine_settings_together
        CHECK ((engine_threads IS NULL) = (engine_hash_mb IS NULL));

COMMENT ON COLUMN move_evaluations.engine_threads IS 'Stockfish Threads; NULL only for rows stored before V4';
COMMENT ON COLUMN move_evaluations.engine_hash_mb IS 'Stockfish Hash in MB; NULL only for rows stored before V4';
