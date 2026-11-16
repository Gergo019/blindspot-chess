package io.github.gergo019.blindspot.game;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;

public interface GameRepository extends JpaRepository<Game, Long> {

    boolean existsByAccountIdAndExternalId(Long accountId, String externalId);

    List<Game> findByAccountIdOrderByPlayedAtDesc(Long accountId, Pageable pageable);

    /** The background job's queue: newest games first, because the pattern report reads the last 20. */
    List<Game> findByAnalysisStatusOrderByPlayedAtDesc(AnalysisStatus status, Pageable pageable);

    /** Candidates for stuck-analysis recovery: RUNNING games whose attempt started before {@code cutoff}. */
    List<Game> findByAnalysisStatusAndAnalysisStartedAtBefore(AnalysisStatus status, Instant cutoff);

    /**
     * Claims a PENDING game for analysis: PENDING -> RUNNING, one more
     * attempt, started now. Returns 1 if this caller claimed it, 0 if the game
     * was not PENDING (already claimed, done or failed). Run it inside a
     * transaction and analyse the game only on 1.
     *
     * <p>A query and not {@link Game#startAnalysis}: the entity method checks
     * the status in memory. Under READ COMMITTED, two workers can both load
     * the game as PENDING and both commit RUNNING. This UPDATE checks the
     * status in the database, on the row it locks; a second worker's UPDATE
     * waits for the first to commit, re-checks the condition against the
     * committed row, and changes nothing. Unlike {@code @Version} it needs no
     * retry, and unlike {@code SELECT ... FOR UPDATE SKIP LOCKED} it holds no
     * lock beyond this short transaction, never across the HTTP call. The
     * transition matches {@link Game#startAnalysis} for a PENDING game; keep
     * them in step. {@code updatedAt} is set here because a bulk update skips
     * the entity's {@code @PreUpdate}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Game g
               set g.analysisStatus = :running,
                   g.analysisAttempts = g.analysisAttempts + 1,
                   g.analysisStartedAt = :now,
                   g.updatedAt = :now
             where g.id = :id and g.analysisStatus = :pending
            """)
    int claimForAnalysis(@Param("id") long id, @Param("now") Instant now,
            @Param("pending") AnalysisStatus pending, @Param("running") AnalysisStatus running);

    /** {@link #claimForAnalysis(long, Instant, AnalysisStatus, AnalysisStatus)} for PENDING -> RUNNING. */
    default boolean claimForAnalysis(long id, Instant now) {
        return claimForAnalysis(id, now, AnalysisStatus.PENDING, AnalysisStatus.RUNNING) == 1;
    }
}
