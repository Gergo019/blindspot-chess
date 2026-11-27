package io.github.gergo019.blindspot.training;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The stored verdict for one legal move of a drill's position (table {@code drill_move_verdicts}, keyed by
 * the move in UCI). {@code drop} is the loss in the mover's winning chances against the best move; the
 * refutation is the engine's line after the move, the board evidence for a wrong answer (product rule 2).
 */
@Embeddable
public record MoveVerdict(
        @Column(name = "move_san", nullable = false, length = 10) String san,
        @Column(name = "score_cp") Integer scoreCp,
        @Column(name = "score_mate") Integer scoreMate,
        @Column(name = "chances_drop", nullable = false, precision = 5, scale = 4) BigDecimal drop,
        @Column(nullable = false) boolean accepted,
        @Column(name = "refutation_uci", nullable = false) String refutationUci) {

    static MoveVerdict of(Judgement.Verdict verdict) {
        return new MoveVerdict(verdict.san(), verdict.scoreCp(), verdict.scoreMate(), verdict.drop(),
                verdict.accepted(), verdict.refutationUci());
    }
}
