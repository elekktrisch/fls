package ch.alpenflight.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterWriteRequest;
import ch.alpenflight.accounting.domain.DeliveryItemDetails;
import ch.alpenflight.accounting.domain.FilterConfig;
import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the credit-discount branch fires through the no-persist dry-run path and
 * that the path mutates NOTHING: a billed pilot's {@link PersonFlightTimeCredit}
 * discounts the FlightTime line, re-running yields identical output, and no
 * {@code PersonFlightTimeCreditTransaction} row is written
 * ({@code DeliveryService.cs:405-408} AsNoTracking parity).
 */
class AccountingDeliveryEngineCreditIT extends PostgresIntegrationTest {

    private static final UUID FILTER_TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final UUID UNIT_MINUTES =
            UUID.fromString("019e2e15-2c00-7a38-8000-000000004a38");
    private static final UUID COST_BALANCE_PILOT_PAYS_ALL =
            UUID.fromString("019e2e15-2c00-7268-8000-000000004268");

    private static final int LEGACY_FLIGHT_TIME = 30;
    private static final int DISCOUNT_PERCENT = 25;

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountingDeliveryEngine engine;
    @Autowired AccountingRuleFiltersService filtersService;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;
    @Autowired PersonFlightTimeCreditRepository credits;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;

    @BeforeEach
    void seedClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_ADEC_", "IT_ADEC");
        fixture.seed();
        clubA = fixture.clubA();
    }

    @Test
    void fullyCoveredFlight_emitsOneDiscountedFlightTimeLine() {
        Scenario s = seedCoveredFlight("HB-CREDIT", 5_400L, COST_BALANCE_PILOT_PAYS_ALL);

        RuleBasedDeliveryDetails result =
                TenantTestContext.runAs(clubA, () -> engine.computeForFlight(s.flightId()));

        DeliveryItemDetails ft = onlyFlightTimeLine(result);
        assertThat(ft.quantity()).isEqualByComparingTo(new BigDecimal("90"));
        assertThat(ft.discountInPercent())
                .as("the credit's DiscountInPercent is stamped on the fully-covered line")
                .isEqualTo(DISCOUNT_PERCENT);
    }

    // The credit applies to the BILLED recipient; the recipient must first be
    // resolved (a recipient filter, or the PILOT_PAYS_ALL / NO_INSTRUCTOR_FEE
    // cost-balance fallback to the PIC). A flight with no cost-balance type leaves
    // the recipient unresolved, so creditsForBilledPerson loads nothing and the
    // line is billed uncredited even though a matching credit exists — the gap the
    // e2e seed hit. Pins the recipient->credit linkage so it can't regress silently.
    @Test
    void noBilledRecipient_emitsUncreditedLine() {
        Scenario s = seedCoveredFlight("HB-NOREC", 5_400L, null);

        RuleBasedDeliveryDetails result =
                TenantTestContext.runAs(clubA, () -> engine.computeForFlight(s.flightId()));

        DeliveryItemDetails ft = onlyFlightTimeLine(result);
        assertThat(result.recipient())
                .as("no cost-balance fallback resolved a billed recipient")
                .isNull();
        assertThat(ft.discountInPercent())
                .as("an unresolved recipient loads no credit, so the line is uncredited")
                .isZero();
    }

    @Test
    void dryRunIsIdempotentAndWritesNoTransaction() {
        Scenario s = seedCoveredFlight("HB-NOWRITE", 5_400L, COST_BALANCE_PILOT_PAYS_ALL);
        long before = transactionCount(s.creditId());

        RuleBasedDeliveryDetails first =
                TenantTestContext.runAs(clubA, () -> engine.computeForFlight(s.flightId()));
        RuleBasedDeliveryDetails second =
                TenantTestContext.runAs(clubA, () -> engine.computeForFlight(s.flightId()));

        assertThat(onlyFlightTimeLine(second).discountInPercent())
                .as("re-running the dry-run yields the identical discounted line")
                .isEqualTo(onlyFlightTimeLine(first).discountInPercent());
        assertThat(onlyFlightTimeLine(second).quantity())
                .isEqualByComparingTo(onlyFlightTimeLine(first).quantity());
        assertThat(transactionCount(s.creditId()))
                .as("the dry-run inserts no PersonFlightTimeCreditTransaction (no balance mutation)")
                .isEqualTo(before);
    }

    private DeliveryItemDetails onlyFlightTimeLine(RuleBasedDeliveryDetails result) {
        List<DeliveryItemDetails> lines = result.deliveryItems().stream()
                .filter(item -> "ART-FT".equals(item.articleNumber()))
                .toList();
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }

    private long transactionCount(UUID creditId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM t_person_flight_time_credit_transaction WHERE credit_id = ?",
                Long.class, creditId);
        return count == null ? 0 : count;
    }

    private Scenario seedCoveredFlight(String immatriculation, long balanceSeconds,
                                      @Nullable UUID costBalanceType) {
        UUID aircraft = seedAircraft(clubA, immatriculation);
        UUID flightType = seedFlightType(clubA, "Schulung", "SCH");
        UUID pilot = seedMember(clubA, "Pilot", "Petra", "REC-CRED");

        UUID flight = seedGliderFlight(clubA, aircraft, flightType,
                Instant.parse("2026-05-15T08:00:00Z"),
                Instant.parse("2026-05-15T09:30:00Z"),
                costBalanceType);
        seedCrew(flight, pilot, FlightCrewTypeIds.PILOT_OR_STUDENT);

        UUID creditId = seedCredit(pilot, immatriculation, balanceSeconds);

        TenantTestContext.runAs(clubA, () ->
                filtersService.create(lineRequest(FILTER_TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME,
                        UNIT_MINUTES, "FT", "ART-FT", "Flugzeit")).id());

        return new Scenario(flight, creditId);
    }

    private record Scenario(UUID flightId, UUID creditId) {}

    private UUID seedCredit(UUID personId, String immatriculation, long balanceSeconds) {
        Person person = persons.findActiveById(personId).orElseThrow();
        PersonFlightTimeCredit credit = PersonFlightTimeCredit.grant(
                person,
                Instant.parse("2026-12-31T00:00:00Z"),
                false, immatriculation, DISCOUNT_PERCENT,
                false, balanceSeconds);
        return TenantTestContext.runAs(clubA, () -> credits.save(credit).getId());
    }

    private static AccountingRuleFilterWriteRequest lineRequest(UUID typeId, int legacyType,
                                                                UUID unitTypeId, String name,
                                                                String article, String lineText) {
        FilterConfig config = new FilterConfig(
                true, false, false,
                false, false, false,
                false, false, false,
                null, 0, null, null, null,
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                null, null);
        return new AccountingRuleFilterWriteRequest(
                typeId, legacyType, unitTypeId, name, null,
                true, false, false,
                article, lineText, null, null, config);
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
                false, false, false, false, false,
                true, true, true,
                false, false, false, null);
        return TenantTestContext.runAs(clubId,
                () -> flightTypes.save(flightType).getId().value());
    }

    private UUID seedMember(UUID clubId, String lastname, String firstname, String memberNumber) {
        String unique = memberNumber + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        Person person = Person.register(firstname, lastname, null);
        return TenantTestContext.runAs(clubId, () -> {
            person.joinClub(clubId, unique, null,
                    PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
            return persons.save(person).getId().value();
        });
    }

    private UUID seedGliderFlight(UUID clubId, UUID aircraftId, UUID flightTypeId,
                                  Instant start, Instant ldg, @Nullable UUID costBalanceType) {
        FlightOperationalData ops = new FlightOperationalData(
                start.atZone(java.time.ZoneOffset.UTC).toLocalDate(), start, ldg, null, null,
                null, null, null, null, null, null,
                flightTypeId, null, (short) 1, (short) 0,
                false, false, null, null, null, null, null,
                costBalanceType, null, null, false);
        return TenantTestContext.runAs(clubId, () ->
                flights.save(Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops)).getId());
    }

    private void seedCrew(UUID flightId, UUID personId, UUID crewTypeId) {
        TenantTestContext.runAs(clubA, () -> {
            Flight flight = flights.findByIdWithCrew(FlightId.of(flightId)).orElseThrow();
            flight.replaceCrew(List.of(new CrewMemberSpec(
                    personId, crewTypeId, null, null, null, null, null, null)));
            flights.save(flight);
        });
    }
}
