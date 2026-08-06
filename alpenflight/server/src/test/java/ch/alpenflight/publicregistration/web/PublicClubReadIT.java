package ch.alpenflight.publicregistration.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The anonymous club read both public forms head with, driven token-less through
 * the production filter chain.
 *
 * <p>The load-bearing case is {@link #the_read_exposes_the_display_name_and_nothing_else}.
 * A club that opted into public registration made its NAME public; it made none
 * of its configuration public, and the aggregate behind this endpoint carries a
 * join code, the operator mail recipients, the club key, a homebase and a
 * deployment id. So the exposed field set is pinned exactly rather than sampled:
 * widening the underlying projection cannot leak through here without reddening
 * this test first. The club is seeded WITH that configuration populated, so the
 * absence assertions are statements about the response rather than about an
 * empty fixture.
 *
 * <p>The other pair — {@link #repeating_the_read_never_throttles_the_visitor} and
 * {@link #enumerating_fresh_slugs_from_one_source_is_refused} — pins the two
 * halves of the read budget together, because either one alone is satisfiable by
 * a guard that is useless or hostile: no limit at all passes the first, and
 * limiting request volume passes the second while locking out the crowd behind a
 * shared address.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PublicClubReadIT extends PostgresIntegrationTest {

    private static final String UNKNOWN_SLUG = "no-such-club-it-pcr";
    private static final String MALFORMED_SLUG = "NotALowercaseSlug";

    /**
     * Restated rather than imported: the reach budget is a security control, so
     * changing it should have to be done twice — in the guard and in the test
     * that pins what an anonymous caller observes.
     */
    private static final int READ_REACH_BUDGET = 25;

    private static final String DISCOVERY_OPERATOR_EMAIL = "discovery-ops-pcr@example.com";
    private static final String SCENIC_OPERATOR_EMAIL = "scenic-ops-pcr@example.com";

    // Deliberately shares no substring with the fixture's club key, so
    // "the body does not carry the club key" cannot pass or fail by accident.
    private static final String CLUB_NAME = "Segelfluggruppe Alpennordrand";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID openClubId;
    private String openSlug;
    private String closedSlug;
    private String joinCode;
    private String clubKey;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_PCR_", "IT_PCR_");
        fixture.seed();
        openClubId = fixture.clubA();

        Club open = clubs.findActiveById(openClubId).orElseThrow();
        open.rename(CLUB_NAME);
        open.enablePublicRegistration();
        open.setRegistrationOperatorEmails(DISCOVERY_OPERATOR_EMAIL, SCENIC_OPERATOR_EMAIL);
        clubs.save(open);
        openSlug = requireSlug(open);
        joinCode = open.getJoinCode();
        clubKey = open.getClubKey();

        closedSlug = requireSlug(clubs.findActiveById(fixture.clubB()).orElseThrow());
    }

    @Test
    void the_read_answers_the_clubs_display_name() {
        ResponseEntity<Map<String, Object>> response = getClub("203.0.113.20", openSlug);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(response.getBody()).get("clubName"))
                .isEqualTo(CLUB_NAME);
    }

    @Test
    void the_read_exposes_the_display_name_and_nothing_else() {
        String source = "203.0.113.21";
        Map<String, Object> body = Objects.requireNonNull(getClub(source, openSlug).getBody());
        String raw = Objects.requireNonNull(
                rest.exchange(request(source, clubPath(openSlug)), String.class).getBody());

        assertThat(body).containsOnlyKeys("clubName");
        // A nested object would satisfy the key set above while still shipping
        // configuration, so the serialized body is checked for the values too.
        assertThat(raw)
                .doesNotContain(joinCode)
                .doesNotContain(clubKey)
                .doesNotContain(DISCOVERY_OPERATOR_EMAIL)
                .doesNotContain(SCENIC_OPERATOR_EMAIL)
                .doesNotContain(openClubId.toString());
    }

    @Test
    void the_read_keeps_the_slug_contract() {
        String source = "203.0.113.22";
        assertThat(getClub(source, UNKNOWN_SLUG).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getClub(source, MALFORMED_SLUG).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getClub(source, closedSlug).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The read is the first thing a visitor's browser calls, so a shared address
     * must not be able to spend its neighbours' registrations — nor its
     * neighbours' page loads — by loading the page. The read budget counts
     * distinct clubs, so this whole loop is one unit of it.
     */
    @Test
    void repeating_the_read_never_throttles_the_visitor() {
        // Past BOTH per-source ceilings: the submit budget the read is
        // deliberately not charged to, and the read budget's own reach limit.
        for (int attempt = 0; attempt < READ_REACH_BUDGET + 5; attempt++) {
            assertThat(getClub("203.0.113.23", openSlug).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    /**
     * The 200/403/404 answer is a club-existence oracle over a guessable
     * keyspace, one database round-trip per well-formed slug, and nothing
     * terminates HTTP in front of the server to bound it — so the reach of a
     * single source across DIFFERENT slugs is what the budget caps.
     *
     * <p>The probes alternate between the two anonymous reads because BOTH
     * answer that oracle: charging only one of them would leave the source with
     * roughly half its reach spent here, and the closing probe would answer 404
     * instead of 429.
     */
    @Test
    void enumerating_fresh_slugs_from_one_source_is_refused() {
        String prober = "203.0.113.24";
        for (int probe = 0; probe < READ_REACH_BUDGET; probe++) {
            String slug = UNKNOWN_SLUG + "-" + probe;
            ResponseEntity<Void> answered = probe % 2 == 0
                    ? read(prober, clubPath(slug))
                    : read(prober, discoveryDaysPath(slug));
            assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        ResponseEntity<Void> refused = read(prober, clubPath(UNKNOWN_SLUG + "-past"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        // Non-vacuity: an ordinary visitor arriving from anywhere else is
        // untouched, so the refusal is the prober's reach and not a global stop.
        assertThat(getClub("203.0.113.25", openSlug).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> getClub(String source, String slug) {
        return rest.exchange(request(source, clubPath(slug)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Void> read(String source, String path) {
        return rest.exchange(request(source, path), Void.class);
    }

    /**
     * Every case drives its own source address so the shared guard bean cannot
     * couple them: the reach budget is per source, and a case that spends it
     * must not decide the next one's outcome.
     */
    private static RequestEntity<Void> request(String source, String path) {
        return RequestEntity.get(URI.create(path))
                .header("X-Forwarded-For", source)
                .build();
    }

    private static String clubPath(String slug) {
        return "/api/v1/public/clubs/" + slug;
    }

    private static String discoveryDaysPath(String slug) {
        return clubPath(slug) + "/discovery-flight-days";
    }

    private static String requireSlug(Club club) {
        String slug = club.getSlug();
        if (slug == null) {
            throw new IllegalStateException("Fixture club has no slug");
        }
        return slug;
    }
}
