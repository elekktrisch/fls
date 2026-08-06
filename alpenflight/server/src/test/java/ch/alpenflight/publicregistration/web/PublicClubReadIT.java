package ch.alpenflight.publicregistration.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PublicClubReadIT extends PostgresIntegrationTest {

    private static final String UNKNOWN_SLUG = "no-such-club-it-pcr";
    private static final String MALFORMED_SLUG = "NotALowercaseSlug";
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
        ResponseEntity<Map<String, Object>> response = getClub(openSlug);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(response.getBody()).get("clubName"))
                .isEqualTo(CLUB_NAME);
    }

    @Test
    void the_read_exposes_the_display_name_and_nothing_else() {
        Map<String, Object> body = Objects.requireNonNull(getClub(openSlug).getBody());
        String raw = Objects.requireNonNull(
                rest.getForEntity(clubPath(openSlug), String.class).getBody());

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
        assertThat(getClub(UNKNOWN_SLUG).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getClub(MALFORMED_SLUG).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getClub(closedSlug).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The read is the first thing a visitor's browser calls, and it is not
     * charged to the submit budget — so a shared address must not be able to
     * spend its neighbours' registrations by loading the page.
     */
    @Test
    void repeating_the_read_never_throttles_the_visitor() {
        // Above PublicRegistrationSubmitGuard's per-(IP, club) cap, so wiring the
        // read into that window would turn this green run red.
        for (int attempt = 0; attempt < 15; attempt++) {
            assertThat(getClub(openSlug).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    private ResponseEntity<Map<String, Object>> getClub(String slug) {
        return rest.exchange(clubPath(slug), HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private static String clubPath(String slug) {
        return "/api/v1/public/clubs/" + slug;
    }

    private static String requireSlug(Club club) {
        String slug = club.getSlug();
        if (slug == null) {
            throw new IllegalStateException("Fixture club has no slug");
        }
        return slug;
    }
}
