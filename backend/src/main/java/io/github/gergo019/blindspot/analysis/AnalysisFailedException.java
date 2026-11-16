package io.github.gergo019.blindspot.analysis;

/**
 * The analysis service did not analyse a game. {@link #getKind()} says whose
 * fault it was, which decides what happens to the game (ADR 0005).
 */
public class AnalysisFailedException extends RuntimeException {

    /** Why the call failed, as far as the backend can tell. */
    public enum Kind {
        /** HTTP 422: the service rejected the game itself (unfinished, illegal, too long). */
        REJECTED,
        /**
         * The service is not there or not working: connection refused or timed
         * out, host not found, an HTTP 5xx, or another error status that says
         * nothing about the game. Not the game's fault.
         */
        UNAVAILABLE,
        /** The service accepted the connection but did not answer within the read timeout. */
        TIMEOUT,
        /** The service answered 2xx, but with no body or a body that cannot be read. */
        INVALID_RESPONSE
    }

    private final Kind kind;

    /** HTTP status from the analysis service, or 0 if there was no response. */
    private final int status;

    public AnalysisFailedException(Kind kind, String message, int status) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public AnalysisFailedException(Kind kind, String message, int status, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
    }

    public Kind getKind() {
        return kind;
    }

    public int getStatus() {
        return status;
    }
}
