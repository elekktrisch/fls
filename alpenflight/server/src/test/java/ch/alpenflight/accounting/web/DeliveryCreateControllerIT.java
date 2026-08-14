package ch.alpenflight.accounting.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterWriteRequest;
import ch.alpenflight.accounting.application.AccountingRuleFiltersService;
import ch.alpenflight.accounting.domain.FilterConfig;
import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.articles.domain.ArticleRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flights.domain.TransitionTrigger;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class DeliveryCreateControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREATE = "/api/v1/deliveries/create";

    private static final UUID FILTER_TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final UUID UNIT_MINUTES =
            UUID.fromString("019e2e15-2c00-7a38-8000-000000004a38");
    private static final UUID COST_BALANCE_PILOT_PAYS_ALL =
            UUID.fromString("019e2e15-2c00-7268-8000-000000004268");
    private static final int LEGACY_FLIGHT_TIME = 30;
    private static final long CREDIT_BALANCE_SECONDS = 5_400L;

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired AccountingRuleFiltersService filtersService;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired ArticleRepository articles;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;
    @Autowired PersonFlightTimeCreditRepository credits;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;
    private String adminA;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DLVCR_", "IT_DLVCR");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        adminA = adminTokenFor(clubA);
    }

    @Test
    void create_persistsDelivery_flipsFlight_andDrawsDownCredit() {
        Scenario s = seedEligibleFlight(clubA, "HB-CREATE");

        ResponseEntity<String> res = post(CREATE, adminA);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        JsonNode delivery = body.get(0);
        assertThat(delivery.get("processStateId").asInt()).isEqualTo(10);
        assertThat(delivery.get("flight").get("flightId").asText())
                .isEqualTo(FlightId.of(s.flightId()).toExternal());
        assertThat(delivery.get("items").size()).isGreaterThan(0);

        assertThat(flightProcessState(s.flightId()))
                .as("the billed flight flipped to DeliveryPrepared")
                .isEqualTo(FlightProcessState.DELIVERY_PREPARED.id());
        assertThat(currentBalance(s.creditId()))
                .as("the credit was drawn down by the consumed flight time")
                .isLessThan(CREDIT_BALANCE_SECONDS);
        assertThat(currentTransactionCount(s.creditId()))
                .as("exactly one current credit transaction remains")
                .isEqualTo(1);
    }

    @Test
    void create_byNonAdmin_isForbidden() {
        seedEligibleFlight(clubA, "HB-FORBID");
        String pilot = jwts.mint(c -> c
                .claim("clubId", clubA.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));

        ResponseEntity<String> res = post(CREATE, pilot);

        assertThat(res.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void create_isTenantScoped_clubBEligibleFlightInvisibleToClubA() {
        seedEligibleFlight(clubB, "HB-OTHER");

        ResponseEntity<String> res = post(CREATE, adminA);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).size())
                .as("club A's create sees no eligible flight (club B's is tenant-invisible)")
                .isZero();
    }


    private Scenario seedEligibleFlight(UUID clubId, String immatriculation) {
        UUID aircraft = seedAircraft(clubId, immatriculation);
        UUID flightType = seedFlightType(clubId, "Schulung", "SCH");
        UUID pilot = seedMember(clubId, "Pilot", "Petra");
        seedArticle(clubId, "ART-FT");

        UUID flight = seedLockedAgedGliderFlight(clubId, aircraft, flightType);
        seedCrew(clubId, flight, pilot, FlightCrewTypeIds.PILOT_OR_STUDENT);
        UUID creditId = seedCredit(clubId, pilot, immatriculation);

        TenantTestContext.runAs(clubId, () ->
                filtersService.create(flightTimeFilter()).id());

        return new Scenario(flight, creditId);
    }

    private record Scenario(UUID flightId, UUID creditId) {}

    private UUID seedLockedAgedGliderFlight(UUID clubId, UUID aircraftId, UUID flightTypeId) {
        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        FlightOperationalData ops = new FlightOperationalData(
                start.atZone(java.time.ZoneOffset.UTC).toLocalDate(), start,
                start.plus(90, ChronoUnit.MINUTES), null, null,
                null, null, null, null, null, null,
                flightTypeId, null, (short) 1, (short) 0,
                false, false, null, null, null, null, null,
                COST_BALANCE_PILOT_PAYS_ALL, null, null, false);
        UUID flightId = TenantTestContext.runAs(clubId, () -> {
            Flight flight = Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops);
            flight.transition(FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB,
                    Instant.parse("2026-05-16T00:00:00Z"));
            return flights.save(flight).getId();
        });
        jdbc.update("UPDATE t_flight SET created_on = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), flightId);
        return flightId;
    }

    private UUID seedCredit(UUID clubId, UUID personId, String immatriculation) {
        Person person = persons.findActiveById(personId).orElseThrow();
        PersonFlightTimeCredit credit = PersonFlightTimeCredit.grant(
                person, Instant.parse("2026-12-31T00:00:00Z"),
                false, immatriculation, 0, false, CREDIT_BALANCE_SECONDS);
        return TenantTestContext.runAs(clubId, () -> credits.save(credit).getId());
    }

    private AccountingRuleFilterWriteRequest flightTimeFilter() {
        FilterConfig config = new FilterConfig(
                true, false, false, false, false, false, false, false, false,
                null, 0, null, null, null,
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                null, null);
        return new AccountingRuleFilterWriteRequest(
                FILTER_TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, UNIT_MINUTES, "FT", null,
                true, false, false, "ART-FT", "Flugzeit", null, null, config);
    }

    private void seedArticle(UUID clubId, String number) {
        TenantTestContext.runAs(clubId, () ->
                articles.save(Article.register(number, "Flight time", null, null, true)).getId());
    }

    private UUID seedAircraft(UUID managingClubId, String immatriculation) {
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        Aircraft aircraft = Aircraft.register(managingClubId, managingClubId, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraftRepository.save(aircraft).getId().value();
    }

    private UUID seedFlightType(UUID clubId, String name, String code) {
        FlightType flightType = FlightType.register(name, code,
                false, false, false, false, false, true, true, true,
                false, false, false, null);
        return TenantTestContext.runAs(clubId, () -> flightTypes.save(flightType).getId().value());
    }

    private UUID seedMember(UUID clubId, String lastname, String firstname) {
        String unique = "M-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Person person = Person.register(firstname, lastname, null);
        return TenantTestContext.runAs(clubId, () -> {
            person.joinClub(clubId, unique, null,
                    PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
            return persons.save(person).getId().value();
        });
    }

    private void seedCrew(UUID clubId, UUID flightId, UUID personId, UUID crewTypeId) {
        TenantTestContext.runAs(clubId, () -> {
            Flight flight = flights.findByIdWithCrew(FlightId.of(flightId)).orElseThrow();
            flight.replaceCrew(List.of(new CrewMemberSpec(
                    personId, crewTypeId, null, null, null, null, null, null)));
            flights.save(flight);
        });
    }


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

    private String adminTokenFor(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
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
