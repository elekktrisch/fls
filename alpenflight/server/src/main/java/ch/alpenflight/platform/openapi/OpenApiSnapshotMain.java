package ch.alpenflight.platform.openapi;

import ch.alpenflight.AlpenFlightApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Standalone driver used by the Gradle tasks {@code generateOpenApiSnapshot} +
 * {@code compareOpenApiSnapshot}. Boots the application on a random port with
 * the {@code dev} profile (springdoc enabled), fetches {@code /v3/api-docs},
 * normalizes (sorted keys via Jackson, LF newlines, trailing newline), then:
 *
 * <ul>
 *   <li>{@code --write <path>} writes the spec to {@code <path>}.</li>
 *   <li>{@code --compare <path>} compares against {@code <path>}; exit 0 on
 *       match, exit 1 with a one-line diagnostic on mismatch.</li>
 * </ul>
 *
 * <p>Volatile field {@code $.info.version} is stripped before serialization /
 * comparison — keeping a literal {@code "0.0.1-SNAPSHOT"} in committed JSON
 * would churn on every release-version bump.
 */
public final class OpenApiSnapshotMain {

    private OpenApiSnapshotMain() {}

    public static void main(String[] args) throws Exception {
        Mode mode = parseMode(args);
        Path target = Path.of(args[1]).toAbsolutePath().normalize();

        System.setProperty("spring.profiles.active", "dev");
        System.setProperty("server.port", "0");
        System.setProperty("springdoc.api-docs.enabled", "true");
        System.setProperty("springdoc.swagger-ui.enabled", "false");
        // Loopback defaults pinned in application-dev.yml — the snapshot run
        // does not need a live Postgres; Flyway connects on startup though, so
        // skip it for snapshot generation.
        System.setProperty("spring.flyway.enabled", "false");
        System.setProperty("spring.jpa.hibernate.ddl-auto", "none");

        try (ConfigurableApplicationContext ctx = SpringApplication.run(AlpenFlightApplication.class)) {
            int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port", "0"));
            if (port == 0) {
                throw new IllegalStateException("local.server.port unresolved — app did not bind");
            }
            String live = fetchLiveSpec(port);
            String normalized = normalize(live);
            switch (mode) {
                case WRITE -> writeSnapshot(target, normalized);
                case COMPARE -> compareSnapshot(target, normalized);
            }
        }
    }

    private static String fetchLiveSpec(int port) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("expected 200 from /v3/api-docs but got " + response.statusCode()
                    + " — body: " + response.body());
        }
        return response.body();
    }

    private static String normalize(String rawJson) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode tree = mapper.readTree(rawJson);
        OpenApiSnapshotNormalize.stripVolatile(tree);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n";
    }

    private static void writeSnapshot(Path target, String normalized) throws Exception {
        Files.createDirectories(target.getParent());
        Files.writeString(target, normalized);
        System.out.println("Wrote OpenAPI snapshot to " + target);
    }

    private static void compareSnapshot(Path target, String live) throws Exception {
        if (!Files.exists(target)) {
            System.err.println("OpenAPI snapshot is missing at " + target
                    + " — run ./gradlew generateOpenApiSnapshot and commit the file.");
            System.exit(1);
        }
        String committed = Files.readString(target);
        if (!committed.equals(live)) {
            System.err.println("Committed OpenAPI snapshot is stale vs. live spec at " + target
                    + " — run ./gradlew generateOpenApiSnapshot and commit the refreshed file.");
            System.exit(1);
        }
        System.out.println("OpenAPI snapshot matches live spec at " + target);
    }

    private static Mode parseMode(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: OpenApiSnapshotMain (--write|--compare) <path>");
        }
        return switch (args[0]) {
            case "--write" -> Mode.WRITE;
            case "--compare" -> Mode.COMPARE;
            default -> throw new IllegalArgumentException("Unknown mode: " + args[0]);
        };
    }

    private enum Mode { WRITE, COMPARE }
}
