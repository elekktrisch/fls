package ch.alpenflight.platform.openapi;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "spring.datasource.url=${DATASOURCE_URL:jdbc:postgresql://localhost:5432/alpenflight}",
        "spring.datasource.username=${DATASOURCE_USER:alpenflight}",
        "spring.datasource.password=${DATASOURCE_PASSWORD:alpenflight}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@EnabledIfEnvironmentVariable(named = "ALPENFLIGHT_OPENAPI_REFRESH_REMOTE", matches = "true")
class OpenApiSnapshotWriterUsingRemotePostgres {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void writeSnapshot() throws Exception {
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
        System.out.println("OpenApiSnapshotWriterUsingRemotePostgres: wrote " + target + " ("
                + normalized.length() + " chars)");
        assertThat(target).exists();
    }
}
