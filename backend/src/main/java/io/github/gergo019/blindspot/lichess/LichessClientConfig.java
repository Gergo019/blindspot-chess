package io.github.gergo019.blindspot.lichess;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client for the Lichess API: identifying User-Agent always, bearer
 * token only when one is configured.
 */
@Configuration
@EnableConfigurationProperties(LichessProperties.class)
public class LichessClientConfig {

    public static final String REST_CLIENT = "lichessRestClient";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** The export streams at 20 games per second or more; a batch is small. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean(REST_CLIENT)
    RestClient lichessRestClient(RestClient.Builder builder, LichessProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return configure(builder.clone(), properties).requestFactory(requestFactory).build();
    }

    /** Base URL and headers; shared with the tests, which bind a mock server instead of a real client. */
    static RestClient.Builder configure(RestClient.Builder builder, LichessProperties properties) {
        builder.baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent());
        if (properties.apiToken() != null && !properties.apiToken().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken().strip());
        }
        return builder;
    }
}
