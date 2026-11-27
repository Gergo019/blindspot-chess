package io.github.gergo019.blindspot.training;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import io.github.gergo019.blindspot.analysis.api.FindingResponses.Facts;

/**
 * How lessons fill up from a player's drills (ADR 0008, "Lessons fill up from findings"). Pure: it works on
 * the facts of each position and returns drill ids, so the lesson service can feed it from the read
 * services and the tests can run it without a database.
 *
 * <ul>
 *   <li><b>Order:</b> direct losses before discovered losses (the moved piece is not the one lost), and
 *       within each, a higher {@code materialLost} first; ties by drill id, so the order is stable. A
 *       starting heuristic (ADR 0008, open question (b)).</li>
 *   <li><b>Unassigned lessons share the unused positions in the recommended order.</b> Each takes up to
 *       {@link Curriculum#TARGET_SIZE} of what the lessons before it left, and can be assigned with at least
 *       {@link Curriculum#MIN_SIZE}. A later lesson never takes positions an earlier unassigned lesson
 *       would get, so with the demo data only lesson 1 fills (#74, answer 2).</li>
 *   <li><b>Practice first, check last:</b> the last {@link Curriculum#CHECK_ITEMS} positions of a lesson
 *       are its check items.</li>
 * </ul>
 */
public final class LessonFilling {

    /** A drill that can become a lesson item, with the facts the order depends on. */
    public record Position(long drillId, int materialLost, boolean discovered) {

        /** {@code materialLost} missing counts as 0; a loss is discovered when the moved piece is not the lost one. */
        public static Position of(long drillId, Facts facts) {
            int lost = facts.materialLost() == null ? 0 : facts.materialLost();
            boolean discovered = facts.movedPiece() != null && facts.piece() != null
                    && !facts.movedPiece().equals(facts.piece());
            return new Position(drillId, lost, discovered);
        }
    }

    static final Comparator<Position> ORDER = Comparator.comparing(Position::discovered)
            .thenComparing(Comparator.comparingInt(Position::materialLost).reversed())
            .thenComparingLong(Position::drillId);

    /** What an unassigned lesson can do now. */
    public sealed interface Outcome {
        Curriculum.Lesson lesson();
    }

    /** The lesson can be assigned with these drills, in item order; the last ones are the check items. */
    public record Fillable(Curriculum.Lesson lesson, List<Long> drillIds) implements Outcome {

        public List<Long> practice() {
            return drillIds.subList(0, drillIds.size() - Curriculum.CHECK_ITEMS);
        }

        public List<Long> check() {
            return drillIds.subList(drillIds.size() - Curriculum.CHECK_ITEMS, drillIds.size());
        }
    }

    /** The lesson cannot be assigned yet: it needs this many more positions. */
    public record NeedsPositions(Curriculum.Lesson lesson, int positionsNeeded) implements Outcome {
    }

    private LessonFilling() {
    }

    /** The positions in the order lessons take them. */
    public static List<Position> order(List<Position> positions) {
        return positions.stream().sorted(ORDER).toList();
    }

    /**
     * What every unassigned lesson of a unit can do.
     *
     * @param unused the player's positions of the unit's motif that are not yet an item of any lesson
     * @param assignedKeys the keys of the lessons the player already has; they take nothing
     */
    public static List<Outcome> plan(Curriculum.Unit unit, List<Position> unused, Set<String> assignedKeys) {
        List<Position> pool = order(unused);
        int next = 0;
        List<Outcome> outcomes = new ArrayList<>();
        for (Curriculum.Lesson lesson : Curriculum.lessons(unit)) {
            if (assignedKeys.contains(lesson.key())) {
                continue;
            }
            int take = Math.min(Curriculum.TARGET_SIZE, pool.size() - next);
            List<Position> taken = pool.subList(next, next + take);
            next += take;
            if (take >= Curriculum.MIN_SIZE) {
                outcomes.add(new Fillable(lesson, taken.stream().map(Position::drillId).toList()));
            } else {
                outcomes.add(new NeedsPositions(lesson, Curriculum.MIN_SIZE - take));
            }
        }
        return outcomes;
    }

    /**
     * The drills a mastery retry would use: the first {@link Curriculum#CHECK_ITEMS} unused positions in the
     * order above, so a retry takes positions a later lesson would otherwise get (amendment of 2026-11-27).
     * Fewer than that means no retry.
     */
    public static List<Long> retryPositions(List<Position> unused) {
        return order(unused).stream().limit(Curriculum.CHECK_ITEMS).map(Position::drillId).toList();
    }
}
