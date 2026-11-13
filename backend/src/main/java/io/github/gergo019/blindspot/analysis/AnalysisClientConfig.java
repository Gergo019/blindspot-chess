package io.github.gergo019.blindspot.analysis;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client for the analysis service. Connecting must be quick; reading
 * may take minutes, because one call analyses a whole game at a fixed depth.
 */
@Configuration
public class AnalysisClientConfig {

    public static final String REST_CLIENT = "analysisRestClient";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    @Bean(REST_CLIENT)
    RestClient analysisRestClient(RestClient.Builder builder,
            @Value("${blindspot.analysis-service-url}") String baseUrl,
            @Value("${blindspot.analysis-service-read-timeout:180s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
