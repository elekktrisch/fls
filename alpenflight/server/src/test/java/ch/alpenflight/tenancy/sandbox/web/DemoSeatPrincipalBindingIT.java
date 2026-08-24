package ch.alpenflight.tenancy.sandbox.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import ch.alpenflight.tenancy.sandbox.application.DemoSeatPoolTestFixture;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import java.util.List;
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
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class DemoSeatPrincipalBindingIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_DSB_";
    private static final String KEY_PREFIX = "IT_DB";
    private static final String ANY_TENANT_SCOPED_READ = "/api/v1/persons";

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtTestFixture jwts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;
    @Autowired private DemoSeatRepository seats;

    private UUID realClub;
    private UUID firstSeatClub;
    private String firstSeatUsername;
    private UUID secondSeatClub;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        realClub = fixture.clubA();
        DemoSeat firstSeat = DemoSeatPoolTestFixture.seatNumbered(seats, 1);
        firstSeatClub = firstSeat.getClubId().value();
        firstSeatUsername = firstSeat.getKeycloakUsername();
        secondSeatClub = DemoSeatPoolTestFixture.seatNumbered(seats, 2).getClubId().value();
        TenantTestContext.clear();
    }

    @Test
    void a_demo_principal_carrying_a_real_club_is_refused() {
        assertThat(statusFor(firstSeatUsername, realClub))
                .as("a seat principal outside its own seat must not read a real club's tenant")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void a_demo_principal_carrying_another_seats_club_is_refused() {
        assertThat(statusFor(firstSeatUsername, secondSeatClub))
                .as("one seat principal must not read another seat's tenant")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void a_principal_that_is_not_a_demo_user_carrying_a_demo_club_is_refused() {
        assertThat(statusFor("not-a-seat-principal", firstSeatClub))
                .as("only the seat's own principal may carry a sandbox seat club")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void a_demo_principal_reading_a_club_outside_its_own_seat_is_refused() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenFor(firstSeatUsername, firstSeatClub));
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get("/api/v1/clubs/" + ClubId.of(realClub).toExternal())
                        .headers(headers).build(),
                String.class);

        assertThat(res.getStatusCode())
                .as("AC-7 — a demo principal that reads a club outside its own seat gets 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void a_demo_principal_carrying_its_own_seat_club_is_admitted() {
        assertThat(statusFor(firstSeatUsername, firstSeatClub))
                .as("positive baseline — the binding must admit the seat's own principal")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void a_real_principal_carrying_its_own_real_club_is_admitted() {
        assertThat(statusFor("real-club-administrator", realClub))
                .as("positive baseline — the binding must not touch a real club's principal")
                .isEqualTo(HttpStatus.OK);
    }

    private HttpStatus statusFor(String username, UUID clubId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenFor(username, clubId));
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(ANY_TENANT_SCOPED_READ).headers(headers).build(), String.class);
        return HttpStatus.valueOf(res.getStatusCode().value());
    }

    private String tokenFor(String username, UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }
}
