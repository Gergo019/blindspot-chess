package io.github.gergo019.blindspot.training;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import io.github.gergo019.blindspot.analysis.Motif;

/**
 * The learning journey's curriculum (ADR 0008): units of lessons, defined in code so that it changes
 * only through code review. Every lesson assignment records the {@link #VERSION} it was built from, as
 * findings record their detector version; change the version whenever a lesson's meaning changes (its
 * sizes, its mastery rule, or what goes into it).
 *
 * <p>Slice 1 has one unit, Hanging Pieces, because the hanging-piece detector is the only one so far.
 * The texts are a first version; hand-written lesson content is out of scope for slice 1 (#74).
 */
public final class Curriculum {

    public static final String VERSION = "journey-1";

    /** Practice items a lesson aims for. */
    public static final int TARGET_PRACTICE = 5;
    /** Practice items a lesson needs before it can be assigned. */
    public static final int MIN_PRACTICE = 3;
    /**
     * The mastery check: this many check items, all answered correctly on their single attempt. A retry is
     * a new set of this size on unused positions (amendment of 2026-11-27).
     */
    public static final int CHECK_ITEMS = 2;

    /** Positions a lesson takes at most. */
    public static final int TARGET_SIZE = TARGET_PRACTICE + CHECK_ITEMS;
    /** Positions a lesson needs before it can be assigned. */
    public static final int MIN_SIZE = MIN_PRACTICE + CHECK_ITEMS;

    /** A unit of the journey, tied to the detector of its motif. */
    public enum Unit {
        HANGING_PIECE(Motif.HANGING_PIECE, "hanging-piece", "Hanging pieces",
                "A piece is hanging when your opponent can take it and you lose material. Before each move, "
                        + "check what it leaves undefended: the piece you move, and the pieces it stops guarding.");

        private final Motif motif;
        private final String keyPrefix;
        private final String title;
        private final String explanation;

        Unit(Motif motif, String keyPrefix, String title, String explanation) {
            this.motif = motif;
            this.keyPrefix = keyPrefix;
            this.title = title;
            this.explanation = explanation;
        }

        public Motif motif() {
            return motif;
        }

        public String title() {
            return title;
        }

        public String explanation() {
            return explanation;
        }
    }

    /** One lesson: a stable key such as {@code hanging-piece/1}, its number within the unit, and its texts. */
    public record Lesson(String key, Unit unit, int number, String title, String explanation) {
    }

    private static final int LESSONS_PER_UNIT = 5;

    private static final List<Lesson> LESSONS = IntStream.rangeClosed(1, LESSONS_PER_UNIT)
            .mapToObj(n -> new Lesson(Unit.HANGING_PIECE.keyPrefix + "/" + n, Unit.HANGING_PIECE, n,
                    Unit.HANGING_PIECE.title + " " + n, Unit.HANGING_PIECE.explanation))
            .toList();

    private Curriculum() {
    }

    /** Every lesson of a unit, in the recommended order. */
    public static List<Lesson> lessons(Unit unit) {
        return LESSONS.stream().filter(l -> l.unit() == unit).toList();
    }

    public static Optional<Lesson> lesson(String key) {
        return LESSONS.stream().filter(l -> l.key().equals(key)).findFirst();
    }
}
