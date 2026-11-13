package io.github.gergo019.blindspot.analysis;

/**
 * The analysis service could not analyse a game: it rejected it (HTTP 422 for
 * an unfinished or illegal game), failed, timed out or could not be reached.
 */
public class AnalysisFailedException extends RuntimeException {

    /** HTTP status from the analysis service, or 0 if there was no response. */
    private final int status;

    public AnalysisFailedException(String message, int status) {
        super(message);
        this.status = status;
    }

    public AnalysisFailedException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
