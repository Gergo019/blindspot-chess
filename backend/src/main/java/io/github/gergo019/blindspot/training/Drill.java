package io.github.gergo019.blindspot.training;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A position to solve: find the right move.
 * <ul>
 *   <li>{@link Kind#OWN_GAME}: the position from the user's game where they went wrong.</li>
 *   <li>{@link Kind#TRANSFER}: an unseen Lichess puzzle with the same motif. Solving it
 *       suggests the lesson carried over, not just that the old move was remembered.</li>
 * </ul>
 * Reviews are scheduled with a simplified SM-2 algorithm, so positions the
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

    @Column(name = "solution_uci", nullable = false, length = 5)
    private String solutionUci;

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

    /** A drill from the user's own game: the position before their mistake. Due immediately. */
    public static Drill fromFinding(User user, MistakeFinding finding, Instant now) {
        Drill drill = base(user, finding.getMotif(), finding.getMoveEvaluation().getFenBefore(),
                finding.getMoveEvaluation().getBestMoveUci(), now);
        drill.kind = Kind.OWN_GAME;
        drill.finding = finding;
        return drill;
    }

    /**
     * A transfer drill: an unseen Lichess puzzle with the same motif. Due immediately.
     * The rating lets transfer be measured over several puzzles of comparable difficulty.
     */
    public static Drill transferPuzzle(User user, Motif motif, String puzzleId, int puzzleRating, String fen,
            String solutionUci, Instant now) {
        if (puzzleId == null || puzzleId.isBlank()) {
            throw new IllegalArgumentException("A transfer drill needs a puzzle id");
        }
        if (puzzleRating <= 0) {
            throw new IllegalArgumentException("A transfer drill needs a positive puzzle rating");
        }
        Drill drill = base(user, motif, fen, solutionUci, now);
        drill.kind = Kind.TRANSFER;
        drill.puzzleId = puzzleId;
        drill.puzzleRating = puzzleRating;
        return drill;
    }

    private static Drill base(User user, Motif motif, String fen, String solutionUci, Instant now) {
        Drill drill = new Drill();
        drill.user = user;
        drill.motif = motif;
        drill.fen = fen;
        drill.solutionUci = solutionUci;
        drill.nextReviewAt = now;
        return drill;
    }

    /**
     * Records an answer and reschedules the drill. A correct answer is graded
     * 4 ("correct after thinking"), a wrong one 1 ("wrong, but familiar") on the SM-2 scale.
     */
    public DrillAttempt answer(String moveUci, Instant now) {
        boolean correct = solutionUci.equals(moveUci);
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
        return new DrillAttempt(this, moveUci, correct, now);
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
