package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.mail.CapturedMailSender;
import ch.alpenflight.platform.mail.MailMessage;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration proof of the daily flight-report mail (S-084, J-15 AC #4) against
 * the captured outbox.
 *
 * <p>The opt-out seed is what makes the assertion mean something: two people fly
 * the same flight and only the one whose club membership carries
 * {@code receiveFlightReports} is mailed.
 */
@Import(CapturedMailSender.Config.class)
class DailyReportJobIT extends PostgresIntegrationTest {

    private static final UUID WINCH_LAUNCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private static final String OPTED_IN_MAIL = "reports.yes@example.com";
    private static final String OPTED_OUT_MAIL = "reports.no@example.com";

    @Autowired JdbcTemplate jdbc;
    @Autowired DailyReportJob job;
    @Autowired CapturedMailSender outbox;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired LocationRepository locations;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;

    @BeforeEach
    void cleanAndSeedClubs() {
        outbox.clear();
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DRJ_", "IT_DRJ");
        fixture.seed();
        clubA = fixture.clubA();
    }

    @Test
    void runOnce_mailsOptedInCrewOnly_andStampsTheFlight() {
        UUID pilot = seedMember(clubA, "Optin", OPTED_IN_MAIL, true);
        UUID instructor = seedMember(clubA, "Optout", OPTED_OUT_MAIL, false);
        UUID flightId = seedFlight(clubA, List.of(
                crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT),
                crew(instructor, FlightCrewTypeIds.FLIGHT_INSTRUCTOR)));

        DailyReportJob.RunSummary summary = job.runOnce();

        List<String> recipients = outbox.sent().stream()
                .flatMap(m -> m.to().stream()).toList();
        assertThat(recipients)
                .as("only the opted-in crew member is mailed")
                .contains(OPTED_IN_MAIL)
                .doesNotContain(OPTED_OUT_MAIL);
        assertThat(summary.mailCount()).isGreaterThanOrEqualTo(1);
        assertThat(flightOf(flightId).getFlightReportSentOn())
                .as("a reported flight is stamped so the next run skips it")
                .isNotNull();
    }

    @Test
    void secondRunDoesNotReportTheSameFlightAgain() {
        UUID pilot = seedMember(clubA, "Repeat", OPTED_IN_MAIL, true);
        seedFlight(clubA, List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));

        job.runOnce();
        long afterFirst = outbox.sent().stream()
                .filter(m -> m.to().contains(OPTED_IN_MAIL)).count();
        job.runOnce();
        long afterSecond = outbox.sent().stream()
                .filter(m -> m.to().contains(OPTED_IN_MAIL)).count();

        assertThat(afterFirst).isEqualTo(1);
        assertThat(afterSecond).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private Flight flightOf(UUID flightId) {
        return TenantTestContext.runAs(clubA, () -> flights.findByIdWithCrew(FlightId.of(flightId))
                .orElseThrow(() -> new AssertionError("no flight " + flightId)));
    }

    private static CrewMemberSpec crew(UUID personId, UUID crewTypeId) {
        return new CrewMemberSpec(personId, crewTypeId, null, null, null, null, null, null);
    }

    private UUID seedFlight(UUID clubId, List<CrewMemberSpec> crew) {
        UUID aircraft = seedAircraft(clubId);
        UUID location = seedLocation(clubId);
        UUID flightType = seedFlightType(clubId);
        Instant start = TODAY.atTime(9, 0).toInstant(ZoneOffset.UTC);
        FlightOperationalData ops = new FlightOperationalData(
                TODAY, start, start.plusSeconds(3600), null, null,
                location, location, null, null, null, null,
                flightType, WINCH_LAUNCH, (short) 1, (short) 0,
                false, false, null, null, null, null, null, null, null, null,
                false);
        return TenantTestContext.runAs(clubId, () -> {
            Flight flight = Flight.createGlider(aircraft, FlightProcessState.VALID.id(), ops);
            flight.replaceCrew(crew);
            return flights.save(flight).getId();
        });
    }

    private UUID seedMember(UUID clubId, String lastname, String email, boolean receiveReports) {
        String unique = "M-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Person person = Person.register("Test", lastname, null);
        person.updateContact(null, null, null, null, null, null, null, null, null, null,
                email, null, false, null, null, false);
        return TenantTestContext.runAs(clubId, () -> {
            person.joinClub(clubId, unique, null, PersonRoleFlags.none(),
                    new PersonNotificationPrefs(receiveReports, false, false), true);
            return persons.save(person).getId().value();
        });
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        String immatriculation = "HB-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        Aircraft aircraft = Aircraft.register(managingClubId, managingClubId, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraftRepository.save(aircraft).getId().value();
    }

    private UUID seedLocation(UUID clubId) {
        UUID locType = jdbc.queryForObject("SELECT id FROM t_location_type LIMIT 1", UUID.class);
        UUID country = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        Location location = Location.create("Base", null, country, locType, null,
                null, null, null, null, null, null, null, null, null, null,
                false, false, false);
        return TenantTestContext.runAs(clubId, () -> locations.save(location).getId().value());
    }

    private UUID seedFlightType(UUID clubId) {
        String code = "R" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
        FlightType flightType = FlightType.register("Report " + code, code,
                false, false, false, false, false, true, true, true,
                false, false, false, null);
        return TenantTestContext.runAs(clubId, () -> flightTypes.save(flightType).getId().value());
    }
}
