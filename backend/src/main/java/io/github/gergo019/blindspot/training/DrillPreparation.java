package io.github.gergo019.blindspot.training;

import java.time.Instant;

import io.github.gergo019.blindspot.analysis.MistakeFinding;
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
 * A finding whose drill could not be prepared yet, or never can be (#36). A finding with neither a drill
 * nor a preparation is simply waiting for {@link DrillPreparationJob}; this row exists only after an
 * attempt that did not produce a drill, and is deleted when one does.
 * <ul>
 *   <li>{@link Status#PENDING}: tried again at {@link #getNextAttemptAt()}: the analysis service's time
 *       budget was exceeded, the call timed out, or the response could not be stored. Nothing of the
 *       failed attempt is kept.</li>
 *   <li>{@link Status#SKIPPED}: never tried again: the position cannot be judged (HTTP 422), or the
 *       verdicts accept the game's mistake (sanity rule, ADR 0008).</li>
 * </ul>
 */
@Entity
@Table(name = "drill_preparations")
public class DrillPreparation extends BaseEntity {

    public enum Status {
        PENDING,
        SKIPPED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finding_id", nullable = false)
    private MistakeFinding finding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Drill.Task task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", nullable = false)
    private String lastError;

    protected DrillPreparation() {
        // for JPA
    }

    DrillPreparation(MistakeFinding finding, Drill.Task task) {
        this.finding = finding;
        this.task = task;
    }

    /** Another failed attempt that is worth repeating: try again at {@code nextAttemptAt}. */
    void retryAt(Instant nextAttemptAt, String error) {
        attempts++;
        status = Status.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        lastError = error;
    }

    /** A failed attempt that would fail the same way again: never try again. */
    void skip(String reason) {
        attempts++;
        status = Status.SKIPPED;
        nextAttemptAt = null;
        lastError = reason;
    }

    public MistakeFinding getFinding() {
        return finding;
    }

    public Drill.Task getTask() {
        return task;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
