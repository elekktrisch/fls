package ch.alpenflight.publicregistration.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.PublicSubmissions;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PublicRegistrationSecurityIT extends PostgresIntegrationTest {

    private static final LocalDate DISCOVERY_DAY = LocalDate.of(2099, 6, 15);
    private static final String UNKNOWN_SLUG = "no-such-club-it-prs";
    private static final String MALFORMED_SLUG = "NotALowercaseSlug";
    private static final String AUDIT_ENTITY_TYPE = "PublicFlightRegistration";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired DiscoveryFlightDayRepository discoveryDays;

    private UUID openClubId;
    private UUID closedClubId;
    private String openSlug;
    private String closedSlug;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_PRS_", "IT_PRS_");
        fixture.seed();
        openClubId = fixture.clubA();
        closedClubId = fixture.clubB();

        Club open = clubs.findActiveById(openClubId).orElseThrow();
        open.enablePublicRegistration();
        clubs.save(open);
        openSlug = requireSlug(open);
        closedSlug = requireSlug(clubs.findActiveById(closedClubId).orElseThrow());
        TenantTestContext.runAs(openClubId, () ->
                discoveryDays.save(DiscoveryFlightDay.schedule(DISCOVERY_DAY, DISCOVERY_DAY)));
    }

    @Test
    void anonymous_submission_reaches_the_endpoint_and_audits_under_the_resolved_club() {
        ResponseEntity<Void> response = anonymousDiscoveryPost(openSlug);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor_user_id, actor_keycloak_sub, system_actor, actor_kind, client_ip, "
                        + "failed, action FROM t_mutation_audit_event "
                        + "WHERE target_entity_type = ? AND tenant_club_id = ?::uuid",
                AUDIT_ENTITY_TYPE, openClubId.toString());
        assertThat(row.get("actor_kind"))
                .as("actor_kind classifies an anonymous internet write apart from a scheduled job")
                .isEqualTo("ANONYMOUS_PUBLIC");
        assertThat(row.get("system_actor"))
                .as("an internet submitter is not the system")
                .isEqualTo(false);
        assertThat(row.get("client_ip"))
                .as("the submitter's address is what the abuse guard acted on")
                .isNotNull();
        assertThat(row.get("actor_user_id")).isNull();
        assertThat(row.get("actor_keycloak_sub")).isNull();
        assertThat(row.get("failed")).isEqualTo(false);
        assertThat(row.get("action")).isEqualTo("CREATE");
    }

    @Test
    void unknown_slug_is_404_and_writes_no_row() {
        long personsBefore = count("t_person");
        long membershipsBefore = count("t_person_club");

        ResponseEntity<Void> response = anonymousDiscoveryPost(UNKNOWN_SLUG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        assertThat(count("t_person")).isEqualTo(personsBefore);
        assertThat(count("t_person_club")).isEqualTo(membershipsBefore);
    }

    @Test
    void closed_club_is_403_and_writes_no_row() {
        long personsBefore = count("t_person");
        long membershipsBefore = count("t_person_club");

        ResponseEntity<Void> response = anonymousScenicPost(closedSlug);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNull();
        assertThat(count("t_person")).isEqualTo(personsBefore);
        assertThat(count("t_person_club")).isEqualTo(membershipsBefore);
    }

    @Test
    void a_rejected_submission_leaves_no_tenantScoped_trace() {
        assertThat(anonymousDiscoveryPost(closedSlug).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(anonymousScenicPost(UNKNOWN_SLUG).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Long scopedRows = jdbc.queryForObject(
                "SELECT count(*) FROM t_mutation_audit_event WHERE tenant_club_id = ?::uuid",
                Long.class, closedClubId.toString());
        assertThat(scopedRows).isZero();
    }

    @Test
    void a_malformed_slug_is_404_rather_than_a_server_error() {
        assertThat(anonymousDiscoveryPost(MALFORMED_SLUG).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(anonymousDiscoveryPost("ab").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void the_permitAll_widening_opens_only_the_two_named_writes() {
        assertThat(rest.getForEntity("/api/v1/clubs", Void.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange(discoveryPath(openSlug), HttpMethod.DELETE, null, Void.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange(discoveryPath(openSlug), HttpMethod.PUT, null, Void.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymousPost("/api/v1/public/clubs/" + openSlug + "/members").getStatusCode())
                .as("an unenumerated POST under the same public prefix is not anonymous-writable")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Void> anonymousPost(String path) {
        return rest.postForEntity(path, null, Void.class);
    }

    private ResponseEntity<Void> anonymousDiscoveryPost(String slug) {
        return rest.postForEntity(discoveryPath(slug),
                PublicSubmissions.discoveryBody(DISCOVERY_DAY), Void.class);
    }

    private ResponseEntity<Void> anonymousScenicPost(String slug) {
        return rest.postForEntity(scenicPath(slug), PublicSubmissions.scenicBody(), Void.class);
    }

    private static String discoveryPath(String slug) {
        return "/api/v1/public/clubs/" + slug + "/discovery-flight-registrations";
    }

    private static String scenicPath(String slug) {
        return "/api/v1/public/clubs/" + slug + "/scenic-flight-registrations";
    }

    private long count(String table) {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return rows == null ? 0L : rows;
    }

    private static String requireSlug(Club club) {
        String slug = club.getSlug();
        if (slug == null) {
            throw new IllegalStateException("Fixture club has no slug");
        }
        return slug;
    }
}
