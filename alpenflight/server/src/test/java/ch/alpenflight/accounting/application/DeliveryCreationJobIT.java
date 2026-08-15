package ch.alpenflight.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterWriteRequest;
import ch.alpenflight.accounting.domain.FilterConfig;
import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.articles.domain.ArticleRepository;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
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
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DeliveryCreationJobIT extends PostgresIntegrationTest {

    private static final UUID FILTER_TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final UUID UNIT_MINUTES =
            UUID.fromString("019e2e15-2c00-7a38-8000-000000004a38");
    private static final UUID COST_BALANCE_PILOT_PAYS_ALL =
            UUID.fromString("019e2e15-2c00-7268-8000-000000004268");
    private static final int LEGACY_FLIGHT_TIME = 30;

    private static final Instant CREATED_ON_WELL_PAST_THE_THREE_DAY_ELIGIBILITY_FLOOR =
            Instant.parse("2026-01-01T00:00:00Z");

    private static final boolean WITH_PILOT = true;
    private static final boolean WITHOUT_PILOT_SO_NO_RECIPIENT_RESOLVES = false;

    @Autowired JdbcTemplate jdbc;
    @Autowired DeliveryCreationJob job;
    @Autowired AccountingRuleFiltersService filtersService;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired ArticleRepository articles;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;

    @BeforeEach
    void cleanAndSeedClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DLVJOB_", "IT_DLVJOB");
        fixture.seed();
        clubA = fixture.clubA();
    }

    @Test
    void runOnce_billsWhatItCan_andIsolatesTheFlightItCannot() {
        seedBillingSetup(clubA);
        UUID billable = seedLockedAgedFlight(clubA, "HB-JOBOK", WITH_PILOT);
        UUID unbillable =
                seedLockedAgedFlight(clubA, "HB-JOBNG", WITHOUT_PILOT_SO_NO_RECIPIENT_RESOLVES);

        DeliveryCreationJob.RunSummary summary = job.runOnce();

        assertThat(processState(billable))
                .as("the billable flight was prepared for delivery")
                .isEqualTo(FlightProcessState.DELIVERY_PREPARED.id());
        assertThat(processState(unbillable))
                .as("the flight with no recipient is flagged, not silently skipped")
                .isEqualTo(FlightProcessState.DELIVERY_PREPARATION_ERROR.id());
        assertThat(summary.createdCount())
                .as("the failing flight did not abort the batch")
                .isGreaterThanOrEqualTo(1);
        assertThat(deliveryCountFor(billable)).isEqualTo(1);
        assertThat(deliveryCountFor(unbillable)).isZero();
    }


    private void seedBillingSetup(UUID clubId) {
        TenantTestContext.runAs(clubId, () ->
                articles.save(Article.register("ART-FT", "Flight time", null, null, true)).getId());
        TenantTestContext.runAs(clubId, () -> filtersService.create(flightTimeFilter()).id());
    }

    private UUID seedLockedAgedFlight(UUID clubId, String immatriculation, boolean withPilot) {
        UUID aircraft = seedAircraft(clubId, immatriculation);
        UUID flightType = seedFlightType(clubId, immatriculation);
        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        FlightOperationalData ops = new FlightOperationalData(
                start.atZone(ZoneOffset.UTC).toLocalDate(), start,
                start.plus(90, ChronoUnit.MINUTES), null, null,
                null, null, null, null, null, null,
                flightType, null, (short) 1, (short) 0,
                false, false, null, null, null, null, null,
                COST_BALANCE_PILOT_PAYS_ALL, null, null, false);
        UUID flightId = TenantTestContext.runAs(clubId, () -> {
            Flight flight = Flight.createGlider(aircraft, FlightProcessState.VALID.id(), ops);
            flight.transition(FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB,
                    Instant.parse("2026-05-16T00:00:00Z"));
            return flights.save(flight).getId();
        });
        if (withPilot) {
            UUID pilot = seedMember(clubId, "Pilot", "Petra");
            TenantTestContext.runAs(clubId, () -> {
                Flight flight = flights.findByIdWithCrew(FlightId.of(flightId)).orElseThrow();
                flight.replaceCrew(List.of(new CrewMemberSpec(
                        pilot, FlightCrewTypeIds.PILOT_OR_STUDENT, null, null, null, null, null, null)));
                return flights.save(flight);
            });
        }
        jdbc.update("UPDATE t_flight SET created_on = ? WHERE id = ?",
                Timestamp.from(CREATED_ON_WELL_PAST_THE_THREE_DAY_ELIGIBILITY_FLOOR), flightId);
        return flightId;
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

    private UUID seedAircraft(UUID managingClubId, String immatriculation) {
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        Aircraft aircraft = Aircraft.register(managingClubId, managingClubId, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraftRepository.save(aircraft).getId().value();
    }

    private UUID seedFlightType(UUID clubId, String code) {
        FlightType flightType = FlightType.register("Schulung " + code, code,
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

    private UUID processState(UUID flightId) {
        return jdbc.queryForObject(
                "SELECT process_state_id FROM t_flight WHERE id = ?", UUID.class, flightId);
    }

    private long deliveryCountFor(UUID flightId) {
        Long v = jdbc.queryForObject(
                "SELECT count(*) FROM t_delivery WHERE flight_id = ?", Long.class, flightId);
        return v == null ? 0 : v;
    }
}
