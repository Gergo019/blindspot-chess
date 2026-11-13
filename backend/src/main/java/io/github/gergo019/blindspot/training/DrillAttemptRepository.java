package io.github.gergo019.blindspot.training;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DrillAttemptRepository extends JpaRepository<DrillAttempt, Long> {

    List<DrillAttempt> findByDrillIdOrderByAttemptedAtDesc(Long drillId);
}
