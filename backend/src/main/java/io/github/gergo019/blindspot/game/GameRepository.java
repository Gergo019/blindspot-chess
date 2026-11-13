package io.github.gergo019.blindspot.game;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;

public interface GameRepository extends JpaRepository<Game, Long> {

    boolean existsByAccountIdAndExternalId(Long accountId, String externalId);

    List<Game> findByAccountIdOrderByPlayedAtDesc(Long accountId, Pageable pageable);

    List<Game> findByAnalysisStatusOrderByPlayedAtAsc(AnalysisStatus status, Pageable pageable);

    /** Candidates for stuck-analysis recovery: RUNNING games whose attempt started before {@code cutoff}. */
    List<Game> findByAnalysisStatusAndAnalysisStartedAtBefore(AnalysisStatus status, Instant cutoff);
}
