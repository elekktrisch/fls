package ch.alpenflight.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.scheduling.MeasuredJob;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({JwtTestFixture.class, JobsAdminControllerIT.TestJobConfig.class})
class JobsAdminControllerIT extends PostgresIntegrationTest {

    private static final String TEST_JOB = "it-admin-console-job";
    private static final String PLANNING_JOB = "planning-day-notification";

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;

    private String sysadminToken() {
        return jwts.mint(c -> c.claim("realm_access",
                Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));
    }

    private String clubAdminToken() {
        return jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-000000000001")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void sysadmin_lists_registry_including_planning_and_test_jobs() {
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                get("/api/v1/admin/jobs", sysadminToken()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> names = response.getBody().stream()
                .map(j -> (String) j.get("name")).toList();
        assertThat(names).contains(PLANNING_JOB, TEST_JOB);
    }

    @Test
    void sysadmin_runs_job_and_gets_completed_run() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                post("/api/v1/admin/jobs/" + TEST_JOB + "/run", sysadminToken()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
        assertThat(response.getBody().get("startedAt")).isNotNull();
        assertThat(response.getBody().get("finishedAt")).isNotNull();
        assertThat(response.getBody()).containsEntry("summary", "it-processed=7");
    }

    @Test
    void club_administrator_cannot_run_a_job() {
        ResponseEntity<Void> response = rest.exchange(
                post("/api/v1/admin/jobs/" + TEST_JOB + "/run", clubAdminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void club_administrator_cannot_list_jobs() {
        ResponseEntity<Void> response = rest.exchange(
                get("/api/v1/admin/jobs", clubAdminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknown_job_name_returns_404() {
        ResponseEntity<Void> response = rest.exchange(
                post("/api/v1/admin/jobs/no-such-job/run", sysadminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static RequestEntity<Void> get(String path, String token) {
        return new RequestEntity<>(bearer(token), HttpMethod.GET, URI.create(path));
    }

    private static RequestEntity<Void> post(String path, String token) {
        return new RequestEntity<>(bearer(token), HttpMethod.POST, URI.create(path));
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @TestConfiguration
    static class TestJobConfig {
        @Bean
        ConsoleTestJob consoleTestJob() {
            return new ConsoleTestJob();
        }
    }

    @MeasuredJob(name = TEST_JOB, cron = "0 0 0 1 1 ?", description = "Admin-console IT job")
    static class ConsoleTestJob implements ch.alpenflight.platform.scheduling.BusinessJob {
        @Override
        public String runOnce() {
            return "it-processed=7";
        }
    }
}
