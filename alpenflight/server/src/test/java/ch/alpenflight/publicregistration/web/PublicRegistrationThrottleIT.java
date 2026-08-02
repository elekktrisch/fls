package ch.alpenflight.publicregistration.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The abuse guard as an anonymous caller experiences it: through the production
 * filter chain, with no token, on the real endpoints.
 *
 * <p>Each case drives its own source address, which is also what makes the
 * separation assertions meaningful — the requests genuinely arrive on one TCP
 * peer (loopback), so a source key that ignored the forwarded chain would
 * collapse every case into one bucket and the separation cases would fail.
 *
 * <p>The limits are restated here rather than imported: this is a security
 * control, so a change to either number should have to be made twice, in the
 * guard and in the test that pins the observable contract.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PublicRegistrationThrottleIT extends PostgresIntegrationTest {

    private static final LocalDate DISCOVERY_DAY = LocalDate.of(2099, 6, 15);
    private static final int PER_CLUB_LIMIT = 10;
    private static final int WINDOW_SECONDS = 15 * 60;
    private static final String AUDIT_ENTITY_TYPE = "PublicFlightRegistration";
    private static final String UNKNOWN_SLUG = "no-such-club-it-prt";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired DiscoveryFlightDayRepository discoveryDays;

    private UUID openClubId;
    private String openSlug;
    private String otherSlug;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_PRT_", "IT_PRT_");
        fixture.seed();
        openClubId = fixture.clubA();
        openSlug = openPublicRegistration(openClubId);
        otherSlug = openPublicRegistration(fixture.clubB());
        TenantTestContext.runAs(openClubId, () ->
                discoveryDays.save(DiscoveryFlightDay.schedule(DISCOVERY_DAY, DISCOVERY_DAY)));
    }

    @Test
    void the_attempt_past_the_limit_is_429_with_a_retryAfter_and_writes_nothing() {
        String source = "203.0.113.11";

        for (int attempt = 0; attempt < PER_CLUB_LIMIT; attempt++) {
            assertThat(submitDiscovery(source, openSlug).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        // Measured after the accepted run, so the delta belongs to the refused
        // attempt alone — the ten before it legitimately registered someone.
        long personsBefore = count("t_person");
        long membershipsBefore = count("t_person_club");

        ResponseEntity<Void> refused = submitDiscovery(source, openSlug);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(retryAfterSeconds(refused)).isPositive().isLessThanOrEqualTo(WINDOW_SECONDS);
        assertThat(acceptedRegistrations(openClubId)).isEqualTo(PER_CLUB_LIMIT);
        assertThat(count("t_person")).isEqualTo(personsBefore);
        assertThat(count("t_person_club")).isEqualTo(membershipsBefore);
    }

    @Test
    void a_second_source_address_is_still_served_at_the_same_club() {
        String exhausted = "203.0.113.12";
        String fresh = "203.0.113.13";
        for (int attempt = 0; attempt < PER_CLUB_LIMIT; attempt++) {
            submitDiscovery(exhausted, openSlug);
        }

        assertThat(submitDiscovery(fresh, openSlug).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(submitDiscovery(exhausted, openSlug).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void the_same_source_is_still_served_at_a_different_club() {
        String source = "203.0.113.14";
        for (int attempt = 0; attempt < PER_CLUB_LIMIT; attempt++) {
            submitDiscovery(source, openSlug);
        }

        assertThat(submitScenic(source, otherSlug).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(submitDiscovery(source, openSlug).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The guard runs before the slug resolves, so probing a slug that does not
     * exist is charged like any other attempt — otherwise enumerating clubs
     * would be the one anonymous operation with no cost.
     */
    @Test
    void probing_an_unknown_slug_is_charged_as_an_attempt() {
        String source = "203.0.113.15";
        for (int attempt = 0; attempt < PER_CLUB_LIMIT; attempt++) {
            assertThat(submitDiscovery(source, UNKNOWN_SLUG).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        assertThat(submitDiscovery(source, UNKNOWN_SLUG).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<Void> submitDiscovery(String source, String slug) {
        RequestEntity<?> request = RequestEntity
                .post(URI.create("/api/v1/public/clubs/" + slug + "/discovery-flight-registrations"))
                .header("X-Forwarded-For", source)
                .contentType(MediaType.APPLICATION_JSON)
                .body(PublicSubmissions.discoveryBody(DISCOVERY_DAY));
        return rest.exchange(request, Void.class);
    }

    private ResponseEntity<Void> submitScenic(String source, String slug) {
        RequestEntity<?> request = RequestEntity
                .post(URI.create("/api/v1/public/clubs/" + slug + "/scenic-flight-registrations"))
                .header("X-Forwarded-For", source)
                .contentType(MediaType.APPLICATION_JSON)
                .body(PublicSubmissions.scenicBody());
        return rest.exchange(request, Void.class);
    }

    private static long retryAfterSeconds(ResponseEntity<Void> response) {
        String header = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(header).isNotNull();
        return Long.parseLong(header);
    }

    private long acceptedRegistrations(UUID clubId) {
        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM t_mutation_audit_event "
                        + "WHERE target_entity_type = ? AND tenant_club_id = ?::uuid",
                Long.class, AUDIT_ENTITY_TYPE, clubId.toString());
        return rows == null ? 0L : rows;
    }

    private long count(String table) {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return rows == null ? 0L : rows;
    }

    private String openPublicRegistration(UUID clubId) {
        Club club = clubs.findActiveById(clubId).orElseThrow();
        club.enablePublicRegistration();
        clubs.save(club);
        String slug = club.getSlug();
        if (slug == null) {
            throw new IllegalStateException("Fixture club has no slug");
        }
        return slug;
    }
}
