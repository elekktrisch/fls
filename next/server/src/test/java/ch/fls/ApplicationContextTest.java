package ch.fls;

import ch.fls.server.testsupport.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Catches DI / @Configuration misconfig on every PR. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "ch.fls.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class ApplicationContextTest {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        var pg = SharedPostgresContainer.INSTANCE;
        r.add("spring.datasource.url", pg::jdbcUrl);
        r.add("spring.datasource.username", pg::username);
        r.add("spring.datasource.password", pg::password);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void contextLoads() {
        // assertion-less: failure surfaces as an exception in @SpringBootTest setup.
    }
}
