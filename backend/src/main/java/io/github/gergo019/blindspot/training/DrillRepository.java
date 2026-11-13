package io.github.gergo019.blindspot.training;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DrillRepository extends JpaRepository<Drill, Long> {

    List<Drill> findByUserIdAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(Long userId, Instant now);

    boolean existsByUserIdAndFindingId(Long userId, Long findingId);

    boolean existsByUserIdAndPuzzleId(Long userId, String puzzleId);
}
