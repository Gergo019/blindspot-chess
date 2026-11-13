package io.github.gergo019.blindspot.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs and the clock they read. Each job can be switched off with
 * its own {@code enabled} property (the integration tests do that).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
