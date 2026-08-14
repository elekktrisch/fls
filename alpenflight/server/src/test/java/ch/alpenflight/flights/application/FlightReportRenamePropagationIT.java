package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightReportRow;
import ch.alpenflight.flights.domain.FlightReportRowRepository;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FlightReportRenamePropagationIT extends PostgresIntegrationTest {

    private static final UUID WINCH_LAUNCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");
    private static final UUID AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");

    @Autowired JdbcTemplate jdbc;
    @Autowired FlightRepository flights;
    @Autowired FlightReportRowRepository rows;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired LocationRepository locations;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void cleanAndSeedClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_FRN_", "IT_FRN");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void aircraftRename_updatesOwnRow_andTowBlockOnTheGlidersRow() {
        UUID gliderAc = seedAircraft(clubA);
        UUID towAc = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Base");
        UUID flightType = seedFlightType(clubA, "T", "T");
        UUID towId = seedTow(clubA, towAc, loc, flightType, List.of());
        UUID gliderId = seedGlider(clubA, gliderAc, loc, flightType, AEROTOW, List.of());
        linkTow(clubA, gliderId, towId);

        TenantTestContext.runAs(clubA, () -> {
            Aircraft tow = loadAircraft(towAc);
            tow.rename("HB-NEW1");
            aircraftRepository.save(tow);
            Aircraft glider = loadAircraft(gliderAc);
            glider.rename("HB-NEW2");
            aircraftRepository.save(glider);
        });

        assertThat(rowOf(gliderId).getTowImmatriculation()).isEqualTo("HB-NEW1");
        assertThat(rowOf(towId).getImmatriculation()).isEqualTo("HB-NEW1");
        assertThat(rowOf(gliderId).getImmatriculation()).isEqualTo("HB-NEW2");
    }

    @Test
    void personRename_updatesPilotAndTowPilotNames() {
        UUID gliderAc = seedAircraft(clubA);
        UUID towAc = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Base");
        UUID flightType = seedFlightType(clubA, "T", "T");
        UUID pilot = seedPerson("Alt", "Anna");
        UUID towPilot = seedPerson("Schlepp", "Sepp");
        UUID towId = seedTow(clubA, towAc, loc, flightType,
                List.of(crew(towPilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));
        UUID gliderId = seedGlider(clubA, gliderAc, loc, flightType, AEROTOW,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));
        linkTow(clubA, gliderId, towId);
        assertThat(rowOf(gliderId).getPilotName()).isEqualTo("Alt Anna");
        assertThat(rowOf(gliderId).getTowPilotName()).isEqualTo("Schlepp Sepp");

        TenantTestContext.runAs(clubA, () -> {
            Person p = persons.findActiveById(pilot).orElseThrow();
            p.rename("Anna", "Neu", null, null);
            persons.save(p);
            Person tp = persons.findActiveById(towPilot).orElseThrow();
            tp.rename("Sepp", "Zieher", null, null);
            persons.save(tp);
        });

        assertThat(rowOf(gliderId).getPilotName()).isEqualTo("Neu Anna");
        assertThat(rowOf(towId).getPilotName()).isEqualTo("Zieher Sepp");
        assertThat(rowOf(gliderId).getTowPilotName()).isEqualTo("Zieher Sepp");
    }

    @Test
    void locationRename_updatesStartAndLdgNames() {
        UUID aircraft = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Altfeld");
        UUID flightType = seedFlightType(clubA, "T", "T");
        UUID flightId = seedGlider(clubA, aircraft, loc, flightType, WINCH_LAUNCH, List.of());
        assertThat(rowOf(flightId).getStartLocationName()).isEqualTo("Altfeld");

        TenantTestContext.runAs(clubA, () -> {
            Location location = locations.findActiveById(loc).orElseThrow();
            location.rename("Neufeld", null);
            locations.save(location);
        });

        FlightReportRow row = rowOf(flightId);
        assertThat(row.getStartLocationName()).isEqualTo("Neufeld");
        assertThat(row.getLdgLocationName()).isEqualTo("Neufeld");
        assertThat(row.getStartLocationId()).isEqualTo(loc);
    }

    @Test
    void flightTypeRename_updatesTypeNameAndCode() {
        UUID aircraft = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Base");
        UUID flightType = seedFlightType(clubA, "Schulung", "SCH");
        UUID flightId = seedGlider(clubA, aircraft, loc, flightType, WINCH_LAUNCH, List.of());
        assertThat(rowOf(flightId).getFlightTypeName()).isEqualTo("Schulung");
        assertThat(rowOf(flightId).getFlightCode()).isEqualTo("SCH");

        TenantTestContext.runAs(clubA, () -> {
            FlightType ft = flightTypes.findActiveById(flightType).orElseThrow();
            ft.rename("Weiterbildung");
            ft.changeFlightCode("WB");
            flightTypes.save(ft);
        });

        assertThat(rowOf(flightId).getFlightTypeName()).isEqualTo("Weiterbildung");
        assertThat(rowOf(flightId).getFlightCode()).isEqualTo("WB");
    }

    @Test
    void rename_doesNotDisturbOtherTenantsRows() {
        UUID pilot = seedPerson("Geteilt", "Greta");
        UUID acA = seedAircraft(clubA);
        UUID locA = seedLocation(clubA, "BaseA");
        UUID typeA = seedFlightType(clubA, "TA", "TA");
        UUID flightA = seedGlider(clubA, acA, locA, typeA, WINCH_LAUNCH,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));
        UUID acB = seedAircraft(clubB);
        UUID locB = seedLocation(clubB, "BaseB");
        UUID typeB = seedFlightType(clubB, "TB", "TB");
        UUID flightB = seedGlider(clubB, acB, locB, typeB, WINCH_LAUNCH,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));

        TenantTestContext.runAs(clubA, () -> {
            Person p = persons.findActiveById(pilot).orElseThrow();
            p.rename("Greta", "Umbenannt", null, null);
            persons.save(p);
        });

        assertThat(rowOf(flightA).getPilotName()).isEqualTo("Umbenannt Greta");
        FlightReportRow rowB = TenantTestContext.runAs(clubB,
                () -> rows.findByFlightId(flightB).orElseThrow());
        assertThat(rowB.getPilotName()).isEqualTo("Geteilt Greta");
    }


    private FlightReportRow rowOf(UUID flightId) {
        return TenantTestContext.runAs(clubA, () -> rows.findByFlightId(flightId)
                .orElseThrow(() -> new AssertionError("no report row for flight " + flightId)));
    }

    private Aircraft loadAircraft(UUID aircraftId) {
        return aircraftRepository.findActiveById(aircraftId).orElseThrow();
    }

    private void linkTow(UUID clubId, UUID gliderId, UUID towId) {
        TenantTestContext.runAs(clubId, () -> {
            Flight glider = flights.findByIdWithCrew(FlightId.of(gliderId)).orElseThrow();
            Flight tow = flights.findByIdWithCrew(FlightId.of(towId)).orElseThrow();
            glider.linkTow(tow);
            flights.save(glider);
        });
    }

    private static CrewMemberSpec crew(UUID personId, UUID crewTypeId) {
        return new CrewMemberSpec(personId, crewTypeId, null, null, null, null, null, null);
    }

    private UUID seedGlider(UUID clubId, UUID aircraftId, UUID loc, UUID flightType,
                            UUID startTypeId, List<CrewMemberSpec> crewSpecs) {
        return seedFlight(clubId, FlightAircraftType.GLIDER, aircraftId, loc, flightType,
                startTypeId, crewSpecs);
    }

    private UUID seedTow(UUID clubId, UUID aircraftId, UUID loc, UUID flightType,
                         List<CrewMemberSpec> crewSpecs) {
        return seedFlight(clubId, FlightAircraftType.TOW, aircraftId, loc, flightType,
                AEROTOW, crewSpecs);
    }

    private UUID seedFlight(UUID clubId, FlightAircraftType type, UUID aircraftId,
                            UUID loc, UUID flightType, UUID startTypeId,
                            List<CrewMemberSpec> crewSpecs) {
        FlightOperationalData ops = new FlightOperationalData(
                LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T11:00:00Z"),
                null, null,
                loc, loc, null, null, null, null,
                flightType, startTypeId, (short) 1, (short) 0,
                false, false, null, null, null, null, null, null, null, null,
                false);
        return TenantTestContext.runAs(clubId, () -> {
            Flight flight = switch (type) {
                case GLIDER -> Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops);
                case TOW -> Flight.createTow(aircraftId, FlightProcessState.VALID.id(), ops);
                case MOTOR -> Flight.createMotor(aircraftId, FlightProcessState.VALID.id(), ops);
            };
            flight.replaceCrew(crewSpecs);
            return flights.save(flight).getId();
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

    private UUID seedLocation(UUID clubId, String name) {
        UUID locType = jdbc.queryForObject("SELECT id FROM t_location_type LIMIT 1", UUID.class);
        UUID country = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        Location location = Location.create(name, null, country, locType, null,
                null, null, null, null, null, null, null, null, null, null,
                false, false, false);
        return TenantTestContext.runAs(clubId,
                () -> locations.save(location).getId().value());
    }

    private UUID seedFlightType(UUID clubId, String name, String code) {
        FlightType flightType = FlightType.register(name, code,
                false, false, false, false, false,
                true, true, true,
                false, false, false, null);
        return TenantTestContext.runAs(clubId,
                () -> flightTypes.save(flightType).getId().value());
    }

    private UUID seedPerson(String lastname, String firstname) {
        return persons.save(Person.register(firstname, lastname, null)).getId().value();
    }
}
