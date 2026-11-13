package io.github.gergo019.blindspot.analysis;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MistakeFindingRepository extends JpaRepository<MistakeFinding, Long> {

    /**
     * The user's findings for one motif, newest games first. Whether they count
     * as a recurring pattern is decided by the caller, from how many distinct
     * games they come from (see the README's evidence thresholds).
     */
    @Query("""
            select f from MistakeFinding f
            where f.moveEvaluation.game.account.user.id = :userId
              and f.motif = :motif
            order by f.moveEvaluation.game.playedAt desc
            """)
    List<MistakeFinding> findByUserAndMotif(@Param("userId") Long userId, @Param("motif") Motif motif);

    /**
     * Distinct games among the given ones in which the motif occurred: a motif
     * counts at most once per game when deciding whether it is a pattern.
     */
    @Query("""
            select distinct f.moveEvaluation.game.id from MistakeFinding f
            where f.motif = :motif
              and f.moveEvaluation.game.id in :gameIds
            """)
    List<Long> findGameIdsWithMotif(@Param("motif") Motif motif, @Param("gameIds") List<Long> gameIds);
}
