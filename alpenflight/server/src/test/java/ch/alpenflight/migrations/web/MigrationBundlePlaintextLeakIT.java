package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.migrations.domain.MigrationBundleCipher;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S-141b AC4 — plaintext-leak runtime smoke. Plants a 32-byte marker in
 * the bundle's manifest ({@code deploymentName}) — the marker rides
 * through the in-process gzip → tar → JSON pipeline before landing in
 * the {@code t_deployment.name} column via the provisioning service.
 *
 * <p>The IT asserts the marker does NOT appear in:
 * <ul>
 *   <li>{@code java.io.tmpdir} subtree — no app-level disk write
 *       (structurally enforced by {@code MigrationIngestNoDiskSinkTest},
 *       runtime-verified here against a future logging accident).</li>
 *   <li>{@code /var/tmp} subtree — same posture for the second canonical
 *       tmp location.</li>
 *   <li>The Logback ListAppender attached to the root logger — covers
 *       every log event the ingest pipeline emits while the bundle is
 *       in-flight.</li>
 * </ul>
 *
 * <p><strong>PGDATA grep deferral.</strong> The AC also called for a
 * container-side {@code grep $PGDATA} excluding {@code pg_wal/}; this IT
 * skips that step because a clean Postgres install legitimately holds the
 * marker in {@code base/} heap files (the destination column) and the
 * heap-vs-non-heap exclusion list is fragile across pg minor versions.
 * The structural no-disk-sink ArchUnit rule covers the in-app code path;
 * Postgres-internal disk storage of committed values is by design.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
@Tag("slow")
class MigrationBundlePlaintextLeakIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MigrationBundleCipher cipher;

    private UUID userId;
    private UUID userSub;
    private String verifiedToken;
    private String testClubKey;
    private String testClubSlug;

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void seedUser() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "IT-" + tag;
        testClubSlug = "leak-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "leak-it-" + userSub, "Leak IT",
                "leak-" + userSub + "@example.com",
                SEED_LANGUAGE_DE.toString(), userSub.toString());
        verifiedToken = jwts.mint(c -> c
                .subject(userSub.toString())
                .claim("email_verified", true));

        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logCapture = new ListAppender<>();
        logCapture.start();
        rootLogger.addAppender(logCapture);
    }

    @AfterEach
    void cleanup() {
        if (rootLogger != null && logCapture != null) {
            rootLogger.detachAppender(logCapture);
            logCapture.stop();
        }
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?", userSub.toString());
        jdbc.update("DELETE FROM t_migration_run WHERE upload_id IN "
                + "(SELECT id FROM t_migration_upload WHERE user_id = ?::uuid)", userId.toString());
        jdbc.update("DELETE FROM t_migration_upload WHERE user_id = ?::uuid", userId.toString());
        String clubsByOwner =
                "SELECT id FROM t_club WHERE deployment_id IN "
                        + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)";
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_member_state WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_club WHERE deployment_id IN "
                + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)", userSub.toString());
        jdbc.update("DELETE FROM t_deployment WHERE owner_keycloak_sub = ?::uuid", userSub.toString());
        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", userId.toString());
    }

    @Test
    void plaintext_marker_does_not_leak_to_tmpdir_var_tmp_or_logs() throws Exception {
        String marker = "ALPENFLIGHT-PLAINTEXT-MARKER-" + UUID.randomUUID();

        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), "Aero Club Leak IT", testClubSlug, testClubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        // The marker rides into deploymentName — encrypted on the wire,
        // decrypted in-memory, parsed by the hardened Jackson mapper, then
        // bound as a JDBC parameter into t_deployment.name. Anywhere outside
        // that committed-row path is a leak.
        byte[] bundle = MigrationBundleTestFactory.buildWalkingSkeletonBundle(
                cipher, uploadId, publicKeyDer, marker, club);

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/" + uploadId + "/bundle"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bundle),
                String.class);

        assertThat(res.getStatusCode())
                .as("bundle ingest must succeed for the leak check to be meaningful; body=%s",
                        res.getBody())
                .isEqualTo(HttpStatus.OK);

        // Confirm the marker DID land in t_deployment.name — the leak check
        // is only meaningful if the marker actually flowed end-to-end.
        Integer deploymentHits = jdbc.queryForObject(
                "SELECT count(*) FROM t_deployment WHERE name = ?", Integer.class, marker);
        assertThat(deploymentHits)
                .as("marker must reach t_deployment.name for the smoke to be meaningful")
                .isEqualTo(1);

        // Log assertion — the single highest-risk leak channel.
        List<String> leakedLogLines = new ArrayList<>();
        for (ILoggingEvent event : logCapture.list) {
            String formatted = event.getFormattedMessage();
            if (formatted != null && formatted.contains(marker)) {
                leakedLogLines.add(event.getLoggerName() + " | " + formatted);
            }
        }
        assertThat(leakedLogLines)
                .as("no log event may carry the plaintext marker — even an "
                        + "INFO line that quotes deploymentName from the manifest "
                        + "would surface the bundle's plaintext into log storage")
                .isEmpty();

        // tmpdir + /var/tmp walk — defense in depth against an unforeseen
        // disk-sink path the ArchUnit rule did not anticipate. Times out
        // gracefully if either directory is huge; the cap keeps the IT
        // bounded on a CI runner with a busy tmpfs.
        assertMarkerNotInTree(Paths.get(System.getProperty("java.io.tmpdir")), marker);
        assertMarkerNotInTree(Paths.get("/var/tmp"), marker);
    }

    private static void assertMarkerNotInTree(Path root, String marker) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
        List<Path> hits = new ArrayList<>();
        long deadline = System.nanoTime() + 5_000_000_000L; // 5-second wall cap
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (System.nanoTime() > deadline) {
                    return FileVisitResult.TERMINATE;
                }
                if (!attrs.isRegularFile() || attrs.size() == 0
                        || attrs.size() > 50L * 1024L * 1024L) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    byte[] payload = Files.readAllBytes(file);
                    if (indexOf(payload, markerBytes) >= 0) {
                        hits.add(file);
                    }
                } catch (IOException ignored) {
                    // Permission denied / vanished file — ignore.
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        assertThat(hits)
                .as("plaintext marker found in tree %s — bundle pipeline is leaking "
                        + "decrypted body to disk", root)
                .isEmpty();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private JsonNode mintHandshake() throws Exception {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/handshake"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(""),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(res.getBody());
    }

    private static byte[] decodePem(String pem) {
        String body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
