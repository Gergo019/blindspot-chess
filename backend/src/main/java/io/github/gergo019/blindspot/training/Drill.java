package io.github.gergo019.blindspot.training;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.common.BaseEntity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A position to solve: find a good-enough move.
 * <ul>
 *   <li>{@link Kind#OWN_GAME}: the position from the user's game where they went wrong.</li>
 *   <li>{@link Kind#TRANSFER}: an unseen Lichess puzzle with the same motif. Solving it
 *       suggests the lesson carried over, not just that the old move was remembered.</li>
 * </ul>
 * Every legal move of the position has a stored verdict from the analysis service
 * ({@link MoveVerdict}, #36, ADR 0008). An answer is correct when its verdict is accepted, so a
 * second safe move counts as well as the engine's first choice; a move without a verdict is not
 * legal and is refused. Reviews are scheduled with a simplified SM-2 algorithm, so positions the
 * user keeps getting wrong come back sooner.
 */
@Entity
@Table(name = "drills")
public class Drill extends BaseEntity {

    static final BigDecimal MIN_EASE = new BigDecimal("1.30");
    static final BigDecimal START_EASE = new BigDecimal("2.50");

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    public enum Kind {
        OWN_GAME,
        TRANSFER
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    /** What an own-game drill asks (ADR 0008). Transfer puzzles use {@link #FIND_SAFE_MOVE} too. */
    public enum Task {
        /** The position before the user's mistake: find a move that keeps the pieces safe. */
        FIND_SAFE_MOVE,
        /** The position after the mistake, the opponent to move: find the winning capture. Not prepared yet. */
        SEE_THE_CAPTURE
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Motif motif;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finding_id")
    private MistakeFinding finding;

    @Column(name = "puzzle_id", length = 10)
    private String puzzleId;

    @Column(name = "puzzle_rating")
    private Integer puzzleRating;

    @Column(nullable = false, length = 100)
    private String fen;

    /** The engine's first choice (the best verdict), for display. Answers are checked against {@link #verdicts}. */
    @Column(name = "solution_uci", nullable = false, length = 5)
    private String solutionUci;

    @Column(name = "verdict_engine_version", nullable = false, length = 30)
    private String verdictEngineVersion;

    @Column(name = "verdict_engine_depth", nullable = false)
    private int verdictEngineDepth;

    @Column(name = "verdict_engine_threads", nullable = false)
    private int verdictEngineThreads;

    @Column(name = "verdict_engine_hash_mb", nullable = false)
    private int verdictEngineHashMb;

    @Column(name = "accept_below", nullable = false, precision = 5, scale = 4)
    private BigDecimal acceptBelow;

    /** Every legal move of {@link #fen}, keyed by UCI. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "drill_move_verdicts", joinColumns = @JoinColumn(name = "drill_id"))
    @MapKeyColumn(name = "move_uci", length = 5)
    private Map<String, MoveVerdict> verdicts = new HashMap<>();

    @Column(nullable = false)
    private int repetitions;

    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(name = "ease_factor", nullable = false, precision = 4, scale = 2)
    private BigDecimal easeFactor = START_EASE;

    @Column(name = "next_review_at", nullable = false)
    private Instant nextReviewAt;

    protected Drill() {
        // for JPA
    }

    /**
     * A "Find a safe move" drill from the user's own game: the position before their mistake, with the
     * verdicts the analysis service gave for it. Due immediately.
     *
     * @throws IllegalArgumentException if the judgement is for another position, has no verdict for the
     *         game's move, or accepts it (sanity rule, ADR 0008: a drill whose premise the engine no longer
     *         supports is never created)
     */
    public static Drill fromFinding(User user, MistakeFinding finding, Judgement judgement, Instant now) {
        String fen = finding.getMoveEvaluation().getFenBefore();
        if (!fen.equals(judgement.fen())) {
            throw new IllegalArgumentException("The judgement is for " + judgement.fen() + ", not " + fen);
        }
        Judgement.Verdict mistake = judgement.verdictFor(finding.getMoveEvaluation().getMoveUci());
        if (mistake == null) {
            throw new IllegalArgumentException("The judgement has no verdict for the game's move "
                    + finding.getMoveEvaluation().getMoveUci());
        }
        if (mistake.accepted()) {
            throw new IllegalArgumentException("The judgement accepts the game's move "
                    + finding.getMoveEvaluation().getMoveUci() + " (drop " + mistake.drop() + ")");
        }
        Drill drill = base(user, finding.getMotif(), Task.FIND_SAFE_MOVE, fen, judgement, now);
        drill.kind = Kind.OWN_GAME;
        drill.finding = finding;
        return drill;
    }

    /**
     * A transfer drill: an unseen Lichess puzzle with the same motif, with the verdicts for its position.
     * Due immediately. The rating lets transfer be measured over several puzzles of comparable difficulty.
     */
    public static Drill transferPuzzle(User user, Motif motif, String puzzleId, int puzzleRating,
            Judgement judgement, Instant now) {
        if (puzzleId == null || puzzleId.isBlank()) {
            throw new IllegalArgumentException("A transfer drill needs a puzzle id");
        }
        if (puzzleRating <= 0) {
            throw new IllegalArgumentException("A transfer drill needs a positive puzzle rating");
        }
        Drill drill = base(user, motif, Task.FIND_SAFE_MOVE, judgement.fen(), judgement, now);
        drill.kind = Kind.TRANSFER;
        drill.puzzleId = puzzleId;
        drill.puzzleRating = puzzleRating;
        return drill;
    }

    private static Drill base(User user, Motif motif, Task task, String fen, Judgement judgement, Instant now) {
        Drill drill = new Drill();
        drill.user = user;
        drill.motif = motif;
        drill.task = task;
        drill.fen = fen;
        drill.solutionUci = judgement.best().uci();
        drill.verdictEngineVersion = judgement.engine().version();
        drill.verdictEngineDepth = judgement.engine().depth();
        drill.verdictEngineThreads = judgement.engine().threads();
        drill.verdictEngineHashMb = judgement.engine().hashMb();
        drill.acceptBelow = judgement.acceptBelow();
        for (Judgement.Verdict verdict : judgement.verdicts()) {
            drill.verdicts.put(verdict.uci(), MoveVerdict.of(verdict));
        }
        drill.nextReviewAt = now;
        return drill;
    }

    /**
     * Records an answer and reschedules the drill. The answer is correct when its stored verdict is
     * accepted. A correct answer is graded 4 ("correct after thinking"), a wrong one 1 ("wrong, but
     * familiar") on the SM-2 scale.
     *
     * @throws IllegalMoveException if the move is not a legal move of the position (it has no verdict);
     *         nothing is recorded and the schedule is unchanged
     */
    public DrillAttempt answer(String moveUci, Instant now) {
        return answer(moveUci, now, null);
    }

    /**
     * As {@link #answer(String, Instant)}, for an answer given inside a lesson: the attempt names the lesson
     * item. Only {@link LessonItem#answer} calls this, after the lesson's own rules.
     */
    DrillAttempt answer(String moveUci, Instant now, LessonItem item) {
        MoveVerdict verdict = verdictFor(moveUci);
        if (verdict == null) {
            throw new IllegalMoveException(moveUci, fen);
        }
        boolean correct = verdict.accepted();
        int quality = correct ? 4 : 1;

        if (correct) {
            repetitions++;
            intervalDays = switch (repetitions) {
                case 1 -> 1;
                case 2 -> 6;
                default -> new BigDecimal(intervalDays).multiply(easeFactor)
                        .setScale(0, RoundingMode.HALF_UP).intValueExact();
            };
        } else {
            repetitions = 0;
            intervalDays = 1;
        }

        // SM-2: EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)), never below 1.3
        int miss = 5 - quality;
        BigDecimal delta = new BigDecimal("0.1")
                .subtract(new BigDecimal(miss).multiply(new BigDecimal("0.08").add(new BigDecimal(miss).multiply(new BigDecimal("0.02")))));
        easeFactor = easeFactor.add(delta).max(MIN_EASE).setScale(2, RoundingMode.HALF_UP);

        nextReviewAt = now.plus(Duration.ofDays(intervalDays));
        return new DrillAttempt(this, normalise(moveUci), correct, now, item);
    }

    /** The stored verdict for a move (UCI, case and surrounding spaces ignored), or null if it is not legal here. */
    public MoveVerdict verdictFor(String moveUci) {
        String uci = normalise(moveUci);
        return uci == null ? null : verdicts.get(uci);
    }

    /** Every legal move with its verdict, keyed by UCI. */
    public Map<String, MoveVerdict> getVerdicts() {
        return Collections.unmodifiableMap(verdicts);
    }

    /** The accepted moves, in UCI order. */
    public List<String> acceptedMoves() {
        return verdicts.entrySet().stream().filter(e -> e.getValue().accepted()).map(Map.Entry::getKey).sorted()
                .toList();
    }

    private static String normalise(String moveUci) {
        if (moveUci == null) {
            return null;
        }
        String uci = moveUci.strip().toLowerCase(Locale.ROOT);
        return Judgement.UCI.matcher(uci).matches() ? uci : null;
    }

    public boolean isDue(Instant now) {
        return !nextReviewAt.isAfter(now);
    }

    public User getUser() {
        return user;
    }

    public Kind getKind() {
        return kind;
    }

    public Task getTask() {
        return task;
    }

    public Judgement.EngineSettings getVerdictEngine() {
        return new Judgement.EngineSettings(verdictEngineVersion, verdictEngineDepth, verdictEngineThreads,
                verdictEngineHashMb);
    }

    public BigDecimal getAcceptBelow() {
        return acceptBelow;
    }

    public Motif getMotif() {
        return motif;
    }

    public MistakeFinding getFinding() {
        return finding;
    }

    public String getPuzzleId() {
        return puzzleId;
    }

    public Integer getPuzzleRating() {
        return puzzleRating;
    }

    public String getFen() {
        return fen;
    }

    public String getSolutionUci() {
        return solutionUci;
    }

    public int getRepetitions() {
        return repetitions;
    }

    public int getIntervalDays() {
        return intervalDays;
    }

    public BigDecimal getEaseFactor() {
        return easeFactor;
    }

    public Instant getNextReviewAt() {
        return nextReviewAt;
    }
}
