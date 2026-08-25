package ch.alpenflight.tenancy.sandbox.application;

import static ch.alpenflight.tenancy.sandbox.application.DemoSeatPoolTestFixture.returnEverySeatToThePool;
import static ch.alpenflight.tenancy.sandbox.application.DemoSeatPoolTestFixture.seatNumbered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCreateRequest;
import ch.alpenflight.aircraft.application.AircraftsService;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.flights.application.FlightsService;
import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationDtos.LocationListItem;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.AircraftTypeId;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.LocationTypeId;
import ch.alpenflight.platform.scheduling.JobRegistry;
import ch.alpenflight.platform.scheduling.JobRegistry.JobDescriptor;
import ch.alpenflight.platform.scheduling.JobRun;
import ch.alpenflight.referencedata.application.ReferenceDataService;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.tenancy.sandbox.SandboxMasterdata;
import ch.alpenflight.tenancy.sandbox.SandboxSeeder;
import ch.alpenflight.tenancy.sandbox.application.SandboxClubPurge.ClubOutsideTheSandboxDeploymentException;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeat.LeaseState;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "demo.pool-size=1")
class SandboxResetJobIT extends PostgresIntegrationTest {

    private static final int SEAT_UNDER_TEST = 1;

    private static final UUID SEAT_1_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-0000000de001");

    private static final UUID CLUB_IN_THE_OPERATOR_DEPLOYMENT =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final String VISITOR_ADDRESS = "203.0.113.77";

    private static final String AIRFIELD_THE_VISITOR_ADDED = "Besucher-Flugplatz";

    private static final String AIRFIELD_THE_OPERATOR_CLUB_OWNS = "Operator-Flugplatz";

    private static final String AIRFIELD_ICAO_THE_OPERATOR_CLUB_OWNS = "LSZO";

    private static final String IMMATRICULATION_THE_OPERATOR_CLUB_OWNS = "HB-9911";

    private static final String MODEL_OF_THE_AIRCRAFT_OUTSIDE_THE_SANDBOX =
            "Ausserhalb der Sandbox";

    private static final List<String> SEEDED_ICAO_CODES = List.of("LSZX", "LSZB", "LSGK", "LSPD");

    private static final int FLIGHTS_PER_SEAT = 24;

    private static final int AIRFIELDS_PER_SEAT = 4;

    @Autowired
    private JobRegistry jobs;

    @Autowired
    private SandboxSeeder seeder;

    @Autowired
    private SandboxClubPurge purge;

    @Autowired
    private DemoSeatRepository seats;

    @Autowired
    private LocationsService locations;

    @Autowired
    private FlightsService flights;

    @Autowired
    private AircraftsService aircraft;

    @Autowired
    private ReferenceDataService referenceData;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @BeforeEach
    void everySeatStartsFree() {
        returnEverySeatToThePool(seats, transactionManager, clock);
        deleteTheRowsThisTestClassOwnsInTheOperatorDeployment();
    }

    @AfterEach
    void everySeatGoesBackSoTheNextTestClassReadsThePoolAsFlywayCreatedIt() {
        returnEverySeatToThePool(seats, transactionManager, clock);
        deleteTheRowsThisTestClassOwnsInTheOperatorDeployment();
    }

    private void deleteTheRowsThisTestClassOwnsInTheOperatorDeployment() {
        jdbc.update("DELETE FROM t_aircraft WHERE immatriculation = ?",
                IMMATRICULATION_THE_OPERATOR_CLUB_OWNS);
        jdbc.update("DELETE FROM t_location WHERE icao_code = ? AND club_id = ?::uuid",
                AIRFIELD_ICAO_THE_OPERATOR_CLUB_OWNS,
                CLUB_IN_THE_OPERATOR_DEPLOYMENT.toString());
    }

    @Test
    void run_now_reclaims_the_expired_seat_and_the_seeded_value_replaces_the_visitor_change() {
        SandboxMasterdata seeded = seeder.seed(SEAT_1_CLUB, SEAT_UNDER_TEST);
        FlightId flightTheVisitorRemoves =
                seeded.operations().flightsOverTheLastThirtyDays().get(0);
        TenantTestContext.runAs(SEAT_1_CLUB, () -> {
            flights.softDeleteFlight(flightTheVisitorRemoves, null);
            locations.createLocation(airfieldRequest(AIRFIELD_THE_VISITOR_ADDED, "LSZV"));
        });
        assertThat(liveFlightCountOf(SEAT_1_CLUB)).isEqualTo(FLIGHTS_PER_SEAT - 1);
        assertThat(airfieldNamesOf(SEAT_1_CLUB)).contains(AIRFIELD_THE_VISITOR_ADDED);
        leaseSeatOneWithAnExpiryThatHasPassed();

        JobRun run = jobs.runOnce(SandboxResetJob.JOB_NAME);

        assertThat(run.getStatus()).isEqualTo(JobRun.Status.COMPLETED);
        assertThat(rowCountOfFlight(flightTheVisitorRemoves))
                .as("the reclaim hard-deletes the row the visitor soft-deleted")
                .isZero();
        assertThat(liveFlightCountOf(SEAT_1_CLUB))
                .as("the seeded flight history is back")
                .isEqualTo(FLIGHTS_PER_SEAT);
        assertThat(airfieldNamesOf(SEAT_1_CLUB))
                .doesNotContain(AIRFIELD_THE_VISITOR_ADDED);
        assertThat(airfieldIcaoCodesOf(SEAT_1_CLUB))
                .containsExactlyInAnyOrderElementsOf(SEEDED_ICAO_CODES)
                .hasSize(AIRFIELDS_PER_SEAT);
        assertThat(seatNumbered(seats, SEAT_UNDER_TEST).getLeaseState())
                .isEqualTo(LeaseState.FREE);
    }

    @Test
    void the_reset_deletes_no_row_outside_the_sandbox_deployment() {
        seeder.seed(SEAT_1_CLUB, SEAT_UNDER_TEST);
        LocationId airfieldOfTheOperatorClub = TenantTestContext.runAs(
                CLUB_IN_THE_OPERATOR_DEPLOYMENT,
                () -> locations.createLocation(airfieldRequest(
                        AIRFIELD_THE_OPERATOR_CLUB_OWNS,
                        AIRFIELD_ICAO_THE_OPERATOR_CLUB_OWNS)).id());
        AircraftId aircraftOfTheOperatorClub = TenantTestContext.runAs(
                CLUB_IN_THE_OPERATOR_DEPLOYMENT,
                () -> aircraft.registerAircraft(
                        aircraftRequest(airfieldOfTheOperatorClub)).id());
        Map<String, Integer> rowsOutsideTheSandboxBeforeTheReset = rowCountsOutsideTheSandbox();
        assertThat(rowsOutsideTheSandboxBeforeTheReset.get("t_location")).isPositive();
        assertThat(rowsOutsideTheSandboxBeforeTheReset.get("t_aircraft")).isPositive();
        assertThat(rowsOutsideTheSandboxBeforeTheReset.get("t_person")).isPositive();
        leaseSeatOneWithAnExpiryThatHasPassed();

        jobs.runOnce(SandboxResetJob.JOB_NAME);

        assertThat(rowCountOfLocation(airfieldOfTheOperatorClub))
                .as("the airfield of the operator-deployment club survives the reset")
                .isEqualTo(1);
        assertThat(rowCountOfAircraft(aircraftOfTheOperatorClub))
                .as("the cross-tenant aircraft of the operator-deployment club survives the reset")
                .isEqualTo(1);
        assertThat(rowCountsOutsideTheSandbox())
                .isEqualTo(rowsOutsideTheSandboxBeforeTheReset);
        assertThat(liveFlightCountOf(SEAT_1_CLUB)).isEqualTo(FLIGHTS_PER_SEAT);
    }

    @Test
    void the_purge_refuses_a_club_that_is_not_bound_to_the_sandbox_deployment() {
        assertThrows(ClubOutsideTheSandboxDeploymentException.class,
                () -> purge.deleteEveryRowOf(CLUB_IN_THE_OPERATOR_DEPLOYMENT));
    }

    @Test
    void the_jobs_console_lists_the_sandbox_reset_job_so_run_now_can_drive_it() {
        assertThat(jobs.list())
                .extracting(JobDescriptor::name)
                .contains(SandboxResetJob.JOB_NAME);
    }

    private void leaseSeatOneWithAnExpiryThatHasPassed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            DemoSeat seat = seatNumbered(seats, SEAT_UNDER_TEST);
            seat.leaseTo(VISITOR_ADDRESS,
                    Instant.now(clock).minus(2, ChronoUnit.HOURS),
                    Duration.ofHours(1));
            seats.save(seat);
        });
    }

    private LocationCreateRequest airfieldRequest(String name, String icaoCode) {
        CountryId switzerland = referenceData.listCountries().stream()
                .filter(country -> "CH".equals(country.iso2Code()))
                .findFirst().orElseThrow().id();
        LocationTypeId gliderAirfield = referenceData.listLocationTypes().stream()
                .filter(locationType -> "GLIDER_AIRFIELD".equals(locationType.code()))
                .findFirst().orElseThrow().id();
        return new LocationCreateRequest(
                name, name.substring(0, 4).toUpperCase(Locale.ROOT),
                switzerland, gliderAirfield, icaoCode, "47.0000", "8.0000",
                null, null, null, null, null, null, null, null,
                false, false, false, null);
    }

    private AircraftCreateRequest aircraftRequest(LocationId homebase) {
        AircraftTypeId glider = referenceData.listAircraftTypes().stream()
                .filter(aircraftType -> "GLIDER".equals(aircraftType.code()))
                .findFirst().orElseThrow().id();
        return new AircraftCreateRequest(
                glider, IMMATRICULATION_THE_OPERATOR_CLUB_OWNS, "Schleicher",
                MODEL_OF_THE_AIRCRAFT_OUTSIDE_THE_SANDBOX, null,
                null, null, null, null, null, null, 1,
                null, null, homebase, null,
                true, true, true, false, null, null);
    }

    private Map<String, Integer> rowCountsOutsideTheSandbox() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> tenantColumn : clubScopedTableColumns()) {
            String table = String.valueOf(tenantColumn.get("table_name"));
            String column = String.valueOf(tenantColumn.get("column_name"));
            counts.put(table + "." + column, count(
                    "SELECT count(*) FROM " + table + " WHERE " + column + " IN "
                            + "(SELECT id FROM t_club WHERE deployment_id <> ?::uuid)",
                    Deployment.SANDBOX_ID.toString()));
        }
        counts.put("t_location", counts.get("t_location.club_id"));
        counts.put("t_aircraft", counts.get("t_aircraft.managing_club_id"));
        counts.put("t_person", count(
                "SELECT count(*) FROM t_person p WHERE EXISTS ("
                        + "SELECT 1 FROM t_person_club pc JOIN t_club c ON c.id = pc.club_id "
                        + "WHERE pc.person_id = p.id AND c.deployment_id <> ?::uuid)",
                Deployment.SANDBOX_ID.toString()));
        return counts;
    }

    private List<Map<String, Object>> clubScopedTableColumns() {
        return jdbc.queryForList("""
                SELECT tc.table_name, kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY' AND ccu.table_name = 't_club'
                ORDER BY tc.table_name, kcu.column_name
                """);
    }

    private List<String> airfieldNamesOf(UUID clubId) {
        return TenantTestContext.runAs(clubId, locations::listLocations).stream()
                .map(LocationListItem::locationName)
                .toList();
    }

    private List<String> airfieldIcaoCodesOf(UUID clubId) {
        return TenantTestContext.runAs(clubId, locations::listLocations).stream()
                .map(LocationListItem::icaoCode)
                .toList();
    }

    private int liveFlightCountOf(UUID clubId) {
        return count("SELECT count(*) FROM t_flight WHERE operating_club_id = ?::uuid "
                + "AND deleted_on IS NULL", clubId.toString());
    }

    private int rowCountOfFlight(FlightId flightId) {
        return count("SELECT count(*) FROM t_flight WHERE id = ?::uuid",
                flightId.value().toString());
    }

    private int rowCountOfLocation(LocationId locationId) {
        return count("SELECT count(*) FROM t_location WHERE id = ?::uuid",
                locationId.value().toString());
    }

    private int rowCountOfAircraft(AircraftId aircraftId) {
        return count("SELECT count(*) FROM t_aircraft WHERE id = ?::uuid",
                aircraftId.value().toString());
    }

    private int count(String sql, String argument) {
        Integer rows = jdbc.queryForObject(sql, Integer.class, argument);
        return rows == null ? 0 : rows;
    }
}
