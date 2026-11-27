-- A drill belongs to at most one lesson (#89, decided by Gergo). V6 only kept a position from repeating
-- within a lesson (unique (assignment_id, drill_id)); that "unused" means unused across all of a user's
-- lessons was a domain rule alone. Drills are per user, so this is the same rule, held by the database.
ALTER TABLE lesson_items
    ADD CONSTRAINT lesson_items_drill_uq UNIQUE (drill_id);

-- The unique constraint's index serves the lookups and the cascade from drills.
DROP INDEX lesson_items_drill_id_idx;
