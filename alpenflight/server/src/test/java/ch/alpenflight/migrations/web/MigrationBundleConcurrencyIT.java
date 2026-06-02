package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.MockKeycloakDirectoryConfig;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-141b AC3 — per-upload row-lock 409 surfaces as
 * {@code BUNDLE_INGEST_IN_PROGRESS}. A helper thread holds a
 * {@code PESSIMISTIC_WRITE} lock on the upload row through a
 * {@link TransactionTemplate}; the controller's
 * {@code lockUpload(timeout=0)} surfaces the contention immediately as
 * 409 (not 429 from the global gate — those are semantically distinct).
 *
 * <p>Tagged {@code slow} so the inner-loop {@code ./gradlew test} can
 * skip the lock-contention budget; CI runs everything.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({JwtTestFixture.class, MockKeycloakDirectoryConfig.class})
@Tag("slow")
class MigrationBundleConcurrencyIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MigrationBundleCipher cipher;
    @Autowired PlatformTransactionManager txManager;

    private UUID userId;
    private UUID userSub;
    private String verifiedToken;
    private String testClubKey;
    private String testClubSlug;

    @BeforeEach
    void seedUser() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "IT-" + tag;
        testClubSlug = "conc-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "conc-it-" + userSub, "Conc IT",
                "conc-" + userSub + "@example.com",
                SEED_LANGUAGE_DE.toString(), userSub.toString());
        verifiedToken = jwts.mint(c -> c
                .subject(userSub.toString())
                .claim("email_verified", true));
    }

    @AfterEach
    void cleanup() {
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
    void per_upload_row_lock_surfaces_409_bundle_ingest_in_progress() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        byte[] bundle = MigrationBundleTestFactory.buildWalkingSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Concurrency IT",
                clubDeclaration("Aero Club Conc IT"));

        // A helper thread takes a PESSIMISTIC_WRITE lock on the upload row
        // and holds it until releaseLock fires. The controller's
        // lockUpload(timeout=0) under POST will fail immediately on the
        // contended row → BundleIngestException(BUNDLE_INGEST_IN_PROGRESS).
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        Thread lockHolder = new Thread(() -> {
            TransactionTemplate template = new TransactionTemplate(txManager);
            template.execute(status -> {
                // Native SQL with explicit FOR UPDATE — same pessimistic
                // row lock the orchestrator's lockUpload takes via JPA's
                // PESSIMISTIC_WRITE hint, without the JPQL the project's
                // bare-table-name linter (FixtureTableNamingConventionTest)
                // does not understand.
                jdbc.queryForMap(
                        "SELECT id FROM t_migration_upload WHERE id = ?::uuid FOR UPDATE",
                        uploadId.toString());
                lockAcquired.countDown();
                try {
                    releaseLock.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        }, "migration-bundle-IT-lock-holder");
        lockHolder.start();

        try {
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS))
                    .as("helper thread failed to acquire PESSIMISTIC_WRITE within 5s")
                    .isTrue();

            ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            JsonNode body = JSON.readTree(res.getBody());
            assertThat(body.get("errorCode").asText())
                    .as("per-upload row-lock contention must surface 409 "
                            + "BUNDLE_INGEST_IN_PROGRESS, not 429 DATABASE_CAPACITY_EXCEEDED")
                    .isEqualTo("BUNDLE_INGEST_IN_PROGRESS");

            // Exactly zero Deployment rows for this caller — the failed
            // POST rolled back inside runInsideTransaction.
            Integer deploymentCount = jdbc.queryForObject(
                    "SELECT count(*) FROM t_deployment WHERE owner_keycloak_sub = ?::uuid",
                    Integer.class, userSub.toString());
            assertThat(deploymentCount).isZero();
        } finally {
            releaseLock.countDown();
            lockHolder.join(15_000);
        }
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

    private ResponseEntity<String> postBundle(UUID uploadId, byte[] body, String token) {
        return rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/" + uploadId + "/bundle"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(body),
                String.class);
    }

    private BundleManifest.ClubDeclaration clubDeclaration(String name) {
        return new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), name, testClubSlug, testClubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
    }

    private static byte[] decodePem(String pem) {
        String body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
