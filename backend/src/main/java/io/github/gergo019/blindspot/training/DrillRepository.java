package io.github.gergo019.blindspot.training;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.gergo019.blindspot.analysis.Motif;

public interface DrillRepository extends JpaRepository<Drill, Long> {

    List<Drill> findByUserIdAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(Long userId, Instant now);

    /** The user's drills of a motif and kind, oldest first: the candidate positions of a lesson. */
    List<Drill> findByUserIdAndMotifAndKindOrderByIdAsc(Long userId, Motif motif, Drill.Kind kind);

    boolean existsByUserIdAndFindingId(Long userId, Long findingId);

    boolean existsByFindingIdAndTask(Long findingId, Drill.Task task);

    boolean existsByUserIdAndPuzzleId(Long userId, String puzzleId);
}
