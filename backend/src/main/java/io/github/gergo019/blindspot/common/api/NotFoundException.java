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
        super(HttpStatus.NOT_FOUND, problem(what, id), null);
    }

    private static ProblemDetail problem(String what, long id) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No " + what + " with id " + id);
        problem.setTitle("Not found");
        return problem;
    }
}
