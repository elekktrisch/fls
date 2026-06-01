package ch.alpenflight.deployments.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
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
 * Admin endpoint contract:
 *
 * <ul>
 *   <li>SYSTEM_ADMINISTRATOR + legal transition → 200 + updated body.</li>
 *   <li>SYSTEM_ADMINISTRATOR + illegal transition → 409 with structured body.</li>
 *   <li>SYSTEM_ADMINISTRATOR + sandbox target id → 409 {@code sandbox_immutable}.</li>
 *   <li>CLUB_ADMINISTRATOR → 403.</li>
 *   <li>Unknown id → 404 (don't leak existence).</li>
 * </ul>
 *
 * <p>Tested with the production filter chain — synthesised RSA-signed JWTs
 * via {@link JwtTestFixture} go through {@code JwtDecoderConfig}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class DeploymentsAdminControllerIT extends PostgresIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired DeploymentRepository deployments;

    private final Clock clock = Clock.systemUTC();
    private String sysadminToken;
    private String clubAdminToken;
    private UUID trialDeploymentId;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM t_deployment WHERE name LIKE 'IT_AC_%'");
        sysadminToken = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-000000000001")
                .claim("realm_access", Map.of(
                        "roles", java.util.List.of("SYSTEM_ADMINISTRATOR"))));
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-000000000001")
                .claim("realm_access", Map.of(
                        "roles", java.util.List.of("CLUB_ADMINISTRATOR"))));

        UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000c001");
        Deployment trial = deployments.save(
                Deployment.startTrial(clock, "IT_AC_trial", owner));
        trialDeploymentId = trial.getId();
    }

    @Test
    void sysadmin_can_flip_trial_to_active() {
        ResponseEntity<Map<String, Object>> response = post(
                "/api/v1/admin/deployments/" + trialDeploymentId + "/lifecycle",
                Map.of("targetState", "ACTIVE"),
                sysadminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("lifecycleState", "ACTIVE");
        assertThat(response.getBody()).containsEntry("plan", "ACTIVE");
    }

    @Test
    void illegal_transition_returns_409_with_code() {
        ResponseEntity<Map<String, Object>> response = post(
                "/api/v1/admin/deployments/" + trialDeploymentId + "/lifecycle",
                Map.of("targetState", "PAST_DUE"),
                sysadminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").toString())
                .startsWith("illegal_transition");
    }

    @Test
    void sandbox_target_id_returns_409_sandbox_immutable() {
        ResponseEntity<Map<String, Object>> response = post(
                "/api/v1/admin/deployments/" + Deployment.SANDBOX_ID + "/lifecycle",
                Map.of("targetState", "ACTIVE"),
                sysadminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").toString()).contains("sandbox");
    }

    @Test
    void club_administrator_is_forbidden() {
        ResponseEntity<Map<String, Object>> response = post(
                "/api/v1/admin/deployments/" + trialDeploymentId + "/lifecycle",
                Map.of("targetState", "ACTIVE"),
                clubAdminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknown_id_returns_404() {
        UUID stranger = UUID.fromString("00000000-0000-0000-0000-00000000dead");
        ResponseEntity<Map<String, Object>> response = post(
                "/api/v1/admin/deployments/" + stranger + "/lifecycle",
                Map.of("targetState", "ACTIVE"),
                sysadminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> post(String path,
                                                     Map<String, Object> body,
                                                     String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RequestEntity<Map<String, Object>> entity =
                new RequestEntity<>(body, headers, org.springframework.http.HttpMethod.POST,
                        java.net.URI.create(path));
        return (ResponseEntity) rest.exchange(entity, Map.class);
    }
}
