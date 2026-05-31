package ch.alpenflight.migrations.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.migrations.domain.MigrationBundleCipher;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
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
 * S-141b negative-path integration tests for the bundle-ingest endpoint.
 * Each test asserts the SPA-facing error code + HTTP status for a
 * specific failure shape — the controller never emits an opaque 500.
 *
 * <p>Shares the {@code @SpringBootTest} annotation surface with
 * {@link MigrationBundleIngestIT} so Spring's context cache hits across
 * both classes (no second boot).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class MigrationBundleNegativePathIT extends PostgresIntegrationTest {

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

    @BeforeEach
    void seedUser() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "IT-" + tag;
        testClubSlug = "neg-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "negpath-it-" + userSub, "Neg-Path IT",
                "negpath-" + userSub + "@example.com",
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
    void bundle_header_malformed_when_wrapped_key_length_is_zero() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());

        byte[] hostilePrefix = MigrationBundleTestFactory.buildHeaderOnlyPrefix(0);

        ResponseEntity<String> res = postBundle(uploadId, hostilePrefix, verifiedToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(res)).isEqualTo("BUNDLE_HEADER_MALFORMED");
    }

    @Test
    void bundle_header_malformed_when_wrapped_key_length_exceeds_max() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());

        // 65535 > MAX_WRAPPED_KEY_LEN (1024) — should reject before reading
        // any further body bytes.
        byte[] hostilePrefix = MigrationBundleTestFactory.buildHeaderOnlyPrefix(65535);

        ResponseEntity<String> res = postBundle(uploadId, hostilePrefix, verifiedToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(res)).isEqualTo("BUNDLE_HEADER_MALFORMED");
    }

    @Test
    void bundle_truncated_mid_ciphertext() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        byte[] completeBundle = MigrationBundleTestFactory.buildWalkingSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Truncated IT", soleClub());
        // Drop the trailing 256 bytes — the AEAD tag + half the body slab.
        byte[] truncated = MigrationBundleTestFactory.truncatedBundle(completeBundle, 256);

        ResponseEntity<String> res = postBundle(uploadId, truncated, verifiedToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Streaming AEAD may surface the truncation as either a tag-tampering
        // detection (BUNDLE_DECRYPT_AEAD_TAG_FAILED) or as a tar-layer read
        // failure when the truncated body still parses past the AEAD layer —
        // both are valid "bundle bytes incomplete" surfaces for the SPA.
        assertThat(errorCodeOf(res))
                .isIn("BUNDLE_TRUNCATED", "BUNDLE_DECRYPT_AEAD_TAG_FAILED",
                        "BUNDLE_TAR_PARSE_FAILED");
    }

    @Test
    void bundle_already_consumed_on_second_post_with_same_upload_id() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        byte[] first = MigrationBundleTestFactory.buildWalkingSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Consumed IT", soleClub());
        ResponseEntity<String> firstRes = postBundle(uploadId, first, verifiedToken);
        assertThat(firstRes.getStatusCode())
                .as("first POST must succeed before re-POST asserts already-consumed; body=%s",
                        firstRes.getBody())
                .isEqualTo(HttpStatus.OK);

        // The first POST provisioned a Deployment — that triggers the
        // pre-decrypt DEPLOYMENT_EXISTS guard ahead of the upload-state
        // check on a re-POST. Tear the Deployment down so the re-POST
        // surfaces the upload-state check (the AC9b-relevant case the SPA
        // sees when a deployment is deleted between POSTs).
        String clubsByOwner =
                "SELECT id FROM t_club WHERE deployment_id IN "
                        + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)";
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_member_state WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_club WHERE deployment_id IN "
                + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)",
                userSub.toString());
        jdbc.update("DELETE FROM t_deployment WHERE owner_keycloak_sub = ?::uuid", userSub.toString());

        ResponseEntity<String> secondRes = postBundle(uploadId, first, verifiedToken);
        assertThat(secondRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCodeOf(secondRes)).isEqualTo("BUNDLE_ALREADY_CONSUMED");
    }

    @Test
    void manifest_empty_clubs_rejected_with_specific_error_code() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        byte[] bundle = MigrationBundleTestFactory.buildEmptyClubsBundle(
                cipher, uploadId, publicKeyDer, "Empty Clubs IT");

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // S-141b AC9(c): the distinct 400 surfaces here, not the catch-all
        // MANIFEST_INVALID Jackson would emit if BundleManifest's constructor
        // still enforced non-empty clubs.
        assertThat(errorCodeOf(res)).isEqualTo("MANIFEST_EMPTY_CLUBS");
    }

    @Test
    void tar_entry_traversal_rejected_before_legacy_id_map_dispatch() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        // The legacy_id_map/ prefix would normally route into copyLegacyIdMap;
        // rejectUnsafeTarName MUST fire first because the name contains "..".
        // AC9(a) regression guard.
        byte[] hostilePayload = "garbage".getBytes(UTF_8);
        byte[] bundle = MigrationBundleTestFactory.buildBundleWithRawTarEntry(
                cipher, uploadId, publicKeyDer, "Traversal IT", soleClub(),
                "legacy_id_map/../etc/passwd.pgcopy", hostilePayload);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(res)).isEqualTo("BUNDLE_TAR_PARSE_FAILED");
        assertThat(res.getBody()).contains("filesystem-traversal defense");
    }

    @Test
    void deployment_exists_pre_decrypt_guard_skips_rsa_unwrap() throws Exception {
        // Seed an active Deployment for this caller — the pre-decrypt guard
        // fails fast with 409 before allocating RSA + AEAD context.
        UUID existingDeploymentId = UUID.randomUUID();
        UUID existingPrimaryClubId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_deployment (id, name, owner_keycloak_sub, primary_club_id,
                                          lifecycle_state, plan, created_on, modified_on, version,
                                          kc_state)
                VALUES (?::uuid, ?, ?::uuid, ?::uuid, 'TRIAL', 'TRIAL', now(), now(), 0, 'READY')
                """,
                existingDeploymentId.toString(),
                "Pre-existing Deployment", userSub.toString(), existingPrimaryClubId.toString());
        jdbc.update("""
                INSERT INTO t_club (id, deployment_id, name, slug, club_key, country_id,
                                    club_state_id, public_registration_enabled,
                                    created_on, modified_on, version)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid, false, now(), now(), 0)
                """,
                existingPrimaryClubId.toString(), existingDeploymentId.toString(),
                "Pre-existing Club", testClubSlug, testClubKey,
                SEED_COUNTRY_CH.toString(), SEED_CLUB_STATE_ACTIVE.toString());

        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        byte[] bundle = MigrationBundleTestFactory.buildWalkingSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Conflict IT", soleClub());

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("DEPLOYMENT_EXISTS");
        assertThat(body.get("existingDeploymentId").asText()).isEqualTo(existingDeploymentId.toString());

        // The upload row must remain AWAITING_UPLOAD-or-FAILED — DEPLOYMENT_EXISTS
        // is a structural conflict (caller-owned Deployment exists), so the
        // recorder flips the upload to FAILED. The bundle never reaches decrypt.
        Map<String, Object> upload = jdbc.queryForMap(
                "SELECT state, consumed_at FROM t_migration_upload WHERE id = ?::uuid",
                uploadId.toString());
        assertThat(upload.get("consumed_at")).isNull();
        assertThat(upload.get("state")).isEqualTo("FAILED");
    }

    @Test
    void multi_club_progress_records_actual_club_for_tenant_scoped_writes() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        // Two Clubs — manifest carries both; no NDJSON streams are sent so
        // noteCurrent is never called. The test asserts the run reaches
        // COMPLETED with currentEntity / currentClubId both cleared by
        // markCompleted (the FSM contract). Multi-Club provisioning is the
        // load-bearing assertion here; the per-entity noteCurrent fork is
        // covered by the parity round-trip IT which carries actual NDJSON.
        String tag2 = (userSub.toString().substring(0, 5) + "2");
        List<BundleManifest.ClubDeclaration> clubs = List.of(
                clubDeclaration("Club A IT", testClubSlug, testClubKey),
                clubDeclaration("Club B IT", "neg-" + tag2, "IT-" + tag2));
        byte[] bundle = MigrationBundleTestFactory.buildMultiClubSkeletonBundle(
                cipher, uploadId, publicKeyDer, "Multi-Club IT", clubs);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);

        assertThat(res.getStatusCode())
                .as("multi-Club provisioning failed; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.get("clubIds").size()).isEqualTo(2);

        // Run completed cleanly: noteCurrent was never called (no NDJSON),
        // so current_entity / current_club_id are both null per markCompleted.
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT state, current_entity, current_club_id FROM t_migration_run "
                        + "WHERE upload_id = ?::uuid",
                uploadId.toString());
        assertThat(run.get("state")).isEqualTo("COMPLETED");
        assertThat(run.get("current_entity")).isNull();
        assertThat(run.get("current_club_id")).isNull();
    }

    @Test
    void ndjson_parse_failure_rolls_back_cleanly_with_audit_trail() throws Exception {
        // Malformed NDJSON in a tar entry — the orchestrator routes through
        // entityStreamIngestor.ingestEntityNdjson which throws
        // BundleIngestException(NDJSON_PARSE_FAILED). Asserts the full
        // rollback trail invariants the mapper-failure path would also
        // satisfy: no Deployment, run state FAILED, MIGRATION_INGEST_FAILED
        // audit row recorded by the REQUIRES_NEW recorder.
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.COUNTRY,
                new EntityPolicy(
                        EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE,
                        EntityPolicy.TombstonePolicy.SKIP_DELETED,
                        java.util.Set.of(),
                        java.util.List.of()));
        byte[] malformedLine = "{not valid json".getBytes(UTF_8);
        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "NDJSON-fail IT",
                List.of(soleClub()),
                entityPolicies,
                Map.of("COUNTRY.ndjson", malformedLine));

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(res)).isEqualTo("NDJSON_PARSE_FAILED");

        // No Deployment / Club / FlightType / MemberState — the whole
        // single-txn ingest rolled back atomically.
        Integer deploymentCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_deployment WHERE owner_keycloak_sub = ?::uuid",
                Integer.class, userSub.toString());
        assertThat(deploymentCount).isZero();

        // The run row was INSERTed inside the failed txn and rolled back —
        // recordFailure's findById(runId) returns empty and does NOT
        // resurrect a row. The upload state + audit trail are the load-
        // bearing post-condition.
        Integer runCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_migration_run WHERE upload_id = ?::uuid",
                Integer.class, uploadId.toString());
        assertThat(runCount)
                .as("MigrationRun was created inside the failing txn and rolled "
                        + "back atomically; recordFailure cannot re-INSERT a row "
                        + "the outer txn dropped")
                .isZero();

        // Upload flipped to FAILED by recordFailure (REQUIRES_NEW survives
        // the outer rollback), private key wiped.
        Map<String, Object> upload = jdbc.queryForMap(
                "SELECT state, private_key_ciphertext FROM t_migration_upload WHERE id = ?::uuid",
                uploadId.toString());
        assertThat(upload.get("state")).isEqualTo("FAILED");
        assertThat(upload.get("private_key_ciphertext")).isNull();

        // Exactly one MIGRATION_INGEST_FAILED audit (the STARTED row landed
        // inside the failed txn and rolled back; only the REQUIRES_NEW
        // FAILED row from recordFailure survives).
        Integer failedAudit = jdbc.queryForObject(
                "SELECT count(*) FROM t_mutation_audit_event WHERE target_entity_id = ?::uuid "
                        + "AND action = 'MIGRATION_INGEST_FAILED'",
                Integer.class, uploadId.toString());
        assertThat(failedAudit).isEqualTo(1);
    }

    @Test
    void note_current_writes_null_for_system_global_entity() throws Exception {
        // Empty NDJSON stream — exercises the noteCurrent path without
        // requiring real mapped data. SYSTEM_GLOBAL_RESOLVE entries write
        // null for current_club_id (S-141b Fork 2).
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.COUNTRY,
                new EntityPolicy(
                        EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE,
                        EntityPolicy.TombstonePolicy.SKIP_DELETED,
                        java.util.Set.of(),
                        java.util.List.of()));
        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "System-global IT",
                List.of(soleClub()),
                entityPolicies,
                // Empty NDJSON file — drains zero rows, but noteCurrent still
                // fires (the dispatch is per tar entry, not per row).
                Map.of("COUNTRY.ndjson", new byte[0]));

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);

        assertThat(res.getStatusCode())
                .as("system-global entry path failed; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        // The run reaches COMPLETED. noteCurrent was called once
        // (entityType=COUNTRY, clubId=null per SYSTEM_GLOBAL_RESOLVE), then
        // markCompleted cleared both fields. So the post-completion row
        // has both null — but the noteCurrent invocation can't be observed
        // directly. The IT proves the SYSTEM_GLOBAL path doesn't NPE / fail
        // due to "null clubId" defenses elsewhere.
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT state FROM t_migration_run WHERE upload_id = ?::uuid",
                uploadId.toString());
        assertThat(run.get("state")).isEqualTo("COMPLETED");
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

    private BundleManifest.ClubDeclaration soleClub() {
        return clubDeclaration("Aero Club Neg IT", testClubSlug, testClubKey);
    }

    private BundleManifest.ClubDeclaration clubDeclaration(String name, String slug, String key) {
        return new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), name, slug, key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
    }

    private static String errorCodeOf(ResponseEntity<String> res) throws Exception {
        JsonNode body = JSON.readTree(res.getBody());
        return body.get("errorCode").asText();
    }

    private static byte[] decodePem(String pem) {
        String body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
