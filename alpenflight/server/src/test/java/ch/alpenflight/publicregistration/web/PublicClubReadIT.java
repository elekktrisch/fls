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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PublicClubReadIT extends PostgresIntegrationTest {

    private static final String UNKNOWN_SLUG = "no-such-club-it-pcr";
    private static final String MALFORMED_SLUG = "NotALowercaseSlug";

    private static final int READ_REACH_BUDGET_RESTATED_NOT_IMPORTED = 25;

    private static final String DISCOVERY_OPERATOR_EMAIL = "discovery-ops-pcr@example.com";
    private static final String SCENIC_OPERATOR_EMAIL = "scenic-ops-pcr@example.com";

    private static final String CLUB_NAME_SHARING_NO_SUBSTRING_WITH_THE_CLUB_KEY =
            "Segelfluggruppe Alpennordrand";

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
        open.rename(CLUB_NAME_SHARING_NO_SUBSTRING_WITH_THE_CLUB_KEY);
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
                .isEqualTo(CLUB_NAME_SHARING_NO_SUBSTRING_WITH_THE_CLUB_KEY);
    }

    @Test
    void the_read_exposes_the_display_name_and_nothing_else() {
        String sourceOwnedByThisCase = "203.0.113.21";
        Map<String, Object> body =
                Objects.requireNonNull(getClub(sourceOwnedByThisCase, openSlug).getBody());
        String raw = Objects.requireNonNull(
                rest.exchange(request(sourceOwnedByThisCase, clubPath(openSlug)), String.class)
                        .getBody());

        assertThat(body).containsOnlyKeys("clubName");
        assertThat(raw)
                .doesNotContain(joinCode)
                .doesNotContain(clubKey)
                .doesNotContain(DISCOVERY_OPERATOR_EMAIL)
                .doesNotContain(SCENIC_OPERATOR_EMAIL)
                .doesNotContain(openClubId.toString());
    }

    @Test
    void the_read_keeps_the_slug_contract() {
        String sourceOwnedByThisCase = "203.0.113.22";
        assertThat(getClub(sourceOwnedByThisCase, UNKNOWN_SLUG).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getClub(sourceOwnedByThisCase, MALFORMED_SLUG).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getClub(sourceOwnedByThisCase, closedSlug).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void repeating_the_read_never_throttles_the_visitor() {
        for (int attempt = 0; attempt < READ_REACH_BUDGET_RESTATED_NOT_IMPORTED + 5; attempt++) {
            assertThat(getClub("203.0.113.23", openSlug).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void enumerating_fresh_slugs_from_one_source_is_refused() {
        String prober = "203.0.113.24";
        for (int probe = 0; probe < READ_REACH_BUDGET_RESTATED_NOT_IMPORTED; probe++) {
            String slug = UNKNOWN_SLUG + "-" + probe;
            ResponseEntity<Void> answered = probe % 2 == 0
                    ? read(prober, clubPath(slug))
                    : read(prober, discoveryDaysPath(slug));
            assertThat(answered.getStatusCode())
                    .as("both anonymous reads answer the club-existence oracle, so the probe "
                            + "alternates between them and both must be charged")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        ResponseEntity<Void> refused = read(prober, clubPath(UNKNOWN_SLUG + "-past"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(getClub("203.0.113.25", openSlug).getStatusCode())
                .as("a visitor arriving from anywhere else is untouched — the refusal is the "
                        + "prober's reach, not a global stop")
                .isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> getClub(String source, String slug) {
        return rest.exchange(request(source, clubPath(slug)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Void> read(String source, String path) {
        return rest.exchange(request(source, path), Void.class);
    }

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
