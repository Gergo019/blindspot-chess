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

    /** The lesson item this attempt answered (ADR 0008); null for a review from the SM-2 queue. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_item_id")
    private LessonItem lessonItem;

    /**
     * Copied from the item's role, so a partial unique index can allow one attempt per check item; the
     * database checks that the copy matches the item.
     */
    @Column(name = "is_check", nullable = false)
    private boolean check;

    protected DrillAttempt() {
        // for JPA
    }

    DrillAttempt(Drill drill, String moveUci, boolean correct, Instant attemptedAt, LessonItem lessonItem) {
        this.drill = drill;
        this.moveUci = moveUci;
        this.correct = correct;
        this.attemptedAt = attemptedAt;
        this.lessonItem = lessonItem;
        this.check = lessonItem != null && lessonItem.isCheck();
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

    public LessonItem getLessonItem() {
        return lessonItem;
    }

    public boolean isCheck() {
        return check;
    }
}
