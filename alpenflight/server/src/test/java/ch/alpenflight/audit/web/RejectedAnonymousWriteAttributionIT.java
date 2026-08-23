package ch.alpenflight.audit.web;

import static ch.alpenflight.audit.web.AuditTestSupport.preCleanAuditRowsThatOutliveTestRollback;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.PublicSubmissions;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RejectedAnonymousWriteAttributionIT extends PostgresIntegrationTest {

    private static final String SUBMITTER_IP = "203.0.113.77";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final LocalDate DAY_THE_CLUB_NEVER_PUBLISHED = LocalDate.of(2099, 7, 21);
    private static final int SUBMITS_UNTIL_THE_GUARD_REJECTS = 11;

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubId;
    private String clubSlug;

    @BeforeEach
    void seedAClubThatAcceptsPublicRegistrations() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_RAW_", "IT_RAW_");
        fixture.seed();
        clubId = fixture.clubA();

        Club club = clubs.findActiveById(clubId).orElseThrow();
        club.enablePublicRegistration();
        clubs.save(club);
        clubSlug = Objects.requireNonNull(club.getSlug(), "fixture club has no slug");
        preCleanAuditRowsThatOutliveTestRollback(jdbc, clubId);
        dropRowsOfThisSubmitter();
    }

    @AfterEach
    void dropRowsOfThisSubmitter() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE target_entity_type = ?", "Public");
    }

    @Test
    void the_guard_rejection_names_the_anonymous_submitter_and_never_reads_as_a_system_row() {
        ResponseEntity<String> rejected = submitUntilTheGuardRejects();

        assertThat(rejected.getStatusCode())
                .as("the abuse guard rejects the eleventh submission of this source")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(theRejectionAuditRow())
                .as("the rejection of an anonymous internet write is attributed to that write, "
                        + "not to the server")
                .containsEntry("actor_kind", "ANONYMOUS_PUBLIC")
                .containsEntry("system_actor", false)
                .containsEntry("actor_user_id", null)
                .containsEntry("actor_keycloak_sub", null)
                .containsEntry("failed", true);
    }

    private ResponseEntity<String> submitUntilTheGuardRejects() {
        ResponseEntity<String> last = null;
        for (int attempt = 0; attempt < SUBMITS_UNTIL_THE_GUARD_REJECTS; attempt++) {
            last = submitOnce();
        }
        return Objects.requireNonNull(last);
    }

    private ResponseEntity<String> submitOnce() {
        return rest.exchange(RequestEntity
                .post(URI.create("/api/v1/public/clubs/" + clubSlug
                        + "/discovery-flight-registrations"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(FORWARDED_FOR, SUBMITTER_IP)
                .body(PublicSubmissions.discoveryBody(DAY_THE_CLUB_NEVER_PUBLISHED)),
                String.class);
    }

    private Map<String, Object> theRejectionAuditRow() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor_kind, system_actor, actor_user_id, actor_keycloak_sub, client_ip, "
                        + "tenant_club_id, failed, http_status FROM t_mutation_audit_event "
                        + "WHERE target_entity_type = ? AND http_status = 429",
                "Public");
        assertThat(rows)
                .as("the rejected write produced exactly one audit row")
                .hasSize(1);
        return rows.getFirst();
    }
}
