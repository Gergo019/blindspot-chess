package io.github.gergo019.blindspot.common.api;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error as an RFC 9457 problem detail ({@code application/problem+json}), written for the
 * client: which parameter is wrong and what is allowed. Exception messages, class names and stack
 * traces never reach a response (#51, #60); unexpected errors are logged with their stack trace and
 * answered with a generic 500.
 *
 * <p>Spring's {@link ResponseEntityExceptionHandler} already turns framework errors into problem
 * details (405 for a wrong method, 404 for an unknown path, 400 for a missing parameter); this class
 * only rewrites the details that would echo internals.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** A query or path parameter that cannot be converted, for example {@code motif=FOO} or {@code page=x}. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() == null ? "parameter" : "'" + ex.getPropertyName() + "'";
        Class<?> type = ex.getRequiredType();
        String detail = type != null && type.isEnum()
                ? "Invalid value for " + name + ". Allowed: " + Arrays.stream(type.getEnumConstants())
                        .map(Object::toString).collect(Collectors.joining(", "))
                : "Invalid value for " + name + ".";
        return problem(HttpStatus.BAD_REQUEST, "Bad request", detail);
    }

    /** Constraint violations on parameters, for example {@code size=500} or {@code page=-1}. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName() + " "
                                + error.getDefaultMessage()))
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Bad request", detail.isEmpty() ? "Invalid parameters." : detail);
    }

    /** Anything unexpected: logged in full, answered without any detail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error in the API", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The request could not be processed.");
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
