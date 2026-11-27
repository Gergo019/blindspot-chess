package io.github.gergo019.blindspot.training;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.common.BaseEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * A lesson of the {@link Curriculum} assigned to one user (ADR 0008): its positions as {@link LessonItem}s,
 * and when the user passed its mastery check. Once assigned, a lesson does not change when new games are
 * analysed; only a retry of the check adds items.
 *
 * <p>The rules live here:
 * <ul>
 *   <li>A lesson is <b>mastered</b> only when the {@link Curriculum#CHECK_ITEMS} items of one check are all
 *       solved on their single attempt. Only that sets {@code masteredAt}: progress comes only from solving
 *       puzzles (#74).</li>
 *   <li><b>A passed lesson stays passed.</b> Nothing clears {@code masteredAt}, also not when a re-analysis
 *       deletes the lesson's drills and so its items.</li>
 *   <li>A check item is answered once ({@link CheckAlreadyAnsweredException}).</li>
 *   <li><b>A failed check can be retried on {@link Curriculum#CHECK_ITEMS} unused positions</b>, never on
 *       positions already in the lesson (amendment of 2026-11-27). Which positions are unused across the
 *       user's lessons is the caller's to find ({@link LessonFilling#retryPositions}); this class refuses
 *       any position that is already one of its items.</li>
 * </ul>
 */
@Entity
@Table(name = "lesson_assignments")
public class LessonAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "lesson_key", nullable = false, length = 40)
    private String lessonKey;

    @Column(name = "curriculum_version", nullable = false, length = 20)
    private String curriculumVersion;

    @Column(name = "mastered_at")
    private Instant masteredAt;

    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL)
    @OrderBy("position ASC")
    private List<LessonItem> items = new ArrayList<>();

    protected LessonAssignment() {
        // for JPA
    }

    /**
     * Assigns a lesson with these drills, in item order. The last {@link Curriculum#CHECK_ITEMS} are the check
     * items, the rest practice ({@link LessonFilling} chooses and orders them).
     *
     * @throws IllegalArgumentException if there are fewer than {@link Curriculum#MIN_SIZE} or more than
     *         {@link Curriculum#TARGET_SIZE} drills, a drill appears twice, or a drill is another user's or of
     *         another motif
     */
    public static LessonAssignment assign(User user, Curriculum.Lesson lesson, List<Drill> drills) {
        if (drills.size() < Curriculum.MIN_SIZE || drills.size() > Curriculum.TARGET_SIZE) {
            throw new IllegalArgumentException("A lesson takes " + Curriculum.MIN_SIZE + " to " + Curriculum.TARGET_SIZE
                    + " positions, not " + drills.size());
        }
        LessonAssignment assignment = new LessonAssignment();
        assignment.user = user;
        assignment.lessonKey = lesson.key();
        assignment.curriculumVersion = Curriculum.VERSION;
        int checkFrom = drills.size() - Curriculum.CHECK_ITEMS;
        for (int i = 0; i < drills.size(); i++) {
            assignment.add(drills.get(i), lesson, i < checkFrom ? LessonItem.Role.PRACTICE : LessonItem.Role.CHECK);
        }
        return assignment;
    }

    /**
     * Answers the item at a position. A check answer that completes a check with every item solved first try
     * masters the lesson.
     *
     * @throws IllegalArgumentException if the lesson has no item at that position
     * @throws CheckAlreadyAnsweredException if it is a check item that has been answered
     * @throws IllegalMoveException if the move is not legal in the position; nothing is recorded
     */
    public DrillAttempt answer(int position, String moveUci, Instant now) {
        LessonItem item = items.stream().filter(i -> i.getPosition() == position).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Lesson " + lessonKey + " has no position " + position));
        DrillAttempt attempt = item.answer(moveUci, now);
        if (item.isCheck() && masteredAt == null && checkPassed()) {
            masteredAt = now;
        }
        return attempt;
    }

    /** The current mastery check: the last {@link Curriculum#CHECK_ITEMS} check items. */
    public List<LessonItem> currentCheck() {
        List<LessonItem> check = items.stream().filter(LessonItem::isCheck).toList();
        return check.subList(Math.max(0, check.size() - Curriculum.CHECK_ITEMS), check.size());
    }

    private boolean checkPassed() {
        List<LessonItem> check = currentCheck();
        return check.size() == Curriculum.CHECK_ITEMS && check.stream().allMatch(LessonItem::isSolvedFirstTry);
    }

    /** Every item of the current check is answered, at least one wrongly, and the lesson is not mastered. */
    public boolean isCheckFailed() {
        List<LessonItem> check = currentCheck();
        return !isMastered() && !check.isEmpty() && check.stream().allMatch(LessonItem::isAnswered)
                && !check.stream().allMatch(LessonItem::isSolvedFirstTry);
    }

    /**
     * How many more unused positions a retry needs, given how many the user has: 0 when a retry is possible,
     * or when there is no failed check to retry.
     */
    public int positionsNeededForRetry(int unusedPositions) {
        return isCheckFailed() ? Math.max(0, Curriculum.CHECK_ITEMS - unusedPositions) : 0;
    }

    /** A retry is possible now: the check failed and there are enough unused positions. */
    public boolean isRetryAvailable(int unusedPositions) {
        return isCheckFailed() && unusedPositions >= Curriculum.CHECK_ITEMS;
    }

    /**
     * Retries the mastery check on new positions: adds them as check items after the existing items.
     *
     * @throws IllegalStateException if there is no failed check to retry (mastered, or not fully answered)
     * @throws IllegalArgumentException if not exactly {@link Curriculum#CHECK_ITEMS} drills are given, or one
     *         of them is already in this lesson, appears twice, or is another user's or of another motif
     */
    public void retry(List<Drill> unusedPositions) {
        if (!isCheckFailed()) {
            throw new IllegalStateException("Lesson " + lessonKey + " has no failed check to retry");
        }
        if (unusedPositions.size() != Curriculum.CHECK_ITEMS) {
            throw new IllegalArgumentException("A retry takes " + Curriculum.CHECK_ITEMS + " unused positions, not "
                    + unusedPositions.size());
        }
        Curriculum.Lesson lesson = lesson();
        Drill first = unusedPositions.get(0);
        Drill second = unusedPositions.get(1);
        requireUsable(first, lesson);
        requireUsable(second, lesson);
        if (same(first, second)) {
            throw new IllegalArgumentException("A position is never repeated within a lesson");
        }
        // Checked before adding, so a refused retry changes nothing.
        add(first, lesson, LessonItem.Role.CHECK);
        add(second, lesson, LessonItem.Role.CHECK);
    }

    private void add(Drill drill, Curriculum.Lesson lesson, LessonItem.Role role) {
        requireUsable(drill, lesson);
        items.add(new LessonItem(this, drill, items.size() + 1, role));
    }

    private void requireUsable(Drill drill, Curriculum.Lesson lesson) {
        if (!same(drill.getUser(), user)) {
            throw new IllegalArgumentException("A lesson takes only its user's drills");
        }
        if (drill.getMotif() != lesson.unit().motif()) {
            throw new IllegalArgumentException("Lesson " + lesson.key() + " takes " + lesson.unit().motif()
                    + " drills, not " + drill.getMotif());
        }
        if (items.stream().anyMatch(item -> same(item.getDrill(), drill))) {
            throw new IllegalArgumentException("A position is never repeated within a lesson");
        }
    }

    /** The same object, or the same saved row. */
    private static boolean same(BaseEntity a, BaseEntity b) {
        return a == b || (a.getId() != null && a.getId().equals(b.getId()));
    }

    public Curriculum.Lesson lesson() {
        return Curriculum.lesson(lessonKey)
                .orElseThrow(() -> new IllegalStateException("Lesson " + lessonKey + " is not in the curriculum"));
    }

    public boolean isMastered() {
        return masteredAt != null;
    }

    public User getUser() {
        return user;
    }

    public String getLessonKey() {
        return lessonKey;
    }

    public String getCurriculumVersion() {
        return curriculumVersion;
    }

    public Instant getMasteredAt() {
        return masteredAt;
    }

    public List<LessonItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
