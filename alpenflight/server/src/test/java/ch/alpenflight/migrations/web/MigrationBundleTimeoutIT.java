package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.tenancy.provisioning.application.DeploymentProvisioningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
@TestPropertySource(properties = {
        "alpenflight.migration.bundle-timeout=PT2S"
})
@Tag("slow")
class MigrationBundleTimeoutIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long PROVISIONING_SLEEP_FAR_PAST_THE_TWO_SECOND_CAP_MS = 30_000L;

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MigrationBundleCipher cipher;

    @MockitoSpyBean DeploymentProvisioningService provisioning;

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
        testClubSlug = "tmout-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "tmout-it-" + userSub, "Tmout IT",
                "tmout-" + userSub + "@example.com",
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
    void bundle_timeout_interrupts_in_flight_worker_and_records_failure() throws Exception {
        doAnswer(inv -> {
            try {
                Thread.sleep(PROVISIONING_SLEEP_FAR_PAST_THE_TWO_SECOND_CAP_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            return inv.callRealMethod();
        }).when(provisioning).provision(any());

        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), "Tmout Club", testClubSlug, testClubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        byte[] bundle = MigrationBundleTestFactory.buildWalkingSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Tmout Deployment", club);

        long before = System.nanoTime();
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/" + uploadId + "/bundle"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bundle),
                String.class);
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;

        assertThat(res.getStatusCode())
                .as("expected 408 BUNDLE_TIMEOUT after the 2s wall-clock cap; body=%s, elapsed=%dms",
                        res.getBody(), elapsedMs)
                .isEqualTo(HttpStatus.REQUEST_TIMEOUT);
        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("BUNDLE_TIMEOUT");
        assertThat(elapsedMs)
                .as("controller must return within ~3s once the 2s timeout fires "
                        + "— if elapsed is >10s the interrupt did not stop the worker")
                .isLessThan(10_000L);

        Integer deploymentCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_deployment WHERE owner_keycloak_sub = ?::uuid",
                Integer.class, userSub.toString());
        assertThat(deploymentCount)
                .as("nothing was provisioned — the worker's transaction rolled back when the "
                        + "cancel interrupted it")
                .isZero();

        Map<String, Object> upload = jdbc.queryForMap(
                "SELECT state, private_key_ciphertext FROM t_migration_upload WHERE id = ?::uuid",
                uploadId.toString());
        assertThat(upload.get("state")).isEqualTo("FAILED");
        assertThat(upload.get("private_key_ciphertext"))
                .as("the private key is wiped even on the timeout path")
                .isNull();
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
