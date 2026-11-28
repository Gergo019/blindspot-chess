package io.github.gergo019.blindspot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The shape every API error must have, shared by the API tests (ReadApiIT, JourneyApiIT). */
public final class ApiProblems {

    private ApiProblems() {
    }

    /**
     * A problem detail without any internals (no stack trace, exception class or exception message) and
     * with {@code instance} set to the request path. Spring Framework 7.1 no longer sets {@code instance}
     * itself, so every error checks it: Spring's own (404 unknown path, 405 wrong method, 400 conversion,
     * 415 media type) and ours (400, 404, 409, 500) (#85).
     */
    public static JsonNode assertProblem(JsonMapper json, HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.has("title")).isTrue();
        assertThat(body.has("instance")).as("instance in " + response.body()).isTrue();
        assertThat(body.get("instance").asString()).isEqualTo(response.request().uri().getRawPath());
        assertThat(body.has("trace")).isFalse();
        assertThat(body.has("exception")).isFalse();
        assertThat(body.has("message")).isFalse();
        assertThat(response.body()).doesNotContain("Exception", "at io.github", "at org.");
        return body;
    }
}
