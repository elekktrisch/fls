package ch.alpenflight.tenancy.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.locations.application.LocationDtos.LocationListItem;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.persons.application.PersonDtos.PersonListItem;
import ch.alpenflight.persons.application.PersonsService;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SandboxSeederIT extends PostgresIntegrationTest {

    private static final int SEAT_UNDER_TEST = 1;
    private static final int NEIGHBOUR_SEAT = 2;

    private static final UUID SEAT_1_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-0000000de001");
    private static final UUID SEAT_2_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-0000000de002");

    private static final int AIRFIELDS_PER_SEAT = 4;
    private static final int AIRCRAFT_PER_SEAT = 4;
    private static final int MEMBERS_PER_SEAT = 6;
    private static final int MEMBER_STATES_PER_SEAT = 3;
    private static final int FLIGHT_TYPES_PER_SEAT = 4;
    private static final int AIRCRAFT_REQUIRED_BY_AC2 = 3;

    private static final List<String> SEAT_1_IMMATRICULATIONS =
            List.of("HB-3101", "HB-3201", "HB-2301", "HB-KDA");
    private static final List<String> SEAT_2_IMMATRICULATIONS =
            List.of("HB-3102", "HB-3202", "HB-2302", "HB-KDB");

    @Autowired
    private SandboxSeeder seeder;

    @Autowired
    private LocationsService locations;

    @Autowired
    private PersonsService persons;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reclaimBothSeatsBeforeEveryCase() {
        reclaim(SEAT_1_CLUB, SEAT_1_IMMATRICULATIONS);
        reclaim(SEAT_2_CLUB, SEAT_2_IMMATRICULATIONS);
    }

    private void reclaim(UUID seatClubId, List<String> immatriculations) {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE tenant_club_id = ?::uuid",
                seatClubId.toString());
        for (String immatriculation : immatriculations) {
            jdbc.update("DELETE FROM t_aircraft WHERE immatriculation = ?", immatriculation);
        }
        jdbc.update("DELETE FROM t_person WHERE id IN "
                        + "(SELECT person_id FROM t_person_club WHERE club_id = ?::uuid)",
                seatClubId.toString());
        jdbc.update("DELETE FROM t_location WHERE club_id = ?::uuid", seatClubId.toString());
    }

    @Test
    void seedsTheMasterdataTheDemoVisitorReadsOnTheirOwnSeat() {
        SandboxMasterdata seeded = seeder.seed(SEAT_1_CLUB, SEAT_UNDER_TEST);

        assertThat(seeded.clubId()).isEqualTo(SEAT_1_CLUB);
        assertThat(seeded.destinationAirfields()).hasSize(AIRFIELDS_PER_SEAT - 1);
        assertThat(seeded.fleet().all()).hasSize(AIRCRAFT_PER_SEAT);
        assertThat(seeded.roster().all()).hasSize(MEMBERS_PER_SEAT);

        assertThat(airfieldCountOf(SEAT_1_CLUB)).isEqualTo(AIRFIELDS_PER_SEAT);
        assertThat(aircraftCountManagedBy(SEAT_1_CLUB))
                .as("AC-2 asks the /aircraft screen for at least %d rows", AIRCRAFT_REQUIRED_BY_AC2)
                .isEqualTo(AIRCRAFT_PER_SEAT)
                .isGreaterThanOrEqualTo(AIRCRAFT_REQUIRED_BY_AC2);
        assertThat(memberCountOf(SEAT_1_CLUB)).isEqualTo(MEMBERS_PER_SEAT);

        assertThat(memberStateCountOf(SEAT_1_CLUB)).isEqualTo(MEMBER_STATES_PER_SEAT);
        assertThat(flightTypeCountOf(SEAT_1_CLUB)).isEqualTo(FLIGHT_TYPES_PER_SEAT);
    }

    @Test
    void seedsRealisticSwissClubMasterdataThroughTheProductionWritePath() {
        seeder.seed(SEAT_1_CLUB, SEAT_UNDER_TEST);

        assertThat(TenantTestContext.runAs(SEAT_1_CLUB, locations::listLocations))
                .extracting(LocationListItem::icaoCode)
                .containsExactlyInAnyOrder("LSZX", "LSZB", "LSGK", "LSPD");
        assertThat(TenantTestContext.runAs(SEAT_1_CLUB, locations::listLocations))
                .filteredOn(LocationListItem::isAirfield)
                .hasSize(AIRFIELDS_PER_SEAT);

        for (String immatriculation : SEAT_1_IMMATRICULATIONS) {
            assertThat(openAirworthinessPeriodCountOf(immatriculation))
                    .as("the aircraft state period of %s came from the domain write path",
                            immatriculation)
                    .isEqualTo(1);
            assertThat(homebaseOf(immatriculation))
                    .as("%s is based on the seat's home airfield", immatriculation)
                    .isNotNull();
        }

        assertThat(TenantTestContext.runAs(SEAT_1_CLUB, persons::listInCurrentTenant))
                .extracting(item -> item.firstname() + " " + item.lastname())
                .contains("Anna Meier", "Thomas Brunner", "Livia Bianchi");
        assertThat(TenantTestContext.runAs(SEAT_1_CLUB, persons::listInCurrentTenant))
                .filteredOn(PersonListItem::isGliderInstructor)
                .hasSize(1);
    }

    @Test
    void seedingTwiceOnOneSeatDoesNotDoubleAnyRow() {
        SandboxMasterdata first = seeder.seed(SEAT_1_CLUB, SEAT_UNDER_TEST);
        int airfieldsAfterFirst = airfieldCountOf(SEAT_1_CLUB);
        int aircraftAfterFirst = aircraftCountManagedBy(SEAT_1_CLUB);
        int membersAfterFirst = memberCountOf(SEAT_1_CLUB);
        int memberStatesAfterFirst = memberStateCountOf(SEAT_1_CLUB);

        SandboxMasterdata second = seeder.seed(SEAT_1_CLUB, SEAT_UNDER_TEST);

        assertThat(airfieldCountOf(SEAT_1_CLUB))
                .isEqualTo(airfieldsAfterFirst).isEqualTo(AIRFIELDS_PER_SEAT);
        assertThat(aircraftCountManagedBy(SEAT_1_CLUB))
                .isEqualTo(aircraftAfterFirst).isEqualTo(AIRCRAFT_PER_SEAT);
        assertThat(memberCountOf(SEAT_1_CLUB))
                .isEqualTo(membersAfterFirst).isEqualTo(MEMBERS_PER_SEAT);
        assertThat(memberStateCountOf(SEAT_1_CLUB))
                .isEqualTo(memberStatesAfterFirst).isEqualTo(MEMBER_STATES_PER_SEAT);
        assertThat(openAirworthinessPeriodCountOf("HB-3101"))
                .as("a re-seed opens no second airworthiness period")
                .isEqualTo(1);

        assertThat(second.homeAirfield()).isEqualTo(first.homeAirfield());
        assertThat(second.fleet()).isEqualTo(first.fleet());
        assertThat(second.roster()).isEqualTo(first.roster());
    }

    @Test
    void oneSeatNeverReadsTheMasterdataOfTheNeighbourSeat() {
        SandboxMasterdata seat1 = seeder.seed(SEAT_1_CLUB, SEAT_UNDER_TEST);
        SandboxMasterdata seat2 = seeder.seed(SEAT_2_CLUB, NEIGHBOUR_SEAT);

        List<LocationId> seat2VisibleAirfields =
                TenantTestContext.runAs(SEAT_2_CLUB, locations::listLocations).stream()
                        .map(LocationListItem::id)
                        .toList();
        assertThat(seat2VisibleAirfields)
                .contains(seat2.homeAirfield())
                .doesNotContain(seat1.homeAirfield());
        assertThat(seat2VisibleAirfields)
                .doesNotContainAnyElementsOf(seat1.destinationAirfields());

        List<PersonId> seat2VisibleMembers =
                TenantTestContext.runAs(SEAT_2_CLUB, persons::listInCurrentTenant).stream()
                        .map(PersonListItem::id)
                        .toList();
        assertThat(seat2VisibleMembers)
                .containsExactlyInAnyOrderElementsOf(seat2.roster().all())
                .doesNotContainAnyElementsOf(seat1.roster().all());
    }

    @Test
    void everySeededRowCarriesTheTenantOfItsOwnSeat() {
        SandboxMasterdata seat1 = seeder.seed(SEAT_1_CLUB, SEAT_UNDER_TEST);
        seeder.seed(SEAT_2_CLUB, NEIGHBOUR_SEAT);

        assertThat(airfieldCountOf(SEAT_1_CLUB)).isEqualTo(AIRFIELDS_PER_SEAT);
        assertThat(airfieldCountOf(SEAT_2_CLUB)).isEqualTo(AIRFIELDS_PER_SEAT);

        for (LocationId airfield : seat1.destinationAirfields()) {
            assertThat(clubIdOfAirfield(airfield)).isEqualTo(SEAT_1_CLUB.toString());
        }
        for (AircraftId aircraft : seat1.fleet().all()) {
            assertThat(managingClubIdOf(aircraft)).isEqualTo(SEAT_1_CLUB.toString());
        }
        for (PersonId member : seat1.roster().all()) {
            assertThat(clubIdsOfMembershipsOf(member))
                    .containsExactly(SEAT_1_CLUB.toString());
        }
        for (String immatriculation : SEAT_2_IMMATRICULATIONS) {
            assertThat(managingClubIdOfImmatriculation(immatriculation))
                    .isEqualTo(SEAT_2_CLUB.toString());
        }
    }

    private int airfieldCountOf(UUID clubId) {
        return count("SELECT count(*) FROM t_location WHERE club_id = ?::uuid "
                + "AND deleted_on IS NULL", clubId.toString());
    }

    private int aircraftCountManagedBy(UUID clubId) {
        return count("SELECT count(*) FROM t_aircraft WHERE managing_club_id = ?::uuid "
                + "AND deleted_on IS NULL", clubId.toString());
    }

    private int memberCountOf(UUID clubId) {
        return count("SELECT count(*) FROM t_person_club WHERE club_id = ?::uuid "
                + "AND deleted_on IS NULL", clubId.toString());
    }

    private int memberStateCountOf(UUID clubId) {
        return count("SELECT count(*) FROM t_member_state WHERE club_id = ?::uuid "
                + "AND deleted_on IS NULL", clubId.toString());
    }

    private int flightTypeCountOf(UUID clubId) {
        return count("SELECT count(*) FROM t_flight_type WHERE operating_club_id = ?::uuid "
                + "AND deleted_on IS NULL", clubId.toString());
    }

    private int openAirworthinessPeriodCountOf(String immatriculation) {
        return count("SELECT count(*) FROM t_aircraft_aircraft_state s "
                + "JOIN t_aircraft a ON a.id = s.aircraft_id "
                + "WHERE a.immatriculation = ? AND s.valid_to IS NULL", immatriculation);
    }

    private int count(String sql, String argument) {
        Integer rows = jdbc.queryForObject(sql, Integer.class, argument);
        return rows == null ? 0 : rows;
    }

    private String homebaseOf(String immatriculation) {
        return jdbc.queryForObject(
                "SELECT homebase_id::text FROM t_aircraft WHERE immatriculation = ?",
                String.class, immatriculation);
    }

    private String clubIdOfAirfield(LocationId airfield) {
        return jdbc.queryForObject("SELECT club_id::text FROM t_location WHERE id = ?::uuid",
                String.class, airfield.value().toString());
    }

    private String managingClubIdOf(AircraftId aircraft) {
        return jdbc.queryForObject(
                "SELECT managing_club_id::text FROM t_aircraft WHERE id = ?::uuid",
                String.class, aircraft.value().toString());
    }

    private String managingClubIdOfImmatriculation(String immatriculation) {
        return jdbc.queryForObject(
                "SELECT managing_club_id::text FROM t_aircraft WHERE immatriculation = ?",
                String.class, immatriculation);
    }

    private List<String> clubIdsOfMembershipsOf(PersonId member) {
        return jdbc.queryForList(
                "SELECT club_id::text FROM t_person_club "
                        + "WHERE person_id = ?::uuid AND deleted_on IS NULL",
                String.class, member.value().toString());
    }
}
