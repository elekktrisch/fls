package ch.alpenflight.server.testsupport;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared base class for full-stack {@code @SpringBootTest} integration tests
 * that need a real Postgres database. Subclasses inherit:
 *
 * <ul>
 *   <li>{@code @SpringBootTest} (default web environment — subclasses that
 *       need {@code RANDOM_PORT} re-declare it; the override is harmless
 *       because Spring caches by full annotation hash).</li>
 *   <li>{@code @ActiveProfiles("test")} — picks up {@code application-test.yml}.</li>
 *   <li>{@code @EnabledIf} pointing at {@link SharedPostgresContainer#available()},
 *       so a contributor without Docker still passes {@code ./gradlew check}
 *       (tests skip rather than fail); CI fails loudly via the
 *       {@code SharedPostgresContainer.available()} contract.</li>
 *   <li>A {@link DynamicPropertySource} pointing Spring's datasource + Flyway
 *       at the JVM-singleton container.</li>
 *   <li>{@link TenantContextExtension} — picks up {@link WithTenant}
 *       annotations and stores the resolved tenant in
 *       {@link TenantTestContext}.</li>
 * </ul>
 *
 * <p><strong>Isolation rule (ADR 0021):</strong> this base class deliberately
 * does NOT add {@code @Transactional} per-test rollback. Tests own their data
 * by tenant key (tenant-scoped data) or stable randomized natural key
 * (cross-tenant data) and pre-clean at start. A test that needs a different
 * isolation strategy declares it explicitly.
 *
 * <p>Container, Flyway state, and Spring context all live for the JVM; the
 * Spring context cache reuses one boot across subclasses that share this
 * annotation set.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
@ExtendWith(TenantContextExtension.class)
public abstract class PostgresIntegrationTest {

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
}
