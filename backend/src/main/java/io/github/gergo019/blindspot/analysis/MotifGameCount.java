package io.github.gergo019.blindspot.analysis;

/** In how many distinct games a motif occurred (a query result, not an entity). */
public record MotifGameCount(Motif motif, long games) {
}
