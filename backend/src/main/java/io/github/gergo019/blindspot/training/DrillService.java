package io.github.gergo019.blindspot.training;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.gergo019.blindspot.common.api.NotFoundException;

/**
 * Answers drills (#36): the move is looked up in the drill's stored verdicts, never sent to the engine. The
 * lesson API (ADR 0008) will call this; until then the Cucumber features do.
 */
@Service
public class DrillService {

    /**
     * The result of one answer, with the evidence for it: the move's verdict and refutation line, the
     * engine's first choice, and every accepted move.
     */
    public record Answer(long drillId, String move, String san, boolean correct, BigDecimal drop,
            String refutationUci, String bestMove, List<String> acceptedMoves) {
    }

    private final DrillRepository drills;
    private final DrillAttemptRepository attempts;
    private final Clock clock;

    public DrillService(DrillRepository drills, DrillAttemptRepository attempts, Clock clock) {
        this.drills = drills;
        this.attempts = attempts;
        this.clock = clock;
    }

    /**
     * Records an answer and reschedules the drill (SM-2).
     *
     * @throws IllegalMoveException if the move is not legal in the drill's position; nothing is recorded
     * @throws NotFoundException if there is no such drill
     */
    @Transactional
    public Answer answer(long drillId, String move) {
        Drill drill = drills.findById(drillId).orElseThrow(() -> new NotFoundException("drill", drillId));
        DrillAttempt attempt = drill.answer(move, clock.instant());
        attempts.save(attempt);
        MoveVerdict verdict = drill.verdictFor(attempt.getMoveUci());
        return new Answer(drillId, attempt.getMoveUci(), verdict.san(), attempt.isCorrect(), verdict.drop(),
                verdict.refutationUci(), drill.getSolutionUci(), drill.acceptedMoves());
    }
}
