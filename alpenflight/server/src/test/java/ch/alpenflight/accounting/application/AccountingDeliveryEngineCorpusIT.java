package ch.alpenflight.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterWriteRequest;
import ch.alpenflight.accounting.domain.AccountingUnitType;
import ch.alpenflight.accounting.domain.DeliveryItemDetails;
import ch.alpenflight.accounting.domain.FilterConfig;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountingDeliveryEngineCorpusIT extends PostgresIntegrationTest {

    private static final UUID TYPE_RECIPIENT =
            UUID.fromString("019e2e15-2c00-7650-8000-000000004650");
    private static final UUID TYPE_NO_LANDING_TAX =
            UUID.fromString("019e2e15-2c00-7651-8000-000000004651");
    private static final UUID TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final UUID TYPE_INSTRUCTOR_FEE =
            UUID.fromString("019e2e15-2c00-7653-8000-000000004653");
    private static final UUID TYPE_ADDITIONAL_FUEL_FEE =
            UUID.fromString("019e2e15-2c00-7654-8000-000000004654");
    private static final UUID TYPE_LANDING_TAX =
            UUID.fromString("019e2e15-2c00-7655-8000-000000004655");
    private static final UUID TYPE_VSF_FEE =
            UUID.fromString("019e2e15-2c00-7656-8000-000000004656");
    private static final UUID TYPE_ENGINE_TIME =
            UUID.fromString("019e2e15-2c00-7657-8000-000000004657");
    private static final UUID TYPE_DO_NOT_INVOICE =
            UUID.fromString("019e2e15-2c00-7658-8000-000000004658");
    private static final UUID TYPE_START_TAX =
            UUID.fromString("019e2e15-2c00-7659-8000-000000004659");

    private static final UUID UNIT_MINUTES =
            UUID.fromString("019e2e15-2c00-7a38-8000-000000004a38");
    private static final UUID UNIT_LANDINGS =
            UUID.fromString("019e2e15-2c00-7a3a-8000-000000004a3a");
    private static final UUID UNIT_START =
            UUID.fromString("019e2e15-2c00-7a3b-8000-000000004a3b");

    private static final int LEGACY_RECIPIENT = 10;
    private static final int LEGACY_NO_LANDING_TAX = 20;
    private static final int LEGACY_FLIGHT_TIME = 30;
    private static final int LEGACY_INSTRUCTOR_FEE = 40;
    private static final int LEGACY_ADDITIONAL_FUEL_FEE = 50;
    private static final int LEGACY_LANDING_TAX = 60;
    private static final int LEGACY_VSF_FEE = 70;
    private static final int LEGACY_ENGINE_TIME = 80;
    private static final int LEGACY_DO_NOT_INVOICE = 5;
    private static final int LEGACY_START_TAX = 55;

    private static final int COST_BALANCE_NO_INSTRUCTOR_FEE = 4;

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountingDeliveryEngine engine;
    @Autowired AccountingRuleFiltersService filtersService;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID club;

    @BeforeEach
    void seedClub(org.junit.jupiter.api.TestInfo testInfo) {
        String tag = "C" + Integer.toHexString(testInfo.getDisplayName().hashCode());
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, tag + "_", tag);
        fixture.seed();
        club = fixture.clubA();
    }

    @Test
    void flightTimeTiered_oracle1069_1068_example_isBitExact() {
        UUID flight = glider(seconds(1500), (short) 1);
        billGlider(TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, UNIT_MINUTES, "1069", "Schulung", 600, null);
        billGlider(TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, UNIT_MINUTES, "1068", "Schulung", 0, 600);

        List<DeliveryItemDetails> items = compute(flight);

        assertThat(items).extracting(DeliveryItemDetails::articleNumber)
                .containsExactly("1069", "1068");
        assertThat(items.get(0).quantity()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(items.get(0).unitType()).isEqualTo("Minuten");
        assertThat(items.get(1).quantity()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void flightTimeMinutes_nonTerminatingDivision_isBitExactToCsharpDecimal() {
        UUID flight = glider(seconds(100), (short) 1);
        billGlider(TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, UNIT_MINUTES, "MIN", "Flugzeit", 0, null);

        List<DeliveryItemDetails> items = compute(flight);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).quantity().toPlainString())
                .isEqualTo("1.666666666666666666666666667");
    }

    @Test
    void zeroFlightTime_emitsNoItems() {
        UUID flight = glider(zeroDuration(), (short) 1);
        billGlider(TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, UNIT_MINUTES, "MIN", "Flugzeit", 0, null);

        assertThat(compute(flight)).isEmpty();
    }

    @Test
    void flightTimeTierGap_leavesRemainderSilentlyUnbilled() {
        UUID flight = glider(seconds(1500), (short) 1);
        billGlider(TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, UNIT_MINUTES, "MID", "Schulung", 600, 1200);

        assertThat(compute(flight)).isEmpty();
    }

    @Test
    void engineTimeTiered_billsPerTier_andNullCounterEmitsNone() {
        UUID withEngine = gliderWithEngineCounter(seconds(1500), 0L, 1800L);
        billGlider(TYPE_ENGINE_TIME, LEGACY_ENGINE_TIME, UNIT_MINUTES, "ENG-HI", "Motor",
                engineWindow(600, null));
        billGlider(TYPE_ENGINE_TIME, LEGACY_ENGINE_TIME, UNIT_MINUTES, "ENG-LO", "Motor",
                engineWindow(0, 600));

        List<DeliveryItemDetails> withEngineItems = compute(withEngine);
        assertThat(withEngineItems).extracting(DeliveryItemDetails::articleNumber)
                .containsExactly("ENG-HI", "ENG-LO");
        assertThat(withEngineItems.get(0).quantity()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(withEngineItems.get(1).quantity()).isEqualByComparingTo(new BigDecimal("10"));

        UUID noEngine = glider(seconds(1500), (short) 1);
        assertThat(compute(noEngine)).isEmpty();
    }

    @Test
    void landingTax_billsNrOfLdgs_butNoLandingTaxSuppressesTheGliderLine() {
        UUID taxed = glider(seconds(1500), (short) 3);
        billGlider(TYPE_LANDING_TAX, LEGACY_LANDING_TAX, UNIT_LANDINGS, "LT", "Landetaxe", 0, null);
        List<DeliveryItemDetails> taxedItems = compute(taxed);
        assertThat(taxedItems).hasSize(1);
        assertThat(taxedItems.get(0).articleNumber()).isEqualTo("LT");
        assertThat(taxedItems.get(0).quantity()).isEqualByComparingTo(BigDecimal.valueOf(3));
        assertThat(taxedItems.get(0).unitType()).isEqualTo("Landung");

        UUID suppressed = glider(seconds(1500), (short) 3);
        noLandingTaxForGlider();
        assertThat(compute(suppressed)).isEmpty();
    }

    @Test
    void startTax_emitsOneStartUnit() {
        UUID flight = glider(seconds(1500), (short) 1);
        billGlider(TYPE_START_TAX, LEGACY_START_TAX, UNIT_START, "ST", "Starttaxe", 0, null);

        List<DeliveryItemDetails> items = compute(flight);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).articleNumber()).isEqualTo("ST");
        assertThat(items.get(0).quantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(items.get(0).unitType()).isEqualTo("Start");
    }

    @Test
    void instructorFee_alwaysNewLine_andZeroOnNoInstructorFee() {
        UUID flight = gliderWithCostBalance(seconds(1800), (short) 1, COST_BALANCE_NO_INSTRUCTOR_FEE);
        billGlider(TYPE_INSTRUCTOR_FEE, LEGACY_INSTRUCTOR_FEE, UNIT_MINUTES, "INSTR", "Lehrer", 0, null);
        billGlider(TYPE_INSTRUCTOR_FEE, LEGACY_INSTRUCTOR_FEE, UNIT_MINUTES, "INSTR", "Lehrer", 0, null);

        List<DeliveryItemDetails> items = compute(flight);
        assertThat(items).hasSize(2);
        assertThat(items).extracting(DeliveryItemDetails::articleNumber)
                .containsExactly("INSTR", "INSTR");
        assertThat(items).allSatisfy(item ->
                assertThat(item.quantity()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void additionalFuelFee_emitsLine() {
        UUID flight = glider(seconds(1800), (short) 1);
        billGlider(TYPE_ADDITIONAL_FUEL_FEE, LEGACY_ADDITIONAL_FUEL_FEE, UNIT_MINUTES,
                "FUEL", "Treibstoff", 0, null);

        List<DeliveryItemDetails> items = compute(flight);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).articleNumber()).isEqualTo("FUEL");
        assertThat(items.get(0).quantity()).isEqualByComparingTo(new BigDecimal("30"));
    }

    @Test
    void vsfFee_billsNrOfLdgs() {
        UUID flight = glider(seconds(1500), (short) 2);
        billGlider(TYPE_VSF_FEE, LEGACY_VSF_FEE, UNIT_LANDINGS, "VSF", "VSF-Beitrag", 0, null);

        List<DeliveryItemDetails> items = compute(flight);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).articleNumber()).isEqualTo("VSF");
        assertThat(items.get(0).quantity()).isEqualByComparingTo(BigDecimal.valueOf(2));
    }

    @Test
    void ignoreFlight_shortCircuitsToEmptyDelivery() {
        UUID flight = glider(seconds(1500), (short) 1);
        ignoreFlight();
        billGlider(TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, UNIT_MINUTES, "MIN", "Flugzeit", 0, null);

        RuleBasedDeliveryDetails result =
                TenantTestContext.runAs(club, () -> engine.computeForFlight(flight));
        assertThat(result.isDoNotInvoiceFlight()).isTrue();
        assertThat(result.deliveryItems()).isEmpty();
    }

    @Test
    void recipient_firstMatchWins() {
        UUID flight = glider(seconds(1500), (short) 1);
        seedMember("First", "Recipient", "REC-1");
        seedMember("Second", "Recipient", "REC-2");
        UUID first = recipient("REC-1", "First Recipient");
        recipient("REC-2", "Second Recipient");

        RuleBasedDeliveryDetails result =
                TenantTestContext.runAs(club, () -> engine.computeForFlight(flight));
        assertThat(result.recipient()).isNotNull();
        assertThat(result.recipient().recipientName()).isEqualTo("First Recipient");
        assertThat(result.getMatchedFilterIds()).contains(first);
    }

    @Test
    void gliderTowRecursion_towLineRollsIntoTheGliderDelivery() {
        UUID tow = tow(seconds(1200), (short) 1);
        UUID glider = gliderWithTow(seconds(1500), (short) 1, tow);
        billGliderAndTow(TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME, UNIT_MINUTES, "ROLL", "Flugzeit");

        List<DeliveryItemDetails> items = compute(glider);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).articleNumber()).isEqualTo("ROLL");
        assertThat(items.get(0).quantity()).isEqualByComparingTo(new BigDecimal("45"));
    }


    private List<DeliveryItemDetails> compute(UUID flightId) {
        return TenantTestContext.runAs(club, () -> engine.computeForFlight(flightId)).deliveryItems();
    }


    private void billGlider(UUID typeId, int legacyType, UUID unitTypeId, String article,
                            String lineText, @Nullable Integer minSeconds, @Nullable Integer maxSeconds) {
        TenantTestContext.runAs(club, () -> filtersService.create(new AccountingRuleFilterWriteRequest(
                typeId, legacyType, unitTypeId, article, null,
                true, false, false,
                article, lineText, null, null,
                gliderConfig(false, minSeconds, maxSeconds, null, null, false))).id());
    }

    private void billGlider(UUID typeId, int legacyType, UUID unitTypeId, String article,
                            String lineText, EngineWindow window) {
        TenantTestContext.runAs(club, () -> filtersService.create(new AccountingRuleFilterWriteRequest(
                typeId, legacyType, unitTypeId, article, null,
                true, false, false,
                article, lineText, null, null,
                gliderConfig(false, null, null, window.min(), window.max(), false))).id());
    }

    private void billGliderAndTow(UUID typeId, int legacyType, UUID unitTypeId, String article,
                                  String lineText) {
        TenantTestContext.runAs(club, () -> filtersService.create(new AccountingRuleFilterWriteRequest(
                typeId, legacyType, unitTypeId, article, null,
                true, false, false,
                article, lineText, null, null,
                gliderConfig(true, 0, null, null, null, true))).id());
    }

    private void noLandingTaxForGlider() {
        FilterConfig config = new FilterConfig(
                true, false, false,
                true, false, false,
                false, false, false,
                null, 0, null, null, null,
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                null, null);
        TenantTestContext.runAs(club, () -> filtersService.create(new AccountingRuleFilterWriteRequest(
                TYPE_NO_LANDING_TAX, LEGACY_NO_LANDING_TAX, null, "NoLdg", null,
                true, false, false,
                null, null, null, null, config)).id());
    }

    private void ignoreFlight() {
        TenantTestContext.runAs(club, () -> filtersService.create(new AccountingRuleFilterWriteRequest(
                TYPE_DO_NOT_INVOICE, LEGACY_DO_NOT_INVOICE, null, "Ignore", null,
                true, false, false,
                null, null, null, null,
                gliderConfig(false, null, null, null, null, false))).id());
    }

    private UUID recipient(String memberNumber, String name) {
        return TenantTestContext.runAs(club, () -> filtersService.create(
                new AccountingRuleFilterWriteRequest(
                        TYPE_RECIPIENT, LEGACY_RECIPIENT, null, name, null,
                        true, true, false,
                        null, null, memberNumber, name,
                        gliderConfig(false, null, null, null, null, false))).id());
    }

    private record EngineWindow(int min, @Nullable Integer max) {}

    private static EngineWindow engineWindow(int min, @Nullable Integer max) {
        return new EngineWindow(min, max);
    }

    private static FilterConfig gliderConfig(boolean isTow, @Nullable Integer minFlightSeconds,
                                             @Nullable Integer maxFlightSeconds,
                                             @Nullable Integer minEngineSeconds,
                                             @Nullable Integer maxEngineSeconds,
                                             boolean extendToTow) {
        return new FilterConfig(
                true, isTow, false,
                false, false, false,
                false, extendToTow, false,
                null, minFlightSeconds, maxFlightSeconds, minEngineSeconds, maxEngineSeconds,
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                null, null);
    }


    private record Times(@Nullable Instant start, @Nullable Instant ldg) {}

    private static Times seconds(int durationSeconds) {
        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        return new Times(start, start.plusSeconds(durationSeconds));
    }

    private static Times zeroDuration() {
        return new Times(null, null);
    }

    private UUID glider(Times times, short nrOfLdgs) {
        return seedFlight(false, times, nrOfLdgs, null, null);
    }

    private UUID gliderWithCostBalance(Times times, short nrOfLdgs, int costBalanceLegacyId) {
        return seedFlight(false, times, nrOfLdgs, resolveCostBalanceTypeId(costBalanceLegacyId), null);
    }

    private UUID gliderWithEngineCounter(Times times, long engineStart, long engineEnd) {
        return seedFlight(false, times, (short) 1, null, new long[] {engineStart, engineEnd});
    }

    private UUID gliderWithTow(Times times, short nrOfLdgs, UUID towFlightId) {
        UUID glider = seedFlight(false, times, nrOfLdgs, null, null);
        TenantTestContext.runAs(club, () -> {
            Flight g = flights.findByIdWithCrew(FlightId.of(glider)).orElseThrow();
            g.linkTow(flights.findByIdWithCrew(FlightId.of(towFlightId)).orElseThrow());
            flights.save(g);
        });
        return glider;
    }

    private UUID tow(Times times, short nrOfLdgs) {
        return seedFlight(true, times, nrOfLdgs, null, null);
    }

    private UUID seedFlight(boolean isTow, Times times, short nrOfLdgs,
                            @Nullable UUID costBalanceTypeId, long @Nullable [] engineCounter) {
        UUID aircraft = seedAircraft();
        String suffix = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        UUID flightType = seedFlightType("Schulung " + suffix, "S" + suffix);
        UUID pilot = seedMember("Petra", "Pilot", "P" + suffix);
        Long engineStart = engineCounter == null ? null : engineCounter[0];
        Long engineEnd = engineCounter == null ? null : engineCounter[1];
        FlightOperationalData ops = new FlightOperationalData(
                times.start() == null ? Instant.parse("2026-05-15T08:00:00Z")
                        .atZone(ZoneOffset.UTC).toLocalDate()
                        : times.start().atZone(ZoneOffset.UTC).toLocalDate(),
                times.start(), times.ldg(), null, null,
                null, null, null, null, null, null,
                flightType, null, nrOfLdgs, (short) 0,
                false, false, engineStart, engineEnd, null, null, null,
                costBalanceTypeId, null, null, false);
        UUID flightId = TenantTestContext.runAs(club, () -> {
            Flight created = isTow
                    ? Flight.createTow(aircraft, FlightProcessState.VALID.id(), ops)
                    : Flight.createGlider(aircraft, FlightProcessState.VALID.id(), ops);
            return flights.save(created).getId();
        });
        TenantTestContext.runAs(club, () -> {
            Flight flight = flights.findByIdWithCrew(FlightId.of(flightId)).orElseThrow();
            flight.replaceCrew(List.of(new CrewMemberSpec(
                    pilot, FlightCrewTypeIds.PILOT_OR_STUDENT, null, null, null, null, null, null)));
            flights.save(flight);
        });
        return flightId;
    }

    private UUID seedAircraft() {
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        String immatriculation = "HB-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        Aircraft aircraft = Aircraft.register(club, club, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraftRepository.save(aircraft).getId().value();
    }

    private UUID seedFlightType(String name, String code) {
        FlightType flightType = FlightType.register(name, code,
                false, false, false, false, false,
                true, true, true,
                false, false, false, null);
        return TenantTestContext.runAs(club, () -> flightTypes.save(flightType).getId().value());
    }

    private UUID seedMember(String firstname, String lastname, String memberNumber) {
        Person person = Person.register(firstname, lastname, null);
        return TenantTestContext.runAs(club, () -> {
            person.joinClub(club, memberNumber, null,
                    PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
            return persons.save(person).getId().value();
        });
    }

    private @Nullable UUID resolveCostBalanceTypeId(int legacyId) {
        return jdbc.queryForObject(
                "SELECT id FROM t_flight_cost_balance_type WHERE legacy_int_id = ?",
                UUID.class, legacyId);
    }
}
