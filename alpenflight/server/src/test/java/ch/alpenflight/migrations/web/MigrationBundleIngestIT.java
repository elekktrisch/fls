package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.MockKeycloakDirectoryConfig;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
 * S-141 happy-path walking-skeleton integration test. Round-trips a
 * minimal encrypted bundle (manifest only, 1 Club, no NDJSON entries)
 * through the full pipeline and verifies the DB + audit + funnel state.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({JwtTestFixture.class, MockKeycloakDirectoryConfig.class})
class MigrationBundleIngestIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MigrationBundleCipher cipher;
    @Autowired KeycloakDeploymentDirectory directory;

    private UUID userId;
    private UUID userSub;
    private String verifiedToken;
    private String testClubKey;
    private String testClubSlug;

    @BeforeEach
    void seedUser() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        // Per-test unique Club key + slug — t_club.club_key + t_club.slug
        // are globally UNIQUE. Two MigrationBundleIngestIT methods running
        // back-to-back in the same JVM would collide on a hardcoded literal.
        // Club.key cap is 10 chars; use the leading 5 hex chars of userSub
        // to keep `IT-<5hex>` inside the cap.
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "IT-" + tag;
        testClubSlug = "aero-it-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "bundle-it-" + userSub, "Bundle IT",
                "bundle-" + userSub + "@example.com",
                SEED_LANGUAGE_DE.toString(), userSub.toString());
        verifiedToken = jwts.mint(c -> c
                .subject(userSub.toString())
                .claim("email_verified", true));

        // Mockable Keycloak boundary — no real realm in this IT. The
        // provision-on-migrate slice (J-0c T-02) calls
        // provisionClubAdminIdentity per migrated Club; return a synthetic
        // sub so the ingest pipeline completes without an upstream realm.
        Mockito.reset(directory);
        when(directory.provisionClubAdminIdentity(
                any(UUID.class), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> UUID.randomUUID());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?", userSub.toString());
        jdbc.update("DELETE FROM t_migration_run WHERE upload_id IN "
                + "(SELECT id FROM t_migration_upload WHERE user_id = ?::uuid)", userId.toString());
        jdbc.update("DELETE FROM t_migration_upload WHERE user_id = ?::uuid", userId.toString());
        // The provisioning side created a Deployment + Club + per-Club
        // reference data (t_flight_type, t_member_state). Tear those down
        // before the Club so the FKs don't block.
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
    void happy_path_round_trips_a_walking_skeleton_bundle() throws Exception {
        // 1. Mint a handshake row via the S-140 endpoint.
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        // 2. Build an encrypted bundle: manifest declares 1 Club, no NDJSON entries.
        UUID legacyClubId = UUID.randomUUID();
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "Aero Club IT", testClubSlug,
                testClubKey, false, SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        byte[] bundle = MigrationBundleTestFactory.buildWalkingSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Aero Club IT Deployment", club);

        // 3. POST the bundle.
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/" + uploadId + "/bundle"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bundle),
                String.class);

        // 4. Response carries the new Deployment + Club.
        assertThat(res.getStatusCode())
                .as("Bundle ingest returned non-200; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.has("deploymentId")).isTrue();
        assertThat(body.has("clubIds")).isTrue();
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        assertThat(body.get("clubIds").size()).isEqualTo(1);

        // 5. Upload row transitioned CONSUMED + private key wiped.
        Map<String, Object> upload = jdbc.queryForMap(
                "SELECT state, private_key_ciphertext, consumed_at FROM t_migration_upload WHERE id = ?::uuid",
                uploadId.toString());
        assertThat(upload.get("state")).isEqualTo("CONSUMED");
        assertThat(upload.get("private_key_ciphertext")).isNull();
        assertThat(upload.get("consumed_at")).isNotNull();

        // 6. Run row reached COMPLETED.
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT state, deployment_id, error_code FROM t_migration_run WHERE upload_id = ?::uuid",
                uploadId.toString());
        assertThat(run.get("state")).isEqualTo("COMPLETED");
        assertThat(UUID.fromString(run.get("deployment_id").toString())).isEqualTo(deploymentId);
        assertThat(run.get("error_code")).isNull();

        // 7. Deployment + Club rows landed.
        Map<String, Object> deployment = jdbc.queryForMap(
                "SELECT name, lifecycle_state, owner_keycloak_sub FROM t_deployment WHERE id = ?::uuid",
                deploymentId.toString());
        assertThat(deployment.get("name")).isEqualTo("Aero Club IT Deployment");
        assertThat(deployment.get("lifecycle_state")).isEqualTo("TRIAL");
        assertThat(UUID.fromString(deployment.get("owner_keycloak_sub").toString())).isEqualTo(userSub);
        Integer clubCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_club WHERE deployment_id = ?::uuid",
                Integer.class, deploymentId.toString());
        assertThat(clubCount).isEqualTo(1);

        // 8. Two ingest-audit rows: STARTED (inside txn) + COMPLETED (post-commit).
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_mutation_audit_event WHERE target_entity_id = ?::uuid "
                        + "AND action IN ('MIGRATION_INGEST_STARTED', 'MIGRATION_INGEST_COMPLETED')",
                Integer.class, uploadId.toString());
        assertThat(auditCount).isEqualTo(2);
    }

    @Test
    void two_club_bundle_provisions_a_keycloak_club_admin_identity_per_club() throws Exception {
        // 1. Handshake.
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        // 2. Two-Club bundle (the J-0c fan-out shape — two migrated clubs).
        String tagA = "a" + testClubSlug.substring(testClubSlug.length() - 4);
        String tagB = "b" + testClubSlug.substring(testClubSlug.length() - 4);
        BundleManifest.ClubDeclaration clubA = new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), "Aero Club A", "aero-" + tagA,
                "K-" + tagA, false, SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        BundleManifest.ClubDeclaration clubB = new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), "Aero Club B", "aero-" + tagB,
                "K-" + tagB, false, SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        byte[] bundle = MigrationBundleTestFactory.buildMultiClubSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Two Club Deployment", List.of(clubA, clubB));

        // 3. Ingest.
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/" + uploadId + "/bundle"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bundle),
                String.class);
        assertThat(res.getStatusCode())
                .as("Bundle ingest returned non-200; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.get("clubIds").size()).isEqualTo(2);
        UUID provisionedClubA = UUID.fromString(body.get("clubIds").get(0).asText());
        UUID provisionedClubB = UUID.fromString(body.get("clubIds").get(1).asText());

        // 4. One Keycloak club-admin identity per PROVISIONED Club, keyed
        // off the provisioned (not legacy) club UUID — so a real login
        // carrying that clubId claim lands in the right tenant. The
        // firstName/lastName are non-blank (J-1 T-06) so the migrated admin
        // clears Keycloak's VERIFY_PROFILE on first login without the
        // (removed) e2e name fixup.
        verify(directory, times(2)).provisionClubAdminIdentity(
                any(UUID.class), anyString(), anyString(), anyString(), anyString());
        verify(directory).provisionClubAdminIdentity(
                eq(provisionedClubA),
                eq("migrated-admin+" + provisionedClubA + "@migrated.alpenflight.local"),
                eq("migrated-admin+" + provisionedClubA + "@migrated.alpenflight.local"),
                eq("Migrated"), eq("Admin"));
        verify(directory).provisionClubAdminIdentity(
                eq(provisionedClubB),
                eq("migrated-admin+" + provisionedClubB + "@migrated.alpenflight.local"),
                eq("migrated-admin+" + provisionedClubB + "@migrated.alpenflight.local"),
                eq("Migrated"), eq("Admin"));
    }

    @Test
    void non_owner_returns_403() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID otherUserSub = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                otherUserId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "intruder-" + otherUserSub, "Intruder", "intruder@example.com",
                SEED_LANGUAGE_DE.toString(), otherUserSub.toString());
        String otherToken = jwts.mint(c -> c
                .subject(otherUserSub.toString())
                .claim("email_verified", true));

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), "Aero Club IT", testClubSlug,
                testClubKey, false, SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        byte[] bundle = MigrationBundleTestFactory.buildWalkingSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Aero Club IT Deployment", club);

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/" + uploadId + "/bundle"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bundle),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("BUNDLE_FORBIDDEN");

        // Cleanup
        jdbc.update("DELETE FROM t_migration_run WHERE upload_id = ?::uuid", uploadId.toString());
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?", otherUserSub.toString());
        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", otherUserId.toString());
    }

    @Test
    void status_endpoint_returns_run_state() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), "Aero Club IT", testClubSlug,
                testClubKey, false, SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        byte[] bundle = MigrationBundleTestFactory.buildWalkingSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Aero Club IT Deployment", club);
        rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/" + uploadId + "/bundle"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bundle),
                String.class);

        ResponseEntity<String> status = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/migrations/" + uploadId + "/status"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
                        .build(),
                String.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(status.getBody());
        assertThat(body.get("uploadId").asText()).isEqualTo(uploadId.toString());
        assertThat(body.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(body.has("deploymentId")).isTrue();
        assertThat(body.get("clubIds").size()).isEqualTo(1);
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
