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
        r.add("spring.flyway.url", pg::jdbcUrl);
        r.add("spring.flyway.user", pg::username);
        r.add("spring.flyway.password", pg::password);
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
                .as("Committed OpenAPI snapshot is stale vs. live spec. Run with ALPENFLIGHT_OPENAPI_REFRESH=true to regenerate via OpenApiSnapshotRegenerationIT.")
                .isEqualTo(committed);
    }
}
