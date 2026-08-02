package ch.alpenflight.publicregistration.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
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

/**
 * The anonymous surface, driven through the production filter chain with no
 * Authorization header at all (S-025). Four things are on trial:
 *
 * <ul>
 *   <li>the two registration POSTs are reachable without a token, and a
 *       neighbouring authenticated endpoint still is not — a {@code permitAll}
 *       widening that opens more than the two named writes fails here;</li>
 *   <li>an unknown slug 404s and a club with public registration closed 403s;</li>
 *   <li><strong>neither rejection writes a row</strong> — asserted by Person /
 *       PersonClub counts across the call, not by the status alone;</li>
 *   <li>the accepted path audits under the resolved club with an anonymous
 *       actor, while the rejected paths leave no tenant-scoped trace at all.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PublicRegistrationSecurityIT extends PostgresIntegrationTest {

    private static final String UNKNOWN_SLUG = "no-such-club-it-prs";
    private static final String MALFORMED_SLUG = "NotALowercaseSlug";
    private static final String AUDIT_ENTITY_TYPE = "PublicFlightRegistration";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

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
    }

    @Test
    void anonymous_submission_reaches_the_endpoint_and_audits_under_the_resolved_club() {
        ResponseEntity<Void> response = anonymousPost(discoveryPath(openSlug));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Anonymous-actor plumbing on a genuinely token-less request: no
        // JwtAuthenticationToken means ActorResolver yields Actor.anonymous() and
        // both identifiers stay null. (actor_kind is deliberately not asserted —
        // V18 pins every non-migrated row to 'NORMAL'; system_actor is the flag
        // that classifies the write.)
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor_user_id, actor_keycloak_sub, system_actor, failed, action "
                        + "FROM t_mutation_audit_event "
                        + "WHERE target_entity_type = ? AND tenant_club_id = ?::uuid",
                AUDIT_ENTITY_TYPE, openClubId.toString());
        assertThat(row.get("system_actor")).isEqualTo(true);
        assertThat(row.get("actor_user_id")).isNull();
        assertThat(row.get("actor_keycloak_sub")).isNull();
        assertThat(row.get("failed")).isEqualTo(false);
        assertThat(row.get("action")).isEqualTo("CREATE");
    }

    @Test
    void unknown_slug_is_404_and_writes_no_row() {
        long personsBefore = count("t_person");
        long membershipsBefore = count("t_person_club");

        ResponseEntity<Void> response = anonymousPost(discoveryPath(UNKNOWN_SLUG));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        assertThat(count("t_person")).isEqualTo(personsBefore);
        assertThat(count("t_person_club")).isEqualTo(membershipsBefore);
    }

    @Test
    void closed_club_is_403_and_writes_no_row() {
        long personsBefore = count("t_person");
        long membershipsBefore = count("t_person_club");

        ResponseEntity<Void> response = anonymousPost(scenicPath(closedSlug));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNull();
        assertThat(count("t_person")).isEqualTo(personsBefore);
        assertThat(count("t_person_club")).isEqualTo(membershipsBefore);
    }

    /**
     * The rejection paths must run with no tenant scope at all, which is why the
     * slug is resolved before any {@code Tenants.runAs} window opens: a closed
     * club that a caller probed must carry no tenant-scoped trace of the attempt.
     */
    @Test
    void a_rejected_submission_leaves_no_tenantScoped_trace() {
        assertThat(anonymousPost(discoveryPath(closedSlug)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(anonymousPost(scenicPath(UNKNOWN_SLUG)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Long scopedRows = jdbc.queryForObject(
                "SELECT count(*) FROM t_mutation_audit_event WHERE tenant_club_id = ?::uuid",
                Long.class, closedClubId.toString());
        assertThat(scopedRows).isZero();
    }

    @Test
    void a_malformed_slug_is_404_rather_than_a_server_error() {
        assertThat(anonymousPost(discoveryPath(MALFORMED_SLUG)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(anonymousPost(discoveryPath("ab")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void the_permitAll_widening_opens_only_the_two_named_writes() {
        // A neighbouring authenticated endpoint is untouched.
        assertThat(rest.getForEntity("/api/v1/clubs", Void.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Sibling verbs on the very same public path stay closed.
        assertThat(rest.exchange(discoveryPath(openSlug), HttpMethod.DELETE, null, Void.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange(discoveryPath(openSlug), HttpMethod.PUT, null, Void.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // An unenumerated POST under the same prefix is not anonymous-writable.
        assertThat(anonymousPost("/api/v1/public/clubs/" + openSlug + "/members").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Void> anonymousPost(String path) {
        return rest.postForEntity(path, null, Void.class);
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
