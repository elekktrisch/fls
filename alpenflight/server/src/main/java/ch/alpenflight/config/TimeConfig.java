package ch.alpenflight.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * UTC system clock as a Spring bean so domain code that stamps timestamps
 * (soft-delete, audit columns, etc.) can be tested deterministically by
 * injecting a fixed clock in tests.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
