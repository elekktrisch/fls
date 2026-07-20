package ch.alpenflight.platform.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Folds in S-123 — regression-lock the springdoc off-state under the prod
 * profile. Runs without any {@code SPRINGDOC_*} env vars set so that Spring's
 * relaxed binding cannot flip the endpoints on through environment leakage.
 * A 200 on either path in prod is a security / information-disclosure issue.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=prod",
                // S-140 — prod normally rejects EPHEMERAL (operator footgun
                // guard); the IT pairs source=EPHEMERAL with the explicit
                // allow-in-prod override so the context boots without a
                // real keyset. Real prod sets ALPENFLIGHT_MIGRATION_MASTER_KEYSET
                // and never sets either of these.
                "alpenflight.migration.master-keyset.source=EPHEMERAL",
                "alpenflight.migration.master-keyset.allow-ephemeral-in-prod=true",
        })
@AutoConfigureTestRestTemplate
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class OpenApiOffByDefaultIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        var pg = SharedPostgresContainer.INSTANCE;
        r.add("spring.datasource.url", pg::jdbcUrl);
        r.add("spring.datasource.username", pg::username);
        r.add("spring.datasource.password", pg::password);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // S-160 split points base spring.flyway.* at the MIGRATOR role; override
        // to the container so boot-time Flyway connects with the container creds.
        r.add("spring.flyway.url", pg::jdbcUrl);
        r.add("spring.flyway.user", pg::username);
        r.add("spring.flyway.password", pg::password);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiDocsReturns404UnderProdProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void swaggerUiReturns404UnderProdProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
