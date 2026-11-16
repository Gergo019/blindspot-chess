package io.github.gergo019.blindspot.analysis;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

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

/**
 * Calls the analysis service's {@code POST /analyze}. One call analyses one
 * finished game synchronously, which takes a while for long games, so callers
 * run it from a background job and never inside a database transaction.
 *
 * <p>Every failure is an {@link AnalysisFailedException} with a
 * {@link Kind}, decided here from the exception types and the status code,
 * so callers never parse messages (ADR 0005).
 */
@Component
public class AnalysisClient {

    private final RestClient restClient;

    public AnalysisClient(@Qualifier(AnalysisClientConfig.REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @throws AnalysisFailedException if the game was not analysed; its kind
     *         says why
     */
    public AnalyzeResponse analyze(AnalyzeRequest request) {
        AnalyzeResponse response;
        try {
            response = restClient.post()
                    .uri("/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        String body = new String(res.getBody().readAllBytes());
                        throw new AnalysisFailedException(kindOf(status),
                                "Analysis service answered " + status + ": " + body, status);
                    })
                    .body(AnalyzeResponse.class);
        } catch (AnalysisFailedException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // An I/O error: no response at all.
            Kind kind = isReadTimeout(e) ? Kind.TIMEOUT : Kind.UNAVAILABLE;
            throw new AnalysisFailedException(kind,
                    "Analysis service call failed (" + kind + "): " + e.getMessage() + " [" + rootCause(e) + "]", 0, e);
        } catch (RestClientException e) {
            // A 2xx response whose body could not be read as an AnalyzeResponse.
            throw new AnalysisFailedException(Kind.INVALID_RESPONSE,
                    "Analysis service response could not be read: " + e.getMessage(), 0, e);
        }
        if (response == null) {
            throw new AnalysisFailedException(Kind.INVALID_RESPONSE, "Analysis service returned no body", 0);
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
