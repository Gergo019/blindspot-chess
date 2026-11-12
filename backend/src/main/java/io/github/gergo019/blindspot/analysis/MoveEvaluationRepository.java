package io.github.gergo019.blindspot.analysis;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MoveEvaluationRepository extends JpaRepository<MoveEvaluation, Long> {

    List<MoveEvaluation> findByGameIdOrderByPlyAsc(Long gameId);

    /** The user's mistakes and blunders across all their accounts, newest games first. */
    @Query("""
            select m from MoveEvaluation m
            where m.game.account.user.id = :userId
              and m.byUser = true
              and m.classification in (io.github.gergo019.blindspot.analysis.MoveClassification.MISTAKE,
                                       io.github.gergo019.blindspot.analysis.MoveClassification.BLUNDER)
            order by m.game.playedAt desc, m.ply asc
            """)
    List<MoveEvaluation> findMistakesOfUser(@Param("userId") Long userId);
}
