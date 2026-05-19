package ch.alpenflight.platform.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Drift gate. The committed {@code alpenflight/web/openapi/openapi.json} is the
 * contract S-004's TS codegen consumes; a stale snapshot produces a client
 * whose runtime behavior diverges silently from the live API.
 *
 * <p>Comparison is structural ({@link JsonNode}) — string-diffing is brittle
 * to whitespace and key-ordering churn. Volatile fields under {@code info}
 * (notably {@code version} if it ever embeds a build timestamp) are stripped
 * before compare so cross-CI runs don't false-positive.
 *
 * <p>When this test fails: run {@code ./gradlew generateOpenApiSnapshot} and
 * commit the refreshed file.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class OpenApiSnapshotIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        var pg = SharedPostgresContainer.INSTANCE;
        r.add("spring.datasource.url", pg::jdbcUrl);
        r.add("spring.datasource.username", pg::username);
        r.add("spring.datasource.password", pg::password);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void snapshotMatchesLiveSpec() throws Exception {
        Path snapshot = Path.of("..", "web", "openapi", "openapi.json").toAbsolutePath().normalize();
        assertThat(snapshot)
                .as("Committed snapshot at %s — run ./gradlew generateOpenApiSnapshot to create / refresh.", snapshot)
                .exists();

        JsonNode live = json.readTree(restTemplate.getForObject("/v3/api-docs", String.class));
        OpenApiSnapshotNormalize.stripVolatile(live);
        JsonNode committed = json.readTree(Files.readAllBytes(snapshot));
        OpenApiSnapshotNormalize.stripVolatile(committed);

        assertThat(live)
                .as("Committed OpenAPI snapshot is stale vs. live spec. Run ./gradlew generateOpenApiSnapshot and commit the refreshed file.")
                .isEqualTo(committed);
    }
}
