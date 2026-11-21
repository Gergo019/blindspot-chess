package io.github.gergo019.blindspot.features;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.cucumber.spring.CucumberContextConfiguration;

/**
 * The application the features run against: the real backend on a random port, with PostgreSQL in a
 * Testcontainers container. The background jobs are off, so only the data a scenario seeds exists. The
 * configured player is "Player1" (lower-cased like the account the Lichess setup creates).
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "blindspot.lichess.importer.enabled=false", "blindspot.analysis.job.enabled=false",
        "blindspot.training.drill-preparation.enabled=false",
        "blindspot.lichess.username=Player1" })
public class CucumberSpringConfiguration {

    /**
     * Started here, once for all scenarios: the JUnit Testcontainers extension does not run under the
     * Cucumber engine. {@link ServiceConnection} points the datasource at it.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
