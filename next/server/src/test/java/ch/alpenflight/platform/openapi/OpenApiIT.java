package ch.alpenflight.platform.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "springdoc.api-docs.enabled=true",
    "springdoc.swagger-ui.enabled=true"
})
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class OpenApiIT {

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
    void apiDocsReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
    }

    @Test
    void specIsOpenApi31() throws Exception {
        JsonNode spec = json.readTree(restTemplate.getForObject("/v3/api-docs", String.class));
        assertThat(spec.path("openapi").asText()).startsWith("3.1");
    }

    @Test
    void specContainsHelloOperation() throws Exception {
        JsonNode spec = json.readTree(restTemplate.getForObject("/v3/api-docs", String.class));
        JsonNode hello = spec.path("paths").path("/api/v1/hello").path("get");
        assertThat(hello.isMissingNode()).isFalse();
        assertThat(hello.path("responses").path("200").isMissingNode()).isFalse();
    }

    @Test
    void specContainsBearerAuthScheme() throws Exception {
        JsonNode spec = json.readTree(restTemplate.getForObject("/v3/api-docs", String.class));
        JsonNode bearer = spec.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(bearer.isMissingNode()).isFalse();
        assertThat(bearer.path("type").asText()).isEqualTo("http");
        assertThat(bearer.path("scheme").asText()).isEqualTo("bearer");
        assertThat(bearer.path("bearerFormat").asText()).isEqualTo("JWT");
    }

    @Test
    void specRequiresBearerAuthOnOperations() throws Exception {
        JsonNode spec = json.readTree(restTemplate.getForObject("/v3/api-docs", String.class));
        JsonNode security = spec.path("paths").path("/api/v1/clubs").path("get").path("security");
        assertThat(security.isArray()).as("each operation must declare a security requirement").isTrue();
        assertThat(security.toString()).contains("bearerAuth");
    }

    @Test
    void swaggerUiReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
