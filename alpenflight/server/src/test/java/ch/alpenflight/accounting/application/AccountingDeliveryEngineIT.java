package ch.alpenflight.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterDetail;
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
import ch.alpenflight.flights.domain.FlightNotFoundException;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for the {@link AccountingDeliveryEngine} orchestrator. Seeds
 * two clubs + a flight graph through production-create paths (ADR 0027 §3) and
 * drives {@code computeForFlight} under each tenant via
 * {@link TenantTestContext#runAs}. Proves: a FlightTime tier + a LandingTax + a
 * Recipient filter produce the expected items + recipient + matched-filter-ids;
 * a DoNotInvoice filter short-circuits to an empty delivery; a cross-tenant
 * flight id is invisible → {@link FlightNotFoundException}.
 */
class AccountingDeliveryEngineIT extends PostgresIntegrationTest {

    private static final UUID FILTER_TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final UUID FILTER_TYPE_LANDING_TAX =
            UUID.fromString("019e2e15-2c00-7655-8000-000000004655");
    private static final UUID FILTER_TYPE_RECIPIENT =
            UUID.fromString("019e2e15-2c00-7650-8000-000000004650");
    private static final UUID FILTER_TYPE_DO_NOT_INVOICE =
            UUID.fromString("019e2e15-2c00-7658-8000-000000004658");
    private static final UUID UNIT_MINUTES =
            UUID.fromString("019e2e15-2c00-7a38-8000-000000004a38");
    private static final UUID UNIT_LANDINGS =
            UUID.fromString("019e2e15-2c00-7a3a-8000-000000004a3a");

    private static final int LEGACY_FLIGHT_TIME = 30;
    private static final int LEGACY_LANDING_TAX = 60;
    private static final int LEGACY_RECIPIENT = 10;
    private static final int LEGACY_DO_NOT_INVOICE = 5;

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

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seedClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_ADE_", "IT_ADE");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void flightTimeTier_landingTax_andRecipient_produceItemsAndRecipient() {
        UUID aircraft = seedAircraft(clubA);
        UUID flightType = seedFlightType(clubA, "Schulung", "SCH");
        UUID pilot = seedMember(clubA, "Pilot", "Petra", "REC-1");

        UUID flight = seedGliderFlight(clubA, aircraft, flightType,
                Instant.parse("2026-05-15T08:00:00Z"),
                Instant.parse("2026-05-15T09:30:00Z"), (short) 2);
        seedCrew(flight, pilot, FlightCrewTypeIds.PILOT_OR_STUDENT);

        UUID flightTimeFilterId = TenantTestContext.runAs(clubA, () ->
                filtersService.create(lineRequest(FILTER_TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME,
                        UNIT_MINUTES, "FT", "ART-FT", "Flugzeit")).id());
        UUID landingTaxFilterId = TenantTestContext.runAs(clubA, () ->
                filtersService.create(lineRequest(FILTER_TYPE_LANDING_TAX, LEGACY_LANDING_TAX,
                        UNIT_LANDINGS, "LT", "ART-LT", "Landetaxe")).id());
        UUID recipientFilterId = TenantTestContext.runAs(clubA, () ->
                filtersService.create(recipientRequest("REC-1", "Petra Pilot")).id());

        RuleBasedDeliveryDetails result =
                TenantTestContext.runAs(clubA, () -> engine.computeForFlight(flight));

        List<DeliveryItemDetails> items = result.deliveryItems();
        assertThat(items).extracting(DeliveryItemDetails::articleNumber)
                .containsExactly("ART-FT", "ART-LT");

        DeliveryItemDetails ft = items.get(0);
        // 90 min flight, tier min=0 -> bill all; SEC->MIN = 5400/60 = 90.
        assertThat(ft.quantity()).isEqualByComparingTo(new BigDecimal("90"));
        assertThat(ft.unitType()).isEqualTo(AccountingUnitType.MIN.unitTypeString());

        DeliveryItemDetails lt = items.get(1);
        assertThat(lt.quantity()).isEqualByComparingTo(BigDecimal.valueOf(2)); // nrOfLdgs
        assertThat(lt.unitType()).isEqualTo(AccountingUnitType.LDGS.unitTypeString());

        assertThat(result.recipient()).isNotNull();
        assertThat(result.recipient().personClubMemberNumber()).isEqualTo("REC-1");
        assertThat(result.recipient().recipientName()).isEqualTo("Petra Pilot");

        assertThat(result.getMatchedFilterIds())
                .contains(flightTimeFilterId, landingTaxFilterId, recipientFilterId);
        assertThat(result.isDoNotInvoiceFlight()).isFalse();
    }

    @Test
    void doNotInvoiceFilter_shortCircuitsToEmptyDelivery() {
        UUID aircraft = seedAircraft(clubA);
        UUID flightType = seedFlightType(clubA, "Schulung", "SCH");
        UUID pilot = seedMember(clubA, "Pilot", "Petra", "REC-2");

        UUID flight = seedGliderFlight(clubA, aircraft, flightType,
                Instant.parse("2026-05-15T08:00:00Z"),
                Instant.parse("2026-05-15T09:30:00Z"), (short) 1);
        seedCrew(flight, pilot, FlightCrewTypeIds.PILOT_OR_STUDENT);

        UUID doNotInvoiceId = TenantTestContext.runAs(clubA, () ->
                filtersService.create(ignoreRequest()).id());
        // A line filter that WOULD emit — proves the short-circuit, not just "no rules".
        TenantTestContext.runAs(clubA, () ->
                filtersService.create(lineRequest(FILTER_TYPE_FLIGHT_TIME, LEGACY_FLIGHT_TIME,
                        UNIT_MINUTES, "FT", "ART-FT", "Flugzeit")));

        RuleBasedDeliveryDetails result =
                TenantTestContext.runAs(clubA, () -> engine.computeForFlight(flight));

        assertThat(result.isDoNotInvoiceFlight()).isTrue();
        assertThat(result.deliveryItems()).isEmpty();
        assertThat(result.getMatchedFilterIds()).containsExactly(doNotInvoiceId);
    }

    @Test
    void crossTenantFlight_isInvisible_throwsNotFound() {
        UUID aircraft = seedAircraft(clubA);
        UUID flightType = seedFlightType(clubA, "Schulung", "SCH");
        UUID flight = seedGliderFlight(clubA, aircraft, flightType,
                Instant.parse("2026-05-15T08:00:00Z"),
                Instant.parse("2026-05-15T09:30:00Z"), (short) 1);

        assertThatThrownBy(() ->
                TenantTestContext.runAs(clubB, () -> engine.computeForFlight(flight)))
                .isInstanceOf(FlightNotFoundException.class);
    }

    // -- payloads ---------------------------------------------------------------

    // A glider-scoped line filter matching all flights (every facet useAllExcept +
    // empty -> no condition), tier window min=0/max=unlimited.
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

    private static AccountingRuleFilterWriteRequest recipientRequest(String memberNumber, String name) {
        return new AccountingRuleFilterWriteRequest(
                FILTER_TYPE_RECIPIENT, LEGACY_RECIPIENT, null, "Recipient", null,
                true, true, false,
                null, null, memberNumber, name, gliderAllConfig());
    }

    private static AccountingRuleFilterWriteRequest ignoreRequest() {
        return new AccountingRuleFilterWriteRequest(
                FILTER_TYPE_DO_NOT_INVOICE, LEGACY_DO_NOT_INVOICE, null, "Ignore", null,
                true, false, false,
                null, null, null, null, gliderAllConfig());
    }

    private static FilterConfig gliderAllConfig() {
        return new FilterConfig(
                true, false, false,
                false, false, false,
                false, false, false,
                null, null, null, null, null,
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(), FilterConfig.MatchList.empty(),
                null, null);
    }

    // -- seeding (production-create paths) --------------------------------------

    private UUID seedAircraft(UUID managingClubId) {
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        String immatriculation = "HB-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
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
        Person person = Person.register(firstname, lastname, null);
        return TenantTestContext.runAs(clubId, () -> {
            person.joinClub(clubId, memberNumber, null,
                    PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
            return persons.save(person).getId().value();
        });
    }

    private UUID seedGliderFlight(UUID clubId, UUID aircraftId, UUID flightTypeId,
                                  Instant start, Instant ldg, short nrOfLdgs) {
        FlightOperationalData ops = new FlightOperationalData(
                start.atZone(java.time.ZoneOffset.UTC).toLocalDate(), start, ldg, null, null,
                null, null, null, null, null, null,
                flightTypeId, null, nrOfLdgs, (short) 0,
                false, false, null, null, null, null, null, null, null, null, false);
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
