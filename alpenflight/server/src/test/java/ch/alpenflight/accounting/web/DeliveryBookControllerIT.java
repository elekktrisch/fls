package ch.alpenflight.accounting.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.application.AccountingRuleFiltersService;
import ch.alpenflight.accounting.domain.DeliveryRepository;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.articles.domain.ArticleRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.DeliveryWriteFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Full-stack HTTP proof of {@code POST /api/v1/deliveries/delivered}. A
 * CLUB_ADMINISTRATOR books a Prepared delivery: the request-supplied number +
 * delivered timestamp are stamped, the delivery flips to {@code Booked}, and the
 * billed flight AND its tow flip to {@code DeliveryBooked} (re-read from the DB). An
 * unknown id returns {@code 200 false} (parity, not an error); deleting a booked
 * delivery is rejected with {@code 409} (booked is terminal — the corrected legacy
 * un-guarded-delete behavior).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class DeliveryBookControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREATE = "/api/v1/deliveries/create";
    private static final String DELIVERIES = "/api/v1/deliveries";
    private static final String DELIVERED = "/api/v1/deliveries/delivered";

    private static final String DELIVERY_NUMBER = "INV-2026-001";
    private static final Instant DELIVERED_AT = Instant.parse("2026-06-25T09:30:00Z");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired AccountingRuleFiltersService filtersService;
    @Autowired FlightRepository flights;
    @Autowired DeliveryRepository deliveries;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired ArticleRepository articles;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;
    @Autowired PersonFlightTimeCreditRepository credits;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private String adminA;
    private DeliveryWriteFixture write;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DLVBK_", "IT_DLVBK");
        fixture.seed();
        clubA = fixture.clubA();
        adminA = adminTokenFor(clubA);
        write = new DeliveryWriteFixture(jdbc, filtersService, flights,
                aircraftRepository, articles, flightTypes, persons, credits);
    }

    @Test
    void book_stampsFields_flipsDeliveryAndFlightAndTowToBooked() {
        Scenario s = seedPreparedDeliveryWithTow();

        ResponseEntity<String> res = book(s.deliveryId(), DELIVERED_AT, DELIVERY_NUMBER, adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("true");

        assertThat(deliveryNumber(s.deliveryId())).isEqualTo(DELIVERY_NUMBER);
        assertThat(deliveredOn(s.deliveryId())).isNotNull();
        assertThat(deliveryProcessState(s.deliveryId()))
                .as("the delivery is Booked (20)")
                .isEqualTo((short) 20);
        assertThat(flightProcessState(s.flightId()))
                .as("the billed flight flipped to DeliveryBooked, re-read from the DB")
                .isEqualTo(FlightProcessState.DELIVERY_BOOKED.id());
        assertThat(flightProcessState(s.towFlightId()))
                .as("the TOW flight flipped to DeliveryBooked too (accounting pair books together)")
                .isEqualTo(FlightProcessState.DELIVERY_BOOKED.id());
    }

    @Test
    void book_unknownDeliveryId_is200False_andNoMutation() {
        Scenario s = seedPreparedDeliveryWithTow();

        ResponseEntity<String> res = book(UUID.randomUUID(), DELIVERED_AT, DELIVERY_NUMBER, adminA);

        assertThat(res.getStatusCode())
                .as("an unknown id is not an error — 200 false (parity)")
                .isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("false");
        assertThat(flightProcessState(s.flightId()))
                .as("nothing was booked — the Prepared flight is untouched")
                .isEqualTo(FlightProcessState.DELIVERY_PREPARED.id());
    }

    @Test
    void reBookOfBookedDelivery_is409_andNoMutation() {
        Scenario s = seedPreparedDeliveryWithTow();
        assertThat(book(s.deliveryId(), DELIVERED_AT, DELIVERY_NUMBER, adminA).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Instant secondDeliveredAt = DELIVERED_AT.plusSeconds(3600);
        ResponseEntity<String> res = book(s.deliveryId(), secondDeliveredAt, "INV-2026-RB", adminA);

        assertThat(res.getStatusCode())
                .as("booked is terminal — re-booking is rejected with 409")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(deliveryNumber(s.deliveryId()))
                .as("no mutation — the original number survives the rejected re-book")
                .isEqualTo(DELIVERY_NUMBER);
        assertThat(deliveredOn(s.deliveryId()))
                .as("no mutation — the original delivered timestamp is untouched")
                .isEqualTo(DELIVERED_AT);
        assertThat(deliveryProcessState(s.deliveryId()))
                .as("no mutation — the delivery stays Booked")
                .isEqualTo((short) 20);
    }

    @Test
    void deleteOfBookedDelivery_is409_andNoMutation() {
        Scenario s = seedPreparedDeliveryWithTow();
        assertThat(book(s.deliveryId(), DELIVERED_AT, DELIVERY_NUMBER, adminA).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> res = delete(DELIVERIES + "/" + s.deliveryId(), adminA);

        assertThat(res.getStatusCode())
                .as("booked is terminal — delete is rejected with 409")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(deliveryProcessState(s.deliveryId()))
                .as("no mutation — the delivery stays Booked after the rejected delete")
                .isEqualTo((short) 20);
        assertThat(flightProcessState(s.flightId()))
                .as("no mutation — the flight stays DeliveryBooked after the rejected delete")
                .isEqualTo(FlightProcessState.DELIVERY_BOOKED.id());
    }

    private Scenario seedPreparedDeliveryWithTow() {
        DeliveryWriteFixture.Seed seed = write.seedEligibleGliderWithTow(clubA);
        ResponseEntity<String> created = post(CREATE, adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID deliveryId = DeliveryWriteFixture.findDeliveryFor(readJson(created), seed.flightId());
        return new Scenario(seed.flightId(), seed.towFlightId(), deliveryId);
    }

    private record Scenario(UUID flightId, UUID towFlightId, UUID deliveryId) {}

    // ----- assertions / http -----

    private UUID flightProcessState(UUID flightId) {
        return jdbc.queryForObject("SELECT process_state_id FROM t_flight WHERE id = ?", UUID.class, flightId);
    }

    private short deliveryProcessState(UUID deliveryId) {
        Short v = jdbc.queryForObject(
                "SELECT process_state_id FROM t_delivery WHERE id = ?", Short.class, deliveryId);
        return v == null ? -1 : v;
    }

    private String deliveryNumber(UUID deliveryId) {
        return jdbc.queryForObject(
                "SELECT delivery_number FROM t_delivery WHERE id = ?", String.class, deliveryId);
    }

    private Instant deliveredOn(UUID deliveryId) {
        java.sql.Timestamp ts = jdbc.queryForObject(
                "SELECT delivered_on FROM t_delivery WHERE id = ?", java.sql.Timestamp.class, deliveryId);
        return ts == null ? null : ts.toInstant();
    }

    private String adminTokenFor(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private ResponseEntity<String> book(UUID deliveryId, Instant deliveredAt, String number, String token) {
        String body = "{\"deliveryId\":\"" + deliveryId + "\",\"deliveryDateTime\":\"" + deliveredAt
                + "\",\"deliveryNumber\":\"" + number + "\"}";
        return rest.exchange(RequestEntity.post(URI.create(DELIVERED))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body), String.class);
    }

    private ResponseEntity<String> post(String path, String token) {
        return rest.exchange(RequestEntity.post(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build(), String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        return rest.exchange(RequestEntity.delete(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build(), String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
