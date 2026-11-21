package io.github.gergo019.blindspot.analysis;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.github.gergo019.blindspot.analysis.AnalysisFailedException.Kind;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeRequest;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeResponse;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.JudgeRequest;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.JudgeResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the analysis service: {@code POST /analyze} (one finished game) and
 * {@code POST /judge} (every legal move of one drill position, #78). Both take
 * seconds to minutes, so callers run them from background jobs and never
 * inside a database transaction.
 *
 * <p>Every failure is an {@link AnalysisFailedException} with a
 * {@link Kind}, decided here from the exception types, the status code and,
 * for the judge's time budget, the reason in the error body, so callers never
 * parse messages (ADR 0005).
 */
@Component
public class AnalysisClient {

    /** {@code detail.reason} of the 503 that {@code POST /judge} answers when its time budget is exceeded. */
    static final String JUDGE_BUDGET_EXCEEDED = "JUDGE_BUDGET_EXCEEDED";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;

    public AnalysisClient(@Qualifier(AnalysisClientConfig.REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @throws AnalysisFailedException if the game was not analysed; its kind
     *         says why
     */
    public AnalyzeResponse analyze(AnalyzeRequest request) {
        return post("/analyze", request, AnalyzeResponse.class);
    }

    /**
     * Judges every legal move of a drill position.
     *
     * @throws AnalysisFailedException if the position was not judged; {@link Kind#BUDGET_EXCEEDED} when the
     *         service's time budget ran out (no verdicts were produced), {@link Kind#REJECTED} when the position
     *         cannot be judged
     */
    public JudgeResponse judge(JudgeRequest request) {
        return post("/judge", request, JudgeResponse.class);
    }

    private <T> T post(String path, Object request, Class<T> responseType) {
        T response;
        try {
            response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new AnalysisFailedException(kindOf(status, body),
                                "Analysis service answered " + status + " to " + path + ": " + body, status);
                    })
                    .body(responseType);
        } catch (AnalysisFailedException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // An I/O error: no response at all.
            Kind kind = isReadTimeout(e) ? Kind.TIMEOUT : Kind.UNAVAILABLE;
            throw new AnalysisFailedException(kind, "Analysis service call to " + path + " failed (" + kind + "): "
                    + e.getMessage() + " [" + rootCause(e) + "]", 0, e);
        } catch (RestClientException e) {
            // A 2xx response whose body could not be read as the expected record.
            throw new AnalysisFailedException(Kind.INVALID_RESPONSE,
                    "Analysis service response to " + path + " could not be read: " + e.getMessage(), 0, e);
        }
        if (response == null) {
            throw new AnalysisFailedException(Kind.INVALID_RESPONSE, "Analysis service returned no body for " + path,
                    0);
        }
        return response;
    }

    /**
     * 422 is the service's verdict on the game. Every other error status says
     * the service (or its configuration) is not working: a 5xx, but also a
     * 404 or 400 from a wrong URL or a contract mismatch, which would hit
     * every game the same way.
     */
    static Kind kindOf(int status) {
        return status == HttpStatus.UNPROCESSABLE_CONTENT.value() ? Kind.REJECTED : Kind.UNAVAILABLE;
    }

    /**
     * As {@link #kindOf(int)}, except a 503 whose {@code detail.reason} is
     * {@value #JUDGE_BUDGET_EXCEEDED}: the service works, but judging that
     * position took longer than its budget.
     */
    static Kind kindOf(int status, String body) {
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value() && JUDGE_BUDGET_EXCEEDED.equals(reason(body))) {
            return Kind.BUDGET_EXCEEDED;
        }
        return kindOf(status);
    }

    /** {@code detail.reason} of an error body, or null if the body is not JSON or has none. */
    private static String reason(String body) {
        try {
            JsonNode reason = JSON.readTree(body).path("detail").path("reason");
            return reason.isString() ? reason.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** The innermost cause, e.g. "UnresolvedAddressException" when the host is gone. Some have no message. */
    private static String rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /**
     * A read timeout: connected, but no answer in time. A connect timeout
     * ({@link HttpConnectTimeoutException}, a subclass of
     * {@link HttpTimeoutException}) means the service is not reachable, so it
     * is checked first.
     */
    static boolean isReadTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
