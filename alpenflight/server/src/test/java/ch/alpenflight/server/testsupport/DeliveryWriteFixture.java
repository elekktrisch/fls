package ch.alpenflight.server.testsupport;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterWriteRequest;
import ch.alpenflight.accounting.application.AccountingRuleFiltersService;
import ch.alpenflight.accounting.domain.FilterConfig;
import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.articles.domain.ArticleRepository;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds the shared delivery write-side scenario both the book and delete ITs drive:
 * a Locked, &gt;3-day-old glider flight linked to a Locked tow, a crew pilot with a
 * flight-time credit, and a single deterministic flight-time {@code AccountingRuleFilter}.
 * The caller runs {@code POST /api/v1/deliveries/create} (so the engine produces the
 * Prepared delivery + flips both flights to {@code DeliveryPrepared} + draws the credit);
 * {@link #findDeliveryFor} resolves the created delivery's id from the create response.
 *
 * <p>One seam, two consumers: extracting it here keeps the duplicate-token ratchet flat
 * as the second write IT lands.
 */
public final class DeliveryWriteFixture {

    private static final UUID FILTER_TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final UUID UNIT_MINUTES =
            UUID.fromString("019e2e15-2c00-7a38-8000-000000004a38");
    private static final UUID COST_BALANCE_PILOT_PAYS_ALL =
            UUID.fromString("019e2e15-2c00-7268-8000-000000004268");
    private static final int LEGACY_FLIGHT_TIME = 30;

    private final JdbcTemplate jdbc;
    private final AccountingRuleFiltersService filtersService;
    private final FlightRepository flights;
    private final AircraftRepository aircraft;
    private final ArticleRepository articles;
    private final FlightTypeRepository flightTypes;
    private final PersonRepository persons;
    private final PersonFlightTimeCreditRepository credits;

    public DeliveryWriteFixture(JdbcTemplate jdbc,
                                AccountingRuleFiltersService filtersService,
                                FlightRepository flights,
                                AircraftRepository aircraft,
                                ArticleRepository articles,
                                FlightTypeRepository flightTypes,
                                PersonRepository persons,
                                PersonFlightTimeCreditRepository credits) {
        this.jdbc = jdbc;
        this.filtersService = filtersService;
        this.flights = flights;
        this.aircraft = aircraft;
        this.articles = articles;
        this.flightTypes = flightTypes;
        this.persons = persons;
        this.credits = credits;
    }

    /** The seeded ids needed by the write ITs to assert flight + credit side effects. */
    public record Seed(UUID flightId, UUID towFlightId, UUID creditId) {}

    /**
     * Seeds the eligible glider+tow flights, crew, credit and rule filter under
     * {@code clubId} and returns the ids. The caller still drives the create endpoint;
     * {@link #findDeliveryFor} extracts the resulting delivery id.
     */
    public Seed seedEligibleGliderWithTow(UUID clubId) {
        String gliderImmat = "HB-G" + suffix();
        UUID glider = seedAircraft(clubId, gliderImmat);
        UUID tow = seedAircraft(clubId, "HB-T" + suffix());
        UUID flightType = seedFlightType(clubId, "Schulung", "SCH");
        UUID pilot = seedMember(clubId, "Pilot", "Petra");
        seedArticle(clubId, "ART-FT");

        UUID towFlight = seedLockedAgedFlight(clubId, tow, flightType, false);
        UUID gliderFlight = seedLockedAgedFlight(clubId, glider, flightType, true);
        linkTow(clubId, gliderFlight, towFlight);
        seedCrew(clubId, gliderFlight, pilot, FlightCrewTypeIds.PILOT_OR_STUDENT);
        UUID creditId = seedCredit(clubId, pilot, gliderImmat, 5_400L);

        TenantTestContext.runAs(clubId, () -> filtersService.create(flightTimeFilter()).id());
        return new Seed(gliderFlight, towFlight, creditId);
    }

    /** The id of the delivery the create response produced for {@code flightId}. */
    public static UUID findDeliveryFor(com.fasterxml.jackson.databind.JsonNode createBody, UUID flightId) {
        for (com.fasterxml.jackson.databind.JsonNode delivery : createBody) {
            if (FlightId.of(flightId).toExternal().equals(delivery.get("flight").get("flightId").asText())) {
                return UUID.fromString(delivery.get("id").asText());
            }
        }
        throw new IllegalStateException("create produced no delivery for the seeded flight: " + createBody);
    }

    private UUID seedLockedAgedFlight(UUID clubId, UUID aircraftId, UUID flightTypeId, boolean glider) {
        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        FlightOperationalData ops = new FlightOperationalData(
                start.atZone(ZoneOffset.UTC).toLocalDate(), start,
                start.plus(90, ChronoUnit.MINUTES), null, null,
                null, null, null, null, null, null,
                flightTypeId, null, (short) 1, (short) 0,
                false, false, null, null, null, null, null,
                COST_BALANCE_PILOT_PAYS_ALL, null, null, false);
        UUID flightId = TenantTestContext.runAs(clubId, () -> {
            Flight flight = glider
                    ? Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops)
                    : Flight.createTow(aircraftId, FlightProcessState.VALID.id(), ops);
            flight.transition(FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB,
                    Instant.parse("2026-05-16T00:00:00Z"));
            return flights.save(flight).getId();
        });
        jdbc.update("UPDATE t_flight SET created_on = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), flightId);
        return flightId;
    }

    private void linkTow(UUID clubId, UUID gliderFlightId, UUID towFlightId) {
        TenantTestContext.runAs(clubId, () -> {
            Flight glider = flights.findByIdWithCrew(FlightId.of(gliderFlightId)).orElseThrow();
            Flight tow = flights.findByIdWithCrew(FlightId.of(towFlightId)).orElseThrow();
            glider.linkTow(tow);
            flights.save(glider);
        });
    }

    private UUID seedCredit(UUID clubId, UUID personId, String immatriculation, long balanceSeconds) {
        Person person = persons.findActiveById(personId).orElseThrow();
        PersonFlightTimeCredit credit = PersonFlightTimeCredit.grant(
                person, Instant.parse("2026-12-31T00:00:00Z"),
                false, immatriculation, 0, false, balanceSeconds);
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
        Aircraft built = Aircraft.register(managingClubId, managingClubId, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraft.save(built).getId().value();
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

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
    }
}
