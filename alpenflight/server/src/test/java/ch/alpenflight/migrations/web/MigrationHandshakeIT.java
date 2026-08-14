package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migrations.application.MigrationHandshakeExpiryJob;
import ch.alpenflight.migrations.domain.MigrationCryptoService;
import ch.alpenflight.migrations.domain.MigrationUploadState;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class MigrationHandshakeIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID SEED_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID LANG_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MigrationCryptoService crypto;
    @Autowired MigrationHandshakeExpiryJob expiryJob;

    private UUID userId;
    private UUID userSub;
    private String verifiedToken;

    @BeforeEach
    void seedUser() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_CLUB.toString(),
                "handshake-it-" + userSub, "Handshake IT",
                "handshake-" + userSub + "@example.com",
                LANG_DE.toString(), userSub.toString());
        verifiedToken = mintEmailVerified(userSub);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?",
                userSub.toString());
        jdbc.update("DELETE FROM t_migration_upload WHERE user_id = ?::uuid", userId.toString());
        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", userId.toString());
    }

    @Test
    void handshake_happy_path_returns_body_and_persists_row() throws Exception {
        ResponseEntity<String> res = postHandshake(verifiedToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = MAPPER.readTree(res.getBody());
        assertThat(body.has("uploadId")).isTrue();
        assertThat(body.has("publicKeyPem")).isTrue();
        assertThat(body.has("expiresAt")).isTrue();
        assertThat(body.size()).as("Response must be exactly 3 fields").isEqualTo(3);

        UUID uploadId = UUID.fromString(body.get("uploadId").asText());
        assertThat(body.get("publicKeyPem").asText())
                .startsWith("-----BEGIN PUBLIC KEY-----\n")
                .endsWith("-----END PUBLIC KEY-----\n");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, private_key_ciphertext, expires_at FROM t_migration_upload WHERE id = ?::uuid",
                uploadId.toString());
        assertThat(row.get("state")).isEqualTo("AWAITING_UPLOAD");
        byte[] ciphertext = (byte[]) row.get("private_key_ciphertext");
        assertThat(ciphertext).isNotEmpty();

        byte[] pkcs8 = crypto.unwrap(uploadId, ciphertext);
        PrivateKey priv = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decodePem(body.get("publicKeyPem").asText())));
        assertThat(priv.getAlgorithm()).isEqualTo("RSA");
        assertThat(pub.getAlgorithm()).isEqualTo("RSA");

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT action, actor_user_id, system_actor, before_state, after_state "
                        + "FROM t_mutation_audit_event WHERE target_entity_id = ?::uuid",
                uploadId.toString());
        assertThat(audit.get("action")).isEqualTo("MIGRATION_HANDSHAKE_ISSUED");
        assertThat(audit.get("actor_user_id")).isEqualTo(userId);
        assertThat(audit.get("system_actor")).isEqualTo(false);
        assertThat(audit.get("before_state")).isNull();
        assertThat(audit.get("after_state")).asString().contains("<bytes:");
    }

    @Test
    void get_current_returns_404_when_no_in_flight_row() {
        ResponseEntity<String> res = rest.exchange(authed(
                RequestEntity.get(URI.create("/api/v1/migrations/handshake/current")),
                verifiedToken).build(), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_current_returns_existing_row_after_handshake() throws Exception {
        ResponseEntity<String> first = postHandshake(verifiedToken);
        JsonNode firstBody = MAPPER.readTree(first.getBody());
        ResponseEntity<String> current = rest.exchange(authed(
                RequestEntity.get(URI.create("/api/v1/migrations/handshake/current")),
                verifiedToken).build(), String.class);

        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = MAPPER.readTree(current.getBody());
        assertThat(body.get("uploadId").asText()).isEqualTo(firstBody.get("uploadId").asText());
        assertThat(body.get("publicKeyPem").asText()).isEqualTo(firstBody.get("publicKeyPem").asText());
    }

    @Test
    void anonymous_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/handshake"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unverified_email_returns_403() {
        String token = jwts.mint(c -> c.subject(userSub.toString()).claim("email_verified", false));
        ResponseEntity<String> res = postHandshake(token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void second_handshake_supersedes_prior_row() throws Exception {
        ResponseEntity<String> first = postHandshake(verifiedToken);
        UUID firstId = UUID.fromString(MAPPER.readTree(first.getBody()).get("uploadId").asText());

        ResponseEntity<String> second = postHandshake(verifiedToken);
        UUID secondId = UUID.fromString(MAPPER.readTree(second.getBody()).get("uploadId").asText());

        assertThat(secondId).isNotEqualTo(firstId);

        Map<String, Object> oldRow = jdbc.queryForMap(
                "SELECT state, private_key_ciphertext FROM t_migration_upload WHERE id = ?::uuid",
                firstId.toString());
        assertThat(oldRow.get("state")).isEqualTo("SUPERSEDED");
        assertThat(oldRow.get("private_key_ciphertext")).isNull();

        Map<String, Object> newRow = jdbc.queryForMap(
                "SELECT state, private_key_ciphertext FROM t_migration_upload WHERE id = ?::uuid",
                secondId.toString());
        assertThat(newRow.get("state")).isEqualTo("AWAITING_UPLOAD");
        assertThat((byte[]) newRow.get("private_key_ciphertext")).isNotEmpty();

        List<Map<String, Object>> trail = jdbc.queryForList(
                "SELECT action FROM t_mutation_audit_event "
                        + "WHERE actor_keycloak_sub = ? ORDER BY occurred_at, action",
                userSub.toString());
        assertThat(trail).extracting(r -> r.get("action"))
                .contains("MIGRATION_HANDSHAKE_ISSUED", "MIGRATION_HANDSHAKE_SUPERSEDED");
    }

    @Test
    void partial_unique_enforces_one_in_flight_row_per_user() throws Exception {
        postHandshake(verifiedToken);
        Integer inFlight = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_migration_upload WHERE user_id = ?::uuid AND state = 'AWAITING_UPLOAD'",
                Integer.class, userId.toString());
        assertThat(inFlight).isEqualTo(1);
    }

    @Test
    void expiry_job_sweeps_rows_past_ttl_and_wipes_key() throws Exception {
        UUID expiredId = UUID.randomUUID();
        byte[] wrapped = crypto.wrap(expiredId, new byte[] {1, 2, 3, 4, 5});
        jdbc.update("""
                INSERT INTO t_migration_upload
                  (id, user_id, state, public_key_pem, private_key_ciphertext, created_at, updated_at, expires_at)
                VALUES (?::uuid, ?::uuid, 'AWAITING_UPLOAD', 'pem', ?, now() - interval '48h',
                        now() - interval '48h', now() - interval '24h')
                """, expiredId.toString(), userId.toString(), wrapped);

        int swept = expiryJob.runOnce();
        assertThat(swept).isGreaterThanOrEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, private_key_ciphertext FROM t_migration_upload WHERE id = ?::uuid",
                expiredId.toString());
        assertThat(row.get("state")).isEqualTo("EXPIRED");
        assertThat(row.get("private_key_ciphertext")).isNull();

        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT action, system_actor, actor_user_id, tenant_club_id, target_entity_type "
                        + "FROM t_mutation_audit_event WHERE target_entity_id = ?::uuid",
                expiredId.toString());
        assertThat(audits)
                .as("Expected exactly one MIGRATION_HANDSHAKE_EXPIRED audit row for upload %s; got %s",
                        expiredId, audits)
                .hasSize(1);
        Map<String, Object> audit = audits.get(0);
        assertThat(audit.get("action")).isEqualTo("MIGRATION_HANDSHAKE_EXPIRED");
        assertThat(audit.get("system_actor")).isEqualTo(true);

        int sweptTwice = expiryJob.runOnce();
        assertThat(sweptTwice).isZero();
        Integer expiredAuditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_mutation_audit_event "
                        + "WHERE target_entity_id = ?::uuid AND action = 'MIGRATION_HANDSHAKE_EXPIRED'",
                Integer.class, expiredId.toString());
        assertThat(expiredAuditRows).isEqualTo(1);
    }

    private ResponseEntity<String> postHandshake(String token) {
        return rest.exchange(authed(
                RequestEntity.post(URI.create("/api/v1/migrations/handshake"))
                        .contentType(MediaType.APPLICATION_JSON),
                token).body(""),
                String.class);
    }

    private static <T extends RequestEntity.HeadersBuilder<?>> T authed(T builder, String token) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return builder;
    }

    private static byte[] decodePem(String pem) {
        String body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return java.util.Base64.getDecoder().decode(body);
    }

    private String mintEmailVerified(UUID sub) {
        return jwts.mint(c -> c
                .subject(sub.toString())
                .claim("email_verified", true));
    }
}
