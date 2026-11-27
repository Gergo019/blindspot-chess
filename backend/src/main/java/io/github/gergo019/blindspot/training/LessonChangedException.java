package io.github.gergo019.blindspot.training;

/**
 * An answer that could not be stored because a re-analysis deleted its lesson item (with its drill) while
 * the answer was being stored. Nothing is recorded; the lesson as it is now has to be loaded again (#90).
 */
public class LessonChangedException extends RuntimeException {

    public LessonChangedException(Throwable cause) {
        super("The lesson item was deleted while the answer was being stored", cause);
    }
}
