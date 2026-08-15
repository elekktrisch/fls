package ch.alpenflight.accounting.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.DeliveryCreationTest;
import ch.alpenflight.accounting.domain.DeliveryCreationTestRepository;
import ch.alpenflight.accounting.domain.DeliveryDetailsSnapshot;
import ch.alpenflight.accounting.domain.DeliveryItemDetails;
import ch.alpenflight.accounting.domain.IgnoreFlags;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DeliveryCreationTestRepositoryIT extends PostgresIntegrationTest {

    @Autowired private DeliveryCreationTestRepository tests;
    @Autowired private FlightRepository flights;
    @Autowired private AircraftRepository aircraft;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private final Clock clock = Clock.systemUTC();

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DCT_", "IT_DCT");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void create_roundTripsEveryFieldIncludingJsonbItemsAndIgnoreFlags() {
        UUID flight = seedGliderFlight(clubA);
        UUID matched = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

        DeliveryItemDetails line = new DeliveryItemDetails(
                1, "ART-FT", "first 30min", "tier A", new BigDecimal("0.5000"), 10, "Min");
        DeliveryDetailsSnapshot expected = new DeliveryDetailsSnapshot(
                List.of(line),
                new RuleBasedDeliveryDetails.Recipient(null, "REC-1", "Petra Pilot", "Petra", "Pilot"),
                "delivery info",
                "additional info");
        IgnoreFlags flags = new IgnoreFlags(true, false, true, false, true, false, true, false, true);

        UUID id = TenantTestContext.runAs(clubA, () -> {
            DeliveryCreationTest test = DeliveryCreationTest.create(
                    clubA, flight, "Tiered FlightTime", "the daily tuning tool", true, false, flags);
            test.captureExpected(expected, List.of(matched));
            DeliveryCreationTest saved = tests.save(test);
            tests.flush();
            UUID savedId = saved.getId();
            assertThat(savedId).isNotNull();
            return savedId;
        });

        TenantTestContext.runAs(clubA, () -> {
            DeliveryCreationTest reloaded = tests.findActiveById(id).orElseThrow();
            assertThat(reloaded.getFlightId()).isEqualTo(flight);
            assertThat(reloaded.getTestName()).isEqualTo("Tiered FlightTime");
            assertThat(reloaded.getDescription()).isEqualTo("the daily tuning tool");
            assertThat(reloaded.isActive()).isTrue();
            assertThat(reloaded.isMustNotCreateDeliveryForFlight()).isFalse();
            assertThat(reloaded.getOperatingClubId()).isEqualTo(clubA);

            DeliveryDetailsSnapshot snapshot = reloaded.getExpectedDelivery();
            assertThat(snapshot.deliveryInformation()).isEqualTo("delivery info");
            assertThat(snapshot.additionalInformation()).isEqualTo("additional info");
            assertThat(snapshot.recipient())
                    .isEqualTo(new RuleBasedDeliveryDetails.Recipient(
                            null, "REC-1", "Petra Pilot", "Petra", "Pilot"));
            assertThat(snapshot.items())
                    .as("the jsonb graph is compared field by field, not by whole-record equals: "
                            + "the Jackson FormatMapper can shift a BigDecimal's scale")
                    .singleElement()
                    .satisfies(jsonItem -> {
                        assertThat(jsonItem.position()).isEqualTo(1);
                        assertThat(jsonItem.articleNumber()).isEqualTo("ART-FT");
                        assertThat(jsonItem.itemText()).isEqualTo("first 30min");
                        assertThat(jsonItem.additionalInformation()).isEqualTo("tier A");
                        assertThat(jsonItem.quantity()).isEqualByComparingTo("0.5000");
                        assertThat(jsonItem.discountInPercent()).isEqualTo(10);
                        assertThat(jsonItem.unitType()).isEqualTo("Min");
                    });
            assertThat(reloaded.getExpectedMatchedFilterIds()).containsExactly(matched);

            assertThat(reloaded.getIgnoreFlags()).isEqualTo(flags);

            assertThat(reloaded.getItems())
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.getOperatingClubId()).isEqualTo(clubA);
                        assertThat(item.getArticleNumber()).isEqualTo("ART-FT");
                        assertThat(item.getItemText()).isEqualTo("first 30min");
                        assertThat(item.getAdditionalInformation()).isEqualTo("tier A");
                        assertThat(item.getQuantity()).isEqualByComparingTo("0.5000");
                        assertThat(item.getDiscountInPercent()).isEqualTo(10);
                        assertThat(item.getUnitTypeCode()).isEqualTo("Min");
                    });
            return null;
        });
    }

    @Test
    void findActiveById_isTenantScoped() {
        UUID flightB = seedGliderFlight(clubB);
        UUID bRow = TenantTestContext.runAs(clubB, () -> {
            DeliveryCreationTest test = DeliveryCreationTest.create(
                    clubB, flightB, "B private", null, true, false, IgnoreFlags.none());
            return tests.save(test).getId();
        });

        TenantTestContext.runAs(clubA, () -> {
            assertThat(tests.findActiveById(bRow))
                    .as("club A cannot load club B's harness by id (cross-tenant 404 foundation)")
                    .isEmpty();
            return null;
        });
        TenantTestContext.runAs(clubB, () -> {
            assertThat(tests.findActiveById(bRow))
                    .as("club B loads its own harness")
                    .isPresent();
            return null;
        });
    }

    @Test
    void softDeletedRowsAreExcludedFromListAndById() {
        UUID flight1 = seedGliderFlight(clubA);
        UUID flight2 = seedGliderFlight(clubA);
        UUID alive = create(clubA, flight1, "Alive");
        UUID doomed = create(clubA, flight2, "Doomed");

        TenantTestContext.runAs(clubA, () -> {
            assertThat(tests.findAllActiveOrderedByName())
                    .extracting(DeliveryCreationTest::getId)
                    .containsExactly(alive, doomed);

            DeliveryCreationTest row = tests.findActiveById(doomed).orElseThrow();
            row.softDelete(null, clock);
            tests.save(row);
            tests.flush();

            assertThat(tests.findAllActiveOrderedByName())
                    .as("soft-deleted row excluded from the active list")
                    .extracting(DeliveryCreationTest::getId)
                    .containsExactly(alive);
            assertThat(tests.findActiveById(doomed))
                    .as("soft-deleted row not loadable")
                    .isEmpty();
            return null;
        });
    }

    private UUID create(UUID clubId, UUID flightId, String name) {
        return TenantTestContext.runAs(clubId, () -> {
            DeliveryCreationTest test = DeliveryCreationTest.create(
                    clubId, flightId, name, null, true, false, IgnoreFlags.none());
            return tests.save(test).getId();
        });
    }

    private UUID seedGliderFlight(UUID clubId) {
        UUID aircraftId = seedAircraft(clubId);
        FlightOperationalData ops = new FlightOperationalData(
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, (short) 0, (short) 0,
                false, false, null, null, null, null, null, null, null, null, false);
        return TenantTestContext.runAs(clubId, () ->
                flights.save(Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops)).getId());
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        String immatriculation = "HB-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        Aircraft a = Aircraft.register(managingClubId, managingClubId, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraft.save(a).getId().value();
    }
}
