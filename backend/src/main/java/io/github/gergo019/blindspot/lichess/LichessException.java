package io.github.gergo019.blindspot.lichess;

/** A Lichess request failed; the import for that account is skipped this run. */
public class LichessException extends RuntimeException {

    public LichessException(String message) {
        super(message);
    }

    public LichessException(String message, Throwable cause) {
        super(message, cause);
    }
}
