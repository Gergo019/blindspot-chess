package io.github.gergo019.blindspot.health;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness check for the backend and its database connection.
 * Returns 200 when PostgreSQL is reachable and 503 when it is not.
 */
@RestController
public class HealthController {

    private static final int DB_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean dbUp = isDatabaseUp();
        HealthResponse body = new HealthResponse(
                dbUp ? "UP" : "DOWN",
                dbUp ? "UP" : "DOWN",
                Instant.now());
        HttpStatus status = dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }

    private boolean isDatabaseUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    public record HealthResponse(String status, String database, Instant timestamp) {
    }
}
