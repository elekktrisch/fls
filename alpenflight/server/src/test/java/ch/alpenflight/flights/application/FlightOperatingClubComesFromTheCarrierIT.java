package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.flights.application.FlightDtos.FlightCreateRequest;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@RecordApplicationEvents
class FlightOperatingClubComesFromTheCarrierIT extends PostgresIntegrationTest {

    private static final UUID SEED_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final String WHY_THE_OPERATING_CLUB_COMES_FROM_THE_CARRIER =
            "FlightsService.createFlight must read the operating club from "
                    + "TenantContextCarrier.current(), never from the saved Flight. Flight.id and "
                    + "Flight.operatingClubId materialise at different moments. "
                    + "@GeneratedValue(strategy = UUID) assigns the id in memory when the entity "
                    + "becomes persistent, so the id read one line earlier is safe. "
                    + "@TenantId is a before-execution generator: Hibernate stamps "
                    + "operatingClubId when the insert runs, which is at flush. Measured against "
                    + "real Postgres: after EntityManager.persist the id is present and "
                    + "operatingClubId is null; after the flush operatingClubId holds the club. "
                    + "The carrier holds the club through the whole method, with no flush "
                    + "dependency, so it is the only correct source at this line.";

    private static final String WHY_THIS_TEST_REPLACES_THE_FLIGHT_REPORT_PROJECTOR =
            " Warning — with the shipped projector in place the entity read passes by accident. "
                    + "Flight publishes a FlightSaved domain event, and "
                    + "FlightReportProjector.onFlightSaved runs queries that auto-flush the insert "
                    + "inside repository.save(). That auto-flush stamps operatingClubId before "
                    + "createFlight reads it, so a swap to the entity read reds nothing. This test "
                    + "replaces the projector with a bean that runs no query, which removes the "
                    + "accident and leaves the rule. Measured with the swap planted at "
                    + "FlightsService.createFlight: the event carries operatingClubId=null in "
                    + "place of the club. The correctness of an entity read rests on a listener in "
                    + "another module; the carrier read rests on nothing.";

    private static final String RESIDUAL_LIMIT_OF_THIS_MEASUREMENT =
            " Residual limit — the second divergence class is unreachable and is not asserted "
                    + "here. An unscoped caller gives an empty carrier and the NO_TENANT all-zero "
                    + "sentinel on the entity, but Postgres rejects that insert on "
                    + "fk_flight_operating_club_id, so createFlight never reaches this line "
                    + "unscoped. This test pins one service method; it does not sweep every "
                    + "@TenantId read in the tree.";

    @Autowired JdbcTemplate jdbc;
    @Autowired FlightsService flightsService;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircrafts;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationEvents recordedEvents;
    @MockitoBean FlightReportProjector projectorWhoseQueriesWouldAutoFlushTheInsert;

    @Test
    void theFlightCreatedEventNamesTheOperatingClubOfTheCallingTenant() {
        UUID aircraftId = seedAircraft();

        UUID createdFlightId = TenantTestContext.runAs(SEED_CLUB,
                () -> flightsService.createFlight(gliderRequestFor(aircraftId)).id().value());

        List<FlightCreatedEvent> published =
                recordedEvents.stream(FlightCreatedEvent.class).toList();
        assertThat(published)
                .as(WHY_THE_OPERATING_CLUB_COMES_FROM_THE_CARRIER
                        + WHY_THIS_TEST_REPLACES_THE_FLIGHT_REPORT_PROJECTOR
                        + RESIDUAL_LIMIT_OF_THIS_MEASUREMENT)
                .singleElement()
                .isEqualTo(new FlightCreatedEvent(createdFlightId, SEED_CLUB, null));
    }

    @Test
    void theSavedFlightCarriesItsIdAtPersistAndItsOperatingClubOnlyAtFlush() {
        UUID aircraftId = seedAircraft();

        TenantTestContext.runAs(SEED_CLUB, () -> newTransaction().execute(status -> {
            Flight flight = Flight.createGlider(aircraftId,
                    FlightProcessState.NOT_PROCESSED.id(), emptyOperationalData());

            entityManager.persist(flight);
            assertThat(flight.getId())
                    .as(WHY_THE_OPERATING_CLUB_COMES_FROM_THE_CARRIER
                            + WHY_THIS_TEST_REPLACES_THE_FLIGHT_REPORT_PROJECTOR)
                    .isNotNull();
            assertThat(flight.getOperatingClubId())
                    .as(WHY_THE_OPERATING_CLUB_COMES_FROM_THE_CARRIER
                            + WHY_THIS_TEST_REPLACES_THE_FLIGHT_REPORT_PROJECTOR)
                    .isNull();
            assertThat(TenantTestContext.current())
                    .as(WHY_THE_OPERATING_CLUB_COMES_FROM_THE_CARRIER)
                    .contains(SEED_CLUB);

            entityManager.flush();
            assertThat(flight.getOperatingClubId())
                    .as(WHY_THE_OPERATING_CLUB_COMES_FROM_THE_CARRIER)
                    .isEqualTo(SEED_CLUB);

            status.setRollbackOnly();
            return null;
        }));
    }

    @Test
    void anUnscopedCreateNeverReachesTheOperatingClubReadBecausePostgresRejectsTheSentinel() {
        UUID aircraftId = seedAircraft();
        TenantTestContext.clear();

        assertThatThrownBy(() -> newTransaction().execute(status -> {
            flights.save(Flight.createGlider(aircraftId,
                    FlightProcessState.NOT_PROCESSED.id(), emptyOperationalData()));
            return null;
        }))
                .as(RESIDUAL_LIMIT_OF_THIS_MEASUREMENT)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_flight_operating_club_id");
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static FlightCreateRequest gliderRequestFor(UUID aircraftId) {
        return new FlightCreateRequest(FlightAircraftType.GLIDER, new AircraftId(aircraftId),
                LocalDate.of(2026, 4, 1),
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, false, false, null, null, null, null, null, null, null, null,
                false, null);
    }

    private static FlightOperationalData emptyOperationalData() {
        return new FlightOperationalData(LocalDate.of(2026, 4, 1),
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, false, false, null, null, null, null, null, null, null, null, false);
    }

    private UUID seedAircraft() {
        UUID aircraftType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1",
                UUID.class);
        String immatriculation =
                "HB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        return aircrafts.save(Aircraft.register(SEED_CLUB, SEED_CLUB, aircraftType,
                        immatriculation, null, null, null, null, null, null, null, null, null, 2,
                        null, null, null, null, null, false, false, false, false, null, null))
                .getId().value();
    }
}
