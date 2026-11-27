package io.github.gergo019.blindspot.training;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.gergo019.blindspot.common.BaseEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * One position of an assigned lesson (ADR 0008): a drill, its place in the lesson, and whether it is
 * practice or part of the mastery check. Whether it is solved is derived from its attempts, never stored.
 * Created only by {@link LessonAssignment}.
 */
@Entity
@Table(name = "lesson_items")
public class LessonItem extends BaseEntity {

    public enum Role {
        /** Can be answered again; every answer goes through SM-2. */
        PRACTICE,
        /** Part of the mastery check: answered once. */
        CHECK
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false)
    private LessonAssignment assignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drill_id", nullable = false)
    private Drill drill;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    /** The answers given in this lesson, oldest first. Reviews from the SM-2 queue are not among them. */
    @OneToMany(mappedBy = "lessonItem", cascade = CascadeType.PERSIST)
    @OrderBy("attemptedAt ASC, id ASC")
    private List<DrillAttempt> attempts = new ArrayList<>();

    protected LessonItem() {
        // for JPA
    }

    LessonItem(LessonAssignment assignment, Drill drill, int position, Role role) {
        this.assignment = assignment;
        this.drill = drill;
        this.position = position;
        this.role = role;
    }

    /**
     * Answers the item's drill (the verdict lookup and SM-2 of {@link Drill#answer}) and records the attempt
     * on the item.
     *
     * @throws CheckAlreadyAnsweredException if this is a check item that has been answered before
     * @throws IllegalMoveException if the move is not legal in the position; nothing is recorded
     */
    DrillAttempt answer(String moveUci, Instant now) {
        if (isCheck() && isAnswered()) {
            throw new CheckAlreadyAnsweredException(position);
        }
        DrillAttempt attempt = drill.answer(moveUci, now, this);
        attempts.add(attempt);
        return attempt;
    }

    public boolean isCheck() {
        return role == Role.CHECK;
    }

    public boolean isAnswered() {
        return !attempts.isEmpty();
    }

    /** The first answer in this lesson was correct; only that counts for mastery. */
    public boolean isSolvedFirstTry() {
        return isAnswered() && attempts.get(0).isCorrect();
    }

    public LessonAssignment getAssignment() {
        return assignment;
    }

    public Drill getDrill() {
        return drill;
    }

    public int getPosition() {
        return position;
    }

    public Role getRole() {
        return role;
    }

    public List<DrillAttempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
    }
}
