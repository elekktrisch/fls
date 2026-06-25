package ch.alpenflight.accounting.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.application.AccountingRuleFiltersService;
import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryRepository;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails;
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
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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

/**
 * Full-stack HTTP proof of {@code DELETE /api/v1/deliveries/{id}}. A
 * CLUB_ADMINISTRATOR deletes a Prepared delivery: the delivery + items disappear,
 * the billed flight AND its tow flip back to {@code Locked} (re-read from the DB —
 * the regression guard against the legacy never-{@code SaveChanges}d / wrong-tow-target
 * bugs), and the consumed flight-time credit is reversed (a new current transaction
 * appended, balance restored). The {@code >1-delivery-per-flight} guard rejects with
 * 409 and no mutation; non-admin → 401/403; missing → 404.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class DeliveryDeleteControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREATE = "/api/v1/deliveries/create";
    private static final String DELIVERIES = "/api/v1/deliveries";

    private static final long CREDIT_BALANCE_SECONDS = 5_400L;

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
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DLVDEL_", "IT_DLVDEL");
        fixture.seed();
        clubA = fixture.clubA();
        adminA = adminTokenFor(clubA);
        write = new DeliveryWriteFixture(jdbc, filtersService, flights,
                aircraftRepository, articles, flightTypes, persons, credits);
    }

    @Test
    void delete_resetsFlightAndTowToLocked_persisted_andReversesCredit() {
        Scenario s = seedPreparedDeliveryWithTow();

        ResponseEntity<String> res = delete(DELIVERIES + "/" + s.deliveryId(), adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(flightProcessState(s.flightId()))
                .as("the billed flight, re-read from the DB, flipped back to Locked (SaveChanges-bug guard)")
                .isEqualTo(FlightProcessState.LOCKED.id());
        assertThat(flightProcessState(s.towFlightId()))
                .as("the TOW flight, re-read from the DB, flipped back to Locked (wrong-target + persist guard)")
                .isEqualTo(FlightProcessState.LOCKED.id());

        assertThat(currentTransactionCount(s.creditId()))
                .as("exactly one current credit transaction after the reversal")
                .isEqualTo(1);
        assertThat(currentBalance(s.creditId()))
                .as("the reversal restored the pre-consumption balance")
                .isEqualTo(CREDIT_BALANCE_SECONDS);
        assertThat(transactionCount(s.creditId()))
                .as("append-only: grant + consumption + reversal rows all kept")
                .isEqualTo(3);

        ResponseEntity<String> after = get(DELIVERIES + "/" + s.deliveryId(), adminA);
        assertThat(after.getStatusCode())
                .as("the deleted delivery is gone from reads (soft-deleted)")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_rejectedWhenMoreThanOneDeliverySharesFlight_409_noMutation() {
        Scenario s = seedPreparedDeliveryWithTow();
        seedSecondDeliveryOnFlight(s.flightId());

        ResponseEntity<String> res = delete(DELIVERIES + "/" + s.deliveryId(), adminA);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(flightProcessState(s.flightId()))
                .as("no partial mutation — the flight stays DeliveryPrepared after the rejected delete")
                .isEqualTo(FlightProcessState.DELIVERY_PREPARED.id());
        assertThat(currentBalance(s.creditId()))
                .as("no partial mutation — the credit is untouched after the rejected delete")
                .isLessThan(CREDIT_BALANCE_SECONDS);
        assertThat(activeDeliveryCount(s.flightId()))
                .as("both deliveries are still active after the rejected delete")
                .isEqualTo(2);
    }

    @Test
    void delete_byNonAdmin_isForbidden_andMissing_is404() {
        Scenario s = seedPreparedDeliveryWithTow();
        String pilot = jwts.mint(c -> c
                .claim("clubId", clubA.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));

        assertThat(delete(DELIVERIES + "/" + s.deliveryId(), pilot).getStatusCode().value())
                .as("a non-admin may not delete")
                .isIn(401, 403);
        assertThat(flightProcessState(s.flightId()))
                .as("the forbidden delete mutated nothing")
                .isEqualTo(FlightProcessState.DELIVERY_PREPARED.id());

        assertThat(delete(DELIVERIES + "/" + UUID.randomUUID(), adminA).getStatusCode())
                .as("an unknown delivery id is a 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----- seed -----

    private Scenario seedPreparedDeliveryWithTow() {
        DeliveryWriteFixture.Seed seed = write.seedEligibleGliderWithTow(clubA);
        ResponseEntity<String> created = post(CREATE, adminA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID deliveryId = DeliveryWriteFixture.findDeliveryFor(readJson(created), seed.flightId());
        return new Scenario(seed.flightId(), seed.towFlightId(), deliveryId, seed.creditId());
    }

    private record Scenario(UUID flightId, UUID towFlightId, UUID deliveryId, UUID creditId) {}

    private void seedSecondDeliveryOnFlight(UUID flightId) {
        RuleBasedDeliveryDetails computed = RuleBasedDeliveryDetails.forClub(clubA);
        computed.setRecipient(new RuleBasedDeliveryDetails.Recipient(
                null, "M-DUP", null, "Dup", "Recipient"));
        computed.addItem(new ch.alpenflight.accounting.domain.DeliveryItemDetails(
                0, "ART-FT", "dup", null, java.math.BigDecimal.ONE, 0, "Min"));
        UUID articleId = TenantTestContext.runAs(clubA,
                () -> articles.findActiveByNumber("ART-FT").orElseThrow().getId().value());
        TenantTestContext.runAs(clubA, () -> {
            Delivery second = Delivery.createFromEligibleFlight(
                    clubA, flightId, computed, 999L, number -> articleId);
            return deliveries.save(second).getId();
        });
    }

    // ----- assertions / http -----

    private UUID flightProcessState(UUID flightId) {
        return jdbc.queryForObject("SELECT process_state_id FROM t_flight WHERE id = ?", UUID.class, flightId);
    }

    private long currentBalance(UUID creditId) {
        Long v = jdbc.queryForObject(
                "SELECT current_flight_time_balance_in_seconds FROM t_person_flight_time_credit_transaction "
                        + "WHERE credit_id = ? AND is_current = true", Long.class, creditId);
        return v == null ? -1 : v;
    }

    private long currentTransactionCount(UUID creditId) {
        Long v = jdbc.queryForObject(
                "SELECT count(*) FROM t_person_flight_time_credit_transaction "
                        + "WHERE credit_id = ? AND is_current = true", Long.class, creditId);
        return v == null ? 0 : v;
    }

    private long transactionCount(UUID creditId) {
        Long v = jdbc.queryForObject(
                "SELECT count(*) FROM t_person_flight_time_credit_transaction WHERE credit_id = ?",
                Long.class, creditId);
        return v == null ? 0 : v;
    }

    private long activeDeliveryCount(UUID flightId) {
        Long v = jdbc.queryForObject(
                "SELECT count(*) FROM t_delivery WHERE flight_id = ? AND deleted_on IS NULL",
                Long.class, flightId);
        return v == null ? 0 : v;
    }

    private String adminTokenFor(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private ResponseEntity<String> post(String path, String token) {
        return rest.exchange(RequestEntity.post(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build(), String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        return rest.exchange(RequestEntity.delete(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build(), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(RequestEntity.get(URI.create(path))
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
