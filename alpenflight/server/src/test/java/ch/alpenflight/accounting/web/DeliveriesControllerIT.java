package ch.alpenflight.accounting.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryItem;
import ch.alpenflight.accounting.domain.DeliveryProcessState;
import ch.alpenflight.accounting.domain.DeliveryRecipient;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.articles.domain.ArticleRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.DeliveryTestHydrator;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import ch.alpenflight.accounting.infra.JpaDeliveryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Locale;
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
 * Full-stack HTTP proof of the read-only Delivery surface under a
 * CLUB_ADMINISTRATOR principal. Seeds two clubs ({@link TwoClubFixture}) + their
 * read-only deliveries (hydrated reflectively, then saved so the {@code @TenantId}
 * resolver stamps the tenant — ADR 0027 §4). Asserts the paged list returns the
 * club's deliveries in legacy order, the view-by-id returns the full detail
 * (line items + frozen recipient + flight link), and the cross-tenant view → 404
 * (the {@code @TenantId} isolation through the HTTP layer + feature exception
 * handler).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class DeliveriesControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/deliveries";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired JpaDeliveryRepository jpaDeliveries;
    @Autowired ArticleRepository articles;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraft;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;
    private String adminA;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DLVC_", "IT_DLVC");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        adminA = adminTokenFor(clubA);
    }

    @Test
    void page_returns_club_deliveries_sorted_batch_desc_recipient_asc() {
        saveDelivery(clubA, 30L, recipientNamed("Zulu"), null, List.of());
        saveDelivery(clubA, 40L, recipientNamed("Mike"), null, List.of());
        // Club B's delivery is invisible to club A's page (tenant-scoped).
        saveDelivery(clubB, 99L, recipientNamed("Other"), null, List.of());

        ResponseEntity<String> res = post(BASE + "/page/0/100", adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);

        assertThat(body.get("totalRows").asLong()).isEqualTo(2);
        JsonNode items = body.get("items");
        assertThat(items.size()).isEqualTo(2);
        // batch 40 (Mike) sorts before batch 30 (Zulu).
        assertThat(items.get(0).get("batchId").asLong()).isEqualTo(40L);
        assertThat(items.get(0).get("recipientName").asText()).isEqualTo("Mike First");
        assertThat(items.get(1).get("batchId").asLong()).isEqualTo(30L);
        assertThat(items.get(0).get("processStateId").asInt()).isEqualTo(10);
    }

    @Test
    void get_returns_detail_with_items_recipient_and_flight_link() {
        UUID articleId = seedArticle(clubA);
        UUID flightId = seedGliderFlight(clubA);
        DeliveryRecipient recipient = new DeliveryRecipient(
                "Petra Pilot", "Petra", "Pilot",
                "Flugplatzstrasse 1", "Hangar 3",
                "8000", "Zürich", "Schweiz", "M-4711");
        DeliveryItem item = DeliveryTestHydrator.item(
                clubA, 1, articleId, "ART-FT", "first 30min",
                new BigDecimal("0.5000"), new BigDecimal("120.0000"), 10, "Min");

        UUID id = saveDelivery(clubA, 90L, recipient, flightId, List.of(item));

        ResponseEntity<String> res = get(BASE + "/" + id, adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = readJson(res);

        assertThat(detail.get("batchId").asLong()).isEqualTo(90L);
        assertThat(detail.get("processStateId").asInt()).isEqualTo(20);
        assertThat(detail.get("recipient").get("lastName").asText()).isEqualTo("Pilot");
        assertThat(detail.get("recipient").get("city").asText()).isEqualTo("Zürich");
        assertThat(detail.get("flight").get("flightId").asText())
                .isEqualTo(FlightId.of(flightId).toExternal());

        JsonNode line = detail.get("items").get(0);
        assertThat(line.get("position").asInt()).isEqualTo(1);
        assertThat(line.get("articleNumber").asText()).isEqualTo("ART-FT");
        assertThat(line.get("itemText").asText()).isEqualTo("first 30min");
        assertThat(line.get("quantity").asDouble()).isEqualTo(0.5);
        assertThat(line.get("unitType").asText()).isEqualTo("Min");
        assertThat(line.get("discountInPercent").asInt()).isEqualTo(10);
    }

    @Test
    void crossTenant_get_returns_404() {
        UUID bRow = saveDelivery(clubB, 5L, recipientNamed("Bravo"), null, List.of());

        // Club A's admin cannot see club B's delivery — the @TenantId
        // discriminator makes it invisible → 404 (not 403).
        ResponseEntity<String> res = get(BASE + "/" + bRow, adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----- seed / helpers -----

    private UUID saveDelivery(UUID clubId, long batchId, DeliveryRecipient recipient,
                              UUID flightId, List<DeliveryItem> items) {
        DeliveryProcessState state = items.isEmpty()
                ? DeliveryProcessState.PREPARED
                : DeliveryProcessState.BOOKED;
        return TenantTestContext.runAs(clubId, () -> {
            Delivery delivery = DeliveryTestHydrator.delivery(
                    state, items.isEmpty() ? null : "INV-2026-001", batchId, recipient, items);
            if (flightId != null) {
                DeliveryTestHydrator.withFlight(delivery, flightId);
            }
            return jpaDeliveries.save(delivery).getId();
        });
    }

    private static DeliveryRecipient recipientNamed(String lastname) {
        return new DeliveryRecipient(null, "First", lastname,
                null, null, null, null, null, null);
    }

    private UUID seedArticle(UUID clubId) {
        return TenantTestContext.runAs(clubId, () -> {
            Article article = Article.register(
                    "ART-FT-" + UUID.randomUUID().toString().substring(0, 8),
                    "Flight time", null, null, true);
            return articles.save(article).getId().value();
        });
    }

    private UUID seedGliderFlight(UUID clubId) {
        UUID aircraftId = seedAircraft(clubId);
        FlightOperationalData ops = new FlightOperationalData(
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, (short) 0, (short) 0,
                false, false, null, null, null, null, null, null, null, null, false);
        return TenantTestContext.runAs(clubId, () ->
                flights.save(Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops)).getId());
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        String immatriculation = "HB-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        Aircraft a = Aircraft.register(managingClubId, managingClubId, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraft.save(a).getId().value();
    }

    private String adminTokenFor(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private ResponseEntity<String> post(String path, String token) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
