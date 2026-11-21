package io.github.gergo019.blindspot.training;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DrillPreparationRepository extends JpaRepository<DrillPreparation, Long> {

    Optional<DrillPreparation> findByFindingIdAndTask(Long findingId, Drill.Task task);

    /**
     * Findings that need a "Find a safe move" drill now, newest game first: no drill yet, and not skipped
     * or waiting for a later retry. Reads the analysis context's tables, but only their ids and order.
     */
    @Query(value = """
            SELECT f.id
            FROM mistake_findings f
            JOIN move_evaluations e ON e.id = f.move_evaluation_id
            JOIN games g ON g.id = e.game_id
            WHERE NOT EXISTS (SELECT 1 FROM drills d WHERE d.finding_id = f.id AND d.task = 'FIND_SAFE_MOVE')
              AND NOT EXISTS (SELECT 1 FROM drill_preparations p
                              WHERE p.finding_id = f.id AND p.task = 'FIND_SAFE_MOVE'
                                AND (p.status = 'SKIPPED' OR p.next_attempt_at > :now))
            ORDER BY g.played_at DESC, e.ply, f.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findFindingsNeedingASafeMoveDrill(@Param("now") Instant now, @Param("limit") int limit);
}
