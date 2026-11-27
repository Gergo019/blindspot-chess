package io.github.gergo019.blindspot.common.api;

import java.net.URI;
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
import org.springframework.web.context.request.ServletWebRequest;
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
 *
 * <p>Every problem detail carries {@code instance}, the request path ({@link #createResponseEntity}).
 * Spring Framework set it by default up to 7.0, and 7.1 (Spring Boot 4.2) no longer does, because a plain
 * request path is not the per-occurrence identifier RFC 9457 describes (spring-framework#35969). Our API
 * keeps the path, so a client can tell which call failed (#85).
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
        return problem(ex, HttpStatus.BAD_REQUEST, "Bad request", detail, request);
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
        return problem(ex, HttpStatus.BAD_REQUEST, "Bad request",
                detail.isEmpty() ? "Invalid parameters." : detail, request);
    }

    /** Anything unexpected: logged in full, answered without any detail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error in the API", ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The request could not be processed.", request);
    }

    /**
     * The one place every response of this handler passes through: Spring's own problem details (via
     * {@link #handleExceptionInternal}) and ours (via {@link #problem}). Sets {@code instance} to the
     * request path when nothing set it.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
            WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getInstance() == null
                && request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(requestPath(servletRequest.getRequest().getRequestURI()));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /**
     * The request path as a URI reference, or null if the raw path is not a valid URI (the container
     * rejects most such paths before they reach here). Never fails: an error handler must not throw.
     */
    static URI requestPath(String requestUri) {
        try {
            return requestUri == null ? null : URI.create(requestUri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A problem detail of our own, through the same path as Spring's ({@link #createResponseEntity}). */
    private ResponseEntity<Object> problem(Exception ex, HttpStatus status, String title, String detail,
            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), status, request);
    }
}
