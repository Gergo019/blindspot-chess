package io.github.gergo019.blindspot.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 404 as RFC 9457 problem detail. Also used for resources that exist but belong to another player,
 * so the API never reveals which ids exist.
 */
public class NotFoundException extends ErrorResponseException {

    public NotFoundException(String what, long id) {
        super(HttpStatus.NOT_FOUND, problem("No " + what + " with id " + id), null);
    }

    /** For resources with a key rather than an id, such as a lesson ({@code hanging-piece/1}). */
    public NotFoundException(String what, String key) {
        super(HttpStatus.NOT_FOUND, problem("No " + what + " " + key), null);
    }

    private static ProblemDetail problem(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setTitle("Not found");
        return problem;
    }
}
