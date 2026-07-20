package ch.alpenflight.platform.openapi;

import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
 * Opt-in helper that writes the current live OpenAPI spec back to
 * {@code alpenflight/web/openapi/openapi.json}. Disabled by default; enable
 * by passing {@code -Dalpenflight.openapi.refresh=true} when running
 * {@code ./gradlew test} after a controller-shape change.
 *
 * <p>Exists because {@code generateOpenApiSnapshot} drives
 * {@code OpenApiSnapshotMain} against the {@code dev} profile and requires a
 * reachable Postgres on {@code localhost:5432} to bootstrap JPA — not always
 * available in sandboxed runs. This IT re-uses the same shared testcontainer
 * the normal suite uses.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
@EnabledIfEnvironmentVariable(named = "ALPENFLIGHT_OPENAPI_REFRESH", matches = "true")
class OpenApiSnapshotRegenerationIT {

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
    private TestRestTemplate rest;

    @Test
    void writeSnapshotFromLiveSpec() throws Exception {
        String live = rest.getForObject("/v3/api-docs", String.class);
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode tree = mapper.readTree(live);
        OpenApiSnapshotNormalize.stripVolatile(tree);
        String normalized = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n";
        Path target = Path.of("..", "web", "openapi", "openapi.json").toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Files.writeString(target, normalized);
        System.out.println("Wrote OpenAPI snapshot to " + target);
    }
}
