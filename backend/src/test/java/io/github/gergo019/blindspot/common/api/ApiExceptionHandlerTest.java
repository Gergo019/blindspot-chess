package io.github.gergo019.blindspot.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/** The {@code instance} rule of every problem detail (#85), without a server. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void anEmptyInstanceBecomesTheRequestPath() {
        ResponseEntity<Object> response = respond(ProblemDetail.forStatus(HttpStatus.NOT_FOUND), "/api/proxy-check");
        assertThat(((ProblemDetail) response.getBody()).getInstance()).isEqualTo(URI.create("/api/proxy-check"));
    }

    @Test
    void theQueryIsNotPartOfTheInstance() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/findings");
        request.setQueryString("size=500");
        ResponseEntity<Object> response = handler.createResponseEntity(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST),
                new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));
        assertThat(((ProblemDetail) response.getBody()).getInstance()).isEqualTo(URI.create("/api/findings"));
    }

    @Test
    void anInstanceSetElsewhereIsKept() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setInstance(URI.create("urn:uuid:3f1c0c1e-8a2b-4d1e-9a0f-2b6f1d7c9e10"));
        ResponseEntity<Object> response = respond(problem, "/api/findings/1");
        assertThat(((ProblemDetail) response.getBody()).getInstance())
                .isEqualTo(URI.create("urn:uuid:3f1c0c1e-8a2b-4d1e-9a0f-2b6f1d7c9e10"));
    }

    @Test
    void aBodyThatIsNotAProblemDetailIsLeftAlone() {
        ResponseEntity<Object> response = handler.createResponseEntity("text", new HttpHeaders(), HttpStatus.OK,
                new ServletWebRequest(new MockHttpServletRequest("GET", "/api/x")));
        assertThat(response.getBody()).isEqualTo("text");
    }

    @Test
    void aRawPathThatIsNotAUriGivesNoInstanceInsteadOfAnError() {
        assertThat(ApiExceptionHandler.requestPath("/api/a b")).isNull();
        assertThat(ApiExceptionHandler.requestPath(null)).isNull();
        assertThat(ApiExceptionHandler.requestPath("/api/findings/%20x")).isEqualTo(URI.create("/api/findings/%20x"));
    }

    private ResponseEntity<Object> respond(ProblemDetail problem, String path) {
        return handler.createResponseEntity(problem, new HttpHeaders(), HttpStatus.valueOf(problem.getStatus()),
                new ServletWebRequest(new MockHttpServletRequest("GET", path)));
    }
}
