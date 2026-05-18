package ch.alpenflight.clubs;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression guard: without the {@code mock-auth} profile the mock security
 * filter chain MUST NOT be registered. Mirrors the {@code OpenApiOffByDefaultIT}
 * pattern for springdoc.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class MockSecurityConfigAbsenceIT {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::jdbcUrl);
        r.add("spring.datasource.username", POSTGRES::username);
        r.add("spring.datasource.password", POSTGRES::password);
        r.add("spring.flyway.url", POSTGRES::jdbcUrl);
        r.add("spring.flyway.user", POSTGRES::username);
        r.add("spring.flyway.password", POSTGRES::password);
    }

    @Autowired ApplicationContext ctx;

    @Test
    void mockSecurityConfig_bean_is_absent_without_mockAuth_profile() {
        assertThat(ctx.getBeanNamesForType(ch.alpenflight.auth.MockSecurityConfig.class))
                .as("MockSecurityConfig must NOT be registered outside the mock-auth profile")
                .isEmpty();
        assertThat(ctx.getBeanNamesForType(ch.alpenflight.auth.MockAuthenticationFilter.class))
                .as("MockAuthenticationFilter must NOT be registered outside the mock-auth profile")
                .isEmpty();
    }
}
