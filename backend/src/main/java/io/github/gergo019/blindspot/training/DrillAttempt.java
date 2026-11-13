package io.github.gergo019.blindspot.training;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One answer to a drill. Append-only, so it has no updated_at.
 */
@Entity
@Table(name = "drill_attempts")
public class DrillAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drill_id", nullable = false)
    private Drill drill;

    @Column(name = "move_uci", nullable = false, length = 5)
    private String moveUci;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected DrillAttempt() {
        // for JPA
    }

    DrillAttempt(Drill drill, String moveUci, boolean correct, Instant attemptedAt) {
        this.drill = drill;
        this.moveUci = moveUci;
        this.correct = correct;
        this.attemptedAt = attemptedAt;
    }

    public Long getId() {
        return id;
    }

    public Drill getDrill() {
        return drill;
    }

    public String getMoveUci() {
        return moveUci;
    }

    public boolean isCorrect() {
        return correct;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
