package ch.alpenflight.aircraft.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCreateRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftDetail;
import ch.alpenflight.aircraft.application.AircraftsService;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftListItem;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftPickerItem;
import ch.alpenflight.aircraft.domain.AircraftNotFoundException;
import ch.alpenflight.aircraft.domain.DuplicateImmatriculationException;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.platform.id.AircraftTypeId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AircraftsTenantIsolationIT extends PostgresIntegrationTest {

    private static final String TEST_NAME_PREFIX = "IT_ATI_";
    private static final String TEST_KEY_PREFIX = "IT_AC";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AircraftsService aircrafts;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private TwoClubFixture fixture;
    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seedTwoClubs() {
        fixture = new TwoClubFixture(
                jdbc, clubs, countries, clubStates, TEST_NAME_PREFIX, TEST_KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @AfterEach
    void deleteTheSandboxDeploymentClubsThisTestSeeded() {
        fixture.deleteEveryAdditionalDeploymentClubThisFixtureSeeded();
    }

    @Test
    void list_returns_aircraft_from_every_club_inside_the_same_deployment() {
        TenantTestContext.runAs(clubA, () -> {
            AircraftDetail aRow = aircrafts.registerAircraft(payload(uniqueImmat()));
            AircraftDetail bRow = TenantTestContext.runAs(clubB,
                    () -> aircrafts.registerAircraft(payload(uniqueImmat())));

            assertThat(aircrafts.listAircraft(null))
                    .as("aircraft stay cross-tenant between clubs of one deployment (ADR 0008)")
                    .extracting(li -> li.id().toString())
                    .contains(aRow.id().toString(), bRow.id().toString());
        });
    }

    @Test
    void a_real_club_never_reads_an_aircraft_of_a_sandbox_deployment_club() {
        UUID sandboxSeatClub =
                fixture.seedAdditionalClubInDeployment(Deployment.SANDBOX_ID, "sandboxseat");
        AircraftDetail sandboxRow = TenantTestContext.runAs(sandboxSeatClub,
                () -> aircrafts.registerAircraft(payload(uniqueImmat())));

        TenantTestContext.runAs(clubA, () -> {
            AircraftDetail ownRow = aircrafts.registerAircraft(payload(uniqueImmat()));

            assertThat(aircrafts.listAircraft(null))
                    .as("the sandbox aircraft must be absent from a real club's list")
                    .extracting(li -> li.id().toString())
                    .contains(ownRow.id().toString())
                    .doesNotContain(sandboxRow.id().toString());
            assertThat(aircrafts.listAircraft(null))
                    .extracting(AircraftListItem::immatriculation)
                    .doesNotContain(sandboxRow.immatriculation());
            assertThat(aircrafts.listAircraftForPicker())
                    .extracting(AircraftPickerItem::immatriculation)
                    .doesNotContain(sandboxRow.immatriculation());
            assertThatThrownBy(() -> aircrafts.getAircraft(sandboxRow.id()))
                    .isInstanceOf(AircraftNotFoundException.class);
        });
    }

    @Test
    void a_sandbox_deployment_club_never_reads_the_fleet_of_a_real_club() {
        UUID sandboxSeatClub =
                fixture.seedAdditionalClubInDeployment(Deployment.SANDBOX_ID, "sandboxseat");
        AircraftDetail realRow = TenantTestContext.runAs(clubA,
                () -> aircrafts.registerAircraft(payload(uniqueImmat())));

        TenantTestContext.runAs(sandboxSeatClub, () -> {
            AircraftDetail ownRow = aircrafts.registerAircraft(payload(uniqueImmat()));

            assertThat(aircrafts.listAircraft(null))
                    .as("the real fleet must be absent from a demo visitor's list")
                    .extracting(li -> li.id().toString())
                    .contains(ownRow.id().toString())
                    .doesNotContain(realRow.id().toString());
            assertThat(aircrafts.listAircraft(null))
                    .as("a demo visitor must not read a real immatriculation")
                    .extracting(AircraftListItem::immatriculation)
                    .doesNotContain(realRow.immatriculation());
            assertThat(aircrafts.listAircraftForPicker())
                    .extracting(AircraftPickerItem::immatriculation)
                    .doesNotContain(realRow.immatriculation());
            assertThatThrownBy(() -> aircrafts.getAircraft(realRow.id()))
                    .isInstanceOf(AircraftNotFoundException.class);
        });
    }

    @Test
    void register_persists_managing_club_id_from_resolver() {
        TenantTestContext.runAs(clubA, () -> {
            AircraftDetail row = aircrafts.registerAircraft(payload(uniqueImmat()));
            Integer matches = jdbc.queryForObject(
                    "SELECT count(*) FROM t_aircraft WHERE id = ?::uuid AND managing_club_id = ?::uuid",
                    Integer.class, row.id().value().toString(), clubA.toString());
            assertThat(matches).isEqualTo(1);
        });
    }

    @Test
    void immatriculation_uniqueness_is_global_across_tenants() {
        TenantTestContext.runAs(clubA, () -> {
            String shared = uniqueImmat();
            aircrafts.registerAircraft(payload(shared));
            TenantTestContext.runAs(clubB, () ->
                    assertThatThrownBy(() -> aircrafts.registerAircraft(payload(shared)))
                            .isInstanceOf(DuplicateImmatriculationException.class));
        });
    }

    @Test
    void register_without_tenant_context_throws_illegalState() {
        assertThatThrownBy(() -> aircrafts.registerAircraft(payload(uniqueImmat())))
                .as("the service fails closed on its own, not only behind the controller's role gate")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant context");
    }

    private static AircraftCreateRequest payload(String immatriculation) {
        return new AircraftCreateRequest(
                AircraftTypeId.of(UUID.fromString(AircraftsTestFixtures.SEED_AIRCRAFT_TYPE_GLIDER)),
                immatriculation,
                "Schleicher", "ASK-21",
                null, null, null, null,
                null, null, null, 2,
                null, null, null, null,
                true, true, true, false,
                "IT_ATI fixture",
                null);
    }

    private static String uniqueImmat() {
        return AircraftsTestFixtures.uniqueImmatriculation();
    }
}
