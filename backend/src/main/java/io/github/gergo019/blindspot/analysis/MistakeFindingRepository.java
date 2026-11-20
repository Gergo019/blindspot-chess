package io.github.gergo019.blindspot.analysis;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * A page of the user's findings, newest game first, with the move and the game fetched in the same
     * query (no N+1). Scoped by user id, not user name, so a signed-in user's id can be passed later.
     */
    @Query(value = """
            select f from MistakeFinding f
              join fetch f.moveEvaluation m
              join fetch m.game g
            where g.account.user.id = :userId
            order by g.playedAt desc, m.ply desc, f.id desc
            """, countQuery = """
            select count(f) from MistakeFinding f
            where f.moveEvaluation.game.account.user.id = :userId
            """)
    Page<MistakeFinding> findPageForUser(@Param("userId") Long userId, Pageable pageable);

    /** {@link #findPageForUser} for one motif. */
    @Query(value = """
            select f from MistakeFinding f
              join fetch f.moveEvaluation m
              join fetch m.game g
            where g.account.user.id = :userId and f.motif = :motif
            order by g.playedAt desc, m.ply desc, f.id desc
            """, countQuery = """
            select count(f) from MistakeFinding f
            where f.moveEvaluation.game.account.user.id = :userId and f.motif = :motif
            """)
    Page<MistakeFinding> findPageForUserAndMotif(@Param("userId") Long userId, @Param("motif") Motif motif,
            Pageable pageable);

    /** One finding of the user, with its move and game; empty if it does not exist or belongs to someone else. */
    @Query("""
            select f from MistakeFinding f
              join fetch f.moveEvaluation m
              join fetch m.game g
            where f.id = :id and g.account.user.id = :userId
            """)
    Optional<MistakeFinding> findForUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * For each motif, in how many of the given games it occurred: a motif counts at most once per game
     * (README, "Report"), however many findings the game has.
     */
    @Query("""
            select new io.github.gergo019.blindspot.analysis.MotifGameCount(f.motif, count(distinct m.game.id))
            from MistakeFinding f join f.moveEvaluation m
            where m.game.id in :gameIds
            group by f.motif
            """)
    List<MotifGameCount> countGamesByMotif(@Param("gameIds") List<Long> gameIds);
}
