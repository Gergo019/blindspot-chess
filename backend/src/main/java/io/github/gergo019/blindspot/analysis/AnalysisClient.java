package io.github.gergo019.blindspot.analysis;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeRequest;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeResponse;

/**
 * Calls the analysis service's {@code POST /analyze}. One call analyses one
 * finished game synchronously, which takes a while for long games, so callers
 * run it from a background job and never inside a database transaction.
 */
@Component
public class AnalysisClient {

    private final RestClient restClient;

    public AnalysisClient(@Qualifier(AnalysisClientConfig.REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @throws AnalysisFailedException if the service rejects the game (422),
     *         answers with any other error status, cannot be reached, times
     *         out, or returns no body
     */
    public AnalyzeResponse analyze(AnalyzeRequest request) {
        try {
            AnalyzeResponse response = restClient.post()
                    .uri("/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes());
                        throw new AnalysisFailedException(
                                "Analysis service answered " + res.getStatusCode().value() + ": " + body,
                                res.getStatusCode().value());
                    })
                    .body(AnalyzeResponse.class);
            if (response == null) {
                throw new AnalysisFailedException("Analysis service returned no body", 0);
            }
            return response;
        } catch (AnalysisFailedException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AnalysisFailedException("Analysis service call failed: " + e.getMessage(), 0, e);
        }
    }
}
