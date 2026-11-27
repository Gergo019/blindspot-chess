package io.github.gergo019.blindspot.training;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.analysis.AnalysisClient;
import io.github.gergo019.blindspot.analysis.AnalysisFailedException;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.JudgeRequest;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.JudgeResponse;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.VerdictDto;
import io.github.gergo019.blindspot.analysis.MistakeFinding;

import jakarta.persistence.EntityManager;

/**
 * Prepares the "Find a safe move" drill of one finding (#36, ADR 0008): asks the analysis service to judge
 * every legal move of the position before the mistake, and stores the drill with all its verdicts in one
 * transaction, so a half-prepared drill never exists.
 *
 * <p>The HTTP call takes seconds and runs outside any transaction, between two short ones (as in
 * {@code AnalysisService}). A failed call stores no verdicts at all; what happens to the finding depends
 * on the failure's kind:
 * <ul>
 *   <li>time budget exceeded, read timeout, or a response that cannot be stored: no drill; a
 *       {@link DrillPreparation} says to try again after {@code retryAfter};</li>
 *   <li>the position cannot be judged (422), or the verdicts accept the game's mistake: skipped for good;</li>
 *   <li>the service is unavailable: nothing is recorded, and the job stops its run.</li>
 * </ul>
 */
@Service
public class DrillPreparationService {

    private static final Logger log = LoggerFactory.getLogger(DrillPreparationService.class);

    /** What one call of {@link #prepare} did. */
    public enum Outcome {
        /** The drill and its verdicts are stored. */
        PREPARED,
        /** No drill; tried again later (budget exceeded, timeout, or a response that cannot be stored). */
        RETRY_LATER,
        /** No drill, and the finding is never tried again (position rejected, or the mistake is accepted). */
        SKIPPED,
        /** The analysis service is unavailable; nothing recorded. The job stops its run. */
        SERVICE_UNAVAILABLE,
        /** The finding is gone, or it already has its drill. */
        NOT_NEEDED
    }

    /** What the job needs to know about the finding, read in a short transaction before the call. */
    private record Position(long findingId, String fen, String mistakeUci) {
    }

    private final AnalysisClient client;
    private final EntityManager entityManager;
    private final DrillRepository drills;
    private final DrillPreparationRepository preparations;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DrillPreparationProperties properties;

    public DrillPreparationService(AnalysisClient client, EntityManager entityManager, DrillRepository drills,
            DrillPreparationRepository preparations, PlatformTransactionManager transactionManager, Clock clock,
            DrillPreparationProperties properties) {
        this.client = client;
        this.entityManager = entityManager;
        this.drills = drills;
        this.preparations = preparations;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
    }

    public Outcome prepare(long findingId) {
        Position position = tx.execute(status -> {
            MistakeFinding finding = entityManager.find(MistakeFinding.class, findingId);
            if (finding == null || drills.existsByFindingIdAndTask(findingId, Drill.Task.FIND_SAFE_MOVE)) {
                return null;
            }
            return new Position(findingId, finding.getMoveEvaluation().getFenBefore(),
                    finding.getMoveEvaluation().getMoveUci());
        });
        if (position == null) {
            return Outcome.NOT_NEEDED;
        }

        JudgeResponse response;
        try {
            response = client.judge(new JudgeRequest(position.fen()));
        } catch (AnalysisFailedException e) {
            return switch (e.getKind()) {
                case UNAVAILABLE -> {
                    log.warn("Drill for finding {} not prepared: analysis service unavailable: {}", findingId,
                            e.getMessage());
                    yield Outcome.SERVICE_UNAVAILABLE;
                }
                case REJECTED -> skip(findingId, "Position rejected by the analysis service: " + e.getMessage());
                case BUDGET_EXCEEDED, TIMEOUT, INVALID_RESPONSE -> retryLater(findingId, e.getKind() + ": "
                        + e.getMessage());
            };
        }

        Judgement judgement;
        try {
            judgement = judgement(response);
        } catch (RuntimeException e) {
            log.error("Drill for finding {}: the /judge response cannot be stored (likely a contract mismatch): {}",
                    findingId, e.getMessage());
            return retryLater(findingId, "INVALID_RESPONSE: " + e.getMessage());
        }
        if (!position.fen().equals(judgement.fen())) {
            return retryLater(findingId, "INVALID_RESPONSE: judged " + judgement.fen() + " instead of "
                    + position.fen());
        }
        Judgement.Verdict mistake = judgement.verdictFor(position.mistakeUci());
        if (mistake == null) {
            return retryLater(findingId, "INVALID_RESPONSE: no verdict for the game's move " + position.mistakeUci());
        }
        if (mistake.accepted()) {
            // Sanity rule (ADR 0008): the engine no longer supports the drill's premise.
            log.warn("Drill for finding {} skipped: the verdicts accept the game's move {} (drop {})", findingId,
                    position.mistakeUci(), mistake.drop());
            return skip(findingId, "The verdicts accept the game's move " + position.mistakeUci() + " (drop "
                    + mistake.drop() + ", threshold " + judgement.acceptBelow() + ")");
        }
        try {
            return tx.execute(status -> store(findingId, judgement));
        } catch (DataIntegrityViolationException e) {
            // Another run stored the drill first (unique finding and task), or a value the database refuses.
            if (Boolean.TRUE.equals(tx.execute(s -> drills.existsByFindingIdAndTask(findingId,
                    Drill.Task.FIND_SAFE_MOVE)))) {
                return Outcome.NOT_NEEDED;
            }
            return retryLater(findingId, "INVALID_RESPONSE: cannot be stored: " + e.getMostSpecificCause().getMessage());
        }
    }

    private Outcome store(long findingId, Judgement judgement) {
        MistakeFinding finding = entityManager.find(MistakeFinding.class, findingId);
        if (finding == null) {
            return Outcome.NOT_NEEDED;
        }
        User user = finding.getMoveEvaluation().getGame().getAccount().getUser();
        drills.saveAndFlush(Drill.fromFinding(user, finding, judgement, clock.instant()));
        preparations.findByFindingIdAndTask(findingId, Drill.Task.FIND_SAFE_MOVE).ifPresent(preparations::delete);
        log.info("Drill prepared for finding {}: {} legal moves, {} accepted ({}, depth {})", findingId,
                judgement.verdicts().size(), judgement.verdicts().stream().filter(Judgement.Verdict::accepted).count(),
                judgement.engine().version(), judgement.engine().depth());
        return Outcome.PREPARED;
    }

    private Outcome retryLater(long findingId, String error) {
        Instant next = clock.instant().plus(properties.retryAfter());
        record(findingId, preparation -> preparation.retryAt(next, error));
        log.warn("Drill for finding {} not prepared; retried after {}: {}", findingId, next, error);
        return Outcome.RETRY_LATER;
    }

    private Outcome skip(long findingId, String reason) {
        record(findingId, preparation -> preparation.skip(reason));
        log.warn("Drill for finding {} skipped for good: {}", findingId, reason);
        return Outcome.SKIPPED;
    }

    private void record(long findingId, java.util.function.Consumer<DrillPreparation> change) {
        tx.executeWithoutResult(status -> {
            MistakeFinding finding = entityManager.find(MistakeFinding.class, findingId);
            if (finding == null) {
                return;
            }
            DrillPreparation preparation = preparations.findByFindingIdAndTask(findingId, Drill.Task.FIND_SAFE_MOVE)
                    .orElseGet(() -> new DrillPreparation(finding, Drill.Task.FIND_SAFE_MOVE));
            change.accept(preparation);
            preparations.save(preparation);
        });
    }

    /** The response as a checked {@link Judgement}; throws if it breaks the contract. */
    static Judgement judgement(JudgeResponse response) {
        if (response.engine() == null) {
            throw new IllegalArgumentException("The /judge response has no engine");
        }
        var engine = new Judgement.EngineSettings(response.engine().name() + " " + response.engine().version(),
                response.engine().depth(), response.engine().threads(), response.engine().hashMb());
        List<Judgement.Verdict> verdicts = response.verdicts().stream().map(DrillPreparationService::verdict).toList();
        return new Judgement(engine, response.fen(), response.acceptBelow(), verdicts);
    }

    private static Judgement.Verdict verdict(VerdictDto dto) {
        Integer cp = dto.score() == null ? null : dto.score().cp();
        Integer mate = dto.score() == null ? null : dto.score().mate();
        return new Judgement.Verdict(dto.uci(), dto.san(), cp, mate, dto.drop(), dto.accepted(), dto.refutationUci());
    }
}
