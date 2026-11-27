package io.github.gergo019.blindspot.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An RFC 9457 problem detail a controller answers with, such as 409 for a second answer to a check puzzle.
 * Controllers turn domain exceptions into this, so the domain knows nothing about HTTP; the title and the
 * detail are written for the client and name nothing from the request or the exception. It passes through
 * {@link ApiExceptionHandler} like Spring's own errors, which sets {@code instance}.
 */
public class ProblemException extends ErrorResponseException {

    public ProblemException(HttpStatus status, String title, String detail, Throwable cause) {
        super(status, problem(status, title, detail), cause);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
