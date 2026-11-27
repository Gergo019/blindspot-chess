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

    /**
     * With a fixed detail that names nothing from the request, such as "No such lesson" (#90: a lesson's
     * key comes from the URL, and the URL is not echoed into errors).
     */
    public NotFoundException(String detail) {
        super(HttpStatus.NOT_FOUND, problem(detail), null);
    }

    private static ProblemDetail problem(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setTitle("Not found");
        return problem;
    }
}
